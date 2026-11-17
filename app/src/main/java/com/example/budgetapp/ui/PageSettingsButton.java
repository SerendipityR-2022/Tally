package com.example.budgetapp.ui;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;
import android.util.TypedValue;
import androidx.appcompat.widget.AppCompatImageButton;
import com.example.budgetapp.R;

/** Settings remains discoverable from every primary destination. */
public class PageSettingsButton extends AppCompatImageButton {
    public PageSettingsButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        setImageResource(R.drawable.ic_settings_outline);
        setImageTintList(android.content.res.ColorStateList.valueOf(context.getColor(R.color.text_primary)));
        setContentDescription("设置");
        TypedValue background = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true);
        setBackgroundResource(background.resourceId);
        int padding = Math.round(12 * getResources().getDisplayMetrics().density);
        setPadding(padding, padding, padding, padding);
        setOnClickListener(v -> context.startActivity(new Intent(context, SettingsActivity.class)));
    }
}
