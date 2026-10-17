package com.opentools.qqexport;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * HyperOS / MIUIX 风格的勾选标记：未选中是细描边圆环，选中是实心亮色圆 + 白色对勾。
 *
 * 替换 framework 的 CheckBox（方框 + 系统 tint），后者在 QQ 主题下就是"4.4 风"的
 * 元凶之一。本身不接收点击，点击交给所在的整行处理。
 */
public final class MiCheck extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tick = new Path();
    private final float d;

    private boolean checked;

    public MiCheck(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setClickable(false);
        setFocusable(false);
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean b) {
        if (checked == b) return;
        checked = b;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int size = Math.max(1, (int) (24 * d + 0.5f));
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas cv) {
        float s = getWidth() / 24f;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;

        if (checked) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Ui.ACCENT);
            cv.drawCircle(cx, cy, 11f * s, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.2f * s);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(Color.WHITE);
            tick.reset();
            tick.moveTo(7f * s, 12.3f * s);
            tick.lineTo(10.5f * s, 15.8f * s);
            tick.lineTo(17f * s, 9.2f * s);
            cv.drawPath(tick, paint);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.6f * s);
            paint.setColor(Ui.dark(getContext()) ? 0x66FFFFFF : 0x33000000);
            cv.drawCircle(cx, cy, 10.2f * s, paint);
        }
    }
}
