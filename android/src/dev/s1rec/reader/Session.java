package dev.s1rec.reader;

import java.util.ArrayList;
import java.util.List;

/** 一场会话（一次录音 / 一次引擎会话）。 */
public class Session {
    /** 10 位秒级 sessionId，来自 Builder 的 date= 毫秒截断。 */
    public long sid;
    public long startMs;
    public long endMs;

    public String mode = "";      // recordMode，如 general / meeting
    public String model = "";     // model='iot'
    public String lang = "";      // fromLanguage
    public int spkCount;          // SpeakerCount
    public boolean spkDetect;

    public String wavPath;        // 匹配到的音频（可能为 null）
    public String summary = "";
    public int errorCount;
    public int partialCount;

    public List<Segment> segments = new ArrayList<Segment>();

    public static final String ST_OK = "ok";
    public static final String ST_EMPTY = "empty";
    public static final String ST_ERROR = "error";
    public static final String ST_SUMMARY = "summary";
    public static final String ST_AUDIO_ONLY = "audio_only";

    public int finalCount() {
        int n = 0;
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            if (s.kind == Segment.FINAL && s.text.length() > 0) n++;
        }
        return n;
    }

    public long durationMs() {
        long d = endMs - startMs;
        if (d <= 0 && segments.size() > 0) {
            long max = 0;
            for (int i = 0; i < segments.size(); i++) {
                Segment s = segments.get(i);
                if (s.endMs > max) max = s.endMs;
                if (s.startMs > max) max = s.startMs;
            }
            d = max;
        }
        return d < 0 ? 0 : d;
    }

    public String status() {
        if (finalCount() > 0) return ST_OK;
        if (errorCount > 0) return ST_ERROR;
        if (summary != null && summary.length() > 0) return ST_SUMMARY;
        if (wavPath != null) return ST_AUDIO_ONLY;
        return ST_EMPTY;
    }

    public String statusLabel() {
        String s = status();
        if (ST_OK.equals(s)) return "已转写";
        if (ST_ERROR.equals(s)) return "识别失败";
        if (ST_SUMMARY.equals(s)) return "仅摘要";
        if (ST_AUDIO_ONLY.equals(s)) return "有录音无文本";
        return "无文本";
    }

    /** 从音频文件名取模式（meeting / memo / interview / shorthand）。 */
    public String audioMode() {
        if (wavPath == null) return null;
        String n = wavPath.substring(wavPath.lastIndexOf('/') + 1);
        int dot = n.lastIndexOf('.');
        if (dot < 0) return null;
        String base = n.substring(0, dot);
        int us = base.lastIndexOf('_');
        return us >= 0 ? base.substring(us + 1) : null;
    }

    /** 展示用模式名，优先取录音文件名（App 真实选定的模式）。 */
    public String modeLabel() {
        String m = audioMode();
        if (m == null || m.length() == 0) m = mode;
        if (m == null) m = "";
        if (m.equals("meeting")) return "会议";
        if (m.equals("memo")) return "备忘";
        if (m.equals("interview")) return "采访";
        if (m.equals("shorthand")) return "速记";
        if (m.equals("news")) return "新闻";
        if (m.equals("general")) return "通用";
        return m.length() == 0 ? "录音" : m;
    }

    /** 首句预览，用于卡片副标题。 */
    public String preview() {
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            if (s.kind == Segment.FINAL && s.text.length() > 0) return s.text;
        }
        return summary == null ? "" : summary;
    }
}
