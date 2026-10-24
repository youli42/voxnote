package dev.voxnote;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 原生 UI 的设置（分类、滑动映射、按键、转写 API、显示开关）。
 *
 * 与核心数据分开：核心（Storage/State）只管录音怎么落盘、用户态怎么存；
 * 这里只管“界面怎么摆”。存 SharedPreferences（voxnote.ui），不再用 WebView。
 *
 * 从旧版（WebView 的 localStorage['s1rec.v5']）升级时，由
 * {@link LegacySettingsImporter} 把那份 JSON 一次性导入 {@link #importLegacy(String)}，
 * 分类是用户手写的、不可再生，迁移必须保真。
 */
public class UiSettings {

    public static final String PREFS = "voxnote.ui";
    private static final String KEY_CATS = "categories";

    public boolean showFirstLine = true;
    public boolean autoTranscribe = false;
    public String provider = "rest";
    public String apiUrl = "https://asr.example.com/v1/transcribe";
    public String apiKey = "";
    public String model = "whisper-1";
    public String lang = "zh";
    public String keyMode = "none";
    public String keyBindingKey = "";
    public String keyBindingLabel = "";
    public String markKey = " ";
    public boolean sortDesc = true;
    /** 录音流：播完自动接下一条。 */
    public boolean streamAuto = false;
    public String swipeRightCat = "c1";
    public String swipeLeftCat = "c2";
    /** 自托管服务器（voxnote server）：地址形如 http://192.168.1.10:8787，令牌在控制台设置页可见。
     *  UI 线程写、同步 IO 线程读，volatile 保证改完立刻可见。 */
    public volatile String serverUrl = "";
    public volatile String serverToken = "";
    public boolean autoSync = false;
    public ArrayList<Cat> categories = new ArrayList<Cat>();

    private SharedPreferences prefs;

    public static class Cat {
        public String id;
        public String name;

        public Cat(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public static UiSettings load(Context c) {
        UiSettings s = new UiSettings();
        s.prefs = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        s.readFromPrefs();
        return s;
    }

    private void readFromPrefs() {
        showFirstLine = prefs.getBoolean("showFirstLine", true);
        autoTranscribe = prefs.getBoolean("autoTranscribe", false);
        provider = prefs.getString("provider", "rest");
        apiUrl = prefs.getString("apiUrl", "https://asr.example.com/v1/transcribe");
        apiKey = prefs.getString("apiKey", "");
        model = prefs.getString("model", "whisper-1");
        lang = prefs.getString("lang", "zh");
        keyMode = prefs.getString("keyMode", "none");
        keyBindingKey = prefs.getString("keyBindingKey", "");
        keyBindingLabel = prefs.getString("keyBindingLabel", "");
        markKey = prefs.getString("markKey", " ");
        sortDesc = prefs.getBoolean("sortDesc", true);
        streamAuto = prefs.getBoolean("streamAuto", false);
        swipeRightCat = prefs.getString("swipeRightCat", "c1");
        swipeLeftCat = prefs.getString("swipeLeftCat", "c2");
        serverUrl = prefs.getString("serverUrl", "");
        serverToken = prefs.getString("serverToken", "");
        autoSync = prefs.getBoolean("autoSync", false);

        categories = new ArrayList<Cat>();
        String raw = prefs.getString(KEY_CATS, "");
        if (raw != null && raw.length() > 0) {
            try {
                JSONArray a = new JSONArray(raw);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    String id = o.optString("id", "");
                    String name = o.optString("name", "");
                    if (id.length() == 0) continue;
                    categories.add(new Cat(id, name.length() == 0 ? "未命名" : name));
                }
            } catch (Throwable ignore) { }
        }
        if (categories.isEmpty()) seedDefaults();
        normalize();
    }

    private void seedDefaults() {
        categories.clear();
        categories.add(new Cat("c1", "重要"));
        categories.add(new Cat("c2", "归档"));
        categories.add(new Cat("c3", "工作"));
        swipeRightCat = "c1";
        swipeLeftCat = "c2";
    }

    /** 保证滑动的两个分类一定存在；分类名不为空。 */
    public void normalize() {
        if (categories.isEmpty()) seedDefaults();
        if (find(swipeRightCat) == null) swipeRightCat = categories.get(0).id;
        if (find(swipeLeftCat) == null) {
            swipeLeftCat = categories.get(Math.min(1, categories.size() - 1)).id;
        }
        if (markKey == null || markKey.length() == 0) markKey = " ";
    }

    public Cat find(String id) {
        if (id == null) return null;
        for (int i = 0; i < categories.size(); i++) {
            if (id.equals(categories.get(i).id)) return categories.get(i);
        }
        return null;
    }

    public String catName(String id) {
        Cat c = find(id);
        return c == null ? "" : c.name;
    }

    /** 服务器拉回的标签按名字对上本地分类；对不上由调用方用 {@link #newCatId()} 新建。 */
    public String catIdByName(String name) {
        if (name == null || name.length() == 0) return null;
        for (int i = 0; i < categories.size(); i++) {
            if (name.equals(categories.get(i).name)) return categories.get(i).id;
        }
        return null;
    }

    /** 下一个空闲的分类 id：cN 取现有最大 N+1（导入的分类可能有任意 id）。 */
    public String newCatId() {
        int max = 0;
        for (int i = 0; i < categories.size(); i++) {
            String id = categories.get(i).id;
            if (id != null && id.startsWith("c") && id.length() > 1) {
                try {
                    max = Math.max(max, Integer.parseInt(id.substring(1)));
                } catch (NumberFormatException ignore) { }
            }
        }
        return "c" + (max + 1);
    }

    /** 分类 id→名字的快照。categories 本体只在 UI 线程增删，而同步在 IO 线程
     *  里查名字——直接遍历会与 UI 线程的 add 并发抛 ConcurrentModificationException，
     *  同步开始前在 UI 线程拍一份只读快照带下去。 */
    public HashMap<String, String> catNameSnapshot() {
        HashMap<String, String> m = new HashMap<String, String>();
        for (int i = 0; i < categories.size(); i++) {
            Cat c = categories.get(i);
            if (c.id != null) m.put(c.id, c.name == null ? "" : c.name);
        }
        return m;
    }

    public void save() {
        if (prefs == null) return;
        normalize();
        JSONArray a = new JSONArray();
        for (int i = 0; i < categories.size(); i++) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", categories.get(i).id);
                o.put("name", categories.get(i).name);
                a.put(o);
            } catch (Throwable ignore) { }
        }
        prefs.edit()
                .putBoolean("showFirstLine", showFirstLine)
                .putBoolean("autoTranscribe", autoTranscribe)
                .putString("provider", nz(provider))
                .putString("apiUrl", nz(apiUrl))
                .putString("apiKey", nz(apiKey))
                .putString("model", nz(model))
                .putString("lang", nz(lang))
                .putString("keyMode", nz(keyMode))
                .putString("keyBindingKey", nz(keyBindingKey))
                .putString("keyBindingLabel", nz(keyBindingLabel))
                .putString("markKey", nz(markKey))
                .putBoolean("sortDesc", sortDesc)
                .putBoolean("streamAuto", streamAuto)
                .putString("swipeRightCat", swipeRightCat)
                .putString("swipeLeftCat", swipeLeftCat)
                .putString("serverUrl", nz(serverUrl))
                .putString("serverToken", nz(serverToken))
                .putBoolean("autoSync", autoSync)
                .putString(KEY_CATS, a.toString())
                .apply();
    }

    /**
     * 恢复界面默认值。**不动分类**：分类与滑动映射是用户手写的东西，
     * 一次“恢复默认”把它们删掉会连带所有记录的归类失效（旧 H5 就是这么丢的）。
     */
    public void resetDefaults() {
        showFirstLine = true;
        autoTranscribe = false;
        provider = "rest";
        apiUrl = "https://asr.example.com/v1/transcribe";
        apiKey = "";
        model = "whisper-1";
        lang = "zh";
        keyMode = "none";
        keyBindingKey = "";
        keyBindingLabel = "";
        markKey = " ";
        sortDesc = true;
        streamAuto = false;
        normalize();
        save();
    }

    /**
     * 导入旧版 localStorage['s1rec.v5'] 的 JSON。
     * 只覆盖旧版确实有的键；分类整体替换（旧版是唯一真相）。
     * 返回是否至少读到了东西。
     */
    public boolean importLegacy(String json) {
        boolean any = false;
        if (json == null || json.trim().length() == 0) return false;
        try {
            JSONObject o = new JSONObject(json);
            if (o.has("showFirstLine")) { showFirstLine = o.optBoolean("showFirstLine", showFirstLine); any = true; }
            if (o.has("autoTranscribe")) { autoTranscribe = o.optBoolean("autoTranscribe", autoTranscribe); any = true; }
            if (o.has("provider")) { provider = o.optString("provider", provider); any = true; }
            if (o.has("apiUrl")) { apiUrl = o.optString("apiUrl", apiUrl); any = true; }
            if (o.has("apiKey")) { apiKey = o.optString("apiKey", apiKey); any = true; }
            if (o.has("model")) { model = o.optString("model", model); any = true; }
            if (o.has("lang")) { lang = o.optString("lang", lang); any = true; }
            if (o.has("keyMode")) { keyMode = o.optString("keyMode", keyMode); any = true; }
            if (o.has("markKey")) { markKey = o.optString("markKey", markKey); any = true; }
            if (o.has("sortDesc")) { sortDesc = o.optBoolean("sortDesc", sortDesc); any = true; }
            if (o.has("swipeRightCat")) { swipeRightCat = o.optString("swipeRightCat", swipeRightCat); any = true; }
            if (o.has("swipeLeftCat")) { swipeLeftCat = o.optString("swipeLeftCat", swipeLeftCat); any = true; }
            JSONObject kb = o.optJSONObject("keyBinding");
            if (kb != null) {
                keyBindingKey = kb.optString("key", "");
                keyBindingLabel = kb.optString("label", "");
                any = true;
            }
            JSONArray cats = o.optJSONArray("categories");
            if (cats != null && cats.length() > 0) {
                ArrayList<Cat> list = new ArrayList<Cat>();
                for (int i = 0; i < cats.length(); i++) {
                    JSONObject c = cats.optJSONObject(i);
                    if (c == null) continue;
                    String id = c.optString("id", "");
                    String name = c.optString("name", "");
                    if (id.length() == 0) continue;
                    list.add(new Cat(id, name.length() == 0 ? "未命名" : name));
                }
                if (!list.isEmpty()) {
                    categories = list;
                    any = true;
                }
            }
        } catch (Throwable ignore) {
            return false;
        }
        if (any) save();
        return any;
    }

    public boolean legacyImported() {
        return prefs != null && prefs.getBoolean("legacyImported", false);
    }

    public void markLegacyImported() {
        if (prefs != null) prefs.edit().putBoolean("legacyImported", true).apply();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
