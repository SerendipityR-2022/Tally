package com.example.budgetapp.ui;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.budgetapp.R;
import com.example.budgetapp.service.ScreenDebugCapture;
import com.example.budgetapp.util.AutoCategoryManager;
import com.example.budgetapp.util.ScreenCapturePolicy;
import com.example.budgetapp.util.ScreenCaptureSettings;
import com.example.budgetapp.util.ScreenHierarchyCodec;
import com.example.budgetapp.util.ScreenNode;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SettingsPolishInstrumentedTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();

    @Test public void settingsTitlesShareTheCategoryPresetPosition() throws Exception {
        int[] standard = header(CategorySettingsActivity.class, "category");
        assertArrayEquals(standard, header(AssistantManagerActivity.class, "assistant"));
        assertArrayEquals(standard, header(SettingsActivity.class, "settings"));
        assertArrayEquals(standard, header(DefaultPageActivity.class, "navigation"));
        assertArrayEquals(standard, header(AutoCategorySettingsActivity.class, "classification"));
        assertArrayEquals(standard, header(ScreenDebugActivity.class, "debug"));
    }

    private <T extends AppCompatActivity> int[] header(Class<T> page, String name) throws Exception {
        int[] metrics = new int[5];
        try (ActivityScenario<T> scenario = ActivityScenario.launch(page)) {
            instrumentation.waitForIdleSync();
            scenario.onActivity(activity -> {
                LinearLayout toolbar = activity.findViewById(R.id.settings_toolbar);
                TextView title = (TextView) toolbar.getChildAt(1);
                int[] toolbarPosition = new int[2], titlePosition = new int[2];
                toolbar.getLocationOnScreen(toolbarPosition);
                title.getLocationOnScreen(titlePosition);
                assertTrue(toolbar.getHeight() > 0);
                metrics[0] = toolbarPosition[0];
                metrics[1] = toolbarPosition[1];
                metrics[2] = toolbar.getHeight();
                metrics[3] = titlePosition[0];
                metrics[4] = titlePosition[1] + title.getBaseline();
            });
            capture(name);
        }
        return metrics;
    }

    @Test public void modelTimeoutChoiceUpdatesTheSettingAndSurvivesRecreation() {
        SharedPreferences prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        String key = "screen_category_ai_timeout_seconds";
        boolean configured = prefs.contains(key);
        int before = AutoCategoryManager.getAiTimeoutSeconds(context);
        try (ActivityScenario<AutoCategorySettingsActivity> scenario = ActivityScenario.launch(AutoCategorySettingsActivity.class)) {
            scenario.onActivity(activity -> activity.findViewById(R.id.row_category_ai_timeout).performClick());
            onData(equalTo(context.getString(R.string.auto_category_ai_timeout_value, 1))).inRoot(isDialog()).perform(click());
            assertEquals(1, AutoCategoryManager.getAiTimeoutSeconds(context));
            scenario.onActivity(activity -> activity.findViewById(R.id.row_category_ai_timeout).performClick());
            onData(equalTo(context.getString(R.string.auto_category_ai_timeout_value, 10))).inRoot(isDialog()).perform(click());
            assertEquals(10, AutoCategoryManager.getAiTimeoutSeconds(context));
            scenario.recreate();
            scenario.onActivity(activity -> assertTrue(activity.findViewById(R.id.row_category_ai_timeout)
                    .getContentDescription().toString().endsWith(context.getString(R.string.auto_category_ai_timeout_value, 10))));
        } finally {
            SharedPreferences.Editor editor = prefs.edit();
            if (configured) editor.putInt(key, before); else editor.remove(key);
            editor.commit();
        }
    }

    @Test public void debugShowsFullImportedDataAndExecutesRuleInTheTestLedger() throws Exception {
        String pkg = ScreenCapturePolicy.PAYMENT_DEMO;
        List<ScreenNode> fields = new ArrayList<>();
        for (String field : new String[]{"支付成功", "实付 ¥18.60", "测试餐厅"}) {
            fields.add(new ScreenNode(field, null, pkg, "android.widget.TextView", null, Collections.emptyList()));
        }
        ScreenNode receipt = new ScreenNode(null, null, pkg, "android.widget.LinearLayout", null, fields);
        String xml = ScreenHierarchyCodec.encode(receipt);
        ScreenDebugCapture.showImported(pkg, xml, receipt);
        try (ActivityScenario<ScreenDebugActivity> scenario = ActivityScenario.launch(ScreenDebugActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals(xml, ((TextView) activity.findViewById(R.id.debug_preview)).getText().toString());
                assertTrue(activity.findViewById(R.id.debug_save_log).isEnabled());
                assertTrue(activity.findViewById(R.id.debug_copy_log).isEnabled());
                assertTrue(activity.findViewById(R.id.debug_export).isEnabled());
                assertTrue(activity.findViewById(R.id.debug_copy_xml).isEnabled());
                ((TextView) activity.findViewById(R.id.debug_draft)).setText("支付成功");
                assertTrue(activity.findViewById(R.id.debug_verify_draft).performClick());
            });
            if (!new ScreenCaptureSettings(context).isSilentAccountingEnabled()) {
                onView(withText(R.string.screen_debug_confirm_button)).perform(click());
            }
            AtomicReference<String> status = new AtomicReference<>("");
            long deadline = SystemClock.uptimeMillis() + 3500;
            do {
                scenario.onActivity(activity -> status.set(((TextView) activity.findViewById(R.id.debug_rule_result)).getText().toString()));
                if (status.get().contains("已写入测试账本")) break;
                SystemClock.sleep(30);
            } while (SystemClock.uptimeMillis() < deadline);
            assertTrue(status.get(), status.get().contains("18.60") && status.get().contains("已写入测试账本"));
            assertTrue(ScreenDebugCapture.createLog(context, ScreenDebugCapture.latest()).contains("replay.write"));
            capture("debug-captured");
        } finally { ScreenDebugCapture.clear(); }
    }

    private void capture(String name) throws Exception {
        instrumentation.getUiAutomation().waitForIdle(600, 5000);
        Configuration config = context.getResources().getConfiguration();
        String mode = (config.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES ? "dark" : "light";
        String orientation = config.orientation == Configuration.ORIENTATION_LANDSCAPE ? "landscape" : "portrait";
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        File directory = context.getExternalFilesDir(null);
        assertNotNull(directory);
        try (FileOutputStream output = new FileOutputStream(new File(directory,
                "settings-polish-" + name + "-" + orientation + "-" + mode + ".png"))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { screenshot.recycle(); }
    }
}
