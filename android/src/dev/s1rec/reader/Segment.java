package dev.s1rec.reader;

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

    public Segment(long startMs, long endMs, String text, int kind) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = text == null ? "" : text;
        this.kind = kind;
    }
}
