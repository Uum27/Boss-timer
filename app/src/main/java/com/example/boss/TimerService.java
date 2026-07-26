package com.example.boss;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.core.app.NotificationCompat;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.Locale;

public class TimerService extends Service {
    private static final String CHANNEL_ID = "boss_timer";
    private static final int NOTIFICATION_ID = 1;
    private Vibrator vibrator;

    @Override
    public void onCreate() {
        super.onCreate();
        vibrator = (Vibrator) this.getSystemService(Context.VIBRATOR_SERVICE);
        EventBus.getDefault().register(this);
        createNotificationChannel();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEventReceived(BossNotificationEvent event) {
        sendBossNotification(event.bossName, event.remainingTime);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 创建前台服务通知
        Notification notification = createNotification(
                getString(R.string.timer_service_notification_title),
                getString(R.string.timer_service_notification_text)
        );
        startForeground(NOTIFICATION_ID, notification);
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription(getString(R.string.timer_service_channel_description));
            channel.enableLights(true);
            channel.enableVibration(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification(String title, String content) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.recluse)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setDefaults(0)
                .setSound(null)
                .setVibrate(null)
                .setSilent(true)
                .setFullScreenIntent(createFullScreenIntent(), true)
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setOngoing(false)
                .build();
    }

    /**
     * 发送 Boss 刷新提醒通知
     * @param bossName Boss名称
     * @param remainingTime 剩余时间（秒）
     */
    public void sendBossNotification(String bossName, long remainingTime) {
        // 震动提醒
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.cancel();
            vibrator.vibrate(1000);
        }

        // 构建通知内容
        String title = getString(R.string.notification_title);
        String content = String.format(Locale.getDefault(),
                getString(R.string.notification_content),
                bossName,
                remainingTime / 3600,
                (remainingTime % 3600) / 60,
                remainingTime % 60);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.recluse)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setDefaults(0)
                .setSound(null)
                .setVibrate(null)
                .setSilent(true)
                .setAutoCancel(true)
                .setOngoing(false);

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            // 确保通知渠道存在
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
                if (channel == null) {
                    createNotificationChannel();
                }
            }
            // 发送通知（使用不同的 ID 避免覆盖前台服务通知）
            manager.notify(NOTIFICATION_ID + 1, builder.build());
        }
    }

    private PendingIntent createFullScreenIntent() {
        Intent fullScreenIntent = new Intent(this, MainActivity.class);
        fullScreenIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return PendingIntent.getActivity(
                this,
                0,
                fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 取消注册 EventBus
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this);
        }
        // 停止震动
        if (vibrator != null) {
            vibrator.cancel();
        }
    }
}