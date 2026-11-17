package com.example.budgetapp.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.widget.TextView;

import com.example.budgetapp.R;
import com.github.mikephil.charting.components.MarkerView;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.utils.MPPointF;

final class CustomMarkerView extends MarkerView {
    private static final float ARROW_HEIGHT_DP = 6f;
    private static final float ARROW_TO_POINT_DP = 6f;
    private final LedgerTrendChart chart;
    private final TextView title;
    private final TextView amount;
    private final float density;
    private final int bubbleColor;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final MPPointF offset = new MPPointF();
    private Entry boundEntry;
    private int measuredForWidth = -1;
    private int pointColor;

    CustomMarkerView(Context context, LedgerTrendChart chart) {
        super(context, R.layout.view_chart_marker);
        this.chart = chart;
        title = findViewById(R.id.tv_marker_date);
        amount = findViewById(R.id.tv_marker_amount);
        density = getResources().getDisplayMetrics().density;
        bubbleColor = context.getColor(R.color.ledger_trend_marker);
        measureLabel(0);
    }

    float getHeadroom() { return getMeasuredHeight() + dp(ARROW_HEIGHT_DP + ARROW_TO_POINT_DP + 4f); }

    String describe(Entry entry) {
        Label label = (Label) entry.getData();
        return label.title + "，" + label.amountText();
    }

    @Override public void refreshContent(Entry entry, Highlight highlight) {
        if (boundEntry != entry) {
            Label label = (Label) entry.getData();
            title.setText(label.title);
            amount.setText(label.amountText());
            boundEntry = entry;
            measuredForWidth = -1;
        }
        if (measuredForWidth != chart.getWidth()) measureLabel(chart.getWidth());
        pointColor = chart.getData().getDataSetByIndex(highlight.getDataSetIndex()).getColor();
    }

    private void measureLabel(int maxWidth) {
        measure(MeasureSpec.makeMeasureSpec(maxWidth, maxWidth > 0 ? MeasureSpec.AT_MOST : MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        layout(0, 0, getMeasuredWidth(), getMeasuredHeight());
        measuredForWidth = maxWidth;
    }

    @Override public MPPointF getOffsetForDrawingAtPoint(float x, float y) {
        float left = Math.max(0, Math.min(x - getWidth() / 2f, chart.getWidth() - getWidth()));
        offset.x = left - x;
        // The chart reserves this height so the label never covers the selected point.
        offset.y = -getHeight() - dp(ARROW_HEIGHT_DP + ARROW_TO_POINT_DP);
        return offset;
    }

    @Override public void draw(Canvas canvas, float x, float y) {
        paint.setColor(pointColor);
        paint.setAlpha(64);
        canvas.drawCircle(x, y, dp(8f), paint);
        paint.setAlpha(255);
        canvas.drawCircle(x, y, dp(3.5f), paint);

        MPPointF position = getOffsetForDrawingAtPoint(x, y);
        float left = x + position.x, top = y + position.y;
        float right = left + getWidth(), bottom = top + getHeight();
        float corner = dp(10f), halfArrow = dp(5f);
        float shoulder = Math.min(corner + halfArrow, getWidth() / 2f);
        float base = Math.max(left + shoulder, Math.min(x, right - shoulder));
        arrow.rewind();
        arrow.moveTo(base - halfArrow, bottom - dp(1f));
        arrow.lineTo(x, bottom + dp(ARROW_HEIGHT_DP));
        arrow.lineTo(base + halfArrow, bottom - dp(1f));
        arrow.close();
        paint.setColor(bubbleColor);
        canvas.drawRoundRect(left, top, right, bottom, corner, corner, paint);
        canvas.drawPath(arrow, paint);
        int checkpoint = canvas.save();
        canvas.translate(left, top);
        draw(canvas);
        canvas.restoreToCount(checkpoint);
    }

    private float dp(float value) { return value * density; }

    static final class Label {
        final String title;
        final double amount;

        Label(String title, double amount) {
            this.title = title;
            this.amount = amount;
        }

        String amountText() { return "¥" + LedgerTransactionRow.money(amount); }
    }
}
