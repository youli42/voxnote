package dev.voxnote;

import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;

/**
 * 一次性连通性探测：GET 一个 URL，报「地址可达 + HTTP 码 + 延迟」或可读的失败原因。
 * 给设置页「测试连接」用——不管对方是什么服务，只要 TCP/TLS 通了、拿到任何 HTTP
 * 响应就算地址可达（404/405 也算，提示核对路径），连不上才算失败。
 *
 * 自己起守护线程，不占 ServerSync 的同步队列；回调切回主线程。
 */
public final class NetProbe {

    public interface Callback {
        void onDone(String err, int code, long ms);
    }

    private NetProbe() { }

    public static void probe(final String rawUrl, final String bearer, final Callback cb) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        if (url.length() == 0) {
            post(cb, "地址为空", 0, 0);
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://" + url;
        final String target = url;
        final long t0 = android.os.SystemClock.elapsedRealtime();
        new Thread(new Runnable() {
            public void run() {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(target).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(10000);
                    c.setInstanceFollowRedirects(true);
                    if (bearer != null && bearer.trim().length() > 0) {
                        c.setRequestProperty("Authorization", "Bearer " + bearer.trim());
                    }
                    // getResponseCode 抛异常 = 连不上；-1 = 对端不是 HTTP
                    int code = c.getResponseCode();
                    long ms = android.os.SystemClock.elapsedRealtime() - t0;
                    post(cb, code < 0 ? "对方不是 HTTP 服务" : null, code, ms);
                } catch (final Throwable t) {
                    post(cb, readable(t), 0, 0);
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }, "voxnote.probe").start();
    }

    private static void post(final Callback cb, final String err, final int code, final long ms) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                cb.onDone(err, code, ms);
            }
        });
    }

    private static String readable(Throwable t) {
        if (t instanceof UnknownHostException) return "无法解析主机名";
        if (t instanceof SocketTimeoutException) return "连接超时";
        if (t instanceof ConnectException) return "连接不上（检查地址 / 端口 / 是否同一网络）";
        if (t instanceof IOException) {
            String m = String.valueOf(t.getMessage());
            if (m.indexOf("Cleartext") >= 0) return "系统拦截了明文 http 请求";
            return m;
        }
        String m = String.valueOf(t.getMessage());
        return m.length() == 0 ? String.valueOf(t) : m;
    }
}
