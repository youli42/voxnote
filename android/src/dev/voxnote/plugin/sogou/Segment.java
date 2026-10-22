package dev.voxnote.plugin.sogou;

/** 一句识别结果，或一个说话人切换标记。 */
public class Segment {
    public static final int FINAL = 0;
    public static final int PARTIAL = 1;
    public static final int BREAK = 2;

    public long startMs;
    public long endMs;
    public String text = "";
    public int kind = FINAL;
    /** 说话人切换时写入 speakerInfo_Begin，普通句为 0。 */
    public long speakerAt;
    /**
     * 说话人编号（会话内 tag，0 起）：
     * 普通句 = 按说话人事件轴归属的发言人；BREAK = 该切换指向的发言人；
     * -1 = 无说话人数据（不能当成"发言人 0"）。
     */
    public int speaker = -1;

    public Segment(long startMs, long endMs, String text, int kind) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = text == null ? "" : text;
        this.kind = kind;
    }
}
