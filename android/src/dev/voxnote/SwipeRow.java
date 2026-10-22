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
 * 提示贴在「卡片让开的那一侧」，而且只显示那一侧松手后真正会归入的分类：
 *   卡片右移（右滑）→ 左边露出 swipeRightCat；
 *   卡片左移（左滑）→ 右边露出 swipeLeftCat。
 * 名字随拖动从卡片底下露出来，看到什么就落到什么，不按手势名硬记左右。
 *
 * 手势判定放在 onInterceptTouchEvent：竖滑一律不拦（交给 ListView 滚动），
 * 横滑超过 8dp 且明显偏水平才接管；这样列表滚动手感不受影响。
 */
public class SwipeRow extends FrameLayout {

    public interface Host {
        void onOpen(RecordVm vm);

        void onTranscribe(RecordVm vm);

        void onSwipe(RecordVm vm, String targetCat);

        /** 卡片右上 ⋯：打开这张卡片的菜单。 */
        void onMore(RecordVm vm);

        /** 长按卡片：进批量管理（批量模式未开启时）。 */
        void onLongPress(RecordVm vm);

        /** 批量模式下点整行＝勾选/取消勾选。 */
        void onToggleSelect(RecordVm vm);
    }

    private final TextView leftEdgeLabel;   // 卡片右移时从左边露出：右滑目标
    private final TextView rightEdgeLabel;  // 卡片左移时从右边露出：左滑目标
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
    private boolean batch;

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

        card = new CardRow(c);
        card.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(card);

        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (host == null || vm == null) return;
                if (batch) host.onToggleSelect(vm);
                else host.onOpen(vm);
            }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                if (host == null || vm == null) return true;
                if (batch) host.onToggleSelect(vm);
                else host.onLongPress(vm);
                return true;
            }
        });
        card.setOnTranscribe(new View.OnClickListener() {
            public void onClick(View v) {
                if (host != null && vm != null) host.onTranscribe(vm);
            }
        });
        card.setOnMore(new View.OnClickListener() {
            public void onClick(View v) {
                if (host != null && vm != null) host.onMore(vm);
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
        leftEdgeLabel.setAlpha(0f);
        rightEdgeLabel.setAlpha(0f);
        dx = 0;
        dragging = false;

        // 按"露出的那一侧"取分类，而不是按手势名：卡片右移时露左边＝右滑目标，
        // 卡片左移时露右边＝左滑目标。两只提示各管一边，看到的就是会落到的。
        String toRight = settings.catName(settings.swipeRightCat);
        String toLeft = settings.catName(settings.swipeLeftCat);
        leftEdgeLabel.setText(toRight.length() > 0 ? toRight : "分类");
        rightEdgeLabel.setText(toLeft.length() > 0 ? toLeft : "分类");
        card.bind(vm, settings, vm.cat == null ? "" : settings.catName(vm.cat), sourceLabel);
    }

    /** 批量模式：整行点击变成勾选，滑动归类关掉，勾选圈代替 ⋯。 */
    public void setBatch(boolean on, boolean selected) {
        batch = on;
        card.setBatch(on, selected);
        if (on) {
            card.animate().cancel();
            card.setTranslationX(0);
            card.setAlpha(1f);
            leftEdgeLabel.setAlpha(0f);
            rightEdgeLabel.setAlpha(0f);
            dx = 0;
            dragging = false;
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (batch) return false;
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
        if (batch) return false;
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
                        if (host != null && vm != null) host.onSwipe(vm, cat);
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
