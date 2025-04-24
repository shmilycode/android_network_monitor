package com.example.networkmonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import java.util.Timer;
import java.util.TimerTask;
import android.Manifest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import android.net.TrafficStats;

public class FloatingWindowService extends Service {
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "NetworkMonitorChannel";
    private static final long UPDATE_INTERVAL = 1000; // 更新间隔为1秒
    private WindowManager windowManager;
    private View floatingView;
    private String packageName;
    private String appName;
    private NetworkStatsManager networkStatsManager;
    private Timer timer;
    private Handler handler;
    
    private long lastWifiRx = 0;
    private long lastWifiTx = 0;
    private long lastMobileRx = 0;
    private long lastMobileTx = 0;
    private long lastUpdateTime = 0;
    private long lastQueryTime = 0;  // 新增：上次查询时间
    private long accumulatedWifiRx = 0;
    private long accumulatedWifiTx = 0;
    private long accumulatedMobileRx = 0;
    private long accumulatedMobileTx = 0;
    private long lastDisplayUpdateTime = 0;  // 新增：上次显示更新时间

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        handler = new Handler();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Network Monitor Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows network usage statistics");
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Network Monitor")
            .setContentText("Monitoring network usage...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            packageName = intent.getStringExtra("packageName");
            appName = intent.getStringExtra("appName");
            networkStatsManager = (NetworkStatsManager) getSystemService(Context.NETWORK_STATS_SERVICE);
            showFloatingWindow();
            startMonitoring();
        }
        return START_STICKY;
    }

    private void showFloatingWindow() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 100;

        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        floatingView = inflater.inflate(R.layout.floating_window, null);

        // 添加拖动功能
        floatingView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(floatingView, params);
                        return true;
                }
                return false;
            }
        });

        Button stopButton = floatingView.findViewById(R.id.stop_button);
        stopButton.setOnClickListener(v -> {
            stopSelf();
            Intent mainIntent = new Intent(this, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(mainIntent);
        });

        windowManager.addView(floatingView, params);
    }

    private void startMonitoring() {
        // 初始化基准数据
        updateNetworkStats(true);
        
        if (timer != null) {
            timer.cancel();
        }
        
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                updateNetworkStats(false);
            }
        }, UPDATE_INTERVAL, UPDATE_INTERVAL); // 每秒更新一次
    }

    private void updateNetworkStats(boolean isInitial) {
        try {
            long now = System.currentTimeMillis();
            
            // 获取当前网络类型
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            boolean isWifi = activeNetwork != null && activeNetwork.getType() == ConnectivityManager.TYPE_WIFI;
            boolean isMobile = activeNetwork != null && activeNetwork.getType() == ConnectivityManager.TYPE_MOBILE;

            // 获取系统总流量
            long totalRxBytes = TrafficStats.getTotalRxBytes();
            long totalTxBytes = TrafficStats.getTotalTxBytes();
            
            // 获取移动数据流量
            long mobileRxBytes = TrafficStats.getMobileRxBytes();
            long mobileTxBytes = TrafficStats.getMobileTxBytes();
            
            // 计算WiFi流量 = 总流量 - 移动数据流量
            long wifiRxBytes = totalRxBytes - mobileRxBytes;
            long wifiTxBytes = totalTxBytes - mobileTxBytes;

            // 计算速率
            if (!isInitial && lastQueryTime > 0) {
                long timeDiff = now - lastQueryTime;
                if (timeDiff > 0) {
                    // 计算这段时间内的数据传输速率（字节/秒）
                    double wifiRxSpeed = ((wifiRxBytes - lastWifiRx) * 1000.0) / timeDiff;
                    double wifiTxSpeed = ((wifiTxBytes - lastWifiTx) * 1000.0) / timeDiff;
                    double mobileRxSpeed = ((mobileRxBytes - lastMobileRx) * 1000.0) / timeDiff;
                    double mobileTxSpeed = ((mobileTxBytes - lastMobileTx) * 1000.0) / timeDiff;

                    // 更新UI显示
                    final String wifiSpeedText = String.format("WiFi ↓%.1fKB/s ↑%.1fKB/s",
                            wifiRxSpeed / 1024, wifiTxSpeed / 1024);
                    final String mobileSpeedText = String.format("流量 ↓%.1fKB/s ↑%.1fKB/s",
                            mobileRxSpeed / 1024, mobileTxSpeed / 1024);

                    handler.post(() -> {
                        TextView statView = floatingView.findViewById(R.id.stats_text);
                        statView.setText(wifiSpeedText + "\n" + mobileSpeedText);
                    });

                    System.out.println("DEBUG: 速率更新 - " + wifiSpeedText + " | " + mobileSpeedText);
                }
            }

            // 更新基准数据
            lastWifiRx = wifiRxBytes;
            lastWifiTx = wifiTxBytes;
            lastMobileRx = mobileRxBytes;
            lastMobileTx = mobileTxBytes;
            lastQueryTime = now;

        } catch (Exception e) {
            System.out.println("ERROR: 网络统计更新失败 - " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (timer != null) {
            timer.cancel();
        }
        if (floatingView != null && windowManager != null) {
            windowManager.removeView(floatingView);
        }
    }
} 