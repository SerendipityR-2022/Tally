package com.example.budgetapp.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.budgetapp.R;
import com.example.budgetapp.util.CategoryIcon;
import com.example.budgetapp.util.CategoryManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.ViewHolder> {
    private final Context context;
    private final OnCategoryClickListener listener;
    private final List<Item> items = new ArrayList<>();
    private List<String> categories;
    private String selectedCategory;
    private boolean expense;
    private boolean allowAdding;
    private OnCategoryLongClickListener subcategories;
    private Consumer<CategoryIcon> accountAction;

    public interface OnCategoryClickListener {
        void onCategoryClick(String category);
    }

    public interface OnCategoryLongClickListener {
        boolean onCategoryLongClick(String category);
    }

    public CategoryAdapter(Context context, List<String> categories, String selected, boolean expense,
                           OnCategoryClickListener listener) {
        this.context = context;
        this.listener = listener;
        this.categories = categories;
        selectedCategory = selected;
        this.expense = expense;
        rebuildItems();
        setStateRestorationPolicy(StateRestorationPolicy.PREVENT_WHEN_EMPTY);
    }

    public void setOnCategoryLongClickListener(OnCategoryLongClickListener listener) {
        subcategories = listener;
    }

    void enableAdding() {
        allowAdding = true;
        rebuildItems();
    }

    void setAccountActions(Consumer<CategoryIcon> action) {
        accountAction = action;
        rebuildItems();
        notifyDataSetChanged();
    }

    public void setCategories(List<String> list, String selected, boolean expense) {
        categories = list;
        selectedCategory = selected;
        this.expense = expense;
        rebuildItems();
        notifyDataSetChanged();
    }

    public void setSelectedCategory(String category) {
        if (Objects.equals(selectedCategory, category)) return;
        String previous = selectedCategory;
        selectedCategory = category;
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (item.action == null && item.name != null
                    && (item.name.equals(previous) || item.name.equals(category))) {
                notifyItemChanged(i);
            }
        }
    }

    int selectedPosition() {
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (item.action == null && item.name != null && item.name.equals(selectedCategory)) return i;
        }
        return RecyclerView.NO_POSITION;
    }

    private void rebuildItems() {
        items.clear();
        boolean showActions = expense && accountAction != null;
        int transferIndex = categories.indexOf(CategoryIcon.ENVELOPE.label);
        int loanIndex = categories.indexOf(CategoryIcon.INVESTMENT.label);
        int fallback = categories.indexOf(CategoryManager.CUSTOM_CATEGORY);
        if (fallback < 0) fallback = categories.size();
        // Keep saved categories in their own order; account actions never enter the expense list.
        for (int i = 0; i <= categories.size(); i++) {
            if (showActions && i == (transferIndex < 0 ? fallback : transferIndex + 1)) {
                items.add(new Item(CategoryIcon.TRANSFER.label, CategoryIcon.TRANSFER));
            }
            if (showActions && i == (loanIndex < 0 ? fallback : loanIndex + 1)) {
                items.add(new Item(CategoryIcon.LEND.label, CategoryIcon.LEND));
                items.add(new Item(CategoryIcon.REPAYMENT.label, CategoryIcon.REPAYMENT));
            }
            if (i < categories.size()) items.add(new Item(categories.get(i), null));
        }
        if (allowAdding) items.add(new Item(null, null));
    }

    @NonNull @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_ledger_category, parent, false);
        if (parent instanceof LedgerCategoryGrid) {
            view.getLayoutParams().height = ((LedgerCategoryGrid) parent).cellHeight();
        }
        return new ViewHolder(view);
    }

    @Override public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Item item = items.get(position);
        boolean add = item.name == null;
        boolean action = item.action != null;
        boolean selected = !add && !action && item.name.equals(selectedCategory);
        boolean hasChildren = !add && !action && subcategories != null
                && !CategoryManager.CUSTOM_CATEGORY.equals(item.name)
                && CategoryManager.isSubCategoryEnabled(context)
                && !CategoryManager.getSubCategories(context, item.name).isEmpty();
        boolean ambiguous = !add && !action && ((allowAdding && "添加".equals(item.name))
                || (expense && accountAction != null && (CategoryIcon.TRANSFER.label.equals(item.name)
                    || CategoryIcon.LEND.label.equals(item.name) || CategoryIcon.REPAYMENT.label.equals(item.name))));
        String label = add ? "添加" : ambiguous ? item.name + "（分类）" : item.name;
        if (add) {
            holder.icon.setImageResource(R.drawable.ic_ledger_category_add);
            holder.icon.setImageTintList(ColorStateList.valueOf(context.getColor(R.color.ledger_chart_blue)));
        } else {
            LedgerCategory.bind(holder.icon, action ? item.action : CategoryManager.getIcon(context, expense, item.name));
        }
        holder.label.setText(label);
        holder.label.setTextColor(context.getColor(add ? R.color.ledger_chart_blue
                : selected ? R.color.ledger_text : R.color.ledger_muted));
        holder.expand.setVisibility(hasChildren ? View.VISIBLE : View.GONE);
        holder.itemView.setSelected(selected);
        holder.itemView.setContentDescription(add ? "添加分类" : label);
        holder.itemView.setOnClickListener(v -> {
            if (add) {
                boolean isExpense = expense;
                CategoryEditorDialog.show(context, isExpense, null, name -> {
                    setCategories(isExpense ? CategoryManager.getExpenseCategories(context)
                            : CategoryManager.getIncomeCategories(context), name, isExpense);
                    if (listener != null) listener.onCategoryClick(name);
                }, null);
            } else if (action) {
                accountAction.accept(item.action);
            } else if (!hasChildren || !subcategories.onCategoryLongClick(item.name)) {
                setSelectedCategory(item.name);
                if (listener != null) listener.onCategoryClick(item.name);
            }
        });
        holder.itemView.setOnLongClickListener(hasChildren ? v -> subcategories.onCategoryLongClick(item.name) : null);
        holder.itemView.setLongClickable(hasChildren);
    }

    @Override public int getItemCount() { return items.size(); }

    private static final class Item {
        final String name;
        final CategoryIcon action;

        Item(String name, CategoryIcon action) {
            this.name = name;
            this.action = action;
        }
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final View expand;
        final TextView label;

        ViewHolder(View view) {
            super(view);
            icon = view.findViewById(R.id.ledger_category_icon);
            expand = view.findViewById(R.id.ledger_category_expand);
            label = view.findViewById(R.id.tv_category_icon);
        }
    }
}
