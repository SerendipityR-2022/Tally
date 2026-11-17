package com.example.budgetapp.util;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Keeps instrumentation fixtures away from the user's real preferences. */
public final class CategoryTestContext extends ContextWrapper {
    private final String prefix = "category_test_" + UUID.randomUUID() + "_";
    private final Set<String> names = new HashSet<>();
    public CategoryTestContext(Context base) { super(base); }
    @Override public synchronized SharedPreferences getSharedPreferences(String name, int mode) {
        names.add(prefix + name);
        return super.getSharedPreferences(prefix + name, mode);
    }
    @Override public Context getApplicationContext() { return this; }
    public synchronized void clear() {
        for (String name : names) getBaseContext().deleteSharedPreferences(name);
    }
}
