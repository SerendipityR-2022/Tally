package com.example.budgetapp.util;

import com.example.budgetapp.ai.TransactionDraft;
import com.example.budgetapp.database.Transaction;
import org.junit.Test;

import static org.junit.Assert.*;

public class TransactionRemarksTest {
    @Test public void legacyAutomaticallyInsertedDatesDoNotRepeatTheTransactionTime() {
        assertEquals("", TransactionRemarks.display(transaction("11-16 08:35", "")));
        assertEquals("", TransactionRemarks.display(transaction("11-16 08:35 manual", "")));
        assertEquals("美味烧鸡", TransactionRemarks.display(transaction("11-16 08:35 美味烧鸡", "")));
        assertEquals("买给家里 · 美味烧鸡",
                TransactionRemarks.display(transaction("11-16 08:35 美味烧鸡", "买给家里")));
        assertEquals("美味烧鸡", TransactionRemarks.display(transaction("11-16 08:35 美味烧鸡", "美味烧鸡")));
        assertEquals("11-16 08:35 提货", TransactionRemarks.display(transaction(null, "11-16 08:35 提货")));
    }

    @Test public void editingTheRemarkCanReplaceOldFreeTextWithoutLosingOvertimeData() {
        Transaction old = transaction("11-16 08:35 商家", "标签补充");
        Transaction sameText = transaction("", TransactionRemarks.display(old));
        TransactionRemarks.applyEdit(old, sameText, sameText.remark);
        assertEquals(old.note, sameText.note);
        assertEquals(old.remark, sameText.remark);

        Transaction removed = transaction("", "");
        TransactionRemarks.applyEdit(old, removed, "");
        assertEquals("", TransactionRemarks.display(removed));

        old.category = "加班";
        old.note = "时长: 3小时, 时薪: 20";
        Transaction amended = transaction("", "周末加班");
        TransactionRemarks.applyEdit(old, amended, amended.remark);
        assertEquals(old.note, amended.note);
        assertEquals("周末加班", amended.remark);
    }

    @Test public void scheduledTimeComesFromTheScheduleAndAiUsesOnlyTheRemark() {
        Transaction scheduled = transaction("生效时间 11-16 08:35", "");
        scheduled.recurringRuleId = 7;
        scheduled.scheduledExecuteTime = 150;
        assertEquals("", TransactionRemarks.display(scheduled));
        assertEquals(150, TransactionRemarks.displayTime(scheduled));

        TransactionDraft draft = new TransactionDraft();
        draft.note = "  午餐  ";
        draft.date = 123;
        Transaction saved = draft.toTransaction();
        assertNull(saved.note);
        assertEquals("午餐", saved.remark);
        assertEquals(123, saved.date);
    }

    private static Transaction transaction(String note, String remark) {
        return new Transaction(10, 0, "餐饮", 20, note, remark);
    }
}
