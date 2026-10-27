package dev.voxnote.v2;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

import dev.voxnote.UiSettings;

/**
 * v2 音频下载器（手机端）：替代 v1 ServerSync.fetchAudio 的“从服务器取回某场录音”。
 *
 * 走 GET /api/v2/records/{recordId}/audio，请求头 Bearer 设备令牌，响应是音频二进制。
 * 与 V2Sync 的网络/存储完全独立：不碰 outbox、不改同步游标，失败只把明确错误
 * 交给调用方（异常或异步回调），由界面提示。
 *
 * 下载落盘规则：destDir/&lt;recordId&gt;.&lt;ext&gt;，ext 先看 Content-Disposition 文件名，
 * 再看 Content-Type，最后默认 .m4a；先写 .part 临时文件，成功后再改名，避免半截文件。
 * 目标文件已存在且长度 &gt; 0 时直接复用，不重复下载。
 */
public class V2Audio {

    /** 下载结束回调：err == null 表示成功，file 为本地音频文件；否则 err 带原因。 */
    public interface Callback {
        void onDone(Throwable err, File file);
    }

    private static final String DEFAULT_EXT = ".m4a";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    // 音频可能较大，读超时比 JSON 接口放宽（10 分钟）
    private static final int READ_TIMEOUT_MS = 600000;

    /**
     * 同步下载一场录音的原始音频到本地，返回落盘文件。
     * 失败抛异常：未配置服务器、401 鉴权失败、404 记录不存在、网络异常、写文件失败等。
     * 注意：会阻塞调用线程，界面请用 {@link #downloadAsync}。
     */
    public static File download(Context ctx, String recordId, File destDir) throws Exception {
        if (ctx == null) throw new IllegalArgumentException("Context 为空");
        if (recordId == null || recordId.trim().length() == 0) {
            throw new IllegalArgumentException("缺少 v2 记录 id");
        }
        if (destDir == null) throw new IllegalArgumentException("下载目录为空");
        final String id = recordId.trim();
        final String safeId = safeName(id);
        final String base = base(ctx);
        final String token = token(ctx);

        // 先看本地有没有已下好的（长度 > 0 才算）：有就直接复用，连网络都不走，
        // 即便此时服务器配置已清空也不影响已下载过的场次播放。
        if (destDir.exists() && destDir.isDirectory()) {
            File cached = reuseExisting(destDir, safeId);
            if (cached != null) return cached;
        }
        if (base.length() == 0 || token.length() == 0) {
            throw new IllegalStateException("未配置 v2 服务器地址或令牌");
        }
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("无法创建下载目录：" + destDir.getAbsolutePath());
        }
        if (!destDir.isDirectory()) {
            throw new IOException("下载目录不是文件夹：" + destDir.getAbsolutePath());
        }

        String url = base + "/api/v2/records/" + urlEncode(id) + "/audio";
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Accept", "audio/*,application/octet-stream");

            int code;
            try {
                code = c.getResponseCode();
            } catch (IOException e) {
                throw networkError(e);
            }
            if (code == 401) {
                throw new IllegalStateException(
                        "鉴权失败（HTTP 401）：v2 令牌无效或已过期，请到设置里重新扫码/登录");
            }
            if (code == 404) {
                throw new IllegalStateException(
                        "服务器上没有这场录音（HTTP 404）：recordId=" + id);
            }
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("音频下载失败（HTTP " + code + "）"
                        + errorBody(c));
            }

            String ext = extFromHeaders(c);
            File dest = new File(destDir, safeId + ext);
            if (dest.exists() && dest.length() > 0) return dest;   // 并发下载时复用先到的

            File tmp = new File(destDir, "." + safeId + ext + ".part");
            InputStream in;
            try {
                in = c.getInputStream();
            } catch (IOException e) {
                throw networkError(e);
            }
            long total = 0;
            boolean saved = false;
            FileOutputStream out;
            try {
                out = new FileOutputStream(tmp);
            } catch (IOException e) {
                try { in.close(); } catch (Throwable ignore) { }
                throw new IOException("无法写入音频文件：" + tmp.getAbsolutePath()
                        + "（" + e.getMessage() + "）", e);
            }
            try {
                byte[] buf = new byte[65536];
                while (true) {
                    int n;
                    try {
                        n = in.read(buf);
                    } catch (IOException e) {
                        throw networkError(e);          // 读网络流出错
                    }
                    if (n <= 0) break;
                    try {
                        out.write(buf, 0, n);
                    } catch (IOException e) {
                        throw new IOException("写入音频文件失败："
                                + tmp.getAbsolutePath(), e);
                    }
                    total += n;
                }
                saved = true;
            } finally {
                try { out.close(); } catch (Throwable ignore) { }
                try { in.close(); } catch (Throwable ignore) { }
                if (!saved) tmp.delete();   // 失败的半截临时文件不留在目录里
            }
            if (total <= 0) {
                tmp.delete();
                throw new IOException("服务器返回的音频为空（0 字节）：recordId=" + id);
            }
            if (dest.exists() && !dest.delete()) {
                tmp.delete();
                throw new IOException("无法覆盖已有音频文件：" + dest.getAbsolutePath());
            }
            if (!tmp.renameTo(dest)) {
                tmp.delete();
                throw new IOException("音频文件保存失败：" + dest.getAbsolutePath());
            }
            return dest;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * 旧 RecordVm 用 stamp 当 id：按 legacy_id 找对应的 v2 记录 id。
     * 找不到（或本地还没同步到）返回 ""，调用方据此提示“尚未同步到 v2”。
     */
    public static String recordIdFor(Context ctx, String legacyStamp) {
        if (ctx == null) return "";
        if (legacyStamp == null || legacyStamp.trim().length() == 0) return "";
        try {
            JSONObject rec = V2Store.get(ctx).findByLegacy(legacyStamp.trim());
            return rec == null ? "" : rec.optString("id", "");
        } catch (Throwable t) {
            // 查本地库失败等同“没找到”：返回空串，让界面走“未同步”提示，不崩 UI
            return "";
        }
    }

    /**
     * 后台线程下载，回调在主线程：成功 onDone(null, file)；
     * 失败 onDone(err, null)，err 里带明确原因（401/404/网络异常等）。
     */
    public static void downloadAsync(final Context ctx, final String recordId,
                                     final File destDir, final Callback cb) {
        if (ctx == null) throw new IllegalArgumentException("Context 为空");
        final Context app = ctx.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            public void run() {
                Throwable err = null;
                File file = null;
                try {
                    file = download(app, recordId, destDir);
                } catch (Throwable t) {
                    err = t;
                }
                final Throwable fErr = err;
                final File fFile = file;
                main.post(new Runnable() {
                    public void run() {
                        if (cb != null) cb.onDone(fErr, fFile);
                    }
                });
            }
        }, "v2-audio").start();
    }

    // ------------------------------------------------------------ 本地复用

    /** destDir 下找 <recordId>.<ext> 且长度 > 0 的音频；.part 临时文件（以 . 开头）不算。 */
    private static File reuseExisting(File destDir, String safeId) {
        File[] files = destDir.listFiles();
        if (files == null) return null;
        String prefix = safeId + ".";
        for (File f : files) {
            if (f == null || !f.isFile() || f.length() <= 0) continue;
            String name = f.getName();
            if (!name.startsWith(prefix)) continue;
            if (looksLikeExt("." + name.substring(prefix.length()))) return f;
        }
        return null;
    }

    // ------------------------------------------------------------ 扩展名推断

    /** 扩展名：Content-Disposition 文件名优先，其次 Content-Type，最后 .m4a。 */
    private static String extFromHeaders(HttpURLConnection c) {
        String name = fileNameFromDisposition(c.getHeaderField("Content-Disposition"));
        String ext = extOfName(name);
        if (ext.length() == 0) ext = extOfMime(c.getContentType());
        return ext.length() == 0 ? DEFAULT_EXT : ext;
    }

    /** 解析 Content-Disposition 里的文件名（filename*=RFC5987 与 filename= 都认）。 */
    private static String fileNameFromDisposition(String cd) {
        if (cd == null) return "";
        String s = cd.trim();
        int i = s.indexOf("filename*=");
        if (i >= 0) {
            String v = s.substring(i + 10).trim();
            int semi = v.indexOf(';');
            if (semi >= 0) v = v.substring(0, semi);
            v = stripQuotes(v.trim());
            // RFC5987 形如 UTF-8''%E5%BD%95%E9%9F%B3.m4a：扩展名是 ASCII，无需解码
            int q = v.indexOf("''");
            if (q >= 0) v = v.substring(q + 2);
            return v;
        }
        i = s.indexOf("filename=");
        if (i < 0) return "";
        String v = s.substring(i + 9).trim();
        int semi = v.indexOf(';');
        if (semi >= 0) v = v.substring(0, semi);
        return stripQuotes(v.trim());
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** 从文件名取扩展名（小写、带点），像 .m4a 才认；没有或不认识返回 ""。 */
    private static String extOfName(String name) {
        if (name == null) return "";
        String s = name.trim();
        int q = s.indexOf('?');           // 去掉 URL 查询串
        if (q >= 0) s = s.substring(0, q);
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        int dot = s.lastIndexOf('.');
        if (dot < 0 || dot < slash || dot == s.length() - 1) return "";
        String ext = s.substring(dot).toLowerCase(Locale.US);
        return looksLikeExt(ext) ? ext : "";
    }

    /** Content-Type → 扩展名；不认识的音频类型返回 ""（上层退 .m4a）。 */
    private static String extOfMime(String contentType) {
        if (contentType == null) return "";
        String type = contentType;
        int semi = type.indexOf(';');
        if (semi >= 0) type = type.substring(0, semi);
        type = type.trim().toLowerCase(Locale.US);
        if ("audio/mp4".equals(type) || "audio/m4a".equals(type)
                || "audio/x-m4a".equals(type) || "application/mp4".equals(type)) {
            return ".m4a";
        }
        if ("audio/mpeg".equals(type) || "audio/mp3".equals(type)
                || "audio/x-mp3".equals(type)) {
            return ".mp3";
        }
        if ("audio/wav".equals(type) || "audio/x-wav".equals(type)
                || "audio/wave".equals(type)) {
            return ".wav";
        }
        if ("audio/ogg".equals(type) || "application/ogg".equals(type)) return ".ogg";
        if ("audio/aac".equals(type) || "audio/aacp".equals(type)) return ".aac";
        if ("audio/flac".equals(type) || "audio/x-flac".equals(type)) return ".flac";
        if ("audio/amr".equals(type)) return ".amr";
        if ("audio/webm".equals(type)) return ".webm";
        if ("audio/3gpp".equals(type)) return ".3gp";
        return "";
    }

    /** 扩展名是否像 ".xxx"（1~5 位字母数字，带点）；用于挡 .part 之类的临时文件。 */
    private static boolean looksLikeExt(String ext) {
        if (ext == null || ext.length() < 2 || ext.length() > 6) return false;
        if (ext.charAt(0) != '.') return false;
        for (int i = 1; i < ext.length(); i++) {
            char ch = ext.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9');
            if (!ok) return false;
        }
        return true;
    }

    // ------------------------------------------------------------ 杂项

    /** 文件名安全化：只留字母数字与 - _，避免 id 里带路径分隔符写出目录。 */
    private static String safeName(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_';
            sb.append(ok ? ch : '_');
        }
        return sb.toString();
    }

    private static IOException networkError(IOException e) {
        String msg = e.getMessage();
        return new IOException("网络异常，无法下载音频："
                + (msg == null || msg.length() == 0 ? e.toString() : msg), e);
    }

    /** 非 2xx/401/404 时把服务器错误体带进异常，方便排查。 */
    private static String errorBody(HttpURLConnection c) {
        InputStream in = null;
        try {
            in = c.getErrorStream();
            if (in == null) return "";
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0 && bos.size() < 4096) bos.write(buf, 0, n);
            String s = new String(bos.toByteArray(), "UTF-8").trim();
            return s.length() == 0 ? "" : "：" + s;
        } catch (Throwable ignore) {
            return "";
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable ignore) { }
            }
        }
    }

    private static String base(Context ctx) {
        UiSettings s = UiSettings.load(ctx);
        String url = s.serverUrl == null ? "" : s.serverUrl.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    private static String token(Context ctx) {
        UiSettings s = UiSettings.load(ctx);
        return s.serverToken == null ? "" : s.serverToken.trim();
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
