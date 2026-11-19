package com.example.budgetapp.ui;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;

public final class LedgerExpenseComparisonChart extends BarChart {
    private static final float WEEK_SIDE_MARGIN_DP = 8f;
    private static final float LABEL_GAP_DP = 4f;
    private static final float LABEL_SIZE_DP = 10f;
    private static final Typeface CONDENSED = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String[] weekLabels;

    public LedgerExpenseComparisonChart(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setComparisonData(BarData data, String[] labels, boolean weekly) {
        weekLabels = weekly ? labels : null;
        setMinOffset(weekly ? WEEK_SIDE_MARGIN_DP : 15f);
        XAxis axis = getXAxis();
        axis.setTypeface(weekly ? CONDENSED : null);
        if (weekly) {
            // Give each bar one full slot so the first and last labels also fit.
            axis.setAxisMinimum(-0.5f);
            axis.setAxisMaximum(labels.length - 0.5f);
        } else {
            axis.resetAxisMinimum();
            axis.resetAxisMaximum();
            axis.setTextSize(LABEL_SIZE_DP);
        }
        fitWeekLabels(getWidth());
        setData(data);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        fitWeekLabels(width);
        super.onSizeChanged(width, height, oldWidth, oldHeight);
    }

    private void fitWeekLabels(int width) {
        if (weekLabels == null || width <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        labelPaint.setTypeface(CONDENSED);
        labelPaint.setTextSize(LABEL_SIZE_DP * density);
        float longest = 1f;
        for (String label : weekLabels) longest = Math.max(longest, labelPaint.measureText(label));
        float slot = (width - 2 * WEEK_SIDE_MARGIN_DP * density) / weekLabels.length;
        float scale = Math.min(1f, (slot - LABEL_GAP_DP * density) / longest);
        getXAxis().setTextSize(LABEL_SIZE_DP * scale);
    }
}
