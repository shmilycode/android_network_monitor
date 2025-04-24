package com.example.networkmonitor;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 1;
    private List<ApplicationInfo> installedApps;
    private List<AppInfo> allApps;
    private AppListAdapter adapter;
    private EditText searchBox;
    private ImageButton clearSearchButton;
    private TextView searchResultInfo;
    private List<AppInfo> currentDisplayedApps;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        searchBox = findViewById(R.id.search_box);
        clearSearchButton = findViewById(R.id.clear_search);
        searchResultInfo = findViewById(R.id.search_result_info);
        
        setupSearchFeatures();

        if (!checkPermissions()) {
            showPermissionGuide();
        } else {
            loadInstalledApps();
            setupAppList();
        }
    }

    private void setupSearchFeatures() {
        // 设置搜索框监听
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                String query = s.toString();
                if (query.isEmpty()) {
                    clearSearch();
                } else {
                    filterApps(query);
                }
                // 根据是否有输入内容显示或隐藏清除按钮
                clearSearchButton.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
            }
        });

        // 设置清除按钮点击事件
        clearSearchButton.setOnClickListener(v -> {
            searchBox.setText("");
            clearSearch();
        });

        // 初始状态隐藏清除按钮
        clearSearchButton.setVisibility(View.GONE);

        // 设置键盘搜索按钮事件
        searchBox.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                // 隐藏键盘
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                return true;
            }
            return false;
        });
    }

    private boolean checkPermissions() {
        // 检查使用情况访问权限
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName());
        boolean hasUsagePermission = mode == AppOpsManager.MODE_ALLOWED;

        // 检查悬浮窗权限
        boolean hasOverlayPermission = Settings.canDrawOverlays(this);

        return hasUsagePermission && hasOverlayPermission;
    }

    private void showPermissionGuide() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("需要权限")
                .setMessage("此应用需要以下权限才能正常工作：\n\n" +
                        "1. 使用情况访问权限：用于监控应用网络使用情况\n" +
                        "2. 悬浮窗权限：用于显示网络监控数据\n\n" +
                        "请在接下来的页面中开启这些权限。")
                .setPositiveButton("确定", (dialog, which) -> {
                    requestPermissions();
                })
                .setNegativeButton("退出", (dialog, which) -> {
                    finish();
                })
                .setCancelable(false)
                .show();
    }

    private void requestPermissions() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName());

        if (mode != AppOpsManager.MODE_ALLOWED) {
            // 显示使用情况访问权限说明
            new AlertDialog.Builder(this)
                    .setTitle("使用情况访问权限")
                    .setMessage("在接下来的页面中，请找到\"网络监控\"应用并开启权限。")
                    .setPositiveButton("去开启", (dialog, which) -> {
                        startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                    })
                    .show();
            return;
        }

        if (!Settings.canDrawOverlays(this)) {
            // 显示悬浮窗权限说明
            new AlertDialog.Builder(this)
                    .setTitle("悬浮窗权限")
                    .setMessage("请在接下来的页面中开启悬浮窗权限。")
                    .setPositiveButton("去开启", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivityForResult(intent, PERMISSION_REQUEST_CODE);
                    })
                    .show();
            return;
        }

        // 如果所有权限都已获取，加载应用列表
        loadInstalledApps();
        setupAppList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkPermissions()) {
            if (installedApps == null || allApps == null) {
                loadInstalledApps();
                setupAppList();
            }
        }
    }

    private void loadInstalledApps() {
        PackageManager packageManager = getPackageManager();
        installedApps = new ArrayList<>();
        allApps = new ArrayList<>();

        try {
            // 使用MATCH_UNINSTALLED_PACKAGES标志来获取所有包括被隐藏的应用
            installedApps = packageManager.getInstalledApplications(
                PackageManager.MATCH_UNINSTALLED_PACKAGES | 
                PackageManager.MATCH_DISABLED_COMPONENTS |
                PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS);

            for (ApplicationInfo appInfo : installedApps) {
                try {
                    String appName = packageManager.getApplicationLabel(appInfo).toString();
                    String packageName = appInfo.packageName;
                    
                    // 添加调试日志
                    if (packageName.contains("aweme")) {
                        System.out.println("找到抖音相关应用：" + packageName + " - " + appName);
                    }
                    
                    allApps.add(new AppInfo(appInfo, appName, packageName));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            // 按应用名称排序
            Collections.sort(allApps, (a, b) -> a.name.compareToIgnoreCase(b.name));
            
            // 打印总应用数量
            System.out.println("总共找到应用数量: " + allApps.size());
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "加载应用列表失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void setupAppList() {
        ListView appListView = findViewById(R.id.app_list);
        currentDisplayedApps = new ArrayList<>(allApps);
        adapter = new AppListAdapter(this, currentDisplayedApps);
        appListView.setAdapter(adapter);

        appListView.setOnItemClickListener((parent, view, position, id) -> {
            AppInfo selectedApp = adapter.getItem(position);
            
            Intent serviceIntent = new Intent(MainActivity.this, FloatingWindowService.class);
            serviceIntent.putExtra("packageName", selectedApp.packageName);
            serviceIntent.putExtra("appName", selectedApp.name);
            
            startService(serviceIntent);
            moveTaskToBack(true);
        });
    }

    private void filterApps(String query) {
        if (adapter == null || allApps == null) return;

        query = query.toLowerCase().trim();
        currentDisplayedApps = new ArrayList<>();

        for (AppInfo app : allApps) {
            if (app.name.toLowerCase().contains(query) ||
                app.packageName.toLowerCase().contains(query)) {
                currentDisplayedApps.add(app);
            }
        }

        adapter.clear();
        adapter.addAll(currentDisplayedApps);
        
        // 更新搜索结果信息
        updateSearchResultInfo(query, currentDisplayedApps.size());
    }

    private void clearSearch() {
        if (adapter == null || allApps == null) return;
        
        currentDisplayedApps = new ArrayList<>(allApps);
        adapter.clear();
        adapter.addAll(allApps);
        searchResultInfo.setVisibility(View.GONE);
        
        // 隐藏键盘
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
    }

    private void updateSearchResultInfo(String query, int resultCount) {
        if (query.isEmpty()) {
            searchResultInfo.setVisibility(View.GONE);
        } else {
            searchResultInfo.setVisibility(View.VISIBLE);
            searchResultInfo.setText(String.format("找到 %d 个匹配的应用", resultCount));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "需要悬浮窗权限才能运行", Toast.LENGTH_LONG).show();
                finish();
            } else {
                // 检查是否所有权限都已获取
                if (checkPermissions()) {
                    loadInstalledApps();
                    setupAppList();
                }
            }
        }
    }

    private static class AppInfo {
        ApplicationInfo applicationInfo;
        String name;
        String packageName;

        AppInfo(ApplicationInfo applicationInfo, String name, String packageName) {
            this.applicationInfo = applicationInfo;
            this.name = name;
            this.packageName = packageName;
        }
    }

    private class AppListAdapter extends ArrayAdapter<AppInfo> {
        private final LayoutInflater inflater;

        AppListAdapter(Context context, List<AppInfo> apps) {
            super(context, 0, apps);
            inflater = LayoutInflater.from(context);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.app_list_item, parent, false);
            }

            AppInfo app = getItem(position);
            if (app != null) {
                TextView appNameView = convertView.findViewById(R.id.app_name);
                TextView packageNameView = convertView.findViewById(R.id.package_name);

                appNameView.setText(app.name);
                packageNameView.setText(app.packageName);
            }

            return convertView;
        }
    }
} 