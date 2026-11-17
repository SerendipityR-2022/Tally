package com.example.budgetapp.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.widget.ImageView;

import com.example.budgetapp.util.CategoryIcon;
import com.example.budgetapp.util.CategoryManager;

final class LedgerCategory {
    private LedgerCategory() {}

    static CategoryIcon icon(Context context, String name, int transactionType) {
        if (transactionType == 0 || transactionType == 1) {
            return CategoryManager.getIcon(context, transactionType == 0, name);
        }
        if (transactionType == 2) {
            return CategoryIcon.REPAYMENT.label.equals(name) ? CategoryIcon.REPAYMENT : CategoryIcon.TRANSFER;
        }
        return CategoryIcon.forName(name);
    }

    static void bind(ImageView view, CategoryIcon icon) {
        view.setImageResource(icon.drawable);
        view.setImageTintList(ColorStateList.valueOf(icon.tint(view.getContext())));
    }
}
