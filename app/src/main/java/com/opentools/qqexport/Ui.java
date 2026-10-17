package com.opentools.qqexport;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 界面基础设施（MIUIX / HyperOS 观感）。
 *
 * 设计基准：
 * - 大圆角：弹窗 28dp，卡片 18dp，输入/取值块 10dp，按钮胶囊 23dp；
 * - 弱分隔线：8% 黑（深色下 12% 白），左右缩进 14dp；
 * - 文字层级：标题 20/22sp 加粗，行标题 15sp，副文案 12sp，说明 11sp；
 * - 强调色 #3482FF（HyperOS 蓝）；填充色 = 5% 黑（容器）/ 白（浮起块）。
 *
 * 刻意不依赖 AndroidX / Material：模块宿主在 QQ 进程里，多引一个库就多一份类加载
 * 与主题依赖的风险；而且系统控件的样式全部取自宿主主题（QQ 的主题偏旧），这正是
 * 之前“安卓 4.4 风”的根源。所以所有控件自绘：{@link MiSwitch}、{@link MiCheck}、
 * {@link MiSegmented}。
 */
public final class Ui {

    private static final int L_BG = 0xFFF2F3F5;
    private static final int L_CARD = 0xFFFFFFFF;
    private static final int L_FILL = 0x0D000000;
    private static final int L_TEXT = 0xE6000000;
    private static final int L_SUB = 0x99000000;
    private static final int L_LINE = 0x14000000;

    private static final int D_BG = 0xFF000000;
    private static final int D_CARD = 0xFF1C1C1E;
    private static final int D_FILL = 0x14FFFFFF;
    private static final int D_TEXT = 0xE6FFFFFF;
    private static final int D_SUB = 0x80FFFFFF;
    private static final int D_LINE = 0x1FFFFFFF;

    public static final int ACCENT = 0xFF3482FF;
    public static final int DANGER = 0xFFE5484D;

    /** 开关回调：单参数，和自绘开关保持一致 */
    public interface OnToggle {
        void onToggle(boolean checked);
    }

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
    public static int colorFill(Context c)   { return dark(c) ? D_FILL : L_FILL; }
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
        if (bold) tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        return tv;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    /** 卡片：白底大圆角，自身不给 padding，交给行来给，方便画分割线 */
    public static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setBackground(round(c, colorCard(c), 18));
        l.setClipToOutline(true);
        return l;
    }

    /** 内容块：5% 填充圆角容器，弹窗里的分组用它 */
    public static LinearLayout panel(Context c) {
        LinearLayout l = column(c);
        l.setBackground(round(c, colorFill(c), 16));
        l.setClipToOutline(true);
        return l;
    }

    public static void addCard(LinearLayout parent, LinearLayout card) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(parent.getContext(), 10);
        parent.addView(card, lp);
    }

    /** 分组小标题（灰色 13sp，不要蓝色，HyperOS 的分组头是弱化的） */
    public static TextView section(Context c, String s) {
        TextView tv = text(c, s, 13, colorSub(c), false);
        tv.setPadding(dp(c, 8), dp(c, 14), 0, dp(c, 6));
        return tv;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(colorLine(c));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.5f)));
        lp.leftMargin = dp(c, 14);
        lp.rightMargin = dp(c, 14);
        v.setLayoutParams(lp);
        return v;
    }

    /** 开关行：左侧标题+说明，右侧自绘开关 */
    public static View switchRow(Context c, String title, String sub, boolean checked,
                                 final OnToggle l) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(c, 14);
        row.setPadding(p, dp(c, 11), p, dp(c, 11));

        LinearLayout col = column(c);
        col.addView(text(c, title, 15, colorText(c), false));
        if (sub != null && sub.length() > 0) {
            TextView s = text(c, sub, 11, colorSub(c), false);
            s.setPadding(0, dp(c, 3), dp(c, 8), 0);
            col.addView(s);
        }
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        MiSwitch sw = new MiSwitch(c);
        sw.setCheckedSilent(checked);
        sw.setOnChange(b -> {
            if (l != null) l.onToggle(b);
        });
        row.addView(sw);
        row.setOnClickListener(v -> sw.performClick());
        return row;
    }

    /** 普通文本行（标题 + 说明 + 右侧可选附加视图） */
    public static View textRow(Context c, String title, String sub, View right) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(c, 14);
        row.setPadding(p, dp(c, 11), p, dp(c, 11));

        LinearLayout col = column(c);
        col.addView(text(c, title, 15, colorText(c), false));
        if (sub != null && sub.length() > 0) {
            TextView s = text(c, sub, 11, colorSub(c), false);
            s.setPadding(0, dp(c, 3), 0, 0);
            col.addView(s);
        }
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (right != null) row.addView(right);
        return row;
    }

    /** 主/次按钮，胶囊形，46dp 高 */
    public static TextView button(Context c, String s, boolean primary, View.OnClickListener l) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(15);
        tv.setGravity(Gravity.CENTER);
        tv.setMinHeight(dp(c, 46));
        tv.setPadding(dp(c, 18), dp(c, 12), dp(c, 18), dp(c, 12));
        tv.setSingleLine(true);
        if (primary) {
            tv.setTextColor(Color.WHITE);
            tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
            tv.setBackground(round(c, ACCENT, 23));
        } else {
            tv.setTextColor(colorText(c));
            tv.setBackground(round(c, colorFill(c), 23));
        }
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(l);
        return tv;
    }

    /** 文字按钮（蓝字，无底色），用于「保存 / 全选」这类次要操作 */
    public static TextView textButton(Context c, String s, View.OnClickListener l) {
        TextView tv = text(c, s, 13, ACCENT, false);
        tv.setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6));
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(l);
        return tv;
    }

    /** 可点击的取值胶囊（时间显示） */
    public static TextView valuePill(Context c, String s) {
        TextView tv = text(c, s, 13, ACCENT, false);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        tv.setBackground(round(c, colorFill(c), 10));
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    /** 数字输入块（seq） */
    public static EditText numPill(Context c, String s) {
        EditText et = new EditText(c);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(s);
        et.setTextSize(13);
        et.setTextColor(colorText(c));
        et.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        et.setSingleLine(true);
        et.setBackground(round(c, colorFill(c), 10));
        et.setPadding(dp(c, 10), dp(c, 7), dp(c, 10), dp(c, 7));
        et.setLayoutParams(new LinearLayout.LayoutParams(
                dp(c, 118), ViewGroup.LayoutParams.WRAP_CONTENT));
        return et;
    }

    /** 右上角关闭按钮：圆形浅底 + 叉 */
    public static TextView closeButton(Context c) {
        TextView tv = text(c, "✕", 14, colorSub(c), false);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(round(c, colorFill(c), 999));
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    /** 弹窗内容根：白底 28dp 大圆角 + 20dp 内边距 */
    public static LinearLayout sheet(Context c) {
        LinearLayout l = column(c);
        int p = dp(c, 20);
        l.setPadding(p, p, p, p);
        l.setBackground(round(c, colorCard(c), 28));
        l.setClipToOutline(true);
        return l;
    }
}
