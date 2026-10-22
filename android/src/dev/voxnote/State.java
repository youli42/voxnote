package dev.voxnote;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

/**
 * 用户态数据：改名 / 分类 / 标记 / 删除（屏蔽）。
 *
 * 放应用私有目录（filesDir/app-state.json）：这是"不可再生的东西"，
 * 不该暴露在公共目录里；私有目录在卸载时会清空，所以另配"导出/导入"把它搬走
 * （导出还没做，见 README 的已知限制）。
 *
 * 老版本把这份文件放在 /sdcard/s1rec/app-state.json，首次启动读不到私有文件时
 * 会去老路径捞一次，读到就迁过来，不丢分类与标记。
 *
 * 结构： {"<itemId>": {"title":"…","cat":"c1","marks":[1.2,8.4]},
 *         "__ignored": {"<itemId>": true}}
 *
 * "__ignored" 是"删除"的落点：厂商来源的场次是从日志重放出来的，删掉导出文件下次
 * 扫描还会回来，所以只能记成屏蔽（意图层），厂商的物理文件永久不动。
 */
public class State {

    /** 公共数据目录：导出物，以及按 sid 清理镜像时要比对的 sessions/。 */
    public static final File DATA_DIR =
            new File(android.os.Environment.getExternalStorageDirectory(), "s1rec");

    /** 旧的公共目录，只用于一次性迁移。 */
    public static final File LEGACY_DIR = DATA_DIR;

    private static final String KEY_IGNORED = "__ignored";

    private final File file;
    private JSONObject root = new JSONObject();
    private boolean dirty;

    public State(Context ctx) {
        file = new File(ctx.getFilesDir(), "app-state.json");
    }

    public synchronized State load() {
        if (file.exists()) {
            readInto(file);
            return this;
        }
        // 一次性迁移：老版本把状态放在公共目录
        File legacy = new File(LEGACY_DIR, "app-state.json");
        if (legacy.isFile()) {
            readInto(legacy);
            dirty = true;
            save();
        }
        return this;
    }

    private void readInto(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            String s = sb.toString().trim();
            if (s.length() > 0) root = new JSONObject(s);
        } catch (Throwable t) {
            root = new JSONObject();
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignore) { }
        }
    }

    public synchronized JSONObject of(String id) {
        return root.optJSONObject(id);
    }

    // ------------------------------------------------------------ 删除 / 屏蔽

    /** 这场是否已被"删除"（厂商来源只记屏蔽）。 */
    public synchronized boolean isIgnored(String id) {
        JSONObject box = root.optJSONObject(KEY_IGNORED);
        return box != null && id != null && box.has(id);
    }

    public synchronized void setIgnored(String id, boolean on) {
        if (id == null || id.length() == 0) return;
        try {
            JSONObject box = root.optJSONObject(KEY_IGNORED);
            if (box == null) box = new JSONObject();
            if (on) box.put(id, true);
            else box = without(box, id);
            root.put(KEY_IGNORED, box);
            dirty = true;
            save();
        } catch (Throwable ignore) { }
    }

    public synchronized int ignoredCount() {
        JSONObject box = root.optJSONObject(KEY_IGNORED);
        return box == null ? 0 : box.length();
    }

    /** 恢复全部被屏蔽的场次，返回恢复了几场。 */
    public synchronized int clearIgnored() {
        JSONObject box = root.optJSONObject(KEY_IGNORED);
        int n = box == null ? 0 : box.length();
        if (n > 0) {
            try {
                root = without(root, KEY_IGNORED);
                dirty = true;
                save();
            } catch (Throwable ignore) { }
        }
        return n;
    }

    /** 真删之后把该记录的用户态一起丢掉，别留下悬空条目。 */
    public synchronized void forget(String id) {
        if (id == null || !root.has(id)) return;
        try {
            root = without(root, id);
            dirty = true;
            save();
        } catch (Throwable ignore) { }
    }

    /** 复制一份去掉某个键的对象：不用 JSONObject.remove（老系统上不可靠）。 */
    private static JSONObject without(JSONObject src, String key) {
        JSONObject out = new JSONObject();
        JSONArray names = src == null ? null : src.names();
        if (names == null) return out;
        for (int i = 0; i < names.length(); i++) {
            String k = names.optString(i);
            if (k == null || k.equals(key)) continue;
            try {
                out.put(k, src.get(k));
            } catch (Throwable ignore) { }
        }
        return out;
    }

    /** 只合并给到的键；cat 传 JSONObject.NULL 表示"改为未分类"。 */
    public synchronized void patch(String id, JSONObject p) {
        if (id == null || id.length() == 0 || p == null) return;
        try {
            JSONObject cur = root.optJSONObject(id);
            if (cur == null) cur = new JSONObject();
            JSONArray names = p.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String k = names.optString(i);
                    cur.put(k, p.get(k));
                }
            }
            root.put(id, cur);
            dirty = true;
            save();
        } catch (Throwable t) {
            // 存不下也不该让 UI 崩
        }
    }

    /** 删除分类时把挂在它下面的记录一并清成未分类，避免留下悬空 id。 */
    public synchronized void clearCat(String catId) {
        if (catId == null || catId.length() == 0) return;
        try {
            JSONArray keys = root.names();
            if (keys == null) return;
            boolean changed = false;
            for (int i = 0; i < keys.length(); i++) {
                JSONObject o = root.optJSONObject(keys.optString(i));
                if (o == null) continue;
                if (catId.equals(o.optString("cat", ""))) {
                    o.put("cat", JSONObject.NULL);
                    changed = true;
                }
            }
            if (changed) {
                dirty = true;
                save();
            }
        } catch (Throwable ignore) { }
    }

    public synchronized void save() {
        if (!dirty && file.exists()) return;
        FileOutputStream fos = null;
        try {
            File tmp = new File(file.getAbsolutePath() + ".tmp");
            fos = new FileOutputStream(tmp);
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(fos, "UTF-8"));
            w.write(root.toString(1));
            w.close();
            fos = null;
            if (!tmp.renameTo(file)) {
                FileOutputStream f2 = new FileOutputStream(file);
                BufferedWriter w2 = new BufferedWriter(new OutputStreamWriter(f2, "UTF-8"));
                w2.write(root.toString(1));
                w2.close();
                tmp.delete();
            }
            dirty = false;
        } catch (Throwable t) {
            if (fos != null) try { fos.close(); } catch (Throwable ignore) { }
        }
    }
}
