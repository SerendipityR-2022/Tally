package com.example.budgetapp.ui;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.budgetapp.MainActivity;
import com.example.budgetapp.R;
import com.example.budgetapp.database.Transaction;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.PieChart;
import java.io.FileOutputStream;
import java.io.File;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LedgerReferenceInstrumentedTest {
    @Test public void keypadComputesDecimalAmountsAndKeepsSaveActionsSeparate() {
        Instrumentation test = InstrumentationRegistry.getInstrumentation();
        test.runOnMainSync(() -> {
            Context themed = new ContextThemeWrapper(test.getTargetContext(), R.style.Theme_BudgetApp);
            View form = LayoutInflater.from(themed).inflate(R.layout.dialog_add_transaction, null);
            AlertDialog dialog = new AlertDialog.Builder(themed).setView(form).create();
            LedgerEntrySheet sheet = new LedgerEntrySheet(dialog, form);
            EditText amount = form.findViewById(R.id.et_amount);
            List<String> submitted = new ArrayList<>();
            sheet.bindSave(true, keep -> submitted.add(amount.getText() + ":" + keep));
            press(form, R.id.entry_key_decimal, R.id.entry_key_1, R.id.entry_key_0, R.id.entry_key_plus,
                    R.id.entry_key_decimal, R.id.entry_key_2, R.id.entry_key_0, R.id.btn_save);
            assertEquals("0.3:false", submitted.get(0));
            amount.setText("8.25");
            press(form, R.id.entry_key_minus, R.id.entry_key_2, R.id.entry_key_decimal, R.id.entry_key_5,
                    R.id.entry_key_1, R.id.entry_key_delete, R.id.entry_save_again);
            assertEquals("5.75:true", submitted.get(1));
            assertEquals(2, submitted.size());
        });
    }

    @Test public void detailAndStatisticsAggregateTheSameSettledTransactions() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate other = today.getDayOfMonth() == 1 ? today.plusDays(1) : today.minusDays(1);
        List<Transaction> fixture = new ArrayList<>();
        fixture.add(tx(other, 12, 0, "医药", 12.0, "药店"));
        fixture.add(tx(today, 16, 0, "购物", 129.90, "日用品"));
        fixture.add(tx(today, 12, 0, "餐饮", 28.50, "午餐"));
        fixture.add(tx(today, 9, 0, "交通", 58.20, "地铁与打车"));
        fixture.add(tx(today, 8, 1, "工资", 420.0, "补贴"));
        Transaction unsettled = tx(today, 7, 0, "居住", 900, "自动续费");
        unsettled.isSettled = false;
        fixture.add(unsettled);
        fixture.add(tx(today, 6, 2, "资产互转", 5000, "账户之间"));
        fixture.sort((a, b) -> Long.compare(b.date, a.date));
        Instrumentation test = InstrumentationRegistry.getInstrumentation();
        boolean screenshot = "true".equals(InstrumentationRegistry.getArguments().getString("ledgerScreens"));

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            navigate(scenario, R.id.nav_details);
            scenario.onActivity(activity -> {
                DetailsFragment details = (DetailsFragment) active(activity);
                details.renderTransactions(fixture);
                View root = details.requireView();
                assertEquals("228.60", text(root, R.id.ledger_details_expense));
                assertEquals("420.00", text(root, R.id.ledger_details_income));
            });
            test.waitForIdleSync();
            if (screenshot) save(test.getUiAutomation().takeScreenshot(), "tally-ledger-details.png");
            navigate(scenario, R.id.nav_stats);
            AtomicReference<Bitmap> composition = new AtomicReference<>();
            List<Transaction> statistics = new ArrayList<>(fixture);
            for (int i = 1; i <= 4; i++) {
                statistics.add(tx(YearMonth.from(today).minusMonths(i).atDay(15), 10, 0, "购物", 110 + 27 * i, "测试"));
            }
            scenario.onActivity(activity -> {
                StatsFragment stats = (StatsFragment) active(activity);
                stats.renderTransactions(statistics);
                View root = stats.requireView();
                assertEquals("228.60", text(root, R.id.ledger_metric_expense));
                assertEquals("191.40", text(root, R.id.ledger_metric_balance));
                PieChart chart = root.findViewById(R.id.chart_pie);
                assertEquals(4, chart.getData().getDataSet().getEntryCount());
                LinearLayout ranking = root.findViewById(R.id.ledger_ranked_transactions);
                assertEquals(4, ranking.getChildCount());
                assertEquals("购物", text(ranking.getChildAt(0), R.id.ledger_tx_title));
                BarChart monthly = root.findViewById(R.id.ledger_month_chart);
                assertEquals(228.6f, monthly.getData().getDataSetByIndex(0).getEntryForIndex(4).getY(), 0.01f);
            });
            test.waitForIdleSync();
            if (screenshot) {
                scenario.onActivity(activity -> {
                    ScrollView scroll = active(activity).requireView().findViewById(R.id.scroll_view_stats);
                    View content = scroll.getChildAt(0);
                    Bitmap bitmap = Bitmap.createBitmap(content.getWidth(), content.getHeight(), Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bitmap);
                    canvas.drawColor(activity.getColor(R.color.ledger_canvas));
                    content.draw(canvas);
                    composition.set(bitmap);
                });
                save(composition.get(), "tally-ledger-stats.png");
            }
        }
    }

    @Test public void expenseRingAnimatesFocusAndOnlyListRowsOpenBills() throws Exception {
        Instrumentation test = InstrumentationRegistry.getInstrumentation();
        LocalDate date = LocalDate.now();
        List<Transaction> fixture = java.util.Arrays.asList(
                tx(date, 15, 0, "购物", 191.92, "示例购物"),
                tx(date, 14, 0, "消费", 188.36, "示例消费"),
                tx(date, 13, 0, "餐饮", 49.45, "示例晚餐"),
                tx(date, 12, 0, "教育", 19.78, "示例课程"),
                tx(date, 11, 1, "工资", 40, ""),
                tx(date, 10, 1, "薪资", 60, ""));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            navigate(scenario, R.id.nav_stats);
            AtomicReference<ExpenseCompositionChart> ring = new AtomicReference<>();
            AtomicReference<View> root = new AtomicReference<>();
            scenario.onActivity(activity -> {
                StatsFragment stats = (StatsFragment) active(activity);
                stats.renderTransactions(fixture);
                root.set(stats.requireView());
                ring.set(root.get().findViewById(R.id.chart_pie));
                PieChart income = root.get().findViewById(R.id.chart_pie_income);
                assertEquals(1, income.getData().getEntryCount());
                assertEquals("薪资", income.getData().getDataSet().getEntryForIndex(0).getLabel());
            });
            test.waitForIdleSync();
            scenario.onActivity(activity -> {
                ExpenseCompositionChart chart = ring.get();
                ScrollView scroll = root.get().findViewById(R.id.scroll_view_stats);
                Rect area = new Rect();
                chart.getDrawingRect(area);
                scroll.offsetDescendantRectToMyCoords(chart, area);
                scroll.scrollTo(0, Math.max(0, area.top - 30));
            });
            long waitUntil = SystemClock.uptimeMillis() + 3500;
            java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
            do {
                scenario.onActivity(activity -> finished.set(ring.get().getAnimator().getPhaseY() == 1f));
                if (finished.get()) break;
                SystemClock.sleep(35);
            } while (SystemClock.uptimeMillis() < waitUntil);
            assertTrue("The visible ring must complete its entrance animation", finished.get());
            scenario.onActivity(activity -> {
                ExpenseCompositionChart chart = ring.get();
                assertEquals("449.51\n支出（元）", chart.getCenterText().toString());
                com.github.mikephil.charting.utils.MPPointF center = chart.getCenterCircleBox();
                assertNull(chart.getHighlightByTouchPoint(center.x, center.y));
                double angle = Math.toRadians(chart.getRotationAngle() + chart.getDrawAngles()[0] / 2f);
                float x = center.x + chart.getRadius() * .8f * (float) Math.cos(angle);
                float y = center.y + chart.getRadius() * .8f * (float) Math.sin(angle);
                com.github.mikephil.charting.utils.MPPointF.recycleInstance(center);
                assertNotNull(chart.getHighlightByTouchPoint(x, y));
                tap(chart, x, y);
            });
            SystemClock.sleep(330);
            test.waitForIdleSync();
            scenario.onActivity(activity -> {
                ExpenseCompositionChart chart = ring.get();
                assertTrue(chart.valuesToHighlight());
                assertEquals("191.92\n购物（元）", chart.getCenterText().toString());
                assertNull(activity.findViewById(R.id.rv_detail_list));
            });
            if ("true".equals(InstrumentationRegistry.getArguments().getString("rc1Screens"))) {
                AtomicReference<Bitmap> bitmap = new AtomicReference<>();
                scenario.onActivity(activity -> {
                    ExpenseCompositionChart chart = ring.get();
                    Bitmap full = Bitmap.createBitmap(chart.getWidth(), chart.getHeight(), Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(full);
                    canvas.drawColor(chart.getContext().getColor(R.color.ledger_panel));
                    chart.draw(canvas);
                    bitmap.set(full);
                });
                save(bitmap.get(), "tally-rc1-composition.png");
            }
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(
                    org.hamcrest.Matchers.containsString("消费清单"))).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
            scenario.onActivity(activity -> {
                LinearLayout rows = root.get().findViewById(R.id.ledger_expense_categories);
                assertTrue(rows.getChildAt(0).performClick());
            });
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(
                    org.hamcrest.Matchers.containsString("购物 - 消费清单")))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
            androidx.test.espresso.Espresso.pressBack();
        }
    }

    private static void tap(View view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
            view.dispatchTouchEvent(event);
            event.recycle();
        }
    }

    private static Transaction tx(LocalDate date, int hour, int type, String category, double amount, String remark) {
        long timestamp = date.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return new Transaction(timestamp, type, category, amount, null, remark);
    }
    private static String text(View root, int id) { return ((TextView) root.findViewById(id)).getText().toString(); }
    private static void press(View root, int... ids) { for (int id : ids) root.findViewById(id).performClick(); }
    private static NavHostFragment nav(MainActivity activity) {
        return (NavHostFragment) activity.getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
    }
    private static Fragment active(MainActivity activity) { return nav(activity).getChildFragmentManager().getPrimaryNavigationFragment(); }
    private static void navigate(ActivityScenario<MainActivity> scenario, int id) {
        scenario.onActivity(activity -> {
            nav(activity).getNavController().navigate(id);
            nav(activity).getChildFragmentManager().executePendingTransactions();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private static void save(Bitmap bitmap, String name) throws Exception {
        assertNotNull(bitmap);
        File cache = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        try (FileOutputStream stream = new FileOutputStream(new File(cache, name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally {
            bitmap.recycle();
        }
    }
}
