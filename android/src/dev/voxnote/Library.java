package dev.voxnote;

import android.media.MediaMetadataRetriever;

import dev.voxnote.reader.Session;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 扫描录音目录，把"音频 + 旁车文稿"拼成一张卡片列表。
 *
 * 扫描范围（都限定在很浅的层级，避免把 43 GB 的 /sdcard 全走一遍）：
 *   1. 设置里的录音目录（默认 /sdcard/s1rec/records），最多递归 2 层
 *   2. /sdcard/<yyyy.MM>/ —— 厂商录音的固定布局（本机实测：wav + md 并排）
 *   3. /sdcard/sogou/md/<yyyy.MM>/ —— 旧 Magisk 模块的文稿镜像（常常只有头部）
 *
 * 同一场录音的多份文件按文件名前 15 位（yyyyMMdd_HHmmss）归并：
 * 谁的内容更全就用谁，缺的字段互相补。
 */
public class Library {

    public static final File STORAGE = new File("/storage/emulated/0");

    private static final Pattern P_MONTH = Pattern.compile("^\\d{4}\\.\\d{2}$");
    private static final Pattern P_KEY = Pattern.compile("^(\\d{8}_\\d{6})");
    private static final Pattern P_SID_IN_NAME = Pattern.compile("_(\\d{10})\\.[A-Za-z0-9]+$");
    private static final Pattern P_SUFFIX = Pattern.compile("_([A-Za-z]+)\\.[A-Za-z0-9]+$");

    private static final SimpleDateFormat KEY_FMT =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
    private static final SimpleDateFormat TITLE_FMT =
            new SimpleDateFormat("HH:mm", Locale.US);

    private final State state;
    private String recordDir;
    private List<Session> logSessions;
    private String lastError = "";
    private int fileCount;
    private final List<String> roots = new ArrayList<String>();

    public Library(State state, String recordDir) {
        this.state = state;
        this.recordDir = recordDir;
    }

    public void setRecordDir(String dir) {
        this.recordDir = dir;
    }

    /** 转录监控扫出来的场次，合并进同一张列表。 */
    public void setLogSessions(List<Session> sessions) {
        this.logSessions = sessions;
    }

    public String error() {
        return lastError;
    }

    /** 供 UI 显示"从哪些目录扫的"。 */
    public List<String> roots() {
        return roots;
    }

    // ---------------------------------------------------------------- 扫描

    public JSONObject refresh() {
        List<Item> items = scan();
        JSONObject out = new JSONObject();
        JSONArray arr = new JSONArray();
        int done = 0, raw = 0, failed = 0, empty = 0, withAudio = 0, sentences = 0;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            arr.put(it.toJson(state.of(it.id)));
            String st = it.status();
            if ("done".equals(st)) done++;
            else if ("raw".equals(st)) raw++;
            else if ("failed".equals(st)) failed++;
            else empty++;
            if (it.audioPath != null) withAudio++;
            sentences += it.segs.size();
        }
        try {
            JSONObject stats = new JSONObject();
            stats.put("total", items.size());
            stats.put("done", done);
            stats.put("raw", raw);
            stats.put("failed", failed);
            stats.put("empty", empty);
            stats.put("withAudio", withAudio);
            stats.put("sentences", sentences);
            stats.put("files", fileCount);
            stats.put("scannedAt", System.currentTimeMillis());
            stats.put("error", lastError);
            JSONArray rs = new JSONArray();
            for (int i = 0; i < roots.size(); i++) rs.put(roots.get(i));
            stats.put("roots", rs);
            out.put("stats", stats);
            out.put("items", arr);
        } catch (Throwable t) {
            // 保底返回空结构，JS 端会显示"没有记录"
        }
        return out;
    }

    public List<Item> scan() {
        Map<String, Item> map = new LinkedHashMap<String, Item>();
        fileCount = 0;
        lastError = "";
        roots.clear();

        Set<String> seen = new HashSet<String>();
        List<File> dirs = new ArrayList<File>();

        if (recordDir != null && recordDir.length() > 0) addDir(dirs, seen, new File(recordDir));

        File[] top = STORAGE.listFiles();
        if (top == null) {
            lastError = "读不到 " + STORAGE.getAbsolutePath() + "（缺存储权限？）";
        } else {
            for (int i = 0; i < top.length; i++) {
                if (top[i].isDirectory() && P_MONTH.matcher(top[i].getName()).matches()) {
                    addDir(dirs, seen, top[i]);
                }
            }
        }

        File mdRoot = new File(STORAGE, "sogou/md");
        File[] months = mdRoot.listFiles();
        if (months != null) {
            for (int i = 0; i < months.length; i++) {
                if (months[i].isDirectory() && P_MONTH.matcher(months[i].getName()).matches()) {
                    addDir(dirs, seen, months[i]);
                }
            }
        }

        for (int i = 0; i < dirs.size(); i++) scanDir(dirs.get(i), map, 0);
        mergeLog(map);

        List<Item> out = new ArrayList<Item>();
        for (Item it : map.values()) {
            finish(it);
            // 三无记录（没音频、没正文、没摘要）不展示，对应数据契约里的 empty
            if (it.audioPath == null && it.segs.isEmpty() && it.summary.length() == 0) continue;
            out.add(it);
        }
        Collections.sort(out, new Comparator<Item>() {
            public int compare(Item a, Item b) {
                if (a.startMs != b.startMs) return a.startMs > b.startMs ? -1 : 1;
                return a.id.compareTo(b.id);
            }
        });
        return out;
    }

    private void addDir(List<File> dirs, Set<String> seen, File d) {
        if (d == null || !d.isDirectory()) return;
        String p = d.getAbsolutePath();
        if (!seen.add(p)) return;
        dirs.add(d);
        roots.add(p);
    }

    private void scanDir(File dir, Map<String, Item> map, int depth) {
        File[] fs = dir.listFiles();
        if (fs == null) {
            if (dir.getAbsolutePath().equals(recordDir)) lastError = "读不到目录：" + dir.getAbsolutePath();
            return;
        }
        for (int i = 0; i < fs.length; i++) {
            File f = fs[i];
            if (f.isDirectory()) {
                if (depth < 2) scanDir(f, map, depth + 1);
                continue;
            }
            String name = f.getName();
            String ln = name.toLowerCase(Locale.US);
            if (ln.indexOf("_denoise") >= 0) continue;      // 厂商的降噪副本，避免同一场出现两条

            Matcher km = P_KEY.matcher(name);
            if (!km.find()) continue;
            String key = km.group(1);
            Item it = map.get(key);
            if (it == null) {
                it = new Item();
                it.id = key;
                it.startMs = parseKey(key);
                map.put(key, it);
            }
            fileCount++;

            if (isAudio(ln)) {
                int rank = audioRank(ln);
                if (it.audioPath == null || rank > audioRank(it.audioPath)) {
                    it.audioPath = f.getAbsolutePath();
                    it.source = "audio";
                    long d = durMs(f, ln);
                    if (d > it.durMs) it.durMs = d;
                }
            } else if (ln.endsWith(".md") || ln.endsWith(".txt")) {
                MdParser.Out o = MdParser.parse(f);
                apply(it, o, f);
            }
        }
    }

    /** 合并一份文稿：内容更全的覆盖，缺的字段补齐。 */
    private void apply(Item it, MdParser.Out o, File f) {
        if (o == null) return;
        boolean takeSegs = o.segs.size() > it.segs.size();
        if (takeSegs) {
            it.segs.clear();
            it.segs.addAll(o.segs);
            it.mdPath = f.getAbsolutePath();
        }
        if (it.summary.length() == 0 && o.summary.length() > 0) it.summary = o.summary;
        if (it.sid == 0 && o.sid > 0) it.sid = o.sid;
        if (it.durMs == 0 && o.durMs > 0) it.durMs = o.durMs;
        if (it.mode.length() == 0 && o.mode.length() > 0) it.mode = o.mode;
        if (it.errorCount == 0 && o.errorCount > 0) it.errorCount = o.errorCount;
        if (it.mdPath == null) it.mdPath = f.getAbsolutePath();
        if (it.source.equals("audio")) it.source = it.audioPath != null ? "audio" : "md";
    }

    private void finish(Item it) {
        if (it.mode.length() == 0) {
            Matcher m = P_SUFFIX.matcher(basename(it.audioPath != null ? it.audioPath : it.mdPath));
            if (m.find()) it.mode = m.group(1).toLowerCase(Locale.US);
        }
        if (it.sid == 0) {
            Matcher m = P_SID_IN_NAME.matcher(basename(it.mdPath != null ? it.mdPath : ""));
            if (m.find()) {
                try { it.sid = Long.parseLong(m.group(1)); } catch (Throwable ignore) { }
            }
        }
        if (it.durMs <= 0 && it.audioPath != null) it.durMs = durMs(new File(it.audioPath), null);
        if (it.durMs <= 0 && it.segs.size() > 0) {
            it.durMs = (long) (it.segs.get(it.segs.size() - 1).end * 1000);
        }
        if (it.summary.length() == 0) it.summary = summarize(it.segs);
        if (it.title.length() == 0) {
            long t = it.startMs > 0 ? it.startMs : System.currentTimeMillis();
            synchronized (TITLE_FMT) {
                it.title = "录音 " + TITLE_FMT.format(new Date(t));
            }
        }
        if (it.startMs <= 0) it.startMs = System.currentTimeMillis();
    }

    /**
     * 把转录监控的场次合进文件扫描结果。
     *
     * 同一场录音三种来源的优先级：文件里的 md 正文（已成形、带标题）> 日志逐句 > 无。
     * 日志只做两件事：给没正文的场次补正文，给没有音频文件的场次建卡片。
     */
    private void mergeLog(Map<String, Item> map) {
        if (logSessions == null || logSessions.isEmpty()) return;
        for (int i = 0; i < logSessions.size(); i++) {
            Session s = logSessions.get(i);
            if (s.startMs <= 0) continue;
            String key = keyOf(s.startMs);
            Item it = map.get(key);
            if (it == null) {
                it = new Item();
                it.id = key;
                it.startMs = s.startMs;
                it.source = "log";
                map.put(key, it);
            }
            if (it.sid == 0) it.sid = s.sid;
            if (it.durMs <= 0) it.durMs = s.durationMs();
            if (it.mode.length() == 0 && s.mode != null) it.mode = s.mode;
            if (it.summary.length() == 0 && s.summary != null) it.summary = s.summary;
            if (it.errorCount == 0) it.errorCount = s.errorCount;
            if (it.audioPath == null && LogMonitor.exists(s.wavPath)) it.audioPath = s.wavPath;
            if (it.segs.isEmpty() && s.finalCount() > 0) LogMonitor.appendSegments(it, s);
            it.fromLog = true;
        }
    }

    private static String keyOf(long ms) {
        synchronized (KEY_FMT) {
            return KEY_FMT.format(new Date(ms));
        }
    }

    private static String summarize(List<Item.Seg> segs) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < segs.size() && i < 2; i++) b.append(segs.get(i).text);
        String s = b.toString().trim();
        if (s.length() > 58) s = s.substring(0, 58) + "…";
        return s;
    }

    // ---------------------------------------------------------------- 工具

    static boolean isAudio(String ln) {
        return ln.endsWith(".wav") || ln.endsWith(".mp3") || ln.endsWith(".m4a")
                || ln.endsWith(".aac") || ln.endsWith(".amr") || ln.endsWith(".ogg")
                || ln.endsWith(".3gp");
    }

    private static int audioRank(String ln) {
        if (ln.endsWith(".wav")) return 4;
        if (ln.endsWith(".m4a")) return 3;
        if (ln.endsWith(".mp3")) return 2;
        return 1;
    }

    private static String basename(String p) {
        if (p == null) return "";
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    private static long parseKey(String key) {
        try {
            synchronized (KEY_FMT) {
                return KEY_FMT.parse(key).getTime();
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    /** WAV 直接读文件头（不 prepare，几十毫秒变几乎为零）；其它容器交给系统探测。 */
    static long durMs(File f, String lowerName) {
        if (f == null || !f.isFile()) return 0;
        String ln = lowerName != null ? lowerName : f.getName().toLowerCase(Locale.US);
        if (ln.endsWith(".wav")) {
            long d = wavDurMs(f);
            if (d > 0) return d;
        }
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (ms != null) return Long.parseLong(ms.trim());
        } catch (Throwable t) {
            // 文件损坏/格式不支持：时长未知，UI 显示 00:00
        } finally {
            try { r.release(); } catch (Throwable ignore) { }
        }
        return 0;
    }

    private static long wavDurMs(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] h = new byte[64 * 1024];
            int n = in.read(h);
            if (n < 44) return 0;
            if (h[0] != 'R' || h[1] != 'I' || h[2] != 'F' || h[3] != 'F') return 0;
            if (h[8] != 'W' || h[9] != 'A' || h[10] != 'V' || h[11] != 'E') return 0;
            long byteRate = 0;
            if (h[12] == 'f' && h[13] == 'm' && h[14] == 't' && h[15] == ' ') {
                byteRate = u32(h, 28);
            }
            for (int p = 12; p + 8 < n; p++) {
                if (h[p] == 'd' && h[p + 1] == 'a' && h[p + 2] == 't' && h[p + 3] == 'a') {
                    long size = u32(h, p + 4);
                    if (byteRate > 0 && size > 0) return size * 1000L / byteRate;
                    break;
                }
            }
        } catch (Throwable t) {
            return 0;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignore) { }
        }
        return 0;
    }

    private static long u32(byte[] b, int off) {
        if (off + 4 > b.length) return 0;
        return (b[off] & 0xffL) | ((b[off + 1] & 0xffL) << 8)
                | ((b[off + 2] & 0xffL) << 16) | ((b[off + 3] & 0xffL) << 24);
    }
}
