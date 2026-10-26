package dev.voxnote;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.nio.ByteBuffer;

/** 解码音频前若干 MB，抽成等宽峰值数组（0..1），用于播放器波形。 */
public final class AudioPeaks {

    private AudioPeaks() { }

    /** 失败返回 null（调用方退化为普通进度条）。 */
    public static float[] load(Context ctx, String path, String uri, int buckets) {
        if (buckets < 16) buckets = 16;
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
            long totalSamples = durUs * (long) sampleRate / 1000000L;
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
                    int bucket = -1;
                    if (info.presentationTimeUs > 0 && durUs > 0) {
                        bucket = (int) (info.presentationTimeUs * buckets / durUs);
                    }
                    if (bucket >= 0 && bucket < buckets && info.size > 1) {
                        ByteBuffer ob = dec.getOutputBuffer(out);
                        if (ob != null) {
                            int n = info.size / 2;
                            float max = 0f;
                            for (int i = 0; i + 1 < info.size; i += 2) {
                                short s = ob.getShort(i);
                                float a = Math.abs(s) / 32768f;
                                if (a > max) max = a;
                            }
                            if (max > peaks[bucket]) peaks[bucket] = max;
                        }
                    }
                    dec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 输出格式变化（通常是 PCM 参数），继续解码即可
                }
            }
            // 归一化，避免整体过小看不出
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
