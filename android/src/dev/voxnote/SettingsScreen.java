package dev.voxnote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 原生设置页。与旧 H5 的 view-settings 一一对应：
 * 分类（左右滑目标 / 改名 / 删除 / 新增）、按键、转写 API、存储位置（三态 + SAF）、
 * 录音开关、外部来源、关于。
 *
 * 所有改动即时落 UiSettings（SharedPreferences），需要重扫/重画时回调 HomeActivity。
 */
public class SettingsScreen {

    public interface Host {
        void onSettingsChanged();

        void onCategoryDeleted(String catId);

        void onStorageChanged();

        void onPickSaf();

        void onClearSaf();

        void onRequestStoragePerms();

        void onScanSource(String id);

        void onResetDefaults();

        void onBeginKeyCapture();

        /** 服务器同步状态一行字（ServerSync.statusLine()）。 */
        String serverStatus();

        /** 不看用户设置、直接从系统取的本机设备名（设备名输入框的留空提示用）。 */
        String autoDeviceName();

        /** 打开扫码配对（App 内相机识别控制台二维码，直接填地址与凭据）。 */
        void onScanPair();

        /** 测试服务器连通（异步，结果由宿主 toast）。 */
        void onServerTest();

        /** 立即同步一轮（推音频 + 拉转写稿）。 */
        void onSyncNow();

        /** 选服务器用的转写引擎（宿主拉 /asr-config 后弹选择器）。 */
        void onPickServerEngine();

        /** 被"删除"（屏蔽）的场次数。 */
        int ignoredCount();

        /** 恢复所有被屏蔽的场次。 */
        void onRestoreIgnored();

        /** v2 同步状态一行字（未配对 / 已连接 + 待上传/失败/冲突）。 */
        String v2Status();

        /** 打开 v2 同步中心（引擎、诊断、备份、清理）。 */
        void onOpenSyncCenter();

        /** 波形缓存占用描述，如「1.2 MB」。 */
        String cacheSummary();

        /** 清空波形缓存。 */
        void onClearWaveCache();
    }

    private final Activity act;
    private final UiSettings s;
    private final Storage storage;
    private final Host host;

    private final LinearLayout page;
    private final FrameLayout scrollHost;
    private final ScrollView root;
    private final LinearLayout content;
    private final LinearLayout tocPanel;
    private final View tocBlocker;
    private final TextView tocBtn;
    private final ArrayList<View> tocViews = new ArrayList<View>();
    private final ArrayList<String> tocTitles = new ArrayList<String>();
    private boolean tocOpen;
    private final LinearLayout catGroup;
    private final LinearLayout keyGroup;
    private final LinearLayout storageGroup;
    private final LinearLayout sourcesGroup;
    private final LinearLayout sourcesList;
    private TextView keyCaptureBtn;
    private final View keyCaptureRow;
    private final TextView keyConflict;
    private final TextView rightCatValue;
    private final TextView leftCatValue;
    private final TextView storageTarget;
    private final TextView dateDirValue;
    private final TextView safLabel;
    private final LinearLayout safRow;
    private final LinearLayout publicSubRow;
    private final LinearLayout permRow;
    private final TextView versionValue;
    private final TextView ignoredValue;
    private final TextView serverEngineValue;

    private boolean capturing;

    public SettingsScreen(Activity act, UiSettings s, Storage storage, Host host) {
        this.act = act;
        this.s = s;
        this.storage = storage;
        this.host = host;

        // ---------------- 页面骨架：固定顶栏（标题 + 右上角目录入口）+ 滚动区 + 目录浮层
        page = Ui.col(act);
        page.setBackgroundColor(Ui.BG);
        LinearLayout head = Ui.row(act);
        Ui.pad(head, 14, 10, 12, 8);
        head.setBackgroundColor(Ui.BG);
        head.addView(Ui.text(act, "设置", 15f, Ui.TEXT, true),
                Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tocBtn = Ui.smallButton(act, "☰ 目录");
        tocBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggleToc();
            }
        });
        page.addView(head, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(Ui.divider(act));

        scrollHost = new FrameLayout(act);
        root = new ScrollView(act);
        root.setBackgroundColor(Ui.BG);
        root.setVerticalScrollBarEnabled(false);
        root.setFillViewport(true);
        content = Ui.col(act);
        Ui.pad(content, 10, 10, 10, 24);
        root.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scrollHost.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tocBlocker = new View(act);
        tocBlocker.setBackgroundColor(0x00000000);
        tocBlocker.setVisibility(View.GONE);
        tocBlocker.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeToc();
            }
        });
        scrollHost.addView(tocBlocker, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tocPanel = Ui.col(act);
        tocPanel.setBackground(Ui.rounded(12, Ui.SURFACE, Ui.LINE2, 1));
        Ui.pad(tocPanel, 5, 7, 5, 7);
        tocPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams tocp = new FrameLayout.LayoutParams(
                Ui.dp(200), ViewGroup.LayoutParams.WRAP_CONTENT);
        tocp.gravity = Gravity.END | Gravity.TOP;
        tocp.topMargin = Ui.dp(4);
        tocp.rightMargin = Ui.dp(10);
        scrollHost.addView(tocPanel, tocp);

        page.addView(scrollHost, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---------------- 分类
        catGroup = Ui.group(act, "分类");
        rightCatValue = valueButton();
        leftCatValue = valueButton();
        catGroup.addView(selectorRow("右滑分类", rightCatValue, new Runnable() {
            public void run() {
                pickCategory(0);
            }
        }));
        catGroup.addView(Ui.divider(act));
        catGroup.addView(selectorRow("左滑分类", leftCatValue, new Runnable() {
            public void run() {
                pickCategory(1);
            }
        }));
        catGroup.addView(Ui.divider(act));
        catGroup.addView(catListBox());
        // catGroup 统一在构造末尾排版

        // ---------------- 按键（所有手机通用，可自定义）
        keyGroup = Ui.group(act, "快捷键");
        keyGroup.addView(Ui.text(act, "所有手机通用：可绑定音量键、蓝牙耳机/外接键盘按键等（部分系统键可能被系统占用）",
                10.5f, Ui.FAINT), Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 6));
        TextView startKeyValue = valueButton();
        keyGroup.addView(selectorRow("开始录音", startKeyValue, new Runnable() {
            public void run() {
                pickStartKey();
            }
        }));
        keyCaptureRow = buildCaptureRow();
        keyGroup.addView(Ui.divider(act));
        keyGroup.addView(keyCaptureRow);
        keyConflict = Ui.text(act, "与标记键相同：详情打开时优先用于标记", 10.5f, Ui.DANGER);
        keyConflict.setVisibility(View.GONE);
        keyGroup.addView(keyConflict, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 2, 0, 2, 8));
        keyGroup.addView(Ui.divider(act));
        TextView markKeyValue = valueButton();
        keyGroup.addView(selectorRow("播放时标记", markKeyValue, new Runnable() {
            public void run() {
                pickMarkKey();
            }
        }));
        this.startKeyValue = startKeyValue;
        this.markKeyValue = markKeyValue;
        // keyGroup 统一在构造末尾排版

        // ---------------- 转写 API
        LinearLayout apiGroup = Ui.group(act, "转写 API");
        TextView providerValue = valueButton();
        apiGroup.addView(selectorRow("服务类型", providerValue, new Runnable() {
            public void run() {
                pickProvider();
            }
        }));
        this.providerValue = providerValue;
        apiGroup.addView(Ui.divider(act));
        apiGroup.addView(inputBlock("接口地址", s.apiUrl, InputType.TYPE_TEXT_VARIATION_URI, true, new OnText() {
            public void onText(String v) {
                s.apiUrl = v;
                s.save();
            }
        }));
        apiGroup.addView(Ui.divider(act));
        apiGroup.addView(inputBlock("API Key", s.apiKey,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, false, new OnText() {
                    public void onText(String v) {
                        s.apiKey = v;
                        s.save();
                    }
                }));
        apiGroup.addView(Ui.divider(act));
        LinearLayout modelRow = Ui.row(act);
        Ui.pad(modelRow, 0, 8, 0, 8);
        EditText model = field(s.model, false);
        model.setHint("whisper-1");
        model.addTextChangedListener(watcher(new OnText() {
            public void onText(String v) {
                s.model = v;
                s.save();
            }
        }));
        LinearLayout modelCol = Ui.col(act);
        modelCol.addView(Ui.text(act, "模型 / 语言", 12.5f, Ui.TEXT));
        LinearLayout modelInline = Ui.row(act);
        modelInline.addView(model, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView langValue = valueButton();
        modelInline.addView(langValue, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 6, 0, 0, 0));
        modelCol.addView(modelInline, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));
        modelRow.addView(modelCol, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        apiGroup.addView(modelRow);
        this.langValue = langValue;
        langValue.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickLang();
            }
        });
        final TextView test = Ui.button(act, "测试连接", 12.5f, false);
        test.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                testTranscribeApi(test);
            }
        });
        apiGroup.addView(test, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 10));
        // apiGroup 统一在构造末尾排版

        // ---------------- 服务器与同步（v2）
        LinearLayout srvGroup = Ui.group(act, "服务器与同步");
        serverEngineValue = valueButton();   // 旧 v1 引擎行已移除，占位避免旧刷新方法空指针
        LinearLayout srvStatusRow = Ui.row(act);
        Ui.pad(srvStatusRow, 0, 8, 0, 8);
        srvStatusRow.addView(Ui.text(act, "状态", 12.5f, Ui.TEXT), Ui.lp(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        serverStatusValue = Ui.text(act, "", 11.5f, Ui.FAINT);
        serverStatusValue.setGravity(Gravity.END);
        srvStatusRow.addView(serverStatusValue, Ui.lp(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        srvGroup.addView(srvStatusRow);
        srvGroup.addView(Ui.text(act, "配对后：录音自动上传，转写引擎在控制台/同步中心配置", 10.5f, Ui.FAINT),
                Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8));

        LinearLayout srvBtns = Ui.row(act);
        Ui.pad(srvBtns, 0, 4, 0, 8);
        TextView scanBtn = Ui.button(act, "扫码配对", 12.5f, false);
        scanBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                host.onScanPair();
            }
        });
        srvBtns.addView(scanBtn, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView centerBtn = Ui.button(act, "同步中心", 12.5f, false);
        centerBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                host.onOpenSyncCenter();
            }
        });
        srvBtns.addView(centerBtn, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8, 0, 0, 0));
        srvGroup.addView(srvBtns);

        LinearLayout srvBtns2 = Ui.row(act);
        Ui.pad(srvBtns2, 0, 0, 0, 8);
        TextView srvSync = Ui.button(act, "立即同步", 12.5f, false);
        srvSync.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                host.onSyncNow();
            }
        });
        srvBtns2.addView(srvSync, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView srvTest = Ui.button(act, "测试连接", 12.5f, false);
        srvTest.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                host.onServerTest();
            }
        });
        srvBtns2.addView(srvTest, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8, 0, 0, 0));
        srvGroup.addView(srvBtns2);

        final LinearLayout srvAdvanced = Ui.col(act);
        srvAdvanced.setVisibility(View.GONE);
        srvAdvanced.addView(Ui.divider(act));
        srvAdvanced.addView(inputBlock("服务器地址", s.serverUrl,
                InputType.TYPE_TEXT_VARIATION_URI, true, new OnText() {
                    public void onText(String v) {
                        s.serverUrl = v;
                        s.save();
                    }
                }));
        srvAdvanced.addView(Ui.divider(act));
        srvAdvanced.addView(inputBlock("配对令牌", s.serverToken,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, true,
                "扫码自动填入；也可手填", new OnText() {
                    public void onText(String v) {
                        s.serverToken = v;
                        s.save();
                    }
                }));
        srvAdvanced.addView(Ui.divider(act));
        srvAdvanced.addView(inputBlock("本机设备名", s.deviceName, InputType.TYPE_CLASS_TEXT, false,
                "留空自动取「" + host.autoDeviceName() + "」", new OnText() {
                    public void onText(String v) {
                        s.deviceName = v;
                        s.save();
                    }
                }));
        TextView advBtn = Ui.smallButton(act, "高级：地址 / 令牌 / 设备名");
        advBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                srvAdvanced.setVisibility(
                        srvAdvanced.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });
        srvGroup.addView(advBtn, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 4));
        srvGroup.addView(srvAdvanced);
        // srvGroup 统一在构造末尾排版

        // ---------------- 录音存储（高级设置）
        storageGroup = Ui.group(act, "录音存储");
        TextView modeValue = valueButton();
        storageGroup.addView(selectorRow("录音放在", modeValue, new Runnable() {
            public void run() {
                pickStorageMode();
            }
        }));
        this.storageModeValue = modeValue;

        publicSubRow = Ui.col(act);
        Ui.pad(publicSubRow, 0, 8, 0, 8);
        publicSubRow.addView(Ui.text(act, "公共子目录", 12.5f, Ui.TEXT));
        final EditText sub = field(storage.publicSub(), true);
        sub.setHint(Storage.DEFAULT_SUB);
        sub.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) hostApplyStorage("public", sub.getText().toString().trim());
            }
        });
        publicSubRow.addView(sub, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));
        storageGroup.addView(Ui.divider(act));
        storageGroup.addView(publicSubRow);

        safRow = Ui.row(act);
        Ui.pad(safRow, 0, 8, 0, 8);
        LinearLayout safMain = Ui.col(act);
        safMain.addView(Ui.text(act, "自定义目录", 12.5f, Ui.TEXT));
        safLabel = Ui.text(act, "未选择", 10.5f, Ui.FAINT);
        safLabel.setMaxLines(2);
        safMain.addView(safLabel);
        safRow.addView(safMain, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout safBtns = Ui.row(act);
        TextView pick = Ui.smallButton(act, "选择");
        pick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                host.onPickSaf();
            }
        });
        TextView clear = Ui.smallButton(act, "清除");
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                host.onClearSaf();
            }
        });
        safBtns.addView(pick);
        safBtns.addView(clear, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 6, 0, 0, 0));
        safRow.addView(safBtns);
        storageGroup.addView(Ui.divider(act));
        storageGroup.addView(safRow);

        LinearLayout targetRow = Ui.col(act);
        Ui.pad(targetRow, 0, 8, 0, 8);
        targetRow.addView(Ui.text(act, "实际写入", 12.5f, Ui.TEXT));
        storageTarget = Ui.text(act, "", 10.5f, Ui.FAINT);
        storageTarget.setTextIsSelectable(true);
        targetRow.addView(storageTarget, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 2, 0, 0));
        storageGroup.addView(Ui.divider(act));
        storageGroup.addView(targetRow);

        permRow = Ui.row(act);
        Ui.pad(permRow, 0, 8, 0, 8);
        LinearLayout permMain = Ui.col(act);
        permMain.addView(Ui.text(act, "还没有存储权限", 12.5f, Ui.TEXT));
        permMain.addView(Ui.text(act, "此时写进应用私有目录（卸载即删）", 10.5f, Ui.FAINT));
        permRow.addView(permMain, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView grant = Ui.smallButton(act, "授予");
        grant.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                host.onRequestStoragePerms();
            }
        });
        permRow.addView(grant);
        storageGroup.addView(Ui.divider(act));
        storageGroup.addView(permRow);

        LinearLayout ddRow = Ui.row(act);
        Ui.pad(ddRow, 0, 8, 0, 8);
        ddRow.addView(Ui.text(act, "日期文件夹", 12.5f, Ui.TEXT), Ui.lp(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        dateDirValue = Ui.text(act, s == null ? "" : storage.dateDir(), 11.5f, Ui.FAINT);
        ddRow.addView(dateDirValue);
        storageGroup.addView(ddRow);
        // storageGroup 统一在构造末尾排版

        // ---------------- 录音
        LinearLayout recGroup = Ui.group(act, "录音");
        recGroup.addView(toggleRow("录完自动转写", s.autoTranscribe, new ToggleListener() {
            public void onChanged(boolean v) {
                s.autoTranscribe = v;
                s.save();
            }
        }));
        recGroup.addView(Ui.divider(act));
        recGroup.addView(toggleRow("列表显示摘要", s.showFirstLine, new ToggleListener() {
            public void onChanged(boolean v) {
                s.showFirstLine = v;
                s.save();
                host.onSettingsChanged();
            }
        }));
        // recGroup 统一在构造末尾排版

        // ---------------- 数据（删除进来的"屏蔽"在这里撤销）
        LinearLayout dataGroup = Ui.group(act, "数据");
        ignoredValue = valueButton();
        dataGroup.addView(selectorRow("已屏蔽的场次", ignoredValue, new Runnable() {
            public void run() {
                host.onRestoreIgnored();
            }
        }));
        dataGroup.addView(Ui.divider(act));
        LinearLayout cacheRow = Ui.row(act);
        Ui.pad(cacheRow, 0, 8, 0, 8);
        cacheRow.addView(Ui.text(act, "波形缓存", 12.5f, Ui.TEXT), Ui.lp(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cacheValue = Ui.text(act, "", 11.5f, Ui.FAINT);
        cacheRow.addView(cacheValue);
        TextView clearCache = Ui.smallButton(act, "清理");
        clearCache.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                host.onClearWaveCache();
            }
        });
        cacheRow.addView(clearCache, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8, 0, 0, 0));
        dataGroup.addView(cacheRow);
        dataGroup.addView(Ui.text(act, "波形缓存只用于播放器显示，清理不会删除录音或转写", 10.5f, Ui.FAINT),
                Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 6));
        // dataGroup 统一在构造末尾排版

        // ---------------- 外部来源
        sourcesGroup = Ui.group(act, "外部来源");
        sourcesList = Ui.col(act);
        sourcesGroup.addView(sourcesList);
        // sourcesGroup 统一在构造末尾排版

        // ---------------- 关于
        LinearLayout about = Ui.group(act, "关于");
        LinearLayout verRow = Ui.row(act);
        Ui.pad(verRow, 0, 8, 0, 8);
        verRow.addView(Ui.text(act, "版本", 12.5f, Ui.TEXT), Ui.lp(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        versionValue = Ui.text(act, appVersion(), 11.5f, Ui.FAINT);
        verRow.addView(versionValue);
        about.addView(verRow);
        about.addView(Ui.divider(act));
        LinearLayout dirRow = Ui.row(act);
        Ui.pad(dirRow, 0, 8, 0, 8);
        dirRow.addView(Ui.text(act, "数据目录", 12.5f, Ui.TEXT), Ui.lp(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView dirValue = Ui.text(act, storage.describe(), 11.5f, Ui.FAINT);
        dirValue.setMaxWidth((int) (Ui.screenWidth() * 0.6f));
        dirValue.setGravity(Gravity.END);
        dirRow.addView(dirValue);
        about.addView(dirRow);
        TextView reset = Ui.button(act, "恢复默认设置", 12.5f, false);
        reset.setTextColor(Ui.DANGER);
        reset.setBackground(Ui.pressable(Ui.rounded(8, Ui.SURFACE2, Ui.DANGER_LINE, 1), 0x22FFFFFF));
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new AlertDialog.Builder(act)
                        .setTitle("恢复默认设置？")
                        .setMessage("会重置录音/同步/快捷键等偏好，不会删除录音与转写。")
                        .setPositiveButton("恢复默认", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                host.onResetDefaults();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        about.addView(reset, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 10));
        // about 统一在构造末尾排版

        // ---------------- 按新信息架构排版
        content.addView(srvGroup, groupLp());
        content.addView(recGroup, groupLp());
        content.addView(storageGroup, groupLp());
        content.addView(keyGroup, groupLp());
        content.addView(catGroup, groupLp());
        content.addView(dataGroup, groupLp());
        content.addView(about, groupLp());

        // ---------------- 目录注册表：顺序 = 页面顺序；打开目录时按可见性过滤
        registerSection("分类", catGroup);
        registerSection("按键", keyGroup);
        // V1 砍掉：转写 API 配置、外部来源（厂商日志导入）入口隐藏，代码保留
        apiGroup.setVisibility(View.GONE);
        sourcesGroup.setVisibility(View.GONE);
        registerSection("服务器", srvGroup);
        registerSection("存储位置", storageGroup);
        registerSection("录音", recGroup);
        registerSection("数据", dataGroup);
        registerSection("关于", about);

        refresh();
    }

    private TextView startKeyValue;
    private TextView markKeyValue;
    private TextView providerValue;
    private TextView langValue;
    private TextView cacheValue;
    private TextView storageModeValue;
    private TextView serverStatusValue;

    public View view() {
        return page;
    }

    // ------------------------------------------------------------ 刷新

    public void refresh() {
        rightCatValue.setText(s.catName(s.swipeRightCat));
        leftCatValue.setText(s.catName(s.swipeLeftCat));
        startKeyValue.setText(startKeyLabel());
        markKeyValue.setText(markKeyLabel());
        keyCaptureRow.setVisibility("custom".equals(s.keyMode) ? View.VISIBLE : View.GONE);
        keyCaptureBtn.setText(s.keyBindingLabel.length() > 0 ? s.keyBindingLabel : "按下按键");
        keyCaptureBtn.setBackground(Ui.pressable(Ui.rounded(8, Ui.SURFACE2,
                capturing ? Ui.WHITE : Ui.LINE2, 1), 0x22FFFFFF));
        boolean conflict = s.keyBindingKey.length() > 0
                && s.keyBindingKey.equals(s.markKey) && !"none".equals(s.keyMode);
        keyConflict.setVisibility(conflict ? View.VISIBLE : View.GONE);
        providerValue.setText(providerLabel(s.provider));
        langValue.setText(langLabel(s.lang));
        serverStatusValue.setText(host.v2Status());
        cacheValue.setText(host.cacheSummary());
        storageModeValue.setText(storageModeLabel(storage.mode()));
        safLabel.setText(storage.safTree().length() > 0 ? storage.safTree() : "未选择");
        storageTarget.setText(storage.describe());
        dateDirValue.setText(storage.dateDir());
        publicSubRow.setVisibility(Storage.MODE_PUBLIC.equals(storage.mode()) ? View.VISIBLE : View.GONE);
        safRow.setVisibility(Storage.MODE_SAF.equals(storage.mode()) ? View.VISIBLE : View.GONE);
        permRow.setVisibility(storage.hasWritePermission() ? View.GONE : View.VISIBLE);
        ignoredValue.setText(host.ignoredCount() + " 场");
        rebuildCats();
    }

    public void setCapturing(boolean c) {
        capturing = c;
        keyCaptureBtn.setText(c ? "请按键…" : (s.keyBindingLabel.length() > 0 ? s.keyBindingLabel : "按下按键"));
        keyCaptureBtn.setBackground(Ui.pressable(Ui.rounded(8, Ui.SURFACE2,
                c ? Ui.WHITE : Ui.LINE2, 1), 0x22FFFFFF));
    }

    /** 服务器转写引擎行回显：宿主懒加载 /asr-config 或保存成功后回填（空 = 提示点开）。 */
    public void setServerEngineLabel(String label) {
        serverEngineValue.setText(
                label == null || label.trim().length() == 0 ? "点开选择" : label);
    }

    public void refreshSources(JSONArray sources) {
        sourcesList.removeAllViews();
        boolean any = false;
        if (sources != null) {
            for (int i = 0; i < sources.length(); i++) {
                JSONObject o = sources.optJSONObject(i);
                if (o == null || !o.optBoolean("available", true)) continue;
                any = true;
                sourcesList.addView(sourceRow(o));
            }
        }
        sourcesGroup.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    private View sourceRow(final JSONObject o) {
        LinearLayout box = Ui.col(act);
        Ui.pad(box, 0, 8, 0, 10);
        box.addView(Ui.text(act, o.optString("label", o.optString("id", "")), 12.5f, Ui.TEXT));
        String dir = o.optString("dir", "");
        if (dir.length() > 0) box.addView(Ui.text(act, dir, 10.5f, Ui.FAINT));
        long lastRun = o.optLong("lastRunAt", 0);
        String err = o.optString("error", "");
        String line;
        if (err.length() > 0) {
            line = "⚠️ " + err;
        } else if (lastRun > 0) {
            line = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US)
                    .format(new java.util.Date(lastRun)) + " 扫描"
                    + (o.optBoolean("wrote", false) ? " · 已导出" : "")
                    + (o.optInt("new", 0) > 0 ? " · 新增 " + o.optInt("new") + " 场" : "");
        } else {
            line = "尚未扫描";
        }
        box.addView(Ui.text(act, line, 10.5f, Ui.FAINT));
        LinearLayout foot = Ui.row(act);
        foot.addView(Ui.text(act, o.optInt("sessions", 0) + " 场 / "
                + o.optInt("sentences", 0) + " 句", 11.5f, Ui.FAINT),
                Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView scan = Ui.smallButton(act, "扫描并导入");
        scan.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                v.setEnabled(false);
                host.onScanSource(o.optString("id", ""));
            }
        });
        foot.addView(scan);
        box.addView(foot, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
        return box;
    }

    // ------------------------------------------------------------ 分类

    private View catListBox() {
        LinearLayout box = Ui.col(act);
        catRowsBox = box;
        rebuildCats();
        return box;
    }

    private LinearLayout catRowsBox;

    private void rebuildCats() {
        if (catRowsBox == null) return;
        catRowsBox.removeAllViews();
        for (int i = 0; i < s.categories.size(); i++) {
            final UiSettings.Cat c = s.categories.get(i);
            LinearLayout row = Ui.row(act);
            Ui.pad(row, 2, 2, 2, 2);
            View dot = new View(act);
            dot.setBackground(Ui.rounded(3, Ui.LINE2));
            row.addView(dot, Ui.lp(Ui.dp(6), Ui.dp(6)));
            final EditText name = new EditText(act);
            name.setText(c.name);
            name.setTextColor(Ui.TEXT);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            name.setBackground(null);
            name.setSingleLine(true);
            name.setPadding(Ui.dp(8), Ui.dp(4), Ui.dp(4), Ui.dp(4));
            name.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence x, int a, int b, int d) { }

                public void onTextChanged(CharSequence x, int a, int b, int d) { }

                public void afterTextChanged(Editable e) {
                    String v = e.toString().trim();
                    c.name = v.length() == 0 ? "未命名" : v;
                    s.save();
                    host.onSettingsChanged();
                    rightCatValue.setText(s.catName(s.swipeRightCat));
                    leftCatValue.setText(s.catName(s.swipeLeftCat));
                }
            });
            row.addView(name, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView del = Ui.text(act, "✕", 14f, Ui.FAINT);
            del.setGravity(Gravity.CENTER);
            Ui.pad(del, 8, 4, 8, 4);
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    deleteCategory(c.id);
                }
            });
            row.addView(del);
            catRowsBox.addView(row);
        }
        TextView add = Ui.button(act, "新增分类", 12.5f, false);
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                addCategory();
            }
        });
        catRowsBox.addView(add, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 8));
    }

    private void addCategory() {
        if (s.categories.size() >= 8) {
            toast("最多 8 个分类");
            return;
        }
        int n = 1;
        while (hasCatName("分类" + n)) n++;
        String id = "c" + System.currentTimeMillis();
        s.categories.add(new UiSettings.Cat(id, "分类" + n));
        s.save();
        rebuildCats();
        host.onSettingsChanged();
    }

    private boolean hasCatName(String name) {
        for (int i = 0; i < s.categories.size(); i++) {
            if (name.equals(s.categories.get(i).name)) return true;
        }
        return false;
    }

    private void deleteCategory(String id) {
        if (s.categories.size() <= 1) {
            toast("至少保留一个分类");
            return;
        }
        UiSettings.Cat cat = s.find(id);
        if (cat == null) return;
        s.categories.remove(cat);
        if (id.equals(s.swipeRightCat)) s.swipeRightCat = s.categories.get(0).id;
        if (id.equals(s.swipeLeftCat)) {
            s.swipeLeftCat = s.categories.get(Math.min(1, s.categories.size() - 1)).id;
        }
        s.save();
        rebuildCats();
        host.onSettingsChanged();
        host.onCategoryDeleted(id);
    }

    private void pickCategory(final int which) {
        String[] labels = new String[s.categories.size()];
        int selected = -1;
        String cur = which == 0 ? s.swipeRightCat : s.swipeLeftCat;
        for (int i = 0; i < s.categories.size(); i++) {
            labels[i] = s.categories.get(i).name;
            if (s.categories.get(i).id.equals(cur)) selected = i;
        }
        Picker.show(act, pickerHost(), "选择分类", labels, selected, new Picker.OnPick() {
            public void onPick(int index) {
                String id = s.categories.get(index).id;
                if (which == 0) s.swipeRightCat = id;
                else s.swipeLeftCat = id;
                s.save();
                refresh();
                host.onSettingsChanged();
            }
        });
    }

    // ------------------------------------------------------------ 按键

    private String startKeyLabel() {
        if ("audio_up".equals(s.keyMode)) return "音量上";
        if ("audio_down".equals(s.keyMode)) return "音量下";
        if ("custom".equals(s.keyMode)) return "自定义";
        return "无";
    }

    private String markKeyLabel() {
        String k = s.markKey;
        if (" ".equals(k)) return "空格";
        if ("Enter".equals(k)) return "回车";
        if ("AudioVolumeUp".equals(k)) return "音量上";
        if ("AudioVolumeDown".equals(k)) return "音量下";
        return k;
    }

    private void pickStartKey() {
        final String[] labels = {"无", "音量上", "音量下", "自定义"};
        int selected = "audio_up".equals(s.keyMode) ? 1
                : "audio_down".equals(s.keyMode) ? 2 : "custom".equals(s.keyMode) ? 3 : 0;
        Picker.show(act, pickerHost(), "开始录音按键", labels, selected, new Picker.OnPick() {
            public void onPick(int index) {
                if (index == 0) {
                    s.keyMode = "none";
                    s.keyBindingKey = "";
                    s.keyBindingLabel = "";
                } else if (index == 1) {
                    s.keyMode = "audio_up";
                    s.keyBindingKey = "AudioVolumeUp";
                    s.keyBindingLabel = "音量上";
                } else if (index == 2) {
                    s.keyMode = "audio_down";
                    s.keyBindingKey = "AudioVolumeDown";
                    s.keyBindingLabel = "音量下";
                } else {
                    s.keyMode = "custom";
                    if ("AudioVolumeUp".equals(s.keyBindingKey) || "AudioVolumeDown".equals(s.keyBindingKey)) {
                        s.keyBindingKey = "";
                        s.keyBindingLabel = "";
                    }
                    host.onBeginKeyCapture();
                }
                s.save();
                refresh();
            }
        });
    }

    private void pickMarkKey() {
        final String[] labels = {"空格", "回车", "音量上", "音量下"};
        final String[] values = {" ", "Enter", "AudioVolumeUp", "AudioVolumeDown"};
        int selected = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(s.markKey)) selected = i;
        Picker.show(act, pickerHost(), "播放时标记按键", labels, selected, new Picker.OnPick() {
            public void onPick(int index) {
                s.markKey = values[index];
                s.save();
                refresh();
            }
        });
    }

    private View buildCaptureRow() {
        LinearLayout row = Ui.row(act);
        Ui.pad(row, 0, 8, 0, 8);
        row.addView(Ui.text(act, "自定义按键", 12.5f, Ui.TEXT), Ui.lp(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        keyCaptureBtn = Ui.smallButton(act, "按下按键");
        keyCaptureBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                host.onBeginKeyCapture();
            }
        });
        row.addView(keyCaptureBtn);
        return row;
    }

    public void onKeyCaptured(String key, String label) {
        s.keyBindingKey = key;
        s.keyBindingLabel = label;
        s.keyMode = "custom";
        s.save();
        capturing = false;
        refresh();
    }

    // ------------------------------------------------------------ 转写 / 语言 / 存储

    private String providerLabel(String p) {
        if ("ws".equals(p)) return "WebSocket 流式";
        if ("local".equals(p)) return "本地模型";
        return "REST 文件";
    }

    private void pickProvider() {
        final String[] labels = {"REST 文件", "WebSocket 流式", "本地模型"};
        final String[] values = {"rest", "ws", "local"};
        int selected = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(s.provider)) selected = i;
        Picker.show(act, pickerHost(), "服务类型", labels, selected, new Picker.OnPick() {
            public void onPick(int index) {
                s.provider = values[index];
                s.save();
                refresh();
            }
        });
    }

    private String langLabel(String l) {
        if ("en".equals(l)) return "English";
        if ("auto".equals(l)) return "自动";
        return "中文";
    }

    private void pickLang() {
        final String[] labels = {"中文", "English", "自动"};
        final String[] values = {"zh", "en", "auto"};
        int selected = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(s.lang)) selected = i;
        Picker.show(act, pickerHost(), "识别语言", labels, selected, new Picker.OnPick() {
            public void onPick(int index) {
                s.lang = values[index];
                s.save();
                refresh();
            }
        });
    }

    private String storageModeLabel(String m) {
        if (Storage.MODE_SAF.equals(m)) return "自定义目录";
        if (Storage.MODE_PRIVATE.equals(m)) return "应用私有";
        return "公共目录";
    }

    private void pickStorageMode() {
        final String[] labels = {"公共目录", "自定义目录", "应用私有"};
        final String[] values = {Storage.MODE_PUBLIC, Storage.MODE_SAF, Storage.MODE_PRIVATE};
        int selected = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(storage.mode())) selected = i;
        Picker.show(act, pickerHost(), "录音放在", labels, selected, new Picker.OnPick() {
            public void onPick(int index) {
                hostApplyStorage(values[index], null);
            }
        });
    }

    private void hostApplyStorage(String mode, String sub) {
        storage.setMode(mode);
        if (sub != null && sub.length() > 0) storage.setPublicSub(sub);
        s.save();
        host.onStorageChanged();
        refresh();
    }

    // ------------------------------------------------------------ 目录导航

    private void registerSection(String title, View group) {
        tocTitles.add(title);
        tocViews.add(group);
    }

    private void toggleToc() {
        if (tocOpen) closeToc();
        else openToc();
    }

    /** 目录项在每次打开时按当前可见分组重建（如「外部来源」无可用来源时整组隐藏）。 */
    private void openToc() {
        tocPanel.removeAllViews();
        TextView cap = Ui.text(act, "跳转到", 10.5f, Ui.FAINT);
        Ui.pad(cap, 6, 2, 6, 4);
        tocPanel.addView(cap, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        int added = 0;
        for (int i = 0; i < tocViews.size(); i++) {
            final View group = tocViews.get(i);
            if (group.getVisibility() != View.VISIBLE) continue;
            added++;
            final int idx = i;
            TextView item = Ui.text(act, tocTitles.get(i), 12.5f, Ui.TEXT);
            item.setSingleLine(true);
            item.setEllipsize(android.text.TextUtils.TruncateAt.END);
            item.setBackground(Ui.pressable(Ui.rounded(9, Ui.SURFACE2, Ui.LINE, 1), 0x22FFFFFF));
            Ui.pad(item, 12, 11, 12, 11);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ip.bottomMargin = Ui.dp(5);
            item.setLayoutParams(ip);
            item.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    closeToc();
                    int top = Math.max(0, group.getTop() - Ui.dp(6));
                    root.smoothScrollTo(0, top);
                }
            });
            tocPanel.addView(item);
        }
        if (added == 0) {
            TextView none = Ui.text(act, "没有可跳转的分组", 11.5f, Ui.FAINT);
            Ui.pad(none, 6, 2, 6, 6);
            tocPanel.addView(none);
        }
        tocBlocker.setVisibility(View.VISIBLE);
        tocPanel.setVisibility(View.VISIBLE);
        tocPanel.setAlpha(0f);
        tocPanel.setTranslationY(-Ui.dp(8));
        tocPanel.animate().alpha(1f).translationY(0f).setDuration(150)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        tocOpen = true;
    }

    private void closeToc() {
        if (!tocOpen) return;
        tocOpen = false;
        tocBlocker.setVisibility(View.GONE);
        tocPanel.animate().alpha(0f).translationY(-Ui.dp(8)).setDuration(120)
                .withEndAction(new Runnable() {
                    public void run() {
                        tocPanel.setVisibility(View.GONE);
                        tocPanel.setAlpha(1f);
                        tocPanel.setTranslationY(0f);
                    }
                }).start();
    }

    // ------------------------------------------------------------ 小工具

    private interface OnText {
        void onText(String v);
    }

    private interface ToggleListener {
        void onChanged(boolean v);
    }

    private TextWatcher watcher(final OnText cb) {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            public void onTextChanged(CharSequence s, int a, int b, int c) { }

            public void afterTextChanged(Editable e) {
                cb.onText(e.toString().trim());
            }
        };
    }

    private View inputBlock(String label, String value, int inputType, boolean mono, final OnText cb) {
        return inputBlock(label, value, inputType, mono, null, cb);
    }

    private View inputBlock(String label, String value, int inputType, boolean mono,
                            String hint, final OnText cb) {
        LinearLayout box = Ui.col(act);
        Ui.pad(box, 0, 8, 0, 8);
        box.addView(Ui.text(act, label, 12.5f, Ui.TEXT));
        EditText f = field(value, mono);
        f.setInputType(inputType);
        f.setHint(hint == null || hint.length() == 0 ? label : hint);
        f.addTextChangedListener(watcher(cb));
        box.addView(f, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));
        return box;
    }

    private EditText field(String value, boolean mono) {
        EditText e = new EditText(act);
        e.setText(value == null ? "" : value);
        e.setTextColor(Ui.TEXT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, mono ? 11.5f : 12.5f);
        e.setBackground(Ui.rounded(8, Ui.SURFACE2, Ui.LINE, 1));
        Ui.pad(e, 9, 7, 9, 7);
        e.setSingleLine(true);
        if (mono) e.setTypeface(Typeface.MONOSPACE);
        return e;
    }

    private LinearLayout selectorRow(String label, TextView value, final Runnable click) {
        LinearLayout row = Ui.row(act);
        Ui.pad(row, 0, 8, 0, 8);
        row.addView(Ui.text(act, label, 12.5f, Ui.TEXT), Ui.lp(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                click.run();
            }
        });
        return row;
    }

    private View toggleRow(String label, boolean checked, final ToggleListener cb) {
        LinearLayout row = Ui.row(act);
        Ui.pad(row, 0, 8, 0, 8);
        row.addView(Ui.text(act, label, 12.5f, Ui.TEXT), Ui.lp(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ToggleView t = new ToggleView(act);
        t.setChecked(checked, false);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ToggleView tv = (ToggleView) v;
                tv.setChecked(!tv.isChecked(), true);
                cb.onChanged(tv.isChecked());
            }
        });
        row.addView(t);
        return row;
    }

    private TextView valueButton() {
        TextView t = Ui.text(act, "", 11.5f, Ui.FAINT);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        t.setMaxWidth(Ui.dp(200));
        t.setBackground(Ui.pressable(Ui.rounded(7, 0x00000000, Ui.LINE, 1), 0x22FFFFFF));
        Ui.pad(t, 8, 4, 8, 4);
        return t;
    }

    private LinearLayout.LayoutParams groupLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = Ui.dp(9);
        return p;
    }

    private String appVersion() {
        try {
            return "v" + act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "v2.0";
        }
    }

    private Picker.Host pickerHost() {
        if (host instanceof Picker.Host) return (Picker.Host) host;
        return null;
    }

    private void toast(String s) {
        android.widget.Toast.makeText(act.getApplicationContext(), s, android.widget.Toast.LENGTH_SHORT).show();
    }

    /** 转写 API「测试连接」：真实探测配置的接口地址，拿不到任何响应才算失败。 */
    private void testTranscribeApi(final TextView btn) {
        String url = s.apiUrl == null ? "" : s.apiUrl.trim();
        if (url.length() == 0) {
            toast("先填接口地址");
            return;
        }
        btn.setEnabled(false);
        btn.setAlpha(0.5f);
        btn.setText("测试中…");
        NetProbe.probe(url, s.apiKey, new NetProbe.Callback() {
            public void onDone(String err, int code, long ms) {
                btn.setEnabled(true);
                btn.setAlpha(1f);
                btn.setText("测试连接");
                if (act.isFinishing() || act.isDestroyed()) return;
                if (err != null) toast("连接失败：" + err);
                else if (code >= 400) toast("地址可达 · HTTP " + code + " · " + ms + "ms（服务有响应，请核对路径）");
                else toast("连接成功 · HTTP " + code + " · " + ms + "ms");
            }
        });
    }
}
