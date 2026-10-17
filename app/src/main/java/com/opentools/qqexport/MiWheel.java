package com.opentools.qqexport;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * MIUIX 风格滚轮：中间一行高亮，上下渐隐，拖动松手后 160ms 吸附到最近一项并回调。
 *
 * 之所以自绘而不是用 NumberPicker / DatePickerDialog：后两者的分隔线、上下按钮、
 * 背景都取自宿主主题，在 QQ 里就是 4.4 观感；自绘可以完全控制配色与圆角。
 */
public final class MiWheel extends View {

    public interface OnPick {
        void onPick(int v);
    }

    private static final int VISIBLE = 5;
    private static final int ITEM_DP = 36;

    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final int itemH;

    private int lo = 0;
    private int hi = 59;
    private float pos = 0f;
    private float downY = 0f;
    private float downPos = 0f;
    private boolean dragging = false;
    private int textColor = 0xFF000000;
    private ValueAnimator anim;
    private OnPick pick;

    public MiWheel(Context c) {
        super(c);
        itemH = Ui.dp(c, ITEM_DP);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(Ui.dp(c, 16));
        setClickable(true);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public void setRange(int lo, int hi, int value) {
        this.lo = lo;
        this.hi = Math.max(lo, hi);
        this.pos = clamp(value - lo);
        invalidate();
    }

    public void setTextColor(int color) {
        this.textColor = color;
        invalidate();
    }

    public void setOnPick(OnPick p) {
        this.pick = p;
    }

    public int value() {
        return lo + Math.round(pos);
    }

    private float clamp(float v) {
        float max = hi - lo;
        if (v < 0f) return 0f;
        if (v > max) return max;
        return v;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = resolveSize(Ui.dp(getContext(), 56), wSpec);
        int h = itemH * VISIBLE;
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float cy = h / 2f;

        hp.setColor(Ui.colorFill(getContext()));
        int inset = Ui.dp(getContext(), 1);
        box.set(inset, cy - itemH / 2f + inset, w - inset, cy + itemH / 2f - inset);
        cv.drawRoundRect(box, Ui.dp(getContext(), 11), Ui.dp(getContext(), 11), hp);

        int half = VISIBLE / 2;
        int from = (int) Math.floor(pos) - half;
        int to = (int) Math.ceil(pos) + half;
        int baseAlpha = Color.alpha(textColor);
        int rgb = textColor & 0x00FFFFFF;
        for (int i = from; i <= to; i++) {
            if (i < 0 || i > hi - lo) continue;
            float d = Math.abs(i - pos);
            if (d > half) continue;
            float k = 1f - d / (half + 0.2f);
            if (k <= 0.02f) continue;
            int a = (int) (baseAlpha * Math.pow(k, 1.15f));
            if (a <= 2) continue;
            tp.setColor((a << 24) | rgb);
            tp.setTypeface(d < 0.4f ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            float y = cy + (i - pos) * itemH - (tp.ascent() + tp.descent()) / 2f;
            cv.drawText(String.valueOf(lo + i), w / 2f, y, tp);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                stopAnim();
                dragging = true;
                downY = e.getY();
                downPos = pos;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return true;
                pos = clamp(downPos - (e.getY() - downY) / (float) itemH);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return true;
                dragging = false;
                snap();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void snap() {
        final float from = pos;
        final float to = clamp(Math.round(pos));
        if (Math.abs(to - from) < 0.01f) {
            pos = to;
            invalidate();
            fire();
            return;
        }
        stopAnim();
        anim = ValueAnimator.ofFloat(from, to);
        anim.setDuration(160);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            pos = (Float) a.getAnimatedValue();
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                pos = to;
                invalidate();
                fire();
            }
        });
        anim.start();
    }

    private void stopAnim() {
        if (anim != null) {
            ValueAnimator a = anim;
            anim = null;
            try {
                a.removeAllListeners();
                a.removeAllUpdateListeners();
                a.cancel();
            } catch (Throwable ignored) {
            }
        }
    }

    private void fire() {
        if (pick != null) pick.onPick(value());
    }
}
