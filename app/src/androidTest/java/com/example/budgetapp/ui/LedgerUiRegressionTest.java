package com.example.budgetapp.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.budgetapp.MainActivity;
import com.example.budgetapp.R;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LedgerUiRegressionTest {
    private static final String[] PREF_KEYS = {"default_page", "is_budget_enabled", "is_detailed_budget_enabled",
            "enable_details_module", "key_enable_assets_module", "theme_mode"};
    private final Map<String, Object> original = new HashMap<>();
    private SharedPreferences prefs;

    @Before public void savePreferences() {
        prefs = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        Map<String, ?> values = prefs.getAll();
        for (String key : PREF_KEYS) original.put(key, values.get(key));
        prefs.edit().putBoolean("is_budget_enabled", true).putBoolean("is_detailed_budget_enabled", false)
                .putBoolean("enable_details_module", true).putBoolean("key_enable_assets_module", true).commit();
    }

    @After public void restorePreferences() {
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : PREF_KEYS) {
            Object value = original.get(key);
            if (value == null) edit.remove(key);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof String) edit.putString(key, (String) value);
        }
        edit.commit();
    }

    @Test public void everyStartupPageCanReturnToRecordThroughBottomNavigation() {
        int[] pages = {R.id.nav_record, R.id.nav_details, R.id.nav_budget, R.id.nav_assets, R.id.nav_stats};
        for (int i = 1; i < pages.length; i++) {
            int startupPage = pages[i];
            prefs.edit().putInt("default_page", i).commit();
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                scenario.onActivity(a -> assertDestination(a, startupPage));
                select(scenario, R.id.nav_record);
                scenario.onActivity(a -> assertTrue(active(a) instanceof RecordFragment));
                select(scenario, startupPage);
                select(scenario, R.id.nav_record);
                scenario.recreate();
                scenario.onActivity(a -> assertDestination(a, R.id.nav_record));
            }
        }
    }

    @Test public void disablingBudgetHidesBothEntrancesAndFallsBackFromBudgetOnResumeAndStartup() {
        prefs.edit().putInt("default_page", 2).commit();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a -> assertDestination(a, R.id.nav_budget));
            select(scenario, R.id.nav_details);
            scenario.onActivity(a -> assertEquals(View.VISIBLE,
                    active(a).requireView().findViewById(R.id.ledger_details_budget).getVisibility()));
            select(scenario, R.id.nav_budget);
            scenario.moveToState(Lifecycle.State.STARTED);
            prefs.edit().putBoolean("is_budget_enabled", false).commit();
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(a -> {
                assertDestination(a, R.id.nav_record);
                assertFalse(bottom(a).getMenu().findItem(R.id.nav_budget).isVisible());
            });
            select(scenario, R.id.nav_details);
            scenario.onActivity(a -> assertEquals(View.GONE,
                    active(a).requireView().findViewById(R.id.ledger_details_budget).getVisibility()));
        }
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a -> assertDestination(a, R.id.nav_record));
        }
    }

    @Test public void summaryCardsAndSectionHeadingsAlignInLightAndDarkThemes() throws Exception {
        int[] pages = {R.id.nav_assets, R.id.nav_details, R.id.nav_budget};
        int[] cards = {R.id.card_assets_summary, R.id.ledger_details_summary, R.id.card_month_summary};
        int[] titles = {R.id.tv_list_title, R.id.tv_details_section_title, R.id.tv_goals_title};
        int[] labels = {R.id.tv_total_assets_label, R.id.ledger_details_focus_label, R.id.tv_total_surplus_label};
        int[] amounts = {R.id.tv_total_assets, R.id.ledger_details_focus_amount, R.id.tv_total_surplus};
        String[] names = {"assets", "details", "budget"};
        for (int theme : new int[]{AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES}) {
            prefs.edit().putInt("theme_mode", theme).putInt("default_page", 0).commit();
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                AtomicReference<Rect> firstCard = new AtomicReference<>();
                AtomicReference<int[]> firstTitle = new AtomicReference<>();
                AtomicReference<Rect[]> firstCardContent = new AtomicReference<>();
                for (int i = 0; i < pages.length; i++) {
                    int index = i;
                    select(scenario, pages[index]);
                    scenario.onActivity(a -> {
                        View page = active(a).requireView();
                        LedgerSummaryCard card = page.findViewById(cards[index]);
                        TextView heading = page.findViewById(titles[index]);
                        Rect cardBounds = bounds(card);
                        int[] titleGeometry = {bounds(heading).top, heading.getHeight(), Math.round(heading.getTextSize())};
                        assertTrue(cardBounds.height() > 0);
                        assertEquals(a.getResources().getDimension(R.dimen.ledger_summary_corner), card.getRadius(), 0.01f);
                        assertTrue(card.getClipToOutline());
                        assertRoundedBackground(card);
                        TextView label = card.findViewById(labels[index]);
                        TextView amount = card.findViewById(amounts[index]);
                        assertTextCentered(cardBounds, label);
                        assertTextCentered(cardBounds, amount);
                        Rect[] content = {bounds(label), bounds(amount), bounds(card.findViewById(R.id.ledger_summary_divider))};
                        assertEquals(a.getColor(R.color.white), card.getCardBackgroundColor().getDefaultColor());
                        assertEquals(a.getColor(R.color.text_primary), heading.getCurrentTextColor());
                        if (index == 0) {
                            firstCard.set(cardBounds);
                            firstCardContent.set(content);
                            firstTitle.set(titleGeometry);
                        } else {
                            assertEquals(firstCard.get(), cardBounds);
                            assertArrayEquals(firstCardContent.get(), content);
                            assertArrayEquals(firstTitle.get(), titleGeometry);
                        }
                    });
                    capture(names[index] + (theme == AppCompatDelegate.MODE_NIGHT_YES ? "-dark" : "-light"));
                }
            }
        }
    }

    @Test public void categoryRowsUseAvailableHeightAndSaveRemainsVisible() throws Exception {
        prefs.edit().putInt("default_page", 0).putInt("theme_mode", AppCompatDelegate.MODE_NIGHT_YES).commit();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<AlertDialog> dialog = new AtomicReference<>();
            AtomicReference<LedgerEntryLayout> form = new AtomicReference<>();
            AtomicInteger tallHeight = new AtomicInteger();
            AtomicInteger tallGridHeight = new AtomicInteger();
            scenario.onActivity(a -> {
                LedgerEntryLayout view = (LedgerEntryLayout) LayoutInflater.from(a)
                        .inflate(R.layout.dialog_add_transaction, null);
                AlertDialog window = new AlertDialog.Builder(a).setView(view).create();
                LedgerEntrySheet sheet = new LedgerEntrySheet(window, view);
                LedgerCategoryGrid grid = view.findViewById(R.id.rv_category);
                grid.setLayoutManager(new GridLayoutManager(a, LedgerCategoryGrid.COLUMNS));
                List<String> names = new ArrayList<>(Arrays.asList("餐饮", "购物", "住房", "交通", "通讯"));
                for (int i = names.size(); i < 45; i++) names.add("分类 " + (i + 1));
                CategoryAdapter categories = new CategoryAdapter(a, names, "餐饮", true, name -> {});
                categories.enableAdding();
                grid.setAdapter(categories);
                sheet.bindSave(true, keepRecording -> {});
                sheet.updateDate(java.util.Calendar.getInstance());
                sheet.show();
                dialog.set(window);
                form.set(view);
            });
            idle();
            try {
                scenario.onActivity(a -> {
                    View view = form.get();
                    LedgerCategoryGrid grid = view.findViewById(R.id.rv_category);
                    GridLayoutManager layout = (GridLayoutManager) grid.getLayoutManager();
                    assertEquals(0, layout.findFirstCompletelyVisibleItemPosition());
                    assertEquals(0, grid.getHeight() % grid.rowHeight());
                    assertEquals(grid.getHeight() / grid.rowHeight() * LedgerCategoryGrid.COLUMNS - 1,
                            layout.findLastCompletelyVisibleItemPosition());
                    assertTrue(grid.canScrollVertically(1));
                    tallHeight.set(view.getHeight());
                    tallGridHeight.set(grid.getHeight());
                    assertFullyVisible(view.findViewById(R.id.et_remark));
                    assertTrue(((EditText) view.findViewById(R.id.et_remark)).getText().toString().isEmpty());
                    assertFullyVisible(view.findViewById(R.id.btn_save));
                    assertFullyVisible(view.findViewById(R.id.entry_save_again));
                });
                capture("entry-full-dark");
                scenario.onActivity(a -> {
                    LedgerCategoryGrid grid = form.get().findViewById(R.id.rv_category);
                    form.get().setMaxHeight(tallHeight.get() - tallGridHeight.get() + grid.rowHeight());
                });
                idle();
                scenario.onActivity(a -> {
                    LedgerCategoryGrid grid = form.get().findViewById(R.id.rv_category);
                    assertEquals(grid.rowHeight(), grid.getHeight());
                    assertFullyVisible(form.get().findViewById(R.id.et_remark));
                    assertFullyVisible(form.get().findViewById(R.id.btn_save));
                    grid.scrollToPosition(grid.getAdapter().getItemCount() - 1);
                });
                idle();
                scenario.onActivity(a -> {
                    LedgerCategoryGrid grid = form.get().findViewById(R.id.rv_category);
                    assertNotNull(grid.findViewHolderForAdapterPosition(grid.getAdapter().getItemCount() - 1));
                    assertFullyVisible(form.get().findViewById(R.id.btn_save));
                    form.get().setMaxHeight(tallHeight.get());
                    ((CategoryAdapter) grid.getAdapter()).setCategories(Arrays.asList("餐饮", "住房"), "餐饮", true);
                });
                idle();
                scenario.onActivity(a -> {
                    LedgerEntryLayout view = form.get();
                    LedgerCategoryGrid grid = view.findViewById(R.id.rv_category);
                    assertEquals(grid.rowHeight(), grid.getHeight());
                    assertEquals(tallHeight.get() - tallGridHeight.get() + grid.rowHeight(), view.getHeight());
                    assertFullyVisible(view.findViewById(R.id.btn_save));
                });
                capture("entry-few-dark");
            } finally {
                scenario.onActivity(a -> dialog.get().dismiss());
            }
        }
    }

    private static void select(ActivityScenario<MainActivity> scenario, int destination) {
        scenario.onActivity(a -> bottom(a).setSelectedItemId(destination));
        long deadline = SystemClock.uptimeMillis() + 2500;
        AtomicReference<Lifecycle.State> state = new AtomicReference<>(Lifecycle.State.INITIALIZED);
        do {
            idle();
            scenario.onActivity(a -> state.set(active(a).getLifecycle().getCurrentState()));
            if (state.get() == Lifecycle.State.RESUMED) break;
            SystemClock.sleep(30);
        } while (SystemClock.uptimeMillis() < deadline);
        assertEquals(Lifecycle.State.RESUMED, state.get());
        scenario.onActivity(a -> assertDestination(a, destination));
    }

    private static void assertDestination(MainActivity activity, int destination) {
        NavHostFragment host = host(activity);
        assertEquals(destination, host.getNavController().getCurrentDestination().getId());
        assertEquals(destination, bottom(activity).getSelectedItemId());
    }

    private static NavHostFragment host(MainActivity activity) {
        return (NavHostFragment) activity.getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
    }

    private static Fragment active(MainActivity activity) {
        return host(activity).getChildFragmentManager().getPrimaryNavigationFragment();
    }

    private static BottomNavigationView bottom(MainActivity activity) { return activity.findViewById(R.id.bottom_nav); }

    private static void idle() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }

    private static Rect bounds(View view) {
        int[] position = new int[2];
        view.getLocationOnScreen(position);
        return new Rect(position[0], position[1], position[0] + view.getWidth(), position[1] + view.getHeight());
    }

    private static void assertRoundedBackground(LedgerSummaryCard card) {
        Bitmap surface = Bitmap.createBitmap(card.getWidth(), card.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            card.draw(new Canvas(surface));
            assertEquals(Color.TRANSPARENT, surface.getPixel(0, 0));
            assertEquals(Color.TRANSPARENT, surface.getPixel(card.getWidth() - 1, 0));
            assertEquals(card.getCardBackgroundColor().getDefaultColor(), surface.getPixel(card.getWidth() / 2, 1));
        } finally {
            surface.recycle();
        }
    }

    private static void assertTextCentered(Rect card, TextView text) {
        android.text.Layout layout = text.getLayout();
        float center = bounds(text).left + text.getCompoundPaddingLeft()
                + (layout.getLineLeft(0) + layout.getLineRight(0)) / 2;
        assertEquals(card.exactCenterX(), center, 2f);
    }

    private static void assertFullyVisible(View view) {
        Rect visible = new Rect();
        assertTrue(view.getGlobalVisibleRect(visible));
        assertEquals(view.getHeight(), visible.height());
    }

    private static void capture(String name) throws Exception {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("ledgerFixScreens"))) return;
        android.app.Instrumentation test = InstrumentationRegistry.getInstrumentation();
        // Wait for the outgoing page to finish drawing before capturing the selected page.
        SystemClock.sleep(350);
        test.getUiAutomation().waitForIdle(300, 4000);
        Bitmap bitmap = test.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File output = new File(test.getTargetContext().getCacheDir(), "ledger-fix-" + name + ".png");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally {
            bitmap.recycle();
        }
    }
}
