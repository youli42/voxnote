package dev.voxnote.v2;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import dev.voxnote.UiSettings;

/**
 * 同步中心：一眼看清"在等什么/失败了什么/哪个冲突要选"。
 * 入口：记录页顶部同步条。不做复杂管理，只给状态 + 重试 + 冲突二选一。
 */
public class V2SyncCenter {

    public static void show(final Activity act) {
        final V2Store store = V2Store.get(act);
        UiSettings s = UiSettings.load(act);
        int pad = dp(act, 16);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(act, 8), pad, dp(act, 8));

        addText(act, root, "服务器：" + (V2Sync.hasServer(act)
                ? s.serverUrl : "未配置（去设置里填地址与令牌）"), 13.5f, 0xFFE8EAED);
        addText(act, root, "待上传 " + store.pendingTotal()
                + " · 失败 " + store.failedTotal()
                + " · 冲突 " + store.conflictCount(), 13f, 0xFFB7BDC7);

        String last = store.getSyncValue("last_sync_at", "");
        if (last != null && last.length() > 0) {
            try {
                long t = Long.parseLong(last);
                addText(act, root, "上次同步：" + DateFormat.format("MM-dd HH:mm", t),
                        12f, 0xFF8A919C);
            } catch (Exception ignore) {
            }
        }
        String err = act.getSharedPreferences("voxnote.v2", 0)
                .getString("last_error", "");
        if (err != null && err.length() > 0) {
            addText(act, root, "最近错误：" + err, 12f, 0xFFFF9A9A);
        }

        JSONArray conflicts = store.openConflicts();
        if (conflicts.length() > 0) {
            addText(act, root, "待选择的冲突", 13f, 0xFFFFD479);
        }
        for (int i = 0; i < conflicts.length(); i++) {
            final JSONObject c = conflicts.optJSONObject(i);
            if (c == null) continue;
            String cid = c.optString("id");
            String rid = c.optString("record_id");
            JSONObject rec = store.getRecord(rid);
            String title = rec == null ? rid : rec.optString("title", rid);
            JSONObject local = c.optJSONObject("local");
            JSONObject server = c.optJSONObject("server");
            String localText = local == null ? "" : String.valueOf(local);
            String serverText = server == null ? "" : String.valueOf(server);

            addText(act, root, "「" + title + "」", 13f, 0xFFE8EAED);
            addText(act, root, "本地：" + shorten(localText), 11.5f, 0xFF8A919C);
            addText(act, root, "服务器：" + shorten(serverText), 11.5f, 0xFF8A919C);

            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(act, 4), 0, dp(act, 10));
            row.addView(button(act, "用本地", new View.OnClickListener() {
                public void onClick(View v) {
                    V2Sync.resolveConflictAsync(act, cid, "local");
                }
            }), new LinearLayout.LayoutParams(0, dp(act, 40), 1f));
            row.addView(button(act, "用服务器", new View.OnClickListener() {
                public void onClick(View v) {
                    V2Sync.resolveConflictAsync(act, cid, "server");
                }
            }), new LinearLayout.LayoutParams(0, dp(act, 40), 1f));
            root.addView(row);
        }

        ScrollView scroll = new ScrollView(act);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(act)
                .setTitle("v2 同步中心")
                .setView(scroll)
                .setPositiveButton("立即同步", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        V2Sync.syncAsync(act);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private static void addText(Activity act, LinearLayout root, String text,
                                float sp, int color) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setPadding(0, dp(act, 4), 0, dp(act, 4));
        root.addView(t);
    }

    private static Button button(Activity act, String text, View.OnClickListener l) {
        Button b = new Button(act);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13f);
        b.setOnClickListener(l);
        return b;
    }

    private static String shorten(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static int dp(Activity act, int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }
}
