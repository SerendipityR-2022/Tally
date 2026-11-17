package com.example.budgetapp.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.budgetapp.R;
import com.example.budgetapp.database.AssetAccount;
import com.example.budgetapp.util.AssetSpinnerAdapter;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ConfirmationOverlayInstrumentedTest {
    @Test public void buttonsStayVisibleAndCategoriesUseOnlyTheSpaceLeftByTheForm() {
        onMain(() -> {
            ConfirmationOverlay root = newForm(1f, Configuration.UI_MODE_NIGHT_YES);
            LedgerCategoryGrid grid = root.findViewById(R.id.rv_window_category);
            NestedScrollView form = root.findViewById(R.id.window_confirmation_form);
            measure(root, 360, 680);
            int regularHeight = grid.getHeight();
            assertTrue(regularHeight > 0);
            assertEquals(0, regularHeight % grid.rowHeight());
            assertTrue(grid.canScrollVertically(1));
            assertFalse(form.canScrollVertically(1));
            assertButtonsVisible(root);

            View save = root.findViewById(R.id.btn_window_save);
            int saveY = bounds(root, save).top;
            grid.scrollBy(0, grid.rowHeight());
            assertTrue(((GridLayoutManager) grid.getLayoutManager()).findFirstVisibleItemPosition() > 0);
            assertEquals(saveY, bounds(root, save).top);

            measure(root, 360, 820);
            assertTrue(grid.getHeight() > regularHeight);
            assertButtonsVisible(root);

            measure(root, 320, 330);
            assertEquals(root.getWidth(), root.findViewById(R.id.window_card_content).getWidth());
            assertTrue(form.canScrollVertically(1));
            form.scrollTo(0, dp(root, 1000));
            assertButtonsVisible(root);
        });
    }

    @Test public void optionalFieldsAndLargeTextStillLeaveRoomForBothActions() {
        onMain(() -> {
            ConfirmationOverlay root = newForm(1.5f, Configuration.UI_MODE_NIGHT_NO);
            View grid = root.findViewById(R.id.rv_window_category);
            grid.setVisibility(View.GONE);
            root.findViewById(R.id.et_window_target).setVisibility(View.VISIBLE);
            root.findViewById(R.id.btn_window_currency).setVisibility(View.VISIBLE);
            root.findViewById(R.id.btn_window_take_photo).setVisibility(View.VISIBLE);
            measure(root, 320, 370);
            assertButtonsVisible(root);
        });
    }

    @Test public void assetTextSharesTheAmountAndRemarkInsetEvenWhenItHasAnIcon() {
        onMain(() -> {
            ConfirmationOverlay root = newForm(1f, Configuration.UI_MODE_NIGHT_YES);
            Spinner assets = root.findViewById(R.id.sp_asset);
            EditText amount = root.findViewById(R.id.et_window_amount);
            EditText remark = root.findViewById(R.id.et_window_remark);
            for (int position = 0; position < 2; position++) {
                assets.setSelection(position);
                measure(root, 360, 680);
                TextView label = assets.getSelectedView().findViewById(R.id.tv_asset_name);
                assertEquals(position == 0 ? View.GONE : View.VISIBLE,
                        assets.getSelectedView().findViewById(R.id.iv_asset_icon).getVisibility());
                assertEquals(textStart(root, amount), textStart(root, label));
                assertEquals(textStart(root, amount), textStart(root, remark));
            }
        });
    }

    private static ConfirmationOverlay newForm(float fontScale, int nightMode) {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.fontScale = fontScale;
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | nightMode;
        Context context = new ContextThemeWrapper(base.createConfigurationContext(config), R.style.Theme_BudgetApp);
        ConfirmationOverlay root = (ConfirmationOverlay) LayoutInflater.from(context)
                .inflate(R.layout.window_confirm_transaction, null);
        LedgerCategoryGrid grid = root.findViewById(R.id.rv_window_category);
        grid.setLayoutManager(new GridLayoutManager(context, LedgerCategoryGrid.COLUMNS));
        List<String> categories = new ArrayList<>();
        for (int i = 0; i < 40; i++) categories.add("分类 " + i);
        grid.setAdapter(new CategoryAdapter(context, categories, categories.get(0), true, name -> {}));
        TextView summary = root.findViewById(R.id.tv_window_category_result);
        summary.setText("分类：购物 · 页面提示");
        summary.setVisibility(View.VISIBLE);
        Spinner assets = root.findViewById(R.id.sp_asset);
        AssetSpinnerAdapter adapter = new AssetSpinnerAdapter(context, R.layout.item_spinner_asset_confirmation);
        adapter.add(new AssetAccount("不关联资产", 0, 0));
        AssetAccount card = new AssetAccount("测试银行卡", 15, 0);
        card.svgIcon = "<svg xmlns='http://www.w3.org/2000/svg' width='20' height='20'>"
                + "<circle cx='10' cy='10' r='6'/></svg>";
        adapter.add(card);
        assets.setAdapter(adapter);
        return root;
    }

    private static void assertButtonsVisible(ConfirmationOverlay root) {
        for (int id : new int[]{R.id.btn_window_cancel, R.id.btn_window_save}) {
            View action = root.findViewById(id);
            Rect b = bounds(root, action);
            assertEquals(dp(root, 56), b.height());
            assertTrue(b.toShortString(), b.top >= 0 && b.bottom <= root.getHeight());
        }
    }

    private static Rect bounds(ConfirmationOverlay root, View view) {
        Rect rect = new Rect(0, 0, view.getWidth(), view.getHeight());
        root.offsetDescendantRectToMyCoords(view, rect);
        return rect;
    }

    private static int textStart(ConfirmationOverlay root, TextView text) {
        return bounds(root, text).left + text.getCompoundPaddingStart();
    }

    private static void measure(ConfirmationOverlay root, int width, int height) {
        int w = dp(root, width), h = dp(root, height);
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    private static void onMain(Runnable run) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(run);
    }
}
