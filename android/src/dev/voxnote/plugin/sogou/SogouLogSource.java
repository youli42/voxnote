package dev.voxnote.plugin.sogou;

import dev.voxnote.ContentSource;
import dev.voxnote.Item;
import dev.voxnote.MdParser;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设备定制插件：从搜狗 S1 的日志与月目录里取"厂商已经识别好"的场次。
 *
 * 这不是核心能力——只有装了那个厂商 App、并且它的目录可读的设备才有意义。
 * 换一台手机、或者厂商改了格式，整个包删掉即可（核心只认 ContentSource）。
 *
 * 两类输入：
 *   1. /sdcard/sogou/log/app/tmlog.tm*  逐句识别结果（结构化、可重放）
 *   2. /sdcard/<年.月>/*.wav|*.md        厂商落盘的录音与旁车文稿
 *      /sdcard/sogou/md/<年.月>/*.md     旧模块抄的镜像（常只有头部）
 */
public class SogouLogSource implements ContentSource {

    private static final File ROOT = new File("/storage/emulated/0");
    private static final File LOG_DIR = new File(ROOT, "sogou/log/app");
    private static final File MIRROR = new File(ROOT, "sogou/md");

    private static final Pattern P_KEY = Pattern.compile("^(\\d{8}_\\d{6})");
    private static final Pattern P_MONTH = Pattern.compile("^\\d{4}\\.\\d{2}$");
    private static final SimpleDateFormat KEY = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    private final LogMonitor monitor = new LogMonitor();
    private int vendorFiles;

    public String id() {
        return "sogou-log";
    }

    public String label() {
        return "厂商日志";
    }

    public boolean available() {
        return LOG_DIR.isDirectory() || hasMonthDir();
    }

    public List<Item> scan(boolean force) {
        Map<String, Item> map = new LinkedHashMap<String, Item>();
        try {
            List<Session> sessions = force ? monitor.scan(true) : monitor.scanIfStale();
            for (int i = 0; i < sessions.size(); i++) merge(map, fromSession(sessions.get(i)));
        } catch (Throwable ignore) {
            // 日志坏了不影响厂商月目录的扫描
        }
        vendorFiles = 0;
        scanVendor(map);
        return new ArrayList<Item>(map.values());
    }

    public JSONObject info() {
        JSONObject o;
        try {
            o = monitor.info();
        } catch (Throwable t) {
            o = new JSONObject();
        }
        try {
            o.put("id", id());
            o.put("label", label());
            o.put("available", available());
            o.put("vendorFiles", vendorFiles);
            o.put("dir", LOG_DIR.getAbsolutePath());
        } catch (Throwable ignore) { }
        return o;
    }

    // ------------------------------------------------------------ 日志会话 → Item

    private Item fromSession(Session s) {
        Item it = new Item();
        it.id = keyOf(s.startMs);
        it.sid = s.sid;
        it.startMs = s.startMs;
        it.durMs = s.durationMs();
        if (s.mode != null) it.mode = s.mode;
        it.summary = s.summary == null ? "" : s.summary;
        it.errorCount = s.errorCount;
        it.source = "import";
        it.fromSource = id();
        if (s.wavPath != null && new File(s.wavPath).isFile()) it.audioPath = s.wavPath;
        for (int i = 0; i < s.segments.size(); i++) {
            Segment g = s.segments.get(i);
            if (g.kind != Segment.FINAL || g.text == null || g.text.length() == 0) continue;
            Item.Seg seg = new Item.Seg(g.startMs / 1000.0, g.endMs / 1000.0, g.text);
            seg.spk = g.speaker;
            it.segs.add(seg);
        }
        return it;
    }

    // ------------------------------------------------------------ 厂商文件 → Item

    private void scanVendor(Map<String, Item> map) {
        File[] tops = ROOT.listFiles();
        if (tops == null) return;
        for (int i = 0; i < tops.length; i++) {
            if (tops[i].isDirectory() && P_MONTH.matcher(tops[i].getName()).matches()) {
                scanDir(tops[i], map);
            }
        }
        File[] mirrors = MIRROR.listFiles();
        if (mirrors != null) {
            for (int i = 0; i < mirrors.length; i++) {
                if (mirrors[i].isDirectory() && P_MONTH.matcher(mirrors[i].getName()).matches()) {
                    scanDir(mirrors[i], map);
                }
            }
        }
    }

    private void scanDir(File dir, Map<String, Item> map) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (int i = 0; i < fs.length; i++) {
            File f = fs[i];
            if (f.isDirectory()) continue;
            String name = f.getName();
            String ln = name.toLowerCase(Locale.US);
            if (ln.indexOf("_denoise") >= 0) continue;
            String key = keyOfName(name);
            if (key == null) continue;
            vendorFiles++;

            Item it = map.get(key);
            if (it == null) {
                it = new Item();
                it.id = key;
                it.startMs = parseKey(key);
                it.source = "import";
                it.fromSource = id();
                map.put(key, it);
            }
            if (isAudio(ln)) {
                if (it.audioPath == null || rank(ln) > rank(it.audioPath)) it.audioPath = f.getAbsolutePath();
            } else if (ln.endsWith(".md") || ln.endsWith(".txt")) {
                MdParser.Out o = MdParser.parse(f);
                if (o.segs.size() > it.segs.size()) {
                    it.segs.clear();
                    it.segs.addAll(o.segs);
                }
                if (it.summary.length() == 0 && o.summary.length() > 0) it.summary = o.summary;
                if (it.sid == 0 && o.sid > 0) it.sid = o.sid;
                if (it.durMs == 0 && o.durMs > 0) it.durMs = o.durMs;
                if (it.mode.length() == 0 && o.mode.length() > 0) it.mode = o.mode;
                if (it.errorCount == 0 && o.errorCount > 0) it.errorCount = o.errorCount;
                it.mdPath = f.getAbsolutePath();
            }
        }
    }

    private void merge(Map<String, Item> map, Item src) {
        Item it = map.get(src.id);
        if (it == null) {
            map.put(src.id, src);
            return;
        }
        if (it.sid == 0) it.sid = src.sid;
        if (it.durMs <= 0) it.durMs = src.durMs;
        if (it.mode.length() == 0) it.mode = src.mode;
        if (it.summary.length() == 0) it.summary = src.summary;
        if (it.errorCount == 0) it.errorCount = src.errorCount;
        if (it.audioPath == null) it.audioPath = src.audioPath;
        if (it.segs.isEmpty()) it.segs.addAll(src.segs);
    }

    // ------------------------------------------------------------ 工具

    private static boolean hasMonthDir() {
        File[] tops = ROOT.listFiles();
        if (tops == null) return false;
        for (int i = 0; i < tops.length; i++) {
            if (tops[i].isDirectory() && P_MONTH.matcher(tops[i].getName()).matches()) return true;
        }
        return false;
    }

    private static boolean isAudio(String ln) {
        return ln.endsWith(".wav") || ln.endsWith(".mp3") || ln.endsWith(".m4a")
                || ln.endsWith(".aac") || ln.endsWith(".amr") || ln.endsWith(".ogg")
                || ln.endsWith(".3gp");
    }

    private static int rank(String name) {
        String ln = name == null ? "" : name.toLowerCase(Locale.US);
        if (ln.endsWith(".wav")) return 4;
        if (ln.endsWith(".m4a")) return 3;
        if (ln.endsWith(".mp3")) return 2;
        return 1;
    }

    private static String keyOfName(String name) {
        Matcher m = P_KEY.matcher(name);
        return m.find() ? m.group(1) : null;
    }

    private static String keyOf(long ms) {
        synchronized (KEY) {
            return KEY.format(new Date(ms));
        }
    }

    private static long parseKey(String key) {
        try {
            synchronized (KEY) {
                return KEY.parse(key).getTime();
            }
        } catch (Throwable t) {
            return 0;
        }
    }
}
