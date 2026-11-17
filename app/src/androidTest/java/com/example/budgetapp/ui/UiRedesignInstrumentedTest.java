package com.example.budgetapp.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.navigation.fragment.NavHostFragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.budgetapp.MainActivity;
import com.example.budgetapp.R;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Read-only regression coverage for layout inflation, navigation and settings discovery. */
@RunWith(AndroidJUnit4.class)
public class UiRedesignInstrumentedTest {
    @Test public void primaryAndSettingsLayoutsInflateInBothThemes() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
            int[] layouts = {R.layout.fragment_record, R.layout.fragment_details, R.layout.fragment_budget,
                    R.layout.fragment_assets, R.layout.fragment_stats, R.layout.activity_settings,
                    R.layout.activity_ai_setting, R.layout.activity_webdav_settings,
                    R.layout.activity_assistant_manager, R.layout.activity_auto_category_settings,
                    R.layout.activity_screen_debug,
                    R.layout.activity_category_settings, R.layout.activity_default_page,
                    R.layout.activity_theme_settings, R.layout.activity_currency_settings,
                    R.layout.activity_security_settings, R.layout.activity_photo_backup_settings,
                    R.layout.activity_auto_renewal, R.layout.activity_auto_track_log,
                    R.layout.activity_budget_history, R.layout.activity_year_calendar,
                    R.layout.activity_installment_detail, R.layout.activity_ai_chat,
                    R.layout.dialog_add_transaction, R.layout.dialog_add_asset, R.layout.dialog_add_goal};
            for (int mode : new int[]{Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES}) {
                Configuration configuration = new Configuration(target.getResources().getConfiguration());
                configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | mode;
                Context context = new ContextThemeWrapper(target.createConfigurationContext(configuration), R.style.Theme_BudgetApp);
                for (int layout : layouts) assertNotNull(LayoutInflater.from(context).inflate(layout, null));
                View record = LayoutInflater.from(context).inflate(R.layout.fragment_record, null);
                assertTrue(record.findViewById(R.id.btn_quick_record) instanceof FloatingActionButton);
                assertTrue(record.findViewById(R.id.btn_settings_menu) instanceof PageSettingsButton);
                assertNotEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, record.getImportantForAccessibility());
            }
        });
    }

    @Test public void allFiveDestinationsOpenAndKeepSettingsVisible() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            int[] destinations = {R.id.nav_record, R.id.nav_details, R.id.nav_budget, R.id.nav_assets, R.id.nav_stats};
            for (int destination : destinations) {
                scenario.onActivity(activity -> {
                    NavHostFragment host = (NavHostFragment) activity.getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
                    host.getNavController().navigate(destination);
                    host.getChildFragmentManager().executePendingTransactions();
                    View page = host.getChildFragmentManager().getPrimaryNavigationFragment().getView();
                    assertNotNull(page);
                    View settings = page.findViewById(destination == R.id.nav_record ? R.id.btn_settings_menu : R.id.btn_page_settings);
                    assertNotNull(settings);
                    assertEquals(View.VISIBLE, settings.getVisibility());
                    assertEquals("设置", settings.getContentDescription());
                    if (destination == R.id.nav_details) assertTrue(page.findViewById(R.id.btn_quick_record_details) instanceof FloatingActionButton);
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            }
        }
    }

    @Test public void settingsSearchMatchesTitleAliasesAndClearsEmptyState() {
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(SettingsActivity.class)) {
            scenario.onActivity(activity -> {
                EditText search = activity.findViewById(R.id.et_settings_search);
                search.setText("WeBdAv");
                assertEquals(View.VISIBLE, activity.findViewById(R.id.btn_webdav_backup).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.settings_section_0).getVisibility());
                search.setText("优先级");
                assertEquals(View.VISIBLE, activity.findViewById(R.id.btn_auto_category_setting).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.btn_category_setting).getVisibility());
                search.setText("no-such-setting");
                assertEquals(View.VISIBLE, activity.findViewById(R.id.tv_settings_empty).getVisibility());
                activity.findViewById(R.id.btn_clear_settings_search).performClick();
                assertEquals("", search.getText().toString());
                assertEquals(View.GONE, activity.findViewById(R.id.tv_settings_empty).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.settings_section_0).getVisibility());
                search.setText("启动页面");
                View navigation = activity.findViewById(R.id.btn_display_options);
                assertEquals(View.VISIBLE, navigation.getVisibility());
                assertTrue(navigation.getContentDescription().toString().contains("页面与导航，"));
                search.setText("自动记账日志");
                assertEquals(View.VISIBLE, activity.findViewById(R.id.btn_assistant_setting).getVisibility());
                search.setText("模型识别超时");
                assertEquals(View.VISIBLE, activity.findViewById(R.id.btn_auto_category_setting).getVisibility());
            });
        }
    }

    @Test public void settingsQuerySurvivesRecreation() {
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(SettingsActivity.class)) {
            scenario.onActivity(activity -> ((EditText) activity.findViewById(R.id.et_settings_search)).setText("WebDAV"));
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals("WebDAV", ((EditText) activity.findViewById(R.id.et_settings_search)).getText().toString());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.btn_webdav_backup).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.settings_section_0).getVisibility());
            });
        }
    }
}
