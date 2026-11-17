package com.example.budgetapp.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import java.util.Objects;

/** Touch selects a point immediately; horizontal motion selects its neighbors. */
public final class LedgerTrendChart extends LineChart {
    private enum TouchMode { IDLE, UNDECIDED, HORIZONTAL }

    private final CustomMarkerView marker;
    private final int touchSlop;
    private final float[] pointer = new float[2];
    private TouchMode touchMode = TouchMode.IDLE;
    private float downX, downY;
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
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
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
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                finishTouch();
                if (!isEnabled() || !selectAt(event.getX())) return false;
                downX = event.getX();
                downY = event.getY();
                touchMode = TouchMode.UNDECIDED;
                // Hold interception while deciding direction, even if the first move is diagonal.
                disallowParentIntercept(true);
                break;
            case MotionEvent.ACTION_MOVE:
                updateTouch(event.getX(), event.getY());
                break;
            case MotionEvent.ACTION_UP:
                updateTouch(event.getX(), event.getY());
                if (touchMode == TouchMode.UNDECIDED) performClick();
                finishTouch();
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                finishTouch();
                break;
        }
        return true;
    }

    private void updateTouch(float x, float y) {
        if (touchMode == TouchMode.UNDECIDED) {
            float dx = Math.abs(x - downX), dy = Math.abs(y - downY);
            if (dx >= touchSlop * 0.5f && dx >= dy) {
                touchMode = TouchMode.HORIZONTAL;
            } else if (dy >= touchSlop && dy > dx) {
                // A vertical drag belongs to the page from here on.
                finishTouch();
            }
        }
        if (touchMode == TouchMode.HORIZONTAL) selectAt(x);
    }

    @Override public boolean performClick() {
        boolean handled = super.performClick();
        ILineDataSet data = series();
        if (data == null || data.getEntryCount() == 0) return handled;
        if (!valuesToHighlight()) showPoint(data.getEntryForIndex(data.getEntryCount() - 1));
        return true;
    }

    @Override protected void onDetachedFromWindow() {
        finishTouch();
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

    private void finishTouch() {
        if (touchMode == TouchMode.IDLE) return;
        touchMode = TouchMode.IDLE;
        disallowParentIntercept(false);
    }

    private void disallowParentIntercept(boolean disallow) {
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(disallow);
    }
}
