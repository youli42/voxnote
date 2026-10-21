package dev.voxnote;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** 原型里的录音按钮：外圈圆 + 内白块，录的时候内块从圆变成圆角方块。 */
public class RecordButton extends View {

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float t;              // 0 = 圆, 1 = 方块
    private ValueAnimator anim;

    public RecordButton(Context c) {
        super(c);
        setClickable(true);
        setFocusable(false);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.dp(1));
        stroke.setColor(Ui.LINE2);
        fill.setColor(Ui.SURFACE2);
        inner.setColor(Ui.WHITE);
    }

    public void setRecording(boolean recording) {
        if (anim != null) anim.cancel();
        final float from = t;
        final float to = recording ? 1f : 0f;
        anim = ValueAnimator.ofFloat(from, to);
        anim.setDuration(240);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                t = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int size = Ui.dp(68);
        setMeasuredDimension(resolveSize(size, w), resolveSize(size, h));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) / 2f - stroke.getStrokeWidth() / 2f;

        canvas.drawCircle(cx, cy, r, fill);
        stroke.setColor(t > 0.5f ? 0xFF4B505A : Ui.LINE2);
        canvas.drawCircle(cx, cy, r, stroke);

        float circleSize = Ui.dp(26);
        float squareSize = Ui.dp(20);
        float side = circleSize + (squareSize - circleSize) * t;
        float rad = (side / 2f) * (1f - t) + Ui.dp(6) * t;
        rect.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f);
        canvas.drawRoundRect(rect, rad, rad, inner);
    }

    @Override
    public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        animate().scaleX(pressed ? 0.95f : 1f).scaleY(pressed ? 0.95f : 1f).setDuration(120).start();
    }
}
