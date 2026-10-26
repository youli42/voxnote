package dev.voxnote.v2;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * 客户端 v2 本地库（framework SQLite，无第三方依赖）。
 *
 * 与服务器 v2 的领域模型同构：records / attempts / segments / edits / outbox /
 * sync_state / conflicts。破坏性重建阶段：schema 变更直接重建，不做迁移。
 */
public class V2Db extends SQLiteOpenHelper {

    public static final String NAME = "voxnote-v2.db";
    public static final int VERSION = 1;

    public V2Db(Context ctx) {
        super(ctx.getApplicationContext(), NAME, null, VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE records(" +
                "id TEXT PRIMARY KEY," +
                "legacy_id TEXT," +
                "rev INTEGER NOT NULL DEFAULT 0," +
                "title TEXT NOT NULL DEFAULT ''," +
                "cat TEXT," +
                "marks TEXT NOT NULL DEFAULT '[]'," +
                "status TEXT NOT NULL DEFAULT 'local'," +
                "recorded_at INTEGER NOT NULL," +
                "dur_ms INTEGER NOT NULL DEFAULT 0," +
                "bytes INTEGER NOT NULL DEFAULT 0," +
                "audio_loc TEXT," +
                "audio_sha256 TEXT," +
                "active_attempt_id TEXT," +
                "deleted INTEGER NOT NULL DEFAULT 0," +
                "error_code TEXT NOT NULL DEFAULT ''," +
                "error_hint TEXT NOT NULL DEFAULT ''," +
                "updated_at INTEGER NOT NULL DEFAULT 0)");

        db.execSQL("CREATE TABLE attempts(" +
                "id TEXT PRIMARY KEY," +
                "record_id TEXT NOT NULL," +
                "source TEXT NOT NULL DEFAULT ''," +
                "engine TEXT NOT NULL DEFAULT ''," +
                "model TEXT NOT NULL DEFAULT ''," +
                "quality_tier INTEGER NOT NULL DEFAULT 2," +
                "duration_ms INTEGER NOT NULL DEFAULT 0," +
                "created_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_attempts_record ON attempts(record_id)");

        db.execSQL("CREATE TABLE segments(" +
                "attempt_id TEXT NOT NULL," +
                "idx INTEGER NOT NULL," +
                "t_ms INTEGER NOT NULL DEFAULT 0," +
                "e_ms INTEGER NOT NULL DEFAULT 0," +
                "text TEXT NOT NULL DEFAULT ''," +
                "spk INTEGER NOT NULL DEFAULT -1," +
                "PRIMARY KEY(attempt_id, idx))");

        db.execSQL("CREATE TABLE edits(" +
                "id TEXT PRIMARY KEY," +
                "record_id TEXT NOT NULL," +
                "rev INTEGER NOT NULL DEFAULT 0," +
                "anchor_t_ms INTEGER NOT NULL DEFAULT 0," +
                "anchor_e_ms INTEGER NOT NULL DEFAULT 0," +
                "text TEXT," +
                "spk INTEGER," +
                "deleted INTEGER NOT NULL DEFAULT 0," +
                "edited_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_edits_record ON edits(record_id, anchor_t_ms)");

        db.execSQL("CREATE TABLE outbox(" +
                "op_id TEXT PRIMARY KEY," +
                "kind TEXT NOT NULL," +
                "entity_id TEXT NOT NULL," +
                "payload TEXT NOT NULL DEFAULT '{}'," +
                "state TEXT NOT NULL DEFAULT 'pending'," +
                "attempts INTEGER NOT NULL DEFAULT 0," +
                "last_error TEXT NOT NULL DEFAULT ''," +
                "created_at INTEGER NOT NULL DEFAULT 0," +
                "updated_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_outbox_state ON outbox(state, created_at)");

        db.execSQL("CREATE TABLE sync_state(k TEXT PRIMARY KEY, v TEXT NOT NULL DEFAULT '')");

        db.execSQL("CREATE TABLE conflicts(" +
                "id TEXT PRIMARY KEY," +
                "record_id TEXT NOT NULL," +
                "kind TEXT NOT NULL DEFAULT ''," +
                "local_payload TEXT NOT NULL DEFAULT '{}'," +
                "server_payload TEXT NOT NULL DEFAULT '{}'," +
                "created_at INTEGER NOT NULL DEFAULT 0," +
                "resolved_at INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 破坏性重建：不做迁移
        db.execSQL("DROP TABLE IF EXISTS conflicts");
        db.execSQL("DROP TABLE IF EXISTS sync_state");
        db.execSQL("DROP TABLE IF EXISTS outbox");
        db.execSQL("DROP TABLE IF EXISTS edits");
        db.execSQL("DROP TABLE IF EXISTS segments");
        db.execSQL("DROP TABLE IF EXISTS attempts");
        db.execSQL("DROP TABLE IF EXISTS records");
        onCreate(db);
    }
}
