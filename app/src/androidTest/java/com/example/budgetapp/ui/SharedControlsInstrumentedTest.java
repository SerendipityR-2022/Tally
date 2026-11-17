package com.example.budgetapp.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.graphics.ColorUtils;
import androidx.navigation.fragment.NavHostFragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.budgetapp.MainActivity;
import com.example.budgetapp.R;
import com.example.budgetapp.util.CategoryManager;
import com.google.android.material.button.MaterialButton;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDate;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class SharedControlsInstrumentedTest {
    @Test public void primarySecondaryAndDangerButtonsAreReadableInBothThemes() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
            for (int night : new int[]{Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES}) {
                Configuration configuration = new Configuration(target.getResources().getConfiguration());
                configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
                Context themed = new ContextThemeWrapper(target.createConfigurationContext(configuration), R.style.Theme_BudgetApp);
                LayoutInflater inflater = LayoutInflater.from(themed);
                View date = inflater.inflate(R.layout.dialog_bottom_date_picker, null);
                assertReadable(date.findViewById(R.id.btn_cancel));
                assertReadable(date.findViewById(R.id.btn_confirm));
                assertReadable(inflater.inflate(R.layout.activity_auto_asset, null).findViewById(R.id.btnAddRule));
                assertReadable(inflater.inflate(R.layout.dialog_confirm_delete, null).findViewById(R.id.btn_dialog_confirm));
            }
        });
    }

    @Test public void keywordRuleUsesTheLedgerCategoryTilesForBothTypes() {
        try (ActivityScenario<AiCategoryRuleActivity> ignored = ActivityScenario.launch(AiCategoryRuleActivity.class)) {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            onView(withId(R.id.btn_add_rule)).perform(click());
            assertCategory(CategoryManager.getExpenseCategories(context));
            onView(withId(R.id.rb_income)).perform(click());
            assertCategory(CategoryManager.getIncomeCategories(context));
            onView(withId(R.id.btn_cancel)).perform(click());
        }
    }

    @Test public void datePickerShowsPreviewAndCancelKeepsTheSelectedMonth() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> navigate(activity, R.id.nav_record));
            onView(withId(R.id.tv_month_title)).perform(click());
            onView(withId(R.id.btn_cancel)).check(matches(withText("取消")));
            LocalDate today = LocalDate.now();
            onView(withId(R.id.tv_date_preview)).check(matches(isDisplayed()));
            onView(withId(R.id.np_day)).check(matches(isDisplayed()));
            onView(withId(R.id.btn_cancel)).perform(click());
            onView(withId(R.id.tv_month_title)).check(matches(withText(Integer.toString(today.getYear()))));
        }
    }

    @Test public void assetDropdownAndInputTextStartAtTheSamePosition() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> navigate(activity, R.id.nav_assets));
            onView(withId(R.id.fab_add_asset)).perform(click());
            onView(withId(R.id.spinner_asset_type)).check((view, error) -> {
                if (error != null) throw error;
                TextView selected = (TextView) ((Spinner) view).getSelectedView();
                TextView name = view.getRootView().findViewById(R.id.et_asset_name);
                assertNotNull(selected);
                assertNotNull(name);
                assertEquals(textStart(name), textStart(selected));
            });
        }
    }

    @Test public void detailsFilterAlignsFieldTextAndKeepsResetVisible() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> navigate(activity, R.id.nav_details));
            onView(withId(R.id.btn_filter)).perform(click());
            onView(withId(R.id.sp_filter_type)).check((view, error) -> {
                if (error != null) throw error;
                TextView selected = (TextView) ((Spinner) view).getSelectedView();
                TextView minAmount = view.getRootView().findViewById(R.id.et_min_amount);
                assertNotNull(selected);
                assertNotNull(minAmount);
                assertEquals(textStart(minAmount), textStart(selected));
            });
            onView(withId(R.id.btn_reset)).check(matches(allOf(withText("重置"), isCompletelyDisplayed())));
            onView(withId(R.id.btn_apply)).check(matches(isCompletelyDisplayed()));
        }
    }

    @Test public void scrollContainersRespectShortWindows() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            MaxHeightScrollView scroll = new MaxHeightScrollView(context);
            scroll.setMaxHeight(600);
            View content = new View(context);
            scroll.addView(content, new android.widget.FrameLayout.LayoutParams(320, 1000));
            scroll.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.AT_MOST));
            assertTrue("The form must fit the available window height", scroll.getMeasuredHeight() <= 300);
        });
    }

    private static void assertCategory(List<String> categories) {
        String label = categories.stream().filter(name -> !CategoryManager.CUSTOM_CATEGORY.equals(name))
                .findFirst().orElseThrow(AssertionError::new);
        onView(allOf(withContentDescription(label), isSelected()))
                .check(matches(hasDescendant(allOf(withId(R.id.ledger_category_icon), isDisplayed()))));
        onView(allOf(withId(R.id.tv_category_icon), withText(label))).check(matches(isDisplayed()));
    }

    private static void assertReadable(MaterialButton button) {
        int fill = button.getBackgroundTintList().getColorForState(button.getDrawableState(), Color.TRANSPARENT);
        assertTrue(ColorUtils.calculateContrast(button.getCurrentTextColor(), fill) >= 4.5);
    }

    private static int textStart(TextView field) {
        int[] location = new int[2];
        field.getLocationOnScreen(location);
        return location[0] + field.getCompoundPaddingLeft();
    }

    private static void navigate(MainActivity activity, int destination) {
        NavHostFragment host = (NavHostFragment) activity.getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
        host.getNavController().navigate(destination);
        host.getChildFragmentManager().executePendingTransactions();
    }
}
