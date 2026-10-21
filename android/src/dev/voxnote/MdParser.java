package dev.voxnote;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析录音旁的 .md / .txt 文稿。
 *
 * 兼容本机已有的三种版式（都是同一套"时间段 + 正文"骨架）：
 *   1. 2026.10/<stamp>_<mode>.md  —— 带 `## 正文` 与 `## 纯文本`，正文行是 `[起 → 止]` 文本
 *   2. sogou/md/<月>/<stamp>_<sid>.md —— 旧模块镜像，常常只有头部没有正文
 *   3. s1rec/sessions/<stamp>_<sid>/transcript.md —— 本 App 自己的导出物
 *
 * 只有时间戳没有正文的版式（任意外部工具产出的 md）会退化处理：
 * 取"纯文本"块按句切分，把时长平均分配，保证列表与逐句视图都能显示。
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

    public static class Out {
        public String title = "";
        public String summary = "";
        public String mode = "";
        public String plain = "";
        public long sid;
        public long durMs;
        public int errorCount;
        public final List<Item.Seg> segs = new ArrayList<Item.Seg>();

        public boolean richer(Out o) {
            return o != null && (o.segs.size() > segs.size()
                    || (o.segs.size() == segs.size() && o.plain.length() > plain.length()));
        }
    }

    public static Out parse(File f) {
        Out o = new Out();
        StringBuilder plain = new StringBuilder();
        boolean inFence = false;
        boolean inPlainSection = false;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"), 1 << 14);
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
                if (o.title.length() == 0 && (m = P_TITLE.matcher(t)).find()) {
                    o.title = m.group(1).trim();
                    continue;
                }
                if ((m = P_SEG.matcher(t)).find()) {
                    double s = mmss(m.group(1), m.group(2));
                    double e = mmss(m.group(3), m.group(4));
                    String text = m.group(5).trim();
                    if (text.length() > 0) o.segs.add(new Item.Seg(s, Math.max(s, e), text));
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
