package dev.voxnote;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;

/**
 * 播放器壳子：只暴露"打开 / 播 / 停 / 拖 / 查位置"。
 *
 * 支持两种定位：文件路径（私有目录、老系统的公共目录）与 content:// 
 * （MediaStore、SAF 选中的目录）。都用原生 MediaPlayer，行为在各机型一致。
 */
public class Player {

    private final Object lock = new Object();
    private final Context ctx;
    private MediaPlayer mp;
    private String key;

    public Player(Context c) {
        ctx = c;
    }

    /** path 与 uri 二选一，uri 优先。 */
    public boolean open(String path, String uri) {
        String k = (uri != null && uri.length() > 0) ? uri : path;
        if (k == null || k.length() == 0) return false;
        synchronized (lock) {
            if (mp != null && k.equals(key)) return true;
            release();
            MediaPlayer m = new MediaPlayer();
            try {
                if (uri != null && uri.length() > 0) {
                    m.setDataSource(ctx, Uri.parse(uri));
                } else {
                    m.setDataSource(path);
                }
                m.prepare();
            } catch (Throwable t) {
                try { m.release(); } catch (Throwable ignore) { }
                return false;
            }
            mp = m;
            key = k;
            return true;
        }
    }

    public void play() {
        synchronized (lock) {
            try { if (mp != null && !mp.isPlaying()) mp.start(); } catch (Throwable ignore) { }
        }
    }

    public void pause() {
        synchronized (lock) {
            try { if (mp != null && mp.isPlaying()) mp.pause(); } catch (Throwable ignore) { }
        }
    }

    public void seek(int ms) {
        synchronized (lock) {
            try { if (mp != null) mp.seekTo(ms < 0 ? 0 : ms); } catch (Throwable ignore) { }
        }
    }

    public int position() {
        synchronized (lock) {
            try { return mp != null ? mp.getCurrentPosition() : 0; } catch (Throwable t) { return 0; }
        }
    }

    public int duration() {
        synchronized (lock) {
            try { return mp != null ? mp.getDuration() : 0; } catch (Throwable t) { return 0; }
        }
    }

    public boolean playing() {
        synchronized (lock) {
            try { return mp != null && mp.isPlaying(); } catch (Throwable t) { return false; }
        }
    }

    public void release() {
        synchronized (lock) {
            if (mp != null) {
                try { mp.stop(); } catch (Throwable ignore) { }
                try { mp.release(); } catch (Throwable ignore) { }
                mp = null;
            }
            key = null;
        }
    }
}
