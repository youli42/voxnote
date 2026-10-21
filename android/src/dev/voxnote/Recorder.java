package dev.voxnote;

import android.media.MediaRecorder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 自录：MediaRecorder → 16 kHz 单声道 AAC(.m4a)。
 *
 * 先录到应用私有临时文件，再由 Storage 决定"发布"到公共目录 / 自定义目录 / 保持私有。
 * 这样无论存储策略怎么变，录音本身只有一条写入路径。
 * 16 kHz 单声道是后续接 ASR（云端或本地小模型）最省事的输入规格。
 */
public class Recorder {

    private final Object lock = new Object();
    private MediaRecorder mr;
    private File out;
    private long t0;

    public boolean active() {
        synchronized (lock) { return mr != null; }
    }

    public boolean start(File target) {
        synchronized (lock) {
            if (mr != null) return false;
            File f = target;
            File dir = f.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) return false;
            MediaRecorder m = new MediaRecorder();
            try {
                m.setAudioSource(MediaRecorder.AudioSource.MIC);
                m.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                m.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                m.setAudioSamplingRate(16000);
                m.setAudioEncodingBitRate(32000);
                m.setAudioChannels(1);
                m.setOutputFile(f.getAbsolutePath());
                m.prepare();
                m.start();
            } catch (Throwable t) {
                try { m.release(); } catch (Throwable ignore) { }
                try { f.delete(); } catch (Throwable ignore) { }
                return false;
            }
            mr = m;
            out = f;
            t0 = System.currentTimeMillis();
            return true;
        }
    }

    /** 返回临时文件（还没发布），失败返回 null。 */
    public File stop() {
        synchronized (lock) {
            if (mr == null) return null;
            File f = out;
            try {
                mr.stop();
            } catch (Throwable t) {
                // 时间太短或被打断时 stop() 会抛，此时文件一般不可用
                f = null;
            } finally {
                try { mr.release(); } catch (Throwable ignore) { }
                mr = null;
                out = null;
            }
            if (f == null || !f.exists() || f.length() < 1024) {
                if (f != null) f.delete();
                return null;
            }
            return f;
        }
    }

    public int ms() {
        synchronized (lock) {
            return mr == null ? 0 : (int) (System.currentTimeMillis() - t0);
        }
    }

    /** 0..1 电平，喂给录音波形动画。 */
    public float level() {
        synchronized (lock) {
            if (mr == null) return 0f;
            try {
                return Math.min(1f, mr.getMaxAmplitude() / 32767f * 3f);
            } catch (Throwable t) {
                return 0f;
            }
        }
    }

    public static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }
}
