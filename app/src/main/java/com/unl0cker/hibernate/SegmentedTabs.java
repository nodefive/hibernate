package com.unl0cker.hibernate;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * One outlined pill split into equal segments by thin dividers. The selected segment is filled
 * edge to edge with a lighter colour, and the fill slides to a newly selected segment.
 */
public class SegmentedTabs extends LinearLayout {

    interface OnSelectListener {
        void onSelect(int index);
    }

    private static final long SLIDE_MS = 300;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final RectF box = new RectF();
    /** Was about 17dp above and below a single line; 40% less. */
    private static final float VERTICAL_PADDING_DP = 10f;

    private final List<TextView> tabs = new ArrayList<>();
    private final List<TextView> counts = new ArrayList<>();
    private final ArgbEvaluator colors = new ArgbEvaluator();
    private final int onColor;
    private final int offColor;
    private final float stroke;

    private int selected;
    /** Fill position in segments (0 = first); animated between whole numbers. */
    private float position;
    private ValueAnimator slide;
    private OnSelectListener listener;

    public SegmentedTabs(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setWillNotDraw(false);
        stroke = getResources().getDisplayMetrics().density * 1.5f;
        track.setColor(context.getColor(R.color.tab_bg));
        fill.setColor(context.getColor(R.color.tab_selected));
        line.setColor(context.getColor(R.color.tab_border));
        line.setStrokeWidth(stroke * 0.75f);
        border.setColor(context.getColor(R.color.tab_border));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(stroke);
        onColor = context.getColor(R.color.tab_text_on);
        offColor = context.getColor(R.color.text_secondary);
    }

    /** One tab per label: the count on top (set with setCount), the label under it. */
    void setTabs(int... labels) {
        removeAllViews();
        tabs.clear();
        counts.clear();
        Typeface bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
        int pad = Math.round(getResources().getDisplayMetrics().density * VERTICAL_PADDING_DP);
        for (int i = 0; i < labels.length; i++) {
            LinearLayout cell = new LinearLayout(getContext());
            cell.setOrientation(VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(0, pad, 0, pad);

            TextView count = new TextView(getContext());
            count.setGravity(Gravity.CENTER);
            count.setTextSize(18);
            count.setTypeface(bold);
            count.setMaxLines(1);
            count.setIncludeFontPadding(false);
            cell.addView(count);

            TextView label = new TextView(getContext());
            label.setText(labels[i]);
            label.setGravity(Gravity.CENTER);
            label.setTextSize(12);
            label.setTypeface(bold);
            label.setMaxLines(1);
            label.setIncludeFontPadding(false);
            label.setPadding(0, pad / 4, 0, 0);
            cell.addView(label);

            final int index = i;
            cell.setOnClickListener(v -> {
                select(index, true);
                if (listener != null) {
                    listener.onSelect(index);
                }
            });
            addView(cell, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            counts.add(count);
            tabs.add(label);
        }
        moveTo(position);
    }

    void setOnSelectListener(OnSelectListener l) {
        listener = l;
    }

    /** The number shown above a tab's label; blank while unknown (negative). */
    void setCount(int index, int value) {
        counts.get(index).setText(value < 0 ? "–" : String.valueOf(value));
    }

    /** Moves the fill to index, sliding when animate is set and the view is laid out. */
    void select(int index, boolean animate) {
        if (index == selected && (slide != null && slide.isRunning() || position == index)) {
            return; // already there or on its way: don't cut the slide short
        }
        int previous = selected;
        selected = index;
        if (slide != null) {
            slide.cancel();
        }
        if (animate && getWidth() > 0 && previous != index) {
            slide = ValueAnimator.ofFloat(position, index);
            slide.setDuration(SLIDE_MS);
            slide.setInterpolator(new DecelerateInterpolator(1.6f));
            slide.addUpdateListener(a -> moveTo((float) a.getAnimatedValue()));
            slide.start();
        } else {
            moveTo(index);
        }
    }

    /** Places the fill and lights each label by how much of the fill is under it. */
    private void moveTo(float at) {
        position = at;
        for (int i = 0; i < tabs.size(); i++) {
            float under = Math.max(0f, 1f - Math.abs(at - i));
            int color = (int) colors.evaluate(under, offColor, onColor);
            tabs.get(i).setTextColor(color);
            counts.get(i).setTextColor(color);
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        shape.reset();
        box.set(0, 0, w, h);
        shape.addRoundRect(box, h / 2f, h / 2f, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        int n = Math.max(1, tabs.size());
        float segment = w / (float) n;

        canvas.drawPath(shape, track);
        canvas.save();
        // Clipping to the pill gives the fill rounded ends at the edges and square sides
        // elsewhere, touching the outline with no gap.
        canvas.clipPath(shape);
        canvas.drawRect(position * segment, 0, (position + 1) * segment, h, fill);
        for (int i = 1; i < n; i++) {
            float x = i * segment;
            canvas.drawLine(x, 0, x, h, line);
        }
        canvas.restore();

        float inset = stroke / 2f;
        box.set(inset, inset, w - inset, h - inset);
        float r = h / 2f - inset;
        canvas.drawRoundRect(box, r, r, border);
    }
}
