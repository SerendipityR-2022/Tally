package com.example.budgetapp.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import com.example.budgetapp.R;

/** Sizes the category viewport from the height left over after the other form content. */
public final class LedgerEntryLayout extends LinearLayout {
    private LedgerCategoryGrid categories;
    private int maxHeight = Integer.MAX_VALUE;

    public LedgerEntryLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override protected void onFinishInflate() {
        super.onFinishInflate();
        categories = findViewById(R.id.rv_category);
        if (categories == null) categories = findViewById(R.id.rv_window_category);
    }

    void setMaxHeight(int height) {
        if (maxHeight == height) return;
        maxHeight = height;
        requestLayout();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int mode = MeasureSpec.getMode(heightSpec);
        int available = mode == MeasureSpec.UNSPECIFIED ? maxHeight
                : Math.min(maxHeight, MeasureSpec.getSize(heightSpec));
        if (categories.getVisibility() != View.GONE) {
            setGridHeight(0);
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            setGridHeight(categories.heightForSpace(available - getMeasuredHeight()));
        }
        // Wrap to the chosen whole rows. On very short windows the form can scroll while Save stays down.
        int bounded = available == Integer.MAX_VALUE ? heightSpec
                : MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST);
        super.onMeasure(widthSpec, bounded);
    }

    private void setGridHeight(int height) {
        ViewGroup.LayoutParams params = categories.getLayoutParams();
        if (params.height == height) return;
        params.height = height;
        // Re-measure the scroll content in this pass, without posting another layout traversal.
        for (View view = categories; view != this; view = (View) view.getParent()) view.forceLayout();
    }
}
