package com.example.budgetapp.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.budgetapp.BackupManager;

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
                    result.add(new AutoCategoryClassifier.Learned(item.optString("package"), item.optString("identity"),
                            item.optInt("type", -1), item.optString("category"), item.optString("subCategory")));
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

    /** Learn a correction or an accepted model suggestion only after the confirmation was saved. */
    public static synchronized void rememberConfirmation(Context context, AutoCategoryClassifier.Input input,
            AutoCategoryClassifier.Result initial, int finalType, String category, String subCategory) {
        if (!isLearningEnabled(context) || finalType != input.type || (finalType != 0 && finalType != 1)) return;
        String identity = AutoCategoryClassifier.learningIdentity(input.description);
        if (identity.isEmpty()) return;
        boolean useSub = CategoryManager.isSubCategoryEnabled(context);
        AutoCategoryClassifier.Result confirmed = AutoCategoryClassifier.validate(getCatalog(context, finalType),
                category, subCategory, AutoCategoryClassifier.Source.LEARNED, useSub);
        if (confirmed == null) return;
        boolean unchanged = confirmed.category.equals(initial.category)
                && confirmed.subCategory.equals(initial.subCategory);
        if (unchanged && initial.source != AutoCategoryClassifier.Source.AI) return;
        List<AutoCategoryClassifier.Learned> rules = new ArrayList<>(getLearned(context));
        rules.removeIf(rule -> rule.type == finalType && rule.packageName.equals(input.packageName)
                && rule.identity.equals(identity));
        rules.add(0, new AutoCategoryClassifier.Learned(input.packageName, identity, finalType,
                confirmed.category, confirmed.subCategory));
        JSONArray array = new JSONArray();
        try {
            for (int i = 0; i < Math.min(MAX_LEARNED, rules.size()); i++) {
                AutoCategoryClassifier.Learned rule = rules.get(i);
                array.put(new JSONObject().put("package", rule.packageName).put("identity", rule.identity)
                        .put("type", rule.type).put("category", rule.category).put("subCategory", rule.subCategory));
            }
            prefs(context).edit().putString(KEY_LEARNED, array.toString()).apply();
            BackupManager.triggerAutoUploadIfEnabled(context);
        } catch (Exception e) {
            Log.w("AutoCategoryManager", "Could not save learned category");
        }
    }

    public static synchronized void clearLearned(Context context) {
        prefs(context).edit().remove(KEY_LEARNED).apply();
        BackupManager.triggerAutoUploadIfEnabled(context);
    }
}
