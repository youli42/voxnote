package dev.voxnote.plugin.sogou;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把搜狗 S1 的 tmlog 解析成会话 + 逐句。
 *
 * 设计要点（均来自 2026-10-21 / 10-22 的实测结论）：
 *  - 会话主键用 10 位秒级 sid（Builder 的 date= 毫秒截断），sid<=0 一律丢弃（实测存在 sessionId:0 的脏行）。
 *  - Builder 不代表一场录音（同一次按键会反复创建引擎），因此音频存在与否单独判定，二者都对不上才丢。
 *  - 上报行（%22 / json:）里嵌着完整的统计 JSON，先挡掉，否则会重复计数。
 *  - 说话人只信 AngleExtraProcessor 回调行的 SpeakerMsg{tag,gender,...}（时间是句子时间轴）；
 *    OnSpeechSoundDetect 行的 speakerInfo_Begin 是流偏移（实测 477792 vs 29000），一律不用；
 *    isvalid:false 的切换厂商自己会过滤（"过滤掉一个讲话人"），我们也不收。
 *  - 全量重扫：日志仅 ~3MB/天，重放比维护增量状态机更可靠（也避免轮转改名导致的整文件重放）。
 */
public class LogParser {

    public static final File SDCARD = new File("/storage/emulated/0");
    public static final File LOG_DIR = new File(SDCARD, "sogou/log/app");

    private static final Pattern P_BUILDER_DATE = Pattern.compile("Builder\\{date=(\\d+)");
    private static final Pattern P_MODE = Pattern.compile("recordMode='([^']*)'");
    private static final Pattern P_MODEL = Pattern.compile("model='([^']*)'");
    private static final Pattern P_LANG = Pattern.compile("fromLanguage='([^']*)'");
    private static final Pattern P_SPK = Pattern.compile("SpeakerCount=(\\d+)");
    private static final Pattern P_SPKDET = Pattern.compile("enableSpeakerDetection=(true|false)");
    /**
     * AngleExtraProcessor 说话人回调：只取归一化时间（句子时间轴），不取 OnSpeechSoundDetect 的流偏移。
     * 实测同一次切换两类行数字不同（如 601792 流偏移 vs 37000 句子时间），detectSpeaker 里那个才是能跟逐句对齐的。
     */
    private static final Pattern P_SPKEVT = Pattern.compile(
            "AngleExtraProcessor valid speaker 匹配成功.*?detectSpeaker:SpeakerMsg\\{tag=(\\d+), gender=(\\d+), speakerInfo_Begin=(\\d+)");

    private static final Pattern P_SENTENCE =
            Pattern.compile("识别结果：(true|false) (.+?) (\\d+) (\\d+)$");
    private static final Pattern P_BREAK = Pattern.compile("speakerInfo_Begin:(\\d+)");
    private static final Pattern P_SUMMARY =
            Pattern.compile("updateSummary,ecordContent：(.*),sessionId:(\\d{10})");
    private static final Pattern P_END =
            Pattern.compile("record_end_[a-zA-Z]+_[a-zA-Z]+_[A-Za-z]+_(\\d{10})_(\\d{13})");
    private static final Pattern P_MEMO_END =
            Pattern.compile("memo recognizeEnd，sessionId：(\\d{10})");
    private static final Pattern P_MEMO_SUMMARY = Pattern.compile("summary:(.*),tail:");
    private static final Pattern P_WAV_NAME = Pattern.compile("^(\\d{8}_\\d{6})_");
    private static final Pattern P_MONTH_DIR = Pattern.compile("^\\d{4}\\.\\d{2}$");

    private static final SimpleDateFormat WAV_FMT =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    /** 解析结果缓存，供详情页复用。 */
    public static volatile List<Session> cache = new ArrayList<Session>();
    public static volatile long lastScanAt;
    public static volatile String lastError;

    public static List<Session> load() {
        return cache;
    }

    public static Session bySid(long sid) {
        List<Session> list = cache;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).sid == sid) return list.get(i);
        }
        return null;
    }

    /** 全量扫描：读日志 + 挂接音频。耗时操作，必须在后台线程调用。 */
    public static List<Session> scan() {
        Map<Long, Session> map = new HashMap<Long, Session>();
        long lines = 0, skipped = 0;

        File[] files = LOG_DIR.listFiles();
        if (files == null) {
            lastError = "读不到日志目录：" + LOG_DIR;
            cache = new ArrayList<Session>();
            return cache;
        }
        List<File> logs = new ArrayList<File>();
        for (int i = 0; i < files.length; i++) {
            String n = files[i].getName();
            if (n.startsWith("tmlog.tm")) logs.add(files[i]);
        }
        // 按修改时间从旧到新：轮转文件先读，活跃文件最后读
        Collections.sort(logs, new Comparator<File>() {
            public int compare(File a, File b) {
                long x = a.lastModified(), y = b.lastModified();
                if (x != y) return x < y ? -1 : 1;
                return a.getName().compareTo(b.getName());
            }
        });

        for (int fi = 0; fi < logs.size(); fi++) {
            File f = logs.get(fi);
            BufferedReader r = null;
            try {
                r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"), 1 << 15);
                String line;
                long cur = 0;
                int liveTag = -1;   // 当前会话「此刻在说的」说话人；句子按日志时序继承它
                while ((line = r.readLine()) != null) {
                    lines++;
                    // 上报行里嵌着整份统计 JSON，含 record_end_* 等锚点，必须先排除
                    if (line.indexOf("%22") >= 0 || line.indexOf("json:") >= 0) { skipped++; continue; }

                    if (line.indexOf("Builder{date=") >= 0) {
                        Matcher m = P_BUILDER_DATE.matcher(line);
                        if (m.find()) {
                            long ms = parseLong(m.group(1), 0);
                            long sid = ms > 0 ? ms / 1000L : 0;
                            if (sid <= 0) { skipped++; continue; }
                            // 只在进入不同会话时重置说话人游标；同 sid 反复出现的 Builder 不清空
                            if (cur != sid) liveTag = -1;
                            cur = sid;
                            Session s = map.get(sid);
                            if (s == null) {
                                s = new Session();
                                s.sid = sid;
                                s.startMs = ms;
                                s.mode = find(P_MODE, line);
                                s.model = find(P_MODEL, line);
                                s.lang = find(P_LANG, line);
                                s.spkCount = (int) parseLong(find(P_SPK, line), 0);
                                s.spkDetect = "true".equals(find(P_SPKDET, line));
                                map.put(sid, s);
                            }
                        }
                        continue;
                    }

                    if (line.indexOf("kt:662") >= 0 && line.indexOf("识别结果：") >= 0) {
                        Session s = map.get(cur);
                        if (s == null) { skipped++; continue; }
                        Matcher m = P_SENTENCE.matcher(line);
                        if (!m.find()) { skipped++; continue; }
                        boolean fin = "true".equals(m.group(1));
                        String text = m.group(2).trim();
                        long st = parseLong(m.group(3), 0);
                        long en = parseLong(m.group(4), 0);
                        if (fin) {
                            if (text.length() > 0) {
                                Segment seg = new Segment(st, en, text, Segment.FINAL);
                                seg.speaker = liveTag;   // 日志此刻的当前说话人（首句可能 -1，稍后补）
                                s.segments.add(seg);
                            }
                        } else {
                            s.partialCount++;
                        }
                        if (en > s.endMs) s.endMs = en;
                        continue;
                    }

                    // 说话人事件轴：AngleExtraProcessor 的"匹配成功"回调带归一化时间 + tag + gender。
                    // "过滤掉一个讲话人"(isvalid:false) 与 OnSpeechSoundDetect(流偏移) 都不进这条轴。
                    if (line.indexOf("AngleExtraProcessor valid speaker 匹配成功") >= 0) {
                        Session s = map.get(cur);
                        if (s == null) { skipped++; continue; }
                        Matcher m = P_SPKEVT.matcher(line);
                        if (m.find()) {
                            int tag = (int) parseLong(m.group(1), -1);
                            int gender = (int) parseLong(m.group(2), 0);
                            long t = parseLong(m.group(3), 0);
                            if (tag >= 0) {
                                s.spkEvents.add(new Session.SpkEvent(t, tag, gender));
                                liveTag = tag;
                            }
                        }
                        continue;
                    }

                    if (line.indexOf("ShouldNewParagraph") >= 0 && line.indexOf("result:true") >= 0) {
                        Session s = map.get(cur);
                        if (s == null) continue;
                        Matcher m = P_BREAK.matcher(line);
                        if (m.find()) {
                            Segment b = new Segment(parseLong(m.group(1), 0), 0, "", Segment.BREAK);
                            b.speakerAt = b.startMs;
                            // 该切换指向的说话人：事件轴里时间精确命中（回调先于本行出现），否则取最近之前的
                            b.speaker = speakerTagAt(s, b.startMs);
                            s.segments.add(b);
                        }
                        continue;
                    }

                    if (line.indexOf("updateSummary") >= 0) {
                        Matcher m = P_SUMMARY.matcher(line);
                        if (m.find()) {
                            long sid = parseLong(m.group(2), 0);
                            Session s = map.get(sid);
                            if (s == null) {
                                s = new Session();
                                s.sid = sid;
                                map.put(sid, s);
                            }
                            String t = m.group(1).trim();
                            if (t.length() > s.summary.length()) s.summary = t;
                        }
                        continue;
                    }

                    if (line.indexOf("record_end_") >= 0) {
                        Matcher m = P_END.matcher(line);
                        if (m.find()) {
                            long sid = parseLong(m.group(1), 0);
                            Session s = map.get(sid);
                            if (s == null) {
                                s = new Session();
                                s.sid = sid;
                                map.put(sid, s);
                            }
                            long end = parseLong(m.group(2), 0);
                            if (end > s.endMs) s.endMs = end;
                        }
                        continue;
                    }

                    if (line.indexOf("recognizeEnd") >= 0) {
                        Matcher m = P_MEMO_END.matcher(line);
                        if (m.find()) {
                            long sid = parseLong(m.group(1), 0);
                            Session s = map.get(sid);
                            if (s == null) {
                                s = new Session();
                                s.sid = sid;
                                map.put(sid, s);
                            }
                            Matcher ms = P_MEMO_SUMMARY.matcher(line);
                            if (ms.find()) {
                                String t = ms.group(1).trim();
                                if (t.length() > s.summary.length()) s.summary = t;
                            }
                        }
                        continue;
                    }

                    if (line.indexOf("OnError") >= 0 || line.indexOf("recognize_error") >= 0) {
                        Session s = map.get(cur);
                        if (s != null) s.errorCount++;
                        continue;
                    }
                }
            } catch (Throwable t) {
                lastError = "读取 " + f.getName() + " 失败：" + t;
            } finally {
                if (r != null) try { r.close(); } catch (Throwable ignore) { }
            }
        }

        // 音频挂接：以 wav 文件名的时间成立与否作为「真的录上了」的硬证据
        attachAudio(map);

        List<Session> out = new ArrayList<Session>();
        for (Session s : map.values()) {
            s.segments = normalize(s.segments);
            assignSpeakers(s);
            boolean meaningful = s.finalCount() > 0
                    || (s.summary != null && s.summary.length() > 0) || s.wavPath != null;
            if (!meaningful) continue;          // 纯引擎噪声：无字、无摘要、无音频（只剩报错）
            if (s.startMs <= 0 && s.wavPath != null) {
                // 仅从音频建立：startMs 已在 attachAudio 里按文件名补齐
            }
            out.add(s);
        }

        Collections.sort(out, new Comparator<Session>() {
            public int compare(Session a, Session b) {
                if (a.startMs != b.startMs) return a.startMs > b.startMs ? -1 : 1;
                return a.sid > b.sid ? -1 : 1;
            }
        });

        cache = out;
        lastScanAt = System.currentTimeMillis();
        return out;
    }

    /** 逐句按起始时间排序，并把相邻说话人切换标记去重。 */
    private static List<Segment> normalize(List<Segment> in) {
        Collections.sort(in, new Comparator<Segment>() {
            public int compare(Segment a, Segment b) {
                if (a.startMs != b.startMs) return a.startMs < b.startMs ? -1 : 1;
                return a.kind - b.kind;
            }
        });
        List<Segment> out = new ArrayList<Segment>(in.size());
        long lastBreak = -1;
        for (int i = 0; i < in.size(); i++) {
            Segment s = in.get(i);
            if (s.kind == Segment.BREAK) {
                if (s.startMs - lastBreak < 500) continue;   // 500ms 内的重复切换合并
                lastBreak = s.startMs;
            }
            out.add(s);
        }
        return out;
    }

    /**
     * 兜底：日志顺序缝隙里落成 speaker=-1 的句子（如首句先于第一个回调出现、轮转文件接缝重置过游标），
     * 按事件轴取"句起点时刻最近的 tag"补齐；轴早于句子时取第一个 tag。
     * 没有事件轴的会话原样保留 -1——"无说话人数据"必须区别于"发言人 0"。
     */
    private static void assignSpeakers(Session s) {
        if (s.spkEvents.isEmpty()) return;
        Collections.sort(s.spkEvents, new Comparator<Session.SpkEvent>() {
            public int compare(Session.SpkEvent a, Session.SpkEvent b) {
                if (a.t != b.t) return a.t < b.t ? -1 : 1;
                return a.tag - b.tag;
            }
        });
        int first = s.spkEvents.get(0).tag;
        for (int i = 0; i < s.segments.size(); i++) {
            Segment g = s.segments.get(i);
            if (g.kind != Segment.FINAL || g.speaker >= 0) continue;
            int tag = first;
            for (int j = 0; j < s.spkEvents.size(); j++) {
                Session.SpkEvent e = s.spkEvents.get(j);
                if (e.t <= g.startMs) tag = e.tag; else break;
            }
            g.speaker = tag;
        }
    }

    /** BREAK 时刻指向的说话人：事件轴里时间精确命中优先，否则取不超过它的最近事件。 */
    private static int speakerTagAt(Session s, long t) {
        int tag = -1;
        for (int i = 0; i < s.spkEvents.size(); i++) {
            Session.SpkEvent e = s.spkEvents.get(i);
            if (e.t == t) return e.tag;
            if (e.t < t) tag = e.tag;
        }
        return tag;
    }

    /** 扫描 /storage/emulated/0/<yyyy.MM>/*.wav，按文件名时间对齐 sid。 */
    private static void attachAudio(Map<Long, Session> map) {
        File[] months = SDCARD.listFiles();
        if (months == null) return;
        for (int i = 0; i < months.length; i++) {
            File d = months[i];
            if (!d.isDirectory() || !P_MONTH_DIR.matcher(d.getName()).matches()) continue;
            File[] ws = d.listFiles();
            if (ws == null) continue;
            for (int j = 0; j < ws.length; j++) {
                File w = ws[j];
                String n = w.getName();
                if (!n.toLowerCase(Locale.US).endsWith(".wav")) continue;
                Matcher m = P_WAV_NAME.matcher(n);
                if (!m.find()) continue;
                long ms;
                try {
                    synchronized (WAV_FMT) { ms = WAV_FMT.parse(m.group(1)).getTime(); }
                } catch (Throwable t) { continue; }
                long sid = ms / 1000L;
                Session s = map.get(sid);
                if (s == null) {
                    s = new Session();
                    s.sid = sid;
                    s.startMs = ms;
                    map.put(sid, s);
                }
                if (s.wavPath == null) s.wavPath = w.getAbsolutePath();
            }
        }
    }

    public static String fmtTime(long ms) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(ms));
    }

    public static String fmtDuration(long ms) {
        long s = ms / 1000;
        long m = s / 60;
        return String.format(Locale.US, "%02d:%02d", m, s % 60);
    }

    public static String fmtStamp(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%02d:%02d.%02d", s / 60, s % 60, (ms % 1000) / 10);
    }

    private static String find(Pattern p, String line) {
        Matcher m = p.matcher(line);
        return m.find() ? m.group(1) : "";
    }

    private static long parseLong(String s, long def) {
        if (s == null || s.length() == 0) return def;
        try { return Long.parseLong(s); } catch (Throwable t) { return def; }
    }
}
