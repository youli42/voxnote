package dev.s1rec.reader;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 首页：把日志解析成一场场会话，用卡片列表展示。 */
public class MainActivity extends Activity {

    private TextView stats;
    private Button refreshBtn;
    private ListView list;
    private final List<Session> data = new ArrayList<Session>();
    private CardAdapter adapter;
    private boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("S1 转写");
        setContentView(buildLayout());

        if (!hasStorage()) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        } else {
            refresh();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == 1) {
            if (hasStorage()) refresh();
            else stats.setText("没有存储权限，无法读取日志");
        }
    }

    private boolean hasStorage() {
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private View buildLayout() {
        int dp = (int) getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F2F3F5"));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(14 * dp, 10 * dp, 10 * dp, 10 * dp);

        stats = new TextView(this);
        stats.setTextSize(12.5f);
        stats.setTextColor(Color.parseColor("#5A6472"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        bar.addView(stats, lp);

        refreshBtn = new Button(this);
        refreshBtn.setText("刷新");
        refreshBtn.setAllCaps(false);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { refresh(); }
        });
        bar.addView(refreshBtn, new LinearLayout.LayoutParams(-2, -2));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        list = new ListView(this);
        list.setDivider(null);
        list.setPadding(10 * dp, 0, 10 * dp, 10 * dp);
        list.setClipToPadding(false);
        adapter = new CardAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int i, long id) {
                Session s = data.get(i);
                Intent it = new Intent(MainActivity.this, DetailActivity.class);
                it.putExtra("sid", s.sid);
                startActivity(it);
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView empty = new TextView(this);
        empty.setText("还没有数据\n点上方「刷新」从日志解析");
        empty.setGravity(Gravity.CENTER);
        empty.setTextColor(Color.parseColor("#8A93A0"));
        root.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1f));
        list.setEmptyView(empty);
        return root;
    }

    private void refresh() {
        if (busy) return;
        busy = true;
        stats.setText("正在解析日志…");
        refreshBtn.setEnabled(false);
        new Thread(new Runnable() {
            public void run() {
                final List<Session> out = LogParser.scan();
                final int written = Store.writeAll(out);
                final String err = LogParser.lastError;
                runOnUiThread(new Runnable() {
                    public void run() {
                        data.clear();
                        data.addAll(out);
                        adapter.notifyDataSetChanged();
                        int sentences = 0, withAudio = 0;
                        for (int i = 0; i < out.size(); i++) {
                            sentences += out.get(i).finalCount();
                            if (out.get(i).wavPath != null) withAudio++;
                        }
                        StringBuilder sb = new StringBuilder();
                        sb.append(withAudio).append(" 场录音 · ").append(out.size()).append(" 个会话 · ")
                                .append(sentences).append(" 句");
                        sb.append("\n已写入 ").append(written).append(" 场 → ").append(Store.OUT.getAbsolutePath());
                        if (err != null) sb.append("\n⚠️ ").append(err);
                        stats.setText(sb.toString());
                        busy = false;
                        refreshBtn.setEnabled(true);
                    }
                });
            }
        }, "scan").start();
    }

    private View card(Session s) {
        int dp = (int) getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(16 * dp, 13 * dp, 16 * dp, 13 * dp);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(10 * dp);
        bg.setStroke(Math.max(1, dp / 2), Color.parseColor("#E3E6EA"));
        box.setBackground(bg);
        box.setPadding(16 * dp, 13 * dp, 16 * dp, 13 * dp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setTextSize(15.5f);
        title.setTextColor(Color.parseColor("#1B1F26"));
        title.setText(LogParser.fmtTime(s.startMs) + "  " + s.modeLabel());
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView chip = new TextView(this);
        chip.setTextSize(11f);
        chip.setTextColor(statusColor(s));
        chip.setText(s.statusLabel());
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setColor(statusBg(s));
        chipBg.setCornerRadius(20 * dp);
        chip.setBackground(chipBg);
        chip.setPadding(8 * dp, 3 * dp, 8 * dp, 3 * dp);
        head.addView(chip, new LinearLayout.LayoutParams(-2, -2));
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));

        TextView meta = new TextView(this);
        meta.setTextSize(12.2f);
        meta.setTextColor(Color.parseColor("#79828F"));
        StringBuilder m = new StringBuilder();
        m.append("时长 ").append(LogParser.fmtDuration(s.durationMs()));
        m.append(" · ").append(s.finalCount()).append(" 句");
        if (s.partialCount > 0) m.append(" · 中间态 ").append(s.partialCount);
        if (s.wavPath == null) m.append(" · 无录音文件");
        if (s.errorCount > 0) m.append(" · 报错 ").append(s.errorCount);
        meta.setText(m.toString());
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = 5 * dp;
        box.addView(meta, mlp);

        String pv = s.preview();
        if (pv != null && pv.length() > 0) {
            TextView prev = new TextView(this);
            prev.setTextSize(13.5f);
            prev.setTextColor(Color.parseColor("#3A4250"));
            prev.setMaxLines(2);
            prev.setEllipsize(android.text.TextUtils.TruncateAt.END);
            prev.setText(pv);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
            plp.topMargin = 7 * dp;
            box.addView(prev, plp);
        }
        return box;
    }

    private int statusColor(Session s) {
        String st = s.status();
        if (Session.ST_OK.equals(st)) return Color.parseColor("#1B7F4B");
        if (Session.ST_ERROR.equals(st)) return Color.parseColor("#B3261E");
        return Color.parseColor("#6B5B00");
    }

    private int statusBg(Session s) {
        String st = s.status();
        if (Session.ST_OK.equals(st)) return Color.parseColor("#E4F5EA");
        if (Session.ST_ERROR.equals(st)) return Color.parseColor("#FBE7E5");
        return Color.parseColor("#FBF3D9");
    }

    private class CardAdapter extends BaseAdapter {
        public int getCount() { return data.size(); }
        public Object getItem(int i) { return data.get(i); }
        public long getItemId(int i) { return data.get(i).sid; }
        public View getView(int i, View reuse, ViewGroup parent) {
            int dp = (int) getResources().getDisplayMetrics().density;
            LinearLayout outer = new LinearLayout(MainActivity.this);
            outer.setOrientation(LinearLayout.VERTICAL);
            outer.setPadding(0, 0, 0, 6 * dp);
            outer.addView(card(data.get(i)), new LinearLayout.LayoutParams(-1, -2));
            return outer;
        }
    }
}
