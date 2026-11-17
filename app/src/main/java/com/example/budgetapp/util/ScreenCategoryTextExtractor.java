package com.example.budgetapp.util;

import java.util.List;
import java.util.regex.Pattern;

/** Selects a small, labelled merchant/product value; never forwards an entire screen to AI. */
public final class ScreenCategoryTextExtractor {
    private static final String[] LABELS = {"商品说明", "商品名称", "商品", "交易说明", "消费说明", "收款方全称",
            "商户全称", "商户名称", "商家名称", "商户简称", "收款方", "收款人", "商户", "商家", "支付给"};
    private static final String[] PRIVATE_LABELS = {"付款方式", "支付方式", "收款方式", "银行卡", "卡号", "订单号",
            "交易单号", "商户单号", "商户订单号", "订单编号", "交易订单号", "交易时间", "支付时间", "创建时间", "收款时间", "账号", "余额", "实付金额", "支付金额", "交易金额",
            "金额", "价格", "总价", "实付款", "合计", "优惠", "立减", "收货地址", "收货人", "电话", "联系电话", "地址", "订单详情", "账单详情", "交易详情", "收银台", "完成", "返回"};
    private static final Pattern PRIVATE_VALUE = Pattern.compile("\\d{4,}|\\*{2,}|尾号|银行卡|卡号|订单号|交易单号|商户单号|余额|付款方式|支付方式");
    private static final Pattern PRICE = Pattern.compile("[¥￥$€£]?\\s*\\d+\\.\\d{1,2}\\s*元?");

    private ScreenCategoryTextExtractor() { }

    public static boolean hasMerchant(String description) {
        return !AutoCategoryClassifier.learningIdentity(description).isEmpty();
    }

    public static String extract(List<String> texts, String existingDescription) {
        // Preserve proven page-specific extraction, if it is not just a platform placeholder.
        if (hasMerchant(existingDescription)) return AutoCategoryClassifier.cleanDescription(existingDescription);
        for (String label : LABELS) {
            for (int i = 0; i < texts.size(); i++) {
                String text = texts.get(i) == null ? "" : texts.get(i).trim();
                String inline = inlineValue(text, label);
                if (inline != null) {
                    String value = merchantValue(inline);
                    if (!value.isEmpty()) return value;
                }
                if (text.equals(label) || text.equals(label + ":") || text.equals(label + "：")) {
                    // Only use the immediately following value, never wander into account fields.
                    for (int j = i + 1; j < Math.min(texts.size(), i + 4); j++) {
                        String value = texts.get(j) == null ? "" : texts.get(j).trim();
                        if (value.isEmpty()) continue;
                        if (isLabel(value)) break;
                        value = merchantValue(value);
                        if (!value.isEmpty()) return value;
                        break;
                    }
                }
            }
        }
        return "";
    }

    private static String inlineValue(String text, String label) {
        if (text.startsWith(label + ":") || text.startsWith(label + "：")) return text.substring(label.length() + 1).trim();
        return null;
    }

    private static boolean isLabel(String value) {
        for (String label : LABELS) if (value.equals(label) || value.startsWith(label + ":") || value.startsWith(label + "：")) return true;
        for (String label : PRIVATE_LABELS) if (value.startsWith(label)) return true;
        return false;
    }

    private static String merchantValue(String value) {
        if (value.length() > 120 || value.contains("\n") || isLabel(value) || PRIVATE_VALUE.matcher(value).find()) return "";
        String cleaned = PRICE.matcher(AutoCategoryClassifier.cleanDescription(value)).replaceAll("").trim();
        if (cleaned.matches("[0-9\\s¥￥$€£元.,:+\\-/*]+") || !hasMerchant(cleaned)) return "";
        return cleaned;
    }
}
