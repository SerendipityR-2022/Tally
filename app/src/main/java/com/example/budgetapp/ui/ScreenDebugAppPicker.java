package com.example.budgetapp.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.budgetapp.R;
import com.example.budgetapp.util.ScreenCapturePolicy;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

final class ScreenDebugAppPicker {
    private ScreenDebugAppPicker() { }

    static void show(AppCompatActivity activity, Executor worker, String selected, Consumer<String> onPick) {
        View content = activity.getLayoutInflater().inflate(R.layout.dialog_screen_debug_app_picker, null);
        View results = content.findViewById(R.id.debug_app_results);
        ViewGroup.LayoutParams params = results.getLayoutParams();
        int screenHeight = activity.getResources().getConfiguration().screenHeightDp;
        int height = Math.max(112, Math.min(320, screenHeight - 236));
        params.height = Math.round(height * activity.getResources().getDisplayMetrics().density);
        results.setLayoutParams(params);

        TextInputEditText search = content.findViewById(R.id.debug_app_search);
        ListView list = content.findViewById(R.id.debug_app_list);
        TextView empty = content.findViewById(R.id.debug_app_empty);
        PackageManager manager = activity.getPackageManager();
        AppAdapter adapter = new AppAdapter(manager, selected);
        list.setAdapter(adapter);
        list.setEmptyView(empty);

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle(R.string.screen_debug_choose)
                .setView(content).setNegativeButton(android.R.string.cancel, null).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            onPick.accept(adapter.getItem(position).packageName);
            dialog.dismiss();
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                adapter.filter(text);
                list.setSelection(0);
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        search.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            InputMethodManager keyboard = activity.getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(view.getWindowToken(), 0);
            return true;
        });
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
        content.requestFocus();
        String owner = activity.getPackageName();
        worker.execute(() -> {
            List<AppEntry> installed = loadApps(manager, owner);
            activity.runOnUiThread(() -> {
                if (!dialog.isShowing() || activity.isDestroyed()) return;
                empty.setText(R.string.screen_debug_app_no_match);
                adapter.apps = installed;
                adapter.filter(search.getText());
            });
        });
    }

    private static List<AppEntry> loadApps(PackageManager manager, String owner) {
        List<AppEntry> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo info : manager.queryIntentActivities(launcher, 0)) {
            if (info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (!ScreenCapturePolicy.canInspect(pkg, owner) || !seen.add(pkg)) continue;
            CharSequence label = info.loadLabel(manager);
            apps.add(new AppEntry(info, label == null ? pkg : label.toString(), pkg));
        }
        Collator order = Collator.getInstance();
        apps.sort((a, b) -> {
            int byLabel = order.compare(a.label, b.label);
            return byLabel != 0 ? byLabel : a.packageName.compareTo(b.packageName);
        });
        return apps;
    }

    private static final class AppEntry {
        final ResolveInfo info;
        final String label, packageName, searchLabel, searchPackage;

        AppEntry(ResolveInfo info, String label, String packageName) {
            this.info = info;
            this.label = label;
            this.packageName = packageName;
            searchLabel = label.toLowerCase(Locale.ROOT);
            searchPackage = packageName.toLowerCase(Locale.ROOT);
        }
    }

    private static final class AppAdapter extends BaseAdapter {
        private final PackageManager manager;
        private final String selected;
        private List<AppEntry> apps = Collections.emptyList();
        private final List<AppEntry> matches = new ArrayList<>();

        AppAdapter(PackageManager manager, String selected) {
            this.manager = manager;
            this.selected = selected;
        }

        void filter(CharSequence text) {
            String query = text == null ? "" : text.toString().trim().toLowerCase(Locale.ROOT);
            matches.clear();
            for (AppEntry app : apps) {
                if (app.searchLabel.contains(query) || app.searchPackage.contains(query)) matches.add(app);
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return matches.size(); }
        @Override public AppEntry getItem(int position) { return matches.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View row, ViewGroup parent) {
            if (row == null) row = View.inflate(parent.getContext(), R.layout.item_screen_debug_app, null);
            AppEntry entry = getItem(position);
            ((ImageView) row.findViewById(R.id.debug_app_icon)).setImageDrawable(entry.info.loadIcon(manager));
            ((TextView) row.findViewById(R.id.debug_app_label)).setText(entry.label);
            ((TextView) row.findViewById(R.id.debug_app_package)).setText(entry.packageName);
            row.findViewById(R.id.debug_app_selected).setVisibility(
                    selected.equals(entry.packageName) ? View.VISIBLE : View.GONE);
            return row;
        }
    }
}
