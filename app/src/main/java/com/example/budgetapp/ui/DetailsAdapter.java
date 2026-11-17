package com.example.budgetapp.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;
import com.example.budgetapp.R;
import com.example.budgetapp.database.AssetAccount;
import com.example.budgetapp.database.Transaction;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DetailsAdapter extends RecyclerView.Adapter<DetailsAdapter.ViewHolder> {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA);
    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEEE", Locale.CHINA);
    private final Map<Integer, AssetAccount> assets = new HashMap<>();
    private final Map<LocalDate, double[]> totals = new HashMap<>();
    private List<Transaction> transactions = Collections.emptyList();
    private OnTransactionClickListener listener;

    public interface OnTransactionClickListener { void onTransactionClick(Transaction transaction); }

    public void setOnTransactionClickListener(OnTransactionClickListener listener) { this.listener = listener; }

    public void setTransactions(List<Transaction> list) {
        transactions = list != null ? list : Collections.emptyList();
        totals.clear();
        for (Transaction tx : transactions) {
            if (tx.isSettled && (tx.type == 0 || tx.type == 1) && !"资产互转".equals(tx.category)) {
                totals.computeIfAbsent(day(tx), key -> new double[2])[tx.type] += tx.amount;
            }
        }
        notifyDataSetChanged();
    }

    public void setAssets(List<AssetAccount> list) {
        assets.clear();
        if (list != null) for (AssetAccount asset : list) assets.put(asset.id, asset);
        notifyDataSetChanged();
    }

    @NonNull @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context context = parent.getContext();
        LinearLayout wrapper = column(context);
        wrapper.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        LinearLayout panel = column(context);
        wrapper.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(context, 14), dp(context, 13), dp(context, 14), dp(context, 11));
        TextView date = new TextView(context);
        date.setTextColor(context.getColor(R.color.ledger_text));
        date.setTextSize(13);
        date.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        androidx.core.view.ViewCompat.setAccessibilityHeading(date, true);
        header.addView(date, new LinearLayout.LayoutParams(0, -2, 1));
        TextView summary = new TextView(context);
        summary.setTextSize(11);
        summary.setGravity(Gravity.END);
        summary.setFontFeatureSettings("tnum");
        summary.setTextColor(context.getColor(R.color.ledger_muted));
        header.addView(summary, new LinearLayout.LayoutParams(-2, -2));
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));
        View divider = new View(context);
        divider.setBackgroundColor(context.getColor(R.color.ledger_rule));
        panel.addView(divider, new LinearLayout.LayoutParams(-1, dp(context, 1)));
        View row = LayoutInflater.from(context).inflate(R.layout.item_ledger_transaction, panel, false);
        panel.addView(row);
        return new ViewHolder(wrapper, panel, header, date, summary, divider, row);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Transaction tx = transactions.get(position);
        Context context = holder.itemView.getContext();
        LocalDate day = day(tx);
        boolean first = position == 0 || !day.equals(day(transactions.get(position - 1)));
        boolean last = position + 1 == getItemCount() || !day.equals(day(transactions.get(position + 1)));
        holder.header.setVisibility(first ? View.VISIBLE : View.GONE);
        if (first) {
            LocalDate today = LocalDate.now();
            String relative = day.equals(today) ? "今天" : day.equals(today.minusDays(1)) ? "昨天" : day.format(WEEKDAY);
            holder.date.setText(day.format(DAY) + "  " + relative);
            double[] values = totals.get(day);
            holder.summary.setText(values == null ? "" : "支 " + LedgerTransactionRow.money(values[0])
                    + "    收 " + LedgerTransactionRow.money(values[1]));
        }
        LinearLayout.LayoutParams line = (LinearLayout.LayoutParams) holder.divider.getLayoutParams();
        line.setMarginStart(first ? 0 : dp(context, 63));
        holder.divider.setLayoutParams(line);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) holder.panel.getLayoutParams();
        lp.setMargins(dp(context, 16), 0, dp(context, 16), last ? dp(context, 12) : 0);
        holder.panel.setLayoutParams(lp);

        float top = first ? dp(context, 18) : 0, bottom = last ? dp(context, 18) : 0;
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadii(new float[]{top,top,top,top,bottom,bottom,bottom,bottom});
        android.content.SharedPreferences prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        int color = context.getColor(R.color.ledger_panel);
        background.setColor(prefs.getInt("theme_mode", -1) == 3 ? ColorUtils.setAlphaComponent(color, 234) : color);
        holder.panel.setBackground(background);
        holder.panel.setClipToOutline(first && last);

        AssetAccount asset = assets.get(tx.assetId);
        holder.content.bind(tx, asset == null ? null : asset.name, prefs.getBoolean("enable_currency", false), false);
        holder.row.setOnClickListener(tx.isSettled && listener != null ? v -> listener.onTransactionClick(tx) : null);
        holder.row.setClickable(tx.isSettled && listener != null);
        holder.row.setEnabled(tx.isSettled);
    }

    @Override public int getItemCount() { return transactions.size(); }

    private static LocalDate day(Transaction tx) {
        return Instant.ofEpochMilli(tx.date).atZone(ZoneId.systemDefault()).toLocalDate();
    }
    private static int dp(Context c, int v) { return Math.round(c.getResources().getDisplayMetrics().density * v); }
    private static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final LinearLayout panel, header;
        final TextView date, summary;
        final View divider, row;
        final LedgerTransactionRow content;
        ViewHolder(View wrapper, LinearLayout panel, LinearLayout header, TextView date, TextView summary, View divider, View row) {
            super(wrapper);
            this.panel = panel;
            this.header = header;
            this.date = date;
            this.summary = summary;
            this.divider = divider;
            this.row = row;
            this.content = new LedgerTransactionRow(row);
        }
    }
}
