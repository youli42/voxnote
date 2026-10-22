package dev.voxnote;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * 列表卡片。一次建好控件，之后只改文字/可见性——滚动时不 new、不 inflate。
 * 与 H5 的 rowHTML 字段一一对应：标题、待处理原因、摘要、待办、关键词、元信息行。
 */
public class CardRow extends LinearLayout {

    private final TextView title;
    private final TextView reason;
    private final TextView more;
    private final TextView summary;
    private final LinearLayout todosBox;
    private final TextView todo1;
    private final TextView todo2;
    private final LinearLayout kwBox;
    private final TextView[] kw = new TextView[4];
    private final TextView date;
    private final TextView dur;
    private final TextView statusTag;
    private final TextView catTag;
    private final TextView marksTag;
    private final TextView modeTag;
    private final TextView sourceTag;
    private final TextView transcribe;
    private final TextView check;
    private boolean wantTranscribe;

    public CardRow(Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.TOP);
        setBackground(Ui.rounded(12, Ui.SURFACE, Ui.LINE, 1));
        Ui.pad(this, 10, 9, 10, 9);

        // 批量模式的勾选圈：只在批量模式显示，平时不占位。
        check = Ui.text(c, "✓", 10.5f, Ui.ON_WHITE);
        check.setGravity(Gravity.CENTER);
        check.setVisibility(GONE);
        LinearLayout.LayoutParams cp = Ui.lp(Ui.dp(20), Ui.dp(20));
        cp.rightMargin = Ui.dp(9);
        cp.topMargin = Ui.dp(1);
        addView(check, cp);

        LinearLayout main = Ui.col(c);
        main.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addView(main);

        LinearLayout top = Ui.row(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        title = Ui.text(c, "", 13.5f, Ui.TEXT);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        reason = Ui.text(c, "", 9.5f, Ui.DANGER);
        reason.setSingleLine(true);
        reason.setBackground(Ui.rounded(5, 0x0FC98B8B, Ui.DANGER_LINE, 1));
        Ui.pad(reason, 6, 1, 6, 1);
        top.addView(reason, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 6, 0, 0, 0));

        // 卡片菜单入口（打开详情 / 归类 / 删除）。批量模式下会被勾选框替换。
        more = Ui.text(c, "⋯", 14f, Ui.FAINT);
        more.setGravity(Gravity.CENTER);
        more.setClickable(true);
        Ui.pad(more, 8, 0, 0, 2);
        top.addView(more, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 2, 0, 0, 0));
        main.addView(top, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        summary = Ui.text(c, "", 11.5f, Ui.DIM);
        summary.setMaxLines(2);
        summary.setEllipsize(TextUtils.TruncateAt.END);
        summary.setLineSpacing(Ui.dp(1), 1f);
        main.addView(summary, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 3, 0, 0));

        todosBox = Ui.col(c);
        todo1 = todoText(c);
        todo2 = todoText(c);
        todosBox.addView(todo1);
        todosBox.addView(todo2, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 2, 0, 0));
        main.addView(todosBox, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));

        kwBox = Ui.row(c);
        for (int i = 0; i < kw.length; i++) {
            kw[i] = Ui.text(c, "", 9.5f, Ui.FAINT);
            kw[i].setSingleLine(true);
            kw[i].setBackground(Ui.rounded(5, 0x04FFFFFF, Ui.LINE, 1));
            Ui.pad(kw[i], 6, 1, 6, 1);
            LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.rightMargin = Ui.dp(4);
            kwBox.addView(kw[i], p);
        }
        main.addView(kwBox, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 5, 0, 0));

        LinearLayout meta = Ui.row(c);
        date = metaText(c);
        dur = metaText(c);
        statusTag = tagText(c);
        catTag = tagText(c);
        marksTag = tagText(c);
        modeTag = tagText(c);
        sourceTag = tagText(c);
        meta.addView(date);
        meta.addView(dur, metaLp());
        meta.addView(statusTag, metaLp());
        meta.addView(catTag, metaLp());
        meta.addView(marksTag, metaLp());
        meta.addView(modeTag, metaLp());
        meta.addView(sourceTag, metaLp());
        main.addView(meta, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 5, 0, 0));

        transcribe = Ui.smallButton(c, "转写");
        transcribe.setVisibility(GONE);
        LinearLayout.LayoutParams tp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = Ui.dp(8);
        tp.topMargin = Ui.dp(2);
        addView(transcribe, tp);
    }

    private static TextView todoText(Context c) {
        TextView t = Ui.text(c, "", 10.5f, Ui.FAINT);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private static TextView metaText(Context c) {
        TextView t = Ui.text(c, "", 10.5f, Ui.FAINT);
        t.setSingleLine(true);
        return t;
    }

    private static LinearLayout.LayoutParams metaLp() {
        LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.leftMargin = Ui.dp(5);
        return p;
    }

    private static TextView tagText(Context c) {
        TextView t = Ui.text(c, "", 9.5f, Ui.FAINT);
        t.setSingleLine(true);
        Ui.pad(t, 5, 0, 5, 0);
        return t;
    }

    private static void styleTag(TextView t, boolean on) {
        t.setTextColor(on ? Ui.DIM : Ui.FAINT);
        t.setBackground(on
                ? Ui.rounded(4, 0x08FFFFFF, Ui.LINE2, 1)
                : Ui.rounded(4, 0x04FFFFFF, Ui.LINE, 1));
    }

    public void setOnTranscribe(View.OnClickListener l) {
        transcribe.setOnClickListener(l);
    }

    public void setOnMore(View.OnClickListener l) {
        more.setOnClickListener(l);
    }

    public void bind(RecordVm vm, UiSettings s, String catName, String sourceLabel) {
        title.setText(vm.title.length() == 0 ? "未命名录音" : vm.title);
        String why = vm.reason();
        if (why.length() > 0) {
            reason.setText(why);
            reason.setVisibility(VISIBLE);
        } else {
            reason.setVisibility(GONE);
        }

        boolean showSummary = s.showFirstLine && vm.showSummary();
        if (showSummary) {
            summary.setText(vm.summary);
            summary.setVisibility(VISIBLE);
        } else {
            summary.setVisibility(GONE);
        }

        ArrayList<String> todos = showSummary ? vm.todos() : new ArrayList<String>();
        todo1.setText(todos.size() > 0 ? "□ " + todos.get(0) : "");
        todo2.setText(todos.size() > 1 ? "□ " + todos.get(1) : "");
        todo1.setVisibility(todos.size() > 0 ? VISIBLE : GONE);
        todo2.setVisibility(todos.size() > 1 ? VISIBLE : GONE);
        todosBox.setVisibility(todos.isEmpty() ? GONE : VISIBLE);

        ArrayList<String> kws = showSummary ? vm.keywords() : new ArrayList<String>();
        int kn = Math.min(kw.length, kws.size());
        for (int i = 0; i < kw.length; i++) {
            if (i < kn) {
                kw[i].setText(kws.get(i));
                kw[i].setVisibility(VISIBLE);
            } else {
                kw[i].setVisibility(GONE);
            }
        }
        kwBox.setVisibility(kn > 0 ? VISIBLE : GONE);

        date.setText(vm.dateText());
        dur.setText(vm.fmtDur());

        boolean showStatus = why.length() == 0;
        statusTag.setVisibility(showStatus ? VISIBLE : GONE);
        if (showStatus) {
            statusTag.setText(vm.statusLabel());
            styleTag(statusTag, "done".equals(vm.status()));
        }

        boolean hasCat = vm.cat != null && catName != null && catName.length() > 0;
        catTag.setVisibility(hasCat ? VISIBLE : GONE);
        if (hasCat) {
            catTag.setText(catName);
            styleTag(catTag, true);
        }

        boolean hasMarks = vm.marks != null && vm.marks.size() > 0;
        marksTag.setVisibility(hasMarks ? VISIBLE : GONE);
        if (hasMarks) {
            marksTag.setText(vm.marks.size() + " 标记");
            styleTag(marksTag, true);
        }

        String mode = vm.modeLabel();
        modeTag.setVisibility(mode.length() > 0 ? VISIBLE : GONE);
        if (mode.length() > 0) {
            modeTag.setText(mode);
            styleTag(modeTag, false);
        }

        String src = null;
        if (vm.fromSource != null && sourceLabel != null) src = sourceLabel;
        else if (!vm.hasAudio()) src = "仅文稿";
        sourceTag.setVisibility(src != null ? VISIBLE : GONE);
        if (src != null) {
            sourceTag.setText(src);
            styleTag(sourceTag, true);
        }

        wantTranscribe = "raw".equals(vm.status()) && vm.hasAudio();
        transcribe.setVisibility(wantTranscribe ? VISIBLE : GONE);
    }

    /**
     * 批量模式：用左侧勾选圈替换 ⋯，并让"转写"按钮让位（整行点击＝勾选）。
     * 选中态用白底黑勾，未选是描边空心圈。
     */
    public void setBatch(boolean on, boolean selected) {
        check.setVisibility(on ? VISIBLE : GONE);
        more.setVisibility(on ? GONE : VISIBLE);
        check.setBackground(selected
                ? Ui.rounded(10, Ui.WHITE)
                : Ui.rounded(10, 0x00000000, Ui.LINE2, 1.5f));
        check.setTextColor(selected ? Ui.ON_WHITE : 0x00000000);
        transcribe.setVisibility(wantTranscribe && !on ? VISIBLE : GONE);
    }
}
