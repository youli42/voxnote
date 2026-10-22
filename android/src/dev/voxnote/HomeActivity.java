package dev.voxnote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
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

import org.json.JSONArray;
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
public class HomeActivity extends Activity implements Picker.Host {

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
    private SettingsScreen settingsPage;
    private ListView list;
    private VoxAdapter adapter;
    private EditText searchInput;
    private TextView clearSearch;
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
    private final ArrayList<Overlay> overlays = new ArrayList<Overlay>();
    private DetailSheet detail;

    private String filterCat = "all";
    private String searchQ = "";
    private String tab = "record";
    private boolean loading;
    private long loadedAt;
    private long lastBackAt;
    private String lastScanError = "";

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

        detail = new DetailSheet(this, player, data, settings, this, detailListener);

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
        settingsPage = new SettingsScreen(this, settings, storage, settingsHost);
        settingsScreen = settingsPage.view();
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
        bar.setGravity(Gravity.CENTER);
        tabRecord = new TabItem(this, TabItem.ICON_RECORD, "录音");
        tabSettings = new TabItem(this, TabItem.ICON_SETTINGS, "设置");
        // 两格固定宽度并居中：原来各占半屏，文字离得太开，看着像两条空栏。
        bar.addView(tabRecord, Ui.lpm(Ui.dp(92), ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, Ui.dp(8), 0));
        bar.addView(tabSettings, Ui.lp(Ui.dp(92), ViewGroup.LayoutParams.WRAP_CONTENT));
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
        if ("settings".equals(name)) {
            settingsPage.refresh();
            settingsPage.refreshSources(data.sourceInfoBlocking());
        }
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

        LinearLayout buttonsRow = Ui.row(this);
        buttonsRow.setPadding(0, Ui.dp(5), 0, 0);
        TextView dummy = Ui.text(this, "", 1f, Ui.BG);
        buttonsRow.addView(dummy, Ui.lp(0, 0, 1f));
        sortBtn = Ui.text(this, "时间↓", 11f, Ui.FAINT);
        sortBtn.setBackground(Ui.pressable(Ui.rounded(14, 0x00000000, Ui.LINE, 1), 0x22FFFFFF));
        sortBtn.setGravity(Gravity.CENTER);
        Ui.pad(sortBtn, 9, 3, 9, 3);
        buttonsRow.addView(sortBtn, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 6, 0, 0, 0));
        refreshBtn = Ui.text(this, "刷新", 11f, Ui.FAINT);
        refreshBtn.setBackground(Ui.pressable(Ui.rounded(14, 0x00000000, Ui.LINE, 1), 0x22FFFFFF));
        refreshBtn.setGravity(Gravity.CENTER);
        Ui.pad(refreshBtn, 9, 3, 9, 3);
        buttonsRow.addView(refreshBtn, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 6, 0, 0, 0));
        toolbar.addView(buttonsRow);

        // 筛选分类单独一行，和上面的排序/刷新拉开距离，不再挤在同一块里。
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        filtersRow = Ui.row(this);
        hs.addView(filtersRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        hs.setPadding(0, Ui.dp(12), 0, Ui.dp(2));
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

    private final SettingsScreen.Host settingsHost = new SettingsScreen.Host() {
        public void onSettingsChanged() {
            adapter.notifyDataSetChanged();
            rebuildFilters();
        }

        public void onCategoryDeleted(String catId) {
            if (catId != null && catId.equals(filterCat)) filterCat = "all";
            data.clearCat(catId, new DataCenter.DoneCallback() {
                public void onDone() {
                    load(false, null, false);
                }
            });
        }

        public void onStorageChanged() {
            load(false, null, false);
        }

        public void onPickSaf() {
            pickSafDir();
        }

        public void onClearSaf() {
            storage.clearSaf();
            storage.setMode(Storage.MODE_PUBLIC);
            settings.save();
            settingsPage.refresh();
            load(false, null, false);
            toast("已清除自定义目录");
        }

        public void onRequestStoragePerms() {
            askPerms();
        }

        public void onScanSource(final String id) {
            toast("正在扫描来源…");
            data.load(true, id, new DataCenter.LoadCallback() {
                public void onLoaded(DataCenter.Result r) {
                    applyResult(r, true, 0, 0, false);
                    settingsPage.refreshSources(data.sourceInfoBlocking());
                    String msg = "已导入";
                    JSONArray arr = r.sources;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o != null && id.equals(o.optString("id", ""))) {
                            msg = "已导入 " + o.optInt("sessions", 0) + " 场 / "
                                    + o.optInt("sentences", 0) + " 句";
                        }
                    }
                    toast(msg);
                }
            });
        }

        public void onResetDefaults() {
            settings.resetDefaults();
            filterCat = "all";
            searchQ = "";
            searchInput.setText("");
            adapter.notifyDataSetChanged();
            rebuildFilters();
            settingsPage.refresh();
            toast("已恢复默认设置");
        }

        public void onBeginKeyCapture() {
            capturingKey = true;
            settingsPage.setCapturing(true);
            toast("请按一个键（返回键取消）");
        }

        public int ignoredCount() {
            return state.ignoredCount();
        }

        public void onRestoreIgnored() {
            if (state.ignoredCount() == 0) {
                toast("没有被屏蔽的场次");
                return;
            }
            data.restoreIgnored(new DataCenter.DoneCallback() {
                public void onDone() {
                    load(false, null, false);
                    settingsPage.refresh();
                    toast("已恢复被屏蔽的场次");
                }
            });
        }
    };

    private boolean capturingKey;

    // ------------------------------------------------------------ 数据

    private void load(boolean forceAll, String onlySourceId, final boolean toastResult) {
        if (loading) return;
        loading = true;
        refreshBtn.setEnabled(false);
        refreshBtn.setText("扫描中…");

        final int keepPos = list.getFirstVisiblePosition();
        final View keep = list.getChildAt(0);
        final int keepTop = keep == null ? 0 : keep.getTop();

        data.load(forceAll, onlySourceId, new DataCenter.LoadCallback() {
            public void onLoaded(DataCenter.Result r) {
                loading = false;
                refreshBtn.setEnabled(true);
                refreshBtn.setText("刷新");
                loadedAt = System.currentTimeMillis();
                applyResult(r, true, keepPos, keepTop, toastResult);
            }
        });
    }

    private void applyResult(DataCenter.Result r, boolean keepScroll, int keepPos, int keepTop,
                             boolean toastResult) {
        all.clear();
        all.addAll(r.items);
        sourceLabels.clear();
        sourceLabels.putAll(r.sourceLabels);

        if (r.error != null && r.error.length() > 0) {
            // 统计行已去掉，扫描失败只在出错时提示一次，不占版面。
            if (!r.error.equals(lastScanError)) {
                lastScanError = r.error;
                toast("扫描失败：" + r.error);
            }
        } else {
            lastScanError = "";
        }
        applyFilter(keepScroll, keepPos, keepTop);
        if (detail != null && detail.isOpen()) {
            RecordVm cur = findVm(detail.id());
            if (cur != null) detail.refresh(cur);
        }
        if (toastResult) toast("已扫描 " + r.total + " 场");
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

        public void onMore(RecordVm vm) {
            showCardMenu(vm);
        }

        public void onLongPress(RecordVm vm) {
            showCardMenu(vm);
        }
    };

    // ------------------------------------------------------------ 卡片菜单 / 删除

    /** 卡片右上 ⋯（长按等同）：详情 / 归类 / 删除。 */
    private void showCardMenu(final RecordVm vm) {
        String[] items = {"打开详情", "归入分类…", "删除…"};
        Picker.show(this, this, vm.title, items, -1, new Picker.OnPick() {
            public void onPick(int i) {
                if (i == 0) {
                    openDetail(vm);
                } else if (i == 1) {
                    pickCategoryFor(java.util.Collections.singletonList(vm));
                } else if (i == 2) {
                    confirmDelete(java.util.Collections.singletonList(vm));
                }
            }
        });
    }

    /** 给若干场挑一个分类；单条菜单和批量归并共用。 */
    private void pickCategoryFor(final List<RecordVm> vms) {
        if (vms == null || vms.isEmpty()) return;
        if (settings.categories.isEmpty()) {
            toast("先在设置里加一个分类");
            return;
        }
        String[] labels = new String[settings.categories.size()];
        int sel = -1;
        for (int i = 0; i < settings.categories.size(); i++) {
            labels[i] = settings.categories.get(i).name;
            if (vms.size() == 1 && settings.categories.get(i).id.equals(vms.get(0).cat)) sel = i;
        }
        Picker.show(this, this, "归入分类", labels, sel, new Picker.OnPick() {
            public void onPick(int i) {
                final String cat = settings.categories.get(i).id;
                ArrayList<String> ids = new ArrayList<String>();
                for (int j = 0; j < vms.size(); j++) ids.add(vms.get(j).id);
                data.patchCat(ids, cat, new DataCenter.DoneCallback() {
                    public void onDone() {
                        for (int j = 0; j < vms.size(); j++) vms.get(j).cat = cat;
                        adapter.notifyDataSetChanged();
                        rebuildFilters();
                        toast("已归入「" + settings.catName(cat) + "」");
                    }
                });
            }
        });
    }

    /** 删除确认：按来源分别计数，文案写明哪部分不可恢复。 */
    private void confirmDelete(final List<RecordVm> vms) {
        if (vms == null || vms.isEmpty()) return;
        int own = 0, vendor = 0;
        for (int i = 0; i < vms.size(); i++) {
            if (storage.isOwnAudio(vms.get(i).audioPath, vms.get(i).audioUri)) own++;
            else vendor++;
        }
        StringBuilder msg = new StringBuilder();
        if (own > 0) msg.append("· ").append(own).append(" 场自录：删除录音文件，不可恢复");
        if (vendor > 0) {
            if (msg.length() > 0) msg.append('\n');
            msg.append("· ").append(vendor)
                    .append(" 场厂商导入：仅从列表屏蔽（可撤销），原始录音与日志不动");
        }
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(vms.size() == 1 ? "删除这场录音？" : "删除 " + vms.size() + " 场？")
                .setMessage(msg.toString())
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        doDelete(vms);
                    }
                }).create();
        dlg.show();
        try {
            dlg.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(Ui.DANGER);
        } catch (Throwable ignore) { }
    }

    private void doDelete(final List<RecordVm> vms) {
        data.delete(vms, new DataCenter.DeleteCallback() {
            public void onDeleted(int files, int hidden) {
                load(false, null, false);
                StringBuilder s = new StringBuilder("已删除 ").append(vms.size()).append(" 场");
                if (hidden > 0) {
                    s.append("（").append(hidden).append(" 场已屏蔽，设置里可恢复）");
                }
                toast(s.toString());
                if (settingsPage != null) settingsPage.refresh();
            }
        });
    }

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
        detail.open(vm);
        pushOverlay(detail);
    }

    private final DetailSheet.Listener detailListener = new DetailSheet.Listener() {
        public void onTitleChanged(RecordVm vm) {
            adapter.notifyDataSetChanged();
        }

        public void onMetaChanged(RecordVm vm) {
            adapter.notifyDataSetChanged();
            rebuildFilters();
        }

        public void onTranscribe(RecordVm vm) {
            transcribe(vm);
        }
    };

    // ------------------------------------------------------------ Overlay 栈（返回键按顺序关）

    public void pushOverlay(Overlay o) {
        if (o != null && !overlays.contains(o)) overlays.add(o);
    }

    public void removeOverlay(Overlay o) {
        overlays.remove(o);
    }

    private void transcribe(final RecordVm vm) {
        toast("转写：正在重扫外部来源…");
        final int keepPos = list.getFirstVisiblePosition();
        final View keep = list.getChildAt(0);
        final int keepTop = keep == null ? 0 : keep.getTop();
        data.load(true, null, new DataCenter.LoadCallback() {
            public void onLoaded(DataCenter.Result r) {
                applyResult(r, true, keepPos, keepTop, false);
                RecordVm updated = findVm(vm.id);
                if (updated != null) {
                    if (!updated.segs.isEmpty()) {
                        toast("已从外部来源导入 " + updated.segs.size() + " 句");
                    } else {
                        toast("外部来源里没有这场的内容（自录的录音只有声音，没有字）");
                    }
                    if (detail.isOpen() && vm.id.equals(detail.id())) detail.refresh(updated);
                } else {
                    toast("外部来源里没有这场的内容");
                }
            }
        });
    }

    private RecordVm findVm(String id) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) return all.get(i);
        }
        return null;
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
        recBtn.setRecording(true);
        recWave.setActive(true);
        recTimer.setText("00:00");
        if (!"record".equals(tab)) switchTab("record");
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
        final String stamp = pendingStamp;
        pendingStamp = null;
        pendingTitle = "";
        recTitle.setText("");
        toast("已保存 " + RecordVm.fmtDur(durSec));
        load(false, null, false);
        list.setSelection(0);
        if (settings.autoTranscribe && stamp != null) {
            ui.postDelayed(new Runnable() {
                public void run() {
                    RecordVm vm = findVm(stamp);
                    if (vm != null) transcribe(vm);
                }
            }, 400);
        }
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
        if (code == REQ_PERMS) {
            load(false, null, false);
            if (settingsPage != null) settingsPage.refresh();
        }
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
        if (settingsPage != null) settingsPage.refresh();
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
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (capturingKey) {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                capturingKey = false;
                settingsPage.setCapturing(false);
                return true;
            }
            String key = keyId(keyCode);
            String label = keyLabel(keyCode);
            settingsPage.onKeyCaptured(key, label);
            capturingKey = false;
            toast("已绑定：" + label);
            return true;
        }
        // 正在输入（标题/搜索/API Key…）时，空格与音量键都该归输入法/系统
        if (isTextFocused()) return super.onKeyDown(keyCode, event);
        if (detail != null && detail.isOpen() && matchesMarkKey(keyCode) && detail.triggerMark()) {
            return true;
        }
        if (!isTextFocused() && matchesRecordKey(keyCode)) {
            toggleRecording();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean isTextFocused() {
        View v = getCurrentFocus();
        return v instanceof EditText;
    }

    private static String keyId(int keyCode) {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) return "AudioVolumeUp";
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return "AudioVolumeDown";
        if (keyCode == android.view.KeyEvent.KEYCODE_ENTER) return "Enter";
        if (keyCode == android.view.KeyEvent.KEYCODE_SPACE) return " ";
        return android.view.KeyEvent.keyCodeToString(keyCode);
    }

    private static String keyLabel(int keyCode) {
        String id = keyId(keyCode);
        if ("AudioVolumeUp".equals(id)) return "音量上";
        if ("AudioVolumeDown".equals(id)) return "音量下";
        if ("Enter".equals(id)) return "回车";
        if (" ".equals(id)) return "空格";
        if ("KEYCODE_BACK".equals(id)) return "返回";
        return id.startsWith("KEYCODE_") ? id.substring(8) : id;
    }

    private boolean matchesMarkKey(int keyCode) {
        String k = settings.markKey;
        if (k == null || k.length() == 0) return false;
        return k.equals(keyId(keyCode));
    }

    private boolean matchesRecordKey(int keyCode) {
        String mode = settings.keyMode;
        if (mode == null) return false;
        if ("audio_up".equals(mode)) return keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP;
        if ("audio_down".equals(mode)) return keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN;
        if ("custom".equals(mode)) {
            return settings.keyBindingKey != null && settings.keyBindingKey.length() > 0
                    && settings.keyBindingKey.equals(keyId(keyCode));
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        if (capturingKey) {
            capturingKey = false;
            settingsPage.setCapturing(false);
            return;
        }
        if (!overlays.isEmpty()) {
            Overlay o = overlays.get(overlays.size() - 1);
            if (o != null && o.isOpen()) {
                o.dismiss();
                return;
            }
            overlays.remove(o);
        }
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
        try {
            data.shutdown();
        } catch (Throwable ignore) { }
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
