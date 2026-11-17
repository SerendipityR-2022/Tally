package com.example.budgetapp.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScreenNodePolicyTest {
    private static final String WECHAT = "com.tencent.mm";

    @Test public void missingPackageIsDistinguishedFromAnotherApplicationsRoot() {
        assertEquals(ScreenNodePolicy.PACKAGE_MISSING,
                ScreenNodePolicy.rejectedBy(WECHAT, null, true, false, false, false));
        assertEquals(ScreenNodePolicy.PACKAGE_MISSING,
                ScreenNodePolicy.rejectedBy(WECHAT, "", true, false, false, false));
        assertEquals(ScreenNodePolicy.PACKAGE_MISMATCH,
                ScreenNodePolicy.rejectedBy(WECHAT, "com.android.systemui", true, false, false, false));
    }

    @Test public void rejectedRootIncludesAllRelevantFlagsRatherThanJustTheFirstOne() {
        int reasons = ScreenNodePolicy.rejectedBy(WECHAT, WECHAT, false, true, true, true);
        assertEquals("NOT_VISIBLE|EDITABLE|PASSWORD|DATA_SENSITIVE", ScreenNodePolicy.reasons(reasons));
        assertEquals(0, reasons & ScreenNodePolicy.PACKAGE_MISMATCH);
        assertEquals("ACCEPTED", ScreenNodePolicy.reasons(
                ScreenNodePolicy.rejectedBy(WECHAT, WECHAT, true, false, false, false)));
    }

    @Test public void everyExistingRestrictionIndependentlyRejectsTheNode() {
        assertEquals(ScreenNodePolicy.NOT_VISIBLE,
                ScreenNodePolicy.rejectedBy(WECHAT, WECHAT, false, false, false, false));
        assertEquals(ScreenNodePolicy.EDITABLE,
                ScreenNodePolicy.rejectedBy(WECHAT, WECHAT, true, true, false, false));
        assertEquals(ScreenNodePolicy.PASSWORD,
                ScreenNodePolicy.rejectedBy(WECHAT, WECHAT, true, false, true, false));
        assertEquals(ScreenNodePolicy.DATA_SENSITIVE,
                ScreenNodePolicy.rejectedBy(WECHAT, WECHAT, true, false, false, true));
    }

    @Test public void noRootAndRejectedRootProduceDifferentExplanations() {
        ScreenNodeCopyReport absent = new ScreenNodeCopyReport();
        absent.missingRoot();
        assertEquals("SYSTEM_ROOT_NULL", absent.rootResult());
        ScreenNodeCopyReport refused = new ScreenNodeCopyReport();
        refused.inspect(0, ScreenNodePolicy.NOT_VISIBLE | ScreenNodePolicy.DATA_SENSITIVE);
        assertEquals("root=NOT_VISIBLE|DATA_SENSITIVE", refused.rootResult());
        assertTrue(refused.summary().contains("visited=1; copied=0; blockedSubtrees=1"));
        assertTrue(refused.summary().contains("descendants of blocked nodes NOT inspected"));
        assertEquals("ROOT_NOT_INSPECTED", new ScreenNodeCopyReport().rootResult());
    }

    @Test public void descendantsCanBeCountedWithoutMisreportingAnAcceptedRoot() {
        ScreenNodeCopyReport report = new ScreenNodeCopyReport();
        report.inspect(0, 0);
        report.inspect(1, 0);
        report.copied();
        report.inspect(1, ScreenNodePolicy.PASSWORD | ScreenNodePolicy.EDITABLE);
        report.nullChild();
        report.copied();
        String log = report.summary();
        assertTrue(log.startsWith("root=ACCEPTED; visited=3; copied=2; blockedSubtrees=1; nullChildren=1"));
        assertTrue(log.contains("EDITABLE=1; PASSWORD=1"));
    }

    @Test public void rejectionExamplesAreBoundedEvenForALargeTree() {
        ScreenNodeCopyReport report = new ScreenNodeCopyReport();
        report.inspect(0, 0);
        for (int i = 0; i < 200; ++i) {
            if (report.inspect(1, ScreenNodePolicy.NOT_VISIBLE)) report.example(1, ScreenNodePolicy.NOT_VISIBLE, "sample-" + i);
        }
        assertTrue(report.summary().contains("NOT_VISIBLE=200"));
        assertTrue(report.summary().contains("sample-5"));
        assertFalse(report.summary().contains("sample-6"));
    }

    @Test public void metadataCannotSpoofNewLogEntriesOrConsumeUnboundedLogSpace() {
        String spoof = DiagnosticValue.of("android.view.View\n[reader.frame] fake\u202e\r");
        assertFalse(spoof.contains("\n"));
        assertFalse(spoof.contains("\r"));
        assertTrue(spoof.contains("\\u000a"));
        assertTrue(spoof.contains("\\u202e"));
        assertEquals("<null>", DiagnosticValue.of(null));
        assertEquals("<empty>", DiagnosticValue.of(""));
        assertTrue(DiagnosticValue.of(new String(new char[10000]).replace('\0', 'x')).length() < 300);
    }
}
