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
 * 用户态数据：改名 / 分类 / 标记。
 *
 * 放应用私有目录（filesDir/app-state.json）：这是"不可再生的东西"，
 * 不该暴露在公共目录里；私有目录在卸载时会清空，所以另配"导出/导入"把它搬走
 * （导出还没做，见 README 的已知限制）。
 *
 * 老版本把这份文件放在 /sdcard/s1rec/app-state.json，首次启动读不到私有文件时
 * 会去老路径捞一次，读到就迁过来，不丢分类与标记。
 *
 * 结构： {"<itemId>": {"title":"…","cat":"c1","marks":[1.2,8.4]}}
 */
public class State {

    /** 旧的公共目录，只用于一次性迁移。 */
    public static final File LEGACY_DIR =
            new File(android.os.Environment.getExternalStorageDirectory(), "s1rec");

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
