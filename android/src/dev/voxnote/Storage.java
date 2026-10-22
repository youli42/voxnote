package dev.voxnote;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 存储策略：录音存哪儿、从哪儿读回来。
 *
 * 三种目标，按"能不能用"自动降级：
 *   1. 公共目录  —— API ≤28 直接写 /sdcard/<子目录>；API ≥29 写 MediaStore（任何播放器可见）
 *   2. 自定义目录 —— 用户用系统文件选择器挑一个文件夹（SAF），全版本可用
 *   3. 应用私有目录 —— getExternalFilesDir()/records，不需要任何权限，卸载即删
 *
 * 默认 public；没有存储权限、没有选目录、或者写入失败时，自动落到 private。
 * 所有目标都按 yyyy.MM 建日期文件夹。
 */
public class Storage {

    public static final String MODE_PUBLIC = "public";
    public static final String MODE_SAF = "saf";
    public static final String MODE_PRIVATE = "private";
    public static final String DEFAULT_SUB = "Music/voxnote";

    private static final SimpleDateFormat DATE_DIR = new SimpleDateFormat("yyyy.MM", Locale.US);

    private final Context ctx;
    private final SharedPreferences prefs;

    public Storage(Context c) {
        ctx = c.getApplicationContext();
        prefs = ctx.getSharedPreferences("voxnote", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ 设置

    public String mode() {
        String m = prefs.getString("storageMode", MODE_PUBLIC);
        return m == null || m.length() == 0 ? MODE_PUBLIC : m;
    }

    public void setMode(String m) {
        prefs.edit().putString("storageMode", m).apply();
    }

    public String publicSub() {
        String s = prefs.getString("publicSub", DEFAULT_SUB);
        return s == null || s.length() == 0 ? DEFAULT_SUB : s;
    }

    public void setPublicSub(String s) {
        prefs.edit().putString("publicSub", s).apply();
    }

    public String safTree() {
        String s = prefs.getString("safTree", "");
        return s == null ? "" : s;
    }

    public void setSafTree(String uri) {
        prefs.edit().putString("safTree", uri == null ? "" : uri).apply();
    }

    public void clearSaf() {
        prefs.edit().remove("safTree").apply();
    }

    public boolean hasWritePermission() {
        if (Build.VERSION.SDK_INT > 28) return true;   // MediaStore 写入不需要权限
        return ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** 实际生效的目标：用户选的那个能用就用，不能用就落到 private。 */
    public String resolved() {
        String m = mode();
        if (MODE_SAF.equals(m) && safTree().length() > 0) return MODE_SAF;
        if (MODE_PUBLIC.equals(m) && hasWritePermission()) return MODE_PUBLIC;
        if (MODE_PRIVATE.equals(m)) return MODE_PRIVATE;
        return MODE_PRIVATE;
    }

    public String dateDir() {
        synchronized (DATE_DIR) {
            return DATE_DIR.format(new Date());
        }
    }

    public File privateRecords() {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        return new File(base, "records");
    }

    public File privateTmp() {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        return new File(base, "tmp");
    }

    /** 老系统里的公共目录根（也是迁移来源）。 */
    public File publicRoot() {
        return new File(Environment.getExternalStorageDirectory(), publicSub());
    }

    /** 给 UI 看的"实际写入位置"。 */
    public String describe() {
        String r = resolved();
        if (MODE_PRIVATE.equals(r)) return "应用私有 · " + privateRecords().getAbsolutePath();
        if (MODE_SAF.equals(r)) return "自定义目录 · " + safTree();
        if (Build.VERSION.SDK_INT > 28) return "公共目录 · " + publicSub() + "/" + dateDir();
        return "公共目录 · " + new File(publicRoot(), dateDir()).getAbsolutePath();
    }

    // ------------------------------------------------------------ 写入

    /**
     * 把录好的临时文件"发布"到当前目标。
     * 返回的 locator 形如 "path:/…" 或 "uri:content://…"，失败时返回私有目录路径。
     */
    public String publish(File temp, String name, String mime) {
        String r = resolved();
        try {
            if (MODE_SAF.equals(r)) {
                String uri = publishSaf(temp, name, mime);
                if (uri != null) {
                    temp.delete();
                    return "uri:" + uri;
                }
            } else if (MODE_PUBLIC.equals(r)) {
                String loc = publishPublic(temp, name, mime);
                if (loc != null) {
                    temp.delete();
                    return loc;
                }
            }
        } catch (Throwable ignore) {
            // 落到私有目录
        }
        String path = publishPrivate(temp, name);
        return "path:" + path;
    }

    private String publishPrivate(File temp, String name) {
        try {
            File dir = new File(privateRecords(), dateDir());
            if (!dir.exists()) dir.mkdirs();
            File dst = new File(dir, name);
            if (temp.getAbsolutePath().equals(dst.getAbsolutePath())) return dst.getAbsolutePath();
            move(temp, dst);
            return dst.getAbsolutePath();
        } catch (Throwable t) {
            return temp.getAbsolutePath();
        }
    }

    private String publishPublic(File temp, String name, String mime) throws Exception {
        if (Build.VERSION.SDK_INT > 28) {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Audio.Media.MIME_TYPE, mime);
            v.put(MediaStore.Audio.Media.RELATIVE_PATH, publicSub() + "/" + dateDir());
            v.put(MediaStore.Audio.Media.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return null;
            InputStream in = new FileInputStream(temp);
            OutputStream out = cr.openOutputStream(uri);
            try {
                copy(in, out);
            } finally {
                try { in.close(); } catch (Throwable ignore) { }
                try { out.close(); } catch (Throwable ignore) { }
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Audio.Media.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            return "uri:" + uri.toString();
        }
        File dir = new File(publicRoot(), dateDir());
        if (!dir.exists() && !dir.mkdirs()) return null;
        File dst = new File(dir, name);
        move(temp, dst);
        return "path:" + dst.getAbsolutePath();
    }

    private String publishSaf(File temp, String name, String mime) throws Exception {
        String tree = safTree();
        if (tree.length() == 0) return null;
        Uri treeUri = Uri.parse(tree);
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        String dirId = ensureSafDir(treeUri, rootId, dateDir());
        if (dirId == null) return null;
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, dirId);
        Uri doc = DocumentsContract.createDocument(ctx.getContentResolver(), parent, mime, name);
        if (doc == null) return null;
        InputStream in = new FileInputStream(temp);
        OutputStream out = ctx.getContentResolver().openOutputStream(doc);
        try {
            copy(in, out);
        } finally {
            try { in.close(); } catch (Throwable ignore) { }
            try { out.close(); } catch (Throwable ignore) { }
        }
        return doc.toString();
    }

    private static void move(File src, File dst) throws Exception {
        if (dst.exists()) dst.delete();
        if (src.renameTo(dst)) return;
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        try {
            copy(in, out);
        } finally {
            try { in.close(); } catch (Throwable ignore) { }
            try { out.close(); } catch (Throwable ignore) { }
        }
        src.delete();
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    // ------------------------------------------------------------ 读取

    /** 扫描"我们自己的"音频与文稿（私有 + 当前目标 + 老公共目录残留）。 */
    public List<Blob> scanOwn() {
        List<Blob> out = new ArrayList<Blob>();
        scanDir(privateRecords(), out, 0);
        scanDir(new File(Environment.getExternalStorageDirectory(), "s1rec/records"), out, 0);

        // 公共目录：当前子目录 + 默认子目录（换过子目录时旧录音也还在）
        List<String> subs = new ArrayList<String>();
        subs.add(publicSub());
        if (!DEFAULT_SUB.equals(publicSub())) subs.add(DEFAULT_SUB);

        for (int i = 0; i < subs.size(); i++) {
            if (Build.VERSION.SDK_INT > 28) {
                queryMediaStore(out, subs.get(i));
            } else {
                scanDir(new File(Environment.getExternalStorageDirectory(), subs.get(i)), out, 0);
            }
        }

        if (safTree().length() > 0) scanSaf(out);
        return out;
    }

    private void scanDir(File dir, List<Blob> out, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 2) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (int i = 0; i < fs.length; i++) {
            File f = fs[i];
            if (f.isDirectory()) {
                scanDir(f, out, depth + 1);
                continue;
            }
            String n = f.getName().toLowerCase(Locale.US);
            if (!(n.endsWith(".m4a") || n.endsWith(".mp4") || n.endsWith(".aac")
                    || n.endsWith(".wav") || n.endsWith(".mp3") || n.endsWith(".amr")
                    || n.endsWith(".ogg") || n.endsWith(".md") || n.endsWith(".txt"))) continue;
            Blob b = new Blob();
            b.name = f.getName();
            b.path = f.getAbsolutePath();
            b.size = f.length();
            b.mtime = f.lastModified();
            out.add(b);
        }
    }

    private void queryMediaStore(List<Blob> out, String sub) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            String[] proj = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_MODIFIED,
            };
            String sel = MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?";
            String[] args = {sub + "/%"};
            Cursor c = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, args, null);
            if (c == null) return;
            try {
                while (c.moveToNext()) {
                    Blob b = new Blob();
                    long id = c.getLong(0);
                    b.name = c.getString(1);
                    b.size = c.getLong(2);
                    b.mtime = c.getLong(3) * 1000L;
                    b.uri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString();
                    if (b.name != null) out.add(b);
                }
            } finally {
                c.close();
            }
        } catch (Throwable ignore) {
            // 没有 READ_MEDIA_AUDIO 时查不到，界面会显示私有目录里的东西
        }
    }

    // ------------------------------------------------------------ SAF 枚举

    private void scanSaf(List<Blob> out) {
        String tree = safTree();
        if (tree.length() == 0) return;
        try {
            Uri treeUri = Uri.parse(tree);
            String rootId = DocumentsContract.getTreeDocumentId(treeUri);
            List<Doc> children = listChildren(treeUri, rootId);
            for (int i = 0; i < children.size(); i++) {
                Doc d = children.get(i);
                if (d.isDir) {
                    List<Doc> files = listChildren(treeUri, d.id);
                    for (int j = 0; j < files.size(); j++) addSafBlob(out, treeUri, files.get(j));
                } else {
                    addSafBlob(out, treeUri, d);
                }
            }
        } catch (Throwable ignore) {
            // 授权失效 / 目录被删：当作没有
        }
    }

    private void addSafBlob(List<Blob> out, Uri treeUri, Doc d) {
        String n = d.name == null ? "" : d.name.toLowerCase(Locale.US);
        boolean ok = n.endsWith(".m4a") || n.endsWith(".mp4") || n.endsWith(".aac")
                || n.endsWith(".wav") || n.endsWith(".mp3") || n.endsWith(".amr")
                || n.endsWith(".ogg") || n.endsWith(".md") || n.endsWith(".txt");
        if (!ok) return;
        Blob b = new Blob();
        b.name = d.name;
        b.size = d.size;
        b.mtime = d.mtime;
        b.uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, d.id).toString();
        out.add(b);
    }

    private static final class Doc {
        String id;
        String name;
        String mime;
        long size;
        long mtime;
        boolean isDir;
    }

    private List<Doc> listChildren(Uri treeUri, String parentId) {
        List<Doc> out = new ArrayList<Doc>();
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
            Cursor c = ctx.getContentResolver().query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            }, null, null, null);
            if (c == null) return out;
            try {
                while (c.moveToNext()) {
                    Doc d = new Doc();
                    d.id = c.getString(0);
                    d.name = c.getString(1);
                    d.mime = c.getString(2);
                    d.size = c.getLong(3);
                    d.mtime = c.getLong(4);
                    d.isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(d.mime);
                    out.add(d);
                }
            } finally {
                c.close();
            }
        } catch (Throwable ignore) {
        }
        return out;
    }

    private String ensureSafDir(Uri treeUri, String rootId, String name) {
        List<Doc> children = listChildren(treeUri, rootId);
        for (int i = 0; i < children.size(); i++) {
            Doc d = children.get(i);
            if (d.isDir && name.equals(d.name)) return d.id;
        }
        try {
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId);
            Uri created = DocumentsContract.createDocument(ctx.getContentResolver(), parent,
                    DocumentsContract.Document.MIME_TYPE_DIR, name);
            if (created == null) return null;
            return DocumentsContract.getDocumentId(created);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ 删除

    /**
     * 这场录音是不是存在"我们能删"的位置：自录（私有 / 公共子目录 / 老 s1rec/records）
     * 或 SAF / MediaStore（都记成 content://）。厂商目录（/sdcard/2026.10、/sdcard/sogou）
     * 一律返回 false —— 那些文件不属于我们，不能碰。
     */
    public boolean isOwnAudio(String path, String uri) {
        if (uri != null && uri.length() > 0) return true;
        if (path == null || path.length() == 0) return false;
        File sd = Environment.getExternalStorageDirectory();
        String[] roots = {
                privateRecords().getAbsolutePath(),
                new File(sd, "s1rec/records").getAbsolutePath(),
                new File(sd, publicSub()).getAbsolutePath(),
                new File(sd, DEFAULT_SUB).getAbsolutePath(),
        };
        for (int i = 0; i < roots.length; i++) {
            String r = roots[i];
            if (path.equals(r) || path.startsWith(r + File.separator)) return true;
        }
        return false;
    }

    /** 删除一条自录音频：文件路径直接删；content:// 走 MediaStore 或 SAF 文档删除。 */
    public boolean deleteOwnAudio(String path, String uri) {
        if (uri != null && uri.length() > 0) {
            try {
                Uri u = Uri.parse(uri);
                if (uri.contains("/document/")) {
                    return DocumentsContract.deleteDocument(ctx.getContentResolver(), u);
                }
                return ctx.getContentResolver().delete(u, null, null) > 0;
            } catch (Throwable t) {
                return false;
            }
        }
        return deleteFile(path);
    }

    public boolean deleteFile(String path) {
        try {
            if (path == null || path.length() == 0) return false;
            File f = new File(path);
            return f.isFile() && f.delete();
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------ Blob

    /** 一个可读的音频/文稿：要么是文件路径，要么是 content:// URI。 */
    public static class Blob {
        public String name;
        public String path;
        public String uri;
        public long size;
        public long mtime;

        public boolean isAudio() {
            String n = name == null ? "" : name.toLowerCase(Locale.US);
            return n.endsWith(".m4a") || n.endsWith(".mp4") || n.endsWith(".aac")
                    || n.endsWith(".wav") || n.endsWith(".mp3") || n.endsWith(".amr")
                    || n.endsWith(".ogg");
        }

        public InputStream open(Context c) throws Exception {
            if (uri != null && uri.length() > 0) {
                return c.getContentResolver().openInputStream(Uri.parse(uri));
            }
            return new FileInputStream(path);
        }
    }
}
