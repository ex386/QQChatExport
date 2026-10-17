package com.opentools.qqexport;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * HyperOS / MIUIX 的分段控件（segmented control）：浅色圆角容器 + 选中项白色浮起圆角块。
 *
 * 替代原来的 RadioGroup + RadioButton（系统单选圆点在 QQ 主题下是 4.x 的绿色/灰色圆环）。
 */
public final class MiSegmented extends LinearLayout {

    public interface OnPick {
        void onPick(int index);
    }

    private final Context c;
    private final TextView[] tabs;
    private int selected = -1;
    private OnPick onPick;

    public MiSegmented(Context ctx, String[] items) {
        super(ctx);
        c = ctx;
        setOrientation(HORIZONTAL);
        int pad = Ui.dp(ctx, 3);
        setPadding(pad, pad, pad, pad);
        setBackground(Ui.round(ctx, Ui.colorFill(ctx), 14));

        tabs = new TextView[items.length];
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView t = new TextView(ctx);
            t.setText(items[i]);
            t.setTextSize(13);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8));
            t.setOnClickListener(v -> select(idx));
            addView(t, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tabs[i] = t;
        }
        select(0);
    }

    public void setOnPick(OnPick p) {
        onPick = p;
    }

    /** 不触发回调的选中（初始化用） */
    public void selectSilent(int i) {
        apply(i);
    }

    public void select(int i) {
        if (selected == i) {
            apply(i);
            return;
        }
        apply(i);
        if (onPick != null) onPick.onPick(i);
    }

    public int selected() {
        return selected;
    }

    private void apply(int i) {
        selected = i;
        for (int k = 0; k < tabs.length; k++) style(tabs[k], k == i);
    }

    private void style(TextView t, boolean on) {
        if (on) {
            t.setTextColor(Ui.colorText(c));
            t.setTypeface(null, Typeface.BOLD);
            GradientDrawable g = Ui.round(c, Ui.colorCard(c), 11);
            t.setBackground(g);
            t.setElevation(Ui.dp(c, 1));
        } else {
            t.setTextColor(Ui.colorSub(c));
            t.setTypeface(null, Typeface.NORMAL);
            t.setBackground(null);
            t.setElevation(0f);
        }
    }
}
