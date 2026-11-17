package com.example.budgetapp.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.budgetapp.R;
import com.example.budgetapp.util.CategoryIcon;
import com.example.budgetapp.util.CategoryManager;

import java.util.function.Consumer;

final class CategoryEditorDialog {
    private CategoryEditorDialog() {}

    static void show(Context context, boolean expense, String oldName, Consumer<String> saved, Runnable delete) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_category_editor, null);
        AlertDialog dialog = new AlertDialog.Builder(context).setView(view).create();
        EditText name = view.findViewById(R.id.et_category_name);
        ImageView preview = view.findViewById(R.id.category_icon_preview);
        RecyclerView grid = view.findViewById(R.id.category_icon_grid);
        Button secondary = view.findViewById(R.id.category_editor_secondary);
        boolean adding = oldName == null;
        ((TextView) view.findViewById(R.id.tv_dialog_title)).setText(
                adding ? (expense ? "添加支出分类" : "添加收入分类") : "编辑分类");
        if (!adding) {
            name.setText(oldName);
            name.setSelection(name.length());
            secondary.setText("删除");
            secondary.setTextColor(context.getColor(R.color.ledger_income));
        }
        secondary.setOnClickListener(v -> {
            dialog.dismiss();
            if (delete != null) delete.run();
        });

        CategoryIcon initial = adding ? CategoryIcon.forName("") : CategoryManager.getIcon(context, expense, oldName);
        boolean[] autoIcon = {adding};
        IconAdapter icons = new IconAdapter(initial, icon -> {
            autoIcon[0] = false;
            LedgerCategory.bind(preview, icon);
            hideKeyboard(name);
        });
        grid.setLayoutManager(new GridLayoutManager(context, 5));
        grid.setItemAnimator(null);
        grid.setAdapter(icons);
        LedgerCategory.bind(preview, initial);

        name.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!autoIcon[0]) return;
                CategoryIcon suggestion = CategoryIcon.forName(s.toString().trim());
                icons.select(suggestion);
                LedgerCategory.bind(preview, suggestion);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        name.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            hideKeyboard(name);
            return true;
        });
        view.findViewById(R.id.btn_confirm).setOnClickListener(v -> {
            String label = name.getText().toString().trim();
            if (label.isEmpty()) {
                name.setError("请输入分类名称");
                return;
            }
            if (label.contains(",") || label.contains("\n") || label.contains("\r")) {
                name.setError("分类名称不能包含英文逗号或换行");
                return;
            }
            if (CategoryManager.CUSTOM_CATEGORY.equals(label) || ("添加".equals(label) && !label.equals(oldName))) {
                name.setError("请换一个分类名称");
                return;
            }
            if (!CategoryManager.saveCategory(context, expense, oldName, label, icons.selected)) {
                name.setError("该分类已存在");
                return;
            }
            hideKeyboard(name);
            dialog.dismiss();
            saved.accept(label);
        });
        view.requestFocus();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
        if (window != null) {
            float density = context.getResources().getDisplayMetrics().density;
            int screen = context.getResources().getDisplayMetrics().widthPixels;
            window.setLayout(Math.min(Math.round(416 * density), screen - Math.round(20 * density)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static void hideKeyboard(EditText input) {
        if (!input.hasFocus()) return;
        InputMethodManager ime = input.getContext().getSystemService(InputMethodManager.class);
        if (ime != null) ime.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
    }

    private static final class IconAdapter extends RecyclerView.Adapter<IconHolder> {
        private final CategoryIcon[] choices = CategoryIcon.values();
        private final Consumer<CategoryIcon> onSelect;
        private CategoryIcon selected;

        IconAdapter(CategoryIcon initial, Consumer<CategoryIcon> onSelect) {
            selected = initial;
            this.onSelect = onSelect;
        }

        void select(CategoryIcon icon) {
            if (selected == icon) return;
            int previous = selected.ordinal();
            selected = icon;
            notifyItemChanged(previous);
            notifyItemChanged(selected.ordinal());
        }

        @NonNull @Override public IconHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new IconHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_category_icon_choice, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull IconHolder holder, int position) {
            CategoryIcon icon = choices[position];
            LedgerCategory.bind((ImageView) holder.itemView, icon);
            holder.itemView.setSelected(selected == icon);
            holder.itemView.setContentDescription("图标：" + icon.label);
            holder.itemView.setOnClickListener(v -> {
                select(icon);
                onSelect.accept(icon);
            });
        }

        @Override public int getItemCount() { return choices.length; }
    }

    private static final class IconHolder extends RecyclerView.ViewHolder {
        IconHolder(View view) { super(view); }
    }
}
