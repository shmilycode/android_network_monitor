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
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.LinkedList;

public class FloatingWindowService extends Service {
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "NetworkMonitorChannel";
    private static final long UPDATE_INTERVAL = 1000; // 更新间隔为1秒
    private static final double MOBILE_SPEED_THRESHOLD = 50.0; // 蜂窝流量阈值，单位KB/s
    private static final String PING_HOST = "8.8.8.8"; // Google DNS服务器
    private static final int PING_COUNT = 1; // 每次只ping一个包
    private static final int PING_TIMEOUT = 1000; // ping超时时间（毫秒）
    private static final int WINDOW_SIZE = 4; // 滑动窗口大小
    
    private WindowManager windowManager;
    private View floatingView;
    private String packageName;
    private String appName;
    private NetworkStatsManager networkStatsManager;
    private Timer timer;
    private Handler handler;
    private ConnectivityManager connectivityManager;
    private ExecutorService pingExecutor;
    
    private long lastWifiRx = 0;
    private long lastWifiTx = 0;
    private long lastMobileRx = 0;
    private long lastMobileTx = 0;
    private long lastUpdateTime = 0;
    private long lastQueryTime = 0;
    private long accumulatedWifiRx = 0;
    private long accumulatedWifiTx = 0;
    private long accumulatedMobileRx = 0;
    private long accumulatedMobileTx = 0;
    private long lastDisplayUpdateTime = 0;
    private int defaultBackgroundColor;
    
    // 网络质量指标
    private final LinkedList<Double> rttWindow = new LinkedList<>();
    private final LinkedList<Boolean> lossWindow = new LinkedList<>();
    private double packetLossRate = 0.0;
    private double averageRtt = 0.0;
    private final Object networkQualityLock = new Object();

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        handler = new Handler();
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        pingExecutor = Executors.newSingleThreadExecutor();
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

        // 保存默认背景颜色
        defaultBackgroundColor = floatingView.getBackground() != null ? 
            ((ColorDrawable) floatingView.getBackground()).getColor() : 
            Color.TRANSPARENT;

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
        }, UPDATE_INTERVAL, UPDATE_INTERVAL);

        // 启动ping监控
        startPingMonitoring();
    }

    private void startPingMonitoring() {
        pingExecutor.execute(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    long startTime = System.currentTimeMillis();
                    
                    Process process = Runtime.getRuntime().exec("ping -c " + PING_COUNT + " -W " + (PING_TIMEOUT / 1000) + " " + PING_HOST);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    String line;
                    boolean packetLost = true;
                    double rtt = 0.0;

                    while ((line = reader.readLine()) != null) {
                        if (line.contains("time=")) {
                            // 解析RTT
                            String timeStr = line.substring(line.indexOf("time=") + 5);
                            timeStr = timeStr.substring(0, timeStr.indexOf(" ms"));
                            rtt = Double.parseDouble(timeStr);
                            packetLost = false;
                            break;
                        }
                    }

                    process.waitFor();

                    // 更新滑动窗口
                    synchronized (networkQualityLock) {
                        // 更新RTT窗口
                        rttWindow.addLast(rtt);
                        if (rttWindow.size() > WINDOW_SIZE) {
                            rttWindow.removeFirst();
                        }

                        // 更新丢包窗口
                        lossWindow.addLast(packetLost);
                        if (lossWindow.size() > WINDOW_SIZE) {
                            lossWindow.removeFirst();
                        }

                        // 计算平均RTT
                        double totalRtt = 0.0;
                        int validRttCount = 0;
                        for (Double r : rttWindow) {
                            if (r > 0) {
                                totalRtt += r;
                                validRttCount++;
                            }
                        }
                        averageRtt = validRttCount > 0 ? totalRtt / validRttCount : 0.0;

                        // 计算丢包率
                        int lostCount = 0;
                        for (Boolean lost : lossWindow) {
                            if (lost) {
                                lostCount++;
                            }
                        }
                        packetLossRate = (lossWindow.size() > 0) ? (lostCount * 100.0) / lossWindow.size() : 0.0;
                    }

                    // 计算剩余等待时间
                    long elapsedTime = System.currentTimeMillis() - startTime;
                    long sleepTime = Math.max(0, UPDATE_INTERVAL - elapsedTime);
                    Thread.sleep(sleepTime);
                } catch (Exception e) {
                    System.out.println("ERROR: Ping监控失败 - " + e.getMessage());
                    e.printStackTrace();
                    try {
                        Thread.sleep(UPDATE_INTERVAL);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        });
    }

    private void updateNetworkStats(boolean isInitial) {
        try {
            long now = System.currentTimeMillis();
            
            // 获取当前网络类型
            NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
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

                    // 计算总带宽（上下行之和）
                    double totalMobileSpeed = (mobileRxSpeed + mobileTxSpeed) / 1024.0; // 转换为KB/s

                    // 获取网络质量指标
                    double currentPacketLossRate;
                    double currentAverageRtt;
                    synchronized (networkQualityLock) {
                        currentPacketLossRate = packetLossRate;
                        currentAverageRtt = averageRtt;
                    }

                    // 更新UI显示
                    final String wifiSpeedText = String.format("WiFi ↓%.1fKB/s ↑%.1fKB/s",
                            wifiRxSpeed / 1024, wifiTxSpeed / 1024);
                    final String mobileSpeedText = String.format("流量 ↓%.1fKB/s ↑%.1fKB/s",
                            mobileRxSpeed / 1024, mobileTxSpeed / 1024);
                    final String networkQualityText = String.format("丢包率: %.1f%% RTT: %.1fms",
                            currentPacketLossRate, currentAverageRtt);

                    handler.post(() -> {
                        TextView statView = floatingView.findViewById(R.id.stats_text);
                        statView.setText(wifiSpeedText + "\n" + mobileSpeedText + "\n" + networkQualityText);

                        // 根据蜂窝流量带宽更新背景颜色
                        if (totalMobileSpeed > MOBILE_SPEED_THRESHOLD) {
                            // 使用淡红色背景
                            floatingView.setBackgroundColor(Color.argb(50, 255, 0, 0));
                        } else {
                            // 恢复默认背景颜色
                            floatingView.setBackgroundColor(defaultBackgroundColor);
                        }
                    });

                    System.out.println("DEBUG: 速率更新 - " + wifiSpeedText + " | " + mobileSpeedText + " | " + networkQualityText);
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
        if (pingExecutor != null) {
            pingExecutor.shutdownNow();
        }
        if (floatingView != null && windowManager != null) {
            windowManager.removeView(floatingView);
        }
    }
} 