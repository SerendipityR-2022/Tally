package com.example.budgetapp.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.budgetapp.R;
import com.example.budgetapp.util.AutoCategoryClassifier;
import com.example.budgetapp.util.AutoCategoryClassifier.Learned;
import com.example.budgetapp.util.AutoCategoryManager;
import com.example.budgetapp.util.KeywordManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LearnedCategoryActivity extends AppCompatActivity {
    private final Map<String, String> appNames = new HashMap<>();
    private final List<Learned> visible = new ArrayList<>();
    private List<Learned> records = Collections.emptyList();
    private Runnable stopObserving;
    private RecyclerView list;
    private TextView count, empty, search;
    private View clear;
    private RecordAdapter adapter;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_learned_category);
        SettingsPage.applyInsets(findViewById(R.id.root_layout), true);
        appNames.putAll(KeywordManager.getSupportedApps());
        list = findViewById(R.id.learning_records);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RecordAdapter();
        list.setAdapter(adapter);
        count = findViewById(R.id.tv_category_learning_count);
        empty = findViewById(R.id.learning_empty);
        search = findViewById(R.id.learning_search);
        clear = findViewById(R.id.btn_category_clear_learning);
        clear.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.auto_category_clear)
                .setMessage(R.string.auto_category_clear_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.auto_category_learning_clear_button, (d, which) -> {
                    AutoCategoryManager.clearLearned(this);
                    Toast.makeText(this, R.string.auto_category_cleared, Toast.LENGTH_SHORT).show();
                }).show());
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filter(); }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    @Override protected void onStart() {
        super.onStart();
        stopObserving = AutoCategoryManager.observeLearnedChanges(this, this::refresh);
        refresh();
    }

    @Override protected void onStop() {
        stopObserving.run();
        stopObserving = null;
        super.onStop();
    }

    private void refresh() {
        records = AutoCategoryManager.getLearned(this);
        count.setText(getString(R.string.auto_category_learning_count, records.size(), AutoCategoryManager.MAX_LEARNED));
        clear.setVisibility(records.isEmpty() ? View.GONE : View.VISIBLE);
        filter();
    }

    private void filter() {
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        visible.clear();
        for (Learned rule : records) {
            if (query.isEmpty() || (rule.identity + " " + rule.packageName + " " + source(rule) + " "
                    + rule.category + " " + rule.subCategory).toLowerCase(Locale.ROOT).contains(query)) visible.add(rule);
        }
        adapter.notifyDataSetChanged();
        list.setVisibility(visible.isEmpty() ? View.GONE : View.VISIBLE);
        empty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        empty.setText(records.isEmpty() ? R.string.auto_category_learning_empty : R.string.auto_category_learning_no_results);
    }

    private String source(Learned rule) {
        String pkg = rule.packageName;
        String app = appNames.computeIfAbsent(pkg, name -> {
            try {
                return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(name, 0)).toString();
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
                return name;
            }
        });
        return getString(R.string.auto_category_learning_source, app, getString(rule.type == 1
                ? R.string.auto_category_learning_income : R.string.auto_category_learning_expense));
    }

    private void edit(Learned rule) {
        View form = getLayoutInflater().inflate(R.layout.dialog_edit_learned_category, null);
        ((TextView) form.findViewById(R.id.learning_edit_source)).setText(rule.identity + "\n" + source(rule));
        List<String> names = new ArrayList<>();
        Map<String, List<String>> children = new HashMap<>();
        for (AutoCategoryClassifier.Category category : AutoCategoryManager.getCatalog(this, rule.type)) {
            names.add(category.name);
            children.put(category.name, category.children);
        }
        String[] selected = {names.contains(rule.category) ? rule.category : ""};
        String[] child = {children.getOrDefault(selected[0], Collections.emptyList()).contains(rule.subCategory)
                ? rule.subCategory : ""};
        SettingsRow childPicker = form.findViewById(R.id.learning_subcategory_picker);
        Runnable bindChild = () -> {
            List<String> options = children.getOrDefault(selected[0], Collections.emptyList());
            childPicker.setVisibility(options.isEmpty() ? View.GONE : View.VISIBLE);
            childPicker.setValue(child[0].isEmpty() ? getString(R.string.auto_category_learning_no_subcategory) : child[0]);
        };
        childPicker.setOnClickListener(v -> {
            List<String> options = children.getOrDefault(selected[0], Collections.emptyList());
            List<String> labels = new ArrayList<>();
            labels.add(getString(R.string.auto_category_learning_no_subcategory));
            labels.addAll(options);
            new MaterialAlertDialogBuilder(this).setTitle(R.string.auto_category_learning_select_subcategory)
                    .setSingleChoiceItems(labels.toArray(new String[0]), options.indexOf(child[0]) + 1, (d, which) -> {
                        child[0] = which == 0 ? "" : options.get(which - 1);
                        bindChild.run();
                        d.dismiss();
                    }).setNegativeButton(android.R.string.cancel, null).show();
        });
        bindChild.run();

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.auto_category_learning_edit)
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.record_save_changes, null).create();
        LedgerCategoryGrid picker = form.findViewById(R.id.learning_category_picker);
        picker.setLayoutManager(new GridLayoutManager(this, LedgerCategoryGrid.COLUMNS));
        CategoryAdapter choices = new CategoryAdapter(this, names, selected[0], rule.type == 0, category -> {
            if (!category.equals(selected[0])) {
                selected[0] = category;
                child[0] = "";
                bindChild.run();
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
        });
        picker.setAdapter(choices);
        picker.getLayoutParams().height = picker.heightForSpace(picker.rowHeight() *
                (getResources().getConfiguration().screenHeightDp < 480 ? 1 : 2));
        if (choices.selectedPosition() >= 0) picker.scrollToPosition(choices.selectedPosition());

        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!selected[0].isEmpty());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (AutoCategoryManager.updateLearned(this, rule, selected[0], child[0])) dialog.dismiss();
            else Toast.makeText(this, R.string.auto_category_learning_update_failed, Toast.LENGTH_SHORT).show();
        });
    }

    private void delete(Learned rule) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.auto_category_learning_delete)
                .setMessage(getString(R.string.auto_category_learning_delete_message, rule.identity))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.auto_category_learning_delete_button,
                        (d, which) -> AutoCategoryManager.deleteLearned(this, rule)).show();
    }

    private final class RecordAdapter extends RecyclerView.Adapter<RecordHolder> {
        @NonNull @Override public RecordHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new RecordHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_learned_category, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull RecordHolder holder, int position) {
            Learned rule = visible.get(position);
            holder.merchant.setText(rule.identity);
            holder.category.setText(rule.subCategory.isEmpty() ? rule.category
                    : getString(R.string.auto_category_learning_category_path, rule.category, rule.subCategory));
            holder.source.setText(source(rule));
            LedgerCategory.bind(holder.icon, LedgerCategory.icon(LearnedCategoryActivity.this, rule.category, rule.type));
            holder.edit.setContentDescription(getString(R.string.auto_category_learning_edit_action, rule.identity)
                    + "，" + holder.category.getText() + "，" + holder.source.getText());
            holder.edit.setOnClickListener(v -> edit(rule));
            holder.delete.setContentDescription(getString(R.string.auto_category_learning_delete_action, rule.identity));
            holder.delete.setOnClickListener(v -> delete(rule));
        }

        @Override public int getItemCount() { return visible.size(); }
    }

    private static final class RecordHolder extends RecyclerView.ViewHolder {
        final TextView merchant, category, source;
        final ImageView icon;
        final View edit, delete;

        RecordHolder(View view) {
            super(view);
            merchant = view.findViewById(R.id.learning_merchant);
            category = view.findViewById(R.id.learning_category);
            source = view.findViewById(R.id.learning_source);
            icon = view.findViewById(R.id.learning_icon);
            edit = view.findViewById(R.id.learning_edit);
            delete = view.findViewById(R.id.learning_delete);
        }
    }
}
