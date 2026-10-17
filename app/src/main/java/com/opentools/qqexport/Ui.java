package com.opentools.qqexport;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 界面基础设施（对齐 TCQT 的观感：卡片分组 + 行内开关 + 深色自适应）。
 *
 * 刻意不依赖 AndroidX / Material：模块宿主在 QQ 进程里，多引一个库就多一份
 * 类加载与主题依赖的风险，所以全部用 framework 控件 + GradientDrawable 拼。
 *
 * 配色是一套语义色，深色/浅色各一份，控件里只取语义名，不再散落魔数。
 */
public final class Ui {

    private static final int L_BG = 0xFFF4F6FA;
    private static final int L_CARD = 0xFFFFFFFF;
    private static final int L_CARD2 = 0xFFEFF2F7;
    private static final int L_TEXT = 0xFF1A1B1E;
    private static final int L_SUB = 0xFF7A7E8A;
    private static final int L_LINE = 0xFFE9ECF2;

    private static final int D_BG = 0xFF121316;
    private static final int D_CARD = 0xFF1C1D22;
    private static final int D_CARD2 = 0xFF26272E;
    private static final int D_TEXT = 0xFFE8E9ED;
    private static final int D_SUB = 0xFF9AA0AC;
    private static final int D_LINE = 0xFF2B2D34;

    public static final int ACCENT = 0xFF3B7CF6;
    public static final int DANGER = 0xFFE5484D;

    private Ui() {
    }

    public static boolean dark(Context c) {
        try {
            return (c.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    public static int colorBg(Context c)     { return dark(c) ? D_BG : L_BG; }
    public static int colorCard(Context c)   { return dark(c) ? D_CARD : L_CARD; }
    public static int colorCard2(Context c)  { return dark(c) ? D_CARD2 : L_CARD2; }
    public static int colorText(Context c)   { return dark(c) ? D_TEXT : L_TEXT; }
    public static int colorSub(Context c)    { return dark(c) ? D_SUB : L_SUB; }
    public static int colorLine(Context c)   { return dark(c) ? D_LINE : L_LINE; }

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable strokeRound(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable d = round(c, fill, radiusDp);
        d.setStroke(dp(c, 1), stroke);
        return d;
    }

    public static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setLineSpacing(dp(c, 2), 1f);
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        return tv;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    /** 一张卡片：圆角 + 竖向排列，自身不给 padding，交给行来给，方便画分割线 */
    public static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setBackground(round(c, colorCard(c), 16));
        l.setClipToOutline(true);
        return l;
    }

    public static void addCard(LinearLayout parent, LinearLayout card) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(parent.getContext(), 10);
        parent.addView(card, lp);
    }

    public static TextView section(Context c, String s) {
        TextView tv = text(c, s, 12, ACCENT, true);
        tv.setLetterSpacing(0.06f);
        tv.setPadding(dp(c, 6), dp(c, 14), 0, dp(c, 6));
        return tv;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(colorLine(c));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.7f)));
        lp.leftMargin = dp(c, 14);
        lp.rightMargin = dp(c, 14);
        v.setLayoutParams(lp);
        return v;
    }

    /** 开关行：左侧标题+说明，右侧 Switch */
    public static View switchRow(Context c, String title, String sub, boolean checked,
                                 CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(c, 14);
        row.setPadding(p, dp(c, 12), p, dp(c, 12));

        LinearLayout col = column(c);
        col.addView(text(c, title, 15, colorText(c), false));
        if (sub != null && sub.length() > 0) {
            TextView s = text(c, sub, 11, colorSub(c), false);
            s.setPadding(0, dp(c, 2), dp(c, 6), 0);
            col.addView(s);
        }
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(c);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(l);
        row.addView(sw);
        return row;
    }

    /** 普通文本行（标题 + 说明 + 右侧可选附加视图） */
    public static View textRow(Context c, String title, String sub, View right) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(c, 14);
        row.setPadding(p, dp(c, 12), p, dp(c, 12));

        LinearLayout col = column(c);
        col.addView(text(c, title, 15, colorText(c), false));
        if (sub != null && sub.length() > 0) {
            TextView s = text(c, sub, 11, colorSub(c), false);
            s.setPadding(0, dp(c, 2), 0, 0);
            col.addView(s);
        }
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (right != null) row.addView(right);
        return row;
    }

    /** 小胶囊标签 */
    public static TextView chip(Context c, String s, int color) {
        TextView tv = text(c, s, 11, color, true);
        tv.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3));
        GradientDrawable d = round(c, color & 0x18FFFFFF | 0x08000000, 8);
        d.setColor((color & 0x00FFFFFF) | 0x1A000000);
        tv.setBackground(d);
        return tv;
    }

    /** 按钮：primary 用亮色实心，否则用浅底 */
    public static TextView button(Context c, String s, boolean primary, View.OnClickListener l) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(c, 16), dp(c, 11), dp(c, 16), dp(c, 11));
        if (primary) {
            tv.setTextColor(Color.WHITE);
            tv.setBackground(round(c, ACCENT, 12));
        } else {
            tv.setTextColor(colorText(c));
            tv.setBackground(round(c, colorCard2(c), 12));
        }
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(l);
        return tv;
    }

    /** 弹窗内容根布局（自身带卡片底色与圆角，用于 AlertDialog.setView） */
    public static LinearLayout dialogRoot(Context c) {
        LinearLayout l = column(c);
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        l.setBackground(round(c, colorCard(c), 20));
        return l;
    }

    /** 让弹窗窗口背景透明，露出我们自己的圆角卡片 */
    public static void transparentDialog(Dialog d, Context c) {
        try {
            if (d.getWindow() != null) {
                d.getWindow().setBackgroundDrawable(round(c, 0x00000000, 20));
                d.getWindow().setDimAmount(0.5f);
            }
        } catch (Throwable ignored) {
        }
    }
}
