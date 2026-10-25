package dev.voxnote;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * 录音流（与 Web 控制台流页同一交互）：平铺全部记录的转写正文，批量 review。
 * 卡片上直接播（共用全局 Player），播完按「连播」开关自动接下一条；
 * 转写正文三行收起、点开看全文；点标题进详情（同时把播放控制权交还详情页）。
 *
 * 会话模型：连播会话期间流页是全局 Player 的控制者（进度刷新 + 播完切卡），
 * HomeActivity.openDetail 会调 stopSession() 结束会话——详情页有自己的
 * 播完暂停逻辑，两边同时管同一个 Player 会打架。
 *
 * 每张卡片外面包 SwipeCatBox：左右滑归类与列表页同款（同一对滑动分类设置、
 * 同一套归类/取消语义，回调直接走列表页的 onSwipe 逻辑）。
 */
public class StreamScreen extends LinearLayout {

    public interface Host {
        /** 全量记录（时间序，与列表页同源）。 */
        ArrayList<RecordVm> records();
        String catName(RecordVm vm);
        void openDetail(RecordVm vm);
        /** 左右滑归类（与列表页 onSwipe 同语义：滑回原分类＝取消）。 */
        void onSwipeCat(RecordVm vm, String targetCat);
    }

    private final Host host;
    private final Player player;
    private final UiSettings settings;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LinearLayout listBox;
    private final TextView countText;
    private final TextView autoBtn;
    private final TextView emptyText;
    private final ArrayList<RecordVm> list = new ArrayList<RecordVm>();
    private int playingIdx = -1;
    private boolean session;
    private boolean tickerRun;

    public StreamScreen(Context c, Player p, UiSettings s, Host h) {
        super(c);
        host = h;
        player = p;
        settings = s;
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.BG);

        LinearLayout head = Ui.row(c);
        Ui.pad(head, 12, 9, 12, 9);
        TextView title = Ui.text(c, "录音流", 14.5f, Ui.TEXT, true);
        head.addView(title, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        countText = Ui.text(c, "", 10.5f, Ui.FAINT);
        head.addView(countText, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8, 2, 0, 0));
        View stretch = new View(c);
        head.addView(stretch, Ui.lp(0, 1, 1f));
        autoBtn = Ui.text(c, "", 10.5f, Ui.DIM);
        autoBtn.setGravity(Gravity.CENTER);
        autoBtn.setClickable(true);
        Ui.pad(autoBtn, 10, 5, 10, 5);
        autoBtn.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                settings.streamAuto = !settings.streamAuto;
                settings.save();
                paintAutoBtn();
                Toast.makeText(v.getContext().getApplicationContext(),
                        settings.streamAuto ? "已开启连播" : "已关闭连播", Toast.LENGTH_SHORT).show();
            }
        });
        head.addView(autoBtn, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(head, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroller = new ScrollView(c);
        listBox = Ui.col(c);
        Ui.pad(listBox, 10, 0, 10, 10);
        scroller.addView(listBox, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroller, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        emptyText = Ui.text(c, "还没有录音", 11.5f, Ui.FAINT);
        emptyText.setGravity(Gravity.CENTER);
        Ui.pad(emptyText, 0, 60, 0, 0);
        emptyText.setVisibility(GONE);
        listBox.addView(emptyText, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        paintAutoBtn();
    }

    /** 数据刷新（切到本页 / 列表数据变化后调用）。重建卡片；正在播的按 id 对回位置。 */
    public void refresh() {
        String keepId = session && playingIdx >= 0 && playingIdx < list.size()
                ? list.get(playingIdx).id : null;
        list.clear();
        list.addAll(host.records());
        int keep = -1;
        if (keepId != null) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id.equals(keepId)) { keep = i; break; }
            }
        }
        playingIdx = keep;
        listBox.removeAllViews();
        listBox.addView(emptyText, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < list.size(); i++) {
            StreamCard card = new StreamCard(getContext(), i);
            final RecordVm vm = list.get(i);
            SwipeCatBox box = new SwipeCatBox(getContext(), settings, card, new SwipeCatBox.Callback() {
                public void onSwipeCat(String targetCat) {
                    host.onSwipeCat(vm, targetCat);
                }
            });
            box.refreshLabels();
            listBox.addView(box, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 7));
            card.bind();
        }
        emptyText.setVisibility(list.isEmpty() ? VISIBLE : GONE);
        countText.setText(list.size() + " 场 · 从上往下连播 review");
        if (playingIdx >= 0) scrollCardVisible(playingIdx);
    }

    private void paintAutoBtn() {
        autoBtn.setText(settings.streamAuto ? "连播 开" : "连播 关");
        autoBtn.setTextColor(settings.streamAuto ? Ui.TEXT : Ui.FAINT);
        autoBtn.setBackground(Ui.pressable(
                Ui.rounded(9, settings.streamAuto ? Ui.SURFACE3 : Ui.SURFACE2, Ui.LINE2, 1), 0x22FFFFFF));
    }

    /** 从第 idx 条开始播（同一对象在播放器里就续播，否则从头）。 */
    private void playFrom(int idx) {
        RecordVm vm = list.get(idx);
        if (!vm.hasAudio()) return;
        boolean same = playingIdx == idx && session;
        if (!player.open(vm.audioPath, vm.audioUri)) {
            Toast.makeText(getContext().getApplicationContext(), "打不开这条音频", Toast.LENGTH_SHORT).show();
            return;
        }
        // 播完后重按播放：MediaPlayer 不像 HTMLMediaElement 会自动回头，手动归零
        if (same && player.duration() > 0 && player.position() >= player.duration() - 120) player.seek(0);
        session = true;
        setPlayingIdx(idx);
        player.play();
        startTicker();
    }

    /** 连播会话结束（打开详情 / 手动停）：音频继续，控制权交还。 */
    public void stopSession() {
        session = false;
        setPlayingIdx(-1);
    }

    private void setPlayingIdx(int idx) {
        int old = playingIdx;
        playingIdx = idx;
        if (old >= 0 && old < list.size()) repaintCard(old);
        if (idx >= 0 && idx < list.size()) repaintCard(idx);
    }

    private void repaintCard(int idx) {
        StreamCard card = cardAt(idx);
        if (card != null) card.bind();
    }

    private void scrollCardVisible(int idx) {
        StreamCard card = cardAt(idx);
        if (card != null) {
            View v = (View) card.getParent();
            v.requestRectangleOnScreen(new android.graphics.Rect(0, 0, v.getWidth(), v.getHeight()), false);
        }
    }

    /** 连播切到下一条后有音频的卡片（找不到 = 本页播完）。 */
    private int nextPlayable(int from) {
        for (int i = from + 1; i < list.size(); i++) {
            if (list.get(i).hasAudio()) return i;
        }
        return -1;
    }

    private void startTicker() {
        if (tickerRun) return;
        tickerRun = true;
        ui.postDelayed(ticker, 100);
    }

    /** 每 100ms 刷进度 + 播完检测（与详情页同节奏）。会话结束后自终止。
     *  播完检测不能只看 isPlaying()：MediaPlayer 到尾的一瞬它已是 false，
     *  两次 tick 之间播完就会漏掉，卡在结尾不切卡。 */
    private final Runnable ticker = new Runnable() {
        public void run() {
            tickerRun = false;
            if (!session || playingIdx < 0 || playingIdx >= list.size()) return;
            StreamCard card = cardAt(playingIdx);
            if (card != null) card.updateProgress();
            if (card != null && card.seekDragging()) { startTicker(); return; } // 拖动中不判播完
            int d = player.duration();
            if (d > 0 && player.position() >= d - 120) {
                if (settings.streamAuto) {
                    int next = nextPlayable(playingIdx);
                    if (next >= 0) {
                        playFrom(next);
                        scrollCardVisible(next);
                        return;
                    }
                    Toast.makeText(getContext().getApplicationContext(),
                            "本页播完了", Toast.LENGTH_SHORT).show();
                } else {
                    player.pause();
                    player.seek(0);
                }
                session = false;
                setPlayingIdx(-1);
                return;
            }
            startTicker();
        }
    };

    /** 卡片现在包在 SwipeCatBox 里，按 idx 沿子项找一层。 */
    private StreamCard cardAt(int idx) {
        for (int i = 0; i < listBox.getChildCount(); i++) {
            View v = listBox.getChildAt(i);
            if (v instanceof SwipeCatBox) {
                StreamCard card = (StreamCard) ((SwipeCatBox) v).card;
                if (card.idx == idx) return card;
            }
        }
        return null;
    }

    /** 一张流卡片：播放键 + 标题 + 三行收起的转写正文 + 播放中才出现的进度条。 */
    private class StreamCard extends LinearLayout {

        final int idx;
        private final TextView playBtn;
        private final TextView title;
        private final TextView relTag;
        private final TextView dur;
        private final TextView text;
        private final TextView catTag;
        private final TextView statusTag;
        private final SeekBarView seek;
        private boolean expanded;

        StreamCard(Context c, int index) {
            super(c);
            idx = index;
            setOrientation(HORIZONTAL);
            setBackground(Ui.rounded(12, Ui.SURFACE, Ui.LINE, 1));
            Ui.pad(this, 10, 9, 10, 9);

            playBtn = Ui.text(c, "▶", 11f, Ui.ON_WHITE);
            playBtn.setGravity(Gravity.CENTER);
            playBtn.setBackground(Ui.pressable(Ui.rounded(15, Ui.WHITE), 0x33000000));
            playBtn.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    if (playingIdx == idx && session) {
                        if (player.playing()) player.pause(); else player.play();
                        bind();
                    } else {
                        playFrom(idx);
                    }
                }
            });
            addView(playBtn, Ui.lp(Ui.dp(30), Ui.dp(30)));

            LinearLayout main = Ui.col(c);
            LinearLayout.LayoutParams mlp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            mlp.leftMargin = Ui.dp(9);
            main.setLayoutParams(mlp);
            LinearLayout trow = Ui.row(c);
            trow.setGravity(Gravity.CENTER_VERTICAL);
            title = Ui.text(c, "", 13f, Ui.TEXT);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setClickable(true);
            title.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    stopSession();
                    host.openDetail(list.get(idx));
                }
            });
            trow.addView(title, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            relTag = Ui.text(c, "", 9.5f, Ui.FAINT);
            relTag.setSingleLine(true);
            Ui.pad(relTag, 5, 0, 5, 0);
            relTag.setBackground(Ui.rounded(4, 0x04FFFFFF, Ui.LINE, 1));
            trow.addView(relTag, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 6, 0, 0, 0));
            dur = Ui.text(c, "", 10.5f, Ui.FAINT);
            dur.setSingleLine(true);
            trow.addView(dur, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 6, 0, 0, 0));
            main.addView(trow, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            text = Ui.text(c, "", 11.5f, Ui.DIM);
            text.setLineSpacing(Ui.dp(1), 1f);
            text.setMaxLines(3);
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setClickable(true);
            text.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    expanded = !expanded;
                    text.setMaxLines(expanded ? Integer.MAX_VALUE : 3);
                    text.setEllipsize(expanded ? null : TextUtils.TruncateAt.END);
                }
            });
            main.addView(text, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));

            seek = new SeekBarView(c);
            seek.setVisibility(GONE);
            seek.setListener(new SeekBarView.Listener() {
                public void onSeek(float fraction) {
                    // 拖动中只回预览（SeekBarView 拖拽期不回写 fraction），松手那次才真正 seek
                    if (playingIdx == idx && session && !seek.isDragging()) {
                        int d = player.duration();
                        if (d > 0) player.seek(Math.round(d * fraction));
                    }
                }
            });
            main.addView(seek, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(22), 0, 2, 0, 0));

            LinearLayout meta = Ui.row(c);
            catTag = Ui.text(c, "", 9.5f, Ui.FAINT);
            catTag.setSingleLine(true);
            Ui.pad(catTag, 5, 0, 5, 0);
            catTag.setBackground(Ui.rounded(4, 0x04FFFFFF, Ui.LINE, 1));
            meta.addView(catTag, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));
            statusTag = Ui.text(c, "", 9.5f, Ui.FAINT);
            statusTag.setSingleLine(true);
            meta.addView(statusTag, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 6, 4, 0, 0));
            main.addView(meta, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addView(main);
        }

        void bind() {
            RecordVm vm = list.get(idx);
            boolean playing = playingIdx == idx && session;
            setBackground(Ui.rounded(12, Ui.SURFACE, playing ? Ui.ACCENT_LINE : Ui.LINE, 1));
            playBtn.setText(playing && player.playing() ? "❚❚" : "▶");
            playBtn.setAlpha(vm.hasAudio() ? 1f : 0.3f);
            boolean defT = vm.isDefaultTitle();
            title.setText(defT ? vm.displayTitle()
                    : (vm.title.length() == 0 ? "未命名录音" : vm.title));
            relTag.setVisibility(defT ? VISIBLE : GONE);
            if (defT) relTag.setText(RecordVm.relDay(vm.startMs));
            dur.setText(vm.fmtDur());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < vm.segs.size(); i++) {
                if (vm.segs.get(i).text == null) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(vm.segs.get(i).text);
            }
            if (sb.length() == 0) {
                text.setText("暂无转写内容");
                text.setTextColor(Ui.FAINT);
            } else {
                text.setText(sb.toString());
                text.setTextColor(Ui.DIM);
            }
            String catName = host.catName(vm);
            catTag.setText(catName == null || catName.length() == 0 ? "未分类" : catName);
            statusTag.setText(vm.statusLabel());
            seek.setVisibility(playing ? VISIBLE : GONE);
            updateProgress();
        }

        void updateProgress() {
            if (playingIdx != idx) return;
            int d = player.duration();
            if (d <= 0) d = (int) (list.get(idx).durMs);
            seek.setTotal(d / 1000.0);
            seek.setFraction(d > 0 ? player.position() / (float) d : 0);
            playBtn.setText(player.playing() ? "❚❚" : "▶");
        }

        boolean seekDragging() {
            return seek.isDragging();
        }
    }
}
