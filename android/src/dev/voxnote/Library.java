package dev.voxnote;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把"自己的文件"和"外部来源给的场次"拼成一张卡片列表。
 *
 * 自己的文件来自 {@link Storage}（私有目录 / 公共目录 / SAF 自定义目录），
 * 外部来源来自 {@link ContentSource} 插件（例如本机的厂商日志）。
 * 两边都按文件名前 15 位（yyyyMMdd_HHmmss）归并：缺的字段互相补，不重复出卡片。
 */
public class Library {

    private static final Pattern P_KEY = Pattern.compile("^(\\d{8}_\\d{6})");
    private static final Pattern P_SID_IN_NAME = Pattern.compile("_(\\d{10})\\.[A-Za-z0-9]+$");
    private static final Pattern P_SUFFIX = Pattern.compile("_([A-Za-z]+)\\.[A-Za-z0-9]+$");

    private static final SimpleDateFormat KEY_FMT =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
    private static final SimpleDateFormat TITLE_FMT =
            new SimpleDateFormat("HH:mm", Locale.US);

    private final Context ctx;
    private final State state;
    private final Storage storage;
    private String lastError = "";
    private int fileCount;

    public Library(Context ctx, State state, Storage storage) {
        this.ctx = ctx;
        this.state = state;
        this.storage = storage;
    }

    public String error() {
        return lastError;
    }

    // ---------------------------------------------------------------- 对外

    public List<Item> scan(List<Item> imported) {
        Map<String, Item> map = new LinkedHashMap<String, Item>();
        fileCount = 0;
        lastError = "";

        List<Storage.Blob> blobs = storage.scanOwn();
        for (int i = 0; i < blobs.size(); i++) applyBlob(map, blobs.get(i));

        mergeImported(map, imported);

        List<Item> out = new ArrayList<Item>();
        for (Item it : map.values()) {
            finish(it);
            // 三无记录（没音频、没正文、没摘要）不展示，对应数据契约里的 empty
            if (it.audioPath == null && it.audioUri == null && it.segs.isEmpty()
                    && it.summary.length() == 0) continue;
            out.add(it);
        }
        Collections.sort(out, new Comparator<Item>() {
            public int compare(Item a, Item b) {
                if (a.startMs != b.startMs) return a.startMs > b.startMs ? -1 : 1;
                return a.id.compareTo(b.id);
            }
        });
        return out;
    }

    // ---------------------------------------------------------------- 自己的文件

    private void applyBlob(Map<String, Item> map, Storage.Blob b) {
        if (b == null || b.name == null) return;
        String key = keyOf(b.name);
        if (key == null) return;
        Item it = map.get(key);
        if (it == null) {
            it = new Item();
            it.id = key;
            it.startMs = parseKey(key);
            map.put(key, it);
        }
        fileCount++;

        if (b.isAudio()) {
            int rank = audioRank(b.name);
            String cur = it.audioPath != null ? it.audioPath : it.audioUri;
            if (cur == null || rank > audioRank(cur)) {
                it.audioPath = b.path;
                it.audioUri = b.uri;
                long d = durMs(b);
                if (d > it.durMs) it.durMs = d;
            }
        } else {
            MdParser.Out o = MdParser.parse(open(b));
            applyText(it, o);
            it.mdPath = b.path;
            it.mdUri = b.uri;
        }
    }

    private java.io.InputStream open(Storage.Blob b) {
        try {
            return b.open(ctx);
        } catch (Throwable t) {
            return null;
        }
    }

    private void applyText(Item it, MdParser.Out o) {
        if (o == null) return;
        if (o.segs.size() > it.segs.size()) {
            it.segs.clear();
            it.segs.addAll(o.segs);
        }
        if (it.summary.length() == 0 && o.summary.length() > 0) it.summary = o.summary;
        if (it.sid == 0 && o.sid > 0) it.sid = o.sid;
        if (it.durMs == 0 && o.durMs > 0) it.durMs = o.durMs;
        if (it.mode.length() == 0 && o.mode.length() > 0) it.mode = o.mode;
        if (it.errorCount == 0 && o.errorCount > 0) it.errorCount = o.errorCount;
    }

    // ---------------------------------------------------------------- 外部来源

    private void mergeImported(Map<String, Item> map, List<Item> imported) {
        if (imported == null) return;
        for (int i = 0; i < imported.size(); i++) {
            Item src = imported.get(i);
            if (src == null || src.id == null || src.id.length() == 0) continue;
            Item it = map.get(src.id);
            if (it == null) {
                it = new Item();
                it.id = src.id;
                it.startMs = src.startMs;
                it.source = "import";
                it.fromSource = src.fromSource;
                map.put(src.id, it);
            }
            if (it.sid == 0) it.sid = src.sid;
            if (it.startMs <= 0) it.startMs = src.startMs;
            if (it.durMs <= 0) it.durMs = src.durMs;
            if (it.mode.length() == 0 && src.mode.length() > 0) it.mode = src.mode;
            if (it.summary.length() == 0 && src.summary.length() > 0) it.summary = src.summary;
            if (it.errorCount == 0) it.errorCount = src.errorCount;
            if (it.audioPath == null && it.audioUri == null) {
                it.audioPath = src.audioPath;
                it.audioUri = src.audioUri;
            }
            if (it.mdPath == null && it.mdUri == null) {
                it.mdPath = src.mdPath;
                it.mdUri = src.mdUri;
            }
            if (it.segs.isEmpty() && !src.segs.isEmpty()) it.segs.addAll(src.segs);
            if (it.fromSource == null) it.fromSource = src.fromSource;
        }
    }

    // ---------------------------------------------------------------- 收尾

    private void finish(Item it) {
        if (it.mode.length() == 0) {
            String base = it.audioPath != null ? it.audioPath
                    : (it.mdPath != null ? it.mdPath : basename(it.audioUri));
            Matcher m = P_SUFFIX.matcher(basename(base));
            if (m.find()) it.mode = m.group(1).toLowerCase(Locale.US);
        }
        if (it.sid == 0) {
            Matcher m = P_SID_IN_NAME.matcher(basename(it.mdPath));
            if (m.find()) {
                try { it.sid = Long.parseLong(m.group(1)); } catch (Throwable ignore) { }
            }
        }
        if (it.durMs <= 0 && it.segs.size() > 0) {
            it.durMs = (long) (it.segs.get(it.segs.size() - 1).end * 1000);
        }
        if (it.summary.length() == 0) it.summary = summarize(it.segs);
        if (it.title.length() == 0) {
            long t = it.startMs > 0 ? it.startMs : System.currentTimeMillis();
            synchronized (TITLE_FMT) {
                it.title = "录音 " + TITLE_FMT.format(new Date(t));
            }
        }
        if (it.startMs <= 0) it.startMs = System.currentTimeMillis();
    }

    private static String summarize(List<Item.Seg> segs) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < segs.size() && i < 2; i++) b.append(segs.get(i).text);
        String s = b.toString().trim();
        if (s.length() > 58) s = s.substring(0, 58) + "…";
        return s;
    }

    // ---------------------------------------------------------------- 工具

    private static int audioRank(String name) {
        String ln = name == null ? "" : name.toLowerCase(Locale.US);
        if (ln.endsWith(".wav")) return 4;
        if (ln.endsWith(".m4a") || ln.endsWith(".mp4")) return 3;
        if (ln.endsWith(".mp3")) return 2;
        return 1;
    }

    private static String basename(String p) {
        if (p == null) return "";
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    private static String keyOf(String name) {
        Matcher m = P_KEY.matcher(name);
        return m.find() ? m.group(1) : null;
    }

    private static long parseKey(String key) {
        try {
            synchronized (KEY_FMT) {
                return KEY_FMT.parse(key).getTime();
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    /** WAV 读文件头；其它容器或 content:// 交给系统探测。 */
    private long durMs(Storage.Blob b) {
        String name = b.name == null ? "" : b.name.toLowerCase(Locale.US);
        if (name.endsWith(".wav") && b.path != null) {
            long d = wavDurMs(new File(b.path));
            if (d > 0) return d;
        }
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            if (b.uri != null && b.uri.length() > 0) {
                r.setDataSource(ctx, Uri.parse(b.uri));
            } else {
                r.setDataSource(b.path);
            }
            String ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (ms != null) return Long.parseLong(ms.trim());
        } catch (Throwable t) {
            // 时长未知，UI 显示 00:00
        } finally {
            try { r.release(); } catch (Throwable ignore) { }
        }
        return 0;
    }

    private static long wavDurMs(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] h = new byte[64 * 1024];
            int n = in.read(h);
            if (n < 44) return 0;
            if (h[0] != 'R' || h[1] != 'I' || h[2] != 'F' || h[3] != 'F') return 0;
            if (h[8] != 'W' || h[9] != 'A' || h[10] != 'V' || h[11] != 'E') return 0;
            long byteRate = 0;
            if (h[12] == 'f' && h[13] == 'm' && h[14] == 't' && h[15] == ' ') {
                byteRate = u32(h, 28);
            }
            for (int p = 12; p + 8 < n; p++) {
                if (h[p] == 'd' && h[p + 1] == 'a' && h[p + 2] == 't' && h[p + 3] == 'a') {
                    long size = u32(h, p + 4);
                    if (byteRate > 0 && size > 0) return size * 1000L / byteRate;
                    break;
                }
            }
        } catch (Throwable t) {
            return 0;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignore) { }
        }
        return 0;
    }

    private static long u32(byte[] b, int off) {
        if (off + 4 > b.length) return 0;
        return (b[off] & 0xffL) | ((b[off + 1] & 0xffL) << 8)
                | ((b[off + 2] & 0xffL) << 16) | ((b[off + 3] & 0xffL) << 24);
    }
}
