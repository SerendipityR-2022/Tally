package com.example.budgetapp.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.example.budgetapp.BackupManager;
import com.example.budgetapp.database.AppDatabase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CategoryManager {
    public static final String CUSTOM_CATEGORY = "自定义";
    private static final String PREF_NAME = "category_prefs";
    private static final String KEY_EXPENSE = "key_expense_categories";
    private static final String KEY_INCOME = "key_income_categories";
    private static final String KEY_ENABLE_SUB_CATEGORY = "enable_sub_category";
    private static final String KEY_EXPENSE_CATALOG = "expense_catalog_390";
    private static final String SUB_PREFIX = "sub_cat_";

    private static final String DEFAULT_EXPENSE = "消费,餐饮,购物,住房,交通,通讯,娱乐,医疗,教育,红包,旅行,投资,美容,亲子,人情社交,宠物,快递,其他,居家,自定义";
    private static final String DEFAULT_INCOME = "工资,奖金,投资,兼职,礼金,自定义";
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
        return categories;
    }

    public static List<String> getIncomeCategories(Context context) {
        return getList(preferences(context), KEY_INCOME, DEFAULT_INCOME);
    }

    public static synchronized void saveExpenseCategories(Context context, List<String> list) {
        writeCategories(preferences(context), true, list);
        BackupManager.triggerAutoUploadIfEnabled(context);
    }

    public static synchronized void saveIncomeCategories(Context context, List<String> list) {
        writeCategories(preferences(context), false, list);
        BackupManager.triggerAutoUploadIfEnabled(context);
    }

    public static CategoryIcon getIcon(Context context, boolean expense, String name) {
        CategoryIcon saved = CategoryIcon.fromId(iconPreferences(context).getString(iconKey(expense, name), null));
        return saved != null ? saved : CategoryIcon.forName(name);
    }

    /** Saves a new category or edits one, with the same name allowed on both sides of the ledger. */
    public static synchronized boolean saveCategory(Context context, boolean expense, String oldName,
                                                    String name, CategoryIcon icon) {
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
            String previous = prefs.getString(SUB_PREFIX + oldName, "");
            if (!previous.isEmpty()) prefs.edit().putString(SUB_PREFIX + name, previous).apply();
        }
        writeCategories(prefs, expense, categories);

        Context application = context.getApplicationContext();
        if (renamed) {
            AppDatabase.databaseWriteExecutor.execute(() -> {
                AppDatabase.getDatabase(application).transactionDao().updateCategoryName(oldName, name, expense ? 0 : 1);
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

    public static List<String> getSubCategories(Context context, String parentCategory) {
        return getList(preferences(context), SUB_PREFIX + parentCategory, "");
    }

    public static void saveSubCategories(Context context, String parentCategory, List<String> list) {
        preferences(context).edit().putString(SUB_PREFIX + parentCategory, TextUtils.join(",", list)).apply();
        BackupManager.triggerAutoUploadIfEnabled(context);
    }

    private static List<String> getList(SharedPreferences prefs, String key, String defaultValue) {
        String saved = prefs.getString(key, defaultValue);
        return TextUtils.isEmpty(saved) ? new ArrayList<>() : new ArrayList<>(Arrays.asList(saved.split(",")));
    }

    private static void writeCategories(SharedPreferences prefs, boolean expense, List<String> list) {
        SharedPreferences.Editor editor = prefs.edit().putString(expense ? KEY_EXPENSE : KEY_INCOME, TextUtils.join(",", list));
        if (expense) editor.putBoolean(KEY_EXPENSE_CATALOG, true);
        editor.apply();
    }
}
