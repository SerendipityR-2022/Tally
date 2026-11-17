package com.example.budgetapp.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure Java classification policy shared by every screen-payment adapter. */
public final class AutoCategoryClassifier {
    public enum Source { RULE, LEARNED, AI, LOCAL, HINT, FALLBACK }

    public static final class Category {
        public final String name;
        public final List<String> children;

        public Category(String name, List<String> children) {
            this.name = value(name);
            this.children = Collections.unmodifiableList(new ArrayList<>(children));
        }
    }

    public static final class Input {
        public final String packageName;
        public final String description;
        public final String merchantIdentity;
        public final int type;
        public final String hint;

        public Input(String packageName, String description, int type, String hint) {
            this.packageName = value(packageName);
            this.description = ScreenCategoryTextExtractor.removeLongNumbers(cleanDescription(description));
            this.merchantIdentity = ScreenCategoryTextExtractor.merchantIdentity(description, this.packageName);
            this.type = type;
            this.hint = value(hint);
        }
    }

    public static final class Rule {
        public final String keyword, category, subCategory;
        public final int type;

        public Rule(String keyword, String category, String subCategory, int type) {
            this.keyword = value(keyword);
            this.category = value(category);
            this.subCategory = value(subCategory);
            this.type = type;
        }
    }

    public static final class Learned {
        public final String packageName, identity, category, subCategory;
        public final int type;

        public Learned(String packageName, String identity, int type, String category, String subCategory) {
            this.packageName = value(packageName);
            this.identity = value(identity);
            this.type = type;
            this.category = value(category);
            this.subCategory = value(subCategory);
        }
    }

    public static final class Result {
        public final String category, subCategory;
        public final Source source;

        public Result(String category, String subCategory, Source source) {
            this.category = value(category);
            this.subCategory = value(subCategory);
            this.source = source;
        }
    }

    private static final Pattern NORMALIZE = Pattern.compile("[\\p{Z}\\s\\p{P}\\p{S}]+");
    private static final Pattern LEARNING_NORMALIZE = Pattern.compile("[\\p{Z}\\s\\p{P}\\p{S}&&[^*]]+");
    private static final Pattern LEADING_TIME = Pattern.compile("^\\s*(?:(?:\\d{4}[-/.年])?\\d{1,2}[-/.月]\\d{1,2}日?\\s+\\d{1,2}:\\d{2}(?::\\d{2})?\\s*|\\d{1,2}:\\d{2}(?::\\d{2})?\\s*)");
    private static final Pattern BACKGROUND_SUFFIX = Pattern.compile("\\s*[（(]后台[）)]\\s*$");
    private static final Set<String> PLACEHOLDERS = new HashSet<>(Arrays.asList(
            "微信", "支付宝", "云闪付", "自动记账", "微信账单", "支付宝账单", "微信支付", "支付宝支付", "云闪付交易",
            "支付成功", "付款成功", "商家转账", "转账", "红包", "微信扫码付款", "微信转账收款", "微信商家转账",
            "京东购物", "拼多多购物", "淘宝购物", "抖音购物", "美团消费", "抖省省消费", "通义千问支付"));
    private static final Set<String> COMMERCE_NAMES = new HashSet<>(Arrays.asList(
            "京东", "淘宝", "拼多多", "抖音", "美团", "抖省省", "通义千问"));

    private static final class Words {
        final String[] labels, keywords;
        Words(String labels, String keywords) {
            this.labels = normalizedWords(labels);
            this.keywords = normalizedWords(keywords);
        }
    }

    // These are semantic vocabularies, not fixed category destinations. Destinations
    // are always selected from the user's CURRENT catalog (including new names).
    private static final Words[] GROUPS = {
            new Words("餐饮,饮食,吃喝,美食,伙食,餐费,食品", "餐厅,餐饮,外卖,美团,饿了么,烧烤,麻辣烫,汉堡,奶茶,咖啡,早餐,午餐,晚餐,夜宵,拉面,面馆,饭店,食堂,肯德基,麦当劳,瑞幸,星巴克,蜜雪,喜茶,茶百道,霸王茶姬,沪上阿姨,餐馆,小吃,面包,蛋糕"),
            new Words("交通,出行,通勤,车费", "滴滴,打车,出租车,地铁,公交,高铁,火车,机票,铁路,12306,加油,停车,充电桩,高速"),
            new Words("购物,采购,日用,消费", "淘宝,天猫,京东,拼多多,超市,便利店,百货,商场,手机店,手机,面膜,肥皂,日用品,美宜佳,7-11,罗森,全家"),
            new Words("医疗,健康,医药,看病", "医院,药店,药房,门诊,挂号,体检,医疗,诊所,看病,买药"),
            new Words("教育,学习,培训", "学费,培训,课程,教材,书店,考试,教育"),
            new Words("居家,住房,生活,家庭", "房租,水费,电费,燃气,物业,宽带,家具,家政"),
            new Words("通讯,通信,话费,手机充值", "中国移动,中国联通,中国电信,话费,手机充值,流量包"),
            new Words("娱乐,休闲,玩乐", "电影,影院,游戏,游乐,演唱会,ktv,视频会员"),
            new Words("宠物,萌宠,毛孩子,养宠", "猫粮,狗粮,宠物,猫砂,狗狗,猫咪,宠物医院"),
            new Words("工资,薪资,薪水", "工资,薪资,薪水,代发工资"),
            new Words("奖金,奖励", "奖金,绩效,年终奖"),
            new Words("投资,理财,收益", "理财收益,基金收益,股票,分红,利息"),
            new Words("红包,礼金,人情", "红包,礼金,压岁钱,份子钱"),
            new Words("退款,退货", "退款,退货,返现"),
            new Words("转账,转入,转出", "转账,转入,转出"),
            new Words("二手交易,闲置", "闲鱼,二手,转转")
    };

    private static final Words[] DETAILS = {
            new Words("咖啡", "咖啡,瑞幸,星巴克,库迪"),
            new Words("奶茶,饮品,饮料,茶饮", "奶茶,蜜雪,喜茶,茶百道,霸王茶姬,沪上阿姨,古茗,甜啦啦"),
            new Words("早餐,早饭", "早餐,早饭"),
            new Words("午餐,午饭", "午餐,午饭"),
            new Words("晚餐,晚饭", "晚餐,晚饭"),
            new Words("快餐", "汉堡,肯德基,麦当劳,快餐"),
            new Words("便利店", "便利店,美宜佳,7-11,罗森,全家"),
            new Words("超市", "超市,沃尔玛,永辉,大润发,盒马"),
            new Words("电商,网购", "淘宝,天猫,京东,拼多多"),
            new Words("打车,出租车", "滴滴,打车,出租车"),
            new Words("地铁,公交", "地铁,公交"),
            new Words("停车", "停车"),
            new Words("话费,通信,通讯", "话费,中国移动,中国联通,中国电信,手机充值"),
            new Words("电影", "电影,影院"),
            new Words("猫粮,狗粮,宠物食品", "猫粮,狗粮,宠物食品")
    };

    private AutoCategoryClassifier() { }

    /** Default order retained for existing callers and installations. */
    public static Result classifyLocal(Input input, List<Category> catalog, List<Rule> rules,
                                       List<Learned> learned, boolean useSubCategories) {
        return classifyLocal(input, catalog, rules, learned, useSubCategories, AutoCategoryPriority.defaults());
    }

    public static Result classifyLocal(Input input, List<Category> catalog, List<Rule> rules,
            List<Learned> learned, boolean useSubCategories, AutoCategoryPriority priority) {
        String text = normalize(input.description);
        String identity = input.merchantIdentity;
        for (Source source : priority.getSources()) {
            switch (source) {
                case RULE:
                    for (Rule rule : rules) {
                        String keyword = normalize(rule.keyword);
                        if (rule.type == input.type && !keyword.isEmpty() && text.contains(keyword)) {
                            Result result = validate(catalog, rule.category, rule.subCategory, Source.RULE, useSubCategories);
                            if (result != null) return result;
                        }
                    }
                    break;
                case LEARNED:
                    if (!identity.isEmpty()) {
                        for (Learned item : learned) {
                            if (item.type == input.type && item.packageName.equals(input.packageName)
                                    && item.identity.equals(identity)) {
                                Result result = validate(catalog, item.category, item.subCategory, Source.LEARNED, useSubCategories);
                                if (result != null) return result;
                            }
                        }
                    }
                    break;
                case LOCAL:
                    Result names = matchNames(catalog, text, useSubCategories);
                    if (names != null) return names;
                    Result semantics = matchSemantics(catalog, text, useSubCategories);
                    if (semantics != null) return semantics;
                    break;
                case HINT:
                    // A generic page hint is not a match; "other" must remain the final fallback.
                    if ("其他".equals(input.hint) || "自定义".equals(input.hint)) break;
                    Result hint = validate(catalog, input.hint, "", Source.HINT, useSubCategories);
                    if (hint != null) return hint;
                    Result meaning = matchNames(catalog, normalize(input.hint), useSubCategories);
                    if (meaning == null) meaning = matchSemantics(catalog, normalize(input.hint), useSubCategories);
                    if (meaning != null) return new Result(meaning.category, meaning.subCategory, Source.HINT);
                    break;
                default:
                    break; // AI is asynchronous; FALLBACK cannot mask real strategies.
            }
        }
        return new Result("其他", "", Source.FALLBACK);
    }

    public static Result applyAi(List<Category> catalog, Result local, String category,
                                 String subCategory, boolean useSubCategories) {
        Result ai = validateAi(catalog, category, subCategory, useSubCategories);
        return ai == null ? local : ai;
    }

    public static Result validateAi(List<Category> catalog, String category, String subCategory, boolean useSubCategories) {
        Result result = validate(catalog, category, subCategory, Source.AI, useSubCategories);
        // A stale or misspelled second level should not discard a valid model-selected primary.
        return result != null ? result : validate(catalog, category, "", Source.AI, false);
    }

    public static Result validate(List<Category> catalog, String category, String subCategory,
                                  Source source, boolean useSubCategories) {
        for (Category item : catalog) {
            if (!normalize(item.name).equals(normalize(category)) || normalize(category).isEmpty()) continue;
            if (!useSubCategories || value(subCategory).isEmpty()) return new Result(item.name, "", source);
            for (String child : item.children) {
                if (normalize(child).equals(normalize(subCategory))) return new Result(item.name, child, source);
            }
            return null;
        }
        return null;
    }

    private static Result matchNames(List<Category> catalog, String text, boolean children) {
        Result best = null;
        int bestScore = 0;
        for (Category category : catalog) {
            if ("其他".equals(category.name)) continue;
            int primaryScore = nameScore(category.name, text);
            if (primaryScore > bestScore) {
                bestScore = primaryScore;
                best = new Result(category.name, children ? bestChild(category, text) : "", Source.LOCAL);
            }
            if (children) {
                for (String child : category.children) {
                    int score = nameScore(child, text);
                    if (score > 0) score += 1; // equally specific child beats primary
                    if (score > bestScore) {
                        bestScore = score;
                        best = new Result(category.name, child, Source.LOCAL);
                    }
                }
            }
        }
        return best;
    }

    private static int nameScore(String name, String text) {
        String label = normalize(name);
        int score = label.length() >= 2 && text.contains(label) ? label.length() * 100 : 0;
        for (Words words : DETAILS) {
            if (longestMatch(text, words.keywords) > 0) {
                score = Math.max(score, longestMatch(label, words.labels) * 10);
            }
        }
        return score;
    }

    private static Result matchSemantics(List<Category> catalog, String text, boolean children) {
        Result best = null;
        int bestScore = 0;
        for (Words group : GROUPS) {
            int strength = Math.max(longestMatch(text, group.keywords), longestMatch(text, group.labels));
            if (strength == 0) continue;
            for (Category category : catalog) {
                String label = normalize(category.name);
                int affinity = Math.max(longestMatch(label, group.labels), longestMatch(label, group.keywords));
                int score = affinity == 0 ? 0 : strength * 100 + affinity;
                if (score > bestScore) {
                    bestScore = score;
                    best = new Result(category.name, children ? bestChild(category, text) : "", Source.LOCAL);
                }
            }
        }
        return best;
    }

    private static String bestChild(Category category, String text) {
        int score = 0;
        String best = "";
        for (String child : category.children) {
            int candidate = nameScore(child, text);
            if (candidate > score) {
                score = candidate;
                best = child;
            }
        }
        return best;
    }

    private static String[] normalizedWords(String csv) {
        String[] words = csv.split(",");
        for (int i = 0; i < words.length; i++) words[i] = normalize(words[i]);
        return words;
    }

    private static int longestMatch(String text, String[] keywords) {
        int longest = 0;
        for (String keyword : keywords) {
            if (keyword.length() >= 2 && text.contains(keyword)) longest = Math.max(longest, keyword.length());
        }
        return longest;
    }

    public static String cleanDescription(String text) {
        return BACKGROUND_SUFFIX.matcher(LEADING_TIME.matcher(value(text)).replaceFirst("")).replaceFirst("").trim();
    }

    /** Exact, app- and type-scoped merchant identity; never learn generic/time-only screens. */
    public static String learningIdentity(String description) {
        return learningIdentity(description, "");
    }

    static String learningIdentity(String description, String sourcePackage) {
        // Keep redaction markers: **（张） and 张** are different merchant identities.
        String identity = LEARNING_NORMALIZE.matcher(cleanDescription(description).toLowerCase(Locale.ROOT)
                .replace('＊', '*')).replaceAll("");
        if (identity.length() < 2 || identity.matches("[0-9*]+") || PLACEHOLDERS.contains(identity)) return "";
        // A commerce app name can be the payee on an independent payment receipt, e.g. 美团 in 微信支付.
        if (COMMERCE_NAMES.contains(identity) && !KeywordManager.PKG_WECHAT.equals(sourcePackage)
                && !KeywordManager.PKG_ALIPAY.equals(sourcePackage) && !KeywordManager.PKG_UNIONPAY.equals(sourcePackage)) return "";
        return identity;
    }

    public static String normalize(String text) {
        return NORMALIZE.matcher(value(text).toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private static String value(String text) {
        return text == null ? "" : text.trim();
    }
}
