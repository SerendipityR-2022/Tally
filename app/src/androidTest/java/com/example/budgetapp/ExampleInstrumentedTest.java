package com.example.budgetapp;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import androidx.core.content.FileProvider;
import com.example.budgetapp.util.AccessibilityServiceIdentity;
import java.io.File;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class ExampleInstrumentedTest {
    @Test
    public void useAppContext() {
        // Context of the app under test.
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(BuildConfig.APPLICATION_ID, appContext.getPackageName());
        assertEquals(BuildConfig.APPLICATION_ID + "/com.google.android.accessibility.selecttospeak.SelectToSpeakService",
                AccessibilityServiceIdentity.componentId(appContext.getPackageName()));
    }

    @Test public void photoProviderUsesTheApplicationId() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        assertNotNull(pictures);
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider",
                new File(pictures, "photo-provider-test.jpg"));
        assertEquals(BuildConfig.APPLICATION_ID + ".fileprovider", uri.getAuthority());
        assertEquals("image/jpeg", context.getContentResolver().getType(uri));
    }
}
