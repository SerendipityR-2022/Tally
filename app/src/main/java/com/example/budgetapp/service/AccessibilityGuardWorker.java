package com.example.budgetapp.service;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.budgetapp.util.ShizukuManager;

import java.util.concurrent.TimeUnit;

public class AccessibilityGuardWorker extends Worker {

    private static final String WORK_NAME = "AccessibilityGuardWork";

    public AccessibilityGuardWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();

        // Respect both explicit opt-in and the experimental source; never silently re-enable accessibility.
        ShizukuManager.restoreKeepAliveIfAllowed(context);
        return Result.success();
    }

    /**
     * 启动周期性保活巡检任务（系统限制最短 15 分钟）
     */
    public static void startPeriodicWork(Context context) {
        PeriodicWorkRequest guardRequest = new PeriodicWorkRequest.Builder(
                AccessibilityGuardWorker.class,
                15, TimeUnit.MINUTES
        ).setConstraints(Constraints.NONE).build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                guardRequest
        );
    }
}