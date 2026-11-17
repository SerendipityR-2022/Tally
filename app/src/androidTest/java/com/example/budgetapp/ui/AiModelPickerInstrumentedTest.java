package com.example.budgetapp.ui;

import android.graphics.Bitmap;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.budgetapp.R;
import com.example.budgetapp.ai.AiModelCatalog;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static org.junit.Assert.*;

/** Runs the real picker binding/layout with a fake transport, never the user's AI credentials. */
@RunWith(AndroidJUnit4.class)
public class AiModelPickerInstrumentedTest {
    private AiModelPickerBinding binding;
    private View pickerRoot;
    private EditText url, key, model;
    private static final String BODY = "{\"data\":[{\"id\":\"alpha-text\"},{\"id\":\"beta-vision\"},{\"id\":\"gamma-audio\"}]}";

    private void install(AutoCategorySettingsActivity activity, AiModelCatalog catalog) {
        activity.setContentView(R.layout.activity_ai_setting);
        url = activity.findViewById(R.id.et_base_url);
        key = activity.findViewById(R.id.et_api_key);
        model = activity.findViewById(R.id.et_text_model);
        pickerRoot = activity.findViewById(R.id.text_model_picker);
        model.setText("manual-existing-model");
        binding = new AiModelPickerBinding(activity, pickerRoot, model,
                () -> new AiModelPickerBinding.Connection(url.getText().toString(), key.getText().toString()), catalog);
        binding.observe(url, key);
    }

    @Test public void automaticallyLoadsThenSearchesAndSelectsWithoutSavingOrEnablingAi() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (ActivityScenario<AutoCategorySettingsActivity> scenario = ActivityScenario.launch(AutoCategorySettingsActivity.class)) {
            scenario.onActivity(activity -> {
                install(activity, new AiModelCatalog((uri, apiKey) -> {
                    assertEquals("https://fixture.invalid/proxy/v1/models", uri.toString());
                    assertEquals("fixture-key", apiKey);
                    calls.incrementAndGet(); return new AiModelCatalog.Response(200, BODY);
                }));
                url.setText("https://fixture.invalid/proxy/v1/"); key.setText("fixture-key");
            });
            awaitStatus("3");
            assertEquals(1, calls.get());
            scenario.onActivity(activity -> {
                assertEquals("manual-existing-model", model.getText().toString());
                assertFalse(((androidx.appcompat.widget.SwitchCompat) activity.findViewById(R.id.switch_ai_enable)).isChecked());
                assertNotNull(activity.findViewById(R.id.vision_model_picker));
                assertNotNull(activity.findViewById(R.id.audio_model_picker));
                pickerRoot.findViewById(R.id.btn_choose_remote_model).performClick();
            });
            saveScreenshot("tally-ai-model-picker.png");
            onView(withId(R.id.et_model_search)).inRoot(isDialog()).perform(replaceText("VISION"), closeSoftKeyboard());
            onView(withText("beta-vision")).inRoot(isDialog()).perform(click());
            scenario.onActivity(activity -> {
                assertEquals("beta-vision", model.getText().toString());
                model.setText("still-manually-editable");
                assertEquals("still-manually-editable", model.getText().toString());
                model.setText("beta-vision");
                model.requestRectangleOnScreen(new android.graphics.Rect(0, 0, model.getWidth(), 550), true);
            });
            saveScreenshot("tally-ai-model-list.png");
            scenario.onActivity(activity -> binding.close());
        } finally { if (binding != null) InstrumentationRegistry.getInstrumentation().runOnMainSync(binding::close); }
    }

    @Test public void failurePreservesManualEntryAndExplicitRefreshRetries() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (ActivityScenario<AutoCategorySettingsActivity> scenario = ActivityScenario.launch(AutoCategorySettingsActivity.class)) {
            scenario.onActivity(activity -> {
                install(activity, new AiModelCatalog((uri, apiKey) -> calls.incrementAndGet() == 1
                        ? new AiModelCatalog.Response(404, "do-not-show-secret") : new AiModelCatalog.Response(200, BODY)));
                url.setText("fixture.invalid"); key.setText("fixture-key");
                pickerRoot.findViewById(R.id.btn_refresh_model_list).performClick();
            });
            awaitStatus("404/405");
            scenario.onActivity(activity -> {
                assertEquals("manual-existing-model", model.getText().toString());
                pickerRoot.findViewById(R.id.btn_refresh_model_list).performClick();
            });
            awaitStatus("3");
            assertEquals(2, calls.get());
            scenario.onActivity(activity -> binding.close());
        } finally { if (binding != null) InstrumentationRegistry.getInstrumentation().runOnMainSync(binding::close); }
    }

    @Test public void credentialEditsDiscardOldResponseAndDebounceTyping() throws Exception {
        CountDownLatch oldStarted = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (ActivityScenario<AutoCategorySettingsActivity> scenario = ActivityScenario.launch(AutoCategorySettingsActivity.class)) {
            scenario.onActivity(activity -> {
                install(activity, new AiModelCatalog((uri, apiKey) -> {
                    calls.incrementAndGet();
                    if (apiKey.equals("old-key")) {
                        oldStarted.countDown();
                        boolean waiting = true;
                        while (waiting) { try { release.await(); waiting = false; } catch (InterruptedException ignored) { } }
                        return new AiModelCatalog.Response(200, "{\"data\":[{\"id\":\"stale-model\"}]}");
                    }
                    return new AiModelCatalog.Response(200, BODY);
                }));
                url.setText("fixture.invalid"); key.setText("old-key");
                pickerRoot.findViewById(R.id.btn_refresh_model_list).performClick();
            });
            assertTrue(oldStarted.await(3, TimeUnit.SECONDS));
            scenario.onActivity(activity -> { key.setText("new"); key.setText("new-key"); });
            awaitStatus("3");
            release.countDown(); Thread.sleep(150);
            scenario.onActivity(activity -> {
                assertTrue(((TextView) pickerRoot.findViewById(R.id.tv_model_list_status)).getText().toString().contains("3"));
                assertEquals("manual-existing-model", model.getText().toString());
                binding.close();
            });
            assertEquals(2, calls.get());
        } finally {
            release.countDown();
            if (binding != null) InstrumentationRegistry.getInstrumentation().runOnMainSync(binding::close);
        }
    }

    @Test public void profileEditorContainsSamePickerAndManualField() {
        try (ActivityScenario<AutoCategorySettingsActivity> scenario = ActivityScenario.launch(AutoCategorySettingsActivity.class)) {
            scenario.onActivity(activity -> {
                View editor = activity.getLayoutInflater().inflate(R.layout.dialog_edit_ai_model_profile, null);
                assertNotNull(editor.findViewById(R.id.et_model_name));
                assertNotNull(editor.findViewById(R.id.profile_model_picker));
                assertNotNull(editor.findViewById(R.id.btn_choose_remote_model));
            });
        }
    }

    private void awaitStatus(String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 6000;
        AtomicReference<String> status = new AtomicReference<>("");
        while (System.currentTimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> status.set(
                    ((TextView) pickerRoot.findViewById(R.id.tv_model_list_status)).getText().toString()));
            if (status.get().contains(expected)) return;
            Thread.sleep(30);
        }
        fail("Expected " + expected + " in status: " + status.get());
    }

    private void saveScreenshot(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation().waitForIdle(500, 5000);
        Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(image);
        File folder = InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null);
        try (FileOutputStream out = new FileOutputStream(new File(folder, name))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally { image.recycle(); }
    }
}
