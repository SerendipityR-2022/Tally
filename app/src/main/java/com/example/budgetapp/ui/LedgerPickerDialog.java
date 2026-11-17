package com.example.budgetapp.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.LayoutRes;

/** Gives the wheel pickers a stable size so the footer stays visible in landscape. */
final class LedgerPickerDialog extends Dialog {
    LedgerPickerDialog(Context context, @LayoutRes int layout) {
        super(context);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(layout);
        setCanceledOnTouchOutside(true);
        Window window = getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.42f);
        window.setGravity(Gravity.CENTER);
        View decor = window.getDecorView();
        decor.setPadding(0, 0, 0, 0);
        float density = context.getResources().getDisplayMetrics().density;
        // The layout includes a 12 dp gutter on each side of the panel.
        window.setLayout(Math.min(context.getResources().getDisplayMetrics().widthPixels,
                Math.round(444 * density)), ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
