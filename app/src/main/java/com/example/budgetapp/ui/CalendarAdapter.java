package com.example.budgetapp.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.example.budgetapp.R;
import com.example.budgetapp.database.RenewalItem;
import com.example.budgetapp.database.Transaction;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class CalendarAdapter extends RecyclerView.Adapter<CalendarAdapter.ViewHolder> {

    private List<LocalDate> days = new ArrayList<>();
    private List<Transaction> transactions = new ArrayList<>();
    private List<RenewalItem> renewalItems = new ArrayList<>(); // 支持多项续费项目
    private LocalDate selectedDate;
    private final OnDateClickListener listener;
    private int filterMode = 0;
    private YearMonth currentMonth;
    private int rowHeight;

    private boolean isBudgetEnabled = false;
    private float monthlyBudget = 0f;

    // 增加一个公开方法用于接收配置
    public void setBudgetConfig(boolean enabled, float budget) {
        if (isBudgetEnabled == enabled && Float.compare(monthlyBudget, budget) == 0) return;
        isBudgetEnabled = enabled;
        monthlyBudget = budget;
        notifyDataSetChanged();
    }

    public interface OnDateClickListener {
        void onDateClick(LocalDate date);
    }

    public CalendarAdapter(OnDateClickListener listener) {
        this.listener = listener;
    }

    public void setFilterMode(int mode) {
        if (filterMode == mode) return;
        filterMode = mode;
        notifyDataSetChanged();
    }

    public void updateData(YearMonth month, List<LocalDate> days, List<Transaction> transactions,
                           List<RenewalItem> renewals, LocalDate selection) {
        currentMonth = month;
        this.days = days;
        this.transactions = transactions;
        renewalItems = renewals != null ? renewals : new ArrayList<>();
        selectedDate = selection;
        notifyDataSetChanged();
    }

    public void setRowHeight(int height) {
        if (rowHeight == height) return;
        rowHeight = height;
        notifyDataSetChanged();
    }

    public void setSelectedDate(LocalDate date) {
        if (Objects.equals(selectedDate, date)) return;
        int previousPosition = selectedDate == null ? -1 : days.indexOf(selectedDate);
        selectedDate = date;
        int nextPosition = days.indexOf(date);
        if (previousPosition >= 0) notifyItemChanged(previousPosition);
        if (nextPosition >= 0) notifyItemChanged(nextPosition);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_calendar_day, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        if (rowHeight > 0) holder.itemView.setMinimumHeight(rowHeight);
        float density = holder.itemView.getResources().getDisplayMetrics().density;
        int screenDp = holder.itemView.getResources().getConfiguration().screenWidthDp;
        int margin = holder.itemView.getResources().getDimensionPixelSize(R.dimen.record_calendar_margin);
        int maxFace = Math.max(1, (Math.round(screenDp * density) - 2 * margin) / 7 - Math.round(4 * density));
        int face = Math.min(maxFace, Math.round(65 * density));
        if (holder.surface.getLayoutParams().width != face) {
            holder.surface.getLayoutParams().width = face;
            holder.surface.requestLayout();
        }
        LocalDate date = days.get(position);
        if (date == null) {
            holder.itemView.setVisibility(View.INVISIBLE);
            holder.itemView.setSelected(false);
            holder.itemView.setOnClickListener(null);
            holder.itemView.setContentDescription(null);
            return;
        }

        holder.itemView.setVisibility(View.VISIBLE);
        Context context = holder.itemView.getContext();
        SharedPreferences prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        boolean compact = prefs.getBoolean("minimalist_mode", false);
        LocalDate today = LocalDate.now();
        boolean isToday = date.equals(today);
        boolean isSelected = date.equals(selectedDate);
        boolean isCurrentMonth = YearMonth.from(date).equals(currentMonth);
        int incomeColor = context.getColor(R.color.income_red);
        int expenseColor = context.getColor(R.color.expense_green);

        double dailySum = 0;
        double dailyHours = 0; // 新增：统计每日工时
        double dailyExpenseForBudget = 0; // <--- 1. 新增这行，定义每日支出变量
        long start = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long end = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

        for (Transaction t : transactions) {
            if (t.date >= start && t.date < end) {

                // 【周期记账】跳过未生效的账单，不计入日历金额统计
                if (!t.isSettled) {
                    continue;
                }

                // 🌟 核心拦截：如果是资产互转，直接跳过，不参与日历下方任何数字的计算
                boolean isTransfer = (t.type == 2) || "资产互转".equals(t.category);
                if (isTransfer) {
                    continue;
                }

                // <--- 2. 新增这块：只要是支出(type==0)，就累加到预算统计里 --->
                if (t.type == 0) {
                    dailyExpenseForBudget += t.amount;
                }

                switch (filterMode) {
                    case 0: // 结余
                        if (t.type == 1) {
                            if (!"加班".equals(t.category)) dailySum += t.amount;
                        } else if (t.type == 0) { // 🌟 严格限制只有真正的支出才减去金额
                            dailySum -= t.amount;
                        }
                        break;
                    case 1: // 收入
                        if (t.type == 1 && !"加班".equals(t.category)) dailySum += t.amount;
                        break;
                    case 2: // 支出
                        if (t.type == 0) dailySum += t.amount;
                        break;
                    case 3: // 加班工资
                        if (t.type == 1 && "加班".equals(t.category)) dailySum += t.amount;
                        break;
                    case 4: // 加班工时
                        if (t.type == 1 && "加班".equals(t.category)) {
                            if (t.note != null) {
                                java.util.regex.Matcher m = java.util.regex.Pattern.compile("时长:\\s*([0-9.]+)\\s*小时").matcher(t.note);
                                if (m.find()) {
                                    try {
                                        dailyHours += Double.parseDouble(m.group(1));
                                    } catch (NumberFormatException ignored) {}
                                }
                            }
                            // 赋值 dailySum 让底部判断有数据
                            dailySum += t.amount;
                        }
                        break;
                }
            }
        }

        boolean hasAmount = Math.abs(dailySum) > 0.001 || dailyHours > 0;
        String caption = "";
        int captionColor = context.getColor(R.color.ledger_muted);
        if (hasAmount) {
            if (filterMode == 4) {
                caption = String.format(Locale.getDefault(), "%.1fh", dailyHours);
                captionColor = context.getColor(R.color.app_blue);
            } else {
                caption = String.format(Locale.getDefault(), "%.2f", dailySum);
                if (filterMode == 3) {
                    captionColor = context.getColor(R.color.calendar_overtime_amount);
                } else {
                    captionColor = filterMode == 2 || dailySum < 0 ? expenseColor : incomeColor;
                }
            }
        } else if (!compact && isCurrentMonth) {
            caption = lunarCaption(date);
        }

        boolean isRenewalDay = false;
        for (RenewalItem item : renewalItems) {
            if (isRenewalDate(item, date)) {
                isRenewalDay = true;
                break;
            }
        }
        boolean showsBudget = isBudgetEnabled && monthlyBudget > 0 && isCurrentMonth && !date.isAfter(today);
        boolean overBudget = showsBudget && dailyExpenseForBudget > monthlyBudget / date.lengthOfMonth();
        int surface = 0;
        if (isSelected) {
            surface = R.drawable.bg_record_calendar_selected;
        } else if (isRenewalDay) {
            surface = R.drawable.bg_record_calendar_renewal;
        } else if (showsBudget) {
            surface = overBudget ? R.drawable.bg_record_calendar_budget_exceed : R.drawable.bg_record_calendar_budget_safe;
        }
        holder.surface.setBackgroundResource(surface);
        holder.itemView.setSelected(isSelected);
        holder.tvDay.setText(String.valueOf(date.getDayOfMonth()));
        holder.tvDay.setTextColor(context.getColor(isSelected ? R.color.real_white : R.color.ledger_text));
        holder.underline.setVisibility(isToday || isSelected ? View.VISIBLE : View.INVISIBLE);
        holder.underline.setBackgroundResource(isToday ? R.drawable.bg_record_calendar_today
                : R.drawable.bg_record_calendar_selection_line);
        holder.underline.setBackgroundTintList(isToday && isSelected
                ? android.content.res.ColorStateList.valueOf(0xFFFFABB0) : null);

        // Keep captions on one baseline; amounts are stronger than the optional lunar text.
        if (holder.hasAmount != hasAmount) {
            holder.hasAmount = hasAmount;
            holder.tvNet.setTypeface(hasAmount ? ViewHolder.AMOUNT_FONT : ViewHolder.LUNAR_FONT);
            int captionSize = context.getResources().getDimensionPixelSize(R.dimen.record_calendar_caption_size);
            if (hasAmount) {
                int minSize = context.getResources().getDimensionPixelSize(R.dimen.record_calendar_amount_min_size);
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(holder.tvNet, minSize,
                        Math.max(minSize + 1, captionSize), 1, TypedValue.COMPLEX_UNIT_PX);
            } else {
                TextViewCompat.setAutoSizeTextTypeWithDefaults(holder.tvNet, TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE);
                holder.tvNet.setTextSize(TypedValue.COMPLEX_UNIT_PX, captionSize);
            }
        }
        holder.tvNet.setText(caption);
        holder.tvNet.setTextColor(isSelected ? context.getColor(R.color.real_white) : captionColor);
        holder.tvNet.setVisibility(caption.isEmpty() ? View.INVISIBLE : View.VISIBLE);

        String description = date.toString();
        if (isToday) description += context.getString(R.string.calendar_day_today_description);
        if (!caption.isEmpty()) description += "，" + caption;
        if (isRenewalDay) description += context.getString(R.string.calendar_day_renewal_description);
        if (overBudget) description += context.getString(R.string.calendar_day_over_budget_description);
        holder.itemView.setContentDescription(description);
        holder.itemView.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
            listener.onDateClick(date);
        });
    }

    private String lunarCaption(LocalDate date) {
        try {
            com.nlf.calendar.Solar solar = com.nlf.calendar.Solar.fromYmd(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
            com.nlf.calendar.Lunar lunar = solar.getLunar();
            List<String> lunarFestivals = lunar.getFestivals();
            if (lunarFestivals != null && !lunarFestivals.isEmpty()) return lunarFestivals.get(0);
            List<String> solarFestivals = solar.getFestivals();
            if (solarFestivals != null && !solarFestivals.isEmpty()) return solarFestivals.get(0);
            String solarTerm = lunar.getJieQi();
            if (solarTerm != null && !solarTerm.isEmpty()) return solarTerm;
            return lunar.getDay() == 1 ? lunar.getMonthInChinese() + "月" : lunar.getDayInChinese();
        } catch (Exception ignored) {
            return "";
        }
    }

    // 新增：用于判断目标日期是否为续费日（包含自定义周期的计算）
    private boolean isRenewalDate(RenewalItem item, LocalDate targetDate) {
        if ("Month".equals(item.period)) {
            return targetDate.getDayOfMonth() == item.day;
        } else if ("Year".equals(item.period)) {
            return targetDate.getMonthValue() == item.month && targetDate.getDayOfMonth() == item.day;
        } else if ("Custom".equals(item.period)) {
            // 安全检查，兼容旧数据
            int startYear = item.year > 2000 ? item.year : targetDate.getYear();
            LocalDate startDate;
            try {
                startDate = LocalDate.of(startYear, item.month, item.day);
            } catch (Exception e) {
                return false;
            }

            // 如果当前查看的日期在起算日期之前，则不触发
            if (targetDate.isBefore(startDate)) {
                return false;
            }

            int value = item.durationValue > 0 ? item.durationValue : 1;

            if ("Day".equals(item.durationUnit)) {
                long days = java.time.temporal.ChronoUnit.DAYS.between(startDate, targetDate);
                return days % value == 0;
            } else if ("Week".equals(item.durationUnit)) {
                long days = java.time.temporal.ChronoUnit.DAYS.between(startDate, targetDate);
                return days % (7L * value) == 0;
            } else if ("Month".equals(item.durationUnit)) {
                // 计算相差的自然月数
                int diffMonths = (targetDate.getYear() - startDate.getYear()) * 12 + (targetDate.getMonthValue() - startDate.getMonthValue());
                if (diffMonths >= 0 && diffMonths % value == 0) {
                    return startDate.plusMonths(diffMonths).equals(targetDate);
                }
                return false;
            } else if ("Year".equals(item.durationUnit)) {
                int diffYears = targetDate.getYear() - startDate.getYear();
                if (diffYears >= 0 && diffYears % value == 0) {
                    return startDate.plusYears(diffYears).equals(targetDate);
                }
                return false;
            }
        }
        return false;
    }

    @Override
    public int getItemCount() {
        return days.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        static final Typeface AMOUNT_FONT = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        static final Typeface LUNAR_FONT = Typeface.create("sans-serif", Typeface.NORMAL);
        final TextView tvDay, tvNet;
        final View surface, underline;
        boolean hasAmount;

        ViewHolder(View v) {
            super(v);
            tvDay = v.findViewById(R.id.tv_day);
            tvNet = v.findViewById(R.id.tv_net_amount);
            surface = v.findViewById(R.id.calendar_day_surface);
            underline = v.findViewById(R.id.calendar_day_underline);
        }
    }
}
