package com.example.budgetapp.util;

import android.content.Context;
import android.content.res.Configuration;

import androidx.annotation.DrawableRes;

import com.example.budgetapp.R;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** IDs are stored in backups and must stay independent of resource IDs and display names. */
public enum CategoryIcon {
    SPENDING("spending", "消费", R.drawable.ic_ledger_spending, 0xFF966011, 0xFFFFBE62),
    FOOD("food", "餐饮", R.drawable.ic_ledger_food, 0xFFAC4F25, 0xFFFF9C6B),
    CART("cart", "购物", R.drawable.ic_ledger_cart, 0xFF3167C8, 0xFF83A7FF),
    HOME("home", "住房", R.drawable.ic_ledger_home, 0xFF865B1D, 0xFFE4B369),
    CAR("car", "交通", R.drawable.ic_ledger_car, 0xFF19735D, 0xFF71D0B0),
    PHONE("phone", "通讯", R.drawable.ic_ledger_phone, 0xFF35679D, 0xFF8FC2F7),
    GAME("game", "娱乐", R.drawable.ic_ledger_game, 0xFF7A53AD, 0xFFCBABF4),
    HEALTH("health", "医疗", R.drawable.ic_ledger_health, 0xFFAD3F54, 0xFFFF9BAA),
    BOOK("book", "教育", R.drawable.ic_ledger_book, 0xFF5265AA, 0xFFAAB8FF),
    ENVELOPE("envelope", "红包", R.drawable.ic_ledger_envelope, 0xFFB14548, 0xFFFF9F9D),
    TRANSFER("transfer", "转账", R.drawable.ic_ledger_transfer, 0xFF395CAD, 0xFF9BB1FF),
    TRAVEL("travel", "旅行", R.drawable.ic_ledger_travel, 0xFF20748A, 0xFF7DD2E5),
    INVESTMENT("investment", "投资", R.drawable.ic_ledger_investment, 0xFF287358, 0xFF85D8A9),
    LEND("lend", "借出", R.drawable.ic_ledger_lend, 0xFF886020, 0xFFF0BB70),
    REPAYMENT("repayment", "还款", R.drawable.ic_ledger_repayment, 0xFF6256A6, 0xFFBDB0F5),
    BEAUTY("beauty", "美容", R.drawable.ic_ledger_beauty, 0xFFAA4B7B, 0xFFF5A6CC),
    FAMILY("family", "亲子", R.drawable.ic_ledger_family, 0xFF9A4F36, 0xFFE9AC90),
    SOCIAL("social", "人情社交", R.drawable.ic_ledger_social, 0xFF2A755D, 0xFF8ADABD),
    PET("pet", "宠物", R.drawable.ic_ledger_pet, 0xFF865192, 0xFFDBB0EB),
    DELIVERY("delivery", "快递", R.drawable.ic_ledger_delivery, 0xFF2A6F8C, 0xFF92CFE9),
    OTHER("other", "其他", R.drawable.ic_ledger_other, 0xFF606675, 0xFFB6BDCD),
    WORK("work", "工作", R.drawable.ic_ledger_work, 0xFF287358, 0xFF8ADABD),
    GIFT("gift", "礼物", R.drawable.ic_ledger_gift, 0xFFAD466E, 0xFFEE9EBE),
    WALLET("wallet", "钱包", R.drawable.ic_ledger_wallet, 0xFF8C5F1C, 0xFFF0BE68),
    TAG("tag", "标签", R.drawable.ic_ledger_tag, 0xFF606675, 0xFFB6BDCD),
    SALARY("salary", "薪资", R.drawable.ic_ledger_salary, 0xFFB14548, 0xFFFF9F9D),
    FINANCE("finance", "理财", R.drawable.ic_ledger_finance, 0xFF966011, 0xFFFFBE62),
    TRANSFER_IN("transfer_in", "收转账", R.drawable.ic_ledger_transfer_in, 0xFF966011, 0xFFFFBE62),
    BORROW("borrow", "借入", R.drawable.ic_ledger_borrow, 0xFF966011, 0xFFFFBE62),
    RECEIPT("receipt", "收款", R.drawable.ic_ledger_receipt, 0xFF19735D, 0xFF71D0B0);

    private static final Map<String, CategoryIcon> BY_ID;
    private static final Map<String, CategoryIcon> BY_LABEL;
    static {
        Map<String, CategoryIcon> ids = new HashMap<>();
        Map<String, CategoryIcon> labels = new HashMap<>();
        for (CategoryIcon icon : values()) {
            ids.put(icon.id, icon);
            labels.put(icon.label, icon);
        }
        labels.put("收红包", ENVELOPE);
        BY_ID = Collections.unmodifiableMap(ids);
        BY_LABEL = Collections.unmodifiableMap(labels);
    }

    public final String id;
    public final String label;
    @DrawableRes public final int drawable;
    private final int light, dark;

    CategoryIcon(String id, String label, int drawable, int light, int dark) {
        this.id = id;
        this.label = label;
        this.drawable = drawable;
        this.light = light;
        this.dark = dark;
    }

    public int tint(Context context) {
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES ? dark : light;
    }

    public static CategoryIcon fromId(String id) { return BY_ID.get(id); }

    public static CategoryIcon forName(String name) {
        if (name == null || name.isEmpty()) return OTHER;
        CategoryIcon exact = BY_LABEL.get(name);
        if (exact != null) return exact;
        if ("自定义".equals(name)) return TAG;
        if (contains(name, "快递", "物流", "邮寄")) return DELIVERY;
        if (contains(name, "借出", "借入")) return LEND;
        if (contains(name, "还款", "还贷")) return REPAYMENT;
        if (contains(name, "转账", "互转")) return TRANSFER;
        if (contains(name, "通讯", "通信", "话费", "电话", "宽带")) return PHONE;
        if (contains(name, "旅游", "旅行", "机票")) return TRAVEL;
        if (contains(name, "投资", "理财", "基金", "股票")) return INVESTMENT;
        if (contains(name, "美容", "美妆", "护肤", "理发")) return BEAUTY;
        if (contains(name, "亲子", "育儿", "母婴")) return FAMILY;
        if (contains(name, "宠物", "猫粮", "狗粮")) return PET;
        if (contains(name, "餐", "食", "饮", "外卖", "水果")) return FOOD;
        if (contains(name, "购物", "网购", "服饰", "日用")) return CART;
        if (contains(name, "居住", "居家", "住房", "房租", "水电", "物业")) return HOME;
        if (contains(name, "交通", "出行", "油费", "车")) return CAR;
        if (contains(name, "医", "药", "健康")) return HEALTH;
        if (contains(name, "教育", "学习", "书", "课程")) return BOOK;
        if (contains(name, "娱乐", "游戏", "电影", "影音")) return GAME;
        if (contains(name, "工资", "工作", "奖金", "加班", "兼职")) return WORK;
        if (contains(name, "红包")) return ENVELOPE;
        if (contains(name, "人情", "社交")) return SOCIAL;
        if (contains(name, "礼")) return GIFT;
        if (contains(name, "其他", "其它")) return OTHER;
        return WALLET;
    }

    private static boolean contains(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }
}
