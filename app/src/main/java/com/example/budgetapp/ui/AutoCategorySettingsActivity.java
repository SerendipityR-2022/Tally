package com.example.budgetapp.ui;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.budgetapp.R;
import com.example.budgetapp.ai.AiConfig;
import com.example.budgetapp.util.AutoCategoryManager;
import com.example.budgetapp.util.AutoCategoryPriority;
import com.example.budgetapp.util.AutoCategoryClassifier.Source;
import com.example.budgetapp.util.CategoryManager;

public class AutoCategorySettingsActivity extends AppCompatActivity {
    private static final AccessibilityActionCompat MOVE_PRIORITY_UP =
            new AccessibilityActionCompat(R.id.action_category_priority_up, null);
    private static final AccessibilityActionCompat MOVE_PRIORITY_DOWN =
            new AccessibilityActionCompat(R.id.action_category_priority_down, null);

    private boolean binding;
    private PriorityAdapter priorityAdapter;
    private ItemTouchHelper priorityDrag;
    private SwitchCompat aiSwitch, learningSwitch, subSwitch;
    private SettingsRow aiTimeout;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        setContentView(R.layout.activity_auto_category_settings);
        View root = findViewById(R.id.auto_category_root);
        SettingsPage.applyInsets(root);
        setupPriorityList();
        aiSwitch = findViewById(R.id.switch_category_ai);
        aiTimeout = findViewById(R.id.row_category_ai_timeout);
        aiTimeout.setPaddingRelative(0, aiTimeout.getPaddingTop(), 0, aiTimeout.getPaddingBottom());
        aiTimeout.setOnClickListener(view -> chooseAiTimeout());
        learningSwitch = findViewById(R.id.switch_category_learning);
        subSwitch = findViewById(R.id.switch_category_sub);
        aiSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (binding) return;
            if (!checked) {
                AutoCategoryManager.setAiEnabled(this, false);
                refresh();
                return;
            }
            binding = true;
            aiSwitch.setChecked(false);
            binding = false;
            new AlertDialog.Builder(this).setTitle(R.string.auto_category_ai)
                    .setMessage(R.string.auto_category_ai_confirm)
                    .setPositiveButton("启用", (dialog, which) -> {
                        AutoCategoryManager.setAiEnabled(this, true);
                        refresh();
                    }).setNegativeButton("取消", null).show();
        });
        learningSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!binding) {
                AutoCategoryManager.setLearningEnabled(this, checked);
                refreshPriority();
            }
        });
        subSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!binding) {
                CategoryManager.setSubCategoryEnabled(this, checked);
                refresh();
            }
        });
        findViewById(R.id.btn_category_priority_reset).setOnClickListener(view -> {
            AutoCategoryManager.setPriority(this, AutoCategoryPriority.defaults());
            refreshPriority();
        });
        findViewById(R.id.btn_category_rules).setOnClickListener(view ->
                startActivity(new Intent(this, AiCategoryRuleActivity.class)));
        findViewById(R.id.btn_category_ai_config).setOnClickListener(view ->
                startActivity(new Intent(this, AiSettingActivity.class)));
        findViewById(R.id.btn_category_clear_learning).setOnClickListener(view ->
                new AlertDialog.Builder(this).setTitle(R.string.auto_category_clear)
                        .setMessage(R.string.auto_category_clear_message)
                        .setPositiveButton("清除", (dialog, which) -> {
                            AutoCategoryManager.clearLearned(this);
                            refresh();
                            Toast.makeText(this, R.string.auto_category_cleared, Toast.LENGTH_SHORT).show();
                        }).setNegativeButton("取消", null).show());
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        binding = true;
        aiSwitch.setChecked(AutoCategoryManager.isAiEnabled(this));
        learningSwitch.setChecked(AutoCategoryManager.isLearningEnabled(this));
        subSwitch.setChecked(CategoryManager.isSubCategoryEnabled(this));
        binding = false;
        refreshPriority();
        ((TextView) findViewById(R.id.tv_category_learning_count)).setText(
                getString(R.string.auto_category_learning_count, AutoCategoryManager.getLearned(this).size(),
                        AutoCategoryManager.MAX_LEARNED));
        ((TextView) findViewById(R.id.tv_category_sub_status)).setText(CategoryManager.isSubCategoryEnabled(this)
                ? R.string.auto_category_sub_enabled : R.string.auto_category_sub_disabled);
        boolean modelReady;
        try {
            AiConfig config = AiConfig.load(this);
            modelReady = config.enabled && config.isTextReady();
        } catch (RuntimeException ignored) {
            modelReady = false;
        }
        int aiStatus = !AutoCategoryManager.isAiEnabled(this) ? R.string.auto_category_ai_disabled
                : modelReady ? R.string.auto_category_ai_ready : R.string.auto_category_ai_unavailable;
        ((TextView) findViewById(R.id.tv_category_ai_status)).setText(aiStatus);
        updateAiTimeout();
    }

    private void chooseAiTimeout() {
        int minimum = AutoCategoryManager.MIN_AI_TIMEOUT_SECONDS;
        String[] options = new String[AutoCategoryManager.MAX_AI_TIMEOUT_SECONDS - minimum + 1];
        for (int i = 0; i < options.length; i++) {
            options[i] = getString(R.string.auto_category_ai_timeout_value, minimum + i);
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.auto_category_ai_timeout)
                .setSingleChoiceItems(options, AutoCategoryManager.getAiTimeoutSeconds(this) - minimum,
                        (dialog, which) -> {
                            AutoCategoryManager.setAiTimeoutSeconds(this, minimum + which);
                            updateAiTimeout();
                            dialog.dismiss();
                        })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void updateAiTimeout() {
        aiTimeout.setValue(getString(R.string.auto_category_ai_timeout_value,
                AutoCategoryManager.getAiTimeoutSeconds(this)));
    }

    private void setupPriorityList() {
        RecyclerView list = findViewById(R.id.layout_category_priority);
        list.setLayoutManager(new LinearLayoutManager(this));
        priorityAdapter = new PriorityAdapter();
        list.setAdapter(priorityAdapter);

        priorityDrag = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override public boolean isLongPressDragEnabled() { return false; }

            @Override public int getDragDirs(@NonNull RecyclerView recycler, @NonNull RecyclerView.ViewHolder row) {
                return priorityAdapter.canMove(row.getBindingAdapterPosition()) ? super.getDragDirs(recycler, row) : 0;
            }

            @Override public boolean canDropOver(@NonNull RecyclerView recycler, @NonNull RecyclerView.ViewHolder row,
                    @NonNull RecyclerView.ViewHolder target) {
                return priorityAdapter.canMove(target.getBindingAdapterPosition());
            }

            @Override public boolean onMove(@NonNull RecyclerView recycler, @NonNull RecyclerView.ViewHolder row,
                    @NonNull RecyclerView.ViewHolder target) {
                return priorityAdapter.move(row.getBindingAdapterPosition(), target.getBindingAdapterPosition());
            }

            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder row, int direction) { }

            @Override public void onSelectedChanged(@Nullable RecyclerView.ViewHolder row, int state) {
                super.onSelectedChanged(row, state);
                if (row != null && state == ItemTouchHelper.ACTION_STATE_DRAG) {
                    row.itemView.setActivated(true);
                } else if (state == ItemTouchHelper.ACTION_STATE_IDLE) {
                    savePriorityOrder();
                }
            }

            @Override public void clearView(@NonNull RecyclerView recycler, @NonNull RecyclerView.ViewHolder row) {
                super.clearView(recycler, row);
                row.itemView.setActivated(false);
            }
        });
        priorityDrag.attachToRecyclerView(list);
    }

    private void refreshPriority() {
        priorityAdapter.bind(AutoCategoryManager.getPriority(this), AutoCategoryManager.isAiEnabled(this),
                AutoCategoryManager.isLearningEnabled(this));
    }

    private void savePriorityOrder() {
        AutoCategoryPriority priority = priorityAdapter.order;
        if (!priority.serialize().equals(AutoCategoryManager.getPriority(this).serialize())) {
            AutoCategoryManager.setPriority(this, priority);
        }
    }

    private final class PriorityAdapter extends RecyclerView.Adapter<PriorityRow> {
        private AutoCategoryPriority order = AutoCategoryPriority.defaults();
        private boolean aiEnabled;
        private boolean learningEnabled;

        PriorityAdapter() { setHasStableIds(true); }

        void bind(AutoCategoryPriority order, boolean aiEnabled, boolean learningEnabled) {
            this.order = order;
            this.aiEnabled = aiEnabled;
            this.learningEnabled = learningEnabled;
            notifyDataSetChanged();
        }

        @Override public int getItemCount() { return order.getSources().size(); }

        @Override public long getItemId(int position) { return order.getSources().get(position).ordinal(); }

        boolean canMove(int position) { return order.canMove(position); }

        boolean move(int from, int to) {
            if (!canMove(from) || !canMove(to) || from == to) return false;
            order = order.move(from, to);
            notifyItemMoved(from, to);
            // Reuse the rows during the move while updating their rank and accessibility actions.
            notifyItemRangeChanged(Math.min(from, to), Math.abs(from - to) + 1, Boolean.TRUE);
            return true;
        }

        @NonNull @Override public PriorityRow onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            PriorityRow row = new PriorityRow(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_category_priority, parent, false));
            row.handle.setOnTouchListener((view, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && canMove(row.getBindingAdapterPosition())) {
                    priorityDrag.startDrag(row);
                }
                return false;
            });
            return row;
        }

        @Override public void onBindViewHolder(@NonNull PriorityRow row, int position) {
            Source source = order.getSources().get(position);
            String label = strategyLabel(source);
            boolean disabled = (source == Source.AI && !aiEnabled) || (source == Source.LEARNED && !learningEnabled);
            row.rank.setText(Integer.toString(position + 1));
            row.label.setText(disabled ? getString(R.string.auto_category_strategy_off, label) : label);
            row.handle.setVisibility(canMove(position) ? View.VISIBLE : View.INVISIBLE);
            row.handle.setContentDescription(getString(R.string.auto_category_strategy_drag, label));
            bindMoveAction(row, position, -1, MOVE_PRIORITY_UP, getString(R.string.auto_category_strategy_up, label));
            bindMoveAction(row, position, 1, MOVE_PRIORITY_DOWN, getString(R.string.auto_category_strategy_down, label));
        }

        private void bindMoveAction(PriorityRow row, int position, int delta, AccessibilityActionCompat action,
                String actionLabel) {
            if (!canMove(position) || !canMove(position + delta)) {
                ViewCompat.removeAccessibilityAction(row.handle, action.getId());
                return;
            }
            ViewCompat.replaceAccessibilityAction(row.handle, action, actionLabel, (view, args) -> {
                int from = row.getBindingAdapterPosition();
                if (!move(from, from + delta)) return false;
                savePriorityOrder();
                return true;
            });
        }
    }

    private static final class PriorityRow extends RecyclerView.ViewHolder {
        final ImageButton handle;
        final TextView rank;
        final TextView label;

        PriorityRow(View row) {
            super(row);
            handle = row.findViewById(R.id.btn_strategy_drag);
            rank = row.findViewById(R.id.tv_priority_rank);
            label = row.findViewById(R.id.tv_priority_label);
        }
    }

    private String strategyLabel(Source source) {
        switch (source) {
            case RULE: return getString(R.string.auto_category_strategy_rule);
            case LEARNED: return getString(R.string.auto_category_strategy_learned);
            case AI: return getString(R.string.auto_category_strategy_ai);
            case LOCAL: return getString(R.string.auto_category_strategy_local);
            case HINT: return getString(R.string.auto_category_strategy_hint);
            default: return getString(R.string.auto_category_strategy_fallback);
        }
    }

}
