package com.example.budgetapp.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ViewParent;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import java.util.Objects;

/** A tap selects a point. Holding a finger on the plot also lets it scrub across the points. */
public final class LedgerTrendChart extends LineChart {
    private final CustomMarkerView marker;
    private final GestureDetector gestures;
    private final float[] pointer = new float[2];
    private boolean scrubbing;
    private String seriesKey;

    public LedgerTrendChart(Context context, AttributeSet attrs) {
        super(context, attrs);
        marker = new CustomMarkerView(context, this);
        setMarker(marker);
        float density = getResources().getDisplayMetrics().density;
        float headroom = marker.getHeadroom();
        setExtraOffsets(1f, headroom / density, 6f, 3f);
        setMinimumHeight((int) Math.ceil(headroom + 176f * density));
        setClickable(true);
        setFocusable(true);
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onSingleTapUp(MotionEvent e) {
                selectAt(e.getX());
                performClick();
                return true;
            }

            @Override public void onLongPress(MotionEvent e) {
                if (!isAttachedToWindow() || !isShown() || !selectAt(e.getX())) return;
                // A normal vertical drag still goes to the ScrollView until a hold is recognized.
                scrubbing = true;
                ViewParent parent = getParent();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
            }
        });
        gestures.setOnDoubleTapListener(null);
    }

    void setTrendData(@Nullable LineData data, @Nullable String key) {
        float previousX = Objects.equals(seriesKey, key) && valuesToHighlight()
                ? getHighlighted()[0].getX() : Float.NaN;
        seriesKey = key;
        if (data == null) {
            clear();
            ViewCompat.setStateDescription(this, null);
            return;
        }
        setData(data);
        Entry previous = Float.isNaN(previousX) ? null : series().getEntryForXValue(previousX, Float.NaN);
        if (previous != null && previous.getX() == previousX) {
            showPoint(previous);
        } else {
            highlightValues(null);
            ViewCompat.setStateDescription(this, null);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN && (!isEnabled() || series() == null)) return false;
        gestures.onTouchEvent(event);
        if (scrubbing && action == MotionEvent.ACTION_MOVE) selectAt(event.getX());
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_POINTER_DOWN) finishScrubbing();
        return true;
    }

    @Override public boolean performClick() {
        boolean handled = super.performClick();
        ILineDataSet data = series();
        if (data == null || data.getEntryCount() == 0) return handled;
        if (!valuesToHighlight()) showPoint(data.getEntryForIndex(data.getEntryCount() - 1));
        return true;
    }

    @Override protected void onDetachedFromWindow() {
        finishScrubbing();
        super.onDetachedFromWindow();
    }

    private ILineDataSet series() { return getData() == null ? null : getData().getDataSetByIndex(0); }

    private boolean selectAt(float x) {
        ILineDataSet data = series();
        if (data == null || data.getEntryCount() == 0 || getViewPortHandler().contentWidth() <= 0) return false;
        pointer[0] = x;
        pointer[1] = 0f;
        getTransformer(YAxis.AxisDependency.LEFT).pixelsToValue(pointer);
        Entry point = data.getEntryForXValue(pointer[0], Float.NaN);
        if (point == null) return false;
        if (!valuesToHighlight() || getHighlighted()[0].getX() != point.getX()) showPoint(point);
        return true;
    }

    private void showPoint(Entry point) {
        highlightValue(point.getX(), point.getY(), 0, false);
        ViewCompat.setStateDescription(this, marker.describe(point));
    }

    private void finishScrubbing() {
        if (!scrubbing) return;
        scrubbing = false;
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
    }
}
