package com.example.budgetapp.util;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;

/** Changes only Tally's own tasks, without overriding activities excluded by the manifest. */
public final class RecentTaskSettings {
    private static final String KEY_HIDE_RECENTS = "hide_in_recent_tasks";

    private RecentTaskSettings() { }

    public static boolean isHidden(Context context) {
        return preferences(context).getBoolean(KEY_HIDE_RECENTS, false);
    }

    public static void setHidden(Context context, boolean hidden) {
        preferences(context).edit().putBoolean(KEY_HIDE_RECENTS, hidden).apply();
        setTaskExclusion(context, hidden);
    }

    public static void apply(Context context) {
        if (isHidden(context)) setTaskExclusion(context, true);
    }

    private static void setTaskExclusion(Context context, boolean hide) {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) return;
        for (ActivityManager.AppTask task : manager.getAppTasks()) {
            try {
                Intent base = task.getTaskInfo().baseIntent;
                ComponentName root = base == null ? null : base.getComponent();
                if (root == null || !context.getPackageName().equals(root.getPackageName())) continue;
                boolean alreadyHidden = (base.getFlags() & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) != 0;
                if (alreadyHidden == hide) continue;
                ActivityInfo activity = context.getPackageManager().getActivityInfo(root, 0);
                if ((activity.flags & ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS) == 0) {
                    task.setExcludeFromRecents(hide);
                }
            } catch (PackageManager.NameNotFoundException | IllegalArgumentException | SecurityException ignored) {
                // A task may have finished while reading the task list.
            }
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
    }
}
