package com.unl0cker.hibernate;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Memory in use as a ring, with the percentage in the middle. */
public class MemoryRing extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private float fraction;
    private ValueAnimator animator;
    private final String caption;

    public MemoryRing(Context context, AttributeSet attrs) {
        super(context, attrs);
        float dp = getResources().getDisplayMetrics().density;
        float sp = getResources().getDisplayMetrics().scaledDensity;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(12 * dp);
        track.setColor(context.getColor(R.color.divider));
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(12 * dp);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(context.getColor(R.color.ring));
        big.setColor(context.getColor(R.color.text));
        big.setTextSize(30 * sp);
        big.setTextAlign(Paint.Align.CENTER);
        big.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        small.setColor(context.getColor(R.color.text_secondary));
        small.setTextSize(12 * sp);
        small.setTextAlign(Paint.Align.CENTER);
        caption = context.getString(R.string.ring_caption);
    }

    /** Animates to the new value (0..1). */
    public void setFraction(float value) {
        float target = Math.max(0f, Math.min(1f, value));
        if (animator != null) {
            animator.cancel();
        }
        animator = ValueAnimator.ofFloat(fraction, target);
        animator.setDuration(600);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            fraction = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
        setContentDescription(Math.round(target * 100) + "% " + caption);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = track.getStrokeWidth() / 2 + 1;
        box.set(inset, inset, getWidth() - inset, getHeight() - inset);
        canvas.drawArc(box, 0, 360, false, track);
        if (fraction > 0) {
            canvas.drawArc(box, -90, 360 * fraction, false, arc);
        }
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        canvas.drawText(Math.round(fraction * 100) + "%", cx, cy + big.getTextSize() * 0.3f, big);
        canvas.drawText(caption, cx, cy + big.getTextSize() * 0.3f + small.getTextSize() * 1.5f, small);
    }
}
