package dev.voxnote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 列表/详情共用的“一条记录”视图模型：
 * 事实来自 {@link Item}（扫描产物），用户态来自 {@link State}（改名/分类/标记），
 * 摘要/待办/关键词在界面层派生（与旧 H5 的 deriveAll 等价）。
 *
 * 这样核心扫描代码不认识 UI，UI 也不用反复读文件。
 */
public class RecordVm {

    private static final String[] TODO_KW =
            {"要", "记得", "明天", "第一", "第二", "第三", "必须", "需要", "得", "别"};
    private static final String[] KW_POOL =
            {"评审", "采样率", "接口", "模型", "导出", "回归", "机房", "门禁",
                    "待办", "摘要", "标记", "分类", "幂等", "降级"};

    public String id;
    public long sid;
    public long startMs;
    public long durMs;
    public String audioPath;
    public String audioUri;
    public String mdPath;
    public String mdUri;
    public String mode = "";
    public String fromSource;
    public String source = "";
    public int errorCount;
    public final List<Item.Seg> segs;

    public String title = "";
    public String cat;
    public ArrayList<Double> marks = new ArrayList<Double>();
    public String summary = "";
    private ArrayList<String> todos;
    private ArrayList<String> keywords;

    public RecordVm(Item it, JSONObject state) {
        id = it.id;
        sid = it.sid;
        startMs = it.startMs;
        durMs = it.durMs;
        audioPath = it.audioPath;
        audioUri = it.audioUri;
        mdPath = it.mdPath;
        mdUri = it.mdUri;
        mode = it.mode == null ? "" : it.mode;
        fromSource = it.fromSource;
        source = it.source == null ? "" : it.source;
        errorCount = it.errorCount;
        segs = it.segs;
        title = it.title == null ? "" : it.title;
        summary = it.summary == null ? "" : it.summary;
        applyState(state);
    }

    public void applyState(JSONObject state) {
        cat = null;
        marks = new ArrayList<Double>();
        if (state == null) return;
        String t = state.optString("title", "");
        if (t.length() > 0) title = t;
        String c = state.optString("cat", "");
        if (c.length() > 0 && !"null".equals(c)) cat = c;
        JSONArray m = state.optJSONArray("marks");
        if (m != null) {
            for (int i = 0; i < m.length(); i++) {
                double v = m.optDouble(i, Double.NaN);
                if (!Double.isNaN(v)) marks.add(v);
            }
        }
    }

    public boolean hasAudio() {
        return (audioPath != null && audioPath.length() > 0)
                || (audioUri != null && audioUri.length() > 0);
    }

    public boolean hasMd() {
        return (mdPath != null && mdPath.length() > 0)
                || (mdUri != null && mdUri.length() > 0);
    }

    public String status() {
        if (!segs.isEmpty()) return "done";
        if (errorCount > 0) return "failed";
        if (hasAudio()) return "raw";
        return "empty";
    }

    public String statusLabel() {
        String s = status();
        if ("done".equals(s)) return "已转写";
        if ("failed".equals(s)) return "转写失败";
        if ("empty".equals(s)) return "无正文";
        return "待转写";
    }

    /**
     * 卡片右上角的"待处理原因"红色徽标：只报转写链路的问题（失败/未转写/无正文）。
     * 归类状态不在这里报——它由卡片元信息行的分类标签（未分类/分类名）单独表达，
     * 这样"未转写"（要转文字）和"未分类"（要归类）两个待办才不会挤进同一个标签里。
     */
    public String reason() {
        String s = status();
        if ("failed".equals(s)) return "转写失败";
        if ("raw".equals(s)) return "未转写";
        if ("empty".equals(s)) return "无正文";
        return "";
    }

    public boolean showSummary() {
        return "done".equals(status()) && summary != null && summary.length() > 0;
    }

    public String dateText() {
        long t = startMs > 0 ? startMs : System.currentTimeMillis();
        return FMT.format(t);
    }

    public String fmtDur() {
        return fmtDur(durMs / 1000.0);
    }

    public static String fmtDur(double seconds) {
        int s = (int) Math.max(0, seconds);
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    public String modeLabel() {
        String m = mode == null ? "" : mode.toLowerCase(Locale.US);
        if ("meeting".equals(m)) return "会议";
        if ("memo".equals(m)) return "备忘";
        if ("interview".equals(m)) return "采访";
        if ("shorthand".equals(m)) return "速记";
        if ("news".equals(m)) return "新闻";
        if ("general".equals(m)) return "通用";
        if ("rec".equals(m)) return "录音";
        return "";
    }

    /** 摘要里已经出现过的句子不再重复当成待办。 */
    public ArrayList<String> todos() {
        if (todos != null) return todos;
        ArrayList<String> out = new ArrayList<String>();
        if (segs.isEmpty()) { todos = out; return out; }
        for (int i = 0; i < segs.size() && out.size() < 2; i++) {
            String text = segs.get(i).text;
            if (text == null || text.length() == 0) continue;
            if (summary != null && summary.contains(text)) continue;
            if (!containsAny(text, TODO_KW)) continue;
            out.add(text.endsWith("。") ? text.substring(0, text.length() - 1) : text);
        }
        todos = out;
        return out;
    }

    public ArrayList<String> keywords() {
        if (keywords != null) return keywords;
        ArrayList<String> out = new ArrayList<String>();
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < segs.size(); i++) {
            if (segs.get(i).text != null) all.append(segs.get(i).text);
        }
        String text = all.toString();
        if (text.length() > 0) {
            for (int i = 0; i < KW_POOL.length && out.size() < 4; i++) {
                if (text.contains(KW_POOL[i])) out.add(KW_POOL[i]);
            }
        }
        keywords = out;
        return out;
    }

    public void invalidateDerived() {
        todos = null;
        keywords = null;
    }

    private static boolean containsAny(String text, String[] keys) {
        for (int i = 0; i < keys.length; i++) {
            if (text.contains(keys[i])) return true;
        }
        return false;
    }

    private static final java.text.SimpleDateFormat FMT =
            new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
}
