package com.example.budgetapp.ui;

import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.widget.NumberPicker;

import com.example.budgetapp.R;

/** Keeps native wheel and accessibility behavior while styling the date and time controls alike. */
public final class LedgerNumberPicker extends NumberPicker {
    public LedgerNumberPicker(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setSelectionDividerHeight(0);
            setTextColor(context.getColor(R.color.ledger_text));
            setTextSize(getResources().getDimension(R.dimen.tally_picker_text_size));
        }
    }
}
