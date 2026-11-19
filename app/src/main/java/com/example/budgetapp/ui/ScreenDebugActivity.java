package com.example.budgetapp.ui;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.example.budgetapp.R;
import com.example.budgetapp.database.Transaction;
import com.example.budgetapp.service.ExperimentalAccountingService;
import com.example.budgetapp.service.ScreenAccountingEngine;
import com.example.budgetapp.service.ScreenDebugCapture;
import com.example.budgetapp.util.KeywordManager;
import com.example.budgetapp.util.ScreenCapturePolicy;
import com.example.budgetapp.util.ScreenCaptureSettings;
import com.example.budgetapp.util.ScreenHierarchyCodec;
import com.example.budgetapp.util.ScreenNode;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.accessibility.selecttospeak.SelectToSpeakService;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-controlled, expiring screen diagnostic plus sandboxed rule execution. */
public final class ScreenDebugActivity extends AppCompatActivity {
    private static final String DEMO = ScreenCapturePolicy.PAYMENT_DEMO;
    private static final String DEMO_RULE_PACKAGE = "com.sankuai.meituan.takeoutnew";
    private static final int PREVIEW_LIMIT = 7000;
    private static final int LOG_CLIPBOARD_LIMIT = 160 * 1024;
    private static final int LOG_WAIT_MS = 15000;
    private static final DateTimeFormatter XML_EXPORT_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private TextInputEditText target, keyword;
    private TextView service, status, badge, counters, preview, ruleResult;
    private Spinner direction;
    private MaterialButton start, copy, save, copyLog, saveLog, verify, verifyDraft, addRule, clear;
    private PendingLog logExport;
    private ScreenCaptureSettings captureSettings;
    private ScreenDebugCapture.Run pendingLogRun;
    private long logWaitUntil;
    private boolean preparingLog, logToFile;
    private String renderedXml, exportXml, importPackage;
    private ScreenAccountingEngine engine;
    private ScreenDebugCapture.Run replayRun;
    private ScreenNode replaySnapshot;
    private String replayKeyword;
    private int replayType, testWrites;
    private boolean replayInFlight;
    private AlertDialog confirmation;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            ExperimentalAccountingService.fetchDiagnostic(ScreenDebugCapture.latest(), false);
            if (preparingLog) deliverLogWhenReady();
            render();
            main.postDelayed(this, 750);
        }
    };
    private final Runnable noMatch = () -> finishReplay(testWrites == 0 ? R.string.screen_debug_no_match : R.string.screen_debug_duplicate);
    private final ActivityResultLauncher<String> export = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/xml"), this::saveFile);
    private final ActivityResultLauncher<String> exportLog = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("text/plain"), this::saveLogFile);
    private final ActivityResultLauncher<String[]> importFile = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::readFile);

    /** Holds the prepared text while the document picker is open, including across rotation. */
    public static final class PendingLog extends ViewModel {
        String text;
        ScreenDebugCapture.Run run;
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        logExport = new ViewModelProvider(this).get(PendingLog.class);
        captureSettings = new ScreenCaptureSettings(this);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setContentView(R.layout.activity_screen_debug);
        SettingsPage.applyInsets(findViewById(R.id.screen_debug_root), true);
        service = findViewById(R.id.debug_service);
        status = findViewById(R.id.debug_capture_status);
        badge = findViewById(R.id.debug_capture_badge);
        counters = findViewById(R.id.debug_capture_counters);
        preview = findViewById(R.id.debug_preview);
        preview.setText(R.string.screen_debug_preview_empty);
        target = findViewById(R.id.debug_package);
        keyword = findViewById(R.id.debug_draft);
        ruleResult = findViewById(R.id.debug_rule_result);
        ScreenDebugCapture.Run current = ScreenDebugCapture.latest();
        if (current != null) target.setText(current.selected);

        findViewById(R.id.debug_select_app).setOnClickListener(v -> chooseApp());
        findViewById(R.id.debug_launch_demo).setOnClickListener(v -> openDemo());
        findViewById(R.id.debug_import_xml).setOnClickListener(v -> chooseImport());
        start = bindButton(R.id.debug_start, this::captureOrStop);
        saveLog = bindButton(R.id.debug_save_log, () -> prepareLog(true));
        copyLog = bindButton(R.id.debug_copy_log, () -> prepareLog(false));
        copy = bindButton(R.id.debug_copy_xml, this::copyXml);
        save = bindButton(R.id.debug_export, this::exportXml);
        verify = bindButton(R.id.debug_verify, () -> replay(false));
        verifyDraft = bindButton(R.id.debug_verify_draft, () -> replay(true));
        addRule = bindButton(R.id.debug_add_rule, this::addDraft);
        clear = bindButton(R.id.debug_clear, () -> {
            ScreenDebugCapture.clear();
            resetReplay();
            render();
        });
        direction = findViewById(R.id.debug_draft_type);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.item_form_spinner_value,
                new String[]{getString(R.string.screen_debug_expense), getString(R.string.screen_debug_income)});
        adapter.setDropDownViewResource(R.layout.item_form_spinner_option);
        direction.setAdapter(adapter);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        ScreenDebugCapture.Run run = ScreenDebugCapture.latest();
        if (run != null && !run.hasFinalReport()) run.note("host.ui.resume", "Returned to Tally diagnostics");
        refresh.run();
    }

    @Override protected void onPause() {
        main.removeCallbacks(refresh);
        super.onPause();
    }

    @Override protected void onDestroy() {
        resetReplay();
        files.shutdown();
        super.onDestroy();
    }

    private void render() {
        int serviceStatus = captureSettings.isEnabled() ? ExperimentalAccountingService.getStatus()
                : SelectToSpeakService.isConnected() ? R.string.screen_debug_accessibility_ready
                : R.string.screen_debug_accessibility_off;
        updateText(service, getString(serviceStatus));
        ScreenDebugCapture.Run run = ScreenDebugCapture.latest();
        boolean active = run != null && run.isActive();
        String xml = run == null ? null : run.getXml();
        updateText(start, getString(active ? R.string.screen_debug_stop : R.string.screen_debug_start));
        updateText(badge, getString(xml != null ? R.string.screen_debug_state_captured
                : active ? R.string.screen_debug_state_active
                : run != null ? R.string.screen_debug_state_ended : R.string.screen_debug_state_idle));
        status.setVisibility(run != null || preparingLog ? View.VISIBLE : View.GONE);
        updateText(status, preparingLog ? getString(R.string.screen_debug_collecting_log) : captureStatus(run));
        saveLog.setEnabled(!preparingLog);
        copyLog.setEnabled(!preparingLog);
        start.setEnabled(!preparingLog);
        boolean showCounters = run != null && run.source != ScreenDebugCapture.Source.IMPORTED;
        counters.setVisibility(showCounters ? View.VISIBLE : View.GONE);
        if (showCounters) updateText(counters, captureCounters(run));
        copy.setEnabled(xml != null && !preparingLog);
        save.setEnabled(xml != null && !preparingLog);
        clear.setEnabled(run != null && !preparingLog);
        verify.setEnabled(xml != null && !replayInFlight && !preparingLog);
        verifyDraft.setEnabled(xml != null && !replayInFlight && !preparingLog);
        ruleResult.setVisibility(ruleResult.length() == 0 ? View.GONE : View.VISIBLE);
        addRule.setVisibility(canAddDraft() ? View.VISIBLE : View.GONE);
        String content = xml == null ? run == null ? null : run.getNodeSummary() : xml;
        if (!Objects.equals(renderedXml, content)) {
            renderedXml = content;
            preview.setText(content == null ? getString(R.string.screen_debug_preview_empty) : content.length() <= PREVIEW_LIMIT ? content
                    : content.substring(0, PREVIEW_LIMIT) + getString(R.string.screen_debug_preview_tail));
        }
    }

    private String captureStatus(ScreenDebugCapture.Run run) {
        if (run == null) return getString(R.string.screen_debug_ready);
        if (run.getXml() != null) return getString(R.string.screen_debug_captured, run.selected);
        if (run.isRejected()) return getString(R.string.screen_debug_arm_failed);
        if (run.isLost()) return getString(R.string.screen_debug_reader_lost);
        if (run.isLocked()) return getString(R.string.screen_debug_locked);
        if (run.isArming()) return getString(R.string.screen_debug_arming);
        if (run.getErrors() > 0) return getString(R.string.screen_debug_capture_errors);
        if (run.getUnreadable() > 0) return getString(R.string.screen_debug_unreadable);
        if (run.getFocused() > 0) return getString(R.string.screen_debug_focus_no_nodes);
        if (run.getNoFocus() > 0 && run.getNoFocus() == run.getProbed()) {
            return getString(R.string.screen_debug_focus_unknown);
        }
        if (run.isActive()) return getString(R.string.screen_debug_waiting_for_window, run.selected);
        return getString(R.string.screen_debug_window_not_seen, run.selected);
    }

    private String captureCounters(ScreenDebugCapture.Run run) {
        return getString(run.source == ScreenDebugCapture.Source.ACCESSIBILITY
                        ? R.string.screen_debug_accessibility_counters : R.string.screen_debug_counters,
                run.getProbed(), run.getFocused(), run.getNoFocus(), run.getUnreadable(), run.getErrors());
    }

    private void chooseApp() {
        String selected = target.getText() == null ? "" : target.getText().toString().trim();
        ScreenDebugAppPicker.show(this, files, selected, pkg -> {
            target.setText(pkg);
            target.clearFocus();
        });
    }

    private void captureOrStop() {
        ScreenDebugCapture.Run run = ScreenDebugCapture.latest();
        if (run != null && run.isActive()) {
            ScreenDebugCapture.stop();
            render();
        } else {
            String pkg = selectedPackage();
            if (pkg != null) beginCapture(pkg, getPackageManager().getLaunchIntentForPackage(pkg));
        }
    }

    private void openDemo() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(DEMO);
        if (launch == null) { toast(R.string.screen_debug_demo_missing); return; }
        target.setText(DEMO);
        beginCapture(DEMO, launch);
    }

    private void beginCapture(String pkg, Intent launch) {
        if (preparingLog) return;
        if (!captureSettings.isEnabled()) {
            ScreenDebugCapture.Run run = SelectToSpeakService.startDiagnostic(pkg);
            if (run == null) {
                toast(R.string.screen_debug_accessibility_required);
                return;
            }
            resetReplay();
            launchCaptureTarget(run, launch);
            return;
        }
        if (!ExperimentalAccountingService.isRunning()) {
            boolean starting = ExperimentalAccountingService.isConnecting()
                    || ExperimentalAccountingService.startFromUser(this);
            toast(starting ? R.string.screen_debug_reader_connecting : R.string.screen_debug_not_running);
            return;
        }
        resetReplay();
        ScreenDebugCapture.Run run = ScreenDebugCapture.start(pkg, ScreenDebugCapture.Source.EXPERIMENTAL);
        ExperimentalAccountingService.armDiagnostic(run, () -> launchCaptureTarget(run, launch));
        render();
    }

    private void launchCaptureTarget(ScreenDebugCapture.Run run, Intent launch) {
        if (isFinishing() || isDestroyed()
                || !getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            if (ScreenDebugCapture.latest() == run) ScreenDebugCapture.stop();
            return;
        }
        render();
        if (!run.isActive()) return;
        if (launch == null) {
            run.note("host.ui.launch_target", "No launchable activity; waiting for manual app switch");
            toast(R.string.screen_debug_manual_open);
            return;
        }
        try {
            run.note("host.ui.launch_target", "Opening " + run.selected);
            startActivity(launch);
        } catch (ActivityNotFoundException | SecurityException e) {
            run.error("host.ui.launch_target", e);
            toast(R.string.screen_debug_manual_open);
        }
    }

    private String selectedPackage() {
        String pkg = target.getText() == null ? "" : target.getText().toString().trim();
        if (ScreenCapturePolicy.canInspect(pkg, getPackageName())) return pkg;
        toast(R.string.screen_debug_bad_package);
        return null;
    }

    private ScreenDebugCapture.Run finishedCapture() {
        ScreenDebugCapture.stop();
        render();
        ScreenDebugCapture.Run run = ScreenDebugCapture.latest();
        return run == null || run.getXml() == null ? null : run;
    }

    private void copyXml() {
        ScreenDebugCapture.Run run = finishedCapture();
        if (run == null) return;
        copyToClipboard("Tally screen XML", run.getXml());
    }

    private void prepareLog(boolean toFile) {
        if (preparingLog) return;
        pendingLogRun = ScreenDebugCapture.latest();
        preparingLog = true;
        logToFile = toFile;
        logWaitUntil = SystemClock.elapsedRealtime() + LOG_WAIT_MS;
        if (pendingLogRun != null) ScreenDebugCapture.stop();
        deliverLogWhenReady();
        render();
    }

    private void deliverLogWhenReady() {
        ScreenDebugCapture.Run run = pendingLogRun;
        if (run != null && !run.hasFinalReport()) {
            if (SystemClock.elapsedRealtime() < logWaitUntil) return;
            run.note("host.export.timeout", "Final reader report still pending after 15 s; this export may be partial");
        }
        pendingLogRun = null;
        preparingLog = false;
        String content = ScreenDebugCapture.createLog(this, run);
        if (!logToFile) {
            if (content.length() > LOG_CLIPBOARD_LIMIT) {
                toast(R.string.screen_debug_log_use_save);
            } else {
                try { copyToClipboard("Tally diagnostics", content); }
                catch (RuntimeException e) {
                    if (run != null) run.error("host.export.clipboard", e);
                    toast(R.string.screen_debug_log_use_save);
                }
            }
            return;
        }
        logExport.text = content;
        logExport.run = run;
        try { exportLog.launch("Tally-screen-diagnostics-" + System.currentTimeMillis() + ".txt"); }
        catch (RuntimeException e) {
            logExport.text = null;
            logExport.run = null;
            if (run != null) run.error("host.export.picker", e);
            toast(R.string.screen_debug_log_file_error);
        }
    }

    private void saveLogFile(Uri uri) {
        String content = logExport.text;
        ScreenDebugCapture.Run run = logExport.run;
        logExport.text = null;
        logExport.run = null;
        if (uri == null) return;
        if (content == null) { toast(R.string.screen_debug_log_file_error); return; }
        saveText(uri, content, run, true);
    }

    private void copyToClipboard(String label, String content) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData clip = ClipData.newPlainText(label, content);
        if (Build.VERSION.SDK_INT >= 33) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
        }
        if (clipboard != null) {
            clipboard.setPrimaryClip(clip);
            toast(R.string.screen_debug_copied);
        }
    }

    private void exportXml() {
        ScreenDebugCapture.Run run = finishedCapture();
        if (run == null) return;
        exportXml = run.getXml();
        export.launch(run.selected + "-screen-" + XML_EXPORT_TIMESTAMP.format(LocalDateTime.now()) + ".xml");
    }

    private void saveFile(Uri uri) {
        ScreenDebugCapture.Run run = ScreenDebugCapture.latest();
        String xml = exportXml != null ? exportXml : run == null ? null : run.getXml();
        exportXml = null;
        if (uri != null && xml != null) saveText(uri, xml, run, false);
    }

    private void saveText(Uri uri, String content, ScreenDebugCapture.Run run, boolean log) {
        files.execute(() -> {
            try {
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IOException("Document provider returned no output stream");
                    out.write(content.getBytes(StandardCharsets.UTF_8));
                }
                main.post(() -> toast(R.string.screen_debug_saved));
            } catch (Exception e) {
                if (run != null) run.error(log ? "host.export.save_log" : "host.export.save_xml", e);
                main.post(() -> toast(log ? R.string.screen_debug_log_file_error : R.string.screen_debug_file_error));
            }
        });
    }

    private void chooseImport() {
        if (preparingLog) return;
        String pkg = selectedPackage();
        if (pkg == null) return;
        ScreenDebugCapture.stop();
        importPackage = pkg;
        importFile.launch(new String[]{"application/xml", "text/xml", "text/plain"});
    }

    private void readFile(Uri uri) {
        if (uri == null) return;
        String pkg = importPackage == null ? selectedPackage() : importPackage;
        importPackage = null;
        if (pkg == null) return;
        files.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new java.io.IOException();
                InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
                StringBuilder xml = new StringBuilder();
                char[] buffer = new char[4096];
                for (int n; (n = reader.read(buffer)) >= 0;) {
                    if (xml.length() + n > ScreenHierarchyCodec.MAX_CHARS) throw new java.io.IOException();
                    xml.append(buffer, 0, n);
                }
                String content = xml.toString();
                ScreenNode tree = ScreenHierarchyCodec.decodeDiagnostic(content, pkg, getPackageName());
                main.post(() -> {
                    if (isDestroyed()) return;
                    resetReplay();
                    ScreenDebugCapture.showImported(pkg, content, tree);
                    target.setText(pkg);
                    render();
                });
            } catch (Exception ignored) { main.post(() -> toast(R.string.screen_debug_file_error)); }
        });
    }

    private void replay(boolean draft) {
        ScreenDebugCapture.Run run = finishedCapture();
        if (run == null || replayInFlight) return;
        String word = draft && keyword.getText() != null ? keyword.getText().toString().trim() : null;
        if (draft && (word == null || word.isEmpty())) { toast(R.string.screen_debug_draft_empty); return; }
        int type = draft ? direction.getSelectedItemPosition() : 0;
        ScreenNode tree = run.getSnapshot();
        String pkg = !draft && DEMO.equals(run.selected) ? DEMO_RULE_PACKAGE : run.selected;
        try {
            if (engine == null || replaySnapshot != tree || !Objects.equals(word, replayKeyword) || type != replayType) {
                resetReplay();
                replayRun = run;
                replaySnapshot = tree;
                replayKeyword = word;
                replayType = type;
                run.note("replay.setup", "Preparing in-memory test ledger");
                engine = ScreenAccountingEngine.forReplay(this, word, type, observer(run));
            }
            replayInFlight = true;
            run.note("replay.start", (draft ? "Draft trigger (type=" + type + ")" : "Existing rules")
                    + "; rule package=" + pkg);
            ruleResult.setText(R.string.screen_debug_verifying);
            render();
            main.postDelayed(noMatch, 1600);
            engine.analyze(pkg.equals(run.selected) ? tree : withPackage(tree, pkg));
        } catch (RuntimeException | LinkageError e) {
            run.error("replay.start", e);
            finishReplay(R.string.screen_debug_error);
        }
    }

    private ScreenAccountingEngine.ReplayObserver observer(ScreenDebugCapture.Run run) {
        return new ScreenAccountingEngine.ReplayObserver() {
            @Override public void onMatched() {
                main.removeCallbacks(noMatch);
                run.note("replay.match", "Matched; entering local classification");
                ruleResult.setText(R.string.screen_debug_matched);
            }
            @Override public void onClassified(boolean silent) {
                run.note("replay.classify", "Local classification completed; "
                        + (silent ? "silent save" : "confirmation required"));
            }
            @Override public void onConfirmation(String record, Runnable accept, Runnable reject) {
                run.note("replay.confirmation", "Test confirmation shown");
                confirmation = new AlertDialog.Builder(ScreenDebugActivity.this)
                        .setTitle(R.string.screen_debug_confirm).setMessage(record)
                        .setPositiveButton(R.string.screen_debug_confirm_button, (d, which) -> {
                            run.note("replay.confirmation", "Confirmed; writing test transaction");
                            try { accept.run(); }
                            catch (RuntimeException | LinkageError e) { onFailure("replay.confirmation.accept", e); }
                        })
                        .setNegativeButton(android.R.string.cancel, (d, which) -> {
                            reject.run(); finishReplay(R.string.screen_debug_cancelled);
                        }).setOnCancelListener(d -> {
                            reject.run(); finishReplay(R.string.screen_debug_cancelled);
                        }).show();
            }
            @Override public void onSaved(Transaction tx) {
                main.removeCallbacks(noMatch);
                replayInFlight = false;
                run.note("replay.write", "Test transaction saved; total=" + (testWrites + 1));
                ruleResult.setText(getString(R.string.screen_debug_result,
                        tx.currencySymbol, tx.amount, tx.category, ++testWrites));
                render();
            }
            @Override public void onFailure(String stage, Throwable error) {
                run.error(stage, error);
                finishReplay(R.string.screen_debug_error);
            }
        };
    }

    private static ScreenNode withPackage(ScreenNode node, String pkg) {
        List<ScreenNode> children = new ArrayList<>(node.getChildCount());
        for (int i = 0; i < node.getChildCount(); i++) children.add(withPackage(node.getChild(i), pkg));
        return new ScreenNode(node.getText(), node.getContentDescription(), pkg, node.getClassName(), node.getViewIdResourceName(), children);
    }

    private boolean canAddDraft() {
        return replaySnapshot != null && replayKeyword != null && testWrites > 0 && !replayInFlight
                && !DEMO.equals(replaySnapshot.getPackageName());
    }

    private void addDraft() {
        if (!canAddDraft()) return;
        String pkg = replaySnapshot.getPackageName(), word = replayKeyword;
        int type = replayType;
        new AlertDialog.Builder(this).setTitle(R.string.screen_debug_add_rule)
                .setMessage(getString(R.string.screen_debug_publish, pkg, word))
                .setPositiveButton(R.string.screen_debug_add_rule, (d, which) -> {
                    boolean added = KeywordManager.publishVerifiedKeyword(this, pkg, type, word);
                    toast(added ? R.string.screen_debug_rule_added : R.string.screen_debug_rule_limit);
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void finishReplay(int message) {
        main.removeCallbacks(noMatch);
        replayInFlight = false;
        if (replayRun != null) replayRun.note("replay.result", getString(message));
        ruleResult.setText(message);
        render();
    }

    private void resetReplay() {
        main.removeCallbacks(noMatch);
        if (confirmation != null) { confirmation.dismiss(); confirmation = null; }
        if (engine != null) { engine.close(); engine = null; }
        if (replayInFlight && replayRun != null) replayRun.note("replay.stop", "Replay ended before completion");
        replayRun = null;
        replaySnapshot = null;
        replayKeyword = null;
        testWrites = 0;
        replayInFlight = false;
        ruleResult.setText("");
    }

    private void toast(int id) { if (!isDestroyed()) Toast.makeText(this, id, Toast.LENGTH_SHORT).show(); }

    private static void updateText(TextView view, CharSequence text) {
        if (!android.text.TextUtils.equals(text, view.getText())) view.setText(text);
    }

    private MaterialButton bindButton(int id, Runnable action) {
        MaterialButton button = findViewById(id);
        button.setOnClickListener(v -> action.run());
        return button;
    }
}
