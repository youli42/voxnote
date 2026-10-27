package dev.voxnote.v2;

import android.content.Context;

import org.json.JSONObject;

/**
 * 单条记录在 v2 同步栈里的状态，供列表卡片与详情页显示。
 *
 * <p>历史上这里是 v1 {@code ServerSync.SYNC_*}，现在统一由 v2 本地库推导，
 * 数值保持不变以兼容既有调用点。</p>
 */
public final class V2Status {

    public static final int NONE = 0;   // 未配服务器 / 没有对应的 v2 记录
    public static final int WAIT = 1;   // 待上传 / 有未完成的 outbox
    public static final int OK = 2;     // 已同步
    public static final int FAIL = 3;   // 上传或转写失败

    private V2Status() { }

    /** 是否已配对 v2 服务器（地址 + 令牌）。 */
    public static boolean hasServer(Context ctx) {
        return V2Sync.hasServer(ctx);
    }

    /** 记录在 v2 栈里的状态；不认识这条记录返回 {@link #NONE}。 */
    public static int of(Context ctx, String legacyStamp) {
        if (!hasServer(ctx)) return NONE;
        if (legacyStamp == null || legacyStamp.length() == 0) return NONE;
        try {
            V2Store st = V2Store.get(ctx);
            JSONObject rec = st.findByLegacy(legacyStamp);
            return of(st, rec);
        } catch (Throwable t) {
            return NONE;
        }
    }

    /** 直接按 v2 记录推导（避免重复查一次）。 */
    public static int of(V2Store st, JSONObject rec) {
        if (rec == null) return NONE;
        if (rec.optBoolean("conflict", false)) return FAIL;
        if (rec.optInt("pending", 0) > 0) return WAIT;
        if ("failed".equals(rec.optString("status", ""))) return FAIL;
        return OK;
    }

    /** 供 UI 直接显示的短文案。 */
    public static String label(int state) {
        if (state == WAIT) return "待上传";
        if (state == OK) return "已同步";
        if (state == FAIL) return "同步异常";
        return "";
    }
}
