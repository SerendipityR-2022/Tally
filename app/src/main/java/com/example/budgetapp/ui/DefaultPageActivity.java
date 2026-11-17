package com.example.budgetapp.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.WindowCompat;

import com.example.budgetapp.BackupManager;
import com.example.budgetapp.R;
import com.example.budgetapp.util.AssistantConfig;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class DefaultPageActivity extends AppCompatActivity {
    public static final String PREF_NAME = "app_prefs";
    public static final String KEY_DEFAULT_PAGE = "default_page";
    public static final String KEY_DETAILS_QUICK_BUTTON = "details_quick_button";
    public static final String KEY_BILL_CARD = "bill_card";
    public static final int PAGE_RECORD = 0;
    public static final int PAGE_DETAILS = 1;
    public static final int PAGE_BUDGET = 2;
    public static final int PAGE_ASSETS = 3;
    public static final int PAGE_STATS = 4;
    private static final String KEY_CALENDAR_DISPLAY = "default_record_mode";

    private SharedPreferences prefs;
    private AssistantConfig config;
    private SettingsRow startPage;
    private SettingsRow calendarDisplay;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_default_page);
        SettingsPage.applyInsets(findViewById(R.id.root_layout));
        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        config = new AssistantConfig(this);

        startPage = findViewById(R.id.btn_default_page);
        startPage.setOnClickListener(v -> chooseStartPage());
        startPage.setValue(startPageLabel(this));
        calendarDisplay = findViewById(R.id.btn_default_record_display);
        calendarDisplay.setOnClickListener(v -> chooseCalendarDisplay());
        updateCalendarDisplay();

        SwitchCompat details = findViewById(R.id.switchDetails);
        details.setChecked(config.isDetailsEnabled());
        details.setOnCheckedChangeListener((view, enabled) -> {
            config.setDetailsEnabled(enabled);
            startPage.setValue(startPageLabel(this));
        });
        SwitchCompat assets = findViewById(R.id.switchAssets);
        assets.setChecked(config.isAssetsEnabled());
        assets.setOnCheckedChangeListener((view, enabled) -> {
            config.setAssetsEnabled(enabled);
            startPage.setValue(startPageLabel(this));
        });
        bindSwitch(R.id.switchDetailsQuickButton, KEY_DETAILS_QUICK_BUTTON, true);
        bindSwitch(R.id.switchBillCard, KEY_BILL_CARD, false);
        bindSwitch(R.id.switch_minimalist, "minimalist_mode", false);
    }

    private void bindSwitch(int id, String key, boolean defaultValue) {
        SwitchCompat toggle = findViewById(id);
        toggle.setChecked(prefs.getBoolean(key, defaultValue));
        toggle.setOnCheckedChangeListener((view, checked) -> {
            prefs.edit().putBoolean(key, checked).apply();
            BackupManager.triggerAutoUploadIfEnabled(this);
        });
    }

    private static boolean pageAvailable(int page, AssistantConfig config) {
        switch (page) {
            case PAGE_RECORD: case PAGE_STATS: return true;
            case PAGE_DETAILS: return config.isDetailsEnabled();
            case PAGE_BUDGET: return config.isBudgetEnabled();
            case PAGE_ASSETS: return config.isAssetsEnabled();
            default: return false;
        }
    }

    static String startPageLabel(Context context) {
        int page = context.getSharedPreferences(PREF_NAME, MODE_PRIVATE).getInt(KEY_DEFAULT_PAGE, PAGE_RECORD);
        return context.getResources().getStringArray(R.array.settings_start_page_choices)
                [pageAvailable(page, new AssistantConfig(context)) ? page : PAGE_RECORD];
    }

    private void chooseStartPage() {
        String[] pages = getResources().getStringArray(R.array.settings_start_page_choices);
        List<Integer> available = new ArrayList<>();
        List<String> choices = new ArrayList<>();
        for (int i = 0; i < pages.length; i++) {
            if (!pageAvailable(i, config)) continue;
            available.add(i);
            choices.add(pages[i]);
        }
        int selected = available.indexOf(prefs.getInt(KEY_DEFAULT_PAGE, PAGE_RECORD));
        new MaterialAlertDialogBuilder(this).setTitle(R.string.settings_start_page)
                .setSingleChoiceItems(choices.toArray(new String[0]), Math.max(0, selected), (dialog, which) -> {
                    setInt(KEY_DEFAULT_PAGE, available.get(which));
                    startPage.setValue(choices.get(which));
                    dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void chooseCalendarDisplay() {
        String[] choices = getResources().getStringArray(R.array.settings_calendar_display_choices);
        new MaterialAlertDialogBuilder(this).setTitle(R.string.settings_calendar_display)
                .setSingleChoiceItems(choices, calendarIndex(choices.length), (dialog, which) -> {
                    setInt(KEY_CALENDAR_DISPLAY, which);
                    updateCalendarDisplay();
                    dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private int calendarIndex(int length) {
        return Math.max(0, Math.min(length - 1, prefs.getInt(KEY_CALENDAR_DISPLAY, 0)));
    }

    private void updateCalendarDisplay() {
        String[] choices = getResources().getStringArray(R.array.settings_calendar_display_choices);
        calendarDisplay.setValue(choices[calendarIndex(choices.length)]);
    }

    private void setInt(String key, int value) {
        prefs.edit().putInt(key, value).apply();
        BackupManager.triggerAutoUploadIfEnabled(this);
    }
}
