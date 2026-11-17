package com.example.budgetapp.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.example.budgetapp.R;
import com.example.budgetapp.database.AssetAccount;
import com.example.budgetapp.util.CurrencyUtils;
import com.example.budgetapp.util.ExchangeRateManager;
import com.example.budgetapp.viewmodel.FinanceViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared account transfer form for Assets and the manual entry sheet. */
final class AssetTransferDialog {
    private AssetTransferDialog() {}

    static void show(Context context, FinanceViewModel model, List<AssetAccount> accounts) {
        show(context, model, accounts, false, 0, "", "", System.currentTimeMillis(), () -> {});
    }

    static void show(Context context, FinanceViewModel model, List<AssetAccount> accounts, boolean repayment,
                     int sourceId, String amountDraft, String noteDraft, long date, Runnable saved) {
        List<AssetAccount> sources = new ArrayList<>();
        List<AssetAccount> destinations = new ArrayList<>();
        if (accounts != null) {
            for (AssetAccount account : accounts) {
                if (!repayment || account.type == 0 || account.type == 3) sources.add(account);
                if (!repayment || account.type == 1) destinations.add(account);
            }
        }
        if (repayment ? sources.isEmpty() || destinations.isEmpty() : sources.size() < 2) {
            Toast.makeText(context, repayment ? "还款需要一个付款账户和一个负债账户"
                    : "转账需要两个账户", Toast.LENGTH_SHORT).show();
            return;
        }

        View view = LayoutInflater.from(context).inflate(R.layout.dialog_transfer_asset, null);
        AlertDialog dialog = new AlertDialog.Builder(context).setView(view).create();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        String title = repayment ? "还款" : "转账";
        ((TextView) view.findViewById(R.id.tv_transfer_title)).setText(title);
        Spinner from = view.findViewById(R.id.spinner_from_account);
        Spinner to = view.findViewById(R.id.spinner_to_account);
        EditText amount = view.findViewById(R.id.et_transfer_amount);
        EditText discount = view.findViewById(R.id.et_transfer_discount);
        EditText note = view.findViewById(R.id.et_transfer_note);
        amount.setHint(title + "金额");
        amount.setText(amountDraft);
        note.setText(noteDraft);
        from.setAdapter(accountsAdapter(context, sources, repayment ? "选择付款账户" : "选择转出账户"));
        to.setAdapter(accountsAdapter(context, destinations, repayment ? "选择负债账户" : "选择转入账户"));
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).id == sourceId) { from.setSelection(i + 1); break; }
        }

        TextView hint = new TextView(context);
        hint.setTextSize(12);
        hint.setTextColor(context.getColor(R.color.text_secondary));
        hint.setVisibility(View.GONE);
        ViewGroup fields = (ViewGroup) amount.getParent();
        fields.addView(hint, fields.indexOfChild(amount) + 1);
        SharedPreferences settings = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        if (settings.getBoolean("enable_currency", false) && settings.getBoolean("single_currency_mode", false)) {
            ExchangeRateManager rates = new ExchangeRateManager(context);
            Handler main = new Handler(Looper.getMainLooper());
            int[] revision = {0};
            Runnable refresh = () -> {
                int request = ++revision[0];
                hint.setVisibility(View.GONE);
                int a = from.getSelectedItemPosition() - 1;
                int b = to.getSelectedItemPosition() - 1;
                double value = parseMoney(amount);
                if (a < 0 || b < 0 || !Double.isFinite(value) || value <= 0) return;
                String sourceSymbol = currency(sources.get(a)), destSymbol = currency(destinations.get(b));
                String sourceCode = CurrencyUtils.symbolToCode(sourceSymbol), destCode = CurrencyUtils.symbolToCode(destSymbol);
                if (sourceCode.equals(destCode)) return;
                rates.getExchangeRate(sourceCode, destCode, new ExchangeRateManager.ExchangeRateCallback() {
                    private void update(String text) {
                        main.post(() -> {
                            if (request != revision[0] || !dialog.isShowing()) return;
                            hint.setText(text);
                            hint.setVisibility(View.VISIBLE);
                        });
                    }
                    @Override public void onSuccess(double rate) {
                        update(String.format(Locale.CHINA, "%s%.2f ≈ %s%.2f", sourceSymbol, value, destSymbol, value * rate));
                    }
                    @Override public void onError(String error) { update("汇率获取失败，将使用缓存汇率"); }
                });
            };
            AdapterView.OnItemSelectedListener accountChange = new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View row, int position, long id) { refresh.run(); }
                @Override public void onNothingSelected(AdapterView<?> parent) { refresh.run(); }
            };
            from.setOnItemSelectedListener(accountChange);
            to.setOnItemSelectedListener(accountChange);
            amount.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refresh.run(); }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        view.findViewById(R.id.btn_cancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.btn_confirm).setOnClickListener(v -> {
            int a = from.getSelectedItemPosition() - 1, b = to.getSelectedItemPosition() - 1;
            if (a < 0 || b < 0) {
                Toast.makeText(context, "请选择两个账户", Toast.LENGTH_SHORT).show();
                return;
            }
            AssetAccount source = sources.get(a), dest = destinations.get(b);
            if (source.id == dest.id) {
                Toast.makeText(context, "请选择不同的账户", Toast.LENGTH_SHORT).show();
                return;
            }
            double value = parseMoney(amount);
            if (!Double.isFinite(value) || value <= 0) {
                amount.setError("请输入有效的" + title + "金额");
                return;
            }
            double reduction = discount.length() == 0 ? 0 : parseMoney(discount);
            if (!Double.isFinite(reduction) || reduction < 0 || reduction >= value) {
                discount.setError("优惠金额需小于" + title + "金额");
                return;
            }
            model.transferAsset(source, dest, value, reduction, note.getText().toString().trim(), date, repayment);
            dialog.dismiss();
            saved.run();
        });
        dialog.show();
    }

    private static double parseMoney(EditText field) {
        try { return Double.parseDouble(field.getText().toString().trim()); }
        catch (NumberFormatException e) { return Double.NaN; }
    }

    private static String currency(AssetAccount account) {
        return account.currencySymbol == null || account.currencySymbol.isEmpty() ? "¥" : account.currencySymbol;
    }

    private static ArrayAdapter<String> accountsAdapter(Context context, List<AssetAccount> accounts, String placeholder) {
        List<String> entries = new ArrayList<>();
        entries.add(placeholder);
        for (AssetAccount account : accounts) {
            entries.add(String.format(Locale.CHINA, "%s (%s%.2f)", account.name, currency(account), account.amount));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, R.layout.item_form_spinner_value, entries) {
            @Override public boolean isEnabled(int position) { return position > 0; }
            @Override public View getDropDownView(int position, View convertView, @NonNull ViewGroup parent) {
                return tint(super.getDropDownView(position, convertView, parent), position);
            }
            @NonNull @Override public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                return tint(super.getView(position, convertView, parent), position);
            }
            private View tint(View row, int position) {
                ((TextView) row).setTextColor(context.getColor(position == 0 ? R.color.text_secondary : R.color.text_primary));
                return row;
            }
        };
        adapter.setDropDownViewResource(R.layout.item_form_spinner_option);
        return adapter;
    }
}
