package com.example.budgetapp.database;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.room.Room;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.gson.Gson;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ScreenLearningMigrationInstrumentedTest {
    @Test public void version27MigratesWithoutLosingBillsOrInventingScreenSources() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "migration_27_28_" + UUID.randomUUID();
        AppDatabase template = Room.inMemoryDatabaseBuilder(context, AppDatabase.class).build();
        AppDatabase migrated = null;
        try {
            try (SQLiteDatabase legacy = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)) {
                SupportSQLiteDatabase current = template.getOpenHelper().getWritableDatabase();
                try (Cursor schema = current.query("SELECT sql FROM sqlite_master WHERE type IN ('table', 'index') AND sql IS NOT NULL AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY type DESC")) {
                    while (schema.moveToNext()) {
                        legacy.execSQL(schema.getString(0).replace(", `screenSourcePackage` TEXT", "")
                                .replace(", `screenMerchantIdentity` TEXT", ""));
                    }
                }
                legacy.execSQL("INSERT INTO transactions (id, date, type, category, amount, remark, assetId, currencySymbol) VALUES (71, 1000, 0, '餐饮', 20.30, '原有备注', 0, '¥')");
                legacy.setVersion(27);
            }
            migrated = Room.databaseBuilder(context, AppDatabase.class, name).addMigrations(AppDatabase.MIGRATION_27_28).build();
            Transaction legacy = migrated.transactionDao().getByIdSync(71);
            assertNotNull(legacy);
            assertEquals(20.30, legacy.amount, 0.0001);
            assertEquals("原有备注", legacy.remark);
            assertNull(legacy.screenSourcePackage);
            assertNull(legacy.screenMerchantIdentity);
            assertEquals(28, migrated.getOpenHelper().getWritableDatabase().getVersion());

            legacy.screenSourcePackage = "com.eg.android.AlipayGphone";
            legacy.screenMerchantIdentity = "示例商户";
            migrated.transactionDao().update(legacy);
            Transaction restored = new Gson().fromJson(new Gson().toJson(migrated.transactionDao().getByIdSync(71)), Transaction.class);
            assertEquals(legacy.screenSourcePackage, restored.screenSourcePackage);
            assertEquals(legacy.screenMerchantIdentity, restored.screenMerchantIdentity);
        } finally {
            template.close();
            if (migrated != null) migrated.close();
            context.deleteDatabase(name);
        }
    }
}
