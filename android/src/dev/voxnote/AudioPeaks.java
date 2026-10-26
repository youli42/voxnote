package dev.voxnote;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** 解码音频抽峰值；带内存/磁盘缓存，同一文件只解一次。失败返回 null。 */
public final class AudioPeaks {

    private static final int MEM_MAX = 8;
    private static final Map<String, float[]> MEM =
            new LinkedHashMap<String, float[]>(MEM_MAX, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, float[]> e) {
                    return size() > MEM_MAX;
                }
            };

    private AudioPeaks() { }

    public static float[] load(Context ctx, String path, String uri, int buckets) {
        if (buckets < 16) buckets = 16;
        final String key;
        try {
            key = keyOf(ctx, path, uri) + ":" + buckets;
        } catch (Throwable t) {
            return decode(ctx, path, uri, buckets);
        }
        synchronized (MEM) {
            float[] hit = MEM.get(key);
            if (hit != null) return hit;
        }
        File cache = cacheFile(ctx, key);
        float[] fromDisk = readCache(cache, buckets);
        if (fromDisk != null) {
            synchronized (MEM) { MEM.put(key, fromDisk); }
            return fromDisk;
        }
        float[] peaks = decode(ctx, path, uri, buckets);
        if (peaks != null) {
            writeCache(cache, peaks);
            synchronized (MEM) { MEM.put(key, peaks); }
        }
        return peaks;
    }

    // ------------------------------------------------------------ 缓存

    private static String keyOf(Context ctx, String path, String uri) {
        String desc;
        if (path != null && path.length() > 0) {
            File f = new File(path);
            desc = "p|" + path + "|" + f.length() + "|" + f.lastModified();
        } else {
            desc = "u|" + uri;
            try {
                Uri u = Uri.parse(uri == null ? "" : uri);
                android.content.res.AssetFileDescriptor afd =
                        ctx.getContentResolver().openAssetFileDescriptor(u, "r");
                if (afd != null) {
                    desc += "|" + afd.getLength();
                    afd.close();
                }
            } catch (Throwable ignore) { }
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(desc.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(desc.hashCode());
        }
    }

    private static File cacheFile(Context ctx, String key) {
        File dir = new File(ctx.getCacheDir(), "waveform");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, key + ".pk");
    }

    private static float[] readCache(File f, int buckets) {
        if (!f.isFile()) return null;
        DataInputStream in = null;
        try {
            in = new DataInputStream(new FileInputStream(f));
            int n = in.readInt();
            if (n != buckets) return null;
            float[] p = new float[n];
            for (int i = 0; i < n; i++) p[i] = in.readFloat();
            return p;
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) { }
        }
    }

    private static void writeCache(File f, float[] peaks) {
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        DataOutputStream out = null;
        try {
            out = new DataOutputStream(new FileOutputStream(tmp));
            out.writeInt(peaks.length);
            for (float v : peaks) out.writeFloat(v);
            out.flush();
            out.close();
            out = null;
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
        } catch (Throwable t) {
            tmp.delete();
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignore) { }
        }
    }

    // ------------------------------------------------------------ 解码

    /** 失败返回 null（调用方退化为普通占位线）。 */
    private static float[] decode(Context ctx, String path, String uri, int buckets) {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        try {
            if (path != null && path.length() > 0) {
                ex.setDataSource(path);
            } else if (uri != null && uri.length() > 0) {
                Uri u = Uri.parse(uri);
                if ("content".equals(u.getScheme())) ex.setDataSource(ctx, u, null);
                else ex.setDataSource(uri);
            } else {
                return null;
            }
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0 || fmt == null) return null;
            ex.selectTrack(track);
            long durUs = fmt.containsKey(MediaFormat.KEY_DURATION)
                    ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
            int sampleRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 16000;
            int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            if (channels < 1) channels = 1;
            if (durUs <= 0) return null;

            dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            dec.configure(fmt, null, null, 0);
            dec.start();

            float[] peaks = new float[buckets];
            boolean inputDone = false;
            boolean outputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!outputDone) {
                if (!inputDone) {
                    int in = dec.dequeueInputBuffer(10000);
                    if (in >= 0) {
                        ByteBuffer buf = dec.getInputBuffer(in);
                        int size = buf == null ? -1 : ex.readSampleData(buf, 0);
                        if (size < 0) {
                            dec.queueInputBuffer(in, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long pts = ex.getSampleTime();
                            dec.queueInputBuffer(in, 0, size, pts, 0);
                            ex.advance();
                        }
                    }
                }
                int out = dec.dequeueOutputBuffer(info, 10000);
                if (out >= 0) {
                    if (info.presentationTimeUs > 0 && durUs > 0 && info.size > 1) {
                        int bucket = (int) (info.presentationTimeUs * buckets / durUs);
                        if (bucket >= 0 && bucket < buckets) {
                            ByteBuffer ob = dec.getOutputBuffer(out);
                            if (ob != null) {
                                float max = 0f;
                                for (int i = 0; i + 1 < info.size; i += 2) {
                                    short s = ob.getShort(i);
                                    float a = Math.abs(s) / 32768f;
                                    if (a > max) max = a;
                                }
                                if (max > peaks[bucket]) peaks[bucket] = max;
                            }
                        }
                    }
                    dec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
            float max = 0f;
            for (int i = 0; i < buckets; i++) if (peaks[i] > max) max = peaks[i];
            if (max <= 0.0001f) return null;
            for (int i = 0; i < buckets; i++) peaks[i] = Math.max(0.03f, peaks[i] / max);
            return peaks;
        } catch (Throwable t) {
            return null;
        } finally {
            if (dec != null) {
                try { dec.stop(); } catch (Throwable ignore) { }
                try { dec.release(); } catch (Throwable ignore) { }
            }
            try { ex.release(); } catch (Throwable ignore) { }
        }
    }
}
