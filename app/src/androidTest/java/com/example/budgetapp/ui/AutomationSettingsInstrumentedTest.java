package com.example.budgetapp.ui;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.widget.SwitchCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.budgetapp.R;
import com.example.budgetapp.util.RecentTaskSettings;
import com.example.budgetapp.util.ScreenCaptureSettings;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AutomationSettingsInstrumentedTest {
    @Test public void recentTasksSettingIsSearchableAndReversible() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        boolean previous = RecentTaskSettings.isHidden(context);
        try {
            RecentTaskSettings.setHidden(context, false);
            try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(SettingsActivity.class)) {
                scenario.onActivity(activity -> {
                    EditText search = activity.findViewById(R.id.et_settings_search);
                    search.setText("最近任务");
                    SwitchCompat hidden = activity.findViewById(R.id.switch_hide_in_recents);
                    assertEquals(View.VISIBLE, hidden.getVisibility());
                    assertFalse(hidden.isChecked());
                    assertFalse(excludedFromRecents(activity));
                    hidden.performClick();
                    assertTrue(RecentTaskSettings.isHidden(activity));
                    assertTrue(excludedFromRecents(activity));
                });
            }
            try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(SettingsActivity.class)) {
                scenario.onActivity(activity -> {
                    SwitchCompat hidden = activity.findViewById(R.id.switch_hide_in_recents);
                    assertTrue(hidden.isChecked());
                    assertTrue(excludedFromRecents(activity));
                    hidden.performClick();
                    assertFalse(RecentTaskSettings.isHidden(activity));
                    assertFalse(excludedFromRecents(activity));
                });
            }
        } finally { RecentTaskSettings.setHidden(context, previous); }
    }

    @Test public void experimentalSourceExplainsAccessibilityAndSilentSettingIsIndependent() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ScreenCaptureSettings settings = new ScreenCaptureSettings(context);
        boolean previousMode = settings.isEnabled();
        boolean previousSilent = settings.isSilentAccountingEnabled();
        try {
            settings.setEnabled(true);
            settings.setSilentAccountingEnabled(false);
            try (ActivityScenario<AssistantManagerActivity> scenario = ActivityScenario.launch(AssistantManagerActivity.class)) {
                scenario.onActivity(activity -> {
                    TextView source = activity.findViewById(R.id.tvAutoTrackSource);
                    assertTrue(source.getText().toString().contains("无需无障碍权限"));
                    SwitchCompat silent = activity.findViewById(R.id.switchSilentAccounting);
                    assertFalse(silent.isChecked());
                    silent.performClick();
                    assertTrue(settings.isSilentAccountingEnabled());
                    assertTrue(settings.isEnabled());
                });
            }
        } finally {
            settings.setSilentAccountingEnabled(previousSilent);
            settings.setEnabled(previousMode);
        }
    }

    private static boolean excludedFromRecents(Activity activity) {
        for (ActivityManager.AppTask task : activity.getSystemService(ActivityManager.class).getAppTasks()) {
            ActivityManager.RecentTaskInfo info = task.getTaskInfo();
            int id = Build.VERSION.SDK_INT >= 29 ? info.taskId : info.id;
            if (id == activity.getTaskId() && info.baseIntent != null) {
                return (info.baseIntent.getFlags() & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) != 0;
            }
        }
        throw new AssertionError("Current task is missing from the app task list");
    }
}
