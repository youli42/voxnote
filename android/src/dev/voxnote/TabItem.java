package dev.voxnote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 底部导航的一格：20dp 线性图标 + 10sp 文字；选中变亮。 */
public class TabItem extends LinearLayout {

    public static final int ICON_RECORD = 0;
    public static final int ICON_SETTINGS = 1;

    private final Icon icon;
    private final TextView label;
    private boolean active;

    public TabItem(Context c, int iconType, String text) {
        super(c);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setClickable(true);
        Ui.pad(this, 0, 7, 0, 9);
        icon = new Icon(c, iconType);
        addView(icon, new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)));
        label = Ui.text(c, text, 10f, Ui.FAINT);
        label.setGravity(Gravity.CENTER);
        addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setActive(false);
    }

    public void setActive(boolean a) {
        active = a;
        icon.setColor(a ? Ui.TEXT : Ui.FAINT);
        label.setTextColor(a ? Ui.TEXT : Ui.FAINT);
    }

    public boolean isActive() {
        return active;
    }

    private static class Icon extends View {
        private final int type;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Icon(Context c, int type) {
            super(c);
            this.type = type;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(1.5f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(Ui.FAINT);
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(resolveSize(Ui.dp(20), w), resolveSize(Ui.dp(20), h));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) / 2f - paint.getStrokeWidth() / 2f;
            if (type == ICON_RECORD) {
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(cx, cy, r * 0.65f, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(cx, cy, r * 0.28f, paint);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * 2 * i / 8;
                    float x1 = (float) (cx + Math.cos(a) * r * 0.5f);
                    float y1 = (float) (cy + Math.sin(a) * r * 0.5f);
                    float x2 = (float) (cx + Math.cos(a) * r * 0.88f);
                    float y2 = (float) (cy + Math.sin(a) * r * 0.88f);
                    canvas.drawLine(x1, y1, x2, y2, paint);
                }
            }
        }
    }
}
