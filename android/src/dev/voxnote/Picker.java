package dev.voxnote;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 通用底部选择器（分类、滑动目标、按键、模型语言……），替代 H5 的 pickModal。 */
public class Picker implements Overlay {

    public interface OnPick {
        void onPick(int index);
    }

    public interface Host {
        void pushOverlay(Overlay o);

        void removeOverlay(Overlay o);
    }

    private final Host host;
    private final FrameLayout root;
    private final Activity act;
    private boolean open = true;

    private Picker(Activity act, Host host, FrameLayout root) {
        this.act = act;
        this.host = host;
        this.root = root;
    }

    public static void show(Activity act, Host host, String title, String[] labels,
                            int selected, final OnPick cb) {
        FrameLayout parent = (FrameLayout) act.findViewById(android.R.id.content);
        if (parent == null) return;

        final FrameLayout wrap = new FrameLayout(act);
        wrap.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final Picker picker = new Picker(act, host, wrap);
        View scrim = new View(act);
        scrim.setBackgroundColor(0x99000000);
        wrap.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = Ui.col(act);
        panel.setBackground(Ui.roundedTop(16, Ui.SURFACE, Ui.LINE, 1));
        Ui.pad(panel, 12, 14, 12, 16);

        TextView t = Ui.text(act, title, 11.5f, Ui.FAINT);
        t.setLetterSpacing(0.06f);
        Ui.pad(t, 3, 0, 3, 9);
        panel.addView(t);

        ScrollView sc = new ScrollView(act);
        sc.setVerticalScrollBarEnabled(false);
        LinearLayout opts = Ui.col(act);
        sc.addView(opts);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView b = Ui.text(act, labels[i], 12.5f, Ui.TEXT);
            boolean sel = i == selected;
            b.setBackground(sel
                    ? Ui.pressable(Ui.rounded(9, Ui.SURFACE3, Ui.LINE2, 1), 0x22FFFFFF)
                    : Ui.pressable(Ui.rounded(9, Ui.SURFACE2, Ui.LINE, 1), 0x22FFFFFF));
            b.setGravity(Gravity.CENTER_VERTICAL);
            Ui.pad(b, 12, 14, 12, 14);
            LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            p.bottomMargin = Ui.dp(6);
            opts.addView(b, p);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    picker.dismiss();
                    if (cb != null) cb.onPick(index);
                }
            });
        }
        int maxH = (int) (Ui.screenHeight() * 0.46f);
        int wanted = labels.length * Ui.dp(58) + Ui.dp(6);
        sc.setLayoutParams(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(maxH, wanted)));
        panel.addView(sc);

        TextView cancel = Ui.text(act, "取消", 12.5f, Ui.DIM);
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackground(Ui.pressable(Ui.rounded(9, Color.TRANSPARENT), 0x22FFFFFF));
        Ui.pad(cancel, 10, 10, 10, 10);
        LinearLayout.LayoutParams cp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = Ui.dp(3);
        panel.addView(cancel, cp);

        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                picker.dismiss();
            }
        });
        scrim.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                picker.dismiss();
            }
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        wrap.addView(panel, lp);
        panel.setTranslationY(Ui.dp(24));
        panel.setAlpha(0f);
        wrap.setAlpha(0f);
        parent.addView(wrap);
        wrap.animate().alpha(1f).setDuration(160).start();
        panel.animate().translationY(0).alpha(1f).setDuration(200)
                .setInterpolator(new DecelerateInterpolator()).start();
        if (host != null) host.pushOverlay(picker);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void dismiss() {
        if (!open) return;
        open = false;
        FrameLayout parent = (FrameLayout) root.getParent();
        if (parent == null) return;
        View panel = root.getChildAt(1);
        root.animate().alpha(0f).setDuration(150).start();
        if (panel != null) {
            ObjectAnimator.ofFloat(panel, "translationY", Ui.dp(24)).setDuration(150).start();
        }
        root.postDelayed(new Runnable() {
            public void run() {
                FrameLayout p = (FrameLayout) root.getParent();
                if (p != null) p.removeView(root);
                if (host != null) host.removeOverlay(Picker.this);
            }
        }, 160);
    }
}
