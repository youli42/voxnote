package dev.voxnote;

import dev.voxnote.reader.LogParser;
import dev.voxnote.reader.Segment;
import dev.voxnote.reader.Session;
import dev.voxnote.reader.Store;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 转录监控：把厂商日志里的识别结果读进 App。
 *
 * 这是旧 Magisk 模块那套"常驻 shell 守护 + 定时轮询日志"的内核（LogParser + Store），
 * 换成 App 里的一次全量重扫：日志 ~3 MB/天，全量重放比维护增量状态机可靠得多，
 * 也天然绕开轮转改名导致的整文件重放。
 *
 * 结果不做成第二套 UI，而是并进同一张卡片列表（见 Library.mergeLog）：
 * 有音频的场次补正文，只有日志没有音频的场次也能作为"仅文稿"卡片出现。
 */
public class LogMonitor {

    private static final long STALE_MS = 60 * 1000L;

    private final Object lock = new Object();
    private List<Session> cached = new ArrayList<Session>();
    private final Set<Long> written = new HashSet<Long>();

    private long lastRunAt;
    private int lastSessions;
    private int lastSentences;
    private int lastNew;
    private String lastError = "";
    private boolean lastWrote;

    /** 一分钟内复用上次结果，避免每次 onResume 都重扫 3 MB 日志。 */
    public List<Session> scanIfStale() {
        synchronized (lock) {
            if (lastRunAt > 0 && System.currentTimeMillis() - lastRunAt < STALE_MS) {
                return new ArrayList<Session>(cached);
            }
            return scan(false);
        }
    }

    public List<Session> scan(boolean forceWrite) {
        synchronized (lock) {
            List<Session> all;
            try {
                all = LogParser.scan();
                lastError = LogParser.lastError == null ? "" : LogParser.lastError;
            } catch (Throwable t) {
                lastError = String.valueOf(t);
                all = new ArrayList<Session>();
            }

            // startMs 为 0 的会话没法放到时间轴上（只有 summary 回写、没有引擎创建锚点），
            // 既不能对齐录音文件，也会产出 1970 目录，这里直接不让它进列表。
            List<Session> usable = new ArrayList<Session>();
            int sentences = 0;
            int fresh = 0;
            for (int i = 0; i < all.size(); i++) {
                Session s = all.get(i);
                if (s.startMs <= 0) continue;
                usable.add(s);
                sentences += s.finalCount();
                if (!written.contains(s.sid)) fresh++;
            }

            lastRunAt = System.currentTimeMillis();
            lastSessions = usable.size();
            lastSentences = sentences;

            boolean needWrite = forceWrite || written.isEmpty() || fresh > 0;
            lastWrote = false;
            if (needWrite && !usable.isEmpty()) {
                try {
                    Store.writeAll(usable);
                    written.clear();
                    for (int i = 0; i < usable.size(); i++) written.add(usable.get(i).sid);
                    lastWrote = true;
                } catch (Throwable t) {
                    lastError = String.valueOf(t);
                }
            }
            lastNew = fresh;
            cached = usable;
            return new ArrayList<Session>(usable);
        }
    }

    public int newSessions() {
        synchronized (lock) { return lastNew; }
    }

    public JSONObject info() {
        JSONObject o = new JSONObject();
        try {
            o.put("sessions", lastSessions);
            o.put("sentences", lastSentences);
            o.put("new", lastNew);
            o.put("wrote", lastWrote);
            o.put("lastRunAt", lastRunAt);
            o.put("error", lastError);
            o.put("dir", LogParser.LOG_DIR.getAbsolutePath());
            o.put("out", Store.OUT.getAbsolutePath());
            o.put("available", LogParser.LOG_DIR.isDirectory());
            JSONObject extra = new JSONObject();
            extra.put("notes", "LogParser + Store（旧模块内核）");
            o.put("engine", extra);
        } catch (Throwable t) {
            // 统计不上就算了，别让 UI 因为一个字段挂掉
        }
        return o;
    }

    /** 卡片列表要的逐句（秒），把毫秒事实转成 UI 单位。 */
    public static void appendSegments(Item it, Session s) {
        if (it == null || s == null) return;
        for (int i = 0; i < s.segments.size(); i++) {
            Segment g = s.segments.get(i);
            if (g.kind != Segment.FINAL) continue;
            if (g.text == null || g.text.length() == 0) continue;
            it.segs.add(new Item.Seg(g.startMs / 1000.0, g.endMs / 1000.0, g.text));
        }
    }

    public static boolean exists(String path) {
        return path != null && path.length() > 0 && new File(path).isFile();
    }
}
