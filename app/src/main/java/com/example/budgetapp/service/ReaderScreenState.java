package com.example.budgetapp.service;

import android.content.Context;
import android.os.IBinder;
import android.util.Log;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import rikka.shizuku.SystemServiceHelper;

/** Read-only screen checks for a Shizuku app_process, which has no ApplicationSharedMemory. */
final class ReaderScreenState {
    private static final String TAG = "TallyScreenReader";
    private final BooleanQuery interactive = new BooleanQuery(Context.POWER_SERVICE,
            "android.os.IPowerManager", "isInteractive");
    private final BooleanQuery keyguardLocked = new BooleanQuery(Context.WINDOW_SERVICE,
            "android.view.IWindowManager", "isKeyguardLocked");

    boolean isAvailable() {
        return interactive.read() && !keyguardLocked.read();
    }

    boolean describe(DiagnosticTrace trace) {
        boolean interactive = this.interactive.read();
        boolean locked = keyguardLocked.read();
        trace.event("reader.screen_state", "interactive=" + interactive + "; keyguardLocked=" + locked);
        return interactive && !locked;
    }

    private static final class BooleanQuery {
        private final String serviceName;
        private final String descriptor;
        private final String methodName;
        private Object proxy;
        private Method getter;

        BooleanQuery(String serviceName, String descriptor, String methodName) {
            this.serviceName = serviceName;
            this.descriptor = descriptor;
            this.methodName = methodName;
        }

        boolean read() {
            try {
                if (proxy == null) connect();
                return (boolean) getter.invoke(proxy);
            } catch (InvocationTargetException e) {
                throw unavailable(e.getCause());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                throw unavailable(e);
            }
        }

        private void connect() throws ReflectiveOperationException {
            IBinder binder = SystemServiceHelper.getSystemService(serviceName);
            if (binder == null) throw new IllegalStateException("System service not found: " + serviceName);
            // Use the device's own AIDL proxy. Do not construct Context-bound framework managers
            // or hard-code transaction numbers that may vary with Android and OEM builds.
            Method method = Class.forName(descriptor).getMethod(methodName);
            if (method.getReturnType() != boolean.class) throw new NoSuchMethodException(methodName);
            Object service = Class.forName(descriptor + "$Stub").getMethod("asInterface", IBinder.class)
                    .invoke(null, binder);
            if (service == null) throw new IllegalStateException("System service proxy not found: " + serviceName);
            getter = method;
            proxy = service;
        }

        private IllegalStateException unavailable(Throwable cause) {
            proxy = null;
            Log.w(TAG, "Screen state query failed: " + methodName, cause);
            // Abort the sample if the reader cannot confirm that capture is allowed.
            return new IllegalStateException("Screen state query failed: " + methodName, cause);
        }
    }
}
