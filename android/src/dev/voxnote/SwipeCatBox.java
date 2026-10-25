package dev.voxnote;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 左/右滑归类的通用容器（SwipeRow 抽掉 CardRow 后的通用版）：
 * 底层是动作提示，上面是调用方塞进来的任意卡片。
 * 手势判定、阈值、提示动画与 SwipeRow 保持一致——列表页和流页手感相同。
 *
 * 提示贴在「卡片让开的那一侧」：卡片右移（右滑）→ 左边露出 swipeRightCat，
 * 卡片左移（左滑）→ 右边露出 swipeLeftCat。看到什么就落到什么。
 * 归类/取消的语义由回调宿主决定（与列表页 onSwipe 同一套）。
 */
public class SwipeCatBox extends FrameLayout {

    public interface Callback {
        void onSwipeCat(String targetCat);
    }

    private final TextView leftEdgeLabel;   // 卡片右移时从左边露出：右滑目标
    private final TextView rightEdgeLabel;  // 卡片左移时从右边露出：左滑目标
    final View card;                        // 包内可见：宿主按内容找卡片用
    private final UiSettings settings;
    private final Callback cb;
    private final int slop;
    private final int threshold;

    private float downRawX;
    private float downRawY;
    private float dx;
    private boolean dragging;

    public SwipeCatBox(Context c, UiSettings s, View content, Callback callback) {
        super(c);
        settings = s;
        cb = callback;
        slop = Math.max(ViewConfiguration.get(c).getScaledTouchSlop(), Ui.dp(8));
        threshold = Ui.dp(64);

        View actionBg = new View(c);
        actionBg.setBackground(Ui.rounded(12, Ui.SURFACE3));
        addView(actionBg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        leftEdgeLabel = actionLabel(c, Gravity.START | Gravity.CENTER_VERTICAL);
        leftEdgeLabel.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START | Gravity.CENTER_VERTICAL));
        Ui.pad(leftEdgeLabel, 18, 0, 0, 0);
        addView(leftEdgeLabel);

        rightEdgeLabel = actionLabel(c, Gravity.END | Gravity.CENTER_VERTICAL);
        rightEdgeLabel.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END | Gravity.CENTER_VERTICAL));
        Ui.pad(rightEdgeLabel, 0, 0, 18, 0);
        addView(rightEdgeLabel);

        card = content;
        card.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(card);
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

    /** 分类名可能被改过，每次数据刷新后重取两侧提示文案。 */
    public void refreshLabels() {
        String toRight = settings.catName(settings.swipeRightCat);
        String toLeft = settings.catName(settings.swipeLeftCat);
        leftEdgeLabel.setText(toRight.length() > 0 ? toRight : "分类");
        rightEdgeLabel.setText(toLeft.length() > 0 ? toLeft : "分类");
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
            leftEdgeLabel.setAlpha(a);
            rightEdgeLabel.setAlpha(0f);
        } else if (d < 0) {
            leftEdgeLabel.setAlpha(0f);
            rightEdgeLabel.setAlpha(a);
        } else {
            leftEdgeLabel.setAlpha(0f);
            rightEdgeLabel.setAlpha(0f);
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
                        leftEdgeLabel.setAlpha(0f);
                        rightEdgeLabel.setAlpha(0f);
                        dx = 0;
                        if (cb != null) cb.onSwipeCat(cat);
                    }
                }).start();
    }

    private void reset() {
        card.animate().translationX(0).alpha(1f).setDuration(180)
                .setInterpolator(new DecelerateInterpolator()).start();
        leftEdgeLabel.animate().alpha(0f).setDuration(120).start();
        rightEdgeLabel.animate().alpha(0f).setDuration(120).start();
        dx = 0;
    }
}
