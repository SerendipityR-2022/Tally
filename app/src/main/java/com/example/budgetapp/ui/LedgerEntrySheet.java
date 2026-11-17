package com.example.budgetapp.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.text.Editable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.WindowMetrics;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;
import androidx.core.view.OneShotPreDrawListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import com.example.budgetapp.R;
import com.example.budgetapp.database.AssetAccount;
import com.example.budgetapp.util.CategoryIcon;
import com.example.budgetapp.viewmodel.FinanceViewModel;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Shared sheet, keyboard and amount preparation for the manual transaction editors. */
final class LedgerEntrySheet {
    private static final int[] DIGITS = {R.id.entry_key_0, R.id.entry_key_1, R.id.entry_key_2, R.id.entry_key_3,
            R.id.entry_key_4, R.id.entry_key_5, R.id.entry_key_6, R.id.entry_key_7, R.id.entry_key_8, R.id.entry_key_9};
    private final AlertDialog dialog;
    private final LedgerEntryLayout root;
    private final LedgerCategoryGrid categories;
    private final EditText amount;
    private final TextView expression;
    private BigDecimal left;
    private boolean subtract;
    private boolean imeVisible;
    private int imeBottom;
    private boolean revealScheduled;

    LedgerEntrySheet(AlertDialog dialog, View view) {
        this.dialog = dialog;
        root = (LedgerEntryLayout) view;
        categories = root.findViewById(R.id.rv_category);
        amount = root.findViewById(R.id.et_amount);
        expression = root.findViewById(R.id.entry_expression);
        amount.setShowSoftInputOnFocus(false);
        root.findViewById(R.id.entry_close).setOnClickListener(v -> dialog.dismiss());
        for (int i = 0; i < DIGITS.length; i++) {
            String digit = Integer.toString(i);
            key(DIGITS[i], () -> input(digit));
        }
        key(R.id.entry_key_decimal, () -> input("."));
        key(R.id.entry_key_delete, this::erase);
        key(R.id.entry_key_plus, () -> operator(false));
        key(R.id.entry_key_minus, () -> operator(true));
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            boolean visible = insets.isVisible(WindowInsetsCompat.Type.ime());
            int bottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            if (imeVisible != visible || imeBottom != bottom) {
                imeVisible = visible;
                imeBottom = bottom;
                updateKeys();
                if (imeVisible) revealFocusedEditor();
            }
            return insets;
        });
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            updateKeys();
            if (newFocus instanceof EditText && newFocus != amount) revealFocusedEditor();
        });
        root.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (b - t != oldB - oldT) revealFocusedEditor();
        });
        amount.setOnClickListener(v -> {
            InputMethodManager ime = (InputMethodManager) root.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (ime != null) ime.hideSoftInputFromWindow(amount.getWindowToken(), 0);
            updateKeys();
        });
        android.content.res.Configuration config = root.getResources().getConfiguration();
        if (config.screenWidthDp >= 550) {
            alignTopControls();
            if (config.screenHeightDp < 650) placeAccountBesideAmount();
        } else if (config.screenHeightDp < 650) {
            putCategoriesFirst();
        }
    }

    private void revealFocusedEditor() {
        View focused = root.findFocus();
        if (revealScheduled || !(focused instanceof EditText) || focused == amount) return;
        revealScheduled = true;
        OneShotPreDrawListener.add(root, () -> {
            revealScheduled = false;
            View editor = root.findFocus();
            if (editor instanceof EditText && editor != amount && editor.isShown()) {
                editor.requestRectangleOnScreen(new Rect(0, 0, editor.getWidth(), editor.getHeight()), true);
            }
        });
    }

    private void putCategoriesFirst() {
        View asset = root.findViewById(R.id.entry_asset_field);
        View categories = root.findViewById(R.id.rv_category);
        LinearLayout body = (LinearLayout) asset.getParent();
        ViewGroup.LayoutParams layout = asset.getLayoutParams();
        body.removeView(asset);
        body.addView(asset, body.indexOfChild(categories) + 1, layout);
    }

    private void placeAccountBesideAmount() {
        View asset = root.findViewById(R.id.entry_asset_field);
        View amountControls = root.findViewById(R.id.entry_amount_controls);
        LinearLayout top = (LinearLayout) amountControls.getParent();
        int index = top.indexOfChild(amountControls);
        top.removeView(amountControls);
        ((ViewGroup) asset.getParent()).removeView(asset);

        LinearLayout row = new LinearLayout(root.getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams assetParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        assetParams.setMarginEnd(dp(8));
        row.addView(asset, assetParams);
        row.addView(amountControls, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.topMargin = dp(5);
        top.addView(row, index, rowParams);
    }

    private void alignTopControls() {
        LinearLayout top = root.findViewById(R.id.entry_top_fields);
        View types = root.findViewById(R.id.rg_type);
        View dates = root.findViewById(R.id.entry_date_controls);
        top.removeView(types);
        top.removeView(dates);
        LinearLayout row = new LinearLayout(root.getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams typeParams = new LinearLayout.LayoutParams(0, -2, 1.15f);
        typeParams.setMarginEnd(dp(10));
        row.addView(types, typeParams);
        row.addView(dates, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(row, 0, new LinearLayout.LayoutParams(-1, -2));
    }

    void show() {
        root.findViewById(R.id.entry_asset_field).setVisibility(root.findViewById(R.id.sp_asset).getVisibility());
        root.findViewById(R.id.entry_currency_fallback).setVisibility(root.findViewById(R.id.btn_currency).getVisibility() == View.VISIBLE
                ? View.GONE : View.VISIBLE);
        root.requestFocus();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        dialog.show();
        if (window != null) {
            // The platform AlertDialog custom panel wraps its content independently of the window height.
            // Mount the sheet directly so its keyboard is anchored to the window's lower edge.
            if (root.getParent() instanceof ViewGroup) ((ViewGroup) root.getParent()).removeView(root);
            window.setContentView(root, new ViewGroup.LayoutParams(-1, -2));
            root.requestFocus();
            sizeWithinSystemBars(window);
        }
        ViewCompat.requestApplyInsets(root);
        if (categories.getAdapter() instanceof CategoryAdapter && categories.getLayoutManager() instanceof GridLayoutManager) {
            CategoryAdapter adapter = (CategoryAdapter) categories.getAdapter();
            GridLayoutManager layout = (GridLayoutManager) categories.getLayoutManager();
            OneShotPreDrawListener.add(categories, () -> {
                int selected = adapter.selectedPosition();
                if (selected > layout.findLastCompletelyVisibleItemPosition()) categories.scrollToPosition(selected);
            });
        }
    }

    private void sizeWithinSystemBars(Window window) {
        android.content.res.Configuration config = root.getResources().getConfiguration();
        int width = dp(config.screenWidthDp);
        int height = dp(config.screenHeightDp);
        WindowManager.LayoutParams attrs = window.getAttributes();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Window bounds include the system bars on newer Android versions.
            WindowMetrics metrics = window.getWindowManager().getCurrentWindowMetrics();
            int types = WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout();
            android.graphics.Insets safe = metrics.getWindowInsets().getInsetsIgnoringVisibility(types);
            width = metrics.getBounds().width() - safe.left - safe.right;
            height = metrics.getBounds().height() - safe.top - safe.bottom;
            // Floating windows must explicitly fit the visible IME as well as the system bars.
            attrs.setFitInsetsTypes(types | WindowInsets.Type.ime());
            attrs.setFitInsetsIgnoringVisibility(false);
        }
        attrs.width = Math.max(1, Math.min(dp(560), width - dp(16)));
        attrs.height = WindowManager.LayoutParams.WRAP_CONTENT;
        root.setMaxHeight(Math.max(1, height - dp(8)));
        window.setAttributes(attrs);
    }

    void bindDateActions(Runnable date, Runnable time) {
        attach(R.id.tv_dialog_date, date);
        attach(R.id.entry_time, time);
    }

    void updateDate(Calendar calendar) {
        Context context = root.getContext();
        java.time.LocalDate day = java.time.Instant.ofEpochMilli(calendar.getTimeInMillis())
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        java.time.LocalDate today = java.time.LocalDate.now();
        String label = day.equals(today) ? "今天" : new SimpleDateFormat(day.getYear() == today.getYear()
                ? "M月d日" : "yyyy/M/d", Locale.CHINA).format(calendar.getTime());
        ((TextView) root.findViewById(R.id.tv_dialog_date)).setText(label);
        java.text.DateFormat time = android.text.format.DateFormat.getTimeFormat(context);
        ((TextView) root.findViewById(R.id.entry_time)).setText(time.format(calendar.getTime()));
    }

    void bindAccountActions(CategoryAdapter categories, FinanceViewModel model, Calendar calendar, List<AssetAccount> linkedAccounts) {
        categories.setAccountActions(action -> {
            if (action == CategoryIcon.LEND) {
                RadioGroup types = root.findViewById(R.id.rg_type);
                types.check(R.id.rb_lend);
                return;
            }
            Spinner account = root.findViewById(R.id.sp_asset);
            int index = account.getSelectedItemPosition();
            int source = index >= 0 && index < linkedAccounts.size() ? linkedAccounts.get(index).id : 0;
            BigDecimal draft = calculatedAmount();
            EditText remark = root.findViewById(R.id.et_remark);
            AssetTransferDialog.show(root.getContext(), model, model.getAllAssets().getValue(), action == CategoryIcon.REPAYMENT,
                    source, draft != null && draft.signum() > 0 ? draft.stripTrailingZeros().toPlainString() : "",
                    remark.getText().toString(), calendar.getTimeInMillis(), dialog::dismiss);
        });
    }

    void bindSave(boolean newEntry, Consumer<Boolean> listener) {
        View again = root.findViewById(R.id.entry_save_again);
        again.setVisibility(newEntry ? View.VISIBLE : View.GONE);
        TextView save = root.findViewById(R.id.btn_save);
        save.setText(newEntry ? R.string.record_save : R.string.record_save_changes);
        LinearLayout.LayoutParams saveParams = (LinearLayout.LayoutParams) save.getLayoutParams();
        // Editing leaves room in the bottom row; measure the full label before distributing that room.
        saveParams.width = newEntry ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT;
        save.setLayoutParams(saveParams);
        save.setOnClickListener(v -> save(v, false, listener));
        again.setOnClickListener(v -> save(v, true, listener));
    }

    private void save(View v, boolean again, Consumer<Boolean> listener) {
        if (!finishAmount()) return;
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        listener.accept(again);
    }

    private void attach(int id, Runnable action) {
        View v = root.findViewById(id);
        v.setOnClickListener(view -> action.run());
        v.setFocusable(true);
    }

    private void key(int id, Runnable action) {
        root.findViewById(id).setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            action.run();
            revealAmount();
        });
    }

    private void revealAmount() {
        Rect visible = new Rect();
        if (!amount.getGlobalVisibleRect(visible) || visible.height() < amount.getHeight()) {
            amount.requestFocus();
            amount.requestRectangleOnScreen(new Rect(0, 0, amount.getWidth(), amount.getHeight()), true);
        }
    }

    private void updateKeys() {
        View focused = root.findFocus();
        boolean typingText = focused instanceof EditText && focused != amount;
        int mode = typingText || imeVisible ? View.GONE : View.VISIBLE;
        setVisibility(root.findViewById(R.id.entry_numeric_rows), mode);
        setVisibility(root.findViewById(R.id.entry_key_0), mode);
        setVisibility(root.findViewById(R.id.entry_key_decimal), mode);
    }

    private static void setVisibility(View view, int mode) {
        if (view.getVisibility() != mode) view.setVisibility(mode);
    }

    private void input(String digit) {
        Editable text = amount.getText();
        int start = amount.getSelectionStart(), end = amount.getSelectionEnd();
        if (start < 0 || end < 0) start = end = text.length();
        int from = Math.min(start, end), to = Math.max(start, end);
        String value = text.subSequence(0, from) + digit + text.subSequence(to, text.length()).toString();
        if (digit.equals(".") && value.equals(".")) {
            text.replace(0, text.length(), "0.");
            amount.setSelection(text.length());
        } else if (value.matches("[0-9]{0,12}(?:\\.[0-9]{0,2})?")) {
            text.replace(from, to, digit);
            amount.setSelection(Math.min(from + digit.length(), text.length()));
        }
    }

    private void erase() {
        Editable text = amount.getText();
        int start = amount.getSelectionStart(), end = amount.getSelectionEnd();
        if (start < 0 || end < 0) start = end = text.length();
        if (start != end) text.delete(Math.min(start, end), Math.max(start, end));
        else if (start > 0) text.delete(start - 1, start);
        else if (text.length() == 0 && left != null) {
            setAmount(left);
            clearOperator();
        }
    }

    private void operator(boolean minus) {
        BigDecimal entered = decimal();
        if (entered == null) {
            if (left != null) { subtract = minus; showOperator(); }
            return;
        }
        BigDecimal next = left == null ? entered : (subtract ? left.subtract(entered) : left.add(entered));
        if (!validSize(next) || next.signum() < 0) { invalidAmount(); return; }
        left = next;
        subtract = minus;
        amount.setText("");
        showOperator();
    }

    private BigDecimal calculatedAmount() {
        BigDecimal entered = decimal();
        if (left == null) return entered;
        if (entered == null) return left;
        return subtract ? left.subtract(entered) : left.add(entered);
    }

    private boolean finishAmount() {
        BigDecimal entered = calculatedAmount();
        if (entered == null || !validSize(entered) || entered.signum() <= 0) {
            invalidAmount();
            return false;
        }
        setAmount(entered);
        clearOperator();
        return true;
    }

    private BigDecimal decimal() {
        try { return new BigDecimal(amount.getText().toString()); }
        catch (NumberFormatException e) { return null; }
    }

    private static boolean validSize(BigDecimal amount) {
        return amount.scale() <= 2 && amount.abs().compareTo(new BigDecimal("1000000000000")) < 0;
    }

    private void setAmount(BigDecimal value) {
        amount.setText(value.stripTrailingZeros().toPlainString());
        amount.setSelection(amount.length());
    }

    private void showOperator() {
        expression.setText(left.stripTrailingZeros().toPlainString() + (subtract ? "  −" : "  +"));
        expression.setVisibility(View.VISIBLE);
    }

    private void clearOperator() {
        left = null;
        expression.setVisibility(View.GONE);
    }

    private void invalidAmount() {
        Toast.makeText(root.getContext(), "请输入有效的金额", Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) { return Math.round(value * root.getResources().getDisplayMetrics().density); }
}
