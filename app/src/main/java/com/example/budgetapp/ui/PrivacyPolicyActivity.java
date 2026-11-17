package com.example.budgetapp.ui;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import com.example.budgetapp.R;

public class PrivacyPolicyActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 开启沉浸式：状态栏和导航栏透明
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        
        setContentView(R.layout.activity_privacy_policy);

        // 处理系统栏遮挡，增加内边距（保留原有的 Padding）
        View rootView = findViewById(R.id.privacy_root);
        SettingsPage.applyInsets(rootView);
    }
}
