package com.example.budgetapp.ui;

import android.content.Context;
import android.util.AttributeSet;
import androidx.cardview.widget.CardView;
import androidx.core.graphics.ColorUtils;
import com.example.budgetapp.R;

public final class LedgerSummaryCard extends CardView {
    public LedgerSummaryCard(Context context, AttributeSet attrs) {
        super(context, attrs);
        setRadius(getResources().getDimension(R.dimen.ledger_summary_corner));
        setClipToOutline(true);
    }

    void applyBackground(boolean customWallpaper) {
        int surface = getContext().getColor(R.color.white);
        setCardBackgroundColor(customWallpaper ? ColorUtils.setAlphaComponent(surface, 230) : surface);
    }
}
