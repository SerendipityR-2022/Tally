package com.example.budgetapp.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.budgetapp.database.AppDatabase;
import com.example.budgetapp.database.AssetAccount;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class AutoAssetManager {

    private static final String PREF_NAME = "auto_asset_prefs";
    private static final String KEY_ENABLE = "key_enable_auto_asset";
    private static final String KEY_RULES = "key_auto_asset_rules";

    // 规则实体类
    public static class AssetRule {
        public String packageName;
        public String keyword;
        public int assetId;

        public AssetRule(String packageName, String keyword, int assetId) {
            this.packageName = packageName;
            this.keyword = keyword == null ? "" : keyword;
            this.assetId = assetId;
        }

        // 序列化存储: pkg|keyword|id
        public String toString() {
            return packageName + "|" + keyword + "|" + assetId;
        }

        public static AssetRule fromString(String str) {
            if (str == null) return null;
            String[] parts = str.split("\\|");
            if (parts.length == 3) {
                try {
                    return new AssetRule(parts[0], parts[1], Integer.parseInt(parts[2]));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }
    }

    public static boolean isEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_ENABLE, false); // 默认关闭
    }

    public static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLE, enabled).apply();
    }

    public static List<AssetRule> getRules(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        Set<String> set = prefs.getStringSet(KEY_RULES, new HashSet<>());
        List<AssetRule> rules = new ArrayList<>();
        for (String s : set) {
            AssetRule rule = AssetRule.fromString(s);
            if (rule != null) rules.add(rule);
        }
        return rules;
    }

    public static void addRule(Context context, AssetRule rule) {
        replaceRule(context, null, rule);
    }

    public static synchronized void replaceRule(Context context, AssetRule previous, AssetRule rule) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        Set<String> set = new HashSet<>(prefs.getStringSet(KEY_RULES, new HashSet<>()));
        if (previous != null) set.remove(previous.toString());
        putRule(set, rule);
        prefs.edit().putStringSet(KEY_RULES, set).apply();
    }

    /** Replace matching rules with the imported rule using the mapped asset ID. */
    public static synchronized void mergeRestoredRules(Context context, List<AssetRule> restored) {
        if (restored.isEmpty()) return;
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        Set<String> rules = new HashSet<>(prefs.getStringSet(KEY_RULES, new HashSet<>()));
        for (AssetRule incoming : restored) putRule(rules, incoming);
        prefs.edit().putStringSet(KEY_RULES, rules).apply();
    }

    private static void putRule(Set<String> rules, AssetRule incoming) {
        rules.removeIf(stored -> {
            AssetRule current = AssetRule.fromString(stored);
            return current != null && current.packageName.equals(incoming.packageName)
                    && current.keyword.equals(incoming.keyword);
        });
        rules.add(incoming.toString());
    }

    public static synchronized void removeRule(Context context, AssetRule rule) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        Set<String> set = new HashSet<>(prefs.getStringSet(KEY_RULES, new HashSet<>()));
        set.remove(rule.toString());
        prefs.edit().putStringSet(KEY_RULES, set).apply();
    }

    /**
     * 核心逻辑：根据当前应用包名和屏幕文字，匹配资产ID
     * 匹配优先级：关键词规则 > 应用默认规则 > 资产名模糊匹配（未命中返回 -1）
     */
    public static int matchAsset(Context context, String packageName, String text) {
        if (!isEnabled(context)) return -1;
        if (packageName == null || packageName.isEmpty()) return -1;

        // Prefer the most specific match, regardless of the preference set's iteration order.
        AssetRule best = null;
        for (AssetRule rule : getRules(context)) {
            if (!packageName.equals(rule.packageName) || rule.assetId <= 0) continue;
            if (!rule.keyword.isEmpty() && (text == null || !text.contains(rule.keyword))) continue;
            if (best == null || rule.keyword.length() > best.keyword.length()
                    || (rule.keyword.length() == best.keyword.length() && rule.assetId < best.assetId)) best = rule;
        }
        if (best != null) return best.assetId;
        if (text == null || text.trim().isEmpty()) return -1;

        // 2. 智能模糊匹配：扫描数据库中的所有资产名称 (优先级高于全局默认资产)
        final int[] matchedId = {-1};
        // 使用 CountDownLatch 进行安全的线程等待，防止主线程直接查库导致报错
        final CountDownLatch latch = new CountDownLatch(1);

        AppDatabase.databaseWriteExecutor.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getDatabase(context);
                List<AssetAccount> allAssets = new ArrayList<>();

                // 获取普通资产和负债账户
                List<AssetAccount> type0 = db.assetAccountDao().getAssetsByTypeSync(0);
                if (type0 != null) allAssets.addAll(type0);

                List<AssetAccount> type1 = db.assetAccountDao().getAssetsByTypeSync(1);
                if (type1 != null) allAssets.addAll(type1);

                for (AssetAccount asset : allAssets) {
                    // 忽略“不关联资产”(id=0)和名字太短(少于2个字)的资产，防止发生诸如 "宝" 匹配上 "支付宝" 的低级误命中
                    if (asset.id == 0 || asset.name == null || asset.name.length() < 2) {
                        continue;
                    }

                    // 核心模糊匹配双向算法：
                    // 场景A: 抓取到的付款方式(text) 包含 资产名(asset.name)
                    //        比如: text="中国银行储蓄卡(4260)", asset.name="中国银行"
                    // 场景B: 资产名(asset.name) 包含 抓取到的付款方式(text)
                    //        比如: text="零钱", asset.name="微信零钱"
                    if (text.contains(asset.name) || asset.name.contains(text)) {
                        matchedId[0] = asset.id;
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                latch.countDown();
            }
        });

        try {
            // 最多等待 500 毫秒，防止由于极端情况下的数据库查询缓慢导致辅助服务卡顿
            latch.await(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // 如果 matchedId[0] 被修改了，说明模糊匹配成功；否则返回 -1，交由底层调用去兜底使用“默认资产”
        return matchedId[0];
    }
}
