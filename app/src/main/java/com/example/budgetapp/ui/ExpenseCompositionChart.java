package com.example.budgetapp.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;

import com.example.budgetapp.R;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.listener.OnChartValueSelectedListener;
import com.github.mikephil.charting.renderer.PieChartRenderer;
import com.github.mikephil.charting.utils.MPPointF;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** The expense ring selects a slice without opening the ledger. */
public final class ExpenseCompositionChart extends PieChart implements OnChartValueSelectedListener {
    private static final float SELECTION_SHIFT = 8f;
    private static final int[] LIGHT_SLICES = {0xff3d6ce6, 0xff559ce9, 0xff019bc2, 0xff09a698, 0xff6262cd, 0xff467bc0, 0xff1e887e};
    private static final int[] DARK_SLICES = {0xff477af2, 0xff65aaf5, 0xff10beea, 0xff0bd7c9, 0xff8a83ed, 0xff83a6ee, 0xff66c6cd};
    private final Rect exposed = new Rect();
    private final Runnable revealWhenVisible = this::revealIfVisible;
    private final ViewTreeObserver.OnScrollChangedListener scrollListener = this::revealIfVisible;
    private boolean revealPending;
    private ValueAnimator revealAnimator, selectionAnimator;
    private double total;
    private String period;
    private String centerAmount = "", centerCaption = "";
    private float selectionShift;

    public ExpenseCompositionChart(Context context, AttributeSet attrs) {
        super(context, attrs);
        setRenderer(new RingRenderer());
        getDescription().setEnabled(false);
        getLegend().setEnabled(false);
        setHoleRadius(51f);
        setHoleColor(Color.TRANSPARENT);
        setTransparentCircleRadius(0);
        setDrawEntryLabels(false);
        setRotationEnabled(false);
        setOnChartValueSelectedListener(this);
        addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> revealIfVisible());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        // MPAndroidChart counts extra offsets again when calculating the circle diameter.
        float density = getResources().getDisplayMetrics().density;
        float sideOffset = Math.min(22f, width / density * .065f);
        setExtraOffsets(sideOffset, 24f, sideOffset, 24f);
        super.onSizeChanged(width, height, oldWidth, oldHeight);
    }

    @Override public float getDiameter() {
        // Leave a side column for the callouts at the widest part of the ring.
        return Math.min(super.getDiameter(), getWidth() * .55f + dp(2f * SELECTION_SHIFT));
    }

    int sliceColor(int index) {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        return (dark ? DARK_SLICES : LIGHT_SLICES)[index];
    }

    void setComposition(List<PieEntry> entries, List<Integer> colors, double amount, String periodKey) {
        if (entries.isEmpty()) {
            revealPending = false;
            cancelAnimations();
            clear();
            return;
        }
        if (sameComposition(entries, amount, periodKey)) return;
        cancelAnimations();
        total = amount;
        period = periodKey;
        PieDataSet set = new PieDataSet(entries, "");
        set.setColors(colors);
        set.setSliceSpace(2f);
        set.setAutomaticallyDisableSliceSpacing(true);
        set.setSelectionShift(SELECTION_SHIFT);
        setData(new PieData(set));
        highlightValues(null);
        updateCenter(null);
        revealPending = ValueAnimator.areAnimatorsEnabled();
        getAnimator().setPhaseY(revealPending ? 0f : 1f);
        post(revealWhenVisible);
        invalidate();
    }

    private boolean sameComposition(List<PieEntry> entries, double amount, String periodKey) {
        PieData data = getData();
        if (data == null || !Objects.equals(period, periodKey) || total != amount
                || data.getEntryCount() != entries.size()) return false;
        for (int i = 0; i < entries.size(); i++) {
            PieEntry previous = data.getDataSet().getEntryForIndex(i);
            PieEntry next = entries.get(i);
            if (!previous.getLabel().equals(next.getLabel()) || !Objects.equals(previous.getData(), next.getData())) return false;
        }
        return true;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnScrollChangedListener(scrollListener);
        post(revealWhenVisible);
    }

    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnScrollChangedListener(scrollListener);
        removeCallbacks(revealWhenVisible);
        cancelAnimations();
        if (!revealPending) getAnimator().setPhaseY(1f);
        super.onDetachedFromWindow();
    }

    private void cancelAnimations() {
        if (revealAnimator != null) revealAnimator.cancel();
        if (selectionAnimator != null) selectionAnimator.cancel();
        selectionShift = 0f;
    }

    private void revealIfVisible() {
        if (!revealPending || getHeight() == 0 || !getGlobalVisibleRect(exposed)
                || exposed.height() < Math.min(getHeight() / 3, dp(80))) return;
        revealPending = false;
        revealAnimator = animate(0f, 1f, 800,
                value -> getAnimator().setPhaseY((float) value.getAnimatedValue()));
    }

    private ValueAnimator animate(float from, float to, long duration, ValueAnimator.AnimatorUpdateListener update) {
        ValueAnimator animator = ValueAnimator.ofFloat(from, to);
        animator.setDuration(duration);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(value -> {
            update.onAnimationUpdate(value);
            invalidate();
        });
        animator.start();
        return animator;
    }

    @Override public Highlight getHighlightByTouchPoint(float x, float y) {
        if (getData() == null || getAnimator().getPhaseY() < 1f
                || distanceToCenter(x, y) < getRadius() * getHoleRadius() / 100f) return null;
        return super.getHighlightByTouchPoint(x, y);
    }

    @Override public void onValueSelected(Entry entry, Highlight highlight) {
        updateCenter((PieEntry) entry);
        if (selectionAnimator != null) selectionAnimator.cancel();
        selectionShift = SELECTION_SHIFT;
        if (ValueAnimator.areAnimatorsEnabled()) {
            selectionShift = 0f;
            selectionAnimator = animate(0f, SELECTION_SHIFT, 240,
                    value -> selectionShift = (float) value.getAnimatedValue());
        }
        invalidate();
    }

    @Override public void onNothingSelected() {
        if (selectionAnimator != null) selectionAnimator.cancel();
        selectionShift = 0f;
        updateCenter(null);
        invalidate();
    }

    private void updateCenter(PieEntry selection) {
        String label = selection == null ? getResources().getString(R.string.stats_expense_total) : selection.getLabel();
        double amount = selection == null ? total : ((Number) selection.getData()).doubleValue();
        centerAmount = LedgerTransactionRow.money(amount);
        centerCaption = getResources().getString(R.string.stats_pie_yuan, label);
        setCenterText(centerAmount + "\n" + centerCaption);
        setContentDescription(label + "，" + centerAmount + " 元");
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics());
    }

    private final class RingRenderer extends PieChartRenderer {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint amount = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint caption = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final List<Leader> left = new ArrayList<>(), right = new ArrayList<>();

        RingRenderer() {
            super(ExpenseCompositionChart.this, getAnimator(), getViewPortHandler());
            amount.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            amount.setTextAlign(Paint.Align.CENTER);
            caption.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void drawCenterText(Canvas canvas) {
            float diameter = 2f * getRadius() * getHoleRadius() / 100f;
            if (centerAmount.isEmpty() || diameter <= 0f) return;
            MPPointF center = getCenterCircleBox();
            float usableWidth = diameter * .84f;
            amount.setTextSize(sp(18f));
            float amountWidth = amount.measureText(centerAmount);
            if (amountWidth > usableWidth) amount.setTextSize(amount.getTextSize() * usableWidth / amountWidth);
            amount.setColor(getContext().getColor(R.color.ledger_text));
            caption.setColor(getContext().getColor(R.color.ledger_muted));
            caption.setTextSize(Math.min(sp(12f), diameter * .18f));
            boolean shortCaption = centerCaption.codePointCount(0, centerCaption.length()) <= 8;
            float captionWidth = caption.measureText(centerCaption);
            if (shortCaption && captionWidth > usableWidth) {
                caption.setTextSize(caption.getTextSize() * usableWidth / captionWidth);
            } else if (captionWidth > usableWidth) {
                caption.setTextSize(Math.max(dp(9f), caption.getTextSize() * (usableWidth - 1f) / captionWidth));
            }
            String text = shortCaption ? centerCaption
                    : TextUtils.ellipsize(centerCaption, caption, usableWidth, TextUtils.TruncateAt.END).toString();
            float gap = dp(2f);
            float height = amount.descent() - amount.ascent() + gap + caption.descent() - caption.ascent();
            float top = center.y - height / 2f;
            canvas.drawText(centerAmount, center.x, top - amount.ascent(), amount);
            canvas.drawText(text, center.x,
                    top + amount.descent() - amount.ascent() + gap - caption.ascent(), caption);
            MPPointF.recycleInstance(center);
        }

        @Override public void drawHighlighted(Canvas canvas, Highlight[] highlights) {
            PieDataSet data = (PieDataSet) getData().getDataSet();
            // Reserve the full highlight margin at layout time; animate only the highlighted radius.
            data.setSelectionShift(selectionShift);
            try {
                super.drawHighlighted(canvas, highlights);
            } finally {
                data.setSelectionShift(SELECTION_SHIFT);
            }
        }

        @Override public void drawValues(Canvas canvas) {
            if (getData() == null || getAnimator().getPhaseY() < 1f) return;
            label.setTextSize(Math.min(sp(11f), dp(13f)));
            label.setColor(getContext().getColor(R.color.ledger_muted));
            line.setStrokeWidth(dp(1.3f));
            line.setStyle(Paint.Style.STROKE);
            left.clear();
            right.clear();
            MPPointF center = getCenterCircleBox();
            float radius = getRadius();
            float angle = getRotationAngle();
            for (int i = 0; i < getData().getEntryCount(); i++) {
                PieEntry entry = getData().getDataSet().getEntryForIndex(i);
                float sweep = getDrawAngles()[i];
                double mid = Math.toRadians(angle + sweep / 2f);
                angle += sweep;
                float dx = (float) Math.cos(mid), dy = (float) Math.sin(mid);
                float outer = radius + (needsHighlight(i) ? dp(selectionShift) : 0f);
                Leader item = new Leader(entry, getData().getDataSet().getColor(i), center.x + dx * (outer - dp(1)),
                        center.y + dy * (outer - dp(1)), center.x + dx * (outer + dp(10)),
                        center.y + dy * (outer + dp(10)));
                (dx < 0 ? left : right).add(item);
            }
            drawLeaders(canvas, left, true);
            drawLeaders(canvas, right, false);
            MPPointF.recycleInstance(center);
        }

        private void drawLeaders(Canvas canvas, List<Leader> leaders, boolean onLeft) {
            leaders.sort(Comparator.comparingDouble(item -> item.elbowY));
            float minY = -label.ascent() + dp(5);
            float maxY = getHeight() - dp(6);
            float step = Math.min(label.descent() - label.ascent() + dp(4),
                    (maxY - minY) / Math.max(1, leaders.size() - 1));
            float previous = minY - step;
            for (Leader item : leaders) {
                item.y = Math.max(item.elbowY, previous + step);
                previous = item.y;
            }
            float next = maxY + step;
            for (int i = leaders.size() - 1; i >= 0; i--) {
                Leader item = leaders.get(i);
                item.y = Math.min(item.y, next - step);
                next = item.y;
            }

            float edge = onLeft ? dp(3) : getWidth() - dp(3);
            label.setTextAlign(onLeft ? Paint.Align.LEFT : Paint.Align.RIGHT);
            float defaultTextSize = label.getTextSize();
            for (Leader item : leaders) {
                float elbowX = onLeft ? Math.max(edge, Math.min(item.x, item.elbowX + dp(4f)))
                        : Math.min(edge, Math.max(item.x, item.elbowX - dp(4f)));
                path.reset();
                path.moveTo(item.x, item.startY);
                path.lineTo(elbowX, item.y);
                path.lineTo(edge, item.y);
                line.setColor(item.color);
                canvas.drawPath(path, line);

                double percent = ((Number) item.entry.getData()).doubleValue() * 100 / total;
                String suffix = String.format(Locale.CHINA, " %.1f%%", percent);
                float maxLabel = Math.abs(edge - elbowX);
                String title = item.entry.getLabel();
                String shortTitle = title.substring(0, title.offsetByCodePoints(0, Math.min(2, title.codePointCount(0, title.length()))));
                float minimum = label.measureText(shortTitle + suffix);
                if (minimum > maxLabel) {
                    label.setTextSize(Math.max(dp(8f), defaultTextSize * (maxLabel - 1f) / minimum));
                }
                CharSequence name = title.equals(shortTitle) ? title : TextUtils.ellipsize(title, label,
                        Math.max(0, maxLabel - label.measureText(suffix)), TextUtils.TruncateAt.END);
                canvas.drawText(name + suffix, edge, item.y - dp(4), label);
                label.setTextSize(defaultTextSize);
            }
        }
    }

    private static final class Leader {
        final PieEntry entry;
        final int color;
        final float x, startY, elbowX, elbowY;
        float y;

        Leader(PieEntry entry, int color, float x, float startY, float elbowX, float elbowY) {
            this.entry = entry;
            this.color = color;
            this.x = x;
            this.startY = startY;
            this.elbowX = elbowX;
            this.elbowY = elbowY;
        }
    }
}
