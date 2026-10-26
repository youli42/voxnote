package dev.voxnote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** 播放器波形：已播部分高亮，点击/拖动可定位。 */
public class WaveformView extends View {

    public interface Listener {
        void onSeek(float fraction);
    }

    private float[] peaks;
    private float progress;
    private Listener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

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

    public void setProgress(float f) {
        float v = Math.max(0f, Math.min(1f, f));
        if (Math.abs(v - progress) > 0.001f) {
            progress = v;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (peaks == null || peaks.length == 0) {
            paint.setColor(0xFF3A404B);
            canvas.drawRoundRect(0, h / 2f - 1, w, h / 2f + 1, 1, 1, paint);
            return;
        }
        int n = peaks.length;
        float gap = Math.max(1f, w / n / 4f);
        float barW = Math.max(1f, (w - gap * (n - 1)) / n);
        float mid = h / 2f;
        float maxBar = h * 0.92f;
        for (int i = 0; i < n; i++) {
            float bh = Math.max(barW, peaks[i] * maxBar);
            float x = i * (barW + gap);
            paint.setColor(i < (int) (progress * n) ? 0xFFE7E9EE : 0xFF3A404B);
            canvas.drawRoundRect(x, mid - bh / 2f, x + barW, mid + bh / 2f,
                    barW / 2f, barW / 2f, paint);
        }
        paint.setColor(0xFF7AA2FF);
        float px = progress * w;
        canvas.drawRoundRect(px - 1, 0, px + 1, h, 1, 1, paint);
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
