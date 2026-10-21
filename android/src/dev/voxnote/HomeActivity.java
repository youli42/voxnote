package dev.voxnote;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 原生主页：录音 → 列表 → 详情（详情在 DetailSheet）。
 *
 * 与旧 WebView 版的行为对齐：
 *   - 列表按录音时间排（可切换方向），筛选行是 全部/转写失败/未分类/各分类；
 *   - 回前台自动重扫，但扫描在后台线程，UI 不冻结；
 *   - 左/右滑归类、卡片点击进详情、录音面板在列表头部随滚动收起；
 *   - 老版本设置由 {@link LegacySettingsImporter} 一次性迁过来。
 *
 * 有意不同的两处：
 *   - 搜索/筛选条固定在顶部（旧版是滚动到顶后才钉住）；功能一致、实现更稳；
 *   - 不再有“无限加载占位”，ListView 自己回收视图。
 */
public class HomeActivity extends Activity {

    private static final int REQ_PERMS = 7;
    private static final int REQ_TREE = 8;
    private static final int MAX_REC_SEC = 3600;

    private UiSettings settings;
    private State state;
    private Storage storage;
    private DataCenter data;
    private Player player;
    private final Recorder recorder = new Recorder();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout content;
    private View listScreen;
    private View settingsScreen;
    private ListView list;
    private VoxAdapter adapter;
    private EditText searchInput;
    private TextView clearSearch;
    private TextView statusText;
    private TextView sortBtn;
    private TextView refreshBtn;
    private LinearLayout filtersRow;
    private TextView emptyText;
    private TabItem tabRecord;
    private TabItem tabSettings;

    private EditText recTitle;
    private TextView recTimer;
    private WaveView recWave;
    private RecordButton recBtn;
    private View recPanel;

    private final ArrayList<RecordVm> all = new ArrayList<RecordVm>();
    private final ArrayList<RecordVm> shown = new ArrayList<RecordVm>();
    private final java.util.HashMap<String, String> sourceLabels = new java.util.HashMap<String, String>();

    private String filterCat = "all";
    private String searchQ = "";
    private String tab = "record";
    private boolean loading;
    private long loadedAt;
    private long lastBackAt;

    private boolean recording;
    private long recStart;
    private String pendingStamp;
    private String pendingTitle = "";
    private final Runnable recTimerTick = new Runnable() {
        public void run() {
            if (!recording) return;
            long sec = (System.currentTimeMillis() - recStart) / 1000;
            recTimer.setText(RecordVm.fmtDur(sec));
            if (sec >= MAX_REC_SEC) {
                stopRecording();
                return;
            }
            ui.postDelayed(this, 200);
        }
    };
    private final Runnable recWaveTick = new Runnable() {
        public void run() {
            if (!recording) return;
            recWave.setLevel(recorder.level());
            ui.postDelayed(this, 60);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            Window w = getWindow();
            w.setStatusBarColor(Ui.BG_DEEP);
        } catch (Throwable ignore) { }

        settings = UiSettings.load(this);
        state = new State(this).load();
        storage = new Storage(this);
        data = new DataCenter(this, state, storage);
        player = new Player(this);

        setContentView(buildRoot());
        // 冷启动别把键盘顶起来：搜索框/标题框默认都不抢焦点。
        try {
            getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        } catch (Throwable ignore) { }
        content.setFocusableInTouchMode(true);
        content.requestFocus();

        LegacySettingsImporter.run(this, settings, new LegacySettingsImporter.Callback() {
            public void onDone(boolean imported) {
                if (imported) {
                    rebuildFilters();
                    adapter.notifyDataSetChanged();
                }
            }
        });

        askPerms();
        load(false, null, false);
    }

    // ------------------------------------------------------------ 界面

    private View buildRoot() {
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);

        content = new FrameLayout(this);
        root.addView(content, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        listScreen = buildListScreen();
        settingsScreen = buildSettingsPlaceholder();
        content.addView(listScreen);
        content.addView(settingsScreen);
        settingsScreen.setVisibility(View.GONE);

        root.addView(Ui.divider(this));
        root.addView(buildTabBar(), Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private View buildTabBar() {
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.BG);
        tabRecord = new TabItem(this, TabItem.ICON_RECORD, "录音");
        tabSettings = new TabItem(this, TabItem.ICON_SETTINGS, "设置");
        bar.addView(tabRecord, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(tabSettings, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabRecord.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                switchTab("record");
            }
        });
        tabSettings.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                switchTab("settings");
            }
        });
        setTabActive("record");
        return bar;
    }

    private void switchTab(String name) {
        tab = name;
        listScreen.setVisibility("record".equals(name) ? View.VISIBLE : View.GONE);
        settingsScreen.setVisibility("settings".equals(name) ? View.VISIBLE : View.GONE);
        setTabActive(name);
    }

    private void setTabActive(String name) {
        tabRecord.setActive("record".equals(name));
        tabSettings.setActive("settings".equals(name));
    }

    private View buildListScreen() {
        LinearLayout screen = Ui.col(this);
        screen.setBackgroundColor(Ui.BG);

        LinearLayout toolbar = Ui.col(this);
        Ui.pad(toolbar, 10, 8, 10, 4);
        toolbar.setBackgroundColor(Ui.BG);

        LinearLayout searchRow = Ui.row(this);
        searchInput = Ui.input(this, "搜索标题、摘要、转写全文", 12.5f);
        Ui.pad(searchInput, 12, 7, 12, 7);
        searchRow.addView(searchInput, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        clearSearch = Ui.text(this, "✕", 11f, Ui.DIM);
        clearSearch.setGravity(Gravity.CENTER);
        clearSearch.setBackground(Ui.pressable(Ui.rounded(10, Ui.SURFACE3), 0x22FFFFFF));
        clearSearch.setVisibility(View.GONE);
        Ui.pad(clearSearch, 0, 4, 0, 4);
        searchRow.addView(clearSearch, Ui.lpm(Ui.dp(20), Ui.dp(20), 6, 0, 0, 0));
        toolbar.addView(searchRow);

        LinearLayout statusRow = Ui.row(this);
        statusRow.setPadding(0, Ui.dp(4), 0, 0);
        statusText = Ui.text(this, "正在扫描…", 10.5f, Ui.FAINT);
        statusText.setSingleLine(true);
        statusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusRow.addView(statusText, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sortBtn = Ui.text(this, "时间↓", 11f, Ui.FAINT);
        sortBtn.setBackground(Ui.pressable(Ui.rounded(14, 0x00000000, Ui.LINE, 1), 0x22FFFFFF));
        sortBtn.setGravity(Gravity.CENTER);
        Ui.pad(sortBtn, 9, 3, 9, 3);
        statusRow.addView(sortBtn, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 6, 0, 0, 0));
        refreshBtn = Ui.text(this, "刷新", 11f, Ui.FAINT);
        refreshBtn.setBackground(Ui.pressable(Ui.rounded(14, 0x00000000, Ui.LINE, 1), 0x22FFFFFF));
        refreshBtn.setGravity(Gravity.CENTER);
        Ui.pad(refreshBtn, 9, 3, 9, 3);
        statusRow.addView(refreshBtn, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 6, 0, 0, 0));
        toolbar.addView(statusRow);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        filtersRow = Ui.row(this);
        hs.addView(filtersRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        hs.setPadding(0, Ui.dp(6), 0, Ui.dp(2));
        toolbar.addView(hs, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        screen.addView(toolbar, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout listWrap = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setPadding(Ui.dp(10), 0, Ui.dp(10), 0);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0x00000000));
        list.setCacheColorHint(0x00000000);
        list.setBackgroundColor(Ui.BG);
        list.addHeaderView(buildRecordPanel(), null, false);
        emptyText = Ui.text(this, "没有符合条件的录音", 12.5f, Ui.FAINT);
        emptyText.setGravity(Gravity.CENTER);
        Ui.pad(emptyText, 0, 40, 0, 40);
        emptyText.setVisibility(View.GONE);
        list.addFooterView(emptyText, null, false);
        adapter = new VoxAdapter();
        list.setAdapter(adapter);
        listWrap.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        screen.addView(listWrap, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        list.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            public void onScrollStateChanged(android.widget.AbsListView v, int s) { }

            public void onScroll(android.widget.AbsListView v, int first, int visible, int total) { }
        });

        searchInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            public void onTextChanged(CharSequence s, int a, int b, int c) { }

            public void afterTextChanged(Editable s) {
                clearSearch.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                ui.removeCallbacks(searchDebounce);
                ui.postDelayed(searchDebounce, 200);
            }
        });
        clearSearch.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                searchInput.setText("");
                searchInput.clearFocus();
            }
        });
        sortBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                settings.sortDesc = !settings.sortDesc;
                settings.save();
                applyFilter(false);
            }
        });
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                load(false, null, true);
            }
        });
        return screen;
    }

    private final Runnable searchDebounce = new Runnable() {
        public void run() {
            searchQ = searchInput.getText().toString().trim();
            applyFilter(false);
        }
    };

    private View buildRecordPanel() {
        LinearLayout panel = Ui.col(this);
        panel.setBackground(Ui.rounded(20, 0xFF191C21, Ui.LINE, 1));
        Ui.pad(panel, 14, 16, 14, 18);

        recTitle = new EditText(this);
        recTitle.setHint("添加标题（可选）");
        recTitle.setHintTextColor(Ui.FAINT);
        recTitle.setTextColor(Ui.TEXT);
        recTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f);
        recTitle.setGravity(Gravity.CENTER);
        recTitle.setSingleLine(true);
        recTitle.setInputType(InputType.TYPE_CLASS_TEXT);
        recTitle.setBackground(Ui.rounded(8, Ui.SURFACE2, Ui.LINE, 1));
        Ui.pad(recTitle, 10, 7, 10, 7);
        panel.addView(recTitle, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        recTimer = Ui.text(this, "00:00", 38f, Ui.TEXT);
        recTimer.setGravity(Gravity.CENTER);
        recTimer.setLetterSpacing(0.04f);
        panel.addView(recTimer, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));

        recWave = new WaveView(this);
        panel.addView(recWave, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));

        FrameLayout btnWrap = new FrameLayout(this);
        recBtn = new RecordButton(this);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(Ui.dp(68), Ui.dp(68));
        bp.gravity = Gravity.CENTER;
        btnWrap.addView(recBtn, bp);
        btnWrap.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        recBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggleRecording();
            }
        });
        panel.addView(btnWrap, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));

        // ListView 的 header 不吃 margin，用一层带 padding 的容器做上下留白。
        LinearLayout wrap = Ui.col(this);
        wrap.setPadding(0, Ui.dp(10), 0, Ui.dp(12));
        wrap.addView(panel, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        recPanel = panel;
        return wrap;
    }

    private View buildSettingsPlaceholder() {
        LinearLayout wrap = Ui.col(this);
        wrap.setBackgroundColor(Ui.BG);
        Ui.pad(wrap, 16, 24, 16, 24);
        wrap.addView(Ui.text(this, "设置页即将接入", 12.5f, Ui.DIM));
        wrap.addView(Ui.text(this, "分类 / 按键 / 转写 API / 存储位置 / 外部来源",
                10.5f, Ui.FAINT), Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));
        return wrap;
    }

    // ------------------------------------------------------------ 数据

    private void load(boolean forceAll, String onlySourceId, final boolean toastResult) {
        if (loading) return;
        loading = true;
        refreshBtn.setEnabled(false);
        statusText.setText("正在扫描…");

        final int keepPos = list.getFirstVisiblePosition();
        final View keep = list.getChildAt(0);
        final int keepTop = keep == null ? 0 : keep.getTop();

        data.load(forceAll, onlySourceId, new DataCenter.LoadCallback() {
            public void onLoaded(DataCenter.Result r) {
                loading = false;
                refreshBtn.setEnabled(true);
                loadedAt = System.currentTimeMillis();
                all.clear();
                all.addAll(r.items);
                sourceLabels.clear();
                sourceLabels.putAll(r.sourceLabels);

                if (r.error != null && r.error.length() > 0) {
                    statusText.setText("⚠️ " + r.error);
                } else {
                    String at = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                            .format(new java.util.Date(r.scannedAt));
                    statusText.setText(r.total + " 场 · " + r.done + " 已转写 · "
                            + r.sentences + " 句 · " + at + " 扫描");
                }
                applyFilter(true, keepPos, keepTop);
                if (toastResult) toast("已扫描 " + r.total + " 场");
            }
        });
    }

    private void applyFilter(boolean keepScroll) {
        applyFilter(keepScroll, -1, 0);
    }

    private void applyFilter(boolean keepScroll, int keepPos, int keepTop) {
        shown.clear();
        String q = searchQ == null ? "" : searchQ.toLowerCase(java.util.Locale.CHINA);
        for (int i = 0; i < all.size(); i++) {
            RecordVm vm = all.get(i);
            if (!matchesFilter(vm)) continue;
            if (q.length() > 0 && !matchesSearch(vm, q)) continue;
            shown.add(vm);
        }
        if (!settings.sortDesc) {
            ArrayList<RecordVm> rev = new ArrayList<RecordVm>(shown);
            java.util.Collections.reverse(rev);
            shown.clear();
            shown.addAll(rev);
        }
        sortBtn.setText(settings.sortDesc ? "时间↓" : "时间↑");
        adapter.notifyDataSetChanged();
        emptyText.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        emptyText.setText(searchQ.length() > 0 ? "没有匹配的录音"
                : ("@failed".equals(filterCat) ? "没有转写失败的录音" : "该分类下还没有录音"));
        rebuildFilters();
        if (keepScroll && keepPos >= 0) {
            list.setSelectionFromTop(keepPos, keepTop);
        } else {
            list.setSelection(0);
        }
    }

    private boolean matchesFilter(RecordVm vm) {
        if ("all".equals(filterCat)) return true;
        if ("@failed".equals(filterCat)) return "failed".equals(vm.status());
        if ("none".equals(filterCat)) return vm.cat == null || vm.cat.length() == 0;
        return filterCat.equals(vm.cat);
    }

    private boolean matchesSearch(RecordVm vm, String q) {
        if (vm.title != null && vm.title.toLowerCase(java.util.Locale.CHINA).contains(q)) return true;
        if (vm.summary != null && vm.summary.toLowerCase(java.util.Locale.CHINA).contains(q)) return true;
        for (int i = 0; i < vm.segs.size(); i++) {
            String t = vm.segs.get(i).text;
            if (t != null && t.toLowerCase(java.util.Locale.CHINA).contains(q)) return true;
        }
        return false;
    }

    private void rebuildFilters() {
        filtersRow.removeAllViews();
        int noneCount = 0;
        int failCount = 0;
        for (int i = 0; i < all.size(); i++) {
            RecordVm vm = all.get(i);
            if (vm.cat == null || vm.cat.length() == 0) noneCount++;
            if ("failed".equals(vm.status())) failCount++;
        }
        addChip("all", "全部", all.size());
        addChip("@failed", "转写失败", failCount);
        addChip("none", "未分类", noneCount);
        for (int i = 0; i < settings.categories.size(); i++) {
            UiSettings.Cat c = settings.categories.get(i);
            int n = 0;
            for (int j = 0; j < all.size(); j++) {
                if (c.id.equals(all.get(j).cat)) n++;
            }
            addChip(c.id, c.name, n);
        }
    }

    private void addChip(final String id, String name, int count) {
        boolean active = (filterCat == null && id == null) || (filterCat != null && filterCat.equals(id));
        TextView chip = Ui.text(this, name + " " + count, 12f, active ? Ui.TEXT : Ui.FAINT);
        chip.setSingleLine(true);
        chip.setBackground(active
                ? Ui.pressable(Ui.rounded(14, Ui.SURFACE2, Ui.LINE2, 1), 0x22FFFFFF)
                : Ui.pressable(Ui.rounded(14, 0x00000000, Ui.LINE, 1), 0x22FFFFFF));
        chip.setGravity(Gravity.CENTER);
        Ui.pad(chip, 11, 5, 11, 5);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.rightMargin = Ui.dp(6);
        filtersRow.addView(chip, p);
        chip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                filterCat = id;
                applyFilter(false);
            }
        });
    }

    // ------------------------------------------------------------ 行交互

    private final SwipeRow.Host rowHost = new SwipeRow.Host() {
        public void onOpen(RecordVm vm) {
            openDetail(vm);
        }

        public void onTranscribe(RecordVm vm) {
            transcribe(vm);
        }

        public void onSwipe(RecordVm vm, String targetCat) {
            boolean cancel = targetCat.equals(vm.cat);
            vm.cat = cancel ? null : targetCat;
            try {
                JSONObject p = new JSONObject();
                p.put("cat", cancel ? JSONObject.NULL : targetCat);
                data.patch(vm.id, p, null);
            } catch (Throwable ignore) { }
            adapter.notifyDataSetChanged();
            rebuildFilters();
            toast(cancel ? "已取消分类" : "已归入「" + settings.catName(targetCat) + "」");
        }
    };

    private class VoxAdapter extends BaseAdapter {
        public int getCount() {
            return shown.size();
        }

        public Object getItem(int position) {
            return shown.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            SwipeRow row;
            if (convertView instanceof SwipeRow) {
                row = (SwipeRow) convertView;
            } else {
                row = new SwipeRow(HomeActivity.this);
            }
            RecordVm vm = shown.get(position);
            String src = vm.fromSource == null ? null : sourceLabels.get(vm.fromSource);
            row.bind(vm, settings, rowHost, src);
            return row;
        }
    }

    private void openDetail(RecordVm vm) {
        toast("详情面板：下一步接入");
    }

    private void transcribe(final RecordVm vm) {
        toast("转写：正在重扫外部来源…");
        data.load(true, null, new DataCenter.LoadCallback() {
            public void onLoaded(DataCenter.Result r) {
                for (int i = 0; i < r.items.size(); i++) {
                    RecordVm x = r.items.get(i);
                    if (x.id.equals(vm.id)) {
                        if (!x.segs.isEmpty()) {
                            toast("已从外部来源导入 " + x.segs.size() + " 句");
                        } else {
                            toast("外部来源里没有这场的内容（自录的录音只有声音，没有字）");
                        }
                        load(false, null, false);
                        return;
                    }
                }
                toast("外部来源里没有这场的内容");
                load(false, null, false);
            }
        });
    }

    // ------------------------------------------------------------ 录音

    private void toggleRecording() {
        if (recording) stopRecording();
        else startRecording();
    }

    private void startRecording() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            askPerms();
            toast("需要麦克风权限");
            return;
        }
        pendingStamp = Recorder.stamp();
        pendingTitle = recTitle.getText().toString().trim();
        File tmp = new File(storage.privateTmp(), pendingStamp + "_memo.m4a");
        if (!recorder.start(tmp)) {
            toast("无法开始录音：检查麦克风权限");
            return;
        }
        recording = true;
        recStart = System.currentTimeMillis();
        recTitle.setText("");
        recBtn.setRecording(true);
        recWave.setActive(true);
        recTimer.setText("00:00");
        ui.post(recTimerTick);
        ui.post(recWaveTick);
    }

    private void stopRecording() {
        if (!recording) return;
        recording = false;
        ui.removeCallbacks(recTimerTick);
        ui.removeCallbacks(recWaveTick);
        recBtn.setRecording(false);
        recWave.setActive(false);
        long durSec = Math.max(1, (System.currentTimeMillis() - recStart) / 1000);
        recTimer.setText("00:00");

        File f = recorder.stop();
        if (f == null) {
            toast("录音失败：没有写进文件");
            return;
        }
        String loc = storage.publish(f, f.getName(), "audio/mp4");
        if (pendingTitle != null && pendingTitle.length() > 0 && pendingStamp != null) {
            try {
                JSONObject p = new JSONObject();
                p.put("title", pendingTitle);
                data.patch(pendingStamp, p, null);
            } catch (Throwable ignore) { }
        }
        pendingStamp = null;
        pendingTitle = "";
        toast("已保存 " + RecordVm.fmtDur(durSec) + (loc == null || loc.length() == 0 ? "" : ""));
        load(false, null, false);
        list.setSelection(0);
    }

    // ------------------------------------------------------------ 权限 / 存储

    private void askPerms() {
        List<String> need = new ArrayList<String>();
        if (Build.VERSION.SDK_INT <= 28) {
            addIfMissing(need, Manifest.permission.READ_EXTERNAL_STORAGE);
            addIfMissing(need, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        } else if (Build.VERSION.SDK_INT <= 32) {
            addIfMissing(need, Manifest.permission.READ_EXTERNAL_STORAGE);
        } else {
            addIfMissing(need, "android.permission.READ_MEDIA_AUDIO");
        }
        addIfMissing(need, Manifest.permission.RECORD_AUDIO);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[need.size()]), REQ_PERMS);
    }

    private void addIfMissing(List<String> need, String perm) {
        try {
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) need.add(perm);
        } catch (Throwable ignore) { }
    }

    private boolean hasPermission(String perm) {
        try {
            return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_PERMS) load(false, null, false);
    }

    public void pickSafDir() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_TREE);
        } catch (Throwable t) {
            toast("打不开文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_TREE) {
            super.onActivityResult(req, res, data);
            return;
        }
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Throwable ignore) { }
        storage.setSafTree(uri.toString());
        storage.setMode(Storage.MODE_SAF);
        toast("已切换自定义目录");
        load(false, null, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (settingsScreen != null && loadedAt > 0
                && System.currentTimeMillis() - loadedAt > 1500 && !loading) {
            load(false, null, false);
        }
    }

    @Override
    public void onBackPressed() {
        if ("settings".equals(tab)) {
            switchTab("record");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackAt < 2000) {
            super.onBackPressed();
            return;
        }
        lastBackAt = now;
        toast("再按一次退出");
    }

    @Override
    protected void onDestroy() {
        try {
            if (recording) recorder.stop();
        } catch (Throwable ignore) { }
        try {
            player.release();
        } catch (Throwable ignore) { }
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
