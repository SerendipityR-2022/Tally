package com.example.budgetapp.ui;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.budgetapp.util.ScreenCaptureSettings;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ExperimentalSettingsMigrationInstrumentedTest {
    @Test public void obsoleteReaderSelectionDoesNotChangeOtherSettings() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences(ScreenCaptureSettings.PREFS, Context.MODE_PRIVATE);
        ScreenCaptureSettings before = new ScreenCaptureSettings(context);
        boolean enabled = before.isEnabled(), silent = before.isSilentAccountingEnabled(), keepAlive = before.isKeepAliveEnabled();
        assertTrue(prefs.edit().putInt("experimental_reader_mode", 1).commit());
        ScreenCaptureSettings migrated = new ScreenCaptureSettings(context);
        assertFalse(prefs.contains("experimental_reader_mode"));
        assertEquals(enabled, migrated.isEnabled());
        assertEquals(silent, migrated.isSilentAccountingEnabled());
        assertEquals(keepAlive, migrated.isKeepAliveEnabled());
    }
}
