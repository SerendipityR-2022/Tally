package com.example.budgetapp.ui;

import android.content.Intent;
import android.Manifest;
import android.app.NotificationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.budgetapp.KeywordSettingActivity;
import com.example.budgetapp.R;
import com.example.budgetapp.util.AssistantConfig;
import com.example.budgetapp.util.KeywordManager;
import com.example.budgetapp.util.ShizukuManager;
import com.example.budgetapp.service.ExperimentalAccountingService;
import com.example.budgetapp.service.ScreenAccountingResultNotification;
import com.example.budgetapp.util.ScreenCaptureSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import rikka.shizuku.Shizuku;

public class AssistantManagerActivity extends AppCompatActivity {

    private AssistantConfig config;

    // 使用 SwitchCompat
    private SwitchCompat switchAutoTrack;

    //    private SwitchCompat switchRefundMonitor;

    private RecyclerView rvKeywords;
    private KeywordAdapter adapter;

    // 声明变量
    private List<KeywordItem> dataList = new ArrayList<>();

    private static final int REQUEST_KEEP_ALIVE = 1001;
    private static final int REQUEST_EXPERIMENTAL = 1002;
    private static final int REQUEST_OVERLAY = 1003;
    private static final int REQUEST_NOTIFICATIONS = 1004;
    private static final int REQUEST_RESULT_NOTIFICATION = 1005;

    private ScreenCaptureSettings captureSettings;
    private SwitchCompat switchShizukuKeepAlive;
    private SwitchCompat switchExperimental;
    private SwitchCompat switchSilentAccounting;
    private SwitchCompat switchResultNotification;
    private TextView tvShizukuStatus;
    private TextView tvExperimentalStatus;
    private TextView tvAutoTrackSource;
    private boolean rendering;
    private boolean resumed;
    private boolean startExperimentalOnResume;
    private int pendingShizukuRequest;
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshStatus = new Runnable() {
        @Override public void run() {
            updateShizukuStatusDisplay();
            statusHandler.postDelayed(this, 1000);
        }
    };

    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> {
        if (requestCode != pendingShizukuRequest || pendingShizukuRequest == 0) return;
        pendingShizukuRequest = 0;
        if (grantResult == PackageManager.PERMISSION_GRANTED && ShizukuManager.hasShizukuPermission()) {
            if (requestCode == REQUEST_KEEP_ALIVE) enableKeepAlive();
            else continueExperimentalWhenResumed();
        } else {
            Toast.makeText(this, R.string.experimental_auth_denied, Toast.LENGTH_SHORT).show();
            updateShizukuStatusDisplay();
        }
    };

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::updateShizukuStatusDisplay;
    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> {
        pendingShizukuRequest = 0;
        startExperimentalOnResume = false;
        updateShizukuStatusDisplay();
    };

    private static class KeywordItem implements Comparable<KeywordItem> {
        String packageName;
        String appName;
        String text;
        int type;

        KeywordItem(String pkg, String appName, String text, int type) {
            this.packageName = pkg;
            this.appName = appName;
            this.text = text;
            this.type = type;
        }

        @Override
        public int compareTo(KeywordItem o) {
            int appCompare = this.appName.compareTo(o.appName);
            if (appCompare != 0) return appCompare;
            if (this.type != o.type) return Integer.compare(this.type, o.type);
            return this.text.compareTo(o.text);
        }
    }

    private static class AppSpinnerItem {
        String packageName;
        String appName;
        AppSpinnerItem(String pkg, String name) { this.packageName = pkg; this.appName = name; }
        @Override public String toString() { return appName; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. 沉浸式设置
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        setContentView(R.layout.activity_assistant_manager);
        findViewById(R.id.btnScreenDebug).setOnClickListener(view ->
                startActivity(new Intent(this, ScreenDebugActivity.class)));

        // 2. 适配内边距
        View rootLayout = findViewById(R.id.root_layout);
        if (rootLayout != null) {
            SettingsPage.applyInsets(rootLayout);
        }

        config = new AssistantConfig(this);
        // 确保默认关键字已初始化
        KeywordManager.initDefaults(this);

        captureSettings = new ScreenCaptureSettings(this);
        if (savedInstanceState != null) {
            pendingShizukuRequest = savedInstanceState.getInt("pending_shizuku_request");
            startExperimentalOnResume = savedInstanceState.getBoolean("start_experimental_on_resume");
        }
        initViews();
        registerShizukuListeners();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        loadData();
        if (startExperimentalOnResume) continueExperimentalWhenResumed();
        refreshStatus.run();
    }

    @Override protected void onPause() {
        resumed = false;
        statusHandler.removeCallbacks(refreshStatus);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle state) {
        state.putInt("pending_shizuku_request", pendingShizukuRequest);
        state.putBoolean("start_experimental_on_resume", startExperimentalOnResume);
        super.onSaveInstanceState(state);
    }

    private void initViews() {
        findViewById(R.id.btn_auto_category_settings).setOnClickListener(view ->
                startActivity(new Intent(this, AutoCategorySettingsActivity.class)));
        findViewById(R.id.btn_auto_track_log).setOnClickListener(view ->
                startActivity(new Intent(this, AutoTrackLogActivity.class)));
        switchAutoTrack = findViewById(R.id.switchAutoTrack);
        switchSilentAccounting = findViewById(R.id.switchSilentAccounting);
        switchSilentAccounting.setChecked(captureSettings.isSilentAccountingEnabled());
        switchSilentAccounting.setOnCheckedChangeListener((button, checked) -> {
            if (rendering) return;
            captureSettings.setSilentAccountingEnabled(checked);
            updateShizukuStatusDisplay();
        });
        switchResultNotification = findViewById(R.id.switchAccountingResultNotification);
        switchResultNotification.setChecked(captureSettings.isResultNotificationEnabled());
        switchResultNotification.setOnCheckedChangeListener((button, checked) -> {
            if (rendering) return;
            if (!checked || ScreenAccountingResultNotification.isAllowed(this)) {
                captureSettings.setResultNotificationEnabled(checked);
                return;
            }
            updateShizukuStatusDisplay();
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_RESULT_NOTIFICATION);
            } else {
                showResultNotificationSettings();
            }
        });

        switchAutoTrack.setChecked(config.isEnabled());

        switchAutoTrack.setOnCheckedChangeListener((button, checked) -> {
            if (rendering) return;
            if (!checked) {
                config.setEnabled(false);
                ExperimentalAccountingService.stop(this);
            } else if (captureSettings.isEnabled()) {
                requestShizuku(REQUEST_EXPERIMENTAL);
            } else {
                config.setEnabled(true);
                if (ShizukuManager.shouldRestoreAccessibility(this)) {
                    ShizukuManager.restoreKeepAliveAsync(this);
                } else {
                    checkAccessibilityPermission();
                }
            }
            updateShizukuStatusDisplay();
        });

        rvKeywords = findViewById(R.id.rvKeywords);
        rvKeywords.setLayoutManager(new LinearLayoutManager(this));
        adapter = new KeywordAdapter();
        rvKeywords.setAdapter(adapter);

        findViewById(R.id.btnAddKeyword).setOnClickListener(v -> {
            Intent intent = new Intent(AssistantManagerActivity.this, KeywordSettingActivity.class);
            startActivity(intent);
        });

        switchShizukuKeepAlive = findViewById(R.id.switchShizukuKeepAlive);
        switchExperimental = findViewById(R.id.switchExperimentalAccounting);
        tvShizukuStatus = findViewById(R.id.tvShizukuStatus);
        tvExperimentalStatus = findViewById(R.id.tvExperimentalStatus);
        tvAutoTrackSource = findViewById(R.id.tvAutoTrackSource);
        switchShizukuKeepAlive.setOnCheckedChangeListener((button, checked) -> {
            if (rendering) return;
            if (checked) requestShizuku(REQUEST_KEEP_ALIVE);
            else {
                captureSettings.setKeepAliveEnabled(false);
                ExperimentalAccountingService.refreshRestartPolicy(this);
            }
            updateShizukuStatusDisplay();
        });
        switchExperimental.setOnCheckedChangeListener((button, checked) -> {
            if (rendering) return;
            if (checked) {
                new AlertDialog.Builder(this).setTitle(R.string.experimental_title)
                        .setMessage(R.string.experimental_consent)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.experimental_continue, (dialog, which) -> requestShizuku(REQUEST_EXPERIMENTAL))
                        .show();
            } else {
                // Disabling the new source must not silently opt the user back into accessibility.
                config.setEnabled(false);
                captureSettings.setEnabled(false);
                ExperimentalAccountingService.stop(this);
            }
            updateShizukuStatusDisplay();
        });
    }

    private void registerShizukuListeners() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionListener);
    }

    private void updateShizukuStatusDisplay() {
        boolean available = ShizukuManager.isShizukuAvailable();
        boolean authorized = available && ShizukuManager.hasShizukuPermission();
        boolean experimental = captureSettings.isEnabled();
        boolean accessibility = isAccessibilitySettingsOn();
        rendering = true;
        try {
            switchShizukuKeepAlive.setChecked(captureSettings.isKeepAliveEnabled());
            switchExperimental.setChecked(experimental);
            switchSilentAccounting.setChecked(captureSettings.isSilentAccountingEnabled());
            switchResultNotification.setChecked(captureSettings.isResultNotificationEnabled());
            switchAutoTrack.setChecked(config.isEnabled() && (experimental
                    ? authorized && (ExperimentalAccountingService.isRunning()
                        || ExperimentalAccountingService.isConnecting()) : accessibility));
        } finally { rendering = false; }
        tvAutoTrackSource.setText(experimental ? R.string.experimental_source : R.string.accessibility_source);
        tvShizukuStatus.setText(!available ? R.string.shizuku_not_running
                : !authorized ? R.string.shizuku_not_authorized
                : !captureSettings.isKeepAliveEnabled() ? R.string.shizuku_authorized
                : !config.isEnabled() ? R.string.shizuku_keep_alive_ready
                : experimental ? R.string.shizuku_experimental_keep_alive
                : accessibility ? R.string.shizuku_accessibility_active : R.string.shizuku_accessibility_waiting);
        int missingPermission = experimental && authorized
                ? ExperimentalAccountingService.getMissingPermissionStatus(this) : 0;
        tvExperimentalStatus.setText(!experimental ? R.string.experimental_off
                : !authorized ? R.string.experimental_status_auth_lost
                : missingPermission != 0 ? missingPermission
                : !config.isEnabled() ? R.string.experimental_status_stopped
                : ExperimentalAccountingService.getStatus());
    }

    private void requestShizuku(int requestCode) {
        if (!ShizukuManager.isShizukuAvailable()) {
            showShizukuNotRunningDialog();
            return;
        }
        try {
            if (Shizuku.isPreV11() || (requestCode == REQUEST_EXPERIMENTAL && Shizuku.getVersion() < 13)) {
                Toast.makeText(this, R.string.experimental_shizuku_version, Toast.LENGTH_LONG).show();
                return;
            }
            if (ShizukuManager.hasShizukuPermission()) {
                if (requestCode == REQUEST_KEEP_ALIVE) enableKeepAlive();
                else continueExperimentalWhenResumed();
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                Toast.makeText(this, R.string.experimental_authorize_in_shizuku, Toast.LENGTH_LONG).show();
            } else if (pendingShizukuRequest == 0) {
                pendingShizukuRequest = requestCode;
                Shizuku.requestPermission(requestCode);
            }
        } catch (RuntimeException e) {
            pendingShizukuRequest = 0;
            Toast.makeText(this, R.string.experimental_status_auth_lost, Toast.LENGTH_SHORT).show();
        }
    }

    private void enableKeepAlive() {
        captureSettings.setKeepAliveEnabled(true);
        ExperimentalAccountingService.refreshRestartPolicy(this);
        ShizukuManager.restoreKeepAliveAsync(this);
        updateShizukuStatusDisplay();
    }

    private void continueExperimentalWhenResumed() {
        startExperimentalOnResume = !resumed;
        if (!resumed) return;
        if (!ShizukuManager.hasShizukuPermission()) {
            updateShizukuStatusDisplay();
            return;
        }
        if (!ExperimentalAccountingService.overlayRequirementMet(this)) {
            showPermissionSettingsDialog(R.string.experimental_title, R.string.experimental_overlay_required,
                    new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())), REQUEST_OVERLAY);
            return;
        }
        if (!ExperimentalAccountingService.notificationsAllowed(this)) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            } else {
                showPermissionSettingsDialog(R.string.experimental_title, R.string.experimental_notifications_required,
                        new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()),
                        REQUEST_NOTIFICATIONS);
            }
            return;
        }
        captureSettings.setEnabled(true);
        config.setEnabled(true);
        if (!ExperimentalAccountingService.startFromUser(this)) {
            Toast.makeText(this, R.string.experimental_status_connection_failed, Toast.LENGTH_LONG).show();
        }
        updateShizukuStatusDisplay();
    }

    private void showResultNotificationSettings() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        String channel = ScreenAccountingResultNotification.CHANNEL_ID;
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        if (manager != null && manager.areNotificationsEnabled() && manager.getNotificationChannel(channel) != null) {
            intent.setAction(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, channel);
        }
        showPermissionSettingsDialog(R.string.screen_accounting_result_channel, R.string.screen_accounting_result_permission,
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()), REQUEST_RESULT_NOTIFICATION);
    }

    private void showPermissionSettingsDialog(int title, int message, Intent intent, int requestCode) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.experimental_go_to_settings, (dialog, which) -> {
                    try { startActivityForResult(intent, requestCode); }
                    catch (RuntimeException e) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
                }).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_RESULT_NOTIFICATION) {
            enableResultNotificationIfAllowed();
            return;
        }
        if ((requestCode == REQUEST_OVERLAY && ExperimentalAccountingService.overlayRequirementMet(this))
                || (requestCode == REQUEST_NOTIFICATIONS && ExperimentalAccountingService.notificationsAllowed(this))) {
            continueExperimentalWhenResumed();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_RESULT_NOTIFICATION) {
            if (!enableResultNotificationIfAllowed() && results.length > 0) showResultNotificationSettings();
            return;
        }
        if (requestCode != REQUEST_NOTIFICATIONS) return;
        if (ExperimentalAccountingService.notificationsAllowed(this)) continueExperimentalWhenResumed();
        else Toast.makeText(this, R.string.experimental_notifications_required, Toast.LENGTH_LONG).show();
    }

    private boolean enableResultNotificationIfAllowed() {
        if (!ScreenAccountingResultNotification.isAllowed(this)) return false;
        captureSettings.setResultNotificationEnabled(true);
        updateShizukuStatusDisplay();
        return true;
    }

    private void showShizukuNotRunningDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Shizuku 未运行")
                .setMessage(R.string.shizuku_start_guide)
                .setPositiveButton("打开/下载 Shizuku", (dialog, which) -> {
                    try {
                        Intent intent = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                        if (intent != null) {
                            startActivity(intent);
                        } else {
                            Intent marketIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api"));
                            startActivity(marketIntent);
                        }
                    } catch (Exception e) {
                        Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"));
                        startActivity(webIntent);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void checkAccessibilityPermission() {
        if (!isAccessibilitySettingsOn()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            View view = LayoutInflater.from(this).inflate(R.layout.dialog_permission_request, null);
            builder.setView(view);
            AlertDialog dialog = builder.create();

            if (dialog.getWindow() != null) {
                // 背景透明以显示圆角
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }

            // 绑定数据
            TextView tvTitle = view.findViewById(R.id.tv_permission_title);
            TextView tvMessage = view.findViewById(R.id.tv_permission_message);
            tvTitle.setText("需要开启辅助服务");
            tvMessage.setText(R.string.accessibility_permission_message);

            // 取消按钮：不仅关闭弹窗，还要把开关关掉
            view.findViewById(R.id.btn_cancel_permission).setOnClickListener(v -> {
                config.setEnabled(false);
                updateShizukuStatusDisplay();
                dialog.dismiss();
            });

            // 去授权按钮
            view.findViewById(R.id.btn_grant_permission).setOnClickListener(v -> {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                dialog.dismiss();
            });

            dialog.setOnCancelListener(ignored -> {
                config.setEnabled(false);
                updateShizukuStatusDisplay();
            });
            dialog.show();
        }
    }

    private boolean isAccessibilitySettingsOn() {
        return ShizukuManager.isAccessibilityRunning(this);
    }

    private void loadData() {
        dataList.clear();
        // 使用 KeywordManager 的新方法
        Map<String, Set<String>> incomeMap = KeywordManager.getIncomeKeywords(this);
        Map<String, Set<String>> expenseMap = KeywordManager.getExpenseKeywords(this);
        Map<String, String> apps = KeywordManager.getRuleApps(this);

        // 整理收入关键字
        for (Map.Entry<String, Set<String>> entry : incomeMap.entrySet()) {
            String pkg = entry.getKey();
            String appName = apps.getOrDefault(pkg, pkg);
            for (String kw : entry.getValue()) {
                dataList.add(new KeywordItem(pkg, appName, kw, KeywordManager.TYPE_INCOME));
            }
        }
        // 整理支出关键字
        for (Map.Entry<String, Set<String>> entry : expenseMap.entrySet()) {
            String pkg = entry.getKey();
            String appName = apps.getOrDefault(pkg, pkg);
            for (String kw : entry.getValue()) {
                dataList.add(new KeywordItem(pkg, appName, kw, KeywordManager.TYPE_EXPENSE));
            }
        }
        Collections.sort(dataList);
        adapter.notifyDataSetChanged();
    }

    private void showEditDialog(KeywordItem oldItem) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        // 加载更新后的布局
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_keyword, null);
        builder.setView(view);
        AlertDialog dialog = builder.create();

        // 设置透明背景，显示圆角
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        // 获取控件
        Spinner spApp = view.findViewById(R.id.sp_app);
        RadioGroup rgType = view.findViewById(R.id.rg_type);
        EditText etKeyword = view.findViewById(R.id.et_keyword);
        Button btnCancel = view.findViewById(R.id.btn_cancel);
        Button btnSave = view.findViewById(R.id.btn_save);

        // 设置 Spinner 数据
        List<AppSpinnerItem> spinnerItems = new ArrayList<>();
        Map<String, String> apps = KeywordManager.getRuleApps(this);

        int selectedIndex = 0;
        int i = 0;
        for (Map.Entry<String, String> entry : apps.entrySet()) {
            spinnerItems.add(new AppSpinnerItem(entry.getKey(), entry.getValue()));
            if (oldItem != null && entry.getKey().equals(oldItem.packageName)) {
                selectedIndex = i;
            }
            i++;
        }
        ArrayAdapter<AppSpinnerItem> spinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, spinnerItems);
        spinnerAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        spApp.setAdapter(spinnerAdapter);
        spApp.setSelection(selectedIndex);

        // 回显数据
        if (oldItem.type == KeywordManager.TYPE_EXPENSE) {
            rgType.check(R.id.rb_expense);
        } else {
            rgType.check(R.id.rb_income);
        }
        etKeyword.setText(oldItem.text);

        // 按钮事件
        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnSave.setOnClickListener(v -> {
            String newText = etKeyword.getText().toString().trim();
            if (newText.isEmpty()) {
                Toast.makeText(this, "请输入关键字", Toast.LENGTH_SHORT).show();
                return;
            }
            AppSpinnerItem selectedApp = (AppSpinnerItem) spApp.getSelectedItem();
            int newType = (rgType.getCheckedRadioButtonId() == R.id.rb_expense)
                    ? KeywordManager.TYPE_EXPENSE : KeywordManager.TYPE_INCOME;

            // 先移除旧的
            if (oldItem.type == KeywordManager.TYPE_INCOME) {
                KeywordManager.removeIncomeKeyword(this, oldItem.packageName, oldItem.text);
            } else {
                KeywordManager.removeExpenseKeyword(this, oldItem.packageName, oldItem.text);
            }

            // 添加新的
            KeywordManager.addKeyword(this, selectedApp.packageName, newType, newText);
            loadData();
            Toast.makeText(this, "修改成功", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.show();
    }

    // 替换原有的 deleteItem 方法
    private void deleteItem(KeywordItem item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_confirm_delete, null);
        builder.setView(view);
        AlertDialog dialog = builder.create();

        // 设置背景透明，以便显示圆角
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        // 初始化控件
        TextView tvTitle = view.findViewById(R.id.tv_dialog_title);
        TextView tvMsg = view.findViewById(R.id.tv_dialog_message);

        tvTitle.setText("删除关键字");
        tvMsg.setText("确定删除 \"" + item.appName + "\" 的关键字 [" + item.text + "] 吗？");

        // 绑定按钮事件
        view.findViewById(R.id.btn_dialog_cancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.btn_dialog_confirm).setOnClickListener(v -> {
            // 执行删除逻辑
            if (item.type == KeywordManager.TYPE_INCOME) {
                KeywordManager.removeIncomeKeyword(this, item.packageName, item.text);
            } else {
                KeywordManager.removeExpenseKeyword(this, item.packageName, item.text);
            }
            loadData();
            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // ===== 新增：注销 Shizuku 监听，防止内存泄露 =====
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener);
            Shizuku.removeBinderDeadListener(binderDeadListener);
            Shizuku.removeRequestPermissionResultListener(permissionListener);
        } catch (Exception ignored) {}
    }

    class KeywordAdapter extends RecyclerView.Adapter<KeywordAdapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_2, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            KeywordItem item = dataList.get(position);
            holder.text1.setText(item.text);
            holder.text1.setTextSize(16);
            holder.text1.setTextColor(ContextCompat.getColor(AssistantManagerActivity.this, R.color.text_primary));
            String typeStr = (item.type == KeywordManager.TYPE_EXPENSE) ? "支出触发词" : "收入触发词";
            String info = "[" + item.appName + "]  " + typeStr;
            holder.text2.setText(info);
            if (item.type == KeywordManager.TYPE_EXPENSE) {
                holder.text2.setTextColor(ContextCompat.getColor(AssistantManagerActivity.this, R.color.expense_green));
            } else {
                holder.text2.setTextColor(ContextCompat.getColor(AssistantManagerActivity.this, R.color.income_red));
            }
            holder.itemView.setOnClickListener(v -> showEditDialog(item));
            holder.itemView.setOnLongClickListener(v -> {
                deleteItem(item);
                return true;
            });
        }
        @Override
        public int getItemCount() { return dataList.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView text1, text2;
            VH(View v) { super(v); text1 = v.findViewById(android.R.id.text1); text2 = v.findViewById(android.R.id.text2); }
        }
    }
}
