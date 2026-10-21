package dev.s1rec.reader;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

/** 详情页：一场会话的逐句文稿。 */
public class DetailActivity extends Activity {

    private Session session;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("文稿");
        long sid = getIntent().getLongExtra("sid", 0);
        session = LogParser.bySid(sid);
        if (session == null) {
            LogParser.scan();
            session = LogParser.bySid(sid);
        }
        if (session == null) {
            TextView t = new TextView(this);
            t.setText("找不到这场会话");
            t.setPadding(40, 60, 40, 40);
            setContentView(t);
            return;
        }
        setContentView(build());
    }

    private View build() {
        int dp = (int) getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F2F3F5"));

        ScrollView head = new ScrollView(this);
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setBackgroundColor(Color.WHITE);
        h.setPadding(18 * dp, 14 * dp, 18 * dp, 14 * dp);

        TextView title = new TextView(this);
        title.setTextSize(18f);
        title.setTextColor(Color.parseColor("#1B1F26"));
        title.setText(LogParser.fmtTime(session.startMs) + "  ·  " + session.modeLabel());
        h.addView(title);

        TextView meta = new TextView(this);
        meta.setTextSize(12.5f);
        meta.setTextColor(Color.parseColor("#79828F"));
        StringBuilder m = new StringBuilder();
        m.append("时长 ").append(LogParser.fmtDuration(session.durationMs()))
                .append(" · ").append(session.finalCount()).append(" 句");
        if (session.partialCount > 0) m.append(" · 中间态 ").append(session.partialCount);
        m.append(" · ").append(session.statusLabel());
        if (session.errorCount > 0) m.append(" · 报错 ").append(session.errorCount);
        meta.setText(m.toString());
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = 6 * dp;
        h.addView(meta, mlp);

        if (session.summary != null && session.summary.length() > 0) {
            TextView sum = new TextView(this);
            sum.setTextSize(13.5f);
            sum.setTextColor(Color.parseColor("#3A4250"));
            sum.setText("摘要：" + session.summary);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
            slp.topMargin = 8 * dp;
            h.addView(sum, slp);
        }

        if (session.wavPath != null) {
            TextView p = new TextView(this);
            p.setTextSize(11.5f);
            p.setTextColor(Color.parseColor("#8A93A0"));
            p.setText("🎵 " + session.wavPath);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
            plp.topMargin = 6 * dp;
            h.addView(p, plp);
        }

        head.addView(h);
        root.addView(head, new LinearLayout.LayoutParams(-1, -2));

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setPadding(10 * dp, 8 * dp, 10 * dp, 10 * dp);
        list.setClipToPadding(false);
        if (session.segments.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("（这场没有识别文本）");
            t.setGravity(Gravity.CENTER);
            t.setTextColor(Color.parseColor("#8A93A0"));
            t.setPadding(0, 40 * dp, 0, 0);
            root.addView(t, new LinearLayout.LayoutParams(-1, 0, 1f));
        } else {
            list.setAdapter(new SegAdapter());
            root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        }
        return root;
    }

    private class SegAdapter extends BaseAdapter {
        public int getCount() { return session.segments.size(); }
        public Object getItem(int i) { return session.segments.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int i, View reuse, ViewGroup parent) {
            int dp = (int) getResources().getDisplayMetrics().density;
            Segment s = session.segments.get(i);

            if (s.kind == Segment.BREAK) {
                TextView b = new TextView(DetailActivity.this);
                b.setText("—— 说话人切换 " + LogParser.fmtStamp(s.startMs) + " ——");
                b.setTextSize(11.5f);
                b.setTextColor(Color.parseColor("#8A93A0"));
                b.setGravity(Gravity.CENTER);
                b.setPadding(0, 10 * dp, 0, 10 * dp);
                return b;
            }

            LinearLayout row = new LinearLayout(DetailActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackgroundColor(Color.WHITE);
            row.setPadding(12 * dp, 10 * dp, 12 * dp, 10 * dp);

            TextView ts = new TextView(DetailActivity.this);
            ts.setText(LogParser.fmtStamp(s.startMs));
            ts.setTextSize(11f);
            ts.setTypeface(Typeface.MONOSPACE);
            ts.setTextColor(Color.parseColor("#96A0AD"));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
            tlp.rightMargin = 10 * dp;
            row.addView(ts, tlp);

            TextView txt = new TextView(DetailActivity.this);
            txt.setText(s.text);
            txt.setTextSize(15f);
            txt.setTextColor(Color.parseColor("#1B1F26"));
            txt.setTextIsSelectable(true);
            row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));

            LinearLayout outer = new LinearLayout(DetailActivity.this);
            outer.setOrientation(LinearLayout.VERTICAL);
            outer.setPadding(0, 0, 0, 3 * dp);
            outer.addView(row, new LinearLayout.LayoutParams(-1, -2));
            return outer;
        }
    }
}
