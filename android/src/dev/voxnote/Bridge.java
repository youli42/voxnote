package dev.voxnote;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import dev.voxnote.reader.Session;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * JS ⇄ Java 的唯一通道（页面里是 window.voxnote）。
 *
 * 约定：所有返回给 JS 的东西都是 JSON 字符串，JS 侧统一 JSON.parse；
 * 异常不往外抛（WebView 桥里抛异常会让调用方静默失败），改成返回 ok/err 结构。
 *
 * 注意：这些方法运行在 WebView 的 JS 线程上，不是主线程。
 * 因此这里只做数据与 media 操作，任何 UI 动作都 post 到主线程。
 */
public class Bridge {

    public static final String VERSION = "1.4";
    private static final String DEFAULT_RECORD_DIR = "/storage/emulated/0/s1rec/records";

    private final Activity act;
    private final SharedPreferences prefs;
    private final State state;
    private final Library lib;
    private final Player player = new Player();
    private final Recorder rec = new Recorder();
    private final LogMonitor monitor = new LogMonitor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String pendingStamp;
    private String pendingTitle;

    public Bridge(Activity a) {
        act = a;
        prefs = a.getSharedPreferences("s1rec", Context.MODE_PRIVATE);
        state = new State().load();
        lib = new Library(state, recordDir());
    }

    private String recordDir() {
        String d = prefs.getString("recordDir", DEFAULT_RECORD_DIR);
        return d == null || d.length() == 0 ? DEFAULT_RECORD_DIR : d;
    }

    // ------------------------------------------------------------ 数据

    @JavascriptInterface
    public String version() {
        return VERSION;
    }

    /**
     * 全量重扫，返回 {items, stats, monitor}。
     * 日志监控一分钟内复用上次结果，所以切回 App 的刷新不会每次都重读 3 MB 日志。
     */
    @JavascriptInterface
    public synchronized String refresh() {
        lib.setRecordDir(recordDir());
        try {
            List<Session> sessions = monitor.scanIfStale();
            lib.setLogSessions(sessions);
            JSONObject o = lib.refresh();
            o.put("monitor", monitor.info());
            return o.toString();
        } catch (Throwable t) {
            return "{\"items\":[],\"stats\":{\"error\":\"" + esc(String.valueOf(t)) + "\"}}";
        }
    }

    /** 强制跑一次转录监控（日志重扫 + 写出 sessions/），并返回新列表。 */
    @JavascriptInterface
    public synchronized String scanLogs() {
        try {
            monitor.scan(true);
            lib.setLogSessions(monitor.scanIfStale());
            JSONObject o = lib.refresh();
            o.put("monitor", monitor.info());
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"message\":\"" + esc(String.valueOf(t)) + "\"}";
        }
    }

    @JavascriptInterface
    public String monitorInfo() {
        return monitor.info().toString();
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

    @JavascriptInterface
    public String setting(String key) {
        return prefs.getString(key, "");
    }

    @JavascriptInterface
    public void setSetting(String key, String value) {
        prefs.edit().putString(key, value == null ? "" : value).apply();
    }

    /** JS 把整份设置推过来，原生只关心落盘目录。 */
    @JavascriptInterface
    public void configure(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String dir = o.optString("recordDir", "");
            if (dir.length() > 0) {
                prefs.edit().putString("recordDir", dir).apply();
                lib.setRecordDir(dir);
            }
        } catch (Throwable ignore) { }
    }

    /**
     * "转文字"。本分支的简单实现 = 立刻重跑一次转录监控，看这场有没有新正文：
     * 真正接识别后端（自建 API / 本地小模型）时，替换的就是这里。
     */
    @JavascriptInterface
    public synchronized String transcribe(String id) {
        if (id == null || id.length() == 0) {
            return "{\"ok\":false,\"message\":\"未知记录\"}";
        }
        try {
            monitor.scan(true);
            lib.setLogSessions(monitor.scanIfStale());
            JSONObject res = lib.refresh();

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
                    ? "已从日志导入 " + n + " 句"
                    : "日志里没有这场的内容（厂商只在上传成功时写逐句）");
            out.put("monitor", monitor.info());
            return out.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"message\":\"" + esc(String.valueOf(t)) + "\"}";
        }
    }

    // ------------------------------------------------------------ 播放

    @JavascriptInterface
    public boolean open(String path) {
        return player.open(path);
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
        boolean ok = rec.start(new java.io.File(recordDir()), stamp);
        if (ok) {
            pendingStamp = stamp;
            pendingTitle = title == null ? "" : title.trim();
        }
        return ok;
    }

    /** 返回落盘路径；失败返回空串。 */
    @JavascriptInterface
    public String stopRec() {
        String path = rec.stop();
        if (path != null && pendingStamp != null && pendingTitle != null && pendingTitle.length() > 0) {
            try {
                JSONObject p = new JSONObject();
                p.put("title", pendingTitle);
                state.patch(pendingStamp, p);
            } catch (Throwable ignore) { }
        }
        pendingStamp = null;
        pendingTitle = null;
        return path == null ? "" : path;
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
