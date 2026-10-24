package dev.voxnote;

import android.app.Activity;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;

/**
 * 云端记录详情（全屏覆盖层，同 DetailSheet 的进出场方式）：
 * 从服务器拉生效转写稿逐句展示，可把文稿/音频取回本机。
 * 本机已有这条记录时只读，不再给取回按钮。
 */
public class ServerRecordSheet implements Overlay {

    private final Activity act;
    private final ServerSync sync;
    private final ServerScreen.Host host;
    private final Picker.Host pickerHost;
    private final ServerSync.ServerRec rec;
    private final FrameLayout wrap;
    private final LinearLayout sheet;
    private final TextView transText;
    private final LinearLayout btnRow;
    private final TextView importTextBtn;
    private final TextView importAudioBtn;
    private final TextView localTag;
    private boolean open;
    private boolean imported;

    public ServerRecordSheet(Activity act, ServerSync sync, ServerScreen.Host host,
                             Picker.Host pickerHost, ServerSync.ServerRec rec) {
        this.act = act;
        this.sync = sync;
        this.host = host;
        this.pickerHost = pickerHost;
        this.rec = rec;

        wrap = new FrameLayout(act);
        wrap.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wrap.setVisibility(View.GONE);

        sheet = Ui.col(act);
        sheet.setBackgroundColor(Ui.BG);
        wrap.addView(sheet, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---------------- 头部：关闭 + 标题
        LinearLayout head = Ui.col(act);
        Ui.pad(head, 12, 8, 12, 8);
        LinearLayout titleRow = Ui.row(act);
        TextView close = Ui.text(act, "✕", 13f, Ui.DIM);
        close.setGravity(Gravity.CENTER);
        close.setBackground(Ui.pressable(Ui.rounded(14, Ui.SURFACE2), 0x22FFFFFF));
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dismiss();
            }
        });
        titleRow.addView(close, Ui.lp(Ui.dp(28), Ui.dp(28)));

        TextView title = new TextView(act);
        title.setTextColor(Ui.TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setBackground(Ui.rounded(7, 0x00000000));
        title.setPadding(Ui.dp(6), Ui.dp(4), Ui.dp(6), Ui.dp(4));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        boolean defT = rec.title == null || rec.title.trim().length() == 0
                || rec.title.trim().matches("^录音 \\d{1,2}:\\d{2}$");
        title.setText(defT ? (rec.startMs() > 0 ? RecordVm.fmtDate(rec.startMs()) : rec.id)
                : rec.title);
        LinearLayout.LayoutParams tip = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tip.leftMargin = Ui.dp(4);
        titleRow.addView(title, tip);
        head.addView(titleRow);

        TextView meta = Ui.text(act, metaLine(), 10.5f, Ui.FAINT);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(meta, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 4, 3, 4, 0));
        sheet.addView(head, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------------- 操作行：取回文稿 / 取回音频（已在本机时只显示标记）
        btnRow = Ui.row(act);
        Ui.pad(btnRow, 12, 0, 12, 8);
        localTag = Ui.text(act, "已在本机", 11.5f, Ui.FAINT);
        importTextBtn = Ui.button(act, "保存文稿到本机", 12f, false);
        importTextBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                doImport(false);
            }
        });
        btnRow.addView(importTextBtn);
        importAudioBtn = Ui.button(act, "下载音频到本机", 12f, true);
        importAudioBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                doImport(true);
            }
        });
        btnRow.addView(importAudioBtn, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8, 0, 0, 0));
        btnRow.addView(localTag);
        sheet.addView(btnRow, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheet.addView(Ui.divider(act));
        paintButtons();

        // ---------------- 转写正文
        transText = Ui.text(act, "加载中…", 12f, Ui.DIM);
        transText.setLineSpacing(Ui.dp(2), 1f);
        transText.setTextIsSelectable(true);
        Ui.pad(transText, 14, 12, 14, 24);
        ScrollView sc = new ScrollView(act);
        sc.addView(transText, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheet.addView(sc, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        loadTranscript();
    }

    private String metaLine() {
        String dev = rec.device == null || rec.device.length() == 0 ? "未知设备" : rec.device;
        return (rec.startMs() > 0 ? RecordVm.fmtDate(rec.startMs()) : rec.id)
                + " · " + RecordVm.fmtDur(rec.durMs / 1000.0)
                + " · 来自 " + dev
                + (rec.bytes > 0 ? " · " + fmtBytes(rec.bytes) : "");
    }

    private static String fmtBytes(long b) {
        if (b >= 1 << 20) return String.format(java.util.Locale.US, "%.1fMB", b / 1048576.0);
        if (b >= 1 << 10) return String.format(java.util.Locale.US, "%.0fKB", b / 1024.0);
        return b + "B";
    }

    private void paintButtons() {
        boolean local = imported || sync.isLocalCopy(rec.id, host.localIds());
        importTextBtn.setVisibility(local ? View.GONE : View.VISIBLE);
        importAudioBtn.setVisibility(local ? View.GONE : View.VISIBLE);
        localTag.setVisibility(local ? View.VISIBLE : View.GONE);
    }

    private void doImport(final boolean withAudio) {
        importTextBtn.setEnabled(false);
        importAudioBtn.setEnabled(false);
        importTextBtn.setAlpha(0.5f);
        importAudioBtn.setAlpha(0.5f);
        sync.importFromServer(rec, withAudio, new ServerSync.ResultCallback() {
            public void onResult(String err, String msg, boolean changed) {
                if (err != null) {
                    toast("取回失败：" + err);
                    paintButtons();
                    return;
                }
                imported = true;
                paintButtons();
                toast(msg);
                host.onImported();
            }
        });
    }

    private void loadTranscript() {
        sync.fetchTranscript(rec.id, new ServerSync.TextCallback() {
            public void onDone(String err, String text) {
                if (err != null) {
                    transText.setText("加载失败：" + err);
                    return;
                }
                String body = render(text == null ? "" : text);
                transText.setText(body.length() == 0 ? "服务器还没有转写稿" : body);
            }
        });
    }

    /** JSONL（t/e 毫秒、spk、text）→ 阅读版纯文本：发言人分段 + 行内时间点。 */
    private static String render(String jsonl) {
        StringBuilder body = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(jsonl.getBytes()), java.nio.charset.StandardCharsets.UTF_8));
        int lastSpk = Integer.MIN_VALUE;
        String line;
        try {
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0) continue;
                JSONObject o;
                try {
                    o = new JSONObject(line);
                } catch (Throwable ignore) {
                    continue;
                }
                long t = o.optLong("t", 0);
                int spk = o.optInt("spk", -1);
                String text = o.optString("text", "").trim();
                if (text.length() == 0) continue;
                if (spk >= 0 && spk != lastSpk) {
                    if (body.length() > 0) body.append('\n');
                    body.append("── 发言人 ").append(spk + 1).append(" ──\n");
                    lastSpk = spk;
                }
                body.append('[').append(fmtMs(t)).append("] ").append(text).append('\n');
            }
            r.close();
        } catch (Throwable ignore) {
        }
        return body.toString();
    }

    private static String fmtMs(long v) {
        long s = Math.max(0, v) / 1000;
        return (s / 60) + ":" + String.format(java.util.Locale.US, "%02d", s % 60);
    }

    private void toast(String s) {
        android.widget.Toast.makeText(act.getApplicationContext(), s,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ Overlay

    public void show() {
        ((FrameLayout) act.findViewById(android.R.id.content)).addView(wrap);
        wrap.setVisibility(View.VISIBLE);
        sheet.setTranslationY(sheet.getHeight());
        sheet.animate().translationY(0f).setDuration(200).start();
        open = true;
        if (pickerHost != null) pickerHost.pushOverlay(this);
    }

    public boolean isOpen() {
        return open;
    }

    public void dismiss() {
        if (!open) return;
        open = false;
        wrap.animate().alpha(0f).setDuration(160).start();
        sheet.animate().translationY(sheet.getHeight()).setDuration(200)
                .withEndAction(new Runnable() {
                    public void run() {
                        wrap.setVisibility(View.GONE);
                        wrap.setAlpha(1f);
                    }
                }).start();
        if (pickerHost != null) pickerHost.removeOverlay(this);
    }
}
