package dev.voxnote;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * JS ⇄ Java 的唯一通道（页面里是 window.voxnote）。
 *
 * 约定：所有返回给 JS 的东西都是 JSON 字符串，JS 侧统一 JSON.parse；
 * 异常不往外抛（WebView 桥里抛异常会让调用方静默失败），改成返回 ok/err 结构。
 *
 * 注意：这些方法运行在 WebView 的 JS 线程上，不是主线程。
 */
public class Bridge {

    public static final String VERSION = "2.0";

    private final Activity act;
    private final SharedPreferences prefs;
    private final State state;
    private final Storage storage;
    private final Library lib;
    private final Player player;
    private final Recorder rec = new Recorder();
    private final List<ContentSource> sources;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String pendingStamp;
    private String pendingTitle;

    public Bridge(Activity a) {
        act = a;
        prefs = a.getSharedPreferences("voxnote", Context.MODE_PRIVATE);
        state = new State(a).load();
        storage = new Storage(a);
        lib = new Library(a, state, storage);
        player = new Player(a);
        sources = Plugins.create();
    }

    // ------------------------------------------------------------ 数据

    @JavascriptInterface
    public String version() {
        return VERSION;
    }

    @JavascriptInterface
    public synchronized String refresh() {
        try {
            return payload(false, null).toString();
        } catch (Throwable t) {
            return "{\"items\":[],\"stats\":{\"error\":\"" + esc(String.valueOf(t)) + "\"}}";
        }
    }

    /** 强制扫某个外部来源（设置页的"扫描并导入"）。 */
    @JavascriptInterface
    public synchronized String scanSources(String id) {
        try {
            return payload(true, id).toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"message\":\"" + esc(String.valueOf(t)) + "\"}";
        }
    }

    private JSONObject payload(boolean forceAll, String onlyId) {
        List<Item> imported = new ArrayList<Item>();
        JSONArray sinfo = new JSONArray();
        for (int i = 0; i < sources.size(); i++) {
            ContentSource s = sources.get(i);
            try {
                boolean force = forceAll && (onlyId == null || onlyId.equals(s.id()));
                List<Item> its = s.scan(force);
                if (its != null) imported.addAll(its);
                sinfo.put(s.info());
            } catch (Throwable t) {
                try {
                    JSONObject e = new JSONObject();
                    e.put("id", s.id());
                    e.put("label", s.label());
                    e.put("available", false);
                    e.put("error", String.valueOf(t));
                    sinfo.put(e);
                } catch (Throwable ignore) { }
            }
        }
        JSONObject o = lib.refresh(imported);
        try {
            o.put("sources", sinfo);
            o.put("storage", storageInfo());
        } catch (Throwable ignore) { }
        return o;
    }

    /** 单条用户态更新：{"title":"…"} / {"cat":"c1"|null} / {"marks":[1.2,8.4]} */
    @JavascriptInterface
    public String patch(String id, String json) {
        try {
            state.patch(id, new JSONObject(json));
            return "{\"ok\":true}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"message\":\"" + esc(String.valueOf(t)) + "\"}";
        }
    }

    /**
     * "转文字"。当前实现 = 立刻重跑外部来源，看这场有没有新正文；
     * 真正接识别后端（自建 API / 本地小模型）时，替换的就是这里。
     */
    @JavascriptInterface
    public synchronized String transcribe(String id) {
        if (id == null || id.length() == 0) {
            return "{\"ok\":false,\"message\":\"未知记录\"}";
        }
        try {
            JSONObject res = payload(true, null);
            int n = 0;
            JSONArray arr = res.optJSONArray("items");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.optJSONObject(i);
                    if (it != null && id.equals(it.optString("id"))) {
                        JSONArray segs = it.optJSONArray("segments");
                        n = segs == null ? 0 : segs.length();
                        break;
                    }
                }
            }
            JSONObject out = new JSONObject();
            out.put("ok", n > 0);
            out.put("segments", n);
            out.put("message", n > 0
                    ? "已从外部来源导入 " + n + " 句"
                    : "外部来源里没有这场的内容（自录的录音只有声音，没有字）");
            return out.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"message\":\"" + esc(String.valueOf(t)) + "\"}";
        }
    }

    // ------------------------------------------------------------ 存储设置

    @JavascriptInterface
    public String storageInfoJson() {
        return storageInfo().toString();
    }

    private JSONObject storageInfo() {
        JSONObject o = new JSONObject();
        try {
            o.put("mode", storage.mode());
            o.put("resolved", storage.resolved());
            o.put("publicSub", storage.publicSub());
            o.put("safTree", storage.safTree());
            o.put("canPublic", storage.hasWritePermission());
            o.put("dateDir", storage.dateDir());
            o.put("privateDir", storage.privateRecords().getAbsolutePath());
            o.put("publicDir", storage.publicRoot().getAbsolutePath());
            o.put("target", storage.describe());
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("needPermission", !storage.hasWritePermission());
        } catch (Throwable ignore) { }
        return o;
    }

    @JavascriptInterface
    public String setStorage(String mode, String publicSub) {
        if (mode != null && mode.length() > 0) storage.setMode(mode);
        if (publicSub != null && publicSub.length() > 0) {
            String s = publicSub.trim();
            while (s.startsWith("/")) s = s.substring(1);
            while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            if (s.length() > 0) storage.setPublicSub(s);
        }
        return storageInfo().toString();
    }

    /** 打开系统文件选择器挑自定义目录（结果在 MainActivity 里回传）。 */
    @JavascriptInterface
    public void pickSafDir() {
        ui.post(new Runnable() {
            public void run() {
                if (act instanceof MainActivity) ((MainActivity) act).pickSafDir();
            }
        });
    }

    @JavascriptInterface
    public void clearSafTree() {
        storage.clearSaf();
    }

    /** 由 MainActivity 在用户选完目录后调用。 */
    public void setSafTree(String uri) {
        storage.setSafTree(uri);
        storage.setMode(Storage.MODE_SAF);
    }

    @JavascriptInterface
    public void requestStoragePerms() {
        ui.post(new Runnable() {
            public void run() {
                if (act instanceof MainActivity) ((MainActivity) act).askPerms();
            }
        });
    }

    // ------------------------------------------------------------ 播放

    @JavascriptInterface
    public boolean open(String path, String uri) {
        return player.open(path, uri);
    }

    @JavascriptInterface
    public void play() {
        player.play();
    }

    @JavascriptInterface
    public void pause() {
        player.pause();
    }

    @JavascriptInterface
    public void seek(int ms) {
        player.seek(ms);
    }

    @JavascriptInterface
    public int position() {
        return player.position();
    }

    @JavascriptInterface
    public int duration() {
        return player.duration();
    }

    @JavascriptInterface
    public boolean playing() {
        return player.playing();
    }

    @JavascriptInterface
    public void close() {
        player.release();
    }

    // ------------------------------------------------------------ 录音

    @JavascriptInterface
    public boolean startRec(String title) {
        if (rec.active()) return true;
        String stamp = Recorder.stamp();
        File tmp = new File(storage.privateTmp(), stamp + "_memo.m4a");
        if (!rec.start(tmp)) return false;
        pendingStamp = stamp;
        pendingTitle = title == null ? "" : title.trim();
        return true;
    }

    /** 停录 → 发布到当前存储目标，返回 "path:…" / "uri:…"；失败返回空串。 */
    @JavascriptInterface
    public String stopRec() {
        File f = rec.stop();
        String stamp = pendingStamp;
        String title = pendingTitle;
        pendingStamp = null;
        pendingTitle = null;
        if (f == null) return "";
        String loc = storage.publish(f, f.getName(), "audio/mp4");
        if (stamp != null && title != null && title.length() > 0) {
            try {
                JSONObject p = new JSONObject();
                p.put("title", title);
                state.patch(stamp, p);
            } catch (Throwable ignore) { }
        }
        return loc;
    }

    @JavascriptInterface
    public int recMs() {
        return rec.ms();
    }

    @JavascriptInterface
    public double recLevel() {
        return rec.level();
    }

    @JavascriptInterface
    public boolean recActive() {
        return rec.active();
    }

    // ------------------------------------------------------------ 杂项

    @JavascriptInterface
    public void toast(String msg) {
        final String m = msg == null ? "" : msg;
        ui.post(new Runnable() {
            public void run() {
                Toast.makeText(act, m, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void release() {
        try { player.release(); } catch (Throwable ignore) { }
        try { rec.stop(); } catch (Throwable ignore) { }
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }
}
