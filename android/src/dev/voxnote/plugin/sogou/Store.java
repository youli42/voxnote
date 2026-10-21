package dev.voxnote.plugin.sogou;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 把解析结果落成结构化数据，放在 /storage/emulated/0/s1rec/：
 *
 *   index.jsonl                          每场一行摘要（供脚本/PC 消费）
 *   sessions/<stamp>_<sid>/record.json   会话元数据
 *   sessions/<stamp>_<sid>/segments.jsonl 逐句（机器派生，可无限重放）
 *   sessions/<stamp>_<sid>/transcript.md  导出物（可删可重建）
 *
 * 编辑层（edits.jsonl）留到下一步再加：机器派生的东西永远不覆盖人工内容。
 */
public class Store {

    public static final File OUT = new File(LogParser.SDCARD, "s1rec");
    public static final int SCHEMA = 1;

    private static final SimpleDateFormat DIR_FMT =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    public static String lastResult = "";

    /** 全量重写。返回写出的会话数。 */
    public static synchronized int writeAll(List<Session> sessions) {
        int n = 0;
        File sessRoot = new File(OUT, "sessions");
        try {
            if (!OUT.exists()) OUT.mkdirs();
            if (!sessRoot.exists()) sessRoot.mkdirs();

            StringBuilder index = new StringBuilder();
            for (int i = 0; i < sessions.size(); i++) {
                Session s = sessions.get(i);
                try {
                    String dirName = writeSession(s, sessRoot);
                    index.append(indexLine(s, dirName)).append('\n');
                    n++;
                } catch (Throwable t) {
                    lastResult = "写会话 " + s.sid + " 失败：" + t;
                }
            }
            writeText(new File(OUT, "index.jsonl"), index.toString());
            lastResult = "已写出 " + n + " 场到 " + OUT.getAbsolutePath();
        } catch (Throwable t) {
            lastResult = "写数据失败：" + t;
        }
        return n;
    }

    private static String dirName(Session s) {
        synchronized (DIR_FMT) {
            return DIR_FMT.format(new Date(s.startMs)) + "_" + s.sid;
        }
    }

    private static String writeSession(Session s, File sessRoot) throws Exception {
        String dn = dirName(s);
        File d = new File(sessRoot, dn);
        if (!d.exists() && !d.mkdirs()) throw new RuntimeException("mkdirs 失败 " + d);

        JSONObject o = new JSONObject();
        o.put("schema", SCHEMA);
        o.put("sid", s.sid);
        o.put("start_ms", s.startMs);
        o.put("end_ms", s.endMs);
        o.put("duration_ms", s.durationMs());
        o.put("start_time", LogParser.fmtTime(s.startMs));
        o.put("mode", s.modeLabel());
        o.put("record_mode", s.mode);
        o.put("model", s.model);
        o.put("lang", s.lang);
        o.put("speaker_count", s.spkCount);
        o.put("speaker_detect", s.spkDetect);
        o.put("status", s.status());
        o.put("final_count", s.finalCount());
        o.put("partial_count", s.partialCount);
        o.put("error_count", s.errorCount);
        o.put("audio", s.wavPath == null ? JSONObject.NULL : s.wavPath);
        o.put("summary", s.summary);
        o.put("generated_at", System.currentTimeMillis());
        writeText(new File(d, "record.json"), o.toString(2));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.segments.size(); i++) {
            Segment g = s.segments.get(i);
            JSONObject j = new JSONObject();
            j.put("k", g.kind == Segment.BREAK ? "break"
                    : (g.kind == Segment.PARTIAL ? "partial" : "final"));
            j.put("t", g.startMs);
            j.put("e", g.endMs);
            if (g.kind != Segment.BREAK) j.put("text", g.text);
            sb.append(j.toString()).append('\n');
        }
        writeText(new File(d, "segments.jsonl"), sb.toString());
        writeText(new File(d, "transcript.md"), markdown(s));
        return dn;
    }

    private static String indexLine(Session s, String dirName) {
        try {
            JSONObject o = new JSONObject();
            o.put("sid", s.sid);
            o.put("start_ms", s.startMs);
            o.put("start_time", LogParser.fmtTime(s.startMs));
            o.put("duration_ms", s.durationMs());
            o.put("mode", s.modeLabel());
            o.put("status", s.status());
            o.put("final_count", s.finalCount());
            o.put("audio", s.wavPath == null ? JSONObject.NULL : s.wavPath);
            o.put("dir", "sessions/" + dirName);
            return o.toString();
        } catch (Throwable t) {
            return "{\"sid\":" + s.sid + "}";
        }
    }

    private static String markdown(Session s) {
        StringBuilder b = new StringBuilder();
        b.append("# ").append(LogParser.fmtTime(s.startMs))
                .append(" ｜ ").append(s.modeLabel()).append('\n').append('\n');
        b.append("- 会话 `").append(s.sid).append("` ｜ 时长 ")
                .append(LogParser.fmtDuration(s.durationMs()))
                .append(" ｜ 句数 ").append(s.finalCount());
        if (s.partialCount > 0) b.append("（另有 ").append(s.partialCount).append(" 条中间态）");
        b.append(" ｜ 状态 ").append(s.statusLabel()).append('\n');
        if (s.wavPath != null) b.append("- 录音 `").append(s.wavPath).append('`').append('\n');
        if (s.errorCount > 0) b.append("- ⚠️ 识别报错 ").append(s.errorCount).append(" 次\n");
        b.append('\n');
        if (s.summary != null && s.summary.length() > 0) {
            b.append("> 摘要：").append(s.summary).append('\n').append('\n');
        }
        boolean any = false;
        for (int i = 0; i < s.segments.size(); i++) {
            Segment g = s.segments.get(i);
            if (g.kind == Segment.BREAK) {
                b.append("> —— 说话人切换 @ ").append(LogParser.fmtStamp(g.startMs))
                        .append(" ——\n\n");
            } else if (g.text.length() > 0) {
                any = true;
                b.append("`[").append(LogParser.fmtStamp(g.startMs)).append(" → ")
                        .append(LogParser.fmtStamp(g.endMs)).append("]` ")
                        .append(g.text).append("\n\n");
            }
        }
        if (!any) b.append("（无识别文本）\n");
        return b.toString();
    }

    private static void writeText(File f, String content) throws Exception {
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        FileOutputStream fs = new FileOutputStream(tmp);
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(fs, "UTF-8"));
        try {
            w.write(content);
        } finally {
            w.close();
        }
        if (!tmp.renameTo(f)) {
            // 目标已存在时 rename 在部分 FUSE 实现上会失败，退化为覆盖写
            FileOutputStream fs2 = new FileOutputStream(f);
            BufferedWriter w2 = new BufferedWriter(new OutputStreamWriter(fs2, "UTF-8"));
            try { w2.write(content); } finally { w2.close(); }
            tmp.delete();
        }
    }
}
