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
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withParent;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AutoCategorySettingsInstrumentedTest {
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
                TextView count = activity.findViewById(R.id.tv_category_learning_count);
                assertEquals(activity.getString(R.string.auto_category_learning_count, 0, AutoCategoryManager.MAX_LEARNED), count.getText().toString());
                String category = AutoCategoryManager.getCatalog(context, 0).get(0).name;
                Transaction bill = new Transaction(1000, 0, category, 1.0);
                AutoCategoryManager.attachScreenSource(bill, new AutoCategoryClassifier.Input("test.learning", "示例店铺", 0, ""));
                AutoCategoryManager.rememberConfirmation(context, bill,
                        new AutoCategoryClassifier.Result(category, "", AutoCategoryClassifier.Source.LOCAL));
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> assertEquals(activity.getString(R.string.auto_category_learning_count, 1,
                    AutoCategoryManager.MAX_LEARNED), ((TextView) activity.findViewById(R.id.tv_category_learning_count)).getText().toString()));
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
                assertTrue(((TextView) activity.findViewById(R.id.tv_category_learning_count)).getText().length() > 0);
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
