package dev.voxnote;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 原生 UI 的调色板与积木：颜色、dp/sp、圆角背景、常用控件构造。
 *
 * 颜色取自原型 CSS 的 :root（深色），圆角/边框也按原型；
 * 但刻意不用阴影/渐变——Mali-T820 上这两样在 WebView 里已经证明会掉帧，
 * 原生这边也保持扁平，性能预算留给列表和转写。
 */
public final class Ui {

    public static final int BG = 0xFF0E0F12;
    public static final int BG_DEEP = 0xFF07080A;
    public static final int SURFACE = 0xFF181A1E;
    public static final int SURFACE2 = 0xFF20232A;
    public static final int SURFACE3 = 0xFF282C34;
    public static final int LINE = 0xFF2A2D34;
    public static final int LINE2 = 0xFF353941;
    public static final int TEXT = 0xFFE7E8EA;
    public static final int DIM = 0xFF8F939C;
    public static final int FAINT = 0xFF5B5F68;
    public static final int WHITE = 0xFFF2F3F5;
    public static final int ON_WHITE = 0xFF15171B;
    public static final int DANGER = 0xFFC98B8B;
    public static final int DANGER_LINE = 0xFF4A3F3F;
    public static final int ACCENT_LINE = 0xFF5A5F69;

    private Ui() { }

    public static float density() {
        return ResourcesHolder.density;
    }

    public static int dp(float v) {
        return Math.round(v * density());
    }

    public static float screenWidth() {
        return ResourcesHolder.width;
    }

    public static float screenHeight() {
        return ResourcesHolder.height;
    }

    private static final class ResourcesHolder {
        static final float density = android.content.res.Resources.getSystem().getDisplayMetrics().density;
        static final float width = android.content.res.Resources.getSystem().getDisplayMetrics().widthPixels;
        static final float height = android.content.res.Resources.getSystem().getDisplayMetrics().heightPixels;
    }

    // ------------------------------------------------------------ 背景

    public static GradientDrawable rounded(float radius, int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(radius));
        d.setColor(fill);
        return d;
    }

    public static GradientDrawable rounded(float radius, int fill, int stroke, float strokeW) {
        GradientDrawable d = rounded(radius, fill);
        d.setStroke(Math.max(1, dp(strokeW)), stroke);
        return d;
    }

    public static GradientDrawable roundedTop(float radius, int fill, int stroke, float strokeW) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        float r = dp(radius);
        d.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        d.setColor(fill);
        if (strokeW > 0) d.setStroke(Math.max(1, dp(strokeW)), stroke);
        return d;
    }

    /** 带按压反馈的背景（Ripple 在 API 27 上可用，比 selector 省文件）。 */
    public static Drawable pressable(Drawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    public static void setPressBg(View v, float radius, int fill, int stroke, float strokeW) {
        GradientDrawable base = strokeW > 0 ? rounded(radius, fill, stroke, strokeW) : rounded(radius, fill);
        v.setBackground(pressable(base, 0x22FFFFFF));
    }

    // ------------------------------------------------------------ 布局

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    public static LinearLayout.LayoutParams lpm(int w, int h, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(left, top, right, bottom);
        return p;
    }

    public static void pad(View v, float l, float t, float r, float b) {
        v.setPadding(dp(l), dp(t), dp(r), dp(b));
    }

    public static void margin(View v, float l, float t, float r, float b) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        if (p instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) p).setMargins(dp(l), dp(t), dp(r), dp(b));
            v.setLayoutParams(p);
        }
    }

    // ------------------------------------------------------------ 文本

    public static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setBackground(null);
        t.setIncludeFontPadding(false);
        return t;
    }

    public static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = text(c, s, sp, color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static TextView label(Context c, CharSequence s, float sp) {
        return text(c, s, sp, DIM);
    }

    /** 小标签（tag/kw），带描边圆角，背景透明。 */
    public static TextView tag(Context c, float sp, int color, int stroke) {
        TextView t = text(c, "", sp, color);
        t.setBackground(rounded(4, 0x00000000, stroke, 1));
        pad(t, 5, 0, 5, 0);
        t.setSingleLine(true);
        return t;
    }

    public static TextView chip(Context c, CharSequence s, float sp) {
        TextView t = text(c, s, sp, FAINT);
        t.setBackground(pressable(rounded(14, 0x00000000, LINE, 1), 0x22FFFFFF));
        pad(t, 11, 5, 11, 5);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    public static TextView button(Context c, CharSequence s, float sp, boolean primary) {
        TextView t = text(c, s, sp, primary ? ON_WHITE : TEXT, primary);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        if (primary) {
            t.setBackground(pressable(rounded(8, WHITE, WHITE, 1), 0x33000000));
        } else {
            t.setBackground(pressable(rounded(8, SURFACE2, LINE2, 1), 0x22FFFFFF));
        }
        pad(t, 12, 7, 12, 7);
        return t;
    }

    public static TextView smallButton(Context c, CharSequence s) {
        TextView t = button(c, s, 11.5f, false);
        pad(t, 9, 6, 9, 6);
        return t;
    }

    public static EditText input(Context c, String hint, float sp) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(FAINT);
        e.setTextColor(TEXT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        e.setBackground(rounded(8, SURFACE2, LINE, 1));
        pad(e, 10, 7, 10, 7);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        return e;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(lp(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return v;
    }

    /**
     * 底部 tab 栏：横向 LinearLayout，两半各放一个 TabItem（weight 1）。
     * 自己只在正中间画一条竖向虚线做分界——只画线、不拦触摸，
     * 点落在哪一半就由那一格的 TabItem 响应，整半区域都生效。
     */
    public static class TabBar extends LinearLayout {
        private final Paint dash;

        public TabBar(Context c, int lineColor) {
            super(c);
            setOrientation(HORIZONTAL);
            setBackgroundColor(BG);
            dash = new Paint(Paint.ANTI_ALIAS_FLAG);
            dash.setColor(lineColor);
            dash.setStyle(Paint.Style.STROKE);
            dash.setStrokeWidth(Math.max(1, dp(1)));
            dash.setPathEffect(new DashPathEffect(new float[]{dp(4), dp(4)}, 0));
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            float x = getWidth() / 2f;
            float pad = dp(7);
            canvas.drawLine(x, pad, x, getHeight() - pad, dash);
        }
    }

    public static View spacer(Context c, int heightDp) {
        View v = new View(c);
        v.setLayoutParams(lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)));
        return v;
    }

    /** 行（设置页用）：左标签 + 右侧值/控件。 */
    public static LinearLayout settingRow(Context c, String label, String desc) {
        LinearLayout r = row(c);
        pad(r, 0, 8, 0, 8);
        LinearLayout main = col(c);
        main.setLayoutParams(lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        main.addView(text(c, label, 12.5f, TEXT));
        if (desc != null) {
            TextView d = text(c, desc, 10.5f, FAINT);
            d.setLineSpacing(dp(1), 1f);
            main.addView(d, lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 2, 0, 0));
        }
        r.addView(main);
        return r;
    }

    public static LinearLayout group(Context c, String title) {
        LinearLayout g = col(c);
        g.setBackground(rounded(14, SURFACE, LINE, 1));
        pad(g, 10, 2, 10, 2);
        if (title != null) {
            TextView t = text(c, title, 10.5f, FAINT);
            t.setLetterSpacing(0.08f);
            g.addView(t, lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 2, 9, 0, 6));
        }
        return g;
    }

    public static void setVisible(View v, boolean visible) {
        if (v != null) v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public static int alpha(int color, float a) {
        return Color.argb(Math.round(255 * Math.min(1f, Math.max(0f, a))),
                Color.red(color), Color.green(color), Color.blue(color));
    }
}
