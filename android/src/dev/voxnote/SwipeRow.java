package dev.voxnote;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 左/右滑分类的容器：底层是动作提示，上面是卡片。
 *
 * 手势判定放在 onInterceptTouchEvent：竖滑一律不拦（交给 ListView 滚动），
 * 横滑超过 8dp 且明显偏水平才接管；这样列表滚动手感不受影响。
 */
public class SwipeRow extends FrameLayout {

    public interface Host {
        void onOpen(RecordVm vm);

        void onTranscribe(RecordVm vm);

        void onSwipe(RecordVm vm, String targetCat);
    }

    private final TextView leftLabel;
    private final TextView rightLabel;
    private final CardRow card;
    private final int slop;
    private final int threshold;

    private RecordVm vm;
    private Host host;
    private UiSettings settings;
    private float downRawX;
    private float downRawY;
    private float dx;
    private boolean dragging;

    public SwipeRow(Context c) {
        super(c);
        // 行间距靠 padding：ListView 的 LayoutParams 不是 MarginLayoutParams，设 margin 无效。
        setPadding(0, 0, 0, Ui.dp(6));
        slop = Math.max(ViewConfiguration.get(c).getScaledTouchSlop(), Ui.dp(8));
        threshold = Ui.dp(64);

        View actionBg = new View(c);
        actionBg.setBackground(Ui.rounded(12, Ui.SURFACE3));
        addView(actionBg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        leftLabel = actionLabel(c, Gravity.START | Gravity.CENTER_VERTICAL);
        leftLabel.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START | Gravity.CENTER_VERTICAL));
        Ui.pad(leftLabel, 18, 0, 0, 0);
        addView(leftLabel);

        rightLabel = actionLabel(c, Gravity.END | Gravity.CENTER_VERTICAL);
        rightLabel.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END | Gravity.CENTER_VERTICAL));
        Ui.pad(rightLabel, 0, 0, 18, 0);
        addView(rightLabel);

        card = new CardRow(c);
        card.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(card);

        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (host != null && vm != null) host.onOpen(vm);
            }
        });
        card.setOnTranscribe(new View.OnClickListener() {
            public void onClick(View v) {
                if (host != null && vm != null) host.onTranscribe(vm);
            }
        });
    }

    private static TextView actionLabel(Context c, int gravity) {
        TextView t = Ui.text(c, "", 11.5f, Ui.DIM);
        t.setGravity(gravity);
        t.setSingleLine(true);
        t.setLetterSpacing(0.12f);
        t.setAlpha(0f);
        // 底层提示不该吃掉触摸
        t.setClickable(false);
        t.setFocusable(false);
        return t;
    }

    public void bind(RecordVm vm, UiSettings settings, Host host, String sourceLabel) {
        this.vm = vm;
        this.settings = settings;
        this.host = host;
        card.animate().cancel();
        card.setTranslationX(0);
        card.setAlpha(1f);
        leftLabel.setAlpha(0f);
        rightLabel.setAlpha(0f);
        dx = 0;
        dragging = false;

        String l = settings.catName(settings.swipeLeftCat);
        String r = settings.catName(settings.swipeRightCat);
        leftLabel.setText(l.length() > 0 ? l : "分类");
        rightLabel.setText(r.length() > 0 ? r : "分类");
        card.bind(vm, settings, vm.cat == null ? "" : settings.catName(vm.cat), sourceLabel);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = ev.getRawX();
                downRawY = ev.getRawY();
                dx = 0;
                dragging = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                float mx = ev.getRawX() - downRawX;
                float my = ev.getRawY() - downRawY;
                if (!dragging && Math.abs(mx) > slop && Math.abs(mx) > Math.abs(my) * 1.1f) {
                    dragging = true;
                    dx = mx;
                    ViewGroup parent = (ViewGroup) getParent();
                    if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                    card.setPressed(false);
                    card.animate().cancel();
                    return true;
                }
                return false;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = ev.getRawX();
                downRawY = ev.getRawY();
                return true;
            case MotionEvent.ACTION_MOVE:
                dx = ev.getRawX() - downRawX;
                applyDrag(dx);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) settle();
                dragging = false;
                ViewGroup parent = (ViewGroup) getParent();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
                return true;
        }
        return true;
    }

    private void applyDrag(float d) {
        float limit = getWidth() * 0.9f;
        if (limit <= 0) limit = Ui.dp(300);
        if (d > limit) d = limit;
        if (d < -limit) d = -limit;
        card.setTranslationX(d);
        float a = Math.min(1f, Math.abs(d) / Ui.dp(80));
        if (d > 0) {
            leftLabel.setAlpha(a);
            rightLabel.setAlpha(0f);
        } else if (d < 0) {
            leftLabel.setAlpha(0f);
            rightLabel.setAlpha(a);
        } else {
            leftLabel.setAlpha(0f);
            rightLabel.setAlpha(0f);
        }
    }

    private void settle() {
        if (dx > threshold) {
            commit(settings.swipeRightCat, 1);
        } else if (dx < -threshold) {
            commit(settings.swipeLeftCat, -1);
        } else {
            reset();
        }
    }

    private void commit(final String targetCat, int dir) {
        if (targetCat == null || settings.find(targetCat) == null) {
            reset();
            return;
        }
        final String cat = targetCat;
        float to = dir > 0 ? getWidth() : -getWidth();
        card.animate().translationX(to).alpha(0.2f).setDuration(180)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(new Runnable() {
                    public void run() {
                        card.setTranslationX(0);
                        card.setAlpha(1f);
                        leftLabel.setAlpha(0f);
                        rightLabel.setAlpha(0f);
                        dx = 0;
                        if (host != null && vm != null) host.onSwipe(vm, cat);
                    }
                }).start();
    }

    private void reset() {
        card.animate().translationX(0).alpha(1f).setDuration(180)
                .setInterpolator(new DecelerateInterpolator()).start();
        leftLabel.animate().alpha(0f).setDuration(120).start();
        rightLabel.animate().alpha(0f).setDuration(120).start();
        dx = 0;
    }
}
