package dev.voxnote;

import java.util.ArrayList;
import java.util.List;

/**
 * 列表里的一条记录 = 一次录音会话。
 *
 * 主键 id 用文件名里的 yyyyMMdd_HHmmss，这样同一场录音的不同载体能对齐：
 * 自录音频、旁车 md、外部来源（厂商日志）里的同一场，
 * 缺哪份都还能出卡片。
 */
public class Item {

    public String id = "";
    public long sid;              // 厂商会话号（10 位秒级），未知为 0
    public long startMs;          // 录音开始时间（本地时间毫秒）
    public long durMs;            // 时长，0 = 未知
    public String audioPath;      // 音频文件路径（可空）
    public String audioUri;       // 音频 content://（SAF / MediaStore，可空）
    public String mdPath;         // 文稿文件路径（可空）
    public String mdUri;          // 文稿 content://（可空）
    public String source = "audio";   // audio | md | import
    public String fromSource;         // 由哪个外部来源并入（null = 全是自己的文件）
    public String mode = "";          // meeting / memo / interview / shorthand ...
    public String title = "";
    public String summary = "";
    public int errorCount;
    public final List<Seg> segs = new ArrayList<Seg>();

    public static class Seg {
        public double start;
        public double end;
        public double conf = 1.0;
        public String text = "";
        /** 会话内说话人编号（0 起）；-1 = 该来源没有说话人数据，不得当成 0。 */
        public int spk = -1;

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
        if (audioPath != null || audioUri != null) return "raw";
        return "empty";
    }
}
