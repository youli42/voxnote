package dev.voxnote;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
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
 *   界面、手势、滚动、动画在 H5 里；
 *   文件扫描、音频播放、麦克风录音、存储目标、权限在 Java 侧，通过 window.voxnote 桥接。
 */
public class MainActivity extends Activity {

    private static final int REQ_PERMS = 7;
    private static final int REQ_TREE = 8;

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

    /** 按系统版本申请对应权限：≤28 读写存储，29–32 读存储，33+ 读音频 + 麦克风。 */
    public void askPerms() {
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
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[need.size()]), REQ_PERMS);
        }
    }

    private void addIfMissing(List<String> need, String perm) {
        try {
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) need.add(perm);
        } catch (Throwable ignore) { }
    }

    /** 系统文件选择器：挑一个自定义录音目录。 */
    public void pickSafDir() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_TREE);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开文件选择器", Toast.LENGTH_SHORT).show();
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
        } catch (Throwable ignore) {
            // 有些文件提供方不支持持久授权，当次会话仍可用
        }
        if (bridge != null) bridge.setSafTree(uri.toString());
        eval("window.__onStorageChanged&&window.__onStorageChanged()");
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != REQ_PERMS) return;
        eval("window.__onResume&&window.__onResume()");
    }

    @Override
    protected void onResume() {
        super.onResume();
        eval("window.__onResume&&window.__onResume()");
    }

    @Override
    public void onBackPressed() {
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
