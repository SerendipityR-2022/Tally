package com.example.budgetapp.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.example.budgetapp.BackupManager;
import com.example.budgetapp.database.AppDatabase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class CategoryManager {
    public static final String CUSTOM_CATEGORY = "自定义";
    private static final String PREF_NAME = "category_prefs";
    private static final String KEY_EXPENSE = "key_expense_categories";
    private static final String KEY_INCOME = "key_income_categories";
    private static final String KEY_ENABLE_SUB_CATEGORY = "enable_sub_category";
    private static final String KEY_EXPENSE_CATALOG = "expense_catalog_390";
    private static final String KEY_INCOME_CATALOG = "income_catalog_386";
    private static final String KEY_SALARY_CHILDREN = "salary_children_merged";
    private static final String SUB_PREFIX = "sub_cat_";
    private static final String LEGACY_SALARY = "工资";
    private static final String SALARY = "薪资";

    private static final String DEFAULT_EXPENSE = "消费,餐饮,购物,住房,交通,通讯,娱乐,医疗,教育,红包,旅行,投资,美容,亲子,人情社交,宠物,快递,其他,居家,自定义";
    private static final String DEFAULT_INCOME = "薪资,奖金,投资,兼职,礼金,理财,收红包,收转账,借入,收款,其他,自定义";
    private static final List<String> NEW_INCOME_PRESETS = Arrays.asList("薪资", "理财", "收红包", "收转账", "借入", "收款", "其他");
    private static final List<String> NEW_PRESETS = Arrays.asList(
            "消费", "住房", "通讯", "红包", "旅行", "投资", "美容", "亲子", "人情社交", "宠物", "快递", "其他");

    public static synchronized List<String> getExpenseCategories(Context context) {
        SharedPreferences prefs = preferences(context);
        List<String> categories = getList(prefs, KEY_EXPENSE, DEFAULT_EXPENSE);
        if (prefs.contains(KEY_EXPENSE) && !prefs.getBoolean(KEY_EXPENSE_CATALOG, false)) {
            // Append newly introduced presets once; preserve old defaults removed by the user and existing order.
            int insert = categories.indexOf(CUSTOM_CATEGORY);
            if (insert < 0) insert = categories.size();
            for (String preset : NEW_PRESETS) {
                if (!categories.contains(preset)) categories.add(insert++, preset);
            }
            writeCategories(prefs, true, categories);
        }
        return uniqueCategories(categories, true);
    }

    public static synchronized List<String> getIncomeCategories(Context context) {
        SharedPreferences prefs = preferences(context);
        List<String> categories = getList(prefs, KEY_INCOME, DEFAULT_INCOME);
        if (prefs.contains(KEY_INCOME) && !prefs.getBoolean(KEY_INCOME_CATALOG, false)) {
            categories = withIncomePresets(categories);
            writeCategories(prefs, false, categories);
        }
        return uniqueCategories(categories, false);
    }

    private static List<String> withIncomePresets(List<String> categories) {
        List<String> updated = uniqueCategories(categories, false);
        int insert = updated.indexOf(CUSTOM_CATEGORY);
        if (insert < 0) insert = updated.size();
        for (String preset : NEW_INCOME_PRESETS) {
            if (!updated.contains(preset)) updated.add(insert++, preset);
        }
        return updated;
    }

    /** Restore settings together; the import schedules one backup after the ledger is ready. */
    public static synchronized void restoreBackupCategories(Context context, List<String> expense,
            List<String> income, Map<String, List<String>> subCategories, boolean incomeCatalogUpdated) {
        SharedPreferences.Editor editor = preferences(context).edit();
        if (expense != null) editor.putString(KEY_EXPENSE, TextUtils.join(",", uniqueCategories(expense, true)))
                .putBoolean(KEY_EXPENSE_CATALOG, true);
        if (income != null) editor.putString(KEY_INCOME,
                TextUtils.join(",", incomeCatalogUpdated ? uniqueCategories(income, false) : withIncomePresets(income)))
                .putBoolean(KEY_INCOME_CATALOG, true);
        if (subCategories != null) {
            for (Map.Entry<String, List<String>> entry : subCategories.entrySet()) {
                if (entry.getValue() != null) editor.putString(SUB_PREFIX + entry.getKey(),
                        TextUtils.join(",", entry.getValue()));
            }
            mergeRestoredSalaryChildren(editor, subCategories);
        }
        editor.apply();
    }

    public static synchronized void restoreSubCategories(Context context, Map<String, List<String>> subCategories) {
        SharedPreferences.Editor editor = preferences(context).edit();
        for (Map.Entry<String, List<String>> entry : subCategories.entrySet()) {
            if (entry.getValue() != null) editor.putString(SUB_PREFIX + entry.getKey(), TextUtils.join(",", entry.getValue()));
        }
        mergeRestoredSalaryChildren(editor, subCategories);
        editor.apply();
        BackupManager.triggerAutoUploadIfEnabled(context);
    }

    private static void mergeRestoredSalaryChildren(SharedPreferences.Editor editor, Map<String, List<String>> children) {
        if (children.get(SALARY) != null || children.get(LEGACY_SALARY) != null) {
            editor.putString(SUB_PREFIX + SALARY,
                    TextUtils.join(",", mergedSalaryChildren(children.get(SALARY), children.get(LEGACY_SALARY))))
                    .putBoolean(KEY_SALARY_CHILDREN, true);
        }
    }

    public static synchronized void saveExpenseCategories(Context context, List<String> list) {
        MonthlyBudgetHistory.checkpointBeforeSettingsChange(context);
        writeCategories(preferences(context), true, list);
        MonthlyBudgetHistory.recordCurrent(context);
        BackupManager.triggerAutoUploadIfEnabled(context);
    }

    public static synchronized void saveIncomeCategories(Context context, List<String> list) {
        writeCategories(preferences(context), false, list);
        BackupManager.triggerAutoUploadIfEnabled(context);
    }

    public static CategoryIcon getIcon(Context context, boolean expense, String name) {
        name = canonicalCategory(expense ? 0 : 1, name);
        CategoryIcon saved = CategoryIcon.fromId(iconPreferences(context).getString(iconKey(expense, name), null));
        return saved != null ? saved : CategoryIcon.forName(name);
    }

    /** Saves a new category or edits one, with the same name allowed on both sides of the ledger. */
    public static synchronized boolean saveCategory(Context context, boolean expense, String oldName,
                                                    String name, CategoryIcon icon) {
        name = canonicalCategory(expense ? 0 : 1, name);
        oldName = canonicalCategory(expense ? 0 : 1, oldName);
        List<String> categories = expense ? getExpenseCategories(context) : getIncomeCategories(context);
        if (!name.equals(oldName) && categories.contains(name)) return false;
        if (oldName == null) {
            int insert = categories.indexOf(CUSTOM_CATEGORY);
            categories.add(insert < 0 ? categories.size() : insert, name);
        } else {
            int index = categories.indexOf(oldName);
            if (index < 0) return false;
            categories.set(index, name);
        }
        boolean renamed = oldName != null && !oldName.equals(name);
        SharedPreferences prefs = preferences(context);
        SharedPreferences.Editor editor = iconPreferences(context).edit();
        // Keep old names' overrides for any historical bills still using those names.
        if (icon == CategoryIcon.forName(name)) editor.remove(iconKey(expense, name));
        else editor.putString(iconKey(expense, name), icon.id);
        editor.apply();
        if (renamed && !prefs.contains(SUB_PREFIX + name)) {
            List<String> previous = getSubCategories(context, oldName);
            if (!previous.isEmpty()) prefs.edit().putString(SUB_PREFIX + name, TextUtils.join(",", previous)).apply();
        }
        if (expense) MonthlyBudgetHistory.checkpointBeforeSettingsChange(context);
        writeCategories(prefs, expense, categories);
        if (expense) MonthlyBudgetHistory.recordCurrent(context);

        Context application = context.getApplicationContext();
        if (renamed) {
            String previousName = oldName;
            String updatedName = name;
            AppDatabase.databaseWriteExecutor.execute(() -> {
                AppDatabase.getDatabase(application).transactionDao().updateCategoryName(previousName, updatedName, expense ? 0 : 1);
                if (!expense && SALARY.equals(previousName)) {
                    AppDatabase.getDatabase(application).transactionDao().updateCategoryName(LEGACY_SALARY, updatedName, 1);
                }
                BackupManager.triggerAutoUploadIfEnabled(application);
            });
        } else {
            BackupManager.triggerAutoUploadIfEnabled(application);
        }
        return true;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private static SharedPreferences iconPreferences(Context context) {
        // ZIP, CSV and WebDAV all round-trip app_prefs, including overrides for historical categories.
        return context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
    }

    private static String iconKey(boolean expense, String name) {
        return (expense ? "ledger_category_icon_expense_" : "ledger_category_icon_income_") + name;
    }

    public static boolean isSubCategoryEnabled(Context context) {
        return preferences(context).getBoolean(KEY_ENABLE_SUB_CATEGORY, false);
    }

    public static void setSubCategoryEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEY_ENABLE_SUB_CATEGORY, enabled).apply();
        BackupManager.triggerAutoUploadIfEnabled(context);
    }

    public static synchronized List<String> getSubCategories(Context context, String parentCategory) {
        SharedPreferences prefs = preferences(context);
        List<String> children = getList(prefs, SUB_PREFIX + parentCategory, "");
        if (SALARY.equals(parentCategory) && !prefs.getBoolean(KEY_SALARY_CHILDREN, false)) {
            children = mergedSalaryChildren(children, getList(prefs, SUB_PREFIX + LEGACY_SALARY, ""));
            prefs.edit().putString(SUB_PREFIX + SALARY, TextUtils.join(",", children))
                    .putBoolean(KEY_SALARY_CHILDREN, true).apply();
        }
        return new ArrayList<>(new LinkedHashSet<>(children));
    }

    public static synchronized void saveSubCategories(Context context, String parentCategory, List<String> list) {
        SharedPreferences.Editor editor = preferences(context).edit()
                .putString(SUB_PREFIX + parentCategory, TextUtils.join(",", new LinkedHashSet<>(list)));
        if (SALARY.equals(parentCategory)) editor.putBoolean(KEY_SALARY_CHILDREN, true);
        editor.apply();
        BackupManager.triggerAutoUploadIfEnabled(context);
    }

    private static List<String> getList(SharedPreferences prefs, String key, String defaultValue) {
        String saved = prefs.getString(key, defaultValue);
        return TextUtils.isEmpty(saved) ? new ArrayList<>() : new ArrayList<>(Arrays.asList(saved.split(",")));
    }

    private static void writeCategories(SharedPreferences prefs, boolean expense, List<String> list) {
        SharedPreferences.Editor editor = prefs.edit().putString(expense ? KEY_EXPENSE : KEY_INCOME,
                TextUtils.join(",", uniqueCategories(list, expense)));
        editor.putBoolean(expense ? KEY_EXPENSE_CATALOG : KEY_INCOME_CATALOG, true);
        editor.apply();
    }

    /** Older income bills can keep their stored value; new choices all use the red salary preset. */
    public static String canonicalCategory(int type, String name) {
        return type == 1 && LEGACY_SALARY.equals(name) ? SALARY : name;
    }

    private static List<String> uniqueCategories(List<String> names, boolean expense) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String name : names) {
            if (name != null && !name.trim().isEmpty()) unique.add(canonicalCategory(expense ? 0 : 1, name.trim()));
        }
        return new ArrayList<>(unique);
    }

    private static List<String> mergedSalaryChildren(List<String> current, List<String> legacy) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (current != null) result.addAll(current);
        if (legacy != null) result.addAll(legacy);
        return new ArrayList<>(result);
    }
}
