package dev.voxnote;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析文稿（md / txt）。输入既可以是文件，也可以是 SAF / MediaStore 打开的流。
 *
 * 兼容本机已有的几种版式（同一套"时间段 + 正文"骨架）：
 *   1. <stamp>_<mode>.md  —— 带 `## 正文` 与 `## 纯文本`，正文行是 `[起 → 止]` 文本
 *   2. sogou/md/<月>/<stamp>_<sid>.md —— 旧模块镜像，常常只有头部没有正文
 *   3. 本 App 的导出物，格式同上
 *
 * 只有正文没有时间戳的文稿会退化处理：按句切分，把时长平均分配。
 */
public class MdParser {

    /** `[00:02.86 → 00:07.07]` 文本 —— 正文行的唯一稳定特征 */
    private static final Pattern P_SEG = Pattern.compile(
            "^`?\\[\\s*(\\d{1,3}):(\\d{2}(?:\\.\\d{1,3})?)\\s*(?:→|->|—|–|-)\\s*"
                    + "(\\d{1,3}):(\\d{2}(?:\\.\\d{1,3})?)\\s*\\]`?\\s*(.+)$");
    private static final Pattern P_DUR = Pattern.compile("时长\\s*(\\d{1,3}):(\\d{2}(?:\\.\\d{1,3})?)");
    private static final Pattern P_SID = Pattern.compile("会话\\s*`?(\\d{6,})`?");
    private static final Pattern P_SUM = Pattern.compile("^>\\s*摘要\\s*[:：]\\s*(.*)$");
    private static final Pattern P_MODE = Pattern.compile("模式\\s*`?([A-Za-z_]+)`?");
    private static final Pattern P_TITLE = Pattern.compile("^#\\s+(.+?)\\s*$");
    private static final Pattern P_ERR = Pattern.compile("错误\\s*(\\d+)\\s*次");
    /** 本 App 导出物的说话人分组头：**发言人 2**（1 基；回读成 0 基的 spk） */
    private static final Pattern P_SPK_HDR = Pattern.compile("^\\*\\*发言人\\s*(\\d+)\\*\\*$");

    public static class Out {
        public String title = "";
        public String summary = "";
        public String mode = "";
        public String plain = "";
        public long sid;
        public long durMs;
        public int errorCount;
        public final List<Item.Seg> segs = new ArrayList<Item.Seg>();
    }

    public static Out parse(File f) {
        try {
            return parse(new FileInputStream(f));
        } catch (Throwable t) {
            return new Out();
        }
    }

    public static Out parse(InputStream in) {
        Out o = new Out();
        StringBuilder plain = new StringBuilder();
        boolean inFence = false;
        boolean inPlainSection = false;
        int curSpk = -1;   // 当前说话人分组（**发言人 N** 头之后生效，0 基）
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(in, "UTF-8"), 1 << 14);
            String raw;
            while ((raw = r.readLine()) != null) {
                String t = raw.trim();
                if (t.startsWith("```")) {
                    inFence = !inFence;
                    continue;
                }
                if (t.startsWith("## ")) {
                    inPlainSection = t.indexOf("纯文本") >= 0;
                    inFence = false;
                    continue;
                }
                if (inPlainSection && inFence) {
                    if (t.length() > 0) plain.append(t).append('\n');
                    continue;
                }

                Matcher m;
                if ((m = P_SPK_HDR.matcher(t)).find()) {
                    curSpk = (int) num(m.group(1), 1) - 1;
                    if (curSpk < 0) curSpk = -1;
                    continue;
                }
                if (o.title.length() == 0 && (m = P_TITLE.matcher(t)).find()) {
                    o.title = m.group(1).trim();
                    continue;
                }
                if ((m = P_SEG.matcher(t)).find()) {
                    double s = mmss(m.group(1), m.group(2));
                    double e = mmss(m.group(3), m.group(4));
                    String text = m.group(5).trim();
                    if (text.length() > 0) {
                        Item.Seg seg = new Item.Seg(s, Math.max(s, e), text);
                        seg.spk = curSpk;
                        o.segs.add(seg);
                    }
                    continue;
                }
                if (o.summary.length() == 0 && (m = P_SUM.matcher(t)).find()) {
                    o.summary = m.group(1).trim();
                    continue;
                }
                if (o.sid == 0 && (m = P_SID.matcher(t)).find()) o.sid = num(m.group(1), 0);
                if (o.durMs == 0 && (m = P_DUR.matcher(t)).find()) {
                    o.durMs = (long) (mmss(m.group(1), m.group(2)) * 1000);
                }
                if (o.mode.length() == 0 && (m = P_MODE.matcher(t)).find()) o.mode = m.group(1);
                if (o.errorCount == 0 && (m = P_ERR.matcher(t)).find()) {
                    o.errorCount = (int) num(m.group(1), 0);
                }
            }
        } catch (Throwable t) {
            // 单个文稿坏掉不影响整库扫描
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignore) { }
        }
        o.plain = plain.toString().trim();
        if (o.segs.isEmpty() && o.plain.length() > 0) synthesize(o);
        return o;
    }

    /** 没有时间戳的文稿：按句切分，把已知时长平均铺开（未知则每句 1.5 s）。 */
    private static void synthesize(Out o) {
        List<String> parts = new ArrayList<String>();
        String[] lines = o.plain.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i].trim();
            if (ln.length() == 0) continue;
            String[] sentences = ln.split("(?<=[。！？!?])");
            for (int j = 0; j < sentences.length; j++) {
                String s = sentences[j].trim();
                if (s.length() > 0) parts.add(s);
            }
        }
        if (parts.isEmpty()) return;
        double total = o.durMs > 0 ? o.durMs / 1000.0 : parts.size() * 1.5;
        double per = total / parts.size();
        for (int i = 0; i < parts.size(); i++) {
            double s = i * per;
            o.segs.add(new Item.Seg(round1(s), round1(Math.min(total, s + per)), parts.get(i)));
        }
    }

    private static double mmss(String min, String sec) {
        return num(min, 0) * 60.0 + dbl(sec);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static long num(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Throwable t) { return def; }
    }

    private static double dbl(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Throwable t) { return 0; }
    }
}
