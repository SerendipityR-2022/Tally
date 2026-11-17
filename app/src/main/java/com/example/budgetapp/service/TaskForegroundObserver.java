package com.example.budgetapp.service;

import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import com.example.budgetapp.BuildConfig;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/** Shell-only task-change hints. No task contents are unmarshalled or retained. */
final class TaskForegroundObserver {
    private static final String DESCRIPTOR = "android.app.ITaskStackListener";
    private final Runnable changed;
    private Object manager;
    private Object listener;
    private Method unregister;
    private IBinder managerBinder;
    private volatile boolean active;
    private boolean unavailable;

    TaskForegroundObserver(Runnable changed) { this.changed = changed; }

    boolean start() {
        if (active && managerBinder != null && managerBinder.isBinderAlive()) return true;
        if (unavailable) return false;
        stop();
        try {
            Class<?> listenerClass = Class.forName(DESCRIPTOR);
            Class<?> stub = Class.forName(DESCRIPTOR + "$Stub");
            Set<Integer> notifications = new HashSet<>();
            // Resolve transaction IDs on this OS; never bake an AIDL transaction number into the APK.
            notifications.add(transaction(stub, "onTaskStackChanged"));
            for (String name : new String[]{"onTaskMovedToFront", "onTaskFocusChanged",
                    "onTaskRemoved", "onTaskDisplayChanged", "onActivityRestartAttempt"}) {
                try { notifications.add(transaction(stub, name)); }
                catch (ReflectiveOperationException ignored) { /* Older Android. */ }
            }
            Binder endpoint = new Binder() {
                @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                        throws RemoteException {
                    if (code == INTERFACE_TRANSACTION) {
                        if (reply != null) reply.writeString(DESCRIPTOR);
                        return true;
                    }
                    if (code >= FIRST_CALL_TRANSACTION && code <= LAST_CALL_TRANSACTION) {
                        if (Binder.getCallingUid() != 1000) return false;
                        data.enforceInterface(DESCRIPTOR);
                        // Other callbacks (including snapshots with file descriptors) are deliberately
                        // left in the Parcel, whose lifetime is managed by Binder. No node/task access.
                        if (active && notifications.contains(code)) changed.run();
                        return true;
                    }
                    return super.onTransact(code, data, reply, flags);
                }
            };
            listener = stub.getMethod("asInterface", IBinder.class).invoke(null, endpoint);
            String owner = Build.VERSION.SDK_INT >= 29 ? "android.app.ActivityTaskManager" : "android.app.ActivityManager";
            String contract = Build.VERSION.SDK_INT >= 29 ? "android.app.IActivityTaskManager" : "android.app.IActivityManager";
            Method getService = Class.forName(owner).getDeclaredMethod("getService");
            getService.setAccessible(true);
            manager = getService.invoke(null);
            Class<?> managerClass = Class.forName(contract);
            managerBinder = (IBinder) managerClass.getMethod("asBinder").invoke(manager);
            unregister = managerClass.getMethod("unregisterTaskStackListener", listenerClass);
            active = true;
            managerClass.getMethod("registerTaskStackListener", listenerClass).invoke(manager, listener);
            if (BuildConfig.DEBUG) Log.d("TallyScreenReader", "Task events enabled");
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            stop();
            unavailable = true;
            if (BuildConfig.DEBUG) Log.d("TallyScreenReader", "Task events unavailable; using timed probes");
            return false;
        }
    }

    private static int transaction(Class<?> stub, String name) throws ReflectiveOperationException {
        Field field = stub.getDeclaredField("TRANSACTION_" + name);
        field.setAccessible(true);
        return field.getInt(null);
    }

    void stop() {
        active = false;
        if (manager != null && listener != null && unregister != null) {
            try { unregister.invoke(manager, listener); }
            catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
        manager = listener = null;
        unregister = null;
        managerBinder = null;
    }
}
