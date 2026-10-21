package dev.voxnote;

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
 * 单独放一份 /sdcard/s1rec/app-state.json，与机器派生的 sessions/、md 完全分离：
 * 重扫日志、重解析 md 都不会碰到它，符合"人写的东西永不被覆盖"。
 *
 * 结构： {"<itemId>": {"title":"…","cat":"c1","marks":[1.2,8.4]}}
 */
public class State {

    public static final File OUT_DIR = new File(Library.STORAGE, "s1rec");
    private final File file = new File(OUT_DIR, "app-state.json");
    private JSONObject root = new JSONObject();

    public synchronized State load() {
        FileInputStream in = null;
        try {
            if (!file.exists()) return this;
            in = new FileInputStream(file);
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
        return this;
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
            save();
        } catch (Throwable t) {
            // 存不下也不该让 UI 崩
        }
    }

    private void save() {
        FileOutputStream fos = null;
        try {
            if (!OUT_DIR.exists()) OUT_DIR.mkdirs();
            File tmp = new File(file.getAbsolutePath() + ".tmp");
            fos = new FileOutputStream(tmp);
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(fos, "UTF-8"));
            w.write(root.toString(1));
            w.close();
            fos = null;
            if (!tmp.renameTo(file)) {
                // 部分 FUSE 实现上 rename 覆盖会失败，退化为直接写
                FileOutputStream f2 = new FileOutputStream(file);
                BufferedWriter w2 = new BufferedWriter(new OutputStreamWriter(f2, "UTF-8"));
                w2.write(root.toString(1));
                w2.close();
                tmp.delete();
            }
        } catch (Throwable t) {
            if (fos != null) try { fos.close(); } catch (Throwable ignore) { }
        }
    }
}
