package com.example.budgetapp.ui;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.espresso.action.GeneralLocation;
import androidx.test.espresso.action.GeneralSwipeAction;
import androidx.test.espresso.action.Press;
import androidx.test.espresso.action.Swipe;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.appcompat.widget.SwitchCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.example.budgetapp.R;
import com.example.budgetapp.database.Transaction;
import com.example.budgetapp.util.AutoCategoryClassifier;
import com.example.budgetapp.util.AutoCategoryManager;
import com.example.budgetapp.util.AutoCategoryPriority;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withParent;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AutoCategorySettingsInstrumentedTest {
    @Test public void learnedRecordsCanBeSearchedEditedAndDeletedWhileLearningIsOff() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        String key = "screen_category_learned_rules";
        String original = prefs.getString(key, null);
        boolean learning = AutoCategoryManager.isLearningEnabled(context);
        String[] packages = {"com.tencent.mm", "com.eg.android.AlipayGphone"};
        try {
            AutoCategoryManager.clearLearned(context);
            AutoCategoryManager.setLearningEnabled(context, true);
            for (String pkg : packages) {
                Transaction bill = new Transaction(1000, 0, "餐饮", 1);
                AutoCategoryManager.attachScreenSource(bill, new AutoCategoryClassifier.Input(pkg, "示例咖啡", 0, ""));
                AutoCategoryManager.rememberConfirmation(context, bill,
                        new AutoCategoryClassifier.Result("餐饮", "", AutoCategoryClassifier.Source.LOCAL));
            }
            AutoCategoryManager.setLearningEnabled(context, false);
            try (ActivityScenario<AutoCategorySettingsActivity> settings = ActivityScenario.launch(AutoCategorySettingsActivity.class)) {
                settings.onActivity(activity -> activity.findViewById(R.id.row_category_learning_records).performClick());
                onView(withId(R.id.learning_search)).perform(replaceText("支付宝"));
                onView(withId(R.id.learning_edit)).perform(click());
                onView(allOf(withId(R.id.tv_category_icon), withText("购物"))).inRoot(isDialog()).perform(click());
                onView(withText(R.string.record_save_changes)).inRoot(isDialog()).perform(click());
                assertEquals("购物", AutoCategoryManager.getLearned(context).get(0).category);
                assertEquals("餐饮", AutoCategoryManager.getLearned(context).get(1).category);
                onView(withId(R.id.learning_delete)).perform(click());
                onView(withText(R.string.auto_category_learning_delete_button)).inRoot(isDialog()).perform(click());
                assertEquals(1, AutoCategoryManager.getLearned(context).size());
                assertEquals(packages[0], AutoCategoryManager.getLearned(context).get(0).packageName);
                onView(withId(R.id.btn_category_clear_learning)).perform(click());
                onView(allOf(withId(android.R.id.button1), withText(R.string.auto_category_learning_clear_button)))
                        .inRoot(isDialog()).perform(click());
                assertTrue(AutoCategoryManager.getLearned(context).isEmpty());
                pressBack();
            }
        } finally {
            if (original == null) prefs.edit().remove(key).commit();
            else prefs.edit().putString(key, original).commit();
            AutoCategoryManager.setLearningEnabled(context, learning);
        }
    }

    @Test public void learningCountRefreshesWhenABillIsConfirmedWhileSettingsRemainVisible() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        String key = "screen_category_learned_rules";
        String original = prefs.getString(key, null);
        boolean enabled = AutoCategoryManager.isLearningEnabled(context);
        AutoCategoryManager.clearLearned(context);
        AutoCategoryManager.setLearningEnabled(context, true);
        try (ActivityScenario<AutoCategorySettingsActivity> scenario = ActivityScenario.launch(AutoCategorySettingsActivity.class)) {
            scenario.onActivity(activity -> {
                View entry = activity.findViewById(R.id.row_category_learning_records);
                assertTrue(entry.getContentDescription().toString().endsWith(
                        activity.getString(R.string.auto_category_learning_short_count, 0)));
                String category = AutoCategoryManager.getCatalog(context, 0).get(0).name;
                Transaction bill = new Transaction(1000, 0, category, 1.0);
                AutoCategoryManager.attachScreenSource(bill, new AutoCategoryClassifier.Input("test.learning", "示例店铺", 0, ""));
                AutoCategoryManager.rememberConfirmation(context, bill,
                        new AutoCategoryClassifier.Result(category, "", AutoCategoryClassifier.Source.LOCAL));
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> assertTrue(activity.findViewById(R.id.row_category_learning_records)
                    .getContentDescription().toString().endsWith(activity.getString(R.string.auto_category_learning_short_count, 1))));
        } finally {
            if (original == null) prefs.edit().remove(key).commit();
            else prefs.edit().putString(key, original).commit();
            AutoCategoryManager.setLearningEnabled(context, enabled);
        }
    }

    @Test public void settingsInflateAndShowPersistedState() throws Exception {
        try (ActivityScenario<AutoCategorySettingsActivity> scenario = ActivityScenario.launch(AutoCategorySettingsActivity.class)) {
            scenario.onActivity(activity -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            InstrumentationRegistry.getInstrumentation().getUiAutomation().waitForIdle(500, 5000);
            scenario.onActivity(activity -> {
                SwitchCompat ai = activity.findViewById(R.id.switch_category_ai);
                assertEquals(AutoCategoryManager.isAiEnabled(activity), ai.isChecked());
                assertNotNull(activity.findViewById(R.id.btn_category_rules));
                RecyclerView priorities = activity.findViewById(R.id.layout_category_priority);
                assertEquals(6, priorities.getAdapter().getItemCount());
                assertNotNull(activity.findViewById(R.id.btn_category_priority_reset));
                assertEquals(View.INVISIBLE, priorities.findViewHolderForAdapterPosition(0).itemView
                        .findViewById(R.id.btn_strategy_drag).getVisibility());
                assertEquals(View.INVISIBLE, priorities.findViewHolderForAdapterPosition(1).itemView
                        .findViewById(R.id.btn_strategy_drag).getVisibility());
                assertEquals(View.INVISIBLE, priorities.findViewHolderForAdapterPosition(5).itemView
                        .findViewById(R.id.btn_strategy_drag).getVisibility());
                assertNotNull(activity.findViewById(R.id.row_category_learning_records));
                assertNull(activity.findViewById(R.id.btn_category_clear_learning));
                assertTrue(((TextView) activity.findViewById(R.id.tv_category_ai_status)).getText().length() > 0);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            saveScreenshot("tally-classification-settings.png");
            scenario.onActivity(activity -> {
                View ai = activity.findViewById(R.id.switch_category_ai);
                ai.getParent().requestChildFocus(ai, ai);
                ai.requestRectangleOnScreen(new android.graphics.Rect(0, 0, ai.getWidth(), 700), true);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            saveScreenshot("tally-classification-ai.png");
        }
    }

    @Test public void draggingFromTheLeftHandleSavesTheNewPriority() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AutoCategoryPriority before = AutoCategoryManager.getPriority(context);
        try (ActivityScenario<AutoCategorySettingsActivity> scenario = ActivityScenario.launch(AutoCategorySettingsActivity.class)) {
            scenario.onActivity(activity -> {
                View list = activity.findViewById(R.id.layout_category_priority);
                list.requestRectangleOnScreen(new Rect(0, 0, list.getWidth(), list.getHeight()), true);
            });
            saveScreenshot("tally-priority-order.png");
            onView(allOf(withId(R.id.btn_strategy_drag), withParent(hasDescendant(
                    allOf(withId(R.id.tv_priority_rank), withText("3"))))))
                    .perform(new GeneralSwipeAction(Swipe.SLOW, GeneralLocation.CENTER, handle -> {
                        RecyclerView list = (RecyclerView) handle.getParent().getParent();
                        View target = list.findViewHolderForAdapterPosition(3).itemView;
                        int[] position = new int[2];
                        target.getLocationOnScreen(position);
                        return new float[]{GeneralLocation.CENTER.calculateCoordinates(handle)[0],
                                position[1] + target.getHeight() * 0.75f};
                    }, Press.FINGER));
            assertEquals(before.move(2, 3).getSources(), AutoCategoryManager.getPriority(context).getSources());
            scenario.recreate();
            onView(withText(R.string.auto_category_rules_description)).check((caption, error) -> {
                if (error != null) throw error;
                View button = caption.getRootView().findViewById(R.id.btn_category_rules);
                float density = caption.getResources().getDisplayMetrics().density;
                assertTrue(caption.getTop() - button.getBottom() >= Math.round(density * 8));
                caption.requestRectangleOnScreen(new Rect(0, 0, caption.getWidth(),
                        caption.getHeight() + Math.round(density * 100)), true);
            });
            saveScreenshot("tally-priority-rule-spacing.png");
        } finally {
            if (!before.getSources().equals(AutoCategoryManager.getPriority(context).getSources())) {
                AutoCategoryManager.setPriority(context, before);
            }
        }
    }

    private void saveScreenshot(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation().waitForIdle(500, 5000);
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        File directory = InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null);
        assertNotNull(directory);
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { screenshot.recycle(); }
    }
}
