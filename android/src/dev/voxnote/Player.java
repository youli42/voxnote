package dev.voxnote;

import android.media.MediaPlayer;

/**
 * 播放器壳子：只暴露"打开 / 播 / 停 / 拖 / 查位置"。
 *
 * 为什么不直接在 WebView 里放 <audio src="file:///…">：
 * Android 8 的 WebView 对 file:// 页面读 file:// 媒体限制多，且拖拽精度依赖
 * 音频元素；用原生 MediaPlayer 由 Java 桥驱动，行为在各机型一致。
 */
public class Player {

    private final Object lock = new Object();
    private MediaPlayer mp;
    private String path;

    /** 同一路径重复 open 直接复用，避免拖动进度条时反复 prepare。 */
    public boolean open(String p) {
        if (p == null || p.length() == 0) return false;
        synchronized (lock) {
            if (mp != null && p.equals(path)) return true;
            release();
            MediaPlayer m = new MediaPlayer();
            try {
                m.setDataSource(p);
                m.prepare();
            } catch (Throwable t) {
                try { m.release(); } catch (Throwable ignore) { }
                return false;
            }
            mp = m;
            path = p;
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
            path = null;
        }
    }
}
