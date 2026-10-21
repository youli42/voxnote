package dev.voxnote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.Random;

/** 录音波形：原型里 38 根小竖条，高度随实时电平抖动。 */
public class WaveView extends View {

    private static final int BARS = 38;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] shape = new float[BARS];
    private final float[] heights = new float[BARS];
    private final Random random = new Random();
    private boolean active;
    private float level;

    public WaveView(Context c) {
        super(c);
        paint.setColor(Ui.LINE2);
        for (int i = 0; i < BARS; i++) {
            shape[i] = (float) (0.3 + 0.7 * Math.abs(Math.sin(i * 0.55 + 0.8)));
            heights[i] = 1;
        }
    }

    public void setActive(boolean active) {
        this.active = active;
        if (!active) {
            level = 0;
            for (int i = 0; i < BARS; i++) heights[i] = 1;
        }
        paint.setColor(active ? 0xFF6D727C : Ui.LINE2);
        invalidate();
    }

    public void setLevel(float level) {
        this.level = Math.max(0f, Math.min(1f, level));
        for (int i = 0; i < BARS; i++) {
            heights[i] = (float) (1 + this.level * shape[i] * (0.85 + random.nextFloat() * 0.3));
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(resolveSize(Ui.dp(200), w), Ui.dp(24));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float barW = Ui.dp(3);
        float gap = Ui.dp(2);
        float total = BARS * barW + (BARS - 1) * gap;
        float x = (getWidth() - total) / 2f;
        float cy = getHeight() / 2f;
        float maxH = Ui.dp(24);
        for (int i = 0; i < BARS; i++) {
            float bh = Math.max(Ui.dp(3), heights[i] * maxH / 1.6f);
            bh = Math.min(maxH, bh);
            float top = cy - bh / 2f;
            canvas.drawRoundRect(x, top, x + barW, top + bh, barW / 2f, barW / 2f, paint);
            x += barW + gap;
        }
    }
}
