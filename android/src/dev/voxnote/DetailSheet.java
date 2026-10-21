package dev.voxnote;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * 详情面板：播放、进度/打点、逐句高亮、改名、分类、重新转写。
 *
 * 播放进度由 Handler 每 100ms 读一次 MediaPlayer；只更新进度条/时间/当前句的背景，
 * 不重建视图——旧版 H5 每个 tick 都跑一遍 DOM 查询和 classList 切换。
 */
public class DetailSheet implements Overlay {

    public interface Listener {
        void onTitleChanged(RecordVm vm);

        void onMetaChanged(RecordVm vm);

        void onTranscribe(RecordVm vm);
    }

    private static final int TICK_MS = 100;

    private final Activity act;
    private final Player player;
    private final DataCenter data;
    private final UiSettings settings;
    private final Picker.Host pickerHost;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final FrameLayout wrap;
    private final View scrim;
    private final LinearLayout sheet;
    private final EditText titleInput;
    private final TextView meta;
    private final TextView catChip;
    private final TextView playBtn;
    private final SeekBarView seekBar;
    private final TextView timeText;
    private final TextView markBtn;
    private final TextView transcribeBtn;
    private final TextView markCount;
    private final LinearLayout marksRow;
    private final LinearLayout segsBox;
    private final TextView paths;

    private RecordVm vm;
    private boolean open;
    private boolean playerOpened;
    private int totalMs;
    private int playPos;
    private boolean binding;
    private final ArrayList<View> segViews = new ArrayList<View>();
    private final ArrayList<TextView> segTimes = new ArrayList<TextView>();
    private int activeSeg = -1;

    private final Runnable ticker = new Runnable() {
        public void run() {
            if (!open) return;
            if (playerOpened) {
                int d = player.duration();
                if (d > 0) totalMs = d;
                int p = player.position();
                if (p >= 0) playPos = p;
                boolean playing = player.playing();
                playBtn.setText(playing ? "❚❚" : "▶");
                markBtn.setEnabled(playing);
                markBtn.setAlpha(playing ? 1f : 0.35f);
                if (playing && totalMs > 0 && playPos >= totalMs - 120) {
                    player.pause();
                    playPos = 0;
                }
            }
            updateProgress();
            ui.postDelayed(this, TICK_MS);
        }
    };

    private final Runnable patchTitle = new Runnable() {
        public void run() {
            if (vm == null) return;
            try {
                JSONObject p = new JSONObject();
                p.put("title", vm.title);
                data.patch(vm.id, p, null);
            } catch (Throwable ignore) { }
        }
    };

    public DetailSheet(Activity act, Player player, DataCenter data, UiSettings settings,
                       Picker.Host pickerHost, Listener listener) {
        this.act = act;
        this.player = player;
        this.data = data;
        this.settings = settings;
        this.pickerHost = pickerHost;
        this.listener = listener;

        FrameLayout parent = (FrameLayout) act.findViewById(android.R.id.content);
        wrap = new FrameLayout(act);
        wrap.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wrap.setVisibility(View.GONE);

        scrim = new View(act);
        scrim.setBackgroundColor(0x8C000000);
        wrap.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrim.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dismiss();
            }
        });

        sheet = Ui.col(act);
        sheet.setBackground(Ui.roundedTop(18, Ui.SURFACE, Ui.LINE, 1));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (Ui.screenHeight() * 0.86f));
        lp.gravity = Gravity.BOTTOM;
        wrap.addView(sheet, lp);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(head, 14, 8, 9, 4);
        View handle = new View(act);
        handle.setBackground(Ui.rounded(2, Ui.LINE2));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(Ui.dp(32), Ui.dp(4));
        hp.weight = 0;
        handle.setLayoutParams(hp);
        FrameLayout handleWrap = new FrameLayout(act);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(Ui.dp(32), Ui.dp(4));
        hlp.gravity = Gravity.CENTER;
        handleWrap.addView(handle, hlp);
        head.addView(handleWrap, Ui.lp(0, Ui.dp(28), 1f));
        TextView close = Ui.text(act, "✕", 13f, Ui.DIM);
        close.setGravity(Gravity.CENTER);
        close.setBackground(Ui.pressable(Ui.rounded(14, Ui.SURFACE2), 0x22FFFFFF));
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dismiss();
            }
        });
        head.addView(close, Ui.lp(Ui.dp(28), Ui.dp(28)));
        sheet.addView(head);

        ScrollView body = new ScrollView(act);
        body.setVerticalScrollBarEnabled(false);
        body.setClipToPadding(false);
        LinearLayout content = Ui.col(act);
        Ui.pad(content, 14, 2, 14, 22);
        body.addView(content);
        sheet.addView(body, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        titleInput = new EditText(act);
        titleInput.setTextColor(Ui.TEXT);
        titleInput.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
        titleInput.setTypeface(Typeface.DEFAULT_BOLD);
        titleInput.setBackground(Ui.rounded(7, 0x00000000));
        titleInput.setPadding(Ui.dp(6), Ui.dp(4), Ui.dp(6), Ui.dp(4));
        titleInput.setSingleLine(true);
        titleInput.setHint("未命名录音");
        titleInput.setHintTextColor(Ui.FAINT);
        content.addView(titleInput, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        meta = Ui.text(act, "", 10.5f, Ui.FAINT);
        content.addView(meta, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1, 4, 1, 0));

        LinearLayout catRow = Ui.row(act);
        catRow.setBackground(Ui.rounded(10, Ui.SURFACE2, Ui.LINE, 1));
        Ui.pad(catRow, 10, 7, 10, 7);
        TextView catLabel = Ui.text(act, "分类", 10.5f, Ui.FAINT);
        catRow.addView(catLabel);
        catChip = Ui.text(act, "未分类", 12.5f, Ui.TEXT);
        catChip.setSingleLine(true);
        catChip.setGravity(Gravity.CENTER_VERTICAL);
        catChip.setBackground(Ui.pressable(Ui.rounded(6, 0x00000000), 0x22FFFFFF));
        catChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickCategory();
            }
        });
        LinearLayout.LayoutParams ccp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ccp.leftMargin = Ui.dp(8);
        catRow.addView(catChip, ccp);
        content.addView(catRow, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0));

        LinearLayout playerRow = Ui.row(act);
        playerRow.setBackground(Ui.rounded(10, Ui.SURFACE2, Ui.LINE, 1));
        Ui.pad(playerRow, 10, 9, 10, 9);
        playBtn = Ui.text(act, "▶", 11f, Ui.ON_WHITE);
        playBtn.setGravity(Gravity.CENTER);
        playBtn.setBackground(Ui.pressable(Ui.rounded(16, Ui.WHITE), 0x33000000));
        playBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                togglePlay();
            }
        });
        playerRow.addView(playBtn, Ui.lp(Ui.dp(32), Ui.dp(32)));

        LinearLayout playerMain = Ui.col(act);
        seekBar = new SeekBarView(act);
        seekBar.setListener(new SeekBarView.Listener() {
            public void onSeek(float fraction) {
                playPos = (int) (fraction * totalMs);
                if (playerOpened) player.seek(playPos);
                updateProgress();
            }
        });
        playerMain.addView(seekBar, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(26)));
        timeText = Ui.text(act, "00:00 / 00:00", 10f, Ui.FAINT);
        playerMain.addView(timeText, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams pmp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pmp.leftMargin = Ui.dp(9);
        playerRow.addView(playerMain, pmp);

        markBtn = Ui.text(act, "标记", 11.5f, Ui.TEXT);
        markBtn.setGravity(Gravity.CENTER);
        markBtn.setBackground(Ui.pressable(Ui.rounded(8, Ui.SURFACE, 0xFF5A5F69, 1), 0x22FFFFFF));
        Ui.pad(markBtn, 10, 6, 10, 6);
        markBtn.setEnabled(false);
        markBtn.setAlpha(0.35f);
        markBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                addMark();
            }
        });
        playerRow.addView(markBtn, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(32), 9, 0, 0, 0));
        content.addView(playerRow, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 11, 0, 0));

        transcribeBtn = Ui.button(act, "转文字", 12.5f, false);
        transcribeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (vm != null && listener != null) listener.onTranscribe(vm);
            }
        });
        content.addView(transcribeBtn, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10, 0, 12));

        LinearLayout markTitle = Ui.row(act);
        TextView mt = Ui.text(act, "标记", 10.5f, Ui.FAINT);
        mt.setLetterSpacing(0.08f);
        markTitle.addView(mt);
        markCount = Ui.text(act, "", 10.5f, Ui.FAINT);
        markTitle.addView(markCount, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 4, 0, 0, 0));
        TextView hint = Ui.text(act, "播放中按标记键或点「标记」", 9.5f, Ui.FAINT);
        hint.setGravity(Gravity.END);
        LinearLayout.LayoutParams hp2 = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hp2.leftMargin = Ui.dp(8);
        markTitle.addView(hint, hp2);
        content.addView(markTitle);

        HorizontalScrollView msc = new HorizontalScrollView(act);
        msc.setHorizontalScrollBarEnabled(false);
        marksRow = Ui.row(act);
        msc.addView(marksRow);
        msc.setPadding(0, 0, 0, Ui.dp(12));
        content.addView(msc, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 2));

        LinearLayout segTitle = Ui.row(act);
        TextView st = Ui.text(act, "转写文本", 10.5f, Ui.FAINT);
        st.setLetterSpacing(0.08f);
        segTitle.addView(st);
        TextView sh = Ui.text(act, "点击句子跳转", 9.5f, Ui.FAINT);
        segTitle.addView(sh, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 4, 0, 0, 0));
        content.addView(segTitle, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 6));

        segsBox = Ui.col(act);
        segsBox.setBackground(Ui.rounded(10, Ui.SURFACE2, Ui.LINE, 1));
        Ui.pad(segsBox, 5, 5, 5, 5);
        content.addView(segsBox, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 4));

        paths = Ui.text(act, "", 10f, Ui.FAINT);
        paths.setTypeface(Typeface.MONOSPACE);
        paths.setVisibility(View.GONE);
        content.addView(paths, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));

        titleInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            public void onTextChanged(CharSequence s, int a, int b, int c) { }

            public void afterTextChanged(Editable s) {
                if (binding || vm == null) return;
                String t = s.toString().trim();
                vm.title = t.length() > 0 ? t : fallbackTitle(vm);
                ui.removeCallbacks(patchTitle);
                ui.postDelayed(patchTitle, 350);
                if (listener != null) listener.onTitleChanged(vm);
            }
        });

        parent.addView(wrap);
    }

    private static String fallbackTitle(RecordVm vm) {
        String d = vm.dateText();
        int i = d.indexOf(' ');
        return "录音 " + (i >= 0 ? d.substring(i + 1) : d);
    }

    public boolean isOpen() {
        return open;
    }

    public String id() {
        return vm == null ? null : vm.id;
    }

    public void open(RecordVm vm) {
        if (this.vm != null && !this.vm.id.equals(vm.id)) {
            ui.removeCallbacks(patchTitle);
            try { player.release(); } catch (Throwable ignore) { }
            playerOpened = false;
            playPos = 0;
        }
        this.vm = vm;
        if (!open) {
            open = true;
            wrap.setVisibility(View.VISIBLE);
            wrap.setAlpha(0f);
            sheet.setTranslationY(sheet.getHeight() == 0 ? Ui.dp(400) : sheet.getHeight());
            wrap.animate().alpha(1f).setDuration(180).start();
            sheet.animate().translationY(0).setDuration(260)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            ui.post(ticker);
        }
        bind(false);
    }

    /** 外部数据刷新（例如重新转写后）调用；播放状态保留。 */
    public void refresh(RecordVm newVm) {
        if (!open || vm == null || newVm == null) return;
        if (!vm.id.equals(newVm.id)) return;
        ui.removeCallbacks(patchTitle);
        try { patchTitle.run(); } catch (Throwable ignore) { }
        boolean wasOpen = playerOpened;
        int keepPos = playPos;
        vm = newVm;
        bind(true);
        playPos = keepPos;
        playerOpened = wasOpen;
        updateProgress();
    }

    private void bind(boolean keepTitle) {
        binding = true;
        if (!keepTitle) titleInput.setText(vm.title);
        String m = vm.dateText() + " · " + vm.fmtDur() + " · " + vm.statusLabel();
        meta.setText(m);
        boolean hasCat = vm.cat != null && vm.cat.length() > 0;
        catChip.setText(hasCat ? settings.catName(vm.cat) + "  ›" : "未分类  ›");
        catChip.setTextColor(hasCat ? Ui.TEXT : Ui.FAINT);
        transcribeBtn.setText("done".equals(vm.status()) ? "重新转写" : "转文字");

        boolean hasAudio = vm.hasAudio();
        playBtn.setAlpha(hasAudio ? 1f : 0.3f);
        playBtn.setEnabled(hasAudio);
        markBtn.setEnabled(false);
        markBtn.setAlpha(0.35f);
        totalMs = (int) Math.max(1000, vm.durMs);
        seekBar.setTotal(totalMs / 1000.0);
        seekBar.setMarks(vm.marks);
        timeText.setText("00:00 / " + RecordVm.fmtDur(totalMs / 1000.0));
        if (!keepTitle || playerOpened) {
            playPos = 0;
        }
        rebuildMarks();
        rebuildSegs();
        binding = false;
        updateProgress();
    }

    private void pickCategory() {
        if (vm == null) return;
        final String[] labels = new String[settings.categories.size() + 1];
        labels[0] = "未分类";
        int selected = 0;
        for (int i = 0; i < settings.categories.size(); i++) {
            labels[i + 1] = settings.categories.get(i).name;
            if (settings.categories.get(i).id.equals(vm.cat)) selected = i + 1;
        }
        Picker.show(act, pickerHost, "选择分类", labels, selected, new Picker.OnPick() {
            public void onPick(int index) {
                String newCat = index <= 0 ? null : settings.categories.get(index - 1).id;
                boolean same = (newCat == null && vm.cat == null) || (newCat != null && newCat.equals(vm.cat));
                if (same) return;
                vm.cat = newCat;
                try {
                    JSONObject p = new JSONObject();
                    p.put("cat", newCat == null ? JSONObject.NULL : newCat);
                    data.patch(vm.id, p, null);
                } catch (Throwable ignore) { }
                boolean has = newCat != null;
                catChip.setText(has ? settings.catName(newCat) + "  ›" : "未分类  ›");
                catChip.setTextColor(has ? Ui.TEXT : Ui.FAINT);
                if (listener != null) listener.onMetaChanged(vm);
                toast(has ? "已归入「" + settings.catName(newCat) + "」" : "已设为未分类");
            }
        });
    }

    private void togglePlay() {
        if (vm == null) return;
        if (!vm.hasAudio()) {
            toast("这场没有录音文件");
            return;
        }
        if (!playerOpened) {
            if (!player.open(vm.audioPath == null ? "" : vm.audioPath,
                    vm.audioUri == null ? "" : vm.audioUri)) {
                toast("打不开录音文件");
                return;
            }
            playerOpened = true;
            int d = player.duration();
            if (d > 0) totalMs = d;
            if (playPos >= totalMs) playPos = 0;
            player.seek(playPos);
        }
        if (player.playing()) {
            player.pause();
        } else {
            player.play();
        }
        updateProgress();
    }

    private void seekTo(int ms) {
        if (vm == null) return;
        if (totalMs <= 0) totalMs = (int) Math.max(1000, vm.durMs);
        playPos = Math.max(0, Math.min(totalMs - 1, ms));
        if (!playerOpened && vm.hasAudio()) {
            if (player.open(vm.audioPath == null ? "" : vm.audioPath,
                    vm.audioUri == null ? "" : vm.audioUri)) {
                playerOpened = true;
                int d = player.duration();
                if (d > 0) totalMs = d;
            }
        }
        if (playerOpened) {
            player.seek(playPos);
            if (!player.playing()) player.play();
        }
        updateProgress();
    }

    private void updateProgress() {
        if (totalMs <= 0) totalMs = 1;
        seekBar.setFraction(playPos / (float) totalMs);
        timeText.setText(RecordVm.fmtDur(playPos / 1000.0) + " / "
                + RecordVm.fmtDur(totalMs / 1000.0));

        double t = playPos / 1000.0;
        int idx = -1;
        if (vm != null) {
            for (int i = 0; i < vm.segs.size(); i++) {
                Item.Seg s = vm.segs.get(i);
                if (t >= s.start && t < Math.max(s.start + 0.05, s.end)) {
                    idx = i;
                    break;
                }
            }
        }
        if (idx != activeSeg) {
            if (activeSeg >= 0 && activeSeg < segViews.size()) {
                segViews.get(activeSeg).setBackground(null);
                segTimes.get(activeSeg).setTextColor(Ui.FAINT);
            }
            if (idx >= 0 && idx < segViews.size()) {
                segViews.get(idx).setBackground(Ui.rounded(7, Ui.SURFACE3));
                segTimes.get(idx).setTextColor(Ui.DIM);
            }
            activeSeg = idx;
        }
    }

    private void addMark() {
        if (vm == null || !playerOpened) return;
        boolean playing = player.playing();
        if (!playing) return;
        double t = Math.round(playPos / 100.0) / 10.0;
        for (int i = 0; i < vm.marks.size(); i++) {
            if (Math.abs(vm.marks.get(i) - t) < 0.3) return;
        }
        vm.marks.add(t);
        java.util.Collections.sort(vm.marks);
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < vm.marks.size(); i++) arr.put(vm.marks.get(i));
            JSONObject p = new JSONObject();
            p.put("marks", arr);
            data.patch(vm.id, p, null);
        } catch (Throwable ignore) { }
        seekBar.setMarks(vm.marks);
        rebuildMarks();
        if (listener != null) listener.onMetaChanged(vm);
        toast("标记 " + RecordVm.fmtDur(t));
    }

    private void rebuildMarks() {
        marksRow.removeAllViews();
        markCount.setText(vm == null || vm.marks.isEmpty() ? "" : String.valueOf(vm.marks.size()));
        if (vm == null || vm.marks.isEmpty()) {
            TextView empty = Ui.text(act, "暂无标记", 10.5f, Ui.FAINT);
            marksRow.addView(empty);
            return;
        }
        for (int i = 0; i < vm.marks.size(); i++) {
            final double t = vm.marks.get(i);
            TextView chip = Ui.text(act, (i + 1) + " · " + RecordVm.fmtDur(t), 10.5f, Ui.DIM);
            chip.setSingleLine(true);
            chip.setBackground(Ui.pressable(Ui.rounded(12, Ui.SURFACE2, Ui.LINE2, 1), 0x22FFFFFF));
            Ui.pad(chip, 8, 3, 8, 3);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.rightMargin = Ui.dp(5);
            marksRow.addView(chip, p);
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    seekTo((int) (t * 1000));
                }
            });
        }
    }

    private void rebuildSegs() {
        segsBox.removeAllViews();
        segViews.clear();
        segTimes.clear();
        activeSeg = -1;
        if (vm == null || vm.segs.isEmpty()) {
            TextView empty = Ui.text(act, "尚未转写", 12.5f, Ui.FAINT);
            empty.setGravity(Gravity.CENTER);
            Ui.pad(empty, 10, 10, 10, 10);
            segsBox.addView(empty);
            return;
        }
        for (int i = 0; i < vm.segs.size(); i++) {
            final Item.Seg s = vm.segs.get(i);
            LinearLayout row = Ui.row(act);
            row.setGravity(Gravity.TOP);
            Ui.pad(row, 8, 6, 8, 6);
            TextView time = Ui.text(act, RecordVm.fmtDur(s.start), 10.5f, Ui.FAINT);
            time.setSingleLine(true);
            row.addView(time, Ui.lp(Ui.dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView text = Ui.text(act, s.text, 13.5f, Ui.TEXT);
            text.setLineSpacing(Ui.dp(2), 1f);
            LinearLayout.LayoutParams tp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tp.leftMargin = Ui.dp(8);
            row.addView(text, tp);
            segsBox.addView(row, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            segViews.add(row);
            segTimes.add(time);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    seekTo((int) (s.start * 1000));
                }
            });
        }

        String p = "";
        if (vm.hasMd()) p += "文稿 " + (vm.mdPath != null ? vm.mdPath : vm.mdUri);
        if (vm.hasAudio()) {
            if (p.length() > 0) p += "\n";
            p += "音频 " + (vm.audioPath != null ? vm.audioPath : vm.audioUri);
        }
        paths.setText(p);
        paths.setVisibility(p.length() > 0 ? View.VISIBLE : View.GONE);
    }

    @Override
    public void dismiss() {
        if (!open) return;
        open = false;
        ui.removeCallbacks(ticker);
        ui.removeCallbacks(patchTitle);
        try {
            player.release();
        } catch (Throwable ignore) { }
        playerOpened = false;
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

    private void toast(String s) {
        Toast.makeText(act, s, Toast.LENGTH_SHORT).show();
    }
}
