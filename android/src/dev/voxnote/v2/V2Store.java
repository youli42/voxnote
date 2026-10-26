package dev.voxnote.v2;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 客户端 v2 本地存储：录音落库、outbox、同步状态、展示状态映射。
 *
 * 单写者：所有写操作 synchronized；读多写少，单用户场景足够。
 */
public class V2Store {

    private static V2Store instance;

    private final Context ctx;
    private final V2Db helper;

    private V2Store(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.helper = new V2Db(this.ctx);
        this.helper.getWritableDatabase();
    }

    public static synchronized V2Store get(Context ctx) {
        if (instance == null) instance = new V2Store(ctx);
        return instance;
    }

    private SQLiteDatabase db() {
        return helper.getWritableDatabase();
    }

    public Context getContext() {
        return ctx;
    }

    // ------------------------------------------------------------ 录音落库

    /** 录完一条：写本地记录 + 上传 outbox（网络由 V2Sync 后台推）。 */
    public synchronized String recordCaptured(String legacyStamp, String title,
                                              String audioLoc, long bytes,
                                              long durMs, String sha256) {
        String id = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("id", id);
        v.put("legacy_id", legacyStamp);
        v.put("title", title == null ? "" : title);
        v.put("status", "local");
        v.put("recorded_at", now);
        v.put("dur_ms", durMs);
        v.put("bytes", bytes);
        v.put("audio_loc", audioLoc);
        v.put("audio_sha256", sha256);
        v.put("updated_at", now);
        db().insertOrThrow("records", null, v);

        try {
            JSONObject meta = new JSONObject();
            meta.put("id", id);
            meta.put("legacy_id", legacyStamp == null ? "" : legacyStamp);
            meta.put("title", title == null ? "" : title);
            meta.put("recorded_at", now);
            meta.put("dur_ms", durMs);
            meta.put("bytes", bytes);
            meta.put("audio_sha256", sha256 == null ? "" : sha256);
            meta.put("audio_loc", audioLoc == null ? "" : audioLoc);
            enqueue("upload_record", id, meta);
        } catch (Exception ignore) {
            // 本地记录已落库；outbox 失败时用户仍能在本地看到
        }
        return id;
    }

    // ------------------------------------------------------------ outbox

    public synchronized void enqueue(String kind, String entityId, JSONObject payload) {
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("op_id", UUID.randomUUID().toString().replace("-", ""));
        v.put("kind", kind);
        v.put("entity_id", entityId);
        v.put("payload", payload == null ? "{}" : payload.toString());
        v.put("state", "pending");
        v.put("created_at", now);
        v.put("updated_at", now);
        db().insertWithOnConflict("outbox", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void enqueueUpdate(String recordId, JSONObject fields) {
        try {
            JSONObject rec = getRecord(recordId);
            JSONObject p = new JSONObject();
            p.put("fields", fields);
            p.put("base_rev", rec == null ? 0 : rec.optInt("rev", 0));
            enqueue("update_record", recordId, p);
        } catch (Exception ignore) {
        }
    }

    public synchronized void enqueueDelete(String recordId) {
        try {
            JSONObject rec = getRecord(recordId);
            JSONObject p = new JSONObject();
            p.put("base_rev", rec == null ? 0 : rec.optInt("rev", 0));
            enqueue("tombstone_record", recordId, p);
            ContentValues v = new ContentValues();
            v.put("deleted", 1);
            v.put("updated_at", System.currentTimeMillis());
            db().update("records", v, "id=?", new String[]{recordId});
        } catch (Exception ignore) {
        }
    }

    public synchronized List<JSONObject> pendingOutbox(int limit) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        Cursor c = db().query("outbox", null,
                "state IN ('pending','failed')", null, null, null,
                "created_at ASC", String.valueOf(Math.max(1, limit)));
        try {
            while (c.moveToNext()) out.add(cursorToJson(c));
        } finally {
            c.close();
        }
        return out;
    }

    public synchronized void markOutbox(String opId, String state, String error) {
        ContentValues v = new ContentValues();
        v.put("state", state);
        v.put("last_error", error == null ? "" : error);
        v.put("updated_at", System.currentTimeMillis());
        v.put("attempts", outboxAttempts(opId) + 1);
        db().update("outbox", v, "op_id=?", new String[]{opId});
    }

    private int outboxAttempts(String opId) {
        Cursor c = db().rawQuery("SELECT attempts FROM outbox WHERE op_id=?",
                new String[]{opId});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public synchronized int pendingCount(String recordId) {
        Cursor c = db().rawQuery(
                "SELECT COUNT(*) FROM outbox WHERE entity_id=?"
                        + " AND state IN ('pending','failed','syncing')",
                new String[]{recordId});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public synchronized boolean hasConflict(String recordId) {
        Cursor c = db().rawQuery(
                "SELECT COUNT(*) FROM conflicts WHERE record_id=? AND resolved_at IS NULL",
                new String[]{recordId});
        try {
            return c.moveToFirst() && c.getInt(0) > 0;
        } finally {
            c.close();
        }
    }

    public synchronized void addConflict(String recordId, String kind,
                                         JSONObject local, JSONObject server) {
        ContentValues v = new ContentValues();
        v.put("id", UUID.randomUUID().toString().replace("-", ""));
        v.put("record_id", recordId);
        v.put("kind", kind == null ? "" : kind);
        v.put("local_payload", local == null ? "{}" : local.toString());
        v.put("server_payload", server == null ? "{}" : server.toString());
        v.put("created_at", System.currentTimeMillis());
        db().insert("conflicts", null, v);
    }

    // ------------------------------------------------------------ 记录读写

    public synchronized JSONObject getRecord(String id) {
        Cursor c = db().query("records", null, "id=?", new String[]{id},
                null, null, null);
        try {
            return c.moveToFirst() ? cursorToJson(c) : null;
        } finally {
            c.close();
        }
    }

    public synchronized JSONArray listRecords() {
        JSONArray out = new JSONArray();
        Cursor c = db().query("records", null, "deleted=0", null,
                null, null, "recorded_at DESC");
        try {
            while (c.moveToNext()) {
                JSONObject o = cursorToJson(c);
                try {
                    o.put("pending", pendingCount(o.optString("id")));
                    o.put("conflict", hasConflict(o.optString("id")));
                    o.put("display", displayStatus(o));
                } catch (Exception ignore) {
                }
                out.put(o);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public synchronized void markLocalDeleted(String id) {
        ContentValues v = new ContentValues();
        v.put("deleted", 1);
        v.put("status", "deleted");
        v.put("updated_at", System.currentTimeMillis());
        db().update("records", v, "id=?", new String[]{id});
    }

    /** 上传成功后：服务器给了 rev，本地状态进入 uploaded。 */
    public synchronized void setUploaded(String id, int rev) {
        ContentValues v = new ContentValues();
        v.put("rev", rev);
        v.put("status", "uploaded");
        v.put("updated_at", System.currentTimeMillis());
        db().update("records", v, "id=?", new String[]{id});
    }

    /** 服务器 change / manifest 拉下来的记录：只覆盖元数据，本地字段保留。 */
    public synchronized void applyServerRecord(JSONObject r) {
        if (r == null) return;
        String id = r.optString("id", "");
        if (id.length() == 0) return;
        boolean exists = getRecord(id) != null;
        ContentValues v = new ContentValues();
        v.put("rev", r.optInt("rev", 0));
        v.put("title", r.optString("title", ""));
        v.put("cat", r.isNull("cat") ? null : r.optString("cat"));
        v.put("marks", r.optString("marks", "[]"));
        v.put("deleted", r.optBoolean("deleted", false) ? 1 : 0);
        v.put("status", r.optString("status", exists ? "synced" : "synced"));
        v.put("dur_ms", r.optLong("dur_ms", 0));
        v.put("recorded_at", r.optLong("recorded_at", 0));
        v.put("active_attempt_id", r.isNull("active_attempt_id")
                ? null : r.optString("active_attempt_id"));
        v.put("error_code", r.optString("error_code", ""));
        v.put("error_hint", r.optString("error_hint", ""));
        v.put("updated_at", System.currentTimeMillis());
        if (exists) {
            db().update("records", v, "id=?", new String[]{id});
        } else {
            v.put("id", id);
            v.put("legacy_id", r.optString("legacy_id", ""));
            db().insertWithOnConflict("records", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        }
    }

    // ------------------------------------------------------------ 状态与同步状态

    /** 本地 + 服务器 + outbox → 一个展示状态（不依赖文件存在性）。 */
    public static String displayStatus(JSONObject rec) {
        if (rec == null) return "未知";
        if (rec.optBoolean("deleted", false)) return "已删除";
        if (rec.optBoolean("conflict", false)) return "同步冲突（待选择）";
        if (rec.optInt("pending", 0) > 0) return "待上传";
        String status = rec.optString("status", "");
        if ("failed".equals(status)) return "失败";
        if (rec.optString("active_attempt_id", "").length() > 0) return "已转写";
        if ("uploaded".equals(status)) return "排队中";
        if ("transcribing".equals(status) || "queued".equals(status)) return "转写中";
        if ("local".equals(status)) return "仅本机";
        return "已同步";
    }

    public synchronized String getSyncValue(String key, String def) {
        Cursor c = db().rawQuery("SELECT v FROM sync_state WHERE k=?",
                new String[]{key});
        try {
            return c.moveToFirst() ? c.getString(0) : def;
        } finally {
            c.close();
        }
    }

    public synchronized void setSyncValue(String key, String value) {
        ContentValues v = new ContentValues();
        v.put("k", key);
        v.put("v", value == null ? "" : value);
        db().insertWithOnConflict("sync_state", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ------------------------------------------------------------ 工具

    public static String sha256(File f) {
        InputStream in = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            in = new FileInputStream(f);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (Throwable t) {
            return "";
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignore) { }
        }
    }

    public static InputStream openAudio(Context ctx, String loc) throws Exception {
        if (loc == null) throw new IllegalStateException("没有音频位置");
        if (loc.startsWith("path:")) {
            return new FileInputStream(new File(loc.substring(5)));
        }
        if (loc.startsWith("uri:")) {
            return ctx.getContentResolver().openInputStream(Uri.parse(loc.substring(4)));
        }
        return new FileInputStream(new File(loc));
    }

    public static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static JSONObject cursorToJson(Cursor c) {
        JSONObject o = new JSONObject();
        for (int i = 0; i < c.getColumnCount(); i++) {
            String name = c.getColumnName(i);
            try {
                switch (c.getType(i)) {
                    case Cursor.FIELD_TYPE_NULL:
                        o.put(name, JSONObject.NULL);
                        break;
                    case Cursor.FIELD_TYPE_INTEGER:
                        o.put(name, c.getLong(i));
                        break;
                    case Cursor.FIELD_TYPE_FLOAT:
                        o.put(name, c.getDouble(i));
                        break;
                    default:
                        o.put(name, c.getString(i));
                }
            } catch (Exception ignore) {
            }
        }
        return o;
    }
}
