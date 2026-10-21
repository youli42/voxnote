package dev.voxnote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;

/**
 * 详情页的进度条：轨道 + 已播进度 + 打点 + 拖拽手柄。
 * 比系统 SeekBar 更贴近原型，也省得为它写三层 drawable。
 */
public class SeekBarView extends View {

    public interface Listener {
        void onSeek(float fraction);
    }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progress = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Double> marks = new ArrayList<Double>();
    private float fraction;
    private double totalSec = 1;
    private boolean dragging;
    private Listener listener;

    public SeekBarView(Context c) {
        super(c);
        track.setColor(Ui.LINE2);
        progress.setColor(Ui.DIM);
        tick.setColor(Ui.WHITE);
        thumb.setColor(Ui.WHITE);
        setClickable(true);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setTotal(double seconds) {
        totalSec = Math.max(1, seconds);
        invalidate();
    }

    public void setMarks(ArrayList<Double> m) {
        marks.clear();
        if (m != null) marks.addAll(m);
        invalidate();
    }

    public void setFraction(float f) {
        if (!dragging) {
            fraction = Math.max(0, Math.min(1, f));
            invalidate();
        }
    }

    public boolean isDragging() {
        return dragging;
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(resolveSize(Ui.dp(200), w), Ui.dp(26));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float left = Ui.dp(4);
        float right = getWidth() - Ui.dp(4);
        float cy = getHeight() / 2f;
        float barH = Ui.dp(4);
        float r = barH / 2f;
        canvas.drawRoundRect(left, cy - r, right, cy + r, r, r, track);

        float px = left + (right - left) * fraction;
        canvas.drawRoundRect(left, cy - r, Math.max(left, px), cy + r, r, r, progress);

        for (int i = 0; i < marks.size(); i++) {
            double m = marks.get(i);
            float f = (float) Math.max(0, Math.min(1, m / totalSec));
            float x = left + (right - left) * f;
            canvas.drawCircle(x, cy, Ui.dp(3), tick);
        }

        float thumbR = Ui.dp(5);
        canvas.drawCircle(px, cy, thumbR, thumb);
    }

    private float fractionOf(float x) {
        float left = Ui.dp(4);
        float right = getWidth() - Ui.dp(4);
        if (right <= left) return 0;
        return Math.max(0, Math.min(1, (x - left) / (right - left)));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                seek(e.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) seek(e.getX());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) seek(e.getX());
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void seek(float x) {
        fraction = fractionOf(x);
        invalidate();
        if (listener != null) listener.onSeek(fraction);
    }
}
