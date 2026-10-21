package dev.voxnote;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * 一次性迁移：从旧版 WebView 的 localStorage 里把 UI 设置搬到 {@link UiSettings}。
 *
 * 旧版设置存在 localStorage['s1rec.v5']：分类、左右滑目标、按键、转写 API。
 * 分类是用户手写的，删 WebView 就会丢，所以升级后的第一次启动要把它导出来。
 * 用 assets/legacy_export.html 读同一 file:// 源下的 localStorage，读完立刻销毁 WebView。
 *
 * 只在 legacyImported=false 时跑；无论成功与否都要落标记，避免每次冷启动多花 1–2 秒。
 */
public class LegacySettingsImporter {

    private static final String TAG = "voxnote.legacy";
    private static final long TIMEOUT_MS = 4000;

    public interface Callback {
        void onDone(boolean imported);
    }

    /** 必须传 Activity（WebView 需要窗口）；回调在主线程。 */
    public static void run(final Activity act, final UiSettings settings, final Callback cb) {
        if (settings.legacyImported()) {
            cb.onDone(false);
            return;
        }
        // 全新安装没有旧 WebView 数据目录：不用为了读 localStorage 初始化一次 WebView。
        java.io.File webviewData = new java.io.File(act.getApplicationInfo().dataDir, "app_webview");
        if (!webviewData.isDirectory()) {
            settings.markLegacyImported();
            cb.onDone(false);
            return;
        }
        final Handler ui = new Handler(Looper.getMainLooper());
        WebView web = null;
        try {
            web = new WebView(act);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setAllowFileAccess(true);
            web.setBackgroundColor(0);
            web.setVisibility(View.INVISIBLE);

            ViewGroup root = (ViewGroup) act.findViewById(android.R.id.content);
            if (root == null) {
                finish(settings, cb, false);
                return;
            }
            root.addView(web, 1, 1);

            final WebView w = web;
            final boolean[] done = new boolean[1];
            web.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public void post(final String json) {
                    ui.post(new Runnable() {
                        public void run() {
                            if (done[0]) return;
                            done[0] = true;
                            if (act.isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && act.isDestroyed())) {
                                // Activity 没了：不落标记，下次启动再迁
                                try { ((ViewGroup) w.getParent()).removeView(w); } catch (Throwable ignore) { }
                                try { w.destroy(); } catch (Throwable ignore) { }
                                return;
                            }
                            boolean imported = settings.importLegacy(json);
                            settings.markLegacyImported();
                            Log.i(TAG, imported ? "imported legacy ui settings" : "legacy settings empty");
                            try { ((ViewGroup) w.getParent()).removeView(w); } catch (Throwable ignore) { }
                            try { w.destroy(); } catch (Throwable ignore) { }
                            cb.onDone(imported);
                        }
                    });
                }
            }, "VoxLegacy");

            web.loadUrl("file:///android_asset/legacy_export.html");

            ui.postDelayed(new Runnable() {
                public void run() {
                    if (done[0]) return;
                    done[0] = true;
                    try { ((ViewGroup) w.getParent()).removeView(w); } catch (Throwable ignore) { }
                    try { w.destroy(); } catch (Throwable ignore) { }
                    if (act.isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && act.isDestroyed())) {
                        return;   // 下次启动再试，别把“没读到”当成已迁移
                    }
                    // 页面没回来（WebView 挂了/被系统禁用）：落标记，别每次启动都卡一下。
                    settings.markLegacyImported();
                    Log.w(TAG, "legacy export timed out");
                    cb.onDone(false);
                }
            }, TIMEOUT_MS);
        } catch (Throwable t) {
            Log.w(TAG, "legacy export failed: " + t);
            if (web != null) {
                try { ((ViewGroup) web.getParent()).removeView(web); } catch (Throwable ignore) { }
                try { web.destroy(); } catch (Throwable ignore) { }
            }
            finish(settings, cb, false);
        }
    }

    private static void finish(UiSettings settings, Callback cb, boolean imported) {
        settings.markLegacyImported();
        cb.onDone(imported);
    }
}
