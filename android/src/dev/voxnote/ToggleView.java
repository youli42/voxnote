package dev.voxnote;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** 原型里的胶囊开关（40×23dp，白色表示开）。 */
public class ToggleView extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private boolean checked;
    private float t;
    private ValueAnimator anim;

    public ToggleView(Context c) {
        super(c);
        setClickable(true);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Ui.dp(1));
        setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                setChecked(!checked, true);
            }
        });
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean v, boolean animate) {
        checked = v;
        if (anim != null) anim.cancel();
        if (!animate) {
            t = v ? 1f : 0f;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(t, v ? 1f : 0f);
        anim.setDuration(200);
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
        setMeasuredDimension(resolveSize(Ui.dp(40), w), resolveSize(Ui.dp(23), h));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float rad = h / 2f;
        int bg = blend(Ui.SURFACE3, Ui.WHITE, t);
        int line = blend(Ui.LINE2, Ui.WHITE, t);
        track.setColor(bg);
        r.set(0, 0, w, h);
        canvas.drawRoundRect(r, rad, rad, track);
        border.setColor(line);
        r.set(border.getStrokeWidth() / 2f, border.getStrokeWidth() / 2f,
                w - border.getStrokeWidth() / 2f, h - border.getStrokeWidth() / 2f);
        canvas.drawRoundRect(r, rad, rad, border);

        float knobSize = Ui.dp(17);
        float x = Ui.dp(2) + t * (w - knobSize - Ui.dp(4));
        float y = (h - knobSize) / 2f;
        knob.setColor(blend(Ui.DIM, Ui.ON_WHITE, t));
        canvas.drawCircle(x + knobSize / 2f, y + knobSize / 2f, knobSize / 2f, knob);
    }

    private static int blend(int a, int b, float t) {
        int aa = (int) (android.graphics.Color.alpha(a) + (android.graphics.Color.alpha(b) - android.graphics.Color.alpha(a)) * t);
        int rr = (int) (android.graphics.Color.red(a) + (android.graphics.Color.red(b) - android.graphics.Color.red(a)) * t);
        int gg = (int) (android.graphics.Color.green(a) + (android.graphics.Color.green(b) - android.graphics.Color.green(a)) * t);
        int bb = (int) (android.graphics.Color.blue(a) + (android.graphics.Color.blue(b) - android.graphics.Color.blue(a)) * t);
        return android.graphics.Color.argb(aa, rr, gg, bb);
    }
}
