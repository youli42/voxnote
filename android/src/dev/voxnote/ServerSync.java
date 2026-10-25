package dev.voxnote;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * App ↔ voxnote server 同步客户端（协议见 server/README.md「与 App / S1 配对」）：
 *
 *   GET  /api/v1/sync/ping                     连通性（Bearer 配对令牌）
 *   POST /api/v1/sync/upload                   音频 + meta（multipart），幂等键 stamp
 *   GET  /api/v1/sync/records?since=           增量枚举（游标 = updated_at）
 *   GET  /api/v1/sync/records/{id}/transcript  生效转写稿（JSONL）
 *   POST /api/v1/sync/records/{id}/transcribe  触发服务器（重）转写
 *   GET/POST /api/v1/sync/asr-config           读 / 改服务器转写配置（引擎、Whisper 模型）
 *
 * 方向：推（把本地音频传上服务器）+ 拉（把服务器转好的稿子回填成本地 <stamp>.md）。
 * 回填规则：本地没有文稿的直接写；服务器回填过的场次有更新（含转写进行中的增量）就覆盖重写；
 * 本地自己转出来的稿子可能是用户改过的，绝不覆盖。
 * 记录对应关系：上传时上报本机设备名（device），服务器返回的真实 id 若与本地不同
 * （同秒撞车加 -2 后缀）记成 srvid.<本地id> 映射，拉取时按映射对回，不会错拿别的设备的稿子。
 *
 * 全部请求是阻塞式 HTTP（java.net，无第三方依赖），跑在自己的单线程 executor 上，
 * 回调切回主线程。文件大小/改动时间没变过的场次跳过，不重复上传。
 */
public class ServerSync {

    /** err 非空 = 失败原因；msg = 人类可读结果；changed = 数据有变化（调用方需要重扫）。 */
    public interface ResultCallback {
        void onResult(String err, String msg, boolean changed);
    }

    /** 服务器拉回的元数据差异，宿主在主线程落库（标签按名对上/新建本地分类，标题 patch 用户态）。
     *  每条 = [记录id, 标签名, 标题]：空串表示该项没变化。传 null = 不关心元数据同步。 */
    public interface MetaApplier {
        void apply(ArrayList<String[]> updates);
    }

    private MetaApplier metaApplier;

    public void setMetaApplier(MetaApplier applier) {
        metaApplier = applier;
    }

    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
    private static final String[] AUDIO_EXTS = {
            ".m4a", ".mp4", ".aac", ".wav", ".mp3", ".amr", ".ogg", ".3gp"};

    private final Context ctx;
    private final UiSettings s;
    private final Storage storage;
    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** ping / 拉配置 / 拉库列表等轻量请求走这条队列：不等大文件上传，
     *  「测试连接」才不会在同步进行中卡到上传结束才反馈。 */
    private final ExecutorService ioLight = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ServerSync(Context c, UiSettings settings, Storage storage) {
        this.ctx = c.getApplicationContext();
        this.s = settings;
        this.storage = storage;
        this.prefs = ctx.getSharedPreferences("voxnote.sync", Context.MODE_PRIVATE);
    }

    public boolean configured() {
        return normalizeBase(s.serverUrl).length() > 0;
    }

    /** 设置页的一行状态：未配置 / 上次同步时间 / 上次失败原因。 */
    public String statusLine() {
        if (!configured()) return "未配置";
        String err = prefs.getString("lastError", "");
        if (err.length() > 0) return "上次同步失败：" + err;
        long last = prefs.getLong("lastSyncAt", 0);
        if (last == 0) return "已配置 · 尚未同步";
        synchronized (FMT) {
            return "上次同步 " + FMT.format(new Date(last));
        }
    }

    public void shutdown() {
        try {
            io.shutdownNow();
        } catch (Throwable ignore) { }
        try {
            ioLight.shutdownNow();
        } catch (Throwable ignore) { }
        ui.removeCallbacksAndMessages(null);
    }

    // ------------------------------------------------------------ 对外（异步）

    /** 测试连通性；msg = 延迟 "xxx ms"。走轻量队列，不等上传。 */
    public void ping(final ResultCallback cb) {
        if (!configured()) {
            deliver(cb, "未配置服务器地址", "", false);
            return;
        }
        ioLight.execute(new Runnable() {
            public void run() {
                try {
                    deliver(cb, null, doPing(), true);
                } catch (Throwable t) {
                    deliver(cb, readable(t), "", false);
                }
            }
        });
    }

    /**
     * 同步一轮：先推（本地音频 → 服务器），再拉（服务器转写稿 → 本地 md）。
     * items 传当前列表快照（录完自动上传时由重扫后的 applyResult 触发，快照里就有新场次）。
     */
    public void syncNow(final ArrayList<RecordVm> items, final ResultCallback cb) {
        if (!configured()) {
            deliver(cb, "未配置服务器地址", "", false);
            return;
        }
        if (!running.compareAndSet(false, true)) {
            deliver(cb, "上一次同步还没跑完", "", false);
            return;
        }
        final ArrayList<RecordVm> snapshot =
                items == null ? new ArrayList<RecordVm>() : new ArrayList<RecordVm>(items);
        // 分类名快照要在 UI 线程拍：IO 线程里直接遍历 categories 会与 UI 线程
        // 的增删（标签回填新建分类/设置页管理）并发抛 CME。
        final HashMap<String, String> catNames = s.catNameSnapshot();
        io.execute(new Runnable() {
            public void run() {
                String err = null;
                int pushed = 0, pulled = 0, cats = 0;
                ArrayList<String[]> catUpdates = new ArrayList<String[]>();
                try {
                    lastPushError = "";
                    int[] p = push(snapshot, catNames);
                    pushed = p[0];
                    if (p[1] > 0) err = "上传失败 " + p[1] + " 场" + (lastPushError.length() > 0 ? "：" + lastPushError : "");
                    try {
                        int[] r = pull(snapshot, catNames, catUpdates);
                        pulled = r[0];
                        cats = r[1];
                    } catch (Throwable t) {
                        err = err == null ? readable(t) : err;
                    }
                    if (err == null) {
                        prefs.edit().putLong("lastSyncAt", System.currentTimeMillis())
                                .putString("lastError", "").apply();
                    } else {
                        prefs.edit().putString("lastError", err).apply();
                    }
                } catch (Throwable t) {
                    err = readable(t);
                    prefs.edit().putString("lastError", err).apply();
                } finally {
                    running.set(false);
                }
                if (cats > 0) applyMeta(catUpdates);
                String msg = "上传 " + pushed + " 场 · 回填 " + pulled + " 场"
                        + (cats > 0 ? " · 标签 " + cats + " 场" : "");
                deliver(cb, err, msg, pushed + pulled + cats > 0);
            }
        });
    }

    // ------------------------------------------------------------ 推：上传音频

    /** 快照里查分类名（id 不在快照 = 快照后本地删了该分类，视同未分类）。 */
    private static String nameOf(Map<String, String> catNames, String catId) {
        if (catId == null) return "";
        String n = catNames.get(catId);
        return n == null ? "" : n;
    }

    /** 返回 {成功数, 失败数}；单个失败不中断整轮，原因记在 lastPushError。 */
    private int[] push(ArrayList<RecordVm> items, Map<String, String> catNames) {
        int ok = 0, fail = 0;
        for (int i = 0; i < items.size(); i++) {
            RecordVm vm = items.get(i);
            if (!vm.hasAudio()) continue;
            String marker = markerOf(vm);
            if (marker.length() == 0) continue;
            if (marker.equals(prefs.getString("up." + vm.id, ""))) continue;
            try {
                pushOne(vm, catNames);
                ok++;
            } catch (Throwable t) {
                fail++;
                lastPushError = readable(t);
            }
        }
        return new int[]{ok, fail};
    }

    private String lastPushError = "";

    /** 传一场的音频；成功记 up 标记并清失败原因，失败把原因记到 uperr（卡片/详情显示用）。
     *  返回服务器端的真实记录 id（同秒撞车时与本地 id 不同，映射同时记进 srvid）。 */
    private String pushOne(RecordVm vm, Map<String, String> catNames) throws Exception {
        String ext = extOf(vm);
        if (ext.length() == 0) throw new java.io.IOException("不支持的音频格式");
        InputStream in = openAudio(vm);
        if (in == null) throw new java.io.IOException("打不开音频文件");
        try {
            JSONObject meta = new JSONObject();
            meta.put("stamp", vm.id);
            meta.put("title", vm.title == null ? "" : vm.title);
            meta.put("cat", nameOf(catNames, vm.cat));
            meta.put("mode", vm.mode == null ? "" : vm.mode);
            meta.put("sid", vm.sid);
            meta.put("device", deviceName());
            meta.put("recorded_at", vm.startMs);
            meta.put("dur_ms", vm.durMs);
            String resp = postMultipart(api() + "/upload", meta.toString(), vm.id + ext, in);
            // 服务器可能给这条记录换了 id（同秒撞车加 -2 后缀）：记下映射，
            // 拉取时按映射对回本地记录，否则会拿别的设备的稿子错位回填。
            String srvId = vm.id;
            try {
                String sid = new JSONObject(resp).optString("id", "");
                if (sid.length() > 0) srvId = sid;
            } catch (Throwable ignore) { }
            if (!srvId.equals(vm.id)) {
                prefs.edit().putString("srvid." + vm.id, srvId).apply();
            }
            prefs.edit().putString("up." + vm.id, markerOf(vm)).remove("uperr." + vm.id).apply();
            return srvId;
        } catch (Throwable t) {
            prefs.edit().putString("uperr." + vm.id, readable(t)).apply();
            throw t;
        } finally {
            try { in.close(); } catch (Throwable ignore) { }
        }
    }

    // ------------------------------------------------------------ 同步状态（卡片/详情显示）

    public static final int SYNC_NONE = 0;   // 无音频或未配服务器：不显示
    public static final int SYNC_OK = 1;     // 已上传且文件没再变
    public static final int SYNC_WAIT = 2;   // 还没传过（或文件变了要重传）
    public static final int SYNC_FAIL = 3;   // 上次上传失败（uperr 里有原因）

    /** 这场的同步状态。markerOf 要读文件 stat，别在滚动路径里逐帧调——
     *  宿主在数据变化后整批算进 map，列表 bind 只查 map。 */
    public int syncState(RecordVm vm) {
        if (!configured() || !vm.hasAudio()) return SYNC_NONE;
        String marker = markerOf(vm);
        if (marker.length() == 0) return SYNC_NONE;
        if (marker.equals(prefs.getString("up." + vm.id, ""))) return SYNC_OK;
        return prefs.getString("uperr." + vm.id, "").length() > 0 ? SYNC_FAIL : SYNC_WAIT;
    }

    /** 上次上传失败的原因（没失败过返回空）。 */
    public String syncError(String id) {
        return prefs.getString("uperr." + id, "");
    }

    /** 设置页状态行用：{已同步, 待上传, 失败} 按当前列表快照计数。 */
    public int[] syncCounts(ArrayList<RecordVm> items) {
        int ok = 0, wait = 0, fail = 0;
        for (int i = 0; i < items.size(); i++) {
            int st = syncState(items.get(i));
            if (st == SYNC_OK) ok++;
            else if (st == SYNC_WAIT) wait++;
            else if (st == SYNC_FAIL) fail++;
        }
        return new int[]{ok, wait, fail};
    }

    /** 重传一场失败的音频（详情页「重传」按钮）；成功后转写稿仍由轮询/同步回填。 */
    public void retryUpload(final RecordVm vm, final ResultCallback cb) {
        if (!configured()) {
            deliver(cb, "未配置服务器地址", "", false);
            return;
        }
        // 分类名快照要在 UI 线程拍（同 syncNow 的并发说明）
        final HashMap<String, String> catNames = s.catNameSnapshot();
        io.execute(new Runnable() {
            public void run() {
                String err = null;
                try {
                    if (!vm.hasAudio()) throw new java.io.IOException("这场没有音频文件");
                    pushOne(vm, catNames);
                } catch (Throwable t) {
                    err = readable(t);
                }
                deliver(cb, err, err == null ? "已重新上传" : "", err == null);
            }
        });
    }

    /** 让服务器（重）转写一场：还没传过（或文件变了）先补传，再 POST 触发入队。
     *  引擎留空 = 服务器配置的默认引擎；新稿转好后由 pollLive/同步增量回填。 */
    public void transcribeRemote(final RecordVm vm, final ResultCallback cb) {
        if (!configured()) {
            deliver(cb, "未配置服务器地址", "", false);
            return;
        }
        final HashMap<String, String> catNames = s.catNameSnapshot();
        io.execute(new Runnable() {
            public void run() {
                String err = null, msg = "";
                try {
                    String marker = markerOf(vm);
                    boolean uploaded = marker.length() > 0
                            && marker.equals(prefs.getString("up." + vm.id, ""));
                    if (!uploaded) pushOne(vm, catNames);
                    // 服务器真实 id：push 记过映射用映射，否则同名直连
                    String srvId = prefs.getString("srvid." + vm.id, vm.id);
                    httpPost(api() + "/records/" + srvId + "/transcribe", "{}");
                    msg = uploaded ? "已请求服务器转写" : "已上传并请求转写";
                } catch (Throwable t) {
                    err = readable(t);
                }
                deliver(cb, err, msg, err == null);
            }
        });
    }

    // ------------------------------------------------------------ 服务器转写配置（设置页）

    /** GET /asr-config 的结果：引擎清单（含可用/停用）与 Whisper 模型清单。 */
    public static class AsrConfig {
        public String defaultEngine = "";
        public String whisperModel = "";
        public final ArrayList<EngineInfo> engines = new ArrayList<EngineInfo>();
        public final ArrayList<WhisperModel> whisperModels = new ArrayList<WhisperModel>();

        public static class EngineInfo {
            public String id = "";
            public String name = "";
            public boolean available;
            public boolean enabled;
        }

        public static class WhisperModel {
            public String name = "";
            public boolean installed;
            public String sizeHint = "";
        }
    }

    public interface AsrConfigCallback {
        void onDone(String err, AsrConfig cfg);
    }

    public void fetchAsrConfig(final AsrConfigCallback cb) {
        if (!configured()) {
            ui.post(new Runnable() { public void run() { cb.onDone("未配置服务器地址", null); } });
            return;
        }
        ioLight.execute(new Runnable() {
            public void run() {
                try {
                    JSONObject root = new JSONObject(httpGet(api() + "/asr-config"));
                    final AsrConfig cfg = new AsrConfig();
                    cfg.defaultEngine = root.optString("default_engine", "");
                    cfg.whisperModel = root.optString("whisper_model", "");
                    JSONArray es = root.optJSONArray("engines");
                    if (es != null) {
                        for (int i = 0; i < es.length(); i++) {
                            JSONObject o = es.optJSONObject(i);
                            if (o == null) continue;
                            AsrConfig.EngineInfo e = new AsrConfig.EngineInfo();
                            e.id = o.optString("id", "");
                            if (e.id.length() == 0) continue;
                            e.name = o.optString("name", e.id);
                            e.available = o.optBoolean("available", false);
                            e.enabled = o.optBoolean("enabled", true);
                            cfg.engines.add(e);
                        }
                    }
                    JSONArray ms = root.optJSONArray("whisper_models");
                    if (ms != null) {
                        for (int i = 0; i < ms.length(); i++) {
                            JSONObject o = ms.optJSONObject(i);
                            if (o == null) continue;
                            AsrConfig.WhisperModel m = new AsrConfig.WhisperModel();
                            m.name = o.optString("name", "");
                            if (m.name.length() == 0) continue;
                            m.installed = o.optBoolean("installed", false);
                            m.sizeHint = o.optString("size_hint", "");
                            cfg.whisperModels.add(m);
                        }
                    }
                    ui.post(new Runnable() { public void run() { cb.onDone(null, cfg); } });
                } catch (final Throwable t) {
                    ui.post(new Runnable() { public void run() { cb.onDone(readable(t), null); } });
                }
            }
        });
    }

    /** 改服务器转写配置：engine / whisperModel 传空串表示该项不改。 */
    public void setAsrConfig(final String engine, final String whisperModel, final ResultCallback cb) {
        if (!configured()) {
            deliver(cb, "未配置服务器地址", "", false);
            return;
        }
        ioLight.execute(new Runnable() {
            public void run() {
                String err = null;
                try {
                    JSONObject p = new JSONObject();
                    if (engine != null && engine.length() > 0) p.put("default_engine", engine);
                    if (whisperModel != null && whisperModel.length() > 0) {
                        p.put("whisper_model", whisperModel);
                    }
                    httpPost(api() + "/asr-config", p.toString());
                } catch (Throwable t) {
                    err = readable(t);
                }
                deliver(cb, err, err == null ? "已保存" : "", err == null);
            }
        });
    }

    private InputStream openAudio(RecordVm vm) {
        try {
            if (vm.audioUri != null && vm.audioUri.length() > 0) {
                return ctx.getContentResolver().openInputStream(Uri.parse(vm.audioUri));
            }
            return new FileInputStream(vm.audioPath);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 上传去重标记：文件看 size+mtime，content:// 记 uri。 */
    private static String markerOf(RecordVm vm) {        if (vm.audioPath != null && vm.audioPath.length() > 0) {
            File f = new File(vm.audioPath);
            return f.length() + ":" + f.lastModified();
        }
        return String.valueOf(vm.audioUri);
    }

    /** 上报给服务器的本机设备名：设置页可自设；留空自动取系统设备名，再退回手机型号。 */
    private String deviceName() {
        String n = s.deviceName == null ? "" : s.deviceName.trim();
        if (n.length() > 0) return n;
        return autoDeviceName();
    }

    /** 不看用户设置、直接从系统取的设备名（设置页提示也用它）。
     *  Settings.Global.DEVICE_NAME 是"关于手机→设备名称"里用户起的名字；
     *  部分机型只把蓝牙名落在 Secure 表里，兜底取一下；都没有再用型号。 */
    public String autoDeviceName() {
        String n = systemDeviceName();
        if (n.length() > 0) return n;
        try {
            n = android.os.Build.MODEL;
        } catch (Throwable t) {
            n = "";
        }
        return n == null ? "" : n;
    }

    private String systemDeviceName() {
        // Global 表存"关于手机→设备名称"；部分机型只把蓝牙名落在 Secure 表，兜底取一下
        String n = readSetting(true, android.provider.Settings.Global.DEVICE_NAME);
        if (n.length() == 0) n = readSetting(false, "bluetooth_name");
        return n;
    }

    private String readSetting(boolean global, String key) {
        try {
            String n = global
                    ? android.provider.Settings.Global.getString(ctx.getContentResolver(), key)
                    : android.provider.Settings.Secure.getString(ctx.getContentResolver(), key);
            return n == null ? "" : n.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private String extOf(RecordVm vm) {
        String name = null;
        if (vm.audioPath != null && vm.audioPath.length() > 0) {
            name = new File(vm.audioPath).getName();
        } else if (vm.audioUri != null && vm.audioUri.length() > 0) {
            name = queryDisplayName(vm.audioUri);
        }
        if (name == null) return "";
        String ln = name.toLowerCase(Locale.US);
        for (int i = 0; i < AUDIO_EXTS.length; i++) {
            if (ln.endsWith(AUDIO_EXTS[i])) return AUDIO_EXTS[i];
        }
        return "";
    }

    private String queryDisplayName(String uri) {
        try {
            android.database.Cursor c = ctx.getContentResolver()
                    .query(Uri.parse(uri), null, null, null, null);
            if (c == null) return null;
            try {
                if (c.moveToFirst()) {
                    int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (i >= 0) return c.getString(i);
                }
            } finally {
                c.close();
            }
        } catch (Throwable ignore) { }
        return null;
    }

    // ------------------------------------------------------------ 拉：转写稿回填 + 标签回填

    /** 服务器有稿（done）或正在转写（asr，内容边长边推）的场次：
     *  本地没有文稿就回填；上次就是服务器回填过的（tr 标记存在）且服务器有更新则覆盖重写。
     *  本地自己转出来的稿子（无 tr 标记）绝不动。
     *  记录对回本地：先按 push 时记下的 id 映射（srvid.<本地id> = 服务器id，同秒撞车
     *  时服务器会给 -2 后缀），再按同名 id 兜底——但已被映射占走的本地记录不再参与
     *  兜底，否则撞车后两台设备同名记录会互相错拿对方的稿子。
     *  回填文稿、tr/cat 持久标记、标签差异上报全都按本地 id 记。
     *  同时收集标签差异（服务器标签名 ≠ 本地标签名）到 catUpdates，由宿主落库——
     *  只应用服务器的非空标签；空标签不清本地（可能是服务器侧从没打过的）。
     *  差异同时记成持久标记 cat.<id>（宿主落库成功后清），补扫让滑出增量窗口
     *  的更新也能重试，不随游标推进丢掉。
     *  返回 {回填场次数, 标签变化场次数}。 */
    private int[] pull(ArrayList<RecordVm> items, Map<String, String> catNames,
                       ArrayList<String[]> catUpdates) throws Exception {
        long since = prefs.getLong("cursor", 0);
        JSONObject root = new JSONObject(httpGet(api() + "/records?since=" + since));
        JSONArray recs = root.optJSONArray("records");
        if (recs == null) return new int[]{0, 0};

        Map<String, RecordVm> byId = new HashMap<String, RecordVm>();
        for (int i = 0; i < items.size(); i++) byId.put(items.get(i).id, items.get(i));
        Map<String, String> bySrv = new HashMap<String, String>();   // 服务器id → 本地id
        java.util.Set<String> mappedLocal = new java.util.HashSet<String>();
        for (Map.Entry<String, ?> en : prefs.getAll().entrySet()) {
            String k = en.getKey();
            if (k.startsWith("srvid.")) {
                bySrv.put(String.valueOf(en.getValue()), k.substring(6));
                mappedLocal.add(k.substring(6));
            }
        }

        long cursor = since;
        long processed = since;   // 游标只推进到"已处理完"的记录：
        int n = 0, nCat = 0;      // 被本地稿子保护跳过的场次仍留在窗口里，
        File mdDir = storage.privateRecords();  // 本地稿子被删后下一轮还能补拉。
        SharedPreferences.Editor ed = prefs.edit();
        for (int i = 0; i < recs.length(); i++) {
            JSONObject o = recs.optJSONObject(i);
            if (o == null) continue;
            String srvId = o.optString("id", "");
            long upd = o.optLong("updated_at", 0);
            String status = o.optString("status", "");
            RecordVm vm = null;
            String mapped = bySrv.get(srvId);
            if (mapped != null) vm = byId.get(mapped);
            if (vm == null) {
                RecordVm cand = byId.get(srvId);
                if (cand != null && !mappedLocal.contains(cand.id)) vm = cand;
            }
            if (vm == null) {                      // 本地没有这场（可能已删 / 别的设备传的）
                if (upd > processed) processed = upd;
                continue;
            }
            String id = vm.id;                     // 本地 id：md 文件名与持久标记都按它
            // 标签回填：与转写回填独立，本地稿子受保护也不影响
            String srvCat = o.optString("cat", "");
            if (srvCat.length() > 0 && !srvCat.equals(nameOf(catNames, vm.cat))) {
                catUpdates.add(new String[]{id, srvCat, ""});
                ed.putString("cat." + id, srvCat);  // 落库成功才清（markCatDone）
                nCat++;
            }
            if (!"done".equals(status) && !"asr".equals(status)) {
                // raw/queued/fail：出不了稿，不用再看
                if (upd > processed) processed = upd;
                continue;
            }
            long lastTr = prefs.getLong("tr." + id, 0);
            if (lastTr >= upd) {                   // 转写没有新变化
                if (upd > processed) processed = upd;
                continue;
            }
            if (vm.hasMd() && !fromServerMd(vm.mdPath)) continue; // 本地自己的稿子不动
            File md = new File(mdDir, id + ".md");
            if (writeTranscriptMd(md, o.optString("title", ""),
                    httpGet(api() + "/records/" + id + "/transcript"))) {
                ed.putLong("tr." + id, upd);       // 写成功才记，空结果下轮再来
                if (upd > processed) processed = upd;
                n++;
            }
        }
        ed.apply();
        if (processed > since) prefs.edit().putLong("cursor", processed).apply();
        nCat += rescanPendingCats(byId, catNames, catUpdates);
        return new int[]{n, nCat};
    }

    /** 补扫待落库的标签标记 cat.<id>=<服务器标签>：游标已经推过去（窗口里看不到）
     *  的差异靠这里重试；本地记录已删、或已经对上的标记清掉。
     *  返回本轮补收的场次数。 */
    private int rescanPendingCats(Map<String, RecordVm> byId, Map<String, String> catNames,
                                  ArrayList<String[]> catUpdates) {
        Map<String, ?> all = prefs.getAll();
        SharedPreferences.Editor ed = null;
        int added = 0;
        for (Map.Entry<String, ?> en : all.entrySet()) {
            String k = en.getKey();
            if (!k.startsWith("cat.") || !(en.getValue() instanceof String)) continue;
            String id = k.substring(4);
            String tag = (String) en.getValue();
            boolean listed = false;
            for (int i = 0; i < catUpdates.size(); i++) {
                if (catUpdates.get(i)[0].equals(id)) { listed = true; break; }
            }
            if (listed) continue;                  // 本轮已收集，标记等宿主落库后清
            RecordVm vm = byId.get(id);
            if (vm == null) {                      // 本地已删这场：标记作废
                if (ed == null) ed = prefs.edit();
                ed.remove(k);
                continue;
            }
            if (tag.equals(nameOf(catNames, vm.cat))) {
                if (ed == null) ed = prefs.edit(); // 已经落上（上轮 patch 生效）
                ed.remove(k);
            } else {
                catUpdates.add(new String[]{id, tag, ""});
                added++;
            }
        }
        if (ed != null) ed.apply();
        return added;
    }

    /** 标签已在本地落库（CatApplier 的 patch 回调里调）：清持久标记。 */
    public void markCatDone(String recordId) {
        prefs.edit().remove("cat." + recordId).apply();
    }

    // ------------------------------------------------------------ 云端页：服务器库浏览与取回

    /** 服务器记录库里的一条（/sync/records 的全量枚举）。 */
    public static class ServerRec {
        public String id;
        public String title = "";
        public String cat = "";
        public String device = "";
        public String status = "";
        public String audioName = "";
        public String preview = "";   // 生效转写稿开头一段（云端页几行预览）
        public long durMs;
        public long bytes;
        public long updatedAt;

        /** 记录 id 前 15 位是 yyyyMMdd_HHmmss，可当录音开始时间。 */
        public long startMs() {
            try {
                synchronized (FMT) {
                    java.text.SimpleDateFormat key =
                            new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
                    java.util.Date d = key.parse(id);
                    return d == null ? 0 : d.getTime();
                }
            } catch (Throwable t) {
                return 0;
            }
        }
    }

    public interface LibraryListCallback {
        void onDone(String err, ArrayList<ServerRec> recs);
    }

    public interface TextCallback {
        void onDone(String err, String text);
    }

    /** 拉服务器全库列表（增量端点的 since=0 形态，500 条上限，云端页够用）。 */
    public void fetchLibrary(final LibraryListCallback cb) {
        if (!configured()) {
            ui.post(new Runnable() { public void run() { cb.onDone("未配置服务器地址", null); } });
            return;
        }
        ioLight.execute(new Runnable() {
            public void run() {
                try {
                    JSONObject root = new JSONObject(httpGet(api() + "/records?since=0"));
                    JSONArray recs = root.optJSONArray("records");
                    ArrayList<ServerRec> out = new ArrayList<ServerRec>();
                    if (recs != null) {
                        for (int i = 0; i < recs.length(); i++) {
                            JSONObject o = recs.optJSONObject(i);
                            if (o == null) continue;
                            ServerRec r = new ServerRec();
                            r.id = o.optString("id", "");
                            if (r.id.length() == 0) continue;
                            r.title = o.optString("title", "");
                            r.cat = o.optString("cat", "");
                            r.device = o.optString("device", "");
                            r.status = o.optString("status", "");
                            r.audioName = o.optString("audio_name", "");
                            r.preview = o.optString("preview", "");
                            r.durMs = o.optLong("dur_ms", 0);
                            r.bytes = o.optLong("bytes", 0);
                            r.updatedAt = o.optLong("updated_at", 0);
                            out.add(r);
                        }
                    }
                    ui.post(new Runnable() {
                        public void run() { cb.onDone(null, out); }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() { public void run() { cb.onDone(readable(t), null); } });
                }
            }
        });
    }

    /** 拉一场的生效转写稿（JSONL 原文，云端页查看用）。 */
    public void fetchTranscript(final String srvId, final TextCallback cb) {
        if (!configured()) {
            ui.post(new Runnable() { public void run() { cb.onDone("未配置服务器地址", null); } });
            return;
        }
        ioLight.execute(new Runnable() {
            public void run() {
                try {
                    final String text = httpGet(api() + "/records/" + srvId + "/transcript");
                    ui.post(new Runnable() { public void run() { cb.onDone(null, text); } });
                } catch (final Throwable t) {
                    ui.post(new Runnable() { public void run() { cb.onDone(readable(t), null); } });
                }
            }
        });
    }

    /** 从服务器取回一场记录到本机：写文稿 md（<服务器id>.md，与同步回填同一目录），
     *  可选下载音频。下载的音频会被记成"已上传"（up.<id> 标记），同步时不会再推回去。
     *  本地 id = 服务器 id（含 -2 后缀，Library 认这种文件名）。 */
    public void importFromServer(final ServerRec rec, final boolean withAudio,
                                 final ResultCallback cb) {
        if (!configured()) {
            deliver(cb, "未配置服务器地址", "", false);
            return;
        }
        if (!running.compareAndSet(false, true)) {
            deliver(cb, "上一次同步还没跑完", "", false);
            return;
        }
        io.execute(new Runnable() {
            public void run() {
                String err = null;
                try {
                    File mdDir = storage.privateRecords();
                    String jsonl = httpGet(api() + "/records/" + rec.id + "/transcript");
                    if (jsonl.trim().length() > 0) {
                        writeTranscriptMd(new File(mdDir, rec.id + ".md"), rec.title, jsonl);
                    } else if (!withAudio) {
                        err = "服务器还没有转写稿";
                    }
                    if (err == null && withAudio) {
                        String ext = extOfName(rec.audioName);
                        if (ext.length() == 0) ext = ".m4a";
                        File dest = new File(mdDir, rec.id + ext);
                        File tmp = new File(mdDir, "." + rec.id + ext + ".part");
                        httpDownload(api() + "/records/" + rec.id + "/audio", tmp);
                        if (dest.exists()) dest.delete();
                        if (!tmp.renameTo(dest)) {
                            tmp.delete();
                            throw new java.io.IOException("音频文件保存失败");
                        }
                        // 与 push 的 markerOf 同格式：size:mtime —— 有这个标记就不再推回服务器
                        prefs.edit().putString("up." + rec.id,
                                dest.length() + ":" + dest.lastModified()).apply();
                    }
                    if (err == null) {
                        prefs.edit().putLong("tr." + rec.id, rec.updatedAt).apply();
                    }
                } catch (Throwable t) {
                    err = readable(t);
                } finally {
                    running.set(false);
                }
                deliver(cb, err, err == null ? (withAudio ? "已下载音频和文稿" : "已保存文稿")
                        : "", err == null);
            }
        });
    }

    /** 云端页判断：这条服务器记录是否已在本机（同名 id，或 push 时被映射过）。 */
    public boolean isLocalCopy(String srvId, java.util.Set<String> localIds) {
        if (localIds != null && localIds.contains(srvId)) return true;
        for (Map.Entry<String, ?> en : prefs.getAll().entrySet()) {
            if (en.getKey().startsWith("srvid.") && srvId.equals(String.valueOf(en.getValue()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 云端页的默认取回：把服务器库里本机没有的场次拉成本地文稿（只文稿不音频，
     * 与「保存文稿到本机」同一落点），并同步服务器上改过的标题/标签。
     *
     * 标题冲突规则（契约「本地人写的永不被覆盖」的延伸）：服务器改名只在本地
     * 没动过标题（仍是默认标题，或就是上次从服务器同步来的那个名字）时才应用；
     * 本地自己改过的名字不动。上次同步来的服务器标题记在 stl.<id>。
     *
     * 本机已有的场次：标题按上面的规则同步；标签差异与转写稿增量由 pull() 负责，
     * 这里不重复拉转写（避免整库逐条 GET）。拉回的新场次 tr.<id> 记到 updated_at，
     * 之后转写更新仍走增量回填；不设 up 标记——没有音频，push 自然跳过。
     */
    public void syncCloud(final ArrayList<RecordVm> items, final ResultCallback cb) {
        if (!configured()) {
            deliver(cb, "未配置服务器地址", "", false);
            return;
        }
        if (!running.compareAndSet(false, true)) {
            deliver(cb, "上一次同步还没跑完", "", false);
            return;
        }
        final ArrayList<RecordVm> snapshot =
                items == null ? new ArrayList<RecordVm>() : new ArrayList<RecordVm>(items);
        final HashMap<String, String> catNames = s.catNameSnapshot();  // UI 线程拍快照（同 syncNow）
        io.execute(new Runnable() {
            public void run() {
                int imported = 0, meta = 0;
                String err = null;
                ArrayList<String[]> metaUpdates = new ArrayList<String[]>();
                try {
                    JSONObject root = new JSONObject(httpGet(api() + "/records?since=0"));
                    JSONArray recs = root.optJSONArray("records");
                    if (recs == null) recs = new JSONArray();

                    Map<String, RecordVm> byId = new HashMap<String, RecordVm>();
                    for (int i = 0; i < snapshot.size(); i++) byId.put(snapshot.get(i).id, snapshot.get(i));
                    Map<String, String> bySrv = new HashMap<String, String>();
                    java.util.Set<String> mappedLocal = new java.util.HashSet<String>();
                    for (Map.Entry<String, ?> en : prefs.getAll().entrySet()) {
                        String k = en.getKey();
                        if (k.startsWith("srvid.")) {
                            bySrv.put(String.valueOf(en.getValue()), k.substring(6));
                            mappedLocal.add(k.substring(6));
                        }
                    }

                    File mdDir = storage.privateRecords();
                    SharedPreferences.Editor ed = prefs.edit();
                    for (int i = 0; i < recs.length(); i++) {
                        JSONObject o = recs.optJSONObject(i);
                        if (o == null) continue;
                        String srvId = o.optString("id", "");
                        if (srvId.length() == 0) continue;
                        long upd = o.optLong("updated_at", 0);
                        String status = o.optString("status", "");
                        String srvTitle = o.optString("title", "");
                        String srvCat = o.optString("cat", "");
                        RecordVm vm = null;
                        String mapped = bySrv.get(srvId);
                        if (mapped != null) vm = byId.get(mapped);
                        if (vm == null) {
                            RecordVm cand = byId.get(srvId);
                            if (cand != null && !mappedLocal.contains(cand.id)) vm = cand;
                        }
                        if (vm == null) {
                            // ---- 本机没有这场：默认拉成本地文稿（只拉有稿的）
                            if (!"done".equals(status) && !"asr".equals(status)) continue;
                            if (prefs.getLong("tr." + srvId, 0) < upd) {
                                String jsonl = httpGet(api() + "/records/" + srvId + "/transcript");
                                if (writeTranscriptMd(new File(mdDir, srvId + ".md"), srvTitle, jsonl)) {
                                    ed.putLong("tr." + srvId, upd);
                                    imported++;
                                }
                            }
                            if (srvCat.length() > 0) {
                                metaUpdates.add(new String[]{srvId, srvCat, ""});
                                ed.putString("cat." + srvId, srvCat);
                                meta++;
                            }
                            if (applyServerTitle(srvId, srvTitle, "", metaUpdates, ed)) meta++;
                        } else {
                            // ---- 本机已有：只同步元数据（转写走增量 pull，不整库重拉）
                            String id = vm.id;
                            if (srvCat.length() > 0 && !srvCat.equals(nameOf(catNames, vm.cat))) {
                                metaUpdates.add(new String[]{id, srvCat, ""});
                                ed.putString("cat." + id, srvCat);
                                meta++;
                            }
                            if (applyServerTitle(id, srvTitle, vm.title, metaUpdates, ed)) meta++;
                        }
                    }
                    ed.apply();
                } catch (Throwable t) {
                    err = readable(t);
                } finally {
                    running.set(false);
                }
                applyMeta(metaUpdates);
                String msg = err == null
                        ? "新回填 " + imported + " 场 · 元数据 " + meta + " 场" : "";
                deliver(cb, err, msg, imported + meta > 0);
            }
        });
    }

    /** 服务器标题同步判定：非默认标题、与上次同步来的不同、且本地没自己改过
     *  （curTitle 传空 = 新场次没有本地标题，直接收）才入队；记 stl 标记。
     *  返回是否收集了一条。 */
    private boolean applyServerTitle(String id, String srvTitle, String curTitle,
                                     ArrayList<String[]> updates, SharedPreferences.Editor ed) {
        String t = srvTitle == null ? "" : srvTitle.trim();
        if (t.length() == 0 || t.matches("^录音 \\d{1,2}:\\d{2}$")) return false;
        String last = prefs.getString("stl." + id, "");
        if (t.equals(last)) return false;
        if (curTitle.length() > 0 && !curTitle.matches("^录音 \\d{1,2}:\\d{2}$")
                && !curTitle.equals(last)) {
            return false;   // 本地自己改过名：不动
        }
        updates.add(new String[]{id, "", t});
        ed.putString("stl." + id, t);
        return true;
    }

    private static String extOfName(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        int dot = n.lastIndexOf('.');
        if (dot < 0) return "";
        String ext = n.substring(dot);
        for (int i = 0; i < AUDIO_EXTS.length; i++) {
            if (AUDIO_EXTS[i].equals(ext)) return ext;
        }
        return "";
    }

    private void httpDownload(String url, File dest) throws Exception {
        HttpURLConnection c = open(url, 600000);
        try {
            check(c, 200);
            InputStream in = c.getInputStream();
            java.io.FileOutputStream out = new java.io.FileOutputStream(dest);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                try { out.close(); } catch (Throwable ignore) { }
                try { in.close(); } catch (Throwable ignore) { }
            }
        } finally {
            c.disconnect();
        }
    }

    /** 元数据差异交给宿主（主线程）落库；宿主负责标签对上/新建、标题 patch 用户态。 */
    private void applyMeta(final ArrayList<String[]> updates) {
        if (metaApplier == null || updates.isEmpty()) return;
        ui.post(new Runnable() {
            public void run() {
                try {
                    metaApplier.apply(updates);
                } catch (Throwable ignore) { }
            }
        });
    }

    /** 轻量轮询：只拉转写稿（不推音频），供前台定时刷新 —— 服务端转写进行中时
     *  手机端能看到内容逐段长出来。返回是否回填了新内容。 */
    public void pollLive(final ArrayList<RecordVm> items, final ResultCallback cb) {
        if (!configured()) return;
        if (!running.compareAndSet(false, true)) return;   // 全量同步进行中就跳过这轮
        final ArrayList<RecordVm> snapshot =
                items == null ? new ArrayList<RecordVm>() : new ArrayList<RecordVm>(items);
        final HashMap<String, String> catNames = s.catNameSnapshot();  // 同 syncNow：UI 线程拍快照
        io.execute(new Runnable() {
            public void run() {
                int pulled = 0, cats = 0;
                String err = null;
                ArrayList<String[]> catUpdates = new ArrayList<String[]>();
                try {
                    int[] r = pull(snapshot, catNames, catUpdates);
                    pulled = r[0];
                    cats = r[1];
                } catch (Throwable t) {
                    err = readable(t);
                } finally {
                    running.set(false);
                }
                if (cats > 0) applyMeta(catUpdates);
                if (err != null) deliver(cb, err, "", false);
                else if (pulled > 0 || cats > 0)
                    deliver(cb, null,
                            (pulled > 0 ? "转写更新 " + pulled + " 场" : "")
                                    + (pulled > 0 && cats > 0 ? " · " : "")
                                    + (cats > 0 ? "标签更新 " + cats + " 场" : ""), true);
            }
        });
    }

    /** 服务器回填的稿子都落在应用私有 records 目录；厂商会话目录里的是本机转写产物。 */
    private boolean fromServerMd(String mdPath) {
        return mdPath != null && mdPath.startsWith(storage.privateRecords().getAbsolutePath());
    }

    /** JSONL（t/e 毫秒、spk、text）→ 本机 md 版式（MdParser 认识的那套）。
     *  返回是否写了文件（内容为空不落空文件）。 */
    private static boolean writeTranscriptMd(File f, String title, String jsonl) throws Exception {
        StringBuilder body = new StringBuilder();
        StringBuilder plain = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(jsonl.getBytes("UTF-8")), "UTF-8"));
        int lastSpk = Integer.MIN_VALUE;
        long lastEnd = 0;
        String line;
        try {
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0) continue;
                JSONObject o;
                try {
                    o = new JSONObject(line);
                } catch (Throwable ignore) {
                    continue;
                }
                long t = o.optLong("t", 0);
                long e = o.optLong("e", t);
                int spk = o.optInt("spk", -1);
                String text = o.optString("text", "").trim();
                if (text.length() == 0) continue;
                if (spk >= 0 && spk != lastSpk) {
                    body.append("**发言人 ").append(spk + 1).append("**\n");
                    lastSpk = spk;
                }
                body.append("`[").append(ms(t)).append(" → ").append(ms(Math.max(e, t)))
                        .append("]` ").append(text).append('\n');
                plain.append(text).append('\n');
                if (e > lastEnd) lastEnd = e;
            }
        } finally {
            try { r.close(); } catch (Throwable ignore) { }
        }
        if (body.length() == 0) return false;   // 没内容就不落空文件（转写刚起步）
        StringBuilder md = new StringBuilder();
        md.append("# ").append(title.length() > 0 ? title : f.getName().replace(".md", "")).append("\n\n");
        md.append("- 时长 ").append(RecordVm.fmtDur(lastEnd / 1000.0)).append("\n\n");
        md.append("## 正文\n\n").append(body).append("\n");
        md.append("## 纯文本\n\n```\n").append(plain).append("```\n");
        f.getParentFile().mkdirs();
        java.io.FileOutputStream out = new java.io.FileOutputStream(f);
        try {
            out.write(md.toString().getBytes("UTF-8"));
        } finally {
            try { out.close(); } catch (Throwable ignore) { }
        }
        return true;
    }

    /** 毫秒 → m:ss.SSS（MdParser 的时间戳正则认识的形态）。 */
    private static String ms(long v) {
        long s = Math.max(0, v) / 1000;
        long frac = Math.max(0, v) % 1000;
        return (s / 60) + ":" + String.format(Locale.US, "%02d.%03d", s % 60, frac);
    }

    // ------------------------------------------------------------ HTTP

    private String base() {
        return normalizeBase(s.serverUrl);
    }

    /** 同步 API 根：主机根 + /api/v1/sync（normalizeBase 只留主机根）。 */
    private String api() {
        return base() + "/api/v1/sync";
    }

    /** 允许填 http://host:8787、host:8787、带 /api/v1 或 /api/v1/sync 尾巴的都归一成主机根。 */
    public static String normalizeBase(String raw) {
        String u = raw == null ? "" : raw.trim();
        if (u.length() == 0) return "";
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/api/v1/sync")) {
            u = u.substring(0, u.length() - "/api/v1/sync".length());
        } else if (u.endsWith("/api/v1")) {
            u = u.substring(0, u.length() - "/api/v1".length());
        }
        if (u.length() == 0) return "";
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        return u;
    }

    private HttpURLConnection open(String url, int readTimeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(readTimeout);
        String token = s.serverToken == null ? "" : s.serverToken.trim();
        if (token.length() > 0) c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }

    private String doPing() throws Exception {
        long t0 = android.os.SystemClock.elapsedRealtime();
        HttpURLConnection c = open(api() + "/ping", 15000);
        try {
            check(c, 200);
        } finally {
            c.disconnect();
        }
        return (android.os.SystemClock.elapsedRealtime() - t0) + " ms";
    }

    private String httpGet(String url) throws Exception {
        HttpURLConnection c = open(url, 30000);
        try {
            check(c, 200);
            return readAll(c);
        } finally {
            c.disconnect();
        }
    }

    /** 小 JSON POST（触发转写、改服务器配置）。 */
    private String httpPost(String url, String json) throws Exception {
        HttpURLConnection c = open(url, 30000);
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream out = c.getOutputStream();
            out.write(json.getBytes("UTF-8"));
            out.close();
            check(c, 200);
            return readAll(c);
        } finally {
            c.disconnect();
        }
    }

    /** multipart 上传；返回响应体（服务器给出的真实记录 id 在里面）。 */
    private String postMultipart(String url, String metaJson, String filename, InputStream in) throws Exception {
        String boundary = "----voxnote" + System.currentTimeMillis();
        HttpURLConnection c = open(url, 120000);
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            OutputStream out = c.getOutputStream();
            write(out, "--" + boundary + "\r\n");
            write(out, "Content-Disposition: form-data; name=\"meta\"\r\n\r\n");
            write(out, metaJson + "\r\n");
            write(out, "--" + boundary + "\r\n");
            write(out, "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n");
            write(out, "Content-Type: application/octet-stream\r\n\r\n");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            write(out, "\r\n--" + boundary + "--\r\n");
            out.close();
            check(c, 200);
            return readAll(c);
        } finally {
            c.disconnect();
        }
    }

    private static void write(OutputStream out, String s) throws Exception {
        out.write(s.getBytes("UTF-8"));
    }

    /** 非 2xx 抛 IOException，401/403 给出令牌提示，400 带上服务端 detail。 */
    private static void check(HttpURLConnection c, int expect) throws Exception {
        int code = c.getResponseCode();
        if (code >= 200 && code < 300) return;
        String body = readAll(c);
        String detail = body;
        try {
            detail = new JSONObject(body).optString("detail", body);
        } catch (Throwable ignore) { }
        if (code == 401 || code == 403) {
            throw new java.io.IOException("配对令牌无效");
        }
        throw new java.io.IOException("HTTP " + code
                + (detail == null || detail.length() == 0 ? "" : "：" + abbreviate(detail, 120)));
    }

    private static String readAll(HttpURLConnection c) {
        try {
            InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) return "";
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static String abbreviate(String s, int max) {
        String t = s == null ? "" : s.trim().replace('\n', ' ');
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /** 把底层异常翻成人话。 */
    private static String readable(Throwable t) {
        if (t instanceof UnknownHostException) return "无法解析主机名";
        if (t instanceof SocketTimeoutException) return "连接超时";
        if (t instanceof java.net.ConnectException) return "连接不上（检查地址 / 是否同一网络）";
        if (t instanceof java.io.IOException) {
            String m = String.valueOf(t.getMessage());
            if (m.indexOf("Cleartext") >= 0) return "系统拦截了明文 http 请求";
            return m;
        }
        String m = String.valueOf(t.getMessage());
        return m.length() == 0 ? String.valueOf(t) : m;
    }

    private void deliver(final ResultCallback cb, final String err, final String msg, final boolean changed) {
        android.util.Log.i("voxnote.sync", err == null ? msg : "失败：" + err);
        if (cb == null) return;
        ui.post(new Runnable() {
            public void run() {
                cb.onResult(err, msg, changed);
            }
        });
    }
}
