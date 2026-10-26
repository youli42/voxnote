package dev.voxnote.v2;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import dev.voxnote.UiSettings;

/**
 * v2 同步引擎（手机端）。
 *
 * 主路径：先推 outbox（上传/改名/删除），再按 rev 游标拉 changes；
 * 游标过期（resync_required）时改拉 manifest 对账。
 *
 * 网络与 v1 ServerSync 分开：v2 用 /api/v2 + Bearer 设备令牌；失败只记录，
 * 不阻塞录音与本地阅读。v1 不自动切换引擎/服务器。
 */
public class V2Sync {

    private static final String PREFS = "voxnote.v2";
    private static volatile boolean running;

    public static void syncAsync(final Context ctx) {
        if (running) return;
        running = true;
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                try {
                    syncBlocking(app);
                } catch (Throwable t) {
                    SharedPreferences p = app.getSharedPreferences(PREFS, 0);
                    p.edit().putString("last_error", String.valueOf(t)).apply();
                } finally {
                    running = false;
                }
            }
        }, "v2-sync").start();
    }

    public static boolean isRunning() {
        return running;
    }

    /** 是否已配置 v2 服务器（地址 + 令牌）。 */
    public static boolean hasServer(Context ctx) {
        return base(ctx).length() > 0 && token(ctx).length() > 0;
    }

    private static String base(Context ctx) {
        UiSettings s = UiSettings.load(ctx);
        String url = s.serverUrl == null ? "" : s.serverUrl.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    private static String token(Context ctx) {
        UiSettings s = UiSettings.load(ctx);
        return s.serverToken == null ? "" : s.serverToken.trim();
    }

    private static String deviceId(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, 0);
        String id = p.getString("device_id", "");
        if (id == null || id.length() == 0) {
            id = "dev-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            p.edit().putString("device_id", id).apply();
        }
        return id;
    }

    public static void syncBlocking(Context ctx) throws Exception {
        String base = base(ctx);
        String token = token(ctx);
        if (base.length() == 0 || token.length() == 0) return;

        V2Store store = V2Store.get(ctx);
        String device = deviceId(ctx);
        pushOutbox(store, base, token, device);
        pullChanges(store, base, token);

        SharedPreferences p = ctx.getSharedPreferences(PREFS, 0);
        p.edit().putLong("last_sync_at", System.currentTimeMillis())
                .putString("last_error", "").apply();
        store.setSyncValue("last_sync_at", String.valueOf(System.currentTimeMillis()));
    }

    // ------------------------------------------------------------ push

    private static void pushOutbox(V2Store store, String base, String token,
                                   String device) {
        List<JSONObject> rows = store.pendingOutbox(20);
        for (JSONObject row : rows) {
            String opId = row.optString("op_id");
            String kind = row.optString("kind");
            String recordId = row.optString("entity_id");
            JSONObject payload;
            try {
                payload = new JSONObject(row.optString("payload", "{}"));
            } catch (Exception e) {
                store.markOutbox(opId, "failed", "outbox payload 损坏");
                continue;
            }
            try {
                if ("upload_record".equals(kind)) {
                    uploadOne(store, base, token, payload);
                    store.markOutbox(opId, "done", "");
                } else {
                    JSONObject op = new JSONObject();
                    op.put("op_id", opId);
                    op.put("kind", kind);
                    op.put("record_id", recordId);
                    op.put("base_rev", payload.optInt("base_rev", 0));
                    op.put("fields", payload.optJSONObject("fields") == null
                            ? new JSONObject() : payload.optJSONObject("fields"));
                    JSONObject body = new JSONObject();
                    body.put("device_id", device);
                    body.put("ops", new JSONArray().put(op));
                    JSONObject result = postJson(base + "/api/v2/sync/ops",
                            token, body);
                    JSONObject r = result.optJSONArray("results") == null
                            || result.optJSONArray("results").length() == 0
                            ? null : result.optJSONArray("results").optJSONObject(0);
                    if (r == null) {
                        store.markOutbox(opId, "failed", "ops 返回为空");
                    } else if ("applied".equals(r.optString("status"))) {
                        store.markOutbox(opId, "done", "");
                        if (r.optJSONObject("record") != null) {
                            store.applyServerRecord(r.optJSONObject("record"));
                        }
                    } else if ("conflict".equals(r.optString("status"))) {
                        store.markOutbox(opId, "conflict",
                                "冲突，等待用户选择");
                        store.addConflict(recordId, kind,
                                payload, r.optJSONObject("record"));
                    } else {
                        store.markOutbox(opId, "failed",
                                r.optString("message", r.optString("code", "失败")));
                    }
                }
            } catch (Throwable t) {
                store.markOutbox(opId, "failed", String.valueOf(t));
                break;
            }
        }
    }

    private static void uploadOne(V2Store store, String base, String token,
                                  JSONObject meta) throws Exception {
        String id = meta.optString("id");
        String loc = meta.optString("audio_loc");
        String name = "audio";
        int dot = loc == null ? -1 : loc.lastIndexOf('.');
        if (dot >= 0) name = "audio" + loc.substring(dot);
        InputStream audio = V2Store.openAudio(store.getContext(), loc);
        JSONObject m = new JSONObject();
        m.put("id", id);
        m.put("legacy_id", meta.optString("legacy_id", ""));
        m.put("title", meta.optString("title", ""));
        m.put("recorded_at", meta.optLong("recorded_at", 0));
        m.put("dur_ms", meta.optLong("dur_ms", 0));
        byte[] resp = postMultipart(base + "/api/v2/sync/upload", token, m, audio, name);
        audio.close();
        JSONObject r = new JSONObject(new String(resp, "UTF-8"));
        store.setUploaded(r.optString("id", id), r.optInt("rev", 0));
    }

    // ------------------------------------------------------------ pull

    private static void pullChanges(V2Store store, String base, String token)
            throws Exception {
        long cursor = parseLong(store.getSyncValue("cursor", "0"));
        for (int guard = 0; guard < 100; guard++) {
            JSONObject ch = getJson(base + "/api/v2/sync/changes?cursor=" + cursor
                    + "&limit=200", token);
            JSONArray arr = ch.optJSONArray("changes");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.optJSONObject(i);
                    if (item == null) continue;
                    if ("record".equals(item.optString("kind"))) {
                        JSONObject rec = item.optJSONObject("record");
                        if (rec != null) {
                            store.applyServerRecord(rec);
                            syncTranscript(store, base, token, rec);
                        } else if (item.optBoolean("deleted", false)) {
                            store.markLocalDeleted(item.optString("entity_id"));
                        }
                    }
                }
            }
            cursor = ch.optLong("next_cursor", cursor);
            store.setSyncValue("cursor", String.valueOf(cursor));
            if (ch.optBoolean("resync_required", false)) {
                pullManifest(store, base, token);
            }
            if (!ch.optBoolean("has_more", false)) break;
        }
    }

    private static void pullManifest(V2Store store, String base, String token)
            throws Exception {
        String cursor = "";
        Set<String> seen = new HashSet<String>();
        for (int guard = 0; guard < 200; guard++) {
            JSONObject page = getJson(base + "/api/v2/sync/manifest?cursor="
                    + urlEncode(cursor) + "&limit=500", token);
            JSONArray items = page.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) continue;
                    String id = it.optString("id");
                    seen.add(id);
                    if (it.optBoolean("deleted", false)) {
                        store.markLocalDeleted(id);
                    } else {
                        JSONObject local = store.getRecord(id);
                        if (local == null
                                || local.optInt("rev", 0) != it.optInt("rev", 0)) {
                            try {
                                JSONObject rec = getJson(base + "/api/v2/records/"
                                        + urlEncode(id), token);
                                store.applyServerRecord(rec);
                                syncTranscript(store, base, token, rec);
                            } catch (Throwable ignore) {
                            }
                        }
                    }
                }
            }
            cursor = page.optString("next_cursor", cursor);
            if (!page.optBoolean("has_more", false)) break;
        }
        JSONArray local = store.listRecords();
        for (int i = 0; i < local.length(); i++) {
            JSONObject r = local.optJSONObject(i);
            if (r == null) continue;
            String id = r.optString("id");
            if (!seen.contains(id) && store.pendingCount(id) == 0) {
                store.markLocalDeleted(id);
            }
        }
    }

    private static void syncTranscript(V2Store store, String base, String token,
                                       JSONObject rec) {
        String id = rec.optString("id", "");
        String aid = rec.optString("active_attempt_id", "");
        if (id.length() == 0 || aid.length() == 0) return;
        if (store.hasTranscript(id)) return;
        try {
            JSONObject tr = getJson(base + "/api/v2/records/" + urlEncode(id)
                    + "/transcript", token);
            store.saveTranscript(tr);
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------ http

    private static JSONObject getJson(String url, String token) throws Exception {
        HttpURLConnection c = open(url, "GET", token);
        try {
            return new JSONObject(read(c));
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject postJson(String url, String token, JSONObject body)
            throws Exception {
        HttpURLConnection c = open(url, "POST", token);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setDoOutput(true);
        try {
            OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();
            return new JSONObject(read(c));
        } finally {
            c.disconnect();
        }
    }

    private static byte[] postMultipart(String url, String token, JSONObject meta,
                                        InputStream audio, String filename)
            throws Exception {
        String boundary = "----voxnote" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection c = open(url, "POST", token);
        c.setRequestProperty("Content-Type",
                "multipart/form-data; boundary=" + boundary);
        c.setChunkedStreamingMode(0);
        c.setDoOutput(true);
        try {
            OutputStream os = c.getOutputStream();
            write(os, "--" + boundary + "\r\n");
            write(os, "Content-Disposition: form-data; name=\"meta\"\r\n\r\n");
            write(os, meta.toString());
            write(os, "\r\n");
            write(os, "--" + boundary + "\r\n");
            write(os, "Content-Disposition: form-data; name=\"file\"; filename=\""
                    + filename + "\"\r\n");
            write(os, "Content-Type: application/octet-stream\r\n\r\n");
            byte[] buf = new byte[65536];
            int n;
            while ((n = audio.read(buf)) > 0) os.write(buf, 0, n);
            write(os, "\r\n--" + boundary + "--\r\n");
            os.close();
            return readBytes(c);
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String method, String token)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/json");
        return c;
    }

    private static String read(HttpURLConnection c) throws Exception {
        return new String(readBytes(c), "UTF-8");
    }

    private static byte[] readBytes(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) throw new IllegalStateException("HTTP " + code);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        if (code >= 400) {
            throw new IllegalStateException("HTTP " + code + ": "
                    + new String(bos.toByteArray(), "UTF-8"));
        }
        return bos.toByteArray();
    }

    private static void write(OutputStream os, String s) throws Exception {
        os.write(s.getBytes("UTF-8"));
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
