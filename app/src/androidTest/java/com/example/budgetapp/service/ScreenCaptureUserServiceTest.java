package com.example.budgetapp.service;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertNotNull;

@RunWith(AndroidJUnit4.class)
public class ScreenCaptureUserServiceTest {
    @Test public void readerCanBindWithoutContextSystemServiceInitialization() {
        Context packageContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context headlessContext = new ContextWrapper(packageContext) {
            @Override public Object getSystemService(String name) {
                throw new IllegalStateException("ApplicationSharedMemory not initialized: " + name);
            }
        };
        assertNotNull(new ScreenCaptureUserService(headlessContext).asBinder());
    }
}
