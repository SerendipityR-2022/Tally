package com.example.budgetapp.ui;

import android.content.Context;
import android.content.ContextWrapper;
import android.util.AttributeSet;
import android.util.TypedValue;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageButton;
import com.example.budgetapp.R;

/** Consistent, accessible back navigation for standalone screens. */
public class BackButton extends AppCompatImageButton {
    public BackButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        setImageResource(R.drawable.ic_arrow_left);
        setImageTintList(android.content.res.ColorStateList.valueOf(context.getColor(R.color.text_primary)));
        setContentDescription(context.getString(R.string.navigate_back));
        TypedValue background = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true);
        setBackgroundResource(background.resourceId);
        setOnClickListener(v -> {
            Context current = getContext();
            while (current instanceof ContextWrapper) {
                if (current instanceof AppCompatActivity) {
                    ((AppCompatActivity) current).getOnBackPressedDispatcher().onBackPressed();
                    return;
                }
                current = ((ContextWrapper) current).getBaseContext();
            }
        });
    }
}
