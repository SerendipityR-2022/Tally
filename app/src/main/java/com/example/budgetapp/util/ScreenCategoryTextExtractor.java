package com.example.budgetapp.util;

import java.util.List;
import java.util.regex.Pattern;

/** Selects a small, labelled merchant/product value; never forwards an entire screen to AI. */
public final class ScreenCategoryTextExtractor {
    private static final String[] PRODUCT_LABELS = {"商品说明", "商品名称", "商品", "交易说明", "消费说明"};
    private static final String[] PAYEE_LABELS = {"收款方全称", "收款方名称", "收款方", "收款人姓名", "收款人名称", "收款人",
            "商户全称", "商户名称", "商家名称", "商户简称", "商户", "商家", "支付给"};
    private static final String[] PRIVATE_LABELS = {"付款方式", "支付方式", "收款方式", "银行卡", "卡号", "订单号",
            "交易单号", "商户单号", "商户订单号", "订单编号", "交易订单号", "交易时间", "支付时间", "创建时间", "收款时间", "账号", "余额", "实付金额", "支付金额", "交易金额", "订单金额", "订单总额", "实付", "实际支付", "应付金额",
            "金额", "价格", "总价", "实付款", "合计", "优惠", "立减", "收货地址", "收货人", "电话", "联系电话", "地址", "订单详情", "账单详情", "交易详情", "收银台", "支付成功", "账户余额", "完成", "返回", "查看订单", "查看账单", "查看详情", "领取"};
    private static final Pattern PRIVATE_VALUE = Pattern.compile("\\d{4,}|尾号|银行卡|卡号|订单号|交易单号|商户单号|余额|付款方式|支付方式");
    private static final Pattern PRICE = Pattern.compile("[¥￥$€£]?\\s*\\d+\\.\\d{1,2}\\s*元?");

    private static final Pattern AMOUNT = Pattern.compile("[+\\-−]?\\s*[¥￥$€£]?\\s*\\d[\\d,]*(?:\\.\\d{1,2})?\\s*元?");

    private ScreenCategoryTextExtractor() { }

    public static boolean hasMerchant(String description) {
        String value = AutoCategoryClassifier.cleanDescription(description);
        return !isLabel(value) && !AMOUNT.matcher(value).matches()
                && !AutoCategoryClassifier.learningIdentity(value).isEmpty();
    }

    public static String extract(List<String> texts, String existingDescription) {
        // Preserve proven page-specific extraction, if it is not just a platform placeholder.
        if (hasMerchant(existingDescription)) return AutoCategoryClassifier.cleanDescription(existingDescription);
        String product = extractLabelled(texts, PRODUCT_LABELS);
        return product.isEmpty() ? extractLabelled(texts, PAYEE_LABELS) : product;
    }

    /** Alipay receipts without a label may show the payee directly under the headline amount. */
    public static String extractAlipayPayee(List<String> texts, int amountIndex) {
        String labelled = extractLabelled(texts, PAYEE_LABELS);
        if (!labelled.isEmpty()) return labelled;
        if (amountIndex < 0) return "";
        for (int i = amountIndex + 1; i < texts.size(); i++) {
            String value = texts.get(i) == null ? "" : texts.get(i).trim();
            if (value.isEmpty() || AMOUNT.matcher(value).matches()) continue;
            // Stop at a detail row or action instead of borrowing a value from another field.
            return merchantValue(value);
        }
        return "";
    }

    private static String extractLabelled(List<String> texts, String[] labels) {
        for (String label : labels) {
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
        return hasLabel(value, PAYEE_LABELS) || hasLabel(value, PRODUCT_LABELS) || isPrivateLabel(value);
    }

    private static boolean hasLabel(String value, String[] labels) {
        for (String label : labels) if (value.equals(label) || inlineValue(value, label) != null) return true;
        return false;
    }

    private static boolean isPrivateLabel(String value) {
        for (String label : PRIVATE_LABELS) if (value.startsWith(label)) return true;
        return false;
    }

    private static String merchantValue(String value) {
        String privateValue = value.replace("*", "").replace("＊", "");
        if (value.length() > 120 || value.contains("\n") || isLabel(value) || PRIVATE_VALUE.matcher(privateValue).find()) return "";
        String cleaned = PRICE.matcher(AutoCategoryClassifier.cleanDescription(value)).replaceAll("").trim();
        return hasMerchant(cleaned) ? cleaned : "";
    }
}
