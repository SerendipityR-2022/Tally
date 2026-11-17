package com.example.budgetapp.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.budgetapp.BackupManager;
import com.example.budgetapp.database.Transaction;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Preference/catalog adapter. Learning stores no separate amount, date or asset fields. */
public final class AutoCategoryManager {
    private static final String KEY_AI = "screen_category_ai_enabled";
    private static final String KEY_LEARNING = "screen_category_learning_enabled";
    private static final String KEY_LEARNED = "screen_category_learned_rules";
    private static final String KEY_PRIORITY = "screen_category_strategy_order";
    private static final String KEY_AI_TIMEOUT = "screen_category_ai_timeout_seconds";
    public static final int MIN_AI_TIMEOUT_SECONDS = 1;
    public static final int MAX_AI_TIMEOUT_SECONDS = 10;
    public static final int DEFAULT_AI_TIMEOUT_SECONDS = 8;
    public static final int MAX_LEARNED = 2000;
    private static String cachedLearnedJson;
    private static List<AutoCategoryClassifier.Learned> cachedLearned = Collections.emptyList();

    private AutoCategoryManager() { }

    // app_prefs is already included in local/WebDAV backups.
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
    }

    public static AutoCategoryPriority getPriority(Context context) {
        try { return AutoCategoryPriority.parse(prefs(context).getString(KEY_PRIORITY, "")); }
        catch (ClassCastException ignored) { return AutoCategoryPriority.defaults(); }
    }

    public static void setPriority(Context context, AutoCategoryPriority priority) {
        prefs(context).edit().putString(KEY_PRIORITY, priority.serialize()).apply();
        BackupManager.triggerAutoUploadIfEnabled(context);
    }

    public static boolean isAiEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AI, false);
    }

    public static void setAiEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AI, enabled).apply();
        BackupManager.triggerAutoUploadIfEnabled(context);
    }

    public static int getAiTimeoutSeconds(Context context) {
        try {
            return boundAiTimeout(prefs(context).getInt(KEY_AI_TIMEOUT, DEFAULT_AI_TIMEOUT_SECONDS));
        } catch (ClassCastException ignored) {
            return DEFAULT_AI_TIMEOUT_SECONDS;
        }
    }

    public static void setAiTimeoutSeconds(Context context, int seconds) {
        prefs(context).edit().putInt(KEY_AI_TIMEOUT, boundAiTimeout(seconds)).apply();
        BackupManager.triggerAutoUploadIfEnabled(context);
    }

    private static int boundAiTimeout(int seconds) {
        return Math.max(MIN_AI_TIMEOUT_SECONDS, Math.min(MAX_AI_TIMEOUT_SECONDS, seconds));
    }

    public static boolean isLearningEnabled(Context context) {
        return prefs(context).getBoolean(KEY_LEARNING, true);
    }

    public static void setLearningEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_LEARNING, enabled).apply();
        BackupManager.triggerAutoUploadIfEnabled(context);
    }

    public static List<AutoCategoryClassifier.Category> getCatalog(Context context, int type) {
        List<String> names = type == 1 ? CategoryManager.getIncomeCategories(context)
                : CategoryManager.getExpenseCategories(context);
        List<AutoCategoryClassifier.Category> result = new ArrayList<>();
        boolean hasOther = false;
        for (String name : names) {
            if (name == null || name.trim().isEmpty() || "自定义".equals(name.trim())) continue;
            name = name.trim();
            result.add(new AutoCategoryClassifier.Category(name, CategoryManager.getSubCategories(context, name)));
            hasOther |= "其他".equals(name);
        }
        if (!hasOther) result.add(new AutoCategoryClassifier.Category("其他", Collections.emptyList()));
        return result;
    }

    public static AutoCategoryClassifier.Result classifyLocal(Context context, AutoCategoryClassifier.Input input) {
        List<AutoCategoryClassifier.Rule> rules = new ArrayList<>();
        for (CategoryRule rule : AiCategoryRuleManager.getRules(context)) {
            rules.add(new AutoCategoryClassifier.Rule(rule.getKeyword(), rule.getCategory(), rule.getSubCategory(), rule.getType()));
        }
        return AutoCategoryClassifier.classifyLocal(input, getCatalog(context, input.type), rules,
                isLearningEnabled(context) ? getLearned(context) : Collections.emptyList(),
                CategoryManager.isSubCategoryEnabled(context), getPriority(context));
    }

    public static synchronized List<AutoCategoryClassifier.Learned> getLearned(Context context) {
        try {
            String stored = prefs(context).getString(KEY_LEARNED, "[]");
            if (stored.equals(cachedLearnedJson)) return cachedLearned;
            List<AutoCategoryClassifier.Learned> result = new ArrayList<>();
            try {
                JSONArray array = new JSONArray(stored);
                for (int i = 0; i < array.length() && result.size() < MAX_LEARNED; i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item == null) continue;
                    int type = item.optInt("type", -1);
                    result.add(new AutoCategoryClassifier.Learned(item.optString("package"), item.optString("identity"),
                            type, CategoryManager.canonicalCategory(type, item.optString("category")), item.optString("subCategory")));
                }
            } catch (org.json.JSONException e) {
                Log.w("AutoCategoryManager", "Ignoring malformed learned categories");
            }
            cachedLearnedJson = stored;
            cachedLearned = Collections.unmodifiableList(result);
            return cachedLearned;
        } catch (ClassCastException ignored) {
            return Collections.emptyList();
        }
    }

    /** Retain a reliable source even if this bill was saved without user confirmation. */
    public static void attachScreenSource(Transaction bill, AutoCategoryClassifier.Input input) {
        if (bill.type != input.type || (bill.type != 0 && bill.type != 1) || input.packageName.isEmpty()
                || !ScreenCategoryTextExtractor.hasMerchant(input.description)) return;
        bill.screenSourcePackage = input.packageName;
        bill.screenMerchantIdentity = AutoCategoryClassifier.learningIdentity(input.description);
    }

    /** Learn a real suggestion or a correction only after the confirmation was saved. */
    public static void rememberConfirmation(Context context, Transaction bill, AutoCategoryClassifier.Result initial) {
        if (initial.source == AutoCategoryClassifier.Source.FALLBACK
                && sameClassification(bill, initial.category, initial.subCategory)) return;
        rememberCategory(context, bill);
    }

    public static void rememberCategoryEdit(Context context, Transaction previous, Transaction updated) {
        if (previous.type == updated.type && !sameClassification(updated, previous.category, previous.subCategory)) {
            rememberCategory(context, updated);
        }
    }

    private static boolean sameClassification(Transaction bill, String category, String subCategory) {
        return value(CategoryManager.canonicalCategory(bill.type, bill.category)).equals(
                value(CategoryManager.canonicalCategory(bill.type, category))) && value(bill.subCategory).equals(value(subCategory));
    }

    private static String value(String text) {
        return text == null ? "" : text.trim();
    }

    private static synchronized void rememberCategory(Context context, Transaction bill) {
        if (!isLearningEnabled(context) || (bill.type != 0 && bill.type != 1)
                || value(bill.screenSourcePackage).isEmpty() || value(bill.screenMerchantIdentity).isEmpty()) return;
        AutoCategoryClassifier.Result confirmed = AutoCategoryClassifier.validate(getCatalog(context, bill.type),
                CategoryManager.canonicalCategory(bill.type, bill.category), bill.subCategory,
                AutoCategoryClassifier.Source.LEARNED, CategoryManager.isSubCategoryEnabled(context));
        if (confirmed == null) return;
        List<AutoCategoryClassifier.Learned> rules = new ArrayList<>(getLearned(context));
        if (!rules.isEmpty()) {
            AutoCategoryClassifier.Learned newest = rules.get(0);
            if (newest.type == bill.type && newest.packageName.equals(bill.screenSourcePackage)
                    && newest.identity.equals(bill.screenMerchantIdentity) && newest.category.equals(confirmed.category)
                    && newest.subCategory.equals(confirmed.subCategory)) return;
        }
        rules.removeIf(rule -> rule.type == bill.type && rule.packageName.equals(bill.screenSourcePackage)
                && rule.identity.equals(bill.screenMerchantIdentity));
        rules.add(0, new AutoCategoryClassifier.Learned(bill.screenSourcePackage, bill.screenMerchantIdentity,
                bill.type, confirmed.category, confirmed.subCategory));
        // The caller backs up the bill and the learned rule together after saving.
        saveLearned(context, rules);
    }

    /** Update the result while keeping the app, merchant and transaction type as the matching key. */
    public static synchronized boolean updateLearned(Context context, AutoCategoryClassifier.Learned key,
            String category, String subCategory) {
        AutoCategoryClassifier.Result validated = AutoCategoryClassifier.validate(getCatalog(context, key.type),
                category, subCategory, AutoCategoryClassifier.Source.LEARNED, true);
        if (validated == null) return false;
        List<AutoCategoryClassifier.Learned> rules = new ArrayList<>(getLearned(context));
        for (int i = 0; i < rules.size(); i++) {
            AutoCategoryClassifier.Learned current = rules.get(i);
            if (!sameKey(current, key)) continue;
            if (current.category.equals(validated.category) && current.subCategory.equals(validated.subCategory)) return true;
            rules.set(i, new AutoCategoryClassifier.Learned(current.packageName, current.identity, current.type,
                    validated.category, validated.subCategory));
            return saveManagedRules(context, rules);
        }
        return false;
    }

    public static synchronized boolean deleteLearned(Context context, AutoCategoryClassifier.Learned key) {
        List<AutoCategoryClassifier.Learned> rules = new ArrayList<>(getLearned(context));
        return rules.removeIf(rule -> sameKey(rule, key)) && saveManagedRules(context, rules);
    }

    private static boolean sameKey(AutoCategoryClassifier.Learned a, AutoCategoryClassifier.Learned b) {
        return a.type == b.type && a.packageName.equals(b.packageName) && a.identity.equals(b.identity);
    }

    private static boolean saveManagedRules(Context context, List<AutoCategoryClassifier.Learned> rules) {
        if (!saveLearned(context, rules)) return false;
        BackupManager.triggerAutoUploadIfEnabled(context.getApplicationContext());
        return true;
    }

    private static boolean saveLearned(Context context, List<AutoCategoryClassifier.Learned> rules) {
        JSONArray array = new JSONArray();
        try {
            for (int i = 0; i < Math.min(MAX_LEARNED, rules.size()); i++) {
                AutoCategoryClassifier.Learned rule = rules.get(i);
                array.put(new JSONObject().put("package", rule.packageName).put("identity", rule.identity)
                        .put("type", rule.type).put("category", rule.category).put("subCategory", rule.subCategory));
            }
            prefs(context).edit().putString(KEY_LEARNED, array.toString()).apply();
            return true;
        } catch (org.json.JSONException e) {
            Log.w("AutoCategoryManager", "Could not save learned category");
            return false;
        }
    }

    public static Runnable observeLearnedChanges(Context context, Runnable onChange) {
        SharedPreferences preferences = prefs(context);
        SharedPreferences.OnSharedPreferenceChangeListener listener = (changed, key) -> {
            if (key == null || KEY_LEARNED.equals(key)) onChange.run();
        };
        preferences.registerOnSharedPreferenceChangeListener(listener);
        return () -> preferences.unregisterOnSharedPreferenceChangeListener(listener);
    }

    public static synchronized void clearLearned(Context context) {
        prefs(context).edit().remove(KEY_LEARNED).apply();
        BackupManager.triggerAutoUploadIfEnabled(context);
    }
}
