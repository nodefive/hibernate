package com.unl0cker.hibernate;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

/**
 * Pull-to-refresh around one scrolling child (no AndroidX): dragging down while the child is at
 * its top pulls a spinner in from above; letting go past the threshold starts a refresh.
 */
public class PullRefreshLayout extends FrameLayout {

    interface OnRefreshListener {
        void onRefresh();
    }

    private static final float DRAG_RATE = 0.5f;
    private static final int SIZE_DP = 42;
    private static final int TRIGGER_DP = 72;
    private static final int MAX_DP = 120;
    private static final int REST_DP = 20;

    private final View spinner;
    private final int size;
    private final int trigger;
    private final int max;
    private final int rest;
    private final int touchSlop;

    private OnRefreshListener listener;
    private boolean refreshing;
    private boolean dragging;
    private float downY;
    private float pull;

    public PullRefreshLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        size = Math.round(SIZE_DP * density);
        trigger = Math.round(TRIGGER_DP * density);
        max = Math.round(MAX_DP * density);
        rest = Math.round(REST_DP * density);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        FrameLayout circle = new FrameLayout(context);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(context.getColor(R.color.surface));
        bg.setStroke(Math.round(1.5f * density), context.getColor(R.color.tab_border));
        circle.setBackground(bg);
        circle.setElevation(4 * density);
        ProgressBar progress = new ProgressBar(context);
        progress.setIndeterminateTintList(ColorStateList.valueOf(context.getColor(R.color.accent_text)));
        int inner = Math.round(size * 0.6f);
        circle.addView(progress, new LayoutParams(inner, inner, Gravity.CENTER));
        spinner = circle;
        spinner.setTranslationY(-size);
        spinner.setAlpha(0f);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        addView(spinner, new LayoutParams(size, size, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
    }

    void setOnRefreshListener(OnRefreshListener l) {
        listener = l;
    }

    boolean isRefreshing() {
        return refreshing;
    }

    /** Ends (or shows) the refresh spinner. */
    void setRefreshing(boolean on) {
        refreshing = on;
        moveSpinner(on ? rest + size : 0, true);
    }

    private View content() {
        return getChildAt(0);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (refreshing || !isEnabled()) {
            return false;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = ev.getY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && ev.getY() - downY > touchSlop
                        && !content().canScrollVertically(-1)) {
                    dragging = true;
                    downY = ev.getY();
                }
                break;
            default:
                break;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!dragging) {
            return super.onTouchEvent(ev);
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                pull = Math.max(0, Math.min(max, (ev.getY() - downY) * DRAG_RATE));
                moveSpinner(pull, false);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (pull >= trigger && ev.getActionMasked() == MotionEvent.ACTION_UP) {
                    setRefreshing(true);
                    if (listener != null) {
                        listener.onRefresh();
                    }
                } else {
                    moveSpinner(0, true);
                }
                pull = 0;
                return true;
            default:
                return true;
        }
    }

    /** distance: how far the spinner's bottom edge is below the top (0 = hidden). */
    private void moveSpinner(float distance, boolean animate) {
        float y = distance - size;
        float alpha = Math.min(1f, distance / trigger);
        spinner.animate().cancel();
        if (animate) {
            spinner.animate().translationY(y).alpha(distance > 0 ? 1f : 0f)
                    .setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
        } else {
            spinner.setTranslationY(y);
            spinner.setAlpha(alpha);
            spinner.setRotation(distance * 1.5f);
        }
    }
}
