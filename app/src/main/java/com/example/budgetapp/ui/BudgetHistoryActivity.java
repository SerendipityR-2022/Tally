package com.example.budgetapp.ui;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.budgetapp.R;
import com.example.budgetapp.database.Goal;
import com.example.budgetapp.database.Transaction;
import com.example.budgetapp.util.DailyBudgetPlan;
import com.example.budgetapp.util.MonthlyBudgetHistory;
import com.example.budgetapp.viewmodel.FinanceViewModel;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BudgetHistoryActivity extends AppCompatActivity {

    private RecyclerView rvTimeline;
    private FinanceViewModel viewModel;
    private List<Transaction> transactions;
    private List<Goal> goals;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        setContentView(R.layout.activity_budget_history);

        View rootLayout = findViewById(R.id.root_layout);
        SettingsPage.applyInsets(rootLayout);

        rvTimeline = findViewById(R.id.rv_timeline);
        rvTimeline.setLayoutManager(new LinearLayoutManager(this));

        viewModel = new ViewModelProvider(this).get(FinanceViewModel.class);
        viewModel.getAllTransactions().observe(this, transactions -> {
            this.transactions = transactions;
            refreshTimeline();
        });
        viewModel.getAllGoals().observe(this, goals -> {
            this.goals = goals;
            refreshTimeline();
        });
    }

    @Override protected void onResume() {
        super.onResume();
        refreshTimeline();
    }

    private void refreshTimeline() {
        if (transactions == null || goals == null) return;
        MonthlyBudgetHistory.recordCurrent(this);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        YearMonth present = YearMonth.from(today);
        YearMonth start = MonthlyBudgetHistory.earliestMonth(this, present);
        for (Goal goal : goals) {
            if (goal.createdAt <= 0) continue;
            YearMonth created = YearMonth.from(Instant.ofEpochMilli(goal.createdAt).atZone(zone));
            if (created.isBefore(start)) start = created;
        }
        List<TimelineItem> timeline = new ArrayList<>();
        List<Goal> pendingGoals = new ArrayList<>(goals);

        // 按优先级和时间排序
        pendingGoals.sort((g1, g2) -> {
            if (g1.isPriority && !g2.isPriority) return -1;
            if (!g1.isPriority && g2.isPriority) return 1;
            return Long.compare(g1.createdAt, g2.createdAt);
        });

        double pool = 0;
        YearMonth planMonth = null;
        DailyBudgetPlan plan = null;
        double dailyBudget = 0;

        for (LocalDate d = start.atDay(1); !d.isAfter(today); d = d.plusDays(1)) {
            YearMonth month = YearMonth.from(d);
            if (!month.equals(planMonth)) {
                planMonth = month;
                Double amount = MonthlyBudgetHistory.amountFor(this, month, present);
                plan = amount == null || amount <= 0 ? null : new DailyBudgetPlan(month, today, zone, amount, false, transactions);
                dailyBudget = plan == null ? 0 : amount / month.lengthOfMonth();
                if (month.isBefore(present)) {
                    long timestamp = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1;
                    timeline.add(new MonthItem(timestamp, month, amount, plan != null ? plan.remainingAfterKnownExpenses() : null));
                }
            }
            if (pendingGoals.isEmpty()) continue;
            double expenseToday = plan == null ? 0 : plan.knownExpenseOn(d.getDayOfMonth());

            // 判断这一天是否存在尚未完成的目标
            boolean hasActiveGoal = false;
            for (Goal g : pendingGoals) {
                LocalDate createDate = Instant.ofEpochMilli(g.createdAt).atZone(zone).toLocalDate();
                if (!d.isBefore(createDate)) {
                    hasActiveGoal = true;
                    break;
                }
            }

            // 只有存在激活目标时，才把当天的净余存入资金池
            if (hasActiveGoal) {
                pool += (dailyBudget - expenseToday);
            } else {
                pool = 0; // 防止历史结余变成死水，瞬间填满未来的新目标
            }

            // 检查目标是否达成
            java.util.Iterator<Goal> it = pendingGoals.iterator();
            while (it.hasNext()) {
                Goal g = it.next();
                LocalDate goalCreateDate = Instant.ofEpochMilli(g.createdAt).atZone(zone).toLocalDate();
                if (d.isBefore(goalCreateDate)) continue;

                // 劫持手动完成的目标
                if (g.isFinished) {
                    LocalDate finishDate = Instant.ofEpochMilli(g.finishedDate).atZone(zone).toLocalDate();
                    if (!d.isBefore(finishDate)) { // 当推演到用户点击完成的那一天
                        long ts = d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli();
                        timeline.add(new GoalItem(ts, g.name, d, g));
                        it.remove();
                        continue; // 手动完成的不消耗资金池
                    }
                }

                // 计算是否自动达标
                double needed = Math.max(0, g.targetAmount - g.savedAmount);
                if (needed <= 0) {
                    // 仅靠基础资金就完成了
                    long ts = d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli();
                    timeline.add(new GoalItem(ts, g.name, d, g));
                    it.remove();
                } else if (pool >= needed) {
                    // 资金池填满了目标
                    pool -= needed;
                    long ts = d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli();
                    timeline.add(new GoalItem(ts, g.name, d, g));
                    it.remove();
                } else {
                    break; // 资金不够填目前的最高优目标，停止当天的顺延分配
                }
            }
        }

        Collections.sort(timeline, (a, b) -> Long.compare(a.getTimestamp(), b.getTimestamp()));
        rvTimeline.setAdapter(new TimelineAdapter(timeline));
    }
    /**
     * 弹出确认删除目标记录的对话框
     */
    private void showDeleteGoalDialog(Goal goal) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_confirm_delete, null);
        builder.setView(view);

        android.app.AlertDialog confirmDialog = builder.create();
        if (confirmDialog.getWindow() != null) {
            confirmDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }

        TextView tvMessage = view.findViewById(R.id.tv_dialog_message);
        if (tvMessage != null) {
            tvMessage.setText("确定要删除历史记录中的“" + goal.name + "”吗？\n删除后将无法恢复。");
        }

        View btnConfirm = view.findViewById(R.id.btn_dialog_confirm);
        if (btnConfirm != null) {
            btnConfirm.setOnClickListener(v -> {
                viewModel.deleteGoal(goal);
                confirmDialog.dismiss();
                android.widget.Toast.makeText(this, "已删除目标记录", android.widget.Toast.LENGTH_SHORT).show();
            });
        }

        View btnCancel = view.findViewById(R.id.btn_dialog_cancel);
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> confirmDialog.dismiss());
        }

        confirmDialog.show();
    }

    // --- 适配器结构 ---

    interface TimelineItem {
        long getTimestamp();
        int getType();
    }

    static class MonthItem implements TimelineItem {
        final long timestamp;
        final YearMonth month;
        final Double budget, surplus;
        MonthItem(long timestamp, YearMonth month, Double budget, Double surplus) {
            this.timestamp = timestamp;
            this.month = month;
            this.budget = budget;
            this.surplus = surplus;
        }
        @Override public long getTimestamp() { return timestamp; }
        @Override public int getType() { return 0; }
    }

    static class GoalItem implements TimelineItem {
        long timestamp;
        String goalName;
        LocalDate achievedDate;
        Goal goal; // 【新增】：保存目标对象本身

        GoalItem(long ts, String name, LocalDate date, Goal goal) {
            timestamp = ts;
            goalName = name;
            achievedDate = date;
            this.goal = goal; // 【新增】
        }
        @Override public long getTimestamp() {
            return timestamp;
        }
        @Override public int getType() {
            return 1;
        }
    }

    // ... TimelineItem 接口和实体类保持不变 ...

    private class TimelineAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        List<TimelineItem> items;
        TimelineAdapter(List<TimelineItem> items) { this.items = items; }

        @Override public int getItemViewType(int position) { return items.get(position).getType(); }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == 0) {
                return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_timeline_month, parent, false)) {};
            } else {
                return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_timeline_goal, parent, false)) {};
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            TimelineItem item = items.get(position);
            if (item.getType() == 0) {
                MonthItem mItem = (MonthItem) item;
                TextView tvTitle = holder.itemView.findViewById(R.id.tv_month_title);
                TextView tvSubtitle = holder.itemView.findViewById(R.id.tv_month_subtitle);
                TextView tvSurplus = holder.itemView.findViewById(R.id.tv_month_surplus);

                tvTitle.setText(getString(R.string.budget_history_month, mItem.month.getYear(), mItem.month.getMonthValue()));
                tvSubtitle.setText(mItem.budget == null ? getString(R.string.budget_history_not_recorded)
                        : mItem.budget <= 0 ? getString(R.string.budget_history_not_set)
                        : getString(R.string.budget_history_amount, mItem.budget));

                // 需求：1月份高亮为主题色作为新年标记，其余月份保持默认主文本色
                if (mItem.month.getMonthValue() == 1) {
                    tvTitle.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.app_blue));
                } else {
                    tvTitle.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.text_primary));
                }

                String sign = mItem.surplus != null && mItem.surplus >= 0 ? "+ " : "";
                tvSurplus.setText(mItem.surplus == null ? "—" : sign + getString(R.string.budget_amount, mItem.surplus));
                tvSurplus.setTextColor(ContextCompat.getColor(BudgetHistoryActivity.this,
                        mItem.surplus == null ? R.color.text_secondary
                                : mItem.surplus >= 0 ? R.color.app_blue : R.color.budget_progress_exceed));
            } else {
                GoalItem gItem = (GoalItem) item;
                TextView tvName = holder.itemView.findViewById(R.id.tv_goal_name);
                TextView tvDate = holder.itemView.findViewById(R.id.tv_goal_date);

                tvName.setText("🎉 实现了目标：" + gItem.goalName);
                tvDate.setText(gItem.achievedDate.toString());

                // 【新增】：给目标卡片绑定点击事件
                holder.itemView.setOnClickListener(v -> showDeleteGoalDialog(gItem.goal));
            }
        }
        @Override public int getItemCount() { return items.size(); }
    }
}
