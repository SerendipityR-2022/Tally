package com.example.budgetapp.service;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import com.example.budgetapp.R;
import com.example.budgetapp.ui.AssistantManagerActivity;
import com.example.budgetapp.util.AssistantConfig;
import com.example.budgetapp.util.ScreenCapturePolicy;
import com.example.budgetapp.util.KeywordManager;
import java.util.Arrays;
import com.example.budgetapp.util.ScreenCaptureSettings;
import com.example.budgetapp.util.ScreenHierarchyCodec;
import com.example.budgetapp.util.ScreenNode;
import com.example.budgetapp.util.ShizukuManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import rikka.shizuku.Shizuku;

import static com.example.budgetapp.service.ScreenSampleCadence.*;

/** User-visible, opt-in screen source. Revocation never falls back to accessibility. */
public final class ExperimentalAccountingService extends Service {
    private static final String TAG = "TallyScreenReader";
    private static final String CHANNEL = "experimental_screen_accounting";
    private static final int NOTIFICATION = 1003;
    // Cached readers must use the same Binder callback signature.
    private static final int READER_VERSION = 15;
    private static final String ACTION_STOP = "com.example.budgetapp.STOP_EXPERIMENTAL_ACCOUNTING";
    private static volatile int status = R.string.experimental_status_stopped;
    private static volatile boolean running;
    // Assigned and accessed only on the main thread.
    private static ExperimentalAccountingService instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean destroyed;
    private volatile boolean failed;
    private volatile long screenGeneration;
    private volatile IScreenCaptureService remote;
    private ScreenAccountingEngine engine;
    private ScreenCaptureSettings settings;
    private String[] rulePackages;
    private final SharedPreferences.OnSharedPreferenceChangeListener ruleChanges = (prefs, key) -> {
        rulePackages = KeywordManager.getCustomScreenPackages(this);
        requestSample(0);
    };
    private AssistantConfig config;
    private PowerManager power;
    private KeyguardManager keyguard;
    private ScreenStateWatcher screenWatcher;
    private DisplayManager displays;
    private boolean displayListenerRegistered;
    private int lastDisplayState = Display.STATE_UNKNOWN;
    private Boolean lastInteractive, lastLocked;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { onDisplayChanged(id); }
        @Override public void onDisplayRemoved(int id) { onDisplayChanged(id); }
        @Override public void onDisplayChanged(int id) {
            if (id != Display.DEFAULT_DISPLAY || destroyed || failed) return;
            Display display = displays.getDisplay(id);
            int state = display == null ? Display.STATE_UNKNOWN : display.getState();
            if (state == lastDisplayState) return;
            lastDisplayState = state;
            screenSignal("display_state=" + state);
        }
    };
    private Shizuku.UserServiceArgs args;
    private boolean awaitingShizuku;
    private boolean binding;
    private boolean receiverRegistered;
    private boolean listenerRegistered;
    // Scheduling state lives on main. Only the serial worker touches the remote reader.
    private boolean screenActive;
    private boolean inFlight;
    private long pendingSampleAt = Long.MAX_VALUE;
    private long scheduledAt = Long.MAX_VALUE;
    private int failures;
    private int emptyScreens;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferences = (prefs, key) ->
            main.post(this::checkRequirements);
    private final Shizuku.OnBinderReceivedListener binderReceived = this::onShizukuReady;
    private final Runnable shizukuTimeout = () -> {
        if (awaitingShizuku) {
            Log.w(TAG, "Shizuku did not deliver its binder to the host process");
            ScreenDebugCapture.CONNECTION.event("host.shizuku.timeout", "Shizuku Binder not delivered in 15 s");
            fail(R.string.experimental_status_auth_lost);
        }
    };
    private final Shizuku.OnBinderDeadListener binderDead = () -> main.post(() -> {
        ScreenDebugCapture.CONNECTION.event("host.shizuku.dead", "Shizuku Binder died");
        fail(R.string.experimental_status_auth_lost);
    });
    private final Runnable bindTimeout = () -> {
        if (!destroyed && !running) {
            Log.w(TAG, "Shizuku reader did not bind within 15 seconds");
            ScreenDebugCapture.CONNECTION.event("host.bind.timeout", "Reader did not complete handshake in 15 s");
            fail(R.string.experimental_status_connection_failed);
        }
    };
    private final Runnable sampleTask = this::startSample;
    private final Runnable sampleTimeout = () -> {
        ScreenDebugCapture.CONNECTION.event("host.sample.timeout", "Normal sample exceeded 20 s");
        fail(R.string.experimental_status_unavailable);
    };
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) suspendReading();
            // Signals trigger a fresh state query; delayed broadcasts are not the current state.
            screenSignal(action);
        }
    };

    public static int getStatus() { return status; }
    public static boolean isRunning() { return running; }
    public static boolean isConnecting() { return status == R.string.experimental_status_connecting; }

    /** Called from visible settings when the user changes the restart policy of an active service. */
    public static void refreshRestartPolicy(Context context) {
        if (isRunning() || isConnecting()) startFromUser(context);
    }

    public static boolean notificationsAllowed(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false;
        NotificationManager manager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = manager == null ? null : manager.getNotificationChannel(CHANNEL);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    public static boolean overlayRequirementMet(Context context) {
        ScreenCaptureSettings settings = new ScreenCaptureSettings(context);
        return settings.isSilentAccountingEnabled() || Settings.canDrawOverlays(context);
    }

    /** Returns zero when the selected way of recording has all its required permissions. */
    public static int getMissingPermissionStatus(Context context) {
        if (!overlayRequirementMet(context)) return R.string.experimental_status_overlay_required;
        if (!notificationsAllowed(context)) return R.string.experimental_status_notification_required;
        return 0;
    }

    /** Only call from a visible Activity; background binder callbacks do not start capture. */
    public static boolean startFromUser(Context context) {
        if (!new ScreenCaptureSettings(context).isEnabled() || !new AssistantConfig(context).isEnabled()) {
            ScreenDebugCapture.CONNECTION.event("host.start", "Experimental mode or automatic accounting disabled");
            return false;
        }
        if (!ShizukuManager.hasShizukuPermission()) {
            ScreenDebugCapture.CONNECTION.event("host.start", "Shizuku unavailable or permission not granted");
            return false;
        }
        int missing = getMissingPermissionStatus(context);
        if (missing != 0) {
            ScreenDebugCapture.CONNECTION.event("host.start", context.getString(missing));
            return false;
        }
        try {
            ContextCompat.startForegroundService(context, new Intent(context, ExperimentalAccountingService.class));
            return true;
        } catch (RuntimeException e) {
            ScreenDebugCapture.CONNECTION.error("host.start_foreground", e);
            status = R.string.experimental_status_connection_failed;
            return false;
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, ExperimentalAccountingService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        ScreenDebugCapture.CONNECTION.event("host.service.create", "Foreground accounting service starting");
        rulePackages = KeywordManager.getCustomScreenPackages(this);
        KeywordManager.rulePreferences(this).registerOnSharedPreferenceChangeListener(ruleChanges);
        status = R.string.experimental_status_connecting;
        settings = new ScreenCaptureSettings(this);
        config = new AssistantConfig(this);
        try {
            power = getSystemService(PowerManager.class);
            keyguard = getSystemService(KeyguardManager.class);
            screenWatcher = new ScreenStateWatcher(
                    () -> new ScreenStateWatcher.State(power != null && power.isInteractive(),
                            keyguard == null || keyguard.isKeyguardLocked()),
                    new ScreenStateWatcher.Scheduler() {
                        @Override public long uptimeMillis() { return SystemClock.uptimeMillis(); }
                        @Override public void post(Runnable task, int delay) { main.postDelayed(task, delay); }
                        @Override public void remove(Runnable task) { main.removeCallbacks(task); }
                    }, new ScreenStateWatcher.Observer() {
                        @Override public void onState(ScreenStateWatcher.State state, String reason) {
                            applyScreenState(state, reason);
                        }
                        @Override public void onError(RuntimeException error) {
                            ScreenDebugCapture.CONNECTION.error("host.screen.query", error);
                            suspendReading();
                        }
                    });
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                    getString(R.string.experimental_notification_channel), NotificationManager.IMPORTANCE_LOW));
            Intent settings = new Intent(this, AssistantManagerActivity.class);
            PendingIntent open = PendingIntent.getActivity(this, 0, settings,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop = PendingIntent.getService(this, 0,
                    new Intent(this, ExperimentalAccountingService.class).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification = new NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_app_logo).setContentTitle(getString(R.string.experimental_title))
                    .setContentText(getString(R.string.experimental_notification_text)).setContentIntent(open)
                    .setOngoing(true).addAction(0, getString(R.string.experimental_stop), stop).build();
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIFICATION, notification);
            engine = new ScreenAccountingEngine(this);
            engine.requireConfirmation();
            engine.setPresentationGuard(this::canCaptureNow);
            getSharedPreferences(ScreenCaptureSettings.PREFS, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(preferences);
            getSharedPreferences("app_prefs", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(preferences);
            Shizuku.addBinderDeadListener(binderDead);
            listenerRegistered = true;
            IntentFilter screenEvents = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            screenEvents.addAction(Intent.ACTION_SCREEN_ON);
            screenEvents.addAction(Intent.ACTION_USER_PRESENT);
            screenEvents.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
            ContextCompat.registerReceiver(this, screenReceiver, screenEvents, ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
            // Independent wake signal: some devices defer screen broadcasts after a long idle.
            displays = getSystemService(DisplayManager.class);
            if (displays != null) {
                try {
                    if (Build.VERSION.SDK_INT >= 36) {
                        displays.registerDisplayListener(command -> main.post(command), DisplayManager.EVENT_TYPE_DISPLAY_STATE
                                | DisplayManager.EVENT_TYPE_DISPLAY_ADDED | DisplayManager.EVENT_TYPE_DISPLAY_REMOVED, displayListener);
                    } else displays.registerDisplayListener(displayListener, main);
                    displayListenerRegistered = true;
                } catch (RuntimeException error) {
                    ScreenDebugCapture.CONNECTION.error("host.screen.display_listener", error);
                }
            }
        } catch (RuntimeException e) {
            ScreenDebugCapture.CONNECTION.error("host.service.create", e);
            fail(R.string.experimental_status_connection_failed);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (destroyed || failed) return START_NOT_STICKY;
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            config.setEnabled(false);
            settings.setEnabled(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean keepAlive = settings.isKeepAliveEnabled();
        if (intent == null && !keepAlive) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!checkLocalRequirements()) return START_NOT_STICKY;
        if (remote == null && !binding && !awaitingShizuku) {
            // After a process death the foreground service can start before Shizuku delivers its binder.
            awaitingShizuku = true;
            main.postDelayed(shizukuTimeout, 15000);
            Shizuku.addBinderReceivedListenerSticky(binderReceived, main);
        } else if (!checkRequirements()) {
            return START_NOT_STICKY;
        }
        return keepAlive ? START_STICKY : START_NOT_STICKY;
    }

    private void onShizukuReady() {
        if (!awaitingShizuku) return;
        awaitingShizuku = false;
        main.removeCallbacks(shizukuTimeout);
        Shizuku.removeBinderReceivedListener(binderReceived);
        if (checkRequirements()) bindReader();
    }

    private boolean checkLocalRequirements() {
        if (destroyed || failed) return false;
        if (!settings.isEnabled() || !config.isEnabled()) {
            stopSelf();
            return false;
        }
        int missingPermission = getMissingPermissionStatus(this);
        if (missingPermission != 0) { fail(missingPermission); return false; }
        return true;
    }

    private boolean checkRequirements() {
        if (!checkLocalRequirements()) return false;
        if (!awaitingShizuku && !ShizukuManager.hasShizukuPermission()) {
            fail(R.string.experimental_status_auth_lost);
            return false;
        }
        return true;
    }

    private boolean screenAvailable() {
        return power != null && power.isInteractive() && keyguard != null && !keyguard.isKeyguardLocked();
    }

    private boolean canCaptureNow() {
        return !destroyed && !failed && settings.isEnabled() && config.isEnabled()
                && screenAvailable() && ShizukuManager.hasShizukuPermission()
                && getMissingPermissionStatus(this) == 0;
    }

    private void bindReader() {
        try {
            binding = true;
            ScreenDebugCapture.CONNECTION.event("host.bind", "Binding reader protocol " + READER_VERSION);
            try {
                ScreenDebugCapture.CONNECTION.event("host.shizuku", "Server API=" + Shizuku.getVersion()
                        + ", UID=" + Shizuku.getUid());
            } catch (RuntimeException e) {
                ScreenDebugCapture.CONNECTION.error("host.shizuku.metadata", e);
            }
            args = new Shizuku.UserServiceArgs(new ComponentName(this, ScreenCaptureUserService.class))
                    .daemon(false).processNameSuffix("screen_reader").tag("experimental_screen_reader").version(READER_VERSION);
            main.postDelayed(bindTimeout, 15000);
            Shizuku.bindUserService(args, connection);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to bind Shizuku reader", e);
            ScreenDebugCapture.CONNECTION.error("host.bind", e);
            fail(R.string.experimental_status_connection_failed);
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (destroyed || failed || !checkRequirements()) return;
            final IScreenCaptureService reader = IScreenCaptureService.Stub.asInterface(binder);
            remote = reader;
            ScreenDebugCapture.CONNECTION.event("host.bind.connected", "Reader Binder delivered");
            io.execute(() -> {
                try {
                    int uid = reader.initialize(new IScreenCaptureListener.Stub() {
                        @Override public void onScreenChanged(long sampleAt) {
                            int caller = Binder.getCallingUid();
                            if (caller != 0 && caller != 2000) return;
                            main.post(() -> {
                                if (remote == reader) requestSampleAt(sampleAt);
                            });
                        }
                    });
                    if (uid != 0 && uid != 2000) throw new SecurityException("Not a shell service");
                    ScreenDebugCapture.CONNECTION.event("host.handshake", "Reader initialized with UID " + uid
                            + "; mode=companion_uiautomation");
                    main.post(() -> {
                        if (remote != reader || !checkRequirements()) return;
                        main.removeCallbacks(bindTimeout);
                        running = true;
                        refreshScreenState();
                    });
                } catch (Exception e) {
                    Log.w(TAG, "Shizuku reader handshake failed", e);
                    ScreenDebugCapture.CONNECTION.error("host.handshake", e);
                    main.post(() -> fail(R.string.experimental_status_connection_failed));
                }
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            if (!destroyed) {
                ScreenDebugCapture.CONNECTION.event("host.bind.disconnected", "Reader Binder disconnected");
                fail(R.string.experimental_status_connection_failed);
            }
        }
    };

    private void screenSignal(String reason) {
        if (destroyed || failed || screenWatcher == null) return;
        ScreenDebugCapture.CONNECTION.event("host.screen.signal", String.valueOf(reason));
        screenWatcher.signal(reason);
    }

    private void refreshScreenState() {
        if (!running || !checkRequirements()) return;
        screenWatcher.start();
        screenWatcher.signal("reader_refresh");
    }

    private void applyScreenState(ScreenStateWatcher.State state, String reason) {
        if (!running || !checkRequirements()) return;
        boolean changed = lastInteractive == null || lastInteractive != state.interactive
                || lastLocked == null || lastLocked != state.locked;
        if (changed || !"paused_recheck".equals(reason)) {
            ScreenDebugCapture.CONNECTION.event("host.screen.state", "reason=" + reason
                    + "; interactive=" + state.interactive + "; keyguardLocked=" + state.locked
                    + "; readingActive=" + screenActive + "; sampleInFlight=" + inFlight);
        }
        lastInteractive = state.interactive;
        lastLocked = state.locked;
        if (!state.available()) {
            suspendReading();
            return;
        }
        if (!screenActive) {
            screenActive = true;
            status = R.string.experimental_status_ready;
            ScreenDebugCapture.CONNECTION.event("host.screen.resume", "Screen verified unlocked; restarting samples; reason=" + reason);
            if (inFlight) main.postDelayed(sampleTimeout, 20000);
        }
        requestSample(0);
    }

    private void suspendReading() {
        if (!running || destroyed || failed) return;
        status = R.string.experimental_status_screen_locked;
        if (!screenActive) return;
        ScreenDebugCapture.CONNECTION.event("host.screen.pause", "Cancelling samples and releasing reader while screen is unavailable");
        screenActive = false;
        screenGeneration++;
        failures = emptyScreens = 0;
        pendingSampleAt = Long.MAX_VALUE;
        scheduledAt = Long.MAX_VALUE;
        main.removeCallbacks(sampleTask);
        main.removeCallbacks(sampleTimeout);
        if (engine != null) engine.pause();
        final IScreenCaptureService reader = remote;
        ScreenDebugCapture.Run run = ScreenDebugCapture.activeExperimental();
        if (run != null) run.note("host.screen", "Screen off or locked; asking reader to stop");
        if (reader != null) io.execute(() -> {
            try { reader.releaseAutomation(); }
            catch (Exception e) { if (run != null) run.error("host.screen.release_reader", e); }
        });
    }

    private void requestSample(int delay) {
        requestSampleAt(SystemClock.uptimeMillis() + delay);
    }

    private void requestSampleAt(long when) {
        if (!screenActive || destroyed || failed || !running || remote == null) return;
        if (inFlight) {
            pendingSampleAt = Math.min(pendingSampleAt, when);
            return;
        }
        if (when >= scheduledAt) return;
        scheduledAt = when;
        main.removeCallbacks(sampleTask);
        main.postAtTime(sampleTask, when);
    }

    public static void armDiagnostic(ScreenDebugCapture.Run run, Runnable onArmed) {
        if (run.source != ScreenDebugCapture.Source.EXPERIMENTAL) return;
        ExperimentalAccountingService service = instance;
        if (service == null) {
            run.reject("No experimental foreground service", false);
            return;
        }
        try {
            if (!service.runningOnScreen() || !service.canCaptureNow()) {
                run.reject("Reader not ready or screen/permission requirements not met: " + service.getString(status), false);
                return;
            }
            service.armReader(run, onArmed);
        } catch (RuntimeException e) {
            run.reportError("host.arm.preflight", e);
            run.reject("Could not prepare diagnostic request", false);
            service.requestSample(0);
        }
    }

    private boolean runningOnScreen() {
        return running && screenActive && !destroyed && !failed && remote != null;
    }

    private void armReader(ScreenDebugCapture.Run run, Runnable onArmed) {
        final IScreenCaptureService reader = remote;
        screenGeneration++;
        main.removeCallbacks(sampleTask);
        main.removeCallbacks(sampleTimeout);
        scheduledAt = Long.MAX_VALUE;
        if (engine != null) engine.pause();
        run.note("host.arm", "Requesting reader-side capture (90 s limit)");
        io.execute(() -> {
            boolean attempted = false;
            try {
                if (!canCaptureNow()) throw new IllegalStateException("Capture no longer authorized");
                ScreenDebugCapture.describeTarget(this, run);
                attempted = true;
                boolean started = reader.startDiagnostic(run.id, run.selected, run.deadline);
                main.post(() -> {
                    if (run.finalRead) return;
                    run.armed = true; // Even a rejected start can have a reader-side error report.
                    if (!started) run.reject("Reader could not start; retrieving reader error report", true);
                    if (remote != reader || !runningOnScreen() || ScreenDebugCapture.activeExperimental() != run) {
                        fetchReaderReport(run, true);
                        return;
                    }
                    run.note("host.arm", "Reader confirmed capture");
                    Log.i(TAG, "Diagnostic reader ready");
                    main.postDelayed(() -> {
                        if (ScreenDebugCapture.latest() == run) fetchReaderReport(run, true);
                    }, Math.max(0, run.deadline - SystemClock.elapsedRealtime()));
                    onArmed.run();
                });
            } catch (Exception | LinkageError e) {
                final boolean mayRead = attempted;
                main.post(() -> {
                    if (run.finalRead) return;
                    run.reportError("host.arm.ipc", e);
                    run.armed = mayRead;
                    run.reject("Capture start failed", mayRead);
                    if (mayRead) fetchReaderReport(run, true);
                    else requestSample(0);
                });
            }
        });
    }

    public static void fetchDiagnostic(ScreenDebugCapture.Run run, boolean stop) {
        if (run == null || run.source != ScreenDebugCapture.Source.EXPERIMENTAL || run.finalRead) return;
        if (instance != null) instance.fetchReaderReport(run, stop);
        else if (stop) run.reportUnavailable("Foreground service is no longer available");
    }

    private void fetchReaderReport(ScreenDebugCapture.Run run, boolean stop) {
        if (stop) run.finalReadRequested = true;
        if (run.finalRead || run.reportInFlight) return;
        if (destroyed || failed || remote == null) {
            if (stop) run.reportUnavailable("Reader connection ended before the final report");
            return;
        }
        if (!run.armed) return;
        final IScreenCaptureService reader = remote;
        final int seen = run.seenFrame;
        final int offset = run.traceLength();
        final boolean finish = run.finalReadRequested || !run.isActive();
        run.reportInFlight = true;
        io.execute(() -> {
            Bundle report = null;
            StringBuilder delta = new StringBuilder();
            String stage = "host.report.ipc";
            Throwable failure = null;
            try {
                report = reader.readDiagnostic(run.id, seen, finish);
                if (report != null) {
                    stage = "host.report.trace";
                    DiagnosticTrace.readDelta(offset, report.getInt(ReaderDiagnosticSession.TRACE_LENGTH), delta,
                            position -> reader.readDiagnosticTrace(run.id, position));
                    if (finish && report.getBoolean(ReaderDiagnosticSession.RUNNING)) {
                        throw new IllegalStateException("Reader still running after stop request");
                    }
                }
            } catch (Exception | LinkageError e) {
                failure = e;
            }
            // Keep diagnostics even if the frame is invalid or cannot be decoded.
            String xml = report == null ? null : report.getString(HIERARCHY);
            ScreenNode snapshot = null;
            try {
                if (xml != null && canCaptureNow()) {
                    snapshot = ScreenHierarchyCodec.decodeDiagnostic(xml, run.selected, getPackageName());
                }
            } catch (Exception | LinkageError e) {
                main.post(() -> run.reportError("host.report.decode_xml", e));
            }
            Bundle result = report;
            ScreenNode node = snapshot;
            String failedStage = stage;
            Throwable reportFailure = failure;
            main.post(() -> {
                run.reportInFlight = false;
                if (run.finalRead) return;
                if (reportFailure != null) run.reportError(failedStage, reportFailure);
                run.report(result, delta, xml, node);
                if (result == null && reportFailure == null) run.reportUnavailable("Reader no longer has this capture ID");
                if (!run.finalRead && (finish || run.finalReadRequested || !run.isActive())) {
                    if (finish && reportFailure != null && ++run.finalReadFailures >= 2) {
                        run.reportUnavailable("Final reader report failed twice; exporting the entries already received");
                    } else {
                        fetchReaderReport(run, true);
                        return;
                    }
                }
                if (run.finalRead && ScreenDebugCapture.latest() == run) {
                    Log.i(TAG, "Diagnostic ended: probes=" + run.getProbed() + " matched=" + run.getFocused()
                            + " unverified=" + run.getNoFocus() + " unreadable=" + run.getUnreadable()
                            + " errors=" + run.getErrors() + " frames=" + run.seenFrame);
                    requestSample(0);
                }
            });
        });
    }

    private void startSample() {
        scheduledAt = Long.MAX_VALUE;
        if (!runningOnScreen() || ScreenDebugCapture.activeExperimental() != null) return;
        final IScreenCaptureService reader = remote;
        final String[] approved = rulePackages;
        final boolean allowHierarchy = !engine.isBusy();
        final long generation = screenGeneration;
        inFlight = true;
        pendingSampleAt = Long.MAX_VALUE;
        main.postDelayed(sampleTimeout, 20000);
        io.execute(() -> {
            try {
                Bundle result = generation == screenGeneration && canCaptureNow()
                        ? reader.sample(allowHierarchy, approved) : null;
                String xml = result == null ? null : result.getString(HIERARCHY);
                ScreenNode snapshot = null;
                if (xml != null && generation == screenGeneration) {
                    String pkg = result.getString(PACKAGE);
                    if (ScreenCapturePolicy.isTargetPackage(pkg)) {
                        snapshot = ScreenHierarchyCodec.decode(xml, pkg);
                    } else if (pkg != null && Arrays.binarySearch(approved, pkg) >= 0) {
                        snapshot = ScreenHierarchyCodec.decodeDiagnostic(xml, pkg, getPackageName());
                    }
                }
                if (result != null) result.remove(HIERARCHY);
                ScreenNode copy = snapshot;
                main.post(() -> {
                    boolean normal = ScreenDebugCapture.activeExperimental() == null && copy != null
                            && (ScreenCapturePolicy.isTargetPackage(copy.getPackageName())
                            || Arrays.binarySearch(rulePackages, copy.getPackageName()) >= 0);
                    finishSample(generation, result, normal ? copy : null, false);
                });
            } catch (Exception e) {
                // A snapshot the app could not consume must not stay de-duplicated in the reader.
                ScreenDebugCapture.CONNECTION.error("host.sample", e);
                try { reader.releaseAutomation(); }
                catch (Exception cleanup) { ScreenDebugCapture.CONNECTION.error("host.sample.release_reader", cleanup); }
                main.post(() -> finishSample(generation, null, null, true));
            }
        });
    }

    private void finishSample(long generation, Bundle result, ScreenNode snapshot, boolean error) {
        main.removeCallbacks(sampleTimeout);
        inFlight = false;
        if (!screenActive || destroyed || failed) return;
        if (generation != screenGeneration) {
            requestSample(0);
            return;
        }
        if (error) {
            if (++failures >= 3) fail(R.string.experimental_status_unavailable);
            else requestSample(ERROR_RETRY_MS);
            return;
        }
        failures = 0;
        if (result == null || result.getInt(STATE) == PAUSED) {
            if (!checkRequirements()) return;
            if (screenAvailable()) requestSample(IDLE_PROBE_MS);
            else {
                suspendReading();
                screenSignal("sample_screen_unavailable");
            }
            return;
        }
        switch (result.getInt(STATE)) {
            case IDLE:
                emptyScreens = 0;
                status = R.string.experimental_status_ready;
                // Leaving a payment page does not invalidate a receipt already recognized by the engine.
                break;
            case UNREADABLE:
                if (++emptyScreens >= 3) status = R.string.experimental_status_unreadable;
                break;
            case TARGET:
                if (snapshot != null && engine.analyze(snapshot)) {
                    emptyScreens = 0;
                    status = R.string.experimental_status_active;
                }
                break;
            default: break;
        }
        int delay = Math.max(EVENT_SETTLE_MS, Math.min(IDLE_WATCHDOG_MS, result.getInt(DELAY, IDLE_PROBE_MS)));
        if (engine.isBusy()) delay = Math.min(delay, TARGET_PROBE_MS);
        requestSampleAt(Math.min(SystemClock.uptimeMillis() + delay, pendingSampleAt));
    }

    private void fail(int message) {
        if (destroyed || failed) return;
        failed = true;
        if (screenWatcher != null) screenWatcher.stop();
        ScreenDebugCapture.CONNECTION.event("host.service.fail", getString(message));
        running = false;
        status = message;
        screenGeneration++;
        if (engine != null) engine.pause();
        stopSelf();
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (screenWatcher != null) screenWatcher.stop();
        if (displayListenerRegistered) {
            try { displays.unregisterDisplayListener(displayListener); }
            catch (RuntimeException error) { ScreenDebugCapture.CONNECTION.error("host.screen.unregister_display", error); }
            displayListenerRegistered = false;
        }
        if (instance == this) {
            ScreenDebugCapture.Run run = ScreenDebugCapture.latest();
            if (run != null && run.source == ScreenDebugCapture.Source.EXPERIMENTAL) {
                run.reportUnavailable("Tally foreground service ended before the final reader report");
            }
            instance = null;
        }
        KeywordManager.rulePreferences(this).unregisterOnSharedPreferenceChangeListener(ruleChanges);
        running = false;
        screenGeneration++;
        main.removeCallbacksAndMessages(null);
        getSharedPreferences(ScreenCaptureSettings.PREFS, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(preferences);
        getSharedPreferences("app_prefs", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(preferences);
        if (listenerRegistered) Shizuku.removeBinderDeadListener(binderDead);
        if (awaitingShizuku) Shizuku.removeBinderReceivedListener(binderReceived);
        if (receiverRegistered) unregisterReceiver(screenReceiver);
        if (engine != null) engine.close();
        io.shutdownNow();
        if (args != null) {
            try { Shizuku.unbindUserService(args, connection, true); }
            catch (RuntimeException e) { ScreenDebugCapture.CONNECTION.error("host.unbind", e); }
        }
        ScreenDebugCapture.CONNECTION.event("host.service.stop", "Foreground accounting service stopped");
        remote = null;
        if (!failed) status = R.string.experimental_status_stopped;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
