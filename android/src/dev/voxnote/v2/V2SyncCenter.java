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

        // 管理动作：诊断 / 引擎 / 备份 / 清理（服务端 v2 接口）
        LinearLayout tools = new LinearLayout(act);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setPadding(0, dp(act, 6), 0, dp(act, 6));
        tools.addView(button(act, "诊断", new View.OnClickListener() {
            public void onClick(View v) {
                adminAsync(act, "GET", "/api/v2/diagnostics", null, "服务器诊断");
            }
        }), new LinearLayout.LayoutParams(0, dp(act, 40), 1f));
        tools.addView(button(act, "引擎", new View.OnClickListener() {
            public void onClick(View v) {
                adminAsync(act, "GET", "/api/v2/engines", null, "已配置引擎");
            }
        }), new LinearLayout.LayoutParams(0, dp(act, 40), 1f));
        tools.addView(button(act, "备份", new View.OnClickListener() {
            public void onClick(View v) {
                adminAsync(act, "POST", "/api/v2/backups", new JSONObject(), "备份");
            }
        }), new LinearLayout.LayoutParams(0, dp(act, 40), 1f));
        tools.addView(button(act, "清理", new View.OnClickListener() {
            public void onClick(View v) {
                cleanupPreview(act);
            }
        }), new LinearLayout.LayoutParams(0, dp(act, 40), 1f));
        root.addView(tools);

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

    private static void adminAsync(final Activity act, final String method,
                                   final String path, final JSONObject body,
                                   final String title) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    final JSONObject r = V2Sync.admin(act, method, path, body);
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            showInfo(act, title, pretty(r));
                        }
                    });
                } catch (final Throwable t) {
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            showInfo(act, title + "失败", String.valueOf(t));
                        }
                    });
                }
            }
        }, "v2-admin").start();
    }

    private static void cleanupPreview(final Activity act) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    final JSONObject r = V2Sync.admin(act, "POST", "/api/v2/cleanup",
                            new JSONObject().put("dry_run", true));
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            String msg = "墓碑 " + r.optInt("tombstones")
                                    + " 条 · 旧尝试 " + r.optInt("attempts")
                                    + " 条 · 可释放 " + (r.optLong("bytes") / 1024) + " KB"
                                    + "\n\n确认后删除，不可撤销。";
                            new AlertDialog.Builder(act)
                                    .setTitle("清理预览")
                                    .setMessage(msg)
                                    .setPositiveButton("确认清理",
                                            new DialogInterface.OnClickListener() {
                                                public void onClick(DialogInterface d, int w) {
                                                    JSONObject body = new JSONObject();
                                                    try {
                                                        body.put("dry_run", false);
                                                    } catch (Exception ignore) {
                                                    }
                                                    adminAsync(act, "POST", "/api/v2/cleanup",
                                                            body, "清理");
                                                }
                                            })
                                    .setNegativeButton("取消", null)
                                    .show();
                        }
                    });
                } catch (final Throwable t) {
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            showInfo(act, "清理预览失败", String.valueOf(t));
                        }
                    });
                }
            }
        }, "v2-cleanup").start();
    }

    private static void showInfo(Activity act, String title, String msg) {
        new AlertDialog.Builder(act).setTitle(title).setMessage(msg)
                .setPositiveButton("好", null).show();
    }

    private static String pretty(JSONObject o) {
        try {
            return o.toString(2);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    private static int dp(Activity act, int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }
}
