package dev.voxnote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 列表里的一条记录 = 一次录音会话。
 *
 * 主键 id 用录音文件名里的 yyyyMMdd_HHmmss（例如 20261021_014357），
 * 因为同一场录音的音频、旁车 md、厂商日志里的会话号都能对齐到这一个时间戳：
 *   2026.10/20261021_014357_meeting.wav
 *   2026.10/20261021_014357_meeting.md
 *   sogou/md/2026.10/20261021_014357_1792518237.md
 * 机型/来源不同只会缺其中一部分，缺哪份都能出卡片。
 */
public class Item {

    public String id = "";
    public long sid;              // 厂商会话号（10 位秒级），未知为 0
    public long startMs;          // 录音开始时间（本地时间毫秒）
    public long durMs;            // 时长，0 = 未知
    public String audioPath;      // 音频绝对路径，null = 只有文本
    public String mdPath;         // 采用的文稿路径
    public String source = "audio";   // audio | md | log
    public boolean fromLog;           // 有厂商日志的逐句兜底（转录监控合进来的）
    public String mode = "";          // meeting / memo / interview / shorthand ...
    public String title = "";         // 默认标题（用户改名走 state）
    public String summary = "";
    public int errorCount;
    public final List<Seg> segs = new ArrayList<Seg>();

    public static class Seg {
        public double start;
        public double end;
        public double conf = 1.0;
        public String text = "";

        public Seg(double s, double e, String t) {
            start = s;
            end = e;
            text = t;
        }
    }

    /**
     * done      有正文
     * failed    有声/有错误但没出字
     * raw       有音频没文本（可重转）
     * empty     既没音频也没正文（脏数据，UI 显式呈现）
     */
    public String status() {
        if (segs.size() > 0) return "done";
        if (errorCount > 0) return "failed";
        if (audioPath != null) return "raw";
        return "empty";
    }

    public String fmtDate() {
        long t = startMs > 0 ? startMs : System.currentTimeMillis();
        synchronized (FMT) {
            return FMT.format(new Date(t));
        }
    }

    /** UI 卡片要的字段，一次序列化过去，避免 JS 再算一遍。 */
    public JSONObject toJson(JSONObject patch) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("sid", sid);
            o.put("seq", startMs);
            o.put("title", titleOf(patch));
            o.put("date", fmtDate());
            o.put("dur", durMs / 1000.0);
            o.put("status", status());
            o.put("source", source);
            o.put("fromLog", fromLog);
            o.put("mode", mode);
            o.put("audioPath", audioPath == null ? JSONObject.NULL : audioPath);
            o.put("mdPath", mdPath == null ? JSONObject.NULL : mdPath);
            o.put("summary", summary);
            o.put("errorCode", "failed".equals(status()) ? Integer.valueOf(14) : JSONObject.NULL);
            o.put("quality", segs.size() > 0 ? 1.0 : 0.0);
            o.put("needsReview", false);

            JSONArray sa = new JSONArray();
            for (int i = 0; i < segs.size(); i++) {
                Seg s = segs.get(i);
                JSONObject j = new JSONObject();
                j.put("start", round2(s.start));
                j.put("end", round2(s.end));
                j.put("text", s.text);
                j.put("confidence", s.conf);
                sa.put(j);
            }
            o.put("segments", sa);

            // 用户态（分类 / 标记 / 改名）
            String cat = null;
            JSONArray marks = new JSONArray();
            if (patch != null) {
                if (patch.has("cat") && !patch.isNull("cat")) {
                    String c = patch.optString("cat", "");
                    if (c.length() > 0) cat = c;
                }
                JSONArray pm = patch.optJSONArray("marks");
                if (pm != null) {
                    for (int i = 0; i < pm.length(); i++) {
                        JSONObject m = new JSONObject();
                        m.put("t", round2(pm.optDouble(i, 0)));
                        marks.put(m);
                    }
                }
            }
            o.put("cat", cat == null ? JSONObject.NULL : cat);
            o.put("marks", marks);
        } catch (Throwable t) {
            // 序列化异常也要返回对象，宁可少字段也不要空列表
        }
        return o;
    }

    private String titleOf(JSONObject patch) {
        if (patch != null) {
            String t = patch.optString("title", "");
            if (t.length() > 0) return t;
        }
        return title;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
}
