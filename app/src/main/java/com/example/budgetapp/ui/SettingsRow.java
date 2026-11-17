package com.example.budgetapp.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.example.budgetapp.R;
import java.util.Locale;

/** A single setting with its current value on the same line. */
public class SettingsRow extends LinearLayout {
    private final TextView title;
    private final TextView value;

    public SettingsRow(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(dp(60));
        setPadding(dp(16), dp(12), dp(12), dp(12));
        setClickable(true);
        setFocusable(true);
        TypedValue ripple = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        setBackgroundResource(ripple.resourceId);
        TypedArray attributes = context.obtainStyledAttributes(attrs, new int[]{android.R.attr.text});
        CharSequence label = attributes.getText(0);
        attributes.recycle();
        title = new TextView(context);
        title.setText(label);
        title.setTextSize(16);
        title.setTextColor(context.getColor(R.color.text_primary));
        addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        value = new TextView(context);
        value.setTextSize(13);
        value.setTextColor(context.getColor(R.color.text_secondary));
        value.setMaxWidth(dp(136));
        value.setMaxLines(2);
        value.setEllipsize(TextUtils.TruncateAt.END);
        value.setGravity(Gravity.END);
        LayoutParams valueParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        valueParams.setMarginStart(dp(12));
        addView(value, valueParams);
        ImageView chevron = new ImageView(context);
        chevron.setImageResource(R.drawable.ic_arrow_right);
        chevron.setImageTintList(android.content.res.ColorStateList.valueOf(context.getColor(R.color.icon_secondary)));
        LayoutParams iconParams = new LayoutParams(dp(18), dp(18));
        iconParams.setMarginStart(dp(8));
        addView(chevron, iconParams);
        // The row is one focus target, including its current value.
        title.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        value.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        chevron.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setValue("");
    }

    public void setValue(CharSequence currentValue) {
        value.setText(currentValue);
        value.setVisibility(TextUtils.isEmpty(currentValue) ? GONE : VISIBLE);
        setContentDescription(title.getText() + (TextUtils.isEmpty(currentValue) ? "" : "，" + currentValue));
    }

    public boolean matches(String query) {
        String terms = title.getText() + " " + (getTag() == null ? "" : getTag());
        return terms.toLowerCase(Locale.ROOT).contains(query);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
