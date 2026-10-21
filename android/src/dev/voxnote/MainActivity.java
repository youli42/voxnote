package dev.voxnote;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 整个 App 只有一个 Activity：一个全屏 WebView 承载 assets/index.html。
 *
 * 分工：
 *   界面、手势、滚动、动画在 H5 里（就是原型那份 HTML，改成读真实数据）；
 *   文件扫描、音频播放、麦克风录音在 Java 侧，通过 window.voxnote 桥接。
 *
 * 这样设备上得到的观感与原型一致，而"读 /sdcard"这类硬需求仍然由原生完成。
 */
public class MainActivity extends Activity {

    private static final int REQ_PERMS = 7;

    private WebView web;
    private Bridge bridge;
    private long lastBackAt;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            getWindow().setStatusBarColor(Color.parseColor("#07080a"));
        } catch (Throwable ignore) { }

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0e0f12"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient());
        try {
            WebView.setWebContentsDebuggingEnabled(true);
        } catch (Throwable ignore) { }

        bridge = new Bridge(this);
        web.addJavascriptInterface(bridge, "voxnote");
        setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.loadUrl("file:///android_asset/index.html");

        askPerms();
    }

    private void askPerms() {
        List<String> need = new ArrayList<String>();
        // 这台机器的 ROM 把读、写当成两条独立权限：只给 WRITE 仍然列不出 /sdcard
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[need.size()]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != REQ_PERMS) return;
        boolean readable = checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
        if (!readable) {
            Toast.makeText(this, "没有存储权限，读不到录音目录", Toast.LENGTH_LONG).show();
        }
        // 权限弹窗关掉后让页面重新扫一遍
        eval("window.__onResume&&window.__onResume()");
    }

    @Override
    protected void onResume() {
        super.onResume();
        eval("window.__onResume&&window.__onResume()");
    }

    @Override
    public void onBackPressed() {
        // 先让页面自己处理（详情面板 / 输入态），它返回 0 表示可以退出
        if (web == null) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("window.__onBack?window.__onBack():0", new ValueCallback<String>() {
            public void onReceiveValue(String v) {
                if (v != null && v.indexOf('1') >= 0) return;
                long now = System.currentTimeMillis();
                if (now - lastBackAt < 2000) {
                    finish();
                } else {
                    lastBackAt = now;
                    Toast.makeText(MainActivity.this, "再按一次退出", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        try {
            if (web != null) {
                web.removeJavascriptInterface("voxnote");
                web.loadUrl("about:blank");
                web.destroy();
            }
            if (bridge != null) bridge.release();
        } catch (Throwable ignore) { }
        web = null;
        super.onDestroy();
    }

    private void eval(String js) {
        try {
            if (web != null) web.evaluateJavascript(js, null);
        } catch (Throwable ignore) { }
    }
}
