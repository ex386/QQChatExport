package com.opentools.qqexport;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * HyperOS / MIUIX 风格的开关。
 *
 * 为什么不用 framework 的 android.widget.Switch：它取宿主主题里的 switchStyle
 * （QQ 的 Activity 主题偏旧），渲染出来是细长轨道 + 小滑块，就是用户看到的
 * "安卓 4.4 风格"。这里整块自绘，不依赖任何主题属性。
 *
 * 尺寸 46x28dp，轨道全圆角，滑块 24dp 白色带 1dp 投影，切换 160ms 减速动画。
 */
public final class MiSwitch extends View {

    public interface OnChange {
        void onChange(boolean checked);
    }

    private static final int W_DP = 46;
    private static final int H_DP = 28;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;

    private boolean checked;
    private float frac;
    private OnChange listener;
    private ValueAnimator anim;

    public MiSwitch(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setClickable(true);
        setFocusable(true);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        paint.setStyle(Paint.Style.FILL);
    }

    public void setOnChange(OnChange l) {
        listener = l;
    }

    public boolean isChecked() {
        return checked;
    }

    /** 初始化用：不回调、不播动画 */
    public void setCheckedSilent(boolean b) {
        checked = b;
        frac = b ? 1f : 0f;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(Math.max(1, (int) (W_DP * d + 0.5f)),
                Math.max(1, (int) (H_DP * d + 0.5f)));
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth();
        float h = getHeight();
        float r = h / 2f;

        int off = Ui.dark(getContext()) ? 0x33FFFFFF : 0x1F000000;
        paint.setColor(blend(off, Ui.ACCENT, frac));
        cv.drawRoundRect(0, 0, w, h, r, r, paint);

        float pr = r - 2.5f * d;
        float cx = r + (w - 2f * r) * frac;

        paint.setColor(Color.WHITE);
        paint.setShadowLayer(1.4f * d, 0f, 1f * d, 0x38000000);
        cv.drawCircle(cx, h / 2f, pr, paint);
        paint.setShadowLayer(0f, 0f, 0f, 0);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        boolean b = !checked;
        checked = b;
        animateTo(b ? 1f : 0f);
        if (listener != null) listener.onChange(b);
        return true;
    }

    private void animateTo(float target) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(frac, target);
        anim.setDuration(160);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            frac = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    private static int blend(int from, int to, float t) {
        float f = Math.max(0f, Math.min(1f, t));
        int a = (int) (((from >>> 24) & 0xFF) + ((((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * f));
        int r = (int) (((from >> 16) & 0xFF) + ((((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * f));
        int g = (int) (((from >> 8) & 0xFF) + ((((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * f));
        int b = (int) ((from & 0xFF) + (((to & 0xFF) - (from & 0xFF)) * f));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
