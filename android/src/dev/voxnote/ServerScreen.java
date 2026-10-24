package dev.voxnote;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 云端页：浏览服务器记录库的全量（不只本机传的）——看每条的转写状态与所属设备，
 * 点开可阅读转写全文，并选择把文稿/音频取回本机（ServerRecordSheet）。
 *
 * 数据来自同步同一端点（/sync/records?since=0）的 device 扩展；本机已有的记录
 * 打「已在本机」标，详情里不再提供下载。设备筛选 chips 按库内出现过的设备生成。
 */
public class ServerScreen extends LinearLayout {

    public interface Host {
        boolean configured();
        ServerSync sync();
        /** 本机记录 id 集合（判断"已在本机"用，配合 ServerSync.isLocalCopy）。 */
        java.util.Set<String> localIds();
        /** 取回成功后让宿主重扫本地列表。 */
        void onImported();
        /** 点开一条服务器记录（宿主负责弹 ServerRecordSheet 并入 overlay 栈）。 */
        void open(ServerSync.ServerRec rec);
    }

    private final Host host;
    private final TextView countText;
    private final TextView refreshBtn;
    private final LinearLayout listBox;
    private final LinearLayout chipsRow;
    private final TextView emptyText;
    private final ArrayList<ServerSync.ServerRec> list = new ArrayList<ServerSync.ServerRec>();
    private String deviceFilter = "";   // 空 = 全部设备
    private boolean loading;

    public ServerScreen(Context c, Host h) {
        super(c);
        host = h;
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.BG);

        LinearLayout head = Ui.row(c);
        Ui.pad(head, 12, 9, 12, 9);
        TextView title = Ui.text(c, "云端", 14.5f, Ui.TEXT, true);
        head.addView(title, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        countText = Ui.text(c, "", 10.5f, Ui.FAINT);
        head.addView(countText, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8, 2, 0, 0));
        View stretch = new View(c);
        head.addView(stretch, Ui.lp(0, 1, 1f));
        refreshBtn = Ui.text(c, "刷新", 10.5f, Ui.DIM);
        refreshBtn.setGravity(Gravity.CENTER);
        refreshBtn.setClickable(true);
        Ui.pad(refreshBtn, 10, 5, 10, 5);
        refreshBtn.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                load();
            }
        });
        head.addView(refreshBtn, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(head, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setVisibility(GONE);
        chipsRow = Ui.row(c);
        Ui.pad(chipsRow, 12, 0, 12, 6);
        hs.addView(chipsRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(hs, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroller = new ScrollView(c);
        listBox = Ui.col(c);
        Ui.pad(listBox, 10, 0, 10, 10);
        scroller.addView(listBox, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroller, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        emptyText = Ui.text(c, "尚未同步", 11.5f, Ui.FAINT);
        emptyText.setGravity(Gravity.CENTER);
        Ui.pad(emptyText, 0, 60, 0, 0);
        emptyText.setVisibility(GONE);
        listBox.addView(emptyText, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 切到本页时调用：拉一遍服务器库。 */
    public void refresh() {
        load();
    }

    /** 本地列表变化后调用：只重算"已在本机"标记，不重新请求网络。 */
    public void refreshPresence() {
        repaint();
    }

    private void load() {
        if (!host.configured()) {
            list.clear();
            emptyText.setText("先在设置里配置服务器地址");
            repaint();
            return;
        }
        if (loading) return;
        loading = true;
        refreshBtn.setText("…");
        host.sync().fetchLibrary(new ServerSync.LibraryListCallback() {
            public void onDone(String err, ArrayList<ServerSync.ServerRec> recs) {
                loading = false;
                refreshBtn.setText("刷新");
                if (err != null) {
                    list.clear();
                    emptyText.setText("加载失败：" + err);
                    repaint();
                    return;
                }
                list.clear();
                if (recs != null) list.addAll(recs);
                emptyText.setText("服务器还没有记录");
                repaint();
            }
        });
    }

    private void repaint() {
        listBox.removeAllViews();
        listBox.addView(emptyText, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        emptyText.setVisibility(list.isEmpty() ? VISIBLE : GONE);

        // 设备筛选 chips：全部 + 按库内出现顺序
        LinkedHashMap<String, Integer> devices = new LinkedHashMap<String, Integer>();
        for (ServerSync.ServerRec r : list) {
            String d = r.device == null || r.device.length() == 0 ? "未知设备" : r.device;
            Integer n = devices.get(d);
            devices.put(d, n == null ? 1 : n + 1);
        }
        chipsRow.removeAllViews();
        chipsRow.getParent().requestLayout();
        ((View) chipsRow.getParent()).setVisibility(devices.size() > 1 ? VISIBLE : GONE);
        addChip("全部 " + list.size(), deviceFilter.length() == 0);
        for (Map.Entry<String, Integer> en : devices.entrySet()) {
            addChip(en.getKey() + " " + en.getValue(), deviceFilter.equals(en.getKey()));
        }

        int shown = 0;
        for (int i = 0; i < list.size(); i++) {
            ServerSync.ServerRec r = list.get(i);
            String d = r.device == null || r.device.length() == 0 ? "未知设备" : r.device;
            if (deviceFilter.length() > 0 && !deviceFilter.equals(d)) continue;
            ServerCard card = new ServerCard(getContext());
            card.bind(r);
            listBox.addView(card, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 7));
            shown++;
        }
        if (list.isEmpty()) {
            countText.setText("");
        } else {
            countText.setText(shown + " / " + list.size() + " 条 · 点开可看转写并取回本机");
        }
    }

    private void addChip(String label, boolean active) {
        TextView chip = Ui.text(getContext(), label, 12f, active ? Ui.TEXT : Ui.FAINT);
        chip.setSingleLine(true);
        chip.setBackground(active
                ? Ui.pressable(Ui.rounded(14, Ui.SURFACE2, Ui.LINE2, 1), 0x22FFFFFF)
                : Ui.pressable(Ui.rounded(14, 0x00000000, Ui.LINE, 1), 0x22FFFFFF));
        chip.setGravity(Gravity.CENTER);
        Ui.pad(chip, 11, 5, 11, 5);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.rightMargin = Ui.dp(6);
        chipsRow.addView(chip, p);
    }

    private String statusLabel(String st) {
        if ("done".equals(st)) return "已转写";
        if ("asr".equals(st)) return "转写中";
        if ("queued".equals(st)) return "排队中";
        if ("fail".equals(st)) return "转写失败";
        return "未转写";
    }

    /** 一条服务器记录卡片。 */
    private class ServerCard extends LinearLayout {

        private final TextView title;
        private final TextView meta;
        private ServerSync.ServerRec rec;

        ServerCard(Context c) {
            super(c);
            setOrientation(VERTICAL);
            setBackground(Ui.rounded(12, Ui.SURFACE, Ui.LINE, 1));
            Ui.pad(this, 10, 9, 10, 9);
            setClickable(true);
            setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    if (rec != null) host.open(rec);
                }
            });

            title = Ui.text(c, "", 13f, Ui.TEXT);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            addView(title, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            meta = Ui.text(c, "", 10.5f, Ui.FAINT);
            meta.setSingleLine(true);
            meta.setEllipsize(TextUtils.TruncateAt.END);
            addView(meta, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));
        }

        void bind(ServerSync.ServerRec r) {
            rec = r;
            boolean defT = r.title == null || r.title.trim().length() == 0
                    || r.title.trim().matches("^录音 \\d{1,2}:\\d{2}$");
            title.setText(defT ? fmtStart(r) : r.title);
            boolean local = host.sync().isLocalCopy(r.id, host.localIds());
            String time = r.startMs() > 0 ? RecordVm.fmtDate(r.startMs()) : r.id;
            meta.setText(time
                    + " · " + RecordVm.fmtDur(r.durMs / 1000.0)
                    + " · " + (r.device == null || r.device.length() == 0 ? "未知设备" : r.device)
                    + " · " + statusLabel(r.status)
                    + (r.bytes > 0 ? " · " + fmtBytes(r.bytes) : "")
                    + (local ? " · 已在本机" : ""));
        }

        private String fmtStart(ServerSync.ServerRec r) {
            long t = r.startMs();
            return t > 0 ? RecordVm.fmtDate(t) : r.id;
        }
    }

    private static String fmtBytes(long b) {
        if (b >= 1 << 20) return String.format(java.util.Locale.US, "%.1fMB", b / 1048576.0);
        if (b >= 1 << 10) return String.format(java.util.Locale.US, "%.0fKB", b / 1024.0);
        return b + "B";
    }
}
