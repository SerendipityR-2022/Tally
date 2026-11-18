package com.example.budgetapp.ui;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.InputDevice;
import android.view.LayoutInflater;
import android.view.View;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
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
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.utils.MPPointF;
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
                BarChart monthly = root.findViewById(R.id.ledger_expense_compare_chart);
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

    @Test public void incomeAndExpenseRingsSelectSlicesWithoutOpeningBillLists() throws Exception {
        LocalDate date = LocalDate.now();
        List<Transaction> fixture = java.util.Arrays.asList(
                tx(date, 15, 0, "购物", 191.92, "示例购物"),
                tx(date, 14, 0, "消费", 188.36, "示例消费"),
                tx(date, 13, 0, "餐饮", 49.45, "示例晚餐"),
                tx(date, 12, 0, "教育", 19.78, "示例课程"),
                tx(date, 11, 1, "工资", 40, ""),
                tx(date, 10, 1, "薪资", 60, ""),
                tx(date, 9, 1, "奖金", 45.75, "示例奖金"),
                tx(date, 8, 1, "理财", 12.25, "示例理财"));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            navigate(scenario, R.id.nav_stats);
            AtomicReference<View> root = new AtomicReference<>();
            scenario.onActivity(activity -> {
                StatsFragment stats = (StatsFragment) active(activity);
                stats.renderTransactions(fixture);
                root.set(stats.requireView());
                LedgerCompositionChart expense = root.get().findViewById(R.id.chart_pie);
                LedgerCompositionChart income = root.get().findViewById(R.id.chart_pie_income);
                assertEquals(expense.getLayoutParams().height, income.getLayoutParams().height);
                assertEquals(expense.getData().getDataSet().getColor(0), income.getData().getDataSet().getColor(0));
                assertEquals(3, income.getData().getEntryCount());
                assertEquals("薪资", income.getData().getDataSet().getEntryForIndex(0).getLabel());
            });
            verifyComposition(scenario, root.get(), R.id.chart_pie, R.id.ledger_expense_categories,
                    "449.51\n支出（元）", "191.92\n购物（元）", "购物 - 消费清单", "tally-expense-composition.png");
            verifyComposition(scenario, root.get(), R.id.chart_pie_income, R.id.ledger_income_categories,
                    "158.00\n收入（元）", "100.00\n薪资（元）", "薪资 - 收入清单", "tally-income-composition.png");
        }
    }

    @Test public void trendPointTracksImmediateDragsWithoutChangingPeriod() throws Exception {
        LocalDate first = LocalDate.now().withDayOfMonth(1);
        LocalDate peak = first.plusDays(4);
        LocalDate last = first.plusMonths(1).minusDays(1);
        List<Transaction> fixture = java.util.Arrays.asList(
                tx(first, 10, 0, "购物", 24.30, ""),
                tx(peak, 10, 0, "餐饮", 99.79, ""),
                tx(peak, 11, 1, "奖金", 32.41, ""),
                tx(last, 10, 0, "交通", 47.51, ""));
        Instrumentation test = InstrumentationRegistry.getInstrumentation();
        String prefix = first.getMonthValue() + "/";

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            navigate(scenario, R.id.nav_stats);
            AtomicReference<View> statsView = new AtomicReference<>();
            scenario.onActivity(activity -> {
                StatsFragment stats = (StatsFragment) active(activity);
                stats.renderTransactions(fixture);
                statsView.set(stats.requireView());
            });
            test.waitForIdleSync();
            View root = statsView.get();
            ScrollView scroll = root.findViewById(R.id.scroll_view_stats);
            LedgerTrendChart chart = root.findViewById(R.id.chart_line);
            float[][] screenPoints = new float[3][];
            int[] scrollY = new int[1];
            String[] period = new String[1];
            scenario.onActivity(activity -> {
                Rect rect = new Rect();
                chart.getDrawingRect(rect);
                scroll.offsetDescendantRectToMyCoords(chart, rect);
                scroll.scrollTo(0, rect.top - 24);
                scrollY[0] = scroll.getScrollY();
                period[0] = text(root, R.id.tv_current_date_range);
                screenPoints[0] = screenPoint(chart, 0);
                screenPoints[1] = screenPoint(chart, 4);
                screenPoints[2] = screenPoint(chart, last.getDayOfMonth() - 1);
            });

            tapScreen(test, screenPoints[1]);
            tapScreen(test, screenPoints[1]); // A second tap on the same point keeps its label.
            AtomicReference<Bitmap> trendScreenshot = new AtomicReference<>();
            boolean screenshots = "true".equals(InstrumentationRegistry.getArguments().getString("trendScreens"));
            scenario.onActivity(activity -> {
                assertTrendPoint(chart, 5, prefix + "5 支出，¥99.79");
                Bitmap bitmap = verifyTrendMarker(chart);
                if (screenshots) trendScreenshot.set(bitmap); else bitmap.recycle();
            });
            if (screenshots) save(trendScreenshot.get(), "tally-trend-point.png");

            // First move drifts vertically beyond ScrollView's slop, but is predominantly horizontal.
            float dragY = screenPoints[0][1] - ViewConfiguration.get(chart.getContext()).getScaledTouchSlop() * 1.5f;
            long down = SystemClock.uptimeMillis();
            touchScreen(test, down, MotionEvent.ACTION_DOWN, screenPoints[0][0], screenPoints[0][1]);
            try {
                scenario.onActivity(activity -> {
                    assertTrendPoint(chart, 1, prefix + "1 支出，¥24.30");
                    verifyTrendMarker(chart).recycle();
                });
                touchScreen(test, down, MotionEvent.ACTION_MOVE, screenPoints[1][0], dragY);
                scenario.onActivity(activity -> assertTrendPoint(chart, 5, prefix + "5 支出，¥99.79"));
                touchScreen(test, down, MotionEvent.ACTION_MOVE, screenPoints[2][0], dragY);
            } finally {
                touchScreen(test, down, MotionEvent.ACTION_UP, screenPoints[2][0], dragY);
            }
            SystemClock.sleep(200); // Give any wrongly triggered period animation time to run.
            scenario.onActivity(activity -> {
                assertEquals(period[0], text(root, R.id.tv_current_date_range));
                assertEquals(scrollY[0], scroll.getScrollY());
                assertTrendPoint(chart, last.getDayOfMonth(), prefix + last.getDayOfMonth() + " 支出，¥47.51");
                verifyTrendMarker(chart).recycle();
                ((StatsFragment) active(activity)).renderTransactions(fixture);
                assertTrendPoint(chart, last.getDayOfMonth(), prefix + last.getDayOfMonth() + " 支出，¥47.51");
                root.findViewById(R.id.ledger_trend_income).performClick();
                assertFalse(chart.valuesToHighlight());
                screenPoints[1] = screenPoint(chart, 4);
            });
            tapScreen(test, screenPoints[1]);
            scenario.onActivity(activity -> {
                assertTrendPoint(chart, 5, prefix + "5 收入，¥32.41");
                root.findViewById(R.id.ledger_trend_net).performClick();
                assertFalse(chart.valuesToHighlight());
                screenPoints[1] = screenPoint(chart, 4);
            });
            tapScreen(test, screenPoints[1]);
            scenario.onActivity(activity -> assertTrendPoint(chart, 5, prefix + "5 结余，¥-67.38"));

            // Swiping vertically without a hold still scrolls, even if the drag starts on the chart.
            float[] point = screenPoints[1];
            swipeScreen(test, point[0], point[1], point[0], point[1] - chart.getHeight() * .45f);
            scenario.onActivity(activity -> {
                assertTrue(scroll.getScrollY() > scrollY[0]);
                assertEquals(period[0], text(root, R.id.tv_current_date_range));
                scroll.fling(0);
                scroll.scrollTo(0, 0);
            });
            float[] start = new float[2];
            float[] end = new float[2];
            scenario.onActivity(activity -> {
                View metrics = root.findViewById(R.id.ledger_stats_metrics);
                int[] location = new int[2];
                metrics.getLocationOnScreen(location);
                start[0] = location[0] + metrics.getWidth() * .2f;
                end[0] = location[0] + metrics.getWidth() * .8f;
                start[1] = end[1] = location[1] + metrics.getHeight() / 2f;
            });
            swipeScreen(test, start[0], start[1], end[0], end[1]);
            SystemClock.sleep(500);
            scenario.onActivity(activity -> {
                assertNotEquals(period[0], text(root, R.id.tv_current_date_range));
                assertFalse(chart.valuesToHighlight());
            });
        }
    }

    private static void assertTrendPoint(LedgerTrendChart chart, int bucket, String description) {
        assertTrue(chart.valuesToHighlight());
        assertEquals(bucket, chart.getHighlighted()[0].getX(), 0f);
        assertEquals(description, ViewCompat.getStateDescription(chart));
    }

    private static Bitmap verifyTrendMarker(LedgerTrendChart chart) {
        Bitmap bitmap = Bitmap.createBitmap(chart.getWidth(), chart.getHeight(), Bitmap.Config.ARGB_8888);
        chart.draw(new Canvas(bitmap));
        com.github.mikephil.charting.highlight.Highlight selection = chart.getHighlighted()[0];
        float x = selection.getDrawX(), y = selection.getDrawY();
        CustomMarkerView marker = (CustomMarkerView) chart.getMarker();
        MPPointF offset = marker.getOffsetForDrawingAtPoint(x, y);
        float left = x + offset.x, top = y + offset.y;
        assertTrue("The label must fit even for a peak or a point at the plot edge", top >= 0 && left >= 0
                && left + marker.getWidth() <= chart.getWidth() && top + marker.getHeight() < y);
        assertEquals(chart.getContext().getColor(R.color.ledger_trend_marker),
                bitmap.getPixel((int) (left + marker.getWidth() / 2f), (int) (top + 3)));
        return bitmap;
    }

    private static float[] screenPoint(LedgerTrendChart chart, int index) {
        Entry entry = chart.getData().getDataSetByIndex(0).getEntryForIndex(index);
        MPPointF point = chart.getPosition(entry, YAxis.AxisDependency.LEFT);
        int[] location = new int[2];
        chart.getLocationOnScreen(location);
        float[] result = {location[0] + point.x, location[1] + point.y};
        MPPointF.recycleInstance(point);
        return result;
    }

    private static void tapScreen(Instrumentation test, float[] point) {
        long down = SystemClock.uptimeMillis();
        touchScreen(test, down, MotionEvent.ACTION_DOWN, point[0], point[1]);
        touchScreen(test, down, MotionEvent.ACTION_UP, point[0], point[1]);
    }

    private static void swipeScreen(Instrumentation test, float x, float y, float endX, float endY) {
        long down = SystemClock.uptimeMillis();
        touchScreen(test, down, MotionEvent.ACTION_DOWN, x, y);
        for (int i = 1; i <= 6; i++) {
            SystemClock.sleep(16);
            touchScreen(test, down, MotionEvent.ACTION_MOVE, x + (endX - x) * i / 6, y + (endY - y) * i / 6);
        }
        touchScreen(test, down, MotionEvent.ACTION_UP, endX, endY);
    }

    private static void touchScreen(Instrumentation test, long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { test.sendPointerSync(event); } finally { event.recycle(); }
    }

    private static void verifyComposition(ActivityScenario<MainActivity> scenario, View root, int chartId, int rowsId,
            String total, String selected, String billListTitle, String screenshot) throws Exception {
        Instrumentation test = InstrumentationRegistry.getInstrumentation();
        LedgerCompositionChart chart = root.findViewById(chartId);
        test.waitForIdleSync();
        scenario.onActivity(activity -> {
            ScrollView scroll = root.findViewById(R.id.scroll_view_stats);
            Rect area = new Rect();
            chart.getDrawingRect(area);
            scroll.offsetDescendantRectToMyCoords(chart, area);
            scroll.scrollTo(0, Math.max(0, area.top - 30));
        });
        long deadline = SystemClock.uptimeMillis() + 3500;
        java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
        do {
            scenario.onActivity(activity -> finished.set(chart.getAnimator().getPhaseY() == 1f));
            if (finished.get()) break;
            SystemClock.sleep(35);
        } while (SystemClock.uptimeMillis() < deadline);
        assertTrue("The visible ring must complete its entrance animation", finished.get());
        scenario.onActivity(activity -> {
            assertEquals(total, chart.getCenterText().toString());
            com.github.mikephil.charting.utils.MPPointF center = chart.getCenterCircleBox();
            assertNull(chart.getHighlightByTouchPoint(center.x, center.y));
            double angle = Math.toRadians(chart.getRotationAngle() + chart.getDrawAngles()[0] / 2f);
            float x = center.x + chart.getRadius() * .8f * (float) Math.cos(angle);
            float y = center.y + chart.getRadius() * .8f * (float) Math.sin(angle);
            com.github.mikephil.charting.utils.MPPointF.recycleInstance(center);
            assertNotNull(chart.getHighlightByTouchPoint(x, y));
            tap(chart, x, y);
            assertTrue(chart.valuesToHighlight());
            assertEquals(selected, chart.getCenterText().toString());
        });
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.rv_detail_list))
                .check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
        if ("true".equals(InstrumentationRegistry.getArguments().getString("compositionScreens"))) {
            SystemClock.sleep(330);
            AtomicReference<Bitmap> bitmap = new AtomicReference<>();
            scenario.onActivity(activity -> {
                Bitmap full = Bitmap.createBitmap(chart.getWidth(), chart.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(full);
                canvas.drawColor(chart.getContext().getColor(R.color.ledger_panel));
                chart.draw(canvas);
                bitmap.set(full);
            });
            save(bitmap.get(), screenshot);
        }
        scenario.onActivity(activity -> {
            LinearLayout rows = root.findViewById(rowsId);
            assertTrue(rows.getChildAt(0).performClick());
        });
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(
                org.hamcrest.Matchers.containsString(billListTitle)))
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
        androidx.test.espresso.Espresso.pressBack();
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
