package com.example.budgetapp.util;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class KeywordManager {

    private static final String PREF_NAME = "app_specific_keywords_prefs";
    private static final String CUSTOM_PACKAGES = "experimental_custom_keyword_packages";
    public static final int MAX_CUSTOM_PACKAGES = 32;

    // 定义支持的包名常量
    public static final String PKG_WECHAT = "com.tencent.mm";
    public static final String PKG_ALIPAY = "com.eg.android.AlipayGphone";
    public static final String PKG_JINGDONG = "com.jingdong.app.mall";
    public static final String PKG_PINDUODUO = "com.xunmeng.pinduoduo";
    public static final String PKG_DOUYIN = "com.ss.android.ugc.aweme";
    public static final String PKG_TAOBAO = "com.taobao.taobao";
    public static final String PKG_MEITUAN = "com.sankuai.meituan";

    public static final String PKG_UNIONPAY = "com.unionpay"; // 【新增】云闪付包名

    // 类型常量
    public static final int TYPE_EXPENSE = 0;
    public static final int TYPE_INCOME = 1;

    /**
     * 获取支持的应用列表
     */
    public static Map<String, String> getSupportedApps() {
        Map<String, String> apps = new HashMap<>();
        apps.put(PKG_WECHAT, "微信");
        apps.put(PKG_ALIPAY, "支付宝");
        apps.put(PKG_TAOBAO, "淘宝");
        apps.put(PKG_JINGDONG, "京东");
        apps.put(PKG_PINDUODUO, "拼多多");
        apps.put(PKG_DOUYIN, "抖音");
        apps.put(PKG_MEITUAN, "美团");
        apps.put("com.sankuai.meituan.takeoutnew", "美团外卖");
        apps.put("com.sankuai.meituan.tuan", "美团团购");
        apps.put("com.aliyun.tongyi", "通义千问");
        apps.put(PKG_UNIONPAY, "云闪付");
        apps.put("com.ss.android.ugc.lifeservices", "抖省省");
        return apps;
    }

    public static SharedPreferences rulePreferences(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static Map<String, String> getRuleApps(Context context) {
        Map<String, String> apps = getSupportedApps();
        for (String pkg : getCustomScreenPackages(context)) apps.putIfAbsent(pkg, pkg);
        return apps;
    }

    public static String[] getCustomScreenPackages(Context context) {
        Set<String> configured = rulePreferences(context).getStringSet(CUSTOM_PACKAGES, new HashSet<>());
        String[] packages = configured.stream()
                .filter(pkg -> ScreenCapturePolicy.canInspect(pkg, context.getPackageName())
                        && !ScreenCapturePolicy.isTargetPackage(pkg) && !ScreenCapturePolicy.PAYMENT_DEMO.equals(pkg)
                        && (!getKeywords(context, pkg, TYPE_EXPENSE).isEmpty() || !getKeywords(context, pkg, TYPE_INCOME).isEmpty()))
                .limit(MAX_CUSTOM_PACKAGES).toArray(String[]::new);
        Arrays.sort(packages);
        return packages;
    }

    /** Explicit publish action. Capturing or replaying by itself never changes production scope. */
    public static boolean publishVerifiedKeyword(Context context, String pkg, int type, String keyword) {
        if (!ScreenCapturePolicy.canInspect(pkg, context.getPackageName()) || ScreenCapturePolicy.PAYMENT_DEMO.equals(pkg)
                || (type != TYPE_EXPENSE && type != TYPE_INCOME) || keyword == null || keyword.trim().isEmpty()) return false;
        SharedPreferences prefs = rulePreferences(context);
        Set<String> words = new HashSet<>(getKeywords(context, pkg, type));
        words.add(keyword.trim());
        SharedPreferences.Editor edit = prefs.edit().putStringSet("keywords_" + pkg + "_" + type, words);
        if (!ScreenCapturePolicy.isTargetPackage(pkg)) {
            Set<String> custom = new HashSet<>(Arrays.asList(getCustomScreenPackages(context)));
            if (custom.size() >= MAX_CUSTOM_PACKAGES && !custom.contains(pkg)) return false;
            custom.add(pkg);
            edit.putStringSet(CUSTOM_PACKAGES, custom);
        }
        edit.apply();
        return true;
    }

    /**
     * 获取指定应用、指定类型的关键字列表
     */
    public static Set<String> getKeywords(Context context, String packageName, int type) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String key = "keywords_" + packageName + "_" + type;
        return prefs.getStringSet(key, new HashSet<>());
    }

    /**
     * 添加关键字
     */
    public static void addKeyword(Context context, String packageName, int type, String keyword) {
        Set<String> current = new HashSet<>(getKeywords(context, packageName, type));
        current.add(keyword);
        saveKeywords(context, packageName, type, current);
    }

    /**
     * 删除关键字
     */
    public static void removeKeyword(Context context, String packageName, int type, String keyword) {
        Set<String> current = new HashSet<>(getKeywords(context, packageName, type));
        current.remove(keyword);
        saveKeywords(context, packageName, type, current);
    }

    private static void saveKeywords(Context context, String packageName, int type, Set<String> newKeywords) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String key = "keywords_" + packageName + "_" + type;
        prefs.edit().putStringSet(key, newKeywords).apply();
    }

    // ================== 新增的方法 (解决编译错误) ==================

    /**
     * 获取所有应用的收入关键字
     * 返回 Map<PackageName, Set<Keywords>>
     */
    public static Map<String, Set<String>> getIncomeKeywords(Context context) {
        Map<String, Set<String>> result = new HashMap<>();
        for (String pkg : getRuleApps(context).keySet()) {
            Set<String> keywords = getKeywords(context, pkg, TYPE_INCOME);
            if (!keywords.isEmpty()) {
                result.put(pkg, keywords);
            }
        }
        return result;
    }

    /**
     * 获取所有应用的支出关键字
     * 返回 Map<PackageName, Set<Keywords>>
     */
    public static Map<String, Set<String>> getExpenseKeywords(Context context) {
        Map<String, Set<String>> result = new HashMap<>();
        for (String pkg : getRuleApps(context).keySet()) {
            Set<String> keywords = getKeywords(context, pkg, TYPE_EXPENSE);
            if (!keywords.isEmpty()) {
                result.put(pkg, keywords);
            }
        }
        return result;
    }

    /**
     * 删除收入关键字的快捷方法
     */
    public static void removeIncomeKeyword(Context context, String packageName, String keyword) {
        removeKeyword(context, packageName, TYPE_INCOME, keyword);
    }

    /**
     * 删除支出关键字的快捷方法
     */
    public static void removeExpenseKeyword(Context context, String packageName, String keyword) {
        removeKeyword(context, packageName, TYPE_EXPENSE, keyword);
    }

    // ==============================================================

    /**
     * 初始化默认数据
     */
    public static void initDefaults(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        
        if (prefs.getAll().isEmpty()) {
//            // --- 微信 ---
//            // 支出
//            addKeyword(context, PKG_WECHAT, TYPE_EXPENSE, "付款方式");
//            // 收入 (包含收款和退款)
//            addKeyword(context, PKG_WECHAT, TYPE_INCOME, "已存入零钱");
//
//            // --- 支付宝 ---
//            // 支出
//            addKeyword(context, PKG_ALIPAY, TYPE_EXPENSE, "支付成功");
        }
    }
}