package dev.voxnote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;

/** 播放器波形：已播部分高亮，点击/拖动可定位。 */
public class WaveformView extends View {

    public interface Listener {
        void onSeek(float fraction);
    }

    private float[] peaks;
    private float progress;
    private float[] markFracs = new float[0];
    private boolean loading;
    private Listener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wavePath = new Path();
    private final RectF clip = new RectF();
    private int builtW = -1;
    private int builtH = -1;
    private float[] builtPeaks = null;

    public WaveformView(Context c) {
        super(c);
        setWillNotDraw(false);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setPeaks(float[] p) {
        peaks = p;
        invalidate();
    }

    public void setLoading(boolean b) {
        if (loading != b) {
            loading = b;
            invalidate();
        }
    }

    public void setProgress(float f) {
        float v = Math.max(0f, Math.min(1f, f));
        if (Math.abs(v - progress) > 0.001f) {
            progress = v;
            invalidate();
        }
    }

    /** 标记点：与播放进度共用波形，不再单独画进度条。 */
    public void setMarks(List<Double> marks, long totalMs) {
        if (marks == null || marks.size() == 0 || totalMs <= 0) {
            markFracs = new float[0];
        } else {
            markFracs = new float[marks.size()];
            for (int i = 0; i < marks.size(); i++) {
                double s = marks.get(i);
                markFracs[i] = (float) Math.max(0, Math.min(1, s * 1000 / totalMs));
            }
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (peaks == null || peaks.length == 0) {
            // 解码中占位：中线 + 进度，保证能看进度/拖动
            paint.setColor(0xFF3A404B);
            canvas.drawRoundRect(0, h / 2f - 1, w, h / 2f + 1, 1, 1, paint);
            if (progress > 0f) {
                paint.setColor(0xFF7AA2FF);
                canvas.drawRoundRect(0, h / 2f - 2, progress * w, h / 2f + 2,
                        2, 2, paint);
            }
            paint.setColor(0xFF7AA2FF);
            float cx = progress * w;
            canvas.drawRoundRect(cx - 1, 0, cx + 1, h, 1, 1, paint);
            return;
        }
        if (wavePath.isEmpty() || builtW != (int) w || builtH != (int) h
                || builtPeaks != peaks) {
            buildPath(w, h);
        }
        // 未播部分
        paint.setColor(0xFF3A404B);
        canvas.drawPath(wavePath, paint);
        // 已播部分：裁剪后重绘
        paint.setColor(0xFFE7E9EE);
        canvas.save();
        clip.set(0, 0, progress * w, h);
        canvas.clipRect(clip);
        canvas.drawPath(wavePath, paint);
        canvas.restore();
        // 播放位置
        paint.setColor(0xFF7AA2FF);
        float px = progress * w;
        canvas.drawRoundRect(px - 1, 0, px + 1, h, 1, 1, paint);
        // 标记
        paint.setColor(0xFFFFD479);
        for (int i = 0; i < markFracs.length; i++) {
            float mx = markFracs[i] * w;
            canvas.drawRoundRect(mx - 1, 0, mx + 1, h, 1, 1, paint);
        }
    }

    /** 连续镜像填充轮廓，和 Web 端 WaveSurfer 的观感一致。 */
    private void buildPath(float w, float h) {
        wavePath.reset();
        int n = peaks.length;
        float mid = h / 2f;
        float maxBar = h * 0.94f;
        float step = Math.max(1f, w / Math.max(1, n - 1));
        // 上沿：左 → 右
        wavePath.moveTo(0, mid - peaks[0] * maxBar / 2f);
        for (int i = 1; i < n; i++) {
            wavePath.lineTo(i * step, mid - peaks[i] * maxBar / 2f);
        }
        // 下沿：右 → 左
        for (int i = n - 1; i >= 0; i--) {
            wavePath.lineTo(i * step, mid + peaks[i] * maxBar / 2f);
        }
        wavePath.close();
        builtW = (int) w;
        builtH = (int) h;
        builtPeaks = peaks;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (listener == null || getWidth() <= 0) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                listener.onSeek(Math.max(0f, Math.min(1f, e.getX() / getWidth())));
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }
}
