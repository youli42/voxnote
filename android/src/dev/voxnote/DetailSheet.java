package dev.voxnote;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
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
 * 详情全屏页：从下往上划入、占满全屏（不再用 86% 高度的底部弹卡 + 遮罩，提高屏幕利用率）。
 *
 * 结构自上而下：
 *  固定区——标题（可改）/ 元信息 / 分类 / 播放条（暂停 + 进度条 + 打点）；进度条始终钉在页面最上方，
 *          下面两页无论怎么滚动都不把它顶走。
 *  两个并列分页——「转写文本」与「标记」：窄屏用 tab + 左右滑动切换（横向分页吸附），
 *          宽屏（≥600dp）双栏同屏显示。
 *  标记页不再有单独的横向 chip 分类；每条标记一行，展示该时间点覆盖的转写文字，支持逐条删除，
 *          点行跳到该时间点继续播放。
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

    /** 屏幕宽度达到这个 dp 就双栏同显「转写 / 标记」，否则 tab + 左右滑动切换。 */
    private static final float DUAL_MIN_DP = 600f;

    private final Activity act;
    private final Player player;
    private final DataCenter data;
    private final UiSettings settings;
    private final Picker.Host pickerHost;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final FrameLayout wrap;
    private final LinearLayout sheet;
    private final EditText titleInput;
    private final TextView meta;
    private final TextView catChip;
    private final TextView playBtn;
    private final SeekBarView seekBar;
    private final TextView timeText;
    private final TextView markAddBtn;
    private TextView transcribeBtn;
    private TextView segsHint;
    private TextView markCount;
    private final TextView tabSegs;
    private final TextView tabMarks;
    private final LinearLayout tabsRow;
    private LinearLayout marksBox;
    private LinearLayout segsBox;
    private final TextView paths;

    private final boolean dual;
    private HorizontalScrollView pager;        // 窄屏分页宿主；宽屏为 null
    private LinearLayout colSegs;
    private LinearLayout colMarks;
    private int pageWidthPx;
    private int tabIdx;

    private RecordVm vm;
    private boolean open;
    private boolean playerOpened;
    private int totalMs;
    private int playPos;
    private boolean binding;
    private final ArrayList<View> segViews = new ArrayList<View>();
    private final ArrayList<TextView> segTimes = new ArrayList<TextView>();
    private int activeSeg = -1;

    /** 发言人数（不含 -1 未知）；0/1 人不显示徽标。 */
    private int distinctSpeakers() {
        if (vm == null) return 0;
        int n = 0;
        boolean[] seen = new boolean[16];
        for (int i = 0; i < vm.segs.size(); i++) {
            int spk = vm.segs.get(i).spk;
            if (spk < 0) continue;
            if (spk < seen.length && !seen[spk]) {
                seen[spk] = true;
                n++;
            } else if (spk >= seen.length) {
                n++;   // 极端情况不去重，只保证不崩
            }
        }
        return n;
    }

    /** 发言人色片：柔和底色 + 深色字，按 tag 轮转。 */
    private static final int[] SPK_BG = {
            0xFFAECBFA, 0xFFFFD8A8, 0xFFB3E6C9, 0xFFFFB8C6, 0xFFD4BEFF, 0xFFA9E7F3,
    };

    private static int spkBg(int tag) {
        return SPK_BG[Math.abs(tag) % SPK_BG.length];
    }

    private final Runnable ticker = new Runnable() {
        public void run() {
            if (!open || act.isFinishing() || act.isDestroyed()) return;
            if (playerOpened) {
                int d = player.duration();
                if (d > 0) totalMs = d;
                if (!seekBar.isDragging()) {   // 拖动预览期间不回写位置，别跟手势打架
                    int p = player.position();
                    if (p >= 0) playPos = p;
                    boolean playing = player.playing();
                    playBtn.setText(playing ? "❚❚" : "▶");
                    markAddBtn.setEnabled(playing);
                    markAddBtn.setAlpha(playing ? 1f : 0.35f);
                    if (playing && totalMs > 0 && playPos >= totalMs - 120) {
                        player.pause();
                        player.seek(0);   // 播放器归零，否则下次 start() 从结尾接着走，播完无法重播
                        playPos = 0;
                    }
                }
            }
            updateProgress();
            ui.postDelayed(this, TICK_MS);
        }
    };

    /** 横滑停稳 220ms 后吸附整页（OnScrollChange 防抖，代替不存在的滚动状态回调）。 */
    private final Runnable snapRunnable = new Runnable() {
        public void run() {
            snapPage();
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
        this.dual = Ui.screenWidth() / Ui.density() >= DUAL_MIN_DP;

        FrameLayout parent = (FrameLayout) act.findViewById(android.R.id.content);
        wrap = new FrameLayout(act);
        wrap.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wrap.setVisibility(View.GONE);

        // 全屏面板：不再带遮罩，入场动画沿 Y 从屏幕下方划入。
        sheet = Ui.col(act);
        sheet.setBackgroundColor(Ui.BG);
        wrap.addView(sheet, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---------------- 固定区：标题 / 元信息 / 分类 / 播放条
        LinearLayout head = Ui.col(act);
        Ui.pad(head, 12, 6, 12, 8);

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

        titleInput = new EditText(act);
        titleInput.setTextColor(Ui.TEXT);
        titleInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        titleInput.setTypeface(Typeface.DEFAULT_BOLD);
        titleInput.setBackground(Ui.rounded(7, 0x00000000));
        titleInput.setPadding(Ui.dp(6), Ui.dp(4), Ui.dp(6), Ui.dp(4));
        titleInput.setSingleLine(true);
        titleInput.setHint("未命名录音");
        titleInput.setHintTextColor(Ui.FAINT);
        LinearLayout.LayoutParams tip = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tip.leftMargin = Ui.dp(4);
        titleRow.addView(titleInput, tip);

        catChip = Ui.text(act, "未分类  ›", 11.5f, Ui.FAINT);
        catChip.setSingleLine(true);
        catChip.setGravity(Gravity.CENTER_VERTICAL);
        catChip.setBackground(Ui.pressable(Ui.rounded(7, Ui.SURFACE2, Ui.LINE, 1), 0x22FFFFFF));
        Ui.pad(catChip, 9, 5, 9, 5);
        catChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickCategory();
            }
        });
        titleRow.addView(catChip, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 6, 0, 0, 0));
        head.addView(titleRow);

        meta = Ui.text(act, "", 10.5f, Ui.FAINT);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(meta, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 4, 3, 4, 0));

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
                // 拖动中只更新预览，松手的那次才落到播放器，避免一秒几十次 seekTo
                if (playerOpened && !seekBar.isDragging()) player.seek(playPos);
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

        markAddBtn = Ui.text(act, "标记", 11.5f, Ui.TEXT);
        markAddBtn.setGravity(Gravity.CENTER);
        markAddBtn.setBackground(Ui.pressable(Ui.rounded(8, Ui.SURFACE, 0xFF5A5F69, 1), 0x22FFFFFF));
        Ui.pad(markAddBtn, 10, 6, 10, 6);
        markAddBtn.setEnabled(false);
        markAddBtn.setAlpha(0.35f);
        markAddBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                addMark();
            }
        });
        playerRow.addView(markAddBtn, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(32),
                9, 0, 0, 0));
        head.addView(playerRow, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 9, 0, 0));

        sheet.addView(head, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------------- tab 行（仅窄屏）
        tabsRow = Ui.row(act);
        tabSegs = tabText("转写");
        tabMarks = tabText("标记");
        tabSegs.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showPage(0);
            }
        });
        tabMarks.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showPage(1);
            }
        });
        LinearLayout.LayoutParams tsP = Ui.lp(0, Ui.dp(30), 1f);
        tabsRow.addView(tabSegs, tsP);
        LinearLayout.LayoutParams tmP = Ui.lp(0, Ui.dp(30), 1f);
        tmP.leftMargin = Ui.dp(6);
        tabsRow.addView(tabMarks, tmP);

        // ---------------- 两个并列分页
        paths = Ui.text(act, "", 10f, Ui.FAINT);
        paths.setTypeface(Typeface.MONOSPACE);
        paths.setVisibility(View.GONE);

        LinearLayout pages = Ui.row(act);
        colSegs = buildSegsColumn();
        colMarks = buildMarksColumn();

        if (dual) {
            pages.addView(colSegs, Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            View split = new View(act);
            split.setBackgroundColor(Ui.LINE);
            pages.addView(split, Ui.lp(Math.max(1, Ui.dp(1)), ViewGroup.LayoutParams.MATCH_PARENT));
            pages.addView(colMarks, Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            sheet.addView(pages, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            tabsRow.setVisibility(View.VISIBLE);
            sheet.addView(tabsRow, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 12, 8, 12, 6));
            pageWidthPx = (int) Ui.screenWidth();
            pages.addView(colSegs, Ui.lp(pageWidthPx, ViewGroup.LayoutParams.MATCH_PARENT));
            pages.addView(colMarks, Ui.lp(pageWidthPx, ViewGroup.LayoutParams.MATCH_PARENT));
            pager = new HorizontalScrollView(act);
            pager.setHorizontalScrollBarEnabled(false);
            pager.setFillViewport(true);
            pager.setOverScrollMode(View.OVER_SCROLL_NEVER);
            pager.addView(pages, new HorizontalScrollView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            sheet.addView(pager, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            pager.setOnScrollChangeListener(new View.OnScrollChangeListener() {
                public void onScrollChange(View v, int x, int y, int oldx, int oldy) {
                    if (Math.abs(x - oldx) < 2) return;
                    ui.removeCallbacks(snapRunnable);
                    ui.postDelayed(snapRunnable, 220);
                }
            });
            // 视口宽度与初值不符（分栏 inset 等）时按实际宽度重排两页。
            pager.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                public void onLayoutChange(View v, int l, int t, int r, int b,
                                           int ol, int ot, int or_, int ob) {
                    int w = pager.getWidth();
                    if (w <= 0 || w == pageWidthPx) return;
                    pageWidthPx = w;
                    setColumnWidth(colSegs, w);
                    setColumnWidth(colMarks, w);
                }
            });
            setTabs(0);
        }

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

    /** 「转写文本」列：列头（句数提示 + 转文字按钮）+ 可滚动逐句列表。 */
    private LinearLayout buildSegsColumn() {
        LinearLayout col = Ui.col(act);

        LinearLayout header = Ui.row(act);
        Ui.pad(header, 4, 2, 4, 6);
        segsHint = Ui.text(act, "转写文本", 10.5f, Ui.FAINT);
        segsHint.setLetterSpacing(0.08f);
        header.addView(segsHint, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        transcribeBtn = Ui.smallButton(act, "转文字");
        transcribeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (vm != null && listener != null) listener.onTranscribe(vm);
            }
        });
        header.addView(transcribeBtn);
        col.addView(header, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sc = new ScrollView(act);
        sc.setVerticalScrollBarEnabled(false);
        sc.setClipToPadding(false);
        LinearLayout inner = Ui.col(act);
        Ui.pad(inner, 4, 0, 4, 12);
        segsBox = Ui.col(act);
        segsBox.setBackground(Ui.rounded(10, Ui.SURFACE2, Ui.LINE, 1));
        Ui.pad(segsBox, 5, 5, 5, 5);
        inner.addView(segsBox, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        inner.addView(paths, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 2, 6, 2, 0));
        sc.addView(inner, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(sc, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return col;
    }

    /** 「标记」列：列头（计数 + 打点提示）+ 可滚动标记列表（时间 + 对应转写 + 删除）。 */
    private LinearLayout buildMarksColumn() {
        LinearLayout col = Ui.col(act);

        LinearLayout header = Ui.row(act);
        Ui.pad(header, 4, 2, 4, 6);
        TextView mt = Ui.text(act, "标记", 10.5f, Ui.FAINT);
        mt.setLetterSpacing(0.08f);
        header.addView(mt);
        markCount = Ui.text(act, "", 10.5f, Ui.FAINT);
        header.addView(markCount, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 4, 0, 0, 0));
        TextView hint = Ui.text(act, "播放中按「标记」打点", 9.5f, Ui.FAINT);
        hint.setGravity(Gravity.END);
        LinearLayout.LayoutParams hp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hp.leftMargin = Ui.dp(8);
        header.addView(hint, hp);
        col.addView(header, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sc = new ScrollView(act);
        sc.setVerticalScrollBarEnabled(false);
        sc.setClipToPadding(false);
        marksBox = Ui.col(act);
        Ui.pad(marksBox, 2, 0, 2, 12);
        sc.addView(marksBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(sc, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return col;
    }

    private void setColumnWidth(LinearLayout col, int widthPx) {
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) col.getLayoutParams();
        if (p == null) return;
        p.width = widthPx;
        col.setLayoutParams(p);
    }

    private TextView tabText(String label) {
        TextView t = Ui.text(act, label, 12f, Ui.DIM);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void setTabs(int idx) {
        tabIdx = idx;
        styleTab(tabSegs, idx == 0);
        styleTab(tabMarks, idx == 1);
    }

    private void styleTab(TextView t, boolean selected) {
        t.setTextColor(selected ? Ui.TEXT : Ui.DIM);
        t.setBackground(Ui.pressable(Ui.rounded(8,
                selected ? Ui.SURFACE3 : 0x00000000,
                selected ? Ui.LINE2 : Ui.LINE, 1), 0x22FFFFFF));
    }

    /** 点 tab 切页：宽屏双栏时按钮不响应（两页都在屏上）。 */
    private void showPage(int idx) {
        if (pager == null) return;
        int w = pager.getWidth();
        if (w > 0) pager.smoothScrollTo(idx * w, 0);
        setTabs(idx);
    }

    /** 横滑停下后吸附到整页。 */
    private void snapPage() {
        if (pager == null) return;
        int w = pager.getWidth();
        if (w <= 0) return;
        int idx = Math.round(pager.getScrollX() / (float) w);
        if (idx < 0) idx = 0;
        if (idx > 1) idx = 1;
        if (pager.getScrollX() == idx * w && tabIdx == idx) return;
        if (pager.getScrollX() != idx * w) pager.smoothScrollTo(idx * w, 0);
        setTabs(idx);
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

    /** 硬件标记键：详情打开且正在播放时返回 true，由 Activity 吃掉按键。 */
    public boolean triggerMark() {
        if (!open || !playerOpened || !player.playing()) return false;
        addMark();
        return true;
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
            sheet.setTranslationY(sheet.getHeight() == 0 ? Ui.screenHeight() : sheet.getHeight());
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
        int spkN = distinctSpeakers();
        if (spkN > 1) m += " · " + spkN + " 位发言人";
        meta.setText(m);
        boolean hasCat = vm.cat != null && vm.cat.length() > 0;
        catChip.setText(hasCat ? settings.catName(vm.cat) + "  ›" : "未分类  ›");
        catChip.setTextColor(hasCat ? Ui.TEXT : Ui.FAINT);
        transcribeBtn.setText("done".equals(vm.status()) ? "重新转写" : "转文字");

        boolean hasAudio = vm.hasAudio();
        playBtn.setAlpha(hasAudio ? 1f : 0.3f);
        playBtn.setEnabled(hasAudio);
        markAddBtn.setEnabled(false);
        markAddBtn.setAlpha(0.35f);
        totalMs = (int) Math.max(1000, vm.durMs);
        seekBar.setTotal(totalMs / 1000.0);
        seekBar.setMarks(vm.marks);
        timeText.setText("00:00 / " + RecordVm.fmtDur(totalMs / 1000.0));
        if (!keepTitle || playerOpened) {
            playPos = 0;
        }
        if (!keepTitle && pager != null) {
            pager.scrollTo(0, 0);
            setTabs(0);
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
        persistMarks();
        seekBar.setMarks(vm.marks);
        rebuildMarks();
        if (listener != null) listener.onMetaChanged(vm);
        toast("标记 " + RecordVm.fmtDur(t));
    }

    private void removeMark(int index) {
        if (vm == null || index < 0 || index >= vm.marks.size()) return;
        double t = vm.marks.remove(index);
        persistMarks();
        seekBar.setMarks(vm.marks);
        rebuildMarks();
        if (listener != null) listener.onMetaChanged(vm);
        toast("已删除标记 " + RecordVm.fmtDur(t));
    }

    private void persistMarks() {
        if (vm == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < vm.marks.size(); i++) arr.put(vm.marks.get(i));
            JSONObject p = new JSONObject();
            p.put("marks", arr);
            data.patch(vm.id, p, null);
        } catch (Throwable ignore) { }
    }

    /** 标记时间点上的转写句：优先取覆盖句；落在句间空隙取前一句；早于第一句取第一句。 */
    private Item.Seg segAt(double t) {
        if (vm == null) return null;
        Item.Seg before = null;
        for (int i = 0; i < vm.segs.size(); i++) {
            Item.Seg s = vm.segs.get(i);
            if (t >= s.start && t < Math.max(s.start + 0.05, s.end)) return s;
            if (s.start <= t) before = s;
        }
        if (before != null) return before;
        return vm.segs.isEmpty() ? null : vm.segs.get(0);
    }

    /** 「标记」列：不再是横向 chip 分类条；每条 = 时间 + 对应转写文字 + 删除。 */
    private void rebuildMarks() {
        marksBox.removeAllViews();
        int n = vm == null ? 0 : vm.marks.size();
        markCount.setText(n > 0 ? String.valueOf(n) : "");
        if (!dual) tabMarks.setText(n > 0 ? "标记 · " + n : "标记");
        if (n == 0) {
            TextView empty = Ui.text(act, "暂无标记：播放中点「标记」或按标记键打点", 10.5f, Ui.FAINT);
            empty.setGravity(Gravity.CENTER);
            Ui.pad(empty, 10, 10, 10, 10);
            marksBox.addView(empty);
            return;
        }
        for (int i = 0; i < vm.marks.size(); i++) {
            marksBox.addView(markRow(vm.marks.get(i), i));
        }
    }

    private View markRow(final double t, final int index) {
        LinearLayout card = Ui.col(act);
        card.setBackground(Ui.pressable(Ui.rounded(10, Ui.SURFACE2, Ui.LINE, 1), 0x22FFFFFF));
        Ui.pad(card, 10, 8, 8, 8);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.bottomMargin = Ui.dp(6);
        card.setLayoutParams(cp);

        LinearLayout top = Ui.row(act);
        TextView idx = Ui.text(act, String.valueOf(index + 1), 10f, Ui.DIM, true);
        idx.setGravity(Gravity.CENTER);
        idx.setBackground(Ui.rounded(9, Ui.SURFACE3));
        Ui.pad(idx, 6, 2, 6, 2);
        top.addView(idx);

        TextView time = Ui.text(act, RecordVm.fmtDur(t), 12f, Ui.TEXT, true);
        time.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams tp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = Ui.dp(8);
        top.addView(time, tp);

        final Item.Seg seg = segAt(t);
        if (seg != null && seg.spk >= 0) {
            TextView chip = Ui.text(act, String.valueOf(seg.spk + 1), 10f, 0xFF1B1E23);
            chip.setGravity(Gravity.CENTER);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setBackground(Ui.rounded(7, spkBg(seg.spk)));
            Ui.pad(chip, 6, 1, 6, 1);
            top.addView(chip, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 6, 0, 0, 0));
        }

        top.addView(Ui.spacer(act, 1), Ui.lp(0, 1, 1f));

        final int idxFinal = index;
        TextView del = Ui.text(act, "✕", 12.5f, Ui.FAINT);
        del.setGravity(Gravity.CENTER);
        del.setBackground(Ui.pressable(Ui.rounded(8, 0x00000000, Ui.LINE, 1), 0x22FFFFFF));
        Ui.pad(del, 9, 5, 9, 5);
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                removeMark(idxFinal);
            }
        });
        top.addView(del);
        card.addView(top);

        String body = "暂无转写：先点「转文字」";
        if (seg != null && seg.text != null && seg.text.length() > 0) body = seg.text;
        TextView text = Ui.text(act, body, 12.5f, seg == null ? Ui.FAINT : Ui.DIM);
        text.setLineSpacing(Ui.dp(2), 1f);
        card.addView(text, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 2, 0));
        if (seg != null) {
            TextView from = Ui.text(act, "句起 " + RecordVm.fmtDur(seg.start), 9.5f, Ui.FAINT);
            card.addView(from, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 3, 2, 0));
        }

        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                seekTo((int) (t * 1000));
            }
        });
        return card;
    }

    private void rebuildSegs() {
        segsBox.removeAllViews();
        segViews.clear();
        segTimes.clear();
        activeSeg = -1;
        int n = vm == null ? 0 : vm.segs.size();
        segsHint.setText(n > 0 ? "转写文本 · " + n + " 句" : "转写文本");
        if (!dual) tabSegs.setText(n > 0 ? "转写 · " + n : "转写");
        if (n == 0) {
            TextView empty = Ui.text(act, "尚未转写", 12.5f, Ui.FAINT);
            empty.setGravity(Gravity.CENTER);
            Ui.pad(empty, 10, 10, 10, 10);
            segsBox.addView(empty);
            paths.setVisibility(View.GONE);
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
            if (s.spk >= 0) {
                TextView chip = Ui.text(act, String.valueOf(s.spk + 1), 10f, 0xFF1B1E23);
                chip.setGravity(Gravity.CENTER);
                chip.setTypeface(Typeface.DEFAULT_BOLD);
                chip.setBackground(Ui.rounded(7, spkBg(s.spk)));
                Ui.pad(chip, 6, 1, 6, 1);
                row.addView(chip, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 2, 1, 4, 0));
            }
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

    /** Activity 销毁时调用：立刻停回调、释放播放器，不走退场动画（View 树已无效）。 */
    public void destroy() {
        open = false;
        ui.removeCallbacks(ticker);
        ui.removeCallbacks(patchTitle);
        ui.removeCallbacks(snapRunnable);
        try {
            player.release();
        } catch (Throwable ignore) { }
        playerOpened = false;
    }

    @Override
    public void dismiss() {
        if (!open) return;
        open = false;
        ui.removeCallbacks(ticker);
        ui.removeCallbacks(patchTitle);
        ui.removeCallbacks(snapRunnable);
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
