package com.example.budgetapp.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import androidx.recyclerview.widget.RecyclerView;
import com.example.budgetapp.R;

/** Vertically scrollable categories with whole rows sized by the surrounding form. */
public final class LedgerCategoryGrid extends RecyclerView {
    static final int COLUMNS = 5;

    public LedgerCategoryGrid(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    int cellHeight() {
        // Leave room for two lines of the category name when the system font is larger.
        float scaledLabel = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 30,
                getResources().getDisplayMetrics());
        return dp(64) + Math.max(0, Math.round(scaledLabel) - dp(30));
    }

    int rowHeight() {
        return cellHeight() + 2 * getResources().getDimensionPixelSize(R.dimen.ledger_category_row_margin);
    }

    int heightForSpace(int available) {
        int count = getAdapter() == null ? 0 : getAdapter().getItemCount();
        if (count == 0) return 0;
        int padding = getPaddingTop() + getPaddingBottom();
        int rowHeight = rowHeight();
        int fullRows = (count + COLUMNS - 1) / COLUMNS;
        int visibleRows = Math.max(1, (available - padding) / rowHeight);
        return Math.min(fullRows, visibleRows) * rowHeight + padding;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
