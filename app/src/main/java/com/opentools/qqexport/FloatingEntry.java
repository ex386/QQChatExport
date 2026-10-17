package com.opentools.qqexport;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/** 在聊天页右下角挂一个“导出”小按钮，不申请悬浮窗权限 */
public final class FloatingEntry {

    private static final String TAG = "qq-export-btn";

    public static void attach(final Activity act) {
        View decor = act.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) return;
        ViewGroup root = (ViewGroup) decor;
        if (root.findViewWithTag(TAG) != null) return;

        TextView btn = new TextView(act);
        btn.setTag(TAG);
        btn.setText("导出");
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(12);

        int p = (int) (10 * act.getResources().getDisplayMetrics().density);
        btn.setPadding(p, p / 2, p, p / 2);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC3482FF);
        bg.setCornerRadius(999);
        btn.setBackground(bg);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.rightMargin = p * 2;
        lp.bottomMargin = p * 14;
        btn.setLayoutParams(lp);

        btn.setOnClickListener(v -> ExportDialog.show(act));
        /* 长按进设置：设置面板与导出逻辑同进程，写的是 QQ 自己的 prefs，改完立即生效 */
        btn.setOnLongClickListener(v -> {
            try {
                SettingsPanel.show(act);
            } catch (Throwable t) {
                L.e(t);
            }
            return true;
        });
        root.addView(btn);
    }
}