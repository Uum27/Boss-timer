package com.example.boss;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;
import androidx.recyclerview.widget.RecyclerView;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class FloatingWindowAdapter extends RecyclerView.Adapter<FloatingWindowAdapter.ViewHolder> {
    private List<RowData> dataList;
    private Handler handler;
    private Vibrator vibrator;
    private NotificationManager notificationManager;
    private static final int NOTIFICATION_ID = 1;
    private Context context; // 这个 context 需要可更新
    private DBHelper dbHelper;
    private ItemAdapter.OnButtonClickListener buttonClickListener;
    private RecyclerView recyclerView;
    private boolean hadRefreshedDay;

    public FloatingWindowAdapter(List<RowData> dataList, Context context) {
        this.context = context;
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "boss_timer",
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription(context.getString(R.string.notification_channel_description));
            channel.enableLights(true);
            channel.enableVibration(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            notificationManager.createNotificationChannel(channel);
        }

        this.dataList = dataList;
        dbHelper = new DBHelper(context);
        handler = new Handler(Looper.getMainLooper());
        hadRefreshedDay = false;
    }

    // 新增：更新 Context 的方法
    public void updateContext(Context newContext) {
        this.context = newContext;
        // 同时更新 dbHelper 的 context（因为 dbHelper 在 getAllBosses 中调用 data.setSpawnTime(context)）
        // 但 dbHelper 是 final 的，需要重新创建，或者我们可以在 updateData 中重新传入 context
        // 简单处理：重新创建 dbHelper
        this.dbHelper = new DBHelper(newContext);
        // 通知渠道可能也需要更新，但通知渠道名称不变，可以不更新
    }

    public void setRecyclerView(RecyclerView recyclerView) {
        this.recyclerView = recyclerView;
        startTimeUpdate();
    }

    public void setOnButtonClickListener(ItemAdapter.OnButtonClickListener listener) {
        this.buttonClickListener = listener;
    }

    private void startTimeUpdate() {
        Runnable timeUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < dataList.size(); i++) {
                    RowData data = dataList.get(i);
                    long elapsedSeconds = data.spawnTime - ((System.currentTimeMillis() - data.startTime) / 1000);

                    if (elapsedSeconds >= 0) {
                        String newTimeText;
                        if (elapsedSeconds / 3600 > 0) {
                            newTimeText = String.format(Locale.getDefault(), "%02d:%02d:%02d",
                                    elapsedSeconds / 3600,
                                    (elapsedSeconds % 3600) / 60,
                                    elapsedSeconds % 60);
                        } else {
                            newTimeText = String.format(Locale.getDefault(), "%02d:%02d",
                                    (elapsedSeconds % 3600) / 60,
                                    elapsedSeconds % 60);
                        }
                        data.text3 = newTimeText;

                        if (elapsedSeconds <= data.notifyTime && !data.isNotified && data.needNotify) {
                            if (vibrator.hasVibrator()) {
                                vibrator.vibrate(2000);
                            }

                            String title = context.getString(R.string.notification_title);
                            String content = String.format(Locale.getDefault(),
                                    context.getString(R.string.notification_content),
                                    data.text1,
                                    elapsedSeconds / 3600,
                                    (elapsedSeconds % 3600) / 60,
                                    elapsedSeconds % 60);

                            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "boss_timer")
                                    .setSmallIcon(R.drawable.recluse)
                                    .setContentTitle(title)
                                    .setContentText(content)
                                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                                    .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_LIGHTS)
                                    .setFullScreenIntent(createFullScreenIntent(), true)
                                    .setAutoCancel(true);

                            notificationManager.notify(NOTIFICATION_ID, builder.build());
                            data.isNotified = true;
                            dbHelper.setIsNotified(data.id, true);
                        }
                    } else if (data.autoReset && data.spawnTime > 0) {
                        long currentTime = System.currentTimeMillis();
                        data.startTime = data.startTime + data.spawnTime * 1000;
                        while (data.startTime + data.spawnTime * 1000 < currentTime) {
                            data.startTime = data.startTime + data.spawnTime * 1000;
                        }
                        data.setSpawnTime(context); // 使用当前上下文
                        data.isNotified = false;
                        dbHelper.resetBossStartTime(data.id, data.startTime);
                        EventBus.getDefault().post(new UpdateFloatWindowEvent(EventTypes.RESET_ITEM, i));
                        notifyItemChanged(i);
                    } else {
                        String refreshed = context.getString(R.string.refreshed);
                        if (!refreshed.equals(data.text2)) {
                            data.text2 = refreshed;
                            notifyItemChanged(i);
                        }
                    }
                }

                notifyDataSetChanged();

                if (!hadRefreshedDay && Calendar.getInstance().get(Calendar.HOUR_OF_DAY) == 0) {
                    for (RowData data : dataList) {
                        data.setSpawnTime(context);
                    }
                    notifyDataSetChanged();
                    hadRefreshedDay = true;
                } else if (hadRefreshedDay && Calendar.getInstance().get(Calendar.HOUR_OF_DAY) != 0) {
                    hadRefreshedDay = false;
                }

                handler.postDelayed(this, 1000);
            }
        };
        handler.post(timeUpdateRunnable);
    }

    private PendingIntent createFullScreenIntent() {
        Intent fullScreenIntent = new Intent(context, MainActivity.class);
        fullScreenIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return PendingIntent.getActivity(context, 0, fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.floating_item_boss, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        if (dataList == null || position >= dataList.size()) {
            holder.itemView.setVisibility(View.GONE);
            return;
        }

        RowData data = dataList.get(position);
        holder.text1.setText(data.text1);
        holder.text2.setText(data.text2);
        holder.text3.setText(data.text3);

        holder.text1.setSelected(true);
        holder.text2.setSelected(true);
        holder.text3.setSelected(true);

        holder.btnReset.setOnClickListener(v -> {
            if (buttonClickListener != null) {
                buttonClickListener.onButtonClick(position, ItemAdapter.ButtonType.RESET);
            }
        });

        long elapsedSeconds = data.spawnTime - ((System.currentTimeMillis() - data.startTime) / 1000);
        if (elapsedSeconds < data.notifyTime) {
            holder.text3.setTextColor(context.getResources().getColor(android.R.color.holo_red_dark));
        } else {
            holder.text3.setTextColor(context.getResources().getColor(android.R.color.white));
        }
    }

    @Override
    public int getItemCount() {
        return dataList.size();
    }

    public void updateData(List<RowData> newData) {
        if (dataList != null) {
            dataList.clear();
        }

        List<RowData> dataList_sorted = new ArrayList<>(newData);

        Collections.sort(dataList_sorted, (o1, o2) -> {
            long time1 = o1.startTime + o1.spawnTime * 1000;
            long time2 = o2.startTime + o2.spawnTime * 1000;
            return Long.compare(time1, time2);
        });

        List<RowData> dataListShow = new ArrayList<>();
        for (RowData data : dataList_sorted) {
            if (data.showInFloat) {
                data.setSpawnTime(context); // 使用当前上下文更新 text2
                dataListShow.add(data);
            }
        }
        dataList = dataListShow;

        notifyDataSetChanged();
    }

    public void resetTime(int position) {
        if (position >= 0 && position < dataList.size()) {
            RowData data = dataList.get(position);
            data.startTime = System.currentTimeMillis();
            data.setSpawnTime(context);
            data.isNotified = false;
            dbHelper.setIsNotified(data.id, false);
            dbHelper.resetBossStartTime(data.id, data.startTime);
            notifyItemChanged(position);
        }
        EventBus.getDefault().post(new UpdateFloatWindowEvent(EventTypes.RESET_ITEM, position));
    }

    public void stopTimer() {
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView text1, text2, text3;
        ImageButton btnReset;

        ViewHolder(View view) {
            super(view);
            text1 = view.findViewById(R.id.text1);
            text1.setTextColor(Color.WHITE);
            text2 = view.findViewById(R.id.text2);
            text2.setTextColor(Color.WHITE);
            text3 = view.findViewById(R.id.text3);
            text3.setTextColor(Color.WHITE);
            btnReset = view.findViewById(R.id.btn_floating_reset);
        }
    }
}