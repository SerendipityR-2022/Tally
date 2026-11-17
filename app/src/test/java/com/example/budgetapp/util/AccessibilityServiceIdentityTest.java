package com.example.budgetapp.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class AccessibilityServiceIdentityTest {
    private static final String APP = "com.example.budgetapp";
    private static final String SERVICE =
            "com.google.android.accessibility.selecttospeak.SelectToSpeakService";

    @Test public void gkdCompanionDoesNotKeepTallyMarkedAsRunning() {
        assertFalse(AccessibilityServiceIdentity.matches(APP,
                "li.songe.gkd/" + SERVICE + "Companion"));
        assertFalse(AccessibilityServiceIdentity.matches(APP,
                "com.google.android.marvin.talkback/" + SERVICE));
        assertTrue(AccessibilityServiceIdentity.matches(APP, APP + "/" + SERVICE));
    }

    @Test public void onlyTheExactServiceInTheExactPackageMatches() {
        assertFalse(AccessibilityServiceIdentity.matches(APP, APP + "/.service.QuickAddTileService"));
        assertFalse(AccessibilityServiceIdentity.matches(APP, APP + "/" + SERVICE + "Companion"));
        assertFalse(AccessibilityServiceIdentity.matches(APP, APP + ".other/" + SERVICE));
    }

    @Test public void aSeparateTestPackageNeverTargetsTheInstalledApp() {
        String qa = APP + ".categoryqa";
        assertEquals(qa + "/" + SERVICE, AccessibilityServiceIdentity.componentId(qa));
        assertTrue(AccessibilityServiceIdentity.matches(qa, qa + "/" + SERVICE));
        assertFalse(AccessibilityServiceIdentity.matches(qa, APP + "/" + SERVICE));
        assertFalse(AccessibilityServiceIdentity.matches(APP, qa + "/" + SERVICE));
    }

    @Test public void missingServiceInformationIsNotAnEnabledService() {
        assertFalse(AccessibilityServiceIdentity.matches(APP, null));
        assertFalse(AccessibilityServiceIdentity.matches(APP, ""));
        assertFalse(AccessibilityServiceIdentity.matches(null, APP + "/" + SERVICE));
        assertFalse(AccessibilityServiceIdentity.matches("", APP + "/" + SERVICE));
    }
}
