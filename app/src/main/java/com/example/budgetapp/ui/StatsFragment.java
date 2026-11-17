package com.example.budgetapp.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.text.InputFilter;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.budgetapp.R;
import com.example.budgetapp.database.AssetAccount;
import com.example.budgetapp.database.Transaction;
import com.example.budgetapp.util.TransactionRemarks;
import com.example.budgetapp.util.AssistantConfig;
import com.example.budgetapp.util.CategoryIcon;
import com.example.budgetapp.util.CategoryManager;
import com.example.budgetapp.viewmodel.FinanceViewModel;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.text.SimpleDateFormat;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StatsFragment extends Fragment {

    private FinanceViewModel viewModel;
    private LineChart lineChart;
    private LedgerCompositionChart expensePieChart;
    private LedgerCompositionChart incomePieChart;
    private LinearLayout layoutTrend;
    private LinearLayout layoutExpense;
    private TextView emptyState;
    private RadioGroup rgTimeScope;
    private TextView tvDateRange;
    private View dividerOvertime;
    private TextView tvOvertimeContent;
    private ScrollView scrollView;
    private GestureDetector gestureDetector;
    private float touchStartX, touchStartY;
    private boolean isDirectionLocked = false;
    private boolean isHorizontalSwipe = false;
    private int touchSlop;
    private int currentMode = 1; // 0=Year, 1=Month, 2=Week
    private int trendMode;
    private View dashboard;
    private LocalDate selectedDate = LocalDate.now();
    private List<Transaction> allTransactions = new ArrayList<>();
    private List<AssetAccount> assetList = new ArrayList<>();
    private CustomMarkerView markerView;

    // 新增状态变量，用于记录当前打开的弹窗
    private AlertDialog currentCategoryDetailDialog;
    private TransactionListAdapter currentCategoryDetailAdapter;
    private String currentDetailCategory;
    private int currentDetailType;
    private ChipGroup currentDetailChipGroup;
    private View currentDetailHsvSubCategories;
    private List<Transaction> currentDetailBaseList;

    // --- 新增：用于显示分类/二级分类的汇总金额 ---
    private TextView currentDetailSummaryTextView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_stats, container, false);
        initViews(view);
        setupGestures();
        setupLineChart();
        RadioGroup trend = view.findViewById(R.id.ledger_trend_toggle);
        trend.check(trendMode == 1 ? R.id.ledger_trend_income : trendMode == 2 ? R.id.ledger_trend_net : R.id.ledger_trend_expense);
        trend.setOnCheckedChangeListener((group, id) -> {
            trendMode = id == R.id.ledger_trend_income ? 1 : id == R.id.ledger_trend_net ? 2 : 0;
            refreshData();
        });
        viewModel = new ViewModelProvider(requireActivity()).get(FinanceViewModel.class);
        viewModel.getAllTransactions().observe(getViewLifecycleOwner(), list -> {
            renderTransactions(list);
            // 新增：如果详细分类列表弹窗正在显示，即时刷新它
            if (currentCategoryDetailDialog != null && currentCategoryDetailDialog.isShowing()) {
                updateCategoryDetailDialogData();
            }
        });
        viewModel.getAllAssets().observe(getViewLifecycleOwner(), assets -> {
            this.assetList = assets;
        });
        setupListeners(view);
        updateDateRangeDisplay();
        return view;
    }

    private void initViews(View view) {
        dashboard = view;
        layoutTrend = view.findViewById(R.id.layout_trend_section);
        layoutExpense = view.findViewById(R.id.layout_expense_section);
        emptyState = view.findViewById(R.id.tv_empty_state);
        lineChart = view.findViewById(R.id.chart_line);
        expensePieChart = view.findViewById(R.id.chart_pie);
        incomePieChart = view.findViewById(R.id.chart_pie_income);
        rgTimeScope = view.findViewById(R.id.rg_time_scope);
        tvDateRange = view.findViewById(R.id.tv_current_date_range);
        dividerOvertime = view.findViewById(R.id.divider_overtime);
        tvOvertimeContent = view.findViewById(R.id.tv_overtime_content);
        scrollView = view.findViewById(R.id.scroll_view_stats);
        touchSlop = ViewConfiguration.get(requireContext()).getScaledTouchSlop();


    }

    // 【修改】showCategoryDetailDialog 方法
    private void showCategoryDetailDialog(String category, int type) {
        if (allTransactions == null) return;

        // 记录状态以便后续即时刷新
        currentDetailCategory = category;
        currentDetailType = type;

        String dateRangeStr = "";
        ZoneId zone = ZoneId.systemDefault();
        if (currentMode == 0) {
            dateRangeStr = selectedDate.format(DateTimeFormatter.ofPattern("yyyy年"));
        } else if (currentMode == 1) {
            dateRangeStr = selectedDate.format(DateTimeFormatter.ofPattern("yyyy年MM月"));
        } else {
            LocalDate startOfWeek = selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate endOfWeek = selectedDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("M.d");
            dateRangeStr = startOfWeek.format(fmt) + " - " + endOfWeek.format(fmt);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_transaction_list, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        currentCategoryDetailDialog = dialog;

        // --- 新增：绑定汇总 TextView ---
        currentDetailSummaryTextView = dialogView.findViewById(R.id.tv_dialog_summary);

        TextView tvTitle = dialogView.findViewById(R.id.tv_dialog_title);
        String typeStr = (type == 1) ? "收入" : "消费";
        tvTitle.setText(dateRangeStr + " " + category + " - " + typeStr + "清单");

        RecyclerView rv = dialogView.findViewById(R.id.rv_detail_list);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));

        TransactionListAdapter adapter = new TransactionListAdapter(t -> {
            LocalDate date = Instant.ofEpochMilli(t.date).atZone(ZoneId.systemDefault()).toLocalDate();
            showAddOrEditDialog(t, date);
            // 核心修复点：这里移除 dialog.dismiss()，修改完毕后不再关闭当前弹窗
        });

        adapter.setAssets(assetList);
        currentCategoryDetailAdapter = adapter;
        rv.setAdapter(adapter);

        currentDetailHsvSubCategories = dialogView.findViewById(R.id.hsv_sub_categories);
        currentDetailChipGroup = dialogView.findViewById(R.id.cg_dialog_sub_categories);

        // 初始化加载数据
        updateCategoryDetailDialogData();

        // 隐藏不必要的按钮
        View btnOvertime = dialogView.findViewById(R.id.btn_add_overtime);
        View btnAdd = dialogView.findViewById(R.id.btn_add_transaction);
        if (btnOvertime != null) btnOvertime.setVisibility(View.GONE);
        if (btnAdd != null) btnAdd.setVisibility(View.GONE);

        Button btnClose = dialogView.findViewById(R.id.btn_close_dialog);
        if (btnClose != null) btnClose.setOnClickListener(v -> dialog.dismiss());

        dialog.setOnDismissListener(d -> {
            currentCategoryDetailDialog = null;
            currentCategoryDetailAdapter = null;
            currentDetailChipGroup = null;
            currentDetailHsvSubCategories = null;
            currentDetailBaseList = null;

            // --- 新增：清空引用 ---
            currentDetailSummaryTextView = null;
        });

        dialog.show();
    }

    // 新增：提取的实时刷新数据和二级分类胶囊方法
    private void updateCategoryDetailDialogData() {
        if (currentCategoryDetailAdapter == null || allTransactions == null) return;
        long startMillis;
        long endMillis;
        ZoneId zone = ZoneId.systemDefault();

        if (currentMode == 0) {
            LocalDate start = LocalDate.of(selectedDate.getYear(), 1, 1);
            startMillis = start.atStartOfDay(zone).toInstant().toEpochMilli();
            endMillis = start.plusYears(1).atStartOfDay(zone).toInstant().toEpochMilli();
        } else if (currentMode == 1) {
            LocalDate start = LocalDate.of(selectedDate.getYear(), selectedDate.getMonthValue(), 1);
            startMillis = start.atStartOfDay(zone).toInstant().toEpochMilli();
            endMillis = start.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli();
        } else {
            LocalDate startOfWeek = selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate endOfWeek = selectedDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
            startMillis = startOfWeek.atStartOfDay(zone).toInstant().toEpochMilli();
            endMillis = endOfWeek.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        }

        List<Transaction> baseList = new ArrayList<>();
        for (Transaction t : allTransactions) {
            String category = android.text.TextUtils.isEmpty(t.category) ? "未分类" : CategoryManager.canonicalCategory(t.type, t.category);
            if (t.isSettled && t.date >= startMillis && t.date < endMillis && t.type == currentDetailType
                    && !"资产互转".equals(category) && category.equals(currentDetailCategory)) {
                baseList.add(t);
            }
        }

        currentDetailBaseList = baseList;

        // 保存刷新前选中的二级分类，防止刷新时跳回"全部"
        String selectedSubCat = null;
        if (currentDetailChipGroup != null) {
            int checkedId = currentDetailChipGroup.getCheckedChipId();
            if (checkedId != View.NO_ID) {
                Chip checkedChip = currentDetailChipGroup.findViewById(checkedId);
                if (checkedChip != null) {
                    selectedSubCat = checkedChip.getText().toString();
                }
            }
        }

        if (CategoryManager.isSubCategoryEnabled(requireContext()) && currentDetailChipGroup != null) {
            Set<String> subCats = new HashSet<>();
            for (Transaction t : baseList) {
                if (t.subCategory != null && !t.subCategory.isEmpty()) {
                    subCats.add(t.subCategory);
                }
            }

            if (!subCats.isEmpty()) {
                currentDetailHsvSubCategories.setVisibility(View.VISIBLE);
                currentDetailChipGroup.removeAllViews();

                int bgDefault = ContextCompat.getColor(requireContext(), R.color.cat_unselected_bg);
                int bgChecked = ContextCompat.getColor(requireContext(), R.color.app_blue);
                int textDefault = ContextCompat.getColor(requireContext(), R.color.text_primary);
                int textChecked = ContextCompat.getColor(requireContext(), R.color.cat_selected_text);
                int[][] states = new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}};
                ColorStateList bgStateList = new ColorStateList(states, new int[]{bgChecked, bgDefault});
                ColorStateList textStateList = new ColorStateList(states, new int[]{textChecked, textDefault});

                for (String subCat : subCats) {
                    Chip chip = new Chip(requireContext());
                    chip.setText(subCat);
                    chip.setCheckable(true);
                    chip.setClickable(true);
                    chip.setChipBackgroundColor(bgStateList);
                    chip.setTextColor(textStateList);
                    chip.setChipStrokeWidth(0);
                    chip.setCheckedIconVisible(false);

                    if (subCat.equals(selectedSubCat)) {
                        chip.setChecked(true);
                    }

                    chip.setOnClickListener(v -> applySubCategoryFilter());
                    currentDetailChipGroup.addView(chip);
                }
            } else {
                currentDetailHsvSubCategories.setVisibility(View.GONE);
            }
        }

        applySubCategoryFilter();
    }

    private void applySubCategoryFilter() {
        if (currentCategoryDetailAdapter == null || currentDetailBaseList == null) return;
        List<Transaction> filteredList = new ArrayList<>();

        if (currentDetailChipGroup != null && CategoryManager.isSubCategoryEnabled(requireContext())) {
            int checkedId = currentDetailChipGroup.getCheckedChipId();
            if (checkedId == View.NO_ID) {
                filteredList.addAll(currentDetailBaseList);
            } else {
                Chip checkedChip = currentDetailChipGroup.findViewById(checkedId);
                if (checkedChip != null) {
                    String subCat = checkedChip.getText().toString();
                    for (Transaction t : currentDetailBaseList) {
                        if (subCat.equals(t.subCategory)) {
                            filteredList.add(t);
                        }
                    }
                } else {
                    filteredList.addAll(currentDetailBaseList);
                }
            }
        } else {
            filteredList.addAll(currentDetailBaseList);
        }

        currentCategoryDetailAdapter.setTransactions(filteredList);

        // ================= 新增：动态计算并更新汇总金额（支持多币种） =================
        if (currentDetailSummaryTextView != null) {
            if (filteredList.isEmpty()) {
                currentDetailSummaryTextView.setVisibility(View.GONE);
            } else {
                currentDetailSummaryTextView.setVisibility(View.VISIBLE);
                
                // 检查是否启用多币种
                SharedPreferences prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
                boolean isCurrencyEnabled = prefs.getBoolean("enable_currency", false);
                
                SpannableStringBuilder ssb = new SpannableStringBuilder();
                int colorMain = (currentDetailType == 0) 
                    ? ContextCompat.getColor(requireContext(), R.color.expense_green)
                    : ContextCompat.getColor(requireContext(), R.color.income_red);
                int colorSecondary = ContextCompat.getColor(requireContext(), R.color.text_secondary);
                
                if (!isCurrencyEnabled) {
                    // ===== 单一币种模式 =====
                    double totalAmount = 0;
                    for (Transaction t : filteredList) {
                        totalAmount += t.amount;
                    }
                    
                    int count = filteredList.size();
                    double average = totalAmount / count;
                    
                    String typeStr = (currentDetailType == 0) ? "支出" : "收入";
                    String mainStr = String.format(Locale.CHINA, "%s %.2f", typeStr, totalAmount);
                    int start = ssb.length();
                    ssb.append(mainStr);
                    ssb.setSpan(new ForegroundColorSpan(colorMain), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    ssb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    
                    String countStr = String.format(Locale.CHINA, "  共 %d 条", count);
                    start = ssb.length();
                    ssb.append(countStr);
                    ssb.setSpan(new ForegroundColorSpan(colorSecondary), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    
                    String avgStr = String.format(Locale.CHINA, "  平均 %.2f", average);
                    start = ssb.length();
                    ssb.append(avgStr);
                    ssb.setSpan(new ForegroundColorSpan(colorSecondary), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    
                } else {
                    // ===== 多币种模式 =====
                    // 按货币符号分组统计
                    Map<String, Double> currencyTotals = new java.util.TreeMap<>();
                    for (Transaction t : filteredList) {
                        // 获取交易关联的资产账户货币
                        String symbol = "¥"; // 默认人民币
                        if (t.assetId != 0 && assetList != null) {
                            for (AssetAccount asset : assetList) {
                                if (asset.id == t.assetId) {
                                    if (asset.currencySymbol != null && !asset.currencySymbol.isEmpty()) {
                                        symbol = asset.currencySymbol;
                                    }
                                    break;
                                }
                            }
                        }
                        currencyTotals.put(symbol, currencyTotals.getOrDefault(symbol, 0.0) + t.amount);
                    }
                    
                    int count = filteredList.size();
                    String typeStr = (currentDetailType == 0) ? "支出" : "收入";
                    
                    // 显示各币种总额
                    boolean first = true;
                    for (Map.Entry<String, Double> entry : currencyTotals.entrySet()) {
                        if (!first) {
                            ssb.append("  ");
                        }
                        String amountStr = String.format(Locale.CHINA, "%s %s%.2f", 
                            first ? typeStr : "", entry.getKey(), entry.getValue());
                        int start = ssb.length();
                        ssb.append(amountStr);
                        ssb.setSpan(new ForegroundColorSpan(colorMain), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        if (first) {
                            ssb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        first = false;
                    }
                    
                    // 显示总条数
                    String countStr = String.format(Locale.CHINA, "  共 %d 条", count);
                    int start = ssb.length();
                    ssb.append(countStr);
                    ssb.setSpan(new ForegroundColorSpan(colorSecondary), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    
                    // 显示各币种平均值
                    ssb.append("  平均 ");
                    first = true;
                    for (Map.Entry<String, Double> entry : currencyTotals.entrySet()) {
                        // 计算该币种的交易数量
                        int currencyCount = 0;
                        for (Transaction t : filteredList) {
                            String symbol = "¥";
                            if (t.assetId != 0 && assetList != null) {
                                for (AssetAccount asset : assetList) {
                                    if (asset.id == t.assetId) {
                                        if (asset.currencySymbol != null && !asset.currencySymbol.isEmpty()) {
                                            symbol = asset.currencySymbol;
                                        }
                                        break;
                                    }
                                }
                            }
                            if (symbol.equals(entry.getKey())) {
                                currencyCount++;
                            }
                        }
                        
                        double average = entry.getValue() / currencyCount;
                        if (!first) {
                            ssb.append("/");
                        }
                        String avgStr = String.format(Locale.CHINA, "%s%.2f", entry.getKey(), average);
                        start = ssb.length();
                        ssb.append(avgStr);
                        ssb.setSpan(new ForegroundColorSpan(colorSecondary), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        first = false;
                    }
                }
                
                currentDetailSummaryTextView.setText(ssb);
            }
        }
        // ==========================================================

    }
    private void showAddOrEditDialog(Transaction existingTransaction, LocalDate date) {
        if (getContext() == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_add_transaction, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        LedgerEntrySheet sheet = new LedgerEntrySheet(dialog, dialogView);
        RadioGroup rgType = dialogView.findViewById(R.id.rg_type);
        RecyclerView rvCategory = dialogView.findViewById(R.id.rv_category);
        EditText etAmount = dialogView.findViewById(R.id.et_amount);
        EditText etCustomCategory = dialogView.findViewById(R.id.et_custom_category);
        EditText etRemark = dialogView.findViewById(R.id.et_remark);
        Spinner spAsset = dialogView.findViewById(R.id.sp_asset);

        Button btnDelete = dialogView.findViewById(R.id.btn_delete);
        TextView tvRevoke = dialogView.findViewById(R.id.tv_revoke);

        etAmount.setFilters(new InputFilter[]{new DecimalDigitsInputFilter(2)});

        List<String> expenseCategories = CategoryManager.getExpenseCategories(getContext());
        List<String> incomeCategories = CategoryManager.getIncomeCategories(getContext());

        // 动态判断：如果是详细分类，则使用弹性流式布局；否则恢复 5 列网格布局
        rvCategory.setLayoutManager(new GridLayoutManager(requireContext(), LedgerCategoryGrid.COLUMNS));

        final boolean[] isExpense = {true}; // 默认支出
        final String[] selectedCategory = {expenseCategories.isEmpty() ? "自定义" : expenseCategories.get(0)};
        final String[] selectedSubCategory = {""}; // 【新增】保存选中的二级分类

        CategoryAdapter categoryAdapter = new CategoryAdapter(getContext(), expenseCategories, selectedCategory[0], true, category -> {
            selectedCategory[0] = category;
            selectedSubCategory[0] = ""; // 【新增】切换主分类时清空二级分类
            if ("自定义".equals(category)) {
                etCustomCategory.setVisibility(View.VISIBLE);
                etCustomCategory.requestFocus();
            } else {
                etCustomCategory.setVisibility(View.GONE);
            }
        });

        // 【新增】长按显示二级分类逻辑
        categoryAdapter.setOnCategoryLongClickListener(category -> {
            if (CategoryManager.isSubCategoryEnabled(getContext()) && !"自定义".equals(category)) {

                // 长按时立刻选中该一级分类并重置状态
                if (!category.equals(selectedCategory[0])) {
                    categoryAdapter.setSelectedCategory(category);
                    selectedCategory[0] = category;
                    selectedSubCategory[0] = "";
                    etCustomCategory.setVisibility(View.GONE);
                }

                List<String> subCats = CategoryManager.getSubCategories(getContext(), category);
                AlertDialog.Builder subBuilder = new AlertDialog.Builder(getContext());
                View subCatView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_select_sub_category, null);
                subBuilder.setView(subCatView);
                AlertDialog subCatDialog = subBuilder.create();

                if (subCatDialog.getWindow() != null) {
                    subCatDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                }

                TextView tvTitle = subCatView.findViewById(R.id.tv_title);
                tvTitle.setText(category + " - 选择细分");
                ChipGroup cgSubCategories = subCatView.findViewById(R.id.cg_sub_categories);
                Button btnCancel = subCatView.findViewById(R.id.btn_cancel);
                TextView tvEmpty = subCatView.findViewById(R.id.tv_empty);
                View nsvContainer = subCatView.findViewById(R.id.nsv_container);

                if (subCats.isEmpty()) {
                    cgSubCategories.setVisibility(View.GONE);
                    tvEmpty.setVisibility(View.VISIBLE);
                    nsvContainer.setMinimumHeight(150);
                } else {
                    cgSubCategories.setVisibility(View.VISIBLE);
                    tvEmpty.setVisibility(View.GONE);

                    String currentSelectedSub = selectedSubCategory[0];
                    int bgDefault = ContextCompat.getColor(getContext(), R.color.cat_unselected_bg);
                    int bgChecked = ContextCompat.getColor(getContext(), R.color.app_blue);
                    int textDefault = ContextCompat.getColor(getContext(), R.color.text_primary);
                    int textChecked = ContextCompat.getColor(getContext(), R.color.cat_selected_text);

                    int[][] states = new int[][] { new int[] { android.R.attr.state_checked }, new int[] { } };
                    ColorStateList bgStateList = new ColorStateList(states, new int[] { bgChecked, bgDefault });
                    ColorStateList textStateList = new ColorStateList(states, new int[] { textChecked, textDefault });

                    for (String subCatName : subCats) {
                        Chip chip = new Chip(getContext());
                        chip.setText(subCatName);
                        chip.setCheckable(true);
                        chip.setClickable(true);
                        chip.setChipBackgroundColor(bgStateList);
                        chip.setTextColor(textStateList);
                        chip.setChipStrokeWidth(0);
                        chip.setCheckedIconVisible(false);

                        if (subCatName.equals(currentSelectedSub)) {
                            chip.setChecked(true);
                        }

                        chip.setOnClickListener(v -> {
                            if (subCatName.equals(selectedSubCategory[0])) {
                                selectedSubCategory[0] = null;
                                Toast.makeText(getContext(), "已取消细分", Toast.LENGTH_SHORT).show();
                            } else {
                                selectedSubCategory[0] = subCatName;
                                Toast.makeText(getContext(), "已选择: " + subCatName, Toast.LENGTH_SHORT).show();
                            }
                            categoryAdapter.setSelectedCategory(category);
                            selectedCategory[0] = category;
                            etCustomCategory.setVisibility(View.GONE);
                            subCatDialog.dismiss();
                        });
                        cgSubCategories.addView(chip);
                    }
                }
                btnCancel.setOnClickListener(v -> subCatDialog.dismiss());
                subCatDialog.show();
                return true;
            }
            return false;
        });

        categoryAdapter.enableAdding();
        rvCategory.setAdapter(categoryAdapter);

        // === 资产配置 ===
        AssistantConfig config = new AssistantConfig(requireContext());
        boolean isAssetEnabled = config.isAssetsEnabled();

        List<AssetAccount> assetList = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), R.layout.item_spinner_dropdown);

        if (isAssetEnabled) {
            spAsset.setVisibility(View.VISIBLE);
            AssetAccount noAsset = new AssetAccount("不关联资产", 0, 0);
            noAsset.id = 0;
            assetList.add(noAsset);

            adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
            spAsset.setAdapter(adapter);

            viewModel.getAllAssets().observe(getViewLifecycleOwner(), assets -> {
                assetList.clear();
                assetList.add(noAsset);
                if (assets != null) {
                    for (AssetAccount a : assets) {
                        if (a.type == 0) {
                            assetList.add(a);
                        }
                    }
                }
                List<String> names = new ArrayList<>();
                for (AssetAccount a : assetList) {
                    names.add(a.name);
                }
                adapter.clear();
                adapter.addAll(names);
                adapter.notifyDataSetChanged();

                if (existingTransaction != null && existingTransaction.assetId != 0) {
                    for (int i = 0; i < assetList.size(); i++) {
                        if (assetList.get(i).id == existingTransaction.assetId) {
                            spAsset.setSelection(i);
                            break;
                        }
                    }
                } else if (existingTransaction == null) {
                    int defaultAssetId = config.getDefaultAssetId();
                    if (defaultAssetId != -1) {
                        for (int i = 0; i < assetList.size(); i++) {
                            if (assetList.get(i).id == defaultAssetId) {
                                spAsset.setSelection(i);
                                break;
                            }
                        }
                    }
                }
            });
        } else {
            spAsset.setVisibility(View.GONE);
        }

        final java.util.Calendar calendar = java.util.Calendar.getInstance();
        if (existingTransaction != null) {
            calendar.setTimeInMillis(existingTransaction.date);
        } else {
            calendar.setTimeInMillis(System.currentTimeMillis());
            calendar.set(java.util.Calendar.YEAR, date.getYear());
            calendar.set(java.util.Calendar.MONTH, date.getMonthValue() - 1);
            calendar.set(java.util.Calendar.DAY_OF_MONTH, date.getDayOfMonth());
        }

        Runnable updateDateDisplay = () -> sheet.updateDate(calendar);
        updateDateDisplay.run();
        sheet.bindDateActions(() -> new android.app.DatePickerDialog(requireContext(), (picker, year, month, day) -> {
                    calendar.set(year, month, day);
                    updateDateDisplay.run();
                }, calendar.get(java.util.Calendar.YEAR), calendar.get(java.util.Calendar.MONTH),
                calendar.get(java.util.Calendar.DAY_OF_MONTH)).show(),
                () -> new android.app.TimePickerDialog(requireContext(), (picker, hour, minute) -> {
                    calendar.set(java.util.Calendar.HOUR_OF_DAY, hour);
                    calendar.set(java.util.Calendar.MINUTE, minute);
                    updateDateDisplay.run();
                }, calendar.get(java.util.Calendar.HOUR_OF_DAY), calendar.get(java.util.Calendar.MINUTE),
                android.text.format.DateFormat.is24HourFormat(requireContext())).show());
        dialogView.findViewById(R.id.btn_currency).setClickable(false);
        dialogView.findViewById(R.id.btn_take_photo).setVisibility(View.GONE);
        dialogView.findViewById(R.id.rb_liability).setVisibility(View.GONE);
        dialogView.findViewById(R.id.rb_lend).setVisibility(View.GONE);

        rgType.setOnCheckedChangeListener((g, id) -> {
            boolean switchToExpense = (id == R.id.rb_expense);
            isExpense[0] = switchToExpense;

            List<String> targetCategories = switchToExpense ? CategoryManager.getExpenseCategories(requireContext())
                    : CategoryManager.getIncomeCategories(requireContext());
            String defaultCat = targetCategories.isEmpty() ? "自定义" : targetCategories.get(0);

            categoryAdapter.setCategories(targetCategories, defaultCat, switchToExpense);
            rvCategory.scrollToPosition(0);
            selectedCategory[0] = defaultCat;

            if ("自定义".equals(defaultCat)) {
                etCustomCategory.setVisibility(View.VISIBLE);
            } else {
                etCustomCategory.setVisibility(View.GONE);
            }
        });

        if (existingTransaction != null) {
            etAmount.setText(String.valueOf(existingTransaction.amount));
            etRemark.setText(TransactionRemarks.display(existingTransaction));

            // 【新增】回显二级分类
            if (existingTransaction.subCategory != null) {
                selectedSubCategory[0] = existingTransaction.subCategory;
            }

            if (existingTransaction.type == 1) {
                rgType.check(R.id.rb_income);
                isExpense[0] = false;
            } else {
                rgType.check(R.id.rb_expense);
                isExpense[0] = true;
            }

            String currentCat = CategoryManager.canonicalCategory(existingTransaction.type, existingTransaction.category);
            List<String> currentList = isExpense[0] ? expenseCategories : incomeCategories;

            if (currentList.contains(currentCat)) {
                categoryAdapter.setSelectedCategory(currentCat);
                selectedCategory[0] = currentCat;
                etCustomCategory.setVisibility(View.GONE);
            } else {
                categoryAdapter.setSelectedCategory("自定义");
                selectedCategory[0] = "自定义";
                etCustomCategory.setVisibility(View.VISIBLE);
                etCustomCategory.setText(currentCat);
            }

            btnDelete.setVisibility(View.VISIBLE);
            btnDelete.setOnClickListener(v -> {
                new AlertDialog.Builder(getContext()).setTitle("确认删除").setMessage("确定要删除这条记录吗？").setPositiveButton("删除", (d, w) -> {
                    viewModel.deleteTransaction(existingTransaction);
                    dialog.dismiss();
                }).setNegativeButton("取消", null).show();
            });

            tvRevoke.setVisibility(View.VISIBLE);
            tvRevoke.setOnClickListener(v -> {
                showRevokeDialog(existingTransaction, dialog);
            });

        } else {
            btnDelete.setVisibility(View.GONE);
            tvRevoke.setVisibility(View.GONE);
        }

        sheet.bindSave(existingTransaction == null, keepRecording -> {
            String amountStr = etAmount.getText().toString();
            if (!amountStr.isEmpty()) {
                double amount = Double.parseDouble(amountStr);
                int type = rgType.getCheckedRadioButtonId() == R.id.rb_income ? 1 : 0;

                String category = selectedCategory[0];
                if ("自定义".equals(category)) {
                    category = etCustomCategory.getText().toString().trim();
                    if (category.isEmpty()) {
                        Toast.makeText(getContext(), "请输入自定义分类", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }

                String userRemark = etRemark.getText().toString().trim();
                long ts = calendar.getTimeInMillis();

                int selectedAssetId = 0;
                if (isAssetEnabled) {
                    int selectedPos = spAsset.getSelectedItemPosition();
                    if (selectedPos >= 0 && selectedPos < assetList.size()) {
                        selectedAssetId = assetList.get(selectedPos).id;
                    }
                }

                // 【修改】保存时带上二级分类数据
                if (existingTransaction == null) {
                    Transaction t = new Transaction(ts, type, category, amount, "", userRemark);
                    t.assetId = selectedAssetId;
                    t.subCategory = selectedSubCategory[0]; // 【新增】保存二级分类
                    // 【修复】使用带资产同步的方法，支持负债/借出还款逻辑
                    viewModel.addTransactionWithAssetSync(t);
                } else {
                    Transaction updateT = new Transaction(ts, type, category, amount, "", userRemark);
                    TransactionRemarks.applyEdit(existingTransaction, updateT, userRemark);
                    updateT.id = existingTransaction.id;
                    updateT.currencySymbol = existingTransaction.currencySymbol;
                    updateT.photoPath = existingTransaction.photoPath;
                    updateT.excludeFromBudget = existingTransaction.excludeFromBudget;
                    updateT.isSettled = existingTransaction.isSettled;
                    updateT.recurringRuleId = existingTransaction.recurringRuleId;
                    updateT.scheduledExecuteTime = existingTransaction.scheduledExecuteTime;
                    updateT.targetObject = existingTransaction.targetObject;
                    updateT.assetId = selectedAssetId;
                    updateT.subCategory = selectedSubCategory[0]; // 【新增】更新二级分类
                    // 【修复】使用带资产同步的方法
                    viewModel.updateTransactionWithAssetSync(updateT);
                }
                dialog.dismiss();
                if (keepRecording && existingTransaction == null) {
                    showAddOrEditDialog(null, java.time.Instant.ofEpochMilli(calendar.getTimeInMillis())
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDate());
                }
            }
        });

        // ========== 新增：弹窗关闭时强制回收软键盘 ==========
        dialog.setOnDismissListener(d -> {
            if (getContext() != null) {
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                // 确保视图和 Token 不为空，执行强制隐藏
                if (imm != null && dialogView != null && dialogView.getWindowToken() != null) {
                    imm.hideSoftInputFromWindow(dialogView.getWindowToken(), 0);
                }
            }
        });
        // ===================================================

        sheet.show();
    }

    private void showRevokeDialog(Transaction transaction, AlertDialog parentDialog) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_revoke_transaction, null);
        builder.setView(view);
        AlertDialog revokeDialog = builder.create();
        if (revokeDialog.getWindow() != null) revokeDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        Spinner spRevokeAsset = view.findViewById(R.id.sp_revoke_asset);
        Button btnCancel = view.findViewById(R.id.btn_revoke_cancel);
        Button btnConfirm = view.findViewById(R.id.btn_revoke_confirm);

        List<AssetAccount> assetList = new ArrayList<>();
        AssetAccount noAsset = new AssetAccount("不关联资产", 0, 0);
        noAsset.id = 0;

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), R.layout.item_spinner_dropdown);
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        spRevokeAsset.setAdapter(adapter);

        viewModel.getAllAssets().observe(getViewLifecycleOwner(), assets -> {
            assetList.clear();
            assetList.add(noAsset);
            if (assets != null) {
                for (AssetAccount a : assets) {
                    if (a.type == 0) {
                        assetList.add(a);
                    }
                }
            }
            List<String> names = new ArrayList<>();
            for (AssetAccount a : assetList) {
                names.add(a.name);
            }
            adapter.clear();
            adapter.addAll(names);
            adapter.notifyDataSetChanged();

            int targetIndex = 0;
            if (transaction.assetId != 0) {
                for (int i = 0; i < assetList.size(); i++) {
                    if (assetList.get(i).id == transaction.assetId) {
                        targetIndex = i;
                        break;
                    }
                }
            }
            spRevokeAsset.setSelection(targetIndex);
        });

        btnCancel.setOnClickListener(v -> revokeDialog.dismiss());

        btnConfirm.setOnClickListener(v -> {
            int selectedPos = spRevokeAsset.getSelectedItemPosition();
            if (selectedPos >= 0 && selectedPos < assetList.size()) {
                AssetAccount selectedAsset = assetList.get(selectedPos);
                viewModel.revokeTransaction(transaction, selectedAsset.id);
                android.widget.Toast.makeText(getContext(), "已撤回", android.widget.Toast.LENGTH_SHORT).show();
                revokeDialog.dismiss();
                if (parentDialog != null && parentDialog.isShowing()) {
                    parentDialog.dismiss();
                }
            }
        });

        revokeDialog.show();
    }

    private static class DecimalDigitsInputFilter implements InputFilter {
        private final Pattern mPattern;
        public DecimalDigitsInputFilter(int digitsAfterZero) {
            mPattern = Pattern.compile("[0-9]*+((\\.[0-9]{0," + (digitsAfterZero - 1) + "})?)||(\\.)?");
        }
        @Override
        public CharSequence filter(CharSequence source, int start, int end, android.text.Spanned dest, int dstart, int dend) {
            String replacement = source.subSequence(start, end).toString();
            String newVal = dest.subSequence(0, dstart).toString() + replacement + dest.subSequence(dend, dest.length()).toString();
            Matcher matcher = mPattern.matcher(newVal);
            if (!matcher.matches()) {
                if (newVal.contains(".")) {
                    int index = newVal.indexOf(".");
                    if (newVal.length() - index - 1 > 2) return "";
                }
            }
            return null;
        }
    }

    // ... 其他辅助方法保持不变 (setupGestures, setupListeners, changeDate 等)
    private void setupGestures() {
        if (scrollView == null) return;
        touchSlop = ViewConfiguration.get(requireContext()).getScaledTouchSlop();
        gestureDetector = new GestureDetector(requireContext(), new SwipeGestureListener());

        scrollView.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    touchStartX = event.getX();
                    touchStartY = event.getY();
                    isDirectionLocked = false;
                    isHorizontalSwipe = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!isDirectionLocked) {
                        float dx = Math.abs(event.getX() - touchStartX);
                        float dy = Math.abs(event.getY() - touchStartY);
                        // 更早锁定方向（降低阈值到50%）
                        if (dx > touchSlop * 0.5f || dy > touchSlop * 0.5f) {
                            isDirectionLocked = true;
                            // 横向滑动距离只需大于纵向1.2倍即可识别为横向
                            if (dx > dy * 1.2f) {
                                isHorizontalSwipe = true;
                                // 通知父视图不要拦截触摸事件
                                scrollView.requestDisallowInterceptTouchEvent(true);
                            } else {
                                isHorizontalSwipe = false;
                            }
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    isDirectionLocked = false;
                    isHorizontalSwipe = false;
                    scrollView.requestDisallowInterceptTouchEvent(false);
                    break;
            }
            return isDirectionLocked && isHorizontalSwipe;
        });

        // 给图表添加触摸监听器，让它们也能响应横向滑动
        View.OnTouchListener chartTouchListener = (v, event) -> {
            gestureDetector.onTouchEvent(event);
            return false;
        };

        if (lineChart != null) lineChart.setOnTouchListener(chartTouchListener);
        if (expensePieChart != null) expensePieChart.setOnTouchListener(chartTouchListener);
        if (incomePieChart != null) incomePieChart.setOnTouchListener(chartTouchListener);
    }

    private class SwipeGestureListener extends GestureDetector.SimpleOnGestureListener {
        private static final int SWIPE_THRESHOLD = 80;  // 降低距离阈值
        private static final int SWIPE_VELOCITY_THRESHOLD = 100;  // 降低速度阈值

        @Override
        public boolean onDown(@NonNull MotionEvent e) { 
            return true; 
        }
        
        @Override
        public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
            if (e1 == null || e2 == null) return false;
            float diffX = e2.getX() - e1.getX();
            float diffY = e2.getY() - e1.getY();

            // 只要横向滑动距离大于纵向，就可能触发翻页
            if (Math.abs(diffX) > Math.abs(diffY) * 1.2f) {
                if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) changeDate(-1);
                    else changeDate(1);
                    return true;
                }
            }
            return false;
        }
    }

    private void setupListeners(View view) {
        ImageButton btnPrev = view.findViewById(R.id.btn_prev);
        ImageButton btnNext = view.findViewById(R.id.btn_next);

        rgTimeScope.check(currentMode == 0 ? R.id.rb_year : currentMode == 2 ? R.id.rb_week : R.id.rb_month);
        rgTimeScope.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_year) currentMode = 0;
            else if (checkedId == R.id.rb_month) currentMode = 1;
            else if (checkedId == R.id.rb_week) currentMode = 2;

            updateDateRangeDisplay();
            refreshData();
        });

        btnPrev.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); // 增加震动反馈
            changeDate(-1);
        });

        btnNext.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); // 增加震动反馈
            changeDate(1);
        });
        tvDateRange.setOnClickListener(v -> {
            // 添加 CLOCK_TICK 清脆振动反馈
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
            showCustomDatePicker();
        });
    }

    private void changeDate(int offset) {
        if (dashboard == null) return;
        View[] animateViews = {dashboard.findViewById(R.id.ledger_stats_metrics), layoutTrend, layoutExpense,
                dashboard.findViewById(R.id.ledger_income_section), dashboard.findViewById(R.id.ledger_month_compare),
                dashboard.findViewById(R.id.ledger_ranking)};

        float screenWidth = scrollView.getWidth();
        if (screenWidth == 0) screenWidth = 1080;

        // 计算滑出目标位移：点击“下一周期”则向左滑出 (-screenWidth)，反之向右
        float targetX = (offset > 0) ? -screenWidth : screenWidth;

        // 1. 第一阶段：旧数据整体滑出并淡出 (时长 150ms)
        for (View v : animateViews) {
            if (v != null && v.getVisibility() == View.VISIBLE) {
                v.animate()
                        .translationX(targetX)
                        .alpha(0f)
                        .setDuration(150)
                        .start();
            }
        }

        // 2. 第二阶段：在数据滑出后的回调中更新内容
        if (scrollView != null) {
            scrollView.postDelayed(() -> {
                if (dashboard == null) return;
                // 更新日期逻辑，顶部的 tvDateRange 会立即变化
                if (currentMode == 0) selectedDate = selectedDate.plusYears(offset);
                else if (currentMode == 1) selectedDate = selectedDate.plusMonths(offset);
                else selectedDate = selectedDate.plusWeeks(offset);

                updateDateRangeDisplay(); // 标题文字更新
                refreshData();            // 图表和总结数据更新

                // 3. 第三阶段：将新数据整体瞬移到反方向，然后减速滑入 (时长 300ms)
                for (View v : animateViews) {
                    v.setAlpha(1f);
                    v.setTranslationX(0f);
                    if (v.getVisibility() == View.VISIBLE) {
                        v.setTranslationX(-targetX * 0.5f); // 预位移
                        v.animate()
                                .translationX(0f)
                                .alpha(1f)
                                .setDuration(300)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                                .start();
                    }
                }
            }, 150);
        }
    }

    /**
     * 提取的纯数据逻辑更新方法
     */
    private void performDateUpdate(int offset) {
        if (currentMode == 0) selectedDate = selectedDate.plusYears(offset);
        else if (currentMode == 1) selectedDate = selectedDate.plusMonths(offset);
        else selectedDate = selectedDate.plusWeeks(offset);

        updateDateRangeDisplay();
        refreshData();
    }

    private void updateDateRangeDisplay() {
        if (currentMode == 0) {
            tvDateRange.setText(selectedDate.format(DateTimeFormatter.ofPattern("yyyy年")));
        } else if (currentMode == 1) {
            tvDateRange.setText(selectedDate.format(DateTimeFormatter.ofPattern("yyyy年MM月")));
        } else {
            WeekFields weekFields = WeekFields.of(Locale.CHINA);
            String yearMonthStr = selectedDate.format(DateTimeFormatter.ofPattern("yyyy年M月"));
            int weekOfMonth = selectedDate.get(weekFields.weekOfMonth());

            LocalDate startOfWeek = selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate endOfWeek = selectedDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));

            DateTimeFormatter rangeFormatter = DateTimeFormatter.ofPattern("M月d日");
            String startStr = startOfWeek.format(rangeFormatter);
            String endStr = endOfWeek.format(rangeFormatter);

            String title = String.format(Locale.CHINA, "%s 第%d周", yearMonthStr, weekOfMonth);
            String subtitle = String.format(Locale.CHINA, "%s - %s", startStr, endStr);

            SpannableStringBuilder ssb = new SpannableStringBuilder();
            ssb.append(title);
            ssb.append("\n");

            int startSubtitle = ssb.length();
            ssb.append(subtitle);

            if (getContext() != null) {
                int secondaryColor = ContextCompat.getColor(requireContext(), R.color.text_secondary);
                ssb.setSpan(new ForegroundColorSpan(secondaryColor), startSubtitle, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new AbsoluteSizeSpan(13, true), startSubtitle, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            tvDateRange.setText(ssb);
        }
    }

    private void showCustomDatePicker() {
        if (getContext() == null) return;
        LedgerPickerDialog dialog = new LedgerPickerDialog(getContext(), R.layout.dialog_bottom_date_picker);
        int curYear = selectedDate.getYear();
        int curMonth = selectedDate.getMonthValue();
        int curDay = selectedDate.getDayOfMonth();
        NumberPicker npYear = dialog.findViewById(R.id.np_year);
        NumberPicker npMonth = dialog.findViewById(R.id.np_month);
        NumberPicker npDay = dialog.findViewById(R.id.np_day);
        TextView tvPreview = dialog.findViewById(R.id.tv_date_preview);
        Button btnCancel = dialog.findViewById(R.id.btn_cancel);
        Button btnConfirm = dialog.findViewById(R.id.btn_confirm);
        if (npYear == null || npMonth == null || npDay == null || btnConfirm == null || btnCancel == null) return;
        npYear.setMinValue(2000);
        npYear.setMaxValue(2050);
        npYear.setValue(curYear);
        npMonth.setMinValue(1);
        npMonth.setMaxValue(12);
        npMonth.setValue(curMonth);
        npDay.setMinValue(1);
        int maxDays = YearMonth.of(curYear, curMonth).lengthOfMonth();
        npDay.setMaxValue(maxDays);
        npDay.setValue(curDay);
        NumberPicker.OnValueChangeListener dateChangeListener = (picker, oldVal, newVal) -> {
            // 新增：在数值发生变化（即滚动）时触发清脆的滴答振动
            picker.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);

            int y = npYear.getValue();
            int m = npMonth.getValue();
            int newMaxDays = YearMonth.of(y, m).lengthOfMonth();
            if (npDay.getMaxValue() != newMaxDays) {
                int currentD = npDay.getValue();
                npDay.setMaxValue(newMaxDays);
                if (currentD > newMaxDays) npDay.setValue(newMaxDays);
            }
            updatePreviewText(tvPreview, y, m, npDay.getValue());
        };
        npYear.setOnValueChangedListener(dateChangeListener);
        npMonth.setOnValueChangedListener(dateChangeListener);
        npDay.setOnValueChangedListener(dateChangeListener);
        updatePreviewText(tvPreview, curYear, curMonth, curDay);
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            int year = npYear.getValue();
            int month = npMonth.getValue();
            int day = npDay.getValue();
            selectedDate = LocalDate.of(year, month, day);
            updateDateRangeDisplay();
            refreshData();
            dialog.dismiss();
        });
        dialog.show();
    }

    private void updatePreviewText(TextView tv, int year, int month, int day) {
        if (tv == null) return;
        try {
            LocalDate date = LocalDate.of(year, month, day);
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINA);
            tv.setText(date.format(formatter));
        } catch (Exception e) {
            tv.setText(year + "年" + month + "月" + day + "日");
        }
    }

    void renderTransactions(List<Transaction> list) {
        allTransactions = list != null ? list : Collections.emptyList();
        refreshData();
    }

    private void refreshData() {
        if (allTransactions == null) return;
        if (currentMode == 0) processYearlyData();
        else if (currentMode == 1) processMonthlyData();
        else processWeeklyData();
    }

    private void processYearlyData() {
        int year = selectedDate.getYear();
        aggregateData(t -> {
            LocalDate date = Instant.ofEpochMilli(t.date).atZone(ZoneId.systemDefault()).toLocalDate();
            return date.getYear() == year ? date.getMonthValue() : -1;
        }, 12, "月", null);
    }

    private void processMonthlyData() {
        int year = selectedDate.getYear();
        int month = selectedDate.getMonthValue();
        int daysInMonth = selectedDate.lengthOfMonth();
        aggregateData(t -> {
            LocalDate date = Instant.ofEpochMilli(t.date).atZone(ZoneId.systemDefault()).toLocalDate();
            return (date.getYear() == year && date.getMonthValue() == month) ? date.getDayOfMonth() : -1;
        }, daysInMonth, "日", null);
    }

    private void processWeeklyData() {
        LocalDate startOfWeek = selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate endOfWeek = selectedDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        aggregateData(t -> {
            LocalDate date = Instant.ofEpochMilli(t.date).atZone(ZoneId.systemDefault()).toLocalDate();
            if (!date.isBefore(startOfWeek) && !date.isAfter(endOfWeek)) {
                return date.getDayOfWeek().getValue();
            }
            return -1;
        }, 7, "", new String[]{"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"});
    }

    interface IndexExtractor { int getIndex(Transaction t); }

    private void aggregateData(IndexExtractor extractor, int maxX, String suffix, String[] customLabels) {
        if (dashboard == null) return;
        Map<Integer, Double> income = new HashMap<>(), expense = new HashMap<>();
        Map<String, Double> expenseByCategory = new HashMap<>(), incomeByCategory = new HashMap<>();
        Map<String, Integer> expenseCounts = new HashMap<>(), incomeCounts = new HashMap<>();
        Map<YearMonth, Double> monthly = new HashMap<>();
        List<Transaction> spending = new ArrayList<>();
        LocalDate start = periodStart(selectedDate);
        LocalDate end = nextPeriod(start).minusDays(1);
        LocalDate previousStart = periodStart(start.minusDays(1));
        LocalDate today = LocalDate.now();
        boolean currentPeriod = !today.isBefore(start) && !today.isAfter(end);
        YearMonth anchorMonth = YearMonth.from(selectedDate);
        YearMonth comparisonStart = anchorMonth.minusMonths(4);
        double previousExpense = 0, totalExpense = 0, totalIncome = 0;
        int lastBucket = 0;
        for (Transaction tx : allTransactions) {
            if (!tx.isSettled || "资产互转".equals(tx.category) || (tx.type != 0 && tx.type != 1)) continue;
            LocalDate day = Instant.ofEpochMilli(tx.date).atZone(ZoneId.systemDefault()).toLocalDate();
            if (tx.type == 0) {
                YearMonth month = YearMonth.from(day);
                if (!month.isBefore(comparisonStart) && !month.isAfter(anchorMonth)) monthly.merge(month, tx.amount, Double::sum);
                if (!day.isBefore(previousStart) && day.isBefore(start)) previousExpense += tx.amount;
            }
            int index = extractor.getIndex(tx);
            if (index < 0) continue;
            lastBucket = Math.max(lastBucket, index);
            String category = android.text.TextUtils.isEmpty(tx.category) ? "未分类" : CategoryManager.canonicalCategory(tx.type, tx.category);
            if (tx.type == 0) {
                totalExpense += tx.amount;
                spending.add(tx);
                expense.merge(index, tx.amount, Double::sum);
                expenseByCategory.merge(category, tx.amount, Double::sum);
                expenseCounts.merge(category, 1, Integer::sum);
            } else {
                totalIncome += tx.amount;
                income.merge(index, tx.amount, Double::sum);
                incomeByCategory.merge(category, tx.amount, Double::sum);
                incomeCounts.merge(category, 1, Integer::sum);
            }
        }
        int elapsedBucket = currentMode == 0 ? today.getMonthValue() : currentMode == 1 ? today.getDayOfMonth() : today.getDayOfWeek().getValue();
        renderTrend(income, expense, maxX, currentPeriod ? Math.max(lastBucket, elapsedBucket) : maxX, suffix, customLabels);
        renderComposition(expensePieChart, R.id.layout_expense_section, R.id.ledger_expense_categories,
                expenseByCategory, expenseCounts, totalExpense, 0);
        renderComposition(incomePieChart, R.id.ledger_income_section, R.id.ledger_income_categories,
                incomeByCategory, incomeCounts, totalIncome, 1);
        renderMonthlyComparison(anchorMonth, monthly);
        renderRanking(spending);
        boolean hasData = !expense.isEmpty() || !income.isEmpty();
        emptyState.setVisibility(hasData || !monthly.isEmpty() ? View.GONE : View.VISIBLE);

        String unit = currentMode == 0 ? "年" : currentMode == 1 ? "月" : "周";
        label(R.id.ledger_metric_expense_label, (currentPeriod ? "本" + unit : "当期") + "支出");
        label(R.id.ledger_metric_comparison_label, "较上" + unit);
        label(R.id.ledger_metric_expense, LedgerTransactionRow.money(totalExpense));
        long days = Math.max(1, ChronoUnit.DAYS.between(start, currentPeriod ? today : end) + 1);
        label(R.id.ledger_metric_daily, LedgerTransactionRow.money(totalExpense / days));
        label(R.id.ledger_metric_balance, LedgerTransactionRow.money(totalIncome - totalExpense));
        double difference = totalExpense - previousExpense;
        label(R.id.ledger_metric_comparison, (difference >= 0.005 ? "+" : "") + LedgerTransactionRow.money(difference));
        dividerOvertime.setVisibility(View.GONE);
        tvOvertimeContent.setVisibility(View.GONE);
        if (checkHasOvertime(start, end)) calculateAndShowOvertime(start, end, currentPeriod ? "本" + unit : "当期");
    }

    private LocalDate periodStart(LocalDate date) {
        if (currentMode == 0) return date.withDayOfYear(1);
        if (currentMode == 1) return date.withDayOfMonth(1);
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private LocalDate nextPeriod(LocalDate date) {
        if (currentMode == 0) return date.plusYears(1);
        if (currentMode == 1) return date.plusMonths(1);
        return date.plusWeeks(1);
    }

    private void label(int id, CharSequence text) { ((TextView) dashboard.findViewById(id)).setText(text); }

    private void renderTrend(Map<Integer, Double> income, Map<Integer, Double> expense,
                             int maxX, int buckets, String suffix, String[] customLabels) {
        boolean hasData = !income.isEmpty() || !expense.isEmpty();
        layoutTrend.setVisibility(hasData ? View.VISIBLE : View.GONE);
        if (!hasData) return;
        String title = trendMode == 1 ? "收入趋势" : trendMode == 2 ? "结余趋势" : "支出趋势";
        label(R.id.ledger_trend_title, title);
        List<Entry> points = new ArrayList<>();
        for (int i = 1; i <= Math.min(buckets, maxX); i++) {
            double in = income.getOrDefault(i, 0.0), out = expense.getOrDefault(i, 0.0);
            points.add(new Entry(i, (float) (trendMode == 1 ? in : trendMode == 2 ? in - out : out)));
        }
        int color = requireContext().getColor(trendMode == 1 ? R.color.ledger_income : R.color.ledger_chart_blue);
        LineDataSet set = new LineDataSet(points, title);
        set.setColor(color);
        set.setCircleColor(color);
        set.setCircleRadius(maxX > 12 ? 2f : 3f);
        set.setDrawCircles(true);
        set.setDrawCircleHole(false);
        set.setLineWidth(2.25f);
        set.setDrawValues(false);
        set.setDrawFilled(trendMode != 2);
        set.setFillColor(color);
        set.setFillAlpha(33);
        set.setHighLightColor(color);
        set.enableDashedHighlightLine(5f, 3f, 0);
        set.setDrawHorizontalHighlightIndicator(false);
        XAxis x = lineChart.getXAxis();
        x.setAxisMinimum(1f);
        x.setAxisMaximum(maxX);
        x.setLabelCount(maxX <= 7 ? maxX : 6);
        x.setAvoidFirstLastClipping(true);
        x.setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                int index = Math.round(value);
                if (index < 1 || index > maxX) return "";
                return customLabels != null && index < customLabels.length ? customLabels[index] : index + suffix;
            }
        });
        if (trendMode == 2) lineChart.getAxisLeft().resetAxisMinimum(); else lineChart.getAxisLeft().setAxisMinimum(0f);
        lineChart.setData(new LineData(set));
        lineChart.highlightValues(null);
        markerView.setSourceData(income, expense, customLabels != null ? "" : suffix, customLabels);
        lineChart.setContentDescription(title);
        lineChart.invalidate();
    }

    private void renderComposition(LedgerCompositionChart chart, int panelId, int rowsId, Map<String, Double> values,
                                   Map<String, Integer> counts, double total, int type) {
        View panel = dashboard.findViewById(panelId);
        panel.setVisibility(total > 0 ? View.VISIBLE : View.GONE);
        LinearLayout rows = dashboard.findViewById(rowsId);
        rows.removeAllViews();
        int totalLabel = type == 0 ? R.string.stats_expense_total : R.string.stats_income_total;
        if (total <= 0) {
            chart.setComposition(Collections.emptyList(), Collections.emptyList(), 0, totalLabel, null);
            return;
        }
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(values.entrySet());
        sorted.sort((a, b) -> {
            int amount = Double.compare(b.getValue(), a.getValue());
            return amount == 0 ? a.getKey().compareTo(b.getKey()) : amount;
        });
        List<PieEntry> slices = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        double remaining = 0;
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<String, Double> item = sorted.get(i);
            String category = item.getKey();
            double value = item.getValue();
            CategoryIcon icon = LedgerCategory.icon(requireContext(), category, type);
            boolean hasNamedSlice = i < LedgerCompositionChart.MAX_CATEGORY_SLICES;
            int color = hasNamedSlice ? chart.sliceColor(i) : icon.tint(requireContext());
            if (hasNamedSlice && value > 0) {
                slices.add(new PieEntry((float) value, category, value));
                colors.add(color);
            } else if (value > 0) remaining += value;
            View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_ledger_category_rank, rows, false);
            LedgerCategory.bind(row.findViewById(R.id.ledger_category_rank_icon), icon);
            ((TextView) row.findViewById(R.id.ledger_category_rank_index)).setText(String.valueOf(i + 1));
            ((TextView) row.findViewById(R.id.ledger_category_rank_label)).setText(category + "  ·  " + counts.get(category) + " 笔");
            ((TextView) row.findViewById(R.id.ledger_category_rank_amount)).setText(LedgerTransactionRow.money(value));
            android.widget.ProgressBar progress = row.findViewById(R.id.ledger_category_rank_progress);
            progress.setProgress((int) Math.round(value / total * 100));
            progress.setProgressTintList(ColorStateList.valueOf(color));
            row.setOnClickListener(v -> showCategoryDetailDialog(category, type));
            rows.addView(row);
        }
        if (remaining > 0) {
            slices.add(new PieEntry((float) remaining, "其余分类", remaining));
            colors.add(requireContext().getColor(R.color.ledger_muted));
        }
        chart.setComposition(slices, colors, total, totalLabel, currentMode + ":" + periodStart(selectedDate));
    }

    private void renderMonthlyComparison(YearMonth anchor, Map<YearMonth, Double> values) {
        dashboard.findViewById(R.id.ledger_month_compare).setVisibility(values.isEmpty() ? View.GONE : View.VISIBLE);
        if (values.isEmpty()) return;
        Context c = requireContext();
        com.github.mikephil.charting.charts.BarChart chart = dashboard.findViewById(R.id.ledger_month_chart);
        int muted = c.getColor(R.color.ledger_muted);
        int active = c.getColor(R.color.ledger_chart_blue);
        List<com.github.mikephil.charting.data.BarEntry> bars = new ArrayList<>();
        String[] labels = new String[5];
        StringBuilder description = new StringBuilder("月支出对比");
        for (int i = 0; i < 5; i++) {
            YearMonth period = anchor.minusMonths(4 - i);
            labels[i] = period.getMonthValue() + "月";
            double amount = values.getOrDefault(period, 0.0);
            bars.add(new com.github.mikephil.charting.data.BarEntry(i, (float) amount));
            description.append("，").append(period).append(" ").append(LedgerTransactionRow.money(amount));
        }
        com.github.mikephil.charting.data.BarDataSet data = new com.github.mikephil.charting.data.BarDataSet(bars, "");
        int faded = androidx.core.graphics.ColorUtils.setAlphaComponent(active, 112);
        data.setColors(faded, faded, faded, faded, active);
        data.setValueTextColor(muted);
        data.setValueTextSize(10f);
        data.setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                return value == 0 ? "0" : value >= 10000 ? String.format(Locale.CHINA, "%.1f万", value / 10000) : LedgerTransactionRow.money(value);
            }
        });
        com.github.mikephil.charting.data.BarData d = new com.github.mikephil.charting.data.BarData(data);
        d.setBarWidth(0.46f);
        chart.setData(d);
        chart.setFitBars(true);
        chart.setTouchEnabled(false);
        chart.getAxisRight().setEnabled(false);
        chart.getAxisLeft().setAxisMinimum(0);
        chart.getAxisLeft().setEnabled(false);
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.setExtraTopOffset(11f);
        XAxis x = chart.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setGranularity(1f);
        x.setDrawGridLines(false);
        x.setTextColor(muted);
        x.setLabelCount(5);
        x.setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                int i = Math.round(value);
                return i >= 0 && i < labels.length ? labels[i] : "";
            }
        });
        chart.setContentDescription(description.toString());
        chart.invalidate();
    }

    private void renderRanking(List<Transaction> spending) {
        dashboard.findViewById(R.id.ledger_ranking).setVisibility(spending.isEmpty() ? View.GONE : View.VISIBLE);
        LinearLayout rows = dashboard.findViewById(R.id.ledger_ranked_transactions);
        rows.removeAllViews();
        spending.sort((a, b) -> a.amount == b.amount ? Long.compare(b.date, a.date) : Double.compare(b.amount, a.amount));
        int limit = Math.min(10, spending.size());
        for (int i = 0; i < limit; i++) {
            View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_ledger_transaction, rows, false);
            row.setPaddingRelative(0, row.getPaddingTop(), 0, row.getPaddingBottom());
            bindRankingRow(row, spending.get(i), null);
            rows.addView(row);
        }
        View all = dashboard.findViewById(R.id.ledger_ranking_all);
        all.setVisibility(spending.size() > limit ? View.VISIBLE : View.GONE);
        all.setOnClickListener(spending.size() > limit ? v -> showFullRanking(spending) : null);
    }

    private void bindRankingRow(View row, Transaction transaction, BottomSheetDialog parent) {
        boolean showCurrency = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getBoolean("enable_currency", false);
        String assetName = null;
        if (assetList != null) for (AssetAccount asset : assetList) if (asset.id == transaction.assetId) { assetName = asset.name; break; }
        new LedgerTransactionRow(row).bind(transaction, assetName, showCurrency, true);
        row.setOnClickListener(v -> {
            if (parent != null) parent.dismiss();
            editRankingItem(transaction);
        });
    }

    private void editRankingItem(Transaction transaction) {
        LocalDate day = Instant.ofEpochMilli(transaction.date).atZone(ZoneId.systemDefault()).toLocalDate();
        showAddOrEditDialog(transaction, day);
    }

    private void showFullRanking(List<Transaction> transactions) {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.panel_ledger_full_ranking, null);
        RecyclerView list = view.findViewById(R.id.ledger_full_rank_list);
        view.findViewById(R.id.ledger_full_rank_close).setOnClickListener(v -> dialog.dismiss());
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_ledger_transaction, parent, false)) {};
            }
            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int i) {
                bindRankingRow(holder.itemView, transactions.get(i), dialog);
            }
            @Override public int getItemCount() { return transactions.size(); }
        });
        dialog.setContentView(view);
        dialog.getBehavior().setMaxHeight((int) (getResources().getDisplayMetrics().heightPixels * 0.80));
        dialog.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        dialog.show();
    }

    private boolean checkHasOvertime(LocalDate start, LocalDate end) {
        long startMillis = start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endMillis = end.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        for (Transaction t : allTransactions) {
            if (t.isSettled && t.date >= startMillis && t.date < endMillis && t.type == 1 && "加班".equals(t.category)) {
                return true;
            }
        }
        return false;
    }

    private void calculateAndShowOvertime(LocalDate start, LocalDate end, String scopeStr) {
        long startMillis = start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endMillis = end.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

        double totalOvertimeHours = 0;
        double weekdayOvertimeHours = 0;
        double holidayOvertimeHours = 0;
        double totalOvertimeIncome = 0;

        Pattern pattern = Pattern.compile("时长:\\s*([0-9.]+)\\s*小时");

        for (Transaction t : allTransactions) {
            if (t.isSettled && t.date >= startMillis && t.date < endMillis && t.type == 1 && "加班".equals(t.category)) {
                totalOvertimeIncome += t.amount;

                if (t.note != null) {
                    Matcher matcher = pattern.matcher(t.note);
                    if (matcher.find()) {
                        try {
                            double hours = Double.parseDouble(matcher.group(1));
                            totalOvertimeHours += hours;

                            LocalDate transDate = Instant.ofEpochMilli(t.date).atZone(ZoneId.systemDefault()).toLocalDate();
                            DayOfWeek dayOfWeek = transDate.getDayOfWeek();
                            if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
                                holidayOvertimeHours += hours;
                            } else {
                                weekdayOvertimeHours += hours;
                            }
                        } catch (NumberFormatException e) {
                            // ignore error
                        }
                    }
                }
            }
        }

        SpannableStringBuilder ssb = new SpannableStringBuilder();
        int redColor = ContextCompat.getColor(requireContext(), R.color.income_red);
        int primaryColor = ContextCompat.getColor(requireContext(), R.color.text_primary);

        String title = scopeStr + "加班";
        int startTitle = ssb.length();
        ssb.append(title);
        ssb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), startTitle, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.setSpan(new AbsoluteSizeSpan(16, true), startTitle, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.setSpan(new ForegroundColorSpan(primaryColor), startTitle, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        ssb.append("\n");

        ssb.append("共计");
        String totalHoursStr = String.format(Locale.CHINA, "%.1f", totalOvertimeHours);
        int startTotalH = ssb.length();
        ssb.append(totalHoursStr);
        ssb.setSpan(new ForegroundColorSpan(redColor), startTotalH, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        ssb.append("小时, 共计获得");
        String amountStr = String.format(Locale.CHINA, "%.2f", totalOvertimeIncome);
        int startAmount = ssb.length();
        ssb.append(amountStr);
        ssb.setSpan(new ForegroundColorSpan(redColor), startAmount, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        ssb.append("元\n");

        ssb.append("工作日加班");
        String weekdayHoursStr = String.format(Locale.CHINA, "%.1f", weekdayOvertimeHours);
        int startWeekday = ssb.length();
        ssb.append(weekdayHoursStr);
        ssb.setSpan(new ForegroundColorSpan(redColor), startWeekday, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        ssb.append("小时, 节假日加班");
        String holidayHoursStr = String.format(Locale.CHINA, "%.1f", holidayOvertimeHours);
        int startHoliday = ssb.length();
        ssb.append(holidayHoursStr);
        ssb.setSpan(new ForegroundColorSpan(redColor), startHoliday, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.append("小时");

        if (currentMode == 1) {
            AssistantConfig config = new AssistantConfig(requireContext());
            float baseSalary = config.getMonthlyBaseSalary();
            if (baseSalary > 0) {
                double totalSalary = baseSalary + totalOvertimeIncome;
                ssb.append("\n");
                ssb.append("本月工资");
                String salaryStr = String.format(Locale.CHINA, "%.2f", totalSalary);
                int startSalary = ssb.length();
                ssb.append(salaryStr);
                ssb.setSpan(new ForegroundColorSpan(redColor), startSalary, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.append("元");
            }
        }

        tvOvertimeContent.setText(ssb);

        if (dividerOvertime != null) dividerOvertime.setVisibility(View.VISIBLE);
        if (tvOvertimeContent != null) tvOvertimeContent.setVisibility(View.VISIBLE);
    }

    private void setupLineChart() {
        int muted = requireContext().getColor(R.color.ledger_muted);
        int rule = requireContext().getColor(R.color.ledger_rule);
        lineChart.getDescription().setEnabled(false);
        lineChart.getLegend().setEnabled(false);
        lineChart.getAxisRight().setEnabled(false);
        lineChart.getAxisLeft().setTextColor(muted);
        lineChart.getAxisLeft().setGridColor(rule);
        lineChart.getAxisLeft().setDrawAxisLine(false);
        XAxis x = lineChart.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setGranularity(1f);
        x.setTextColor(muted);
        x.setGridColor(rule);
        x.setAxisLineColor(rule);
        lineChart.setExtraOffsets(1f, 6f, 6f, 3f);
        lineChart.setDragEnabled(false);
        lineChart.setScaleEnabled(false);
        lineChart.setHighlightPerDragEnabled(false);
        markerView = new CustomMarkerView(requireContext(), R.layout.view_chart_marker);
        markerView.setChartView(lineChart);
        lineChart.setMarker(markerView);
    }

    @Override
    public void onResume() {
        super.onResume();
        SharedPreferences prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        boolean isCustomBg = prefs.getInt("theme_mode", -1) == 3;
        updateFragmentTransparency(isCustomBg);
    }

    private void updateFragmentTransparency(boolean isCustomBg) {
        if (dashboard == null) return;
        if (isCustomBg) dashboard.setBackgroundColor(Color.TRANSPARENT);
        else dashboard.setBackgroundResource(R.color.ledger_canvas);
        int alpha = isCustomBg ? 235 : 255;
        for (int id : new int[]{R.id.rg_time_scope, R.id.ledger_stats_metrics, R.id.layout_trend_section,
                R.id.layout_expense_section, R.id.ledger_income_section, R.id.ledger_month_compare, R.id.ledger_ranking}) {
            View panel = dashboard.findViewById(id);
            if (panel.getBackground() != null) panel.getBackground().mutate().setAlpha(alpha);
        }
    }

    @Override public void onDestroyView() {
        dashboard = null;
        super.onDestroyView();
    }
}
