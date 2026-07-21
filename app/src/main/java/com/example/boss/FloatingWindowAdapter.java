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
import java.util.Map;
import java.util.HashMap;

public class FloatingWindowAdapter extends RecyclerView.Adapter<FloatingWindowAdapter.ViewHolder> {
    public interface OnCrossNotifyListener {
        void onCrossNotify();
    }
    private OnCrossNotifyListener crossNotifyListener;

    public void setOnCrossNotifyListener(OnCrossNotifyListener listener) {
        this.crossNotifyListener = listener;
    }
    private List<RowData> dataList;
    private Handler handler;
    private Vibrator vibrator;
    private NotificationManager notificationManager;
    private static final int NOTIFICATION_ID = 100;
    private Context context; // 这个 context 需要可更新
    private DBHelper dbHelper;
    private DataManager dataManager;
    private ItemAdapter.OnButtonClickListener buttonClickListener;
    private RecyclerView recyclerView;
    private boolean hadRefreshedDay;
    private boolean resetLocked = false;
    private final Handler resetLockHandler = new Handler(Looper.getMainLooper());
    private Runnable resetLockRunnable;
    private boolean hasLocalNotify = false;
    private boolean hasSharedNotify = false;
    private boolean suppressNextNotify = false;
    private java.util.Set<Long> notifiedBossIds = new java.util.HashSet<>();

    public void suppressNextNotification() { suppressNextNotify = true; }

    public boolean hasLocalNotify() { return hasLocalNotify; }
    public void clearLocalNotify() { hasLocalNotify = false; }
    public boolean hasSharedNotify() { return hasSharedNotify; }
    public void clearSharedNotify() { hasSharedNotify = false; }

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
        dataManager = DataManager.getInstance(context);
        handler = new Handler(Looper.getMainLooper());
        hadRefreshedDay = false;
    }

    // 新增：更新 Context 的方法
    public void updateContext(Context newContext) {
        this.context = newContext;
        this.dbHelper = new DBHelper(newContext);
        this.dataManager = DataManager.getInstance(newContext);
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
                notifiedBossIds.clear();
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

                        checkAndNotify(data, elapsedSeconds);
                    } else if (data.autoReset && data.spawnTime > 0) {
                        long currentTime = System.currentTimeMillis();
                        data.startTime = data.startTime + data.spawnTime * 1000;
                        while (data.startTime + data.spawnTime * 1000 < currentTime) {
                            data.startTime = data.startTime + data.spawnTime * 1000;
                        }
                        data.setSpawnTime(context); // 使用当前上下文
                        data.isNotified = false;
                        if (data.docId != null && data.roomId != null) {
                            dataManager.resetBossShared(data.id, data.startTime);
                        } else {
                            dataManager.resetBossStartTime(data.id, data.startTime);
                        }
                        EventBus.getDefault().post(new UpdateFloatWindowEvent(EventTypes.RESET_ITEM, i));
                        notifyItemChanged(i);
                    } else {
                        String refreshed = context.getString(R.string.refreshed);
                        if (!refreshed.equals(data.text2)) {
                            data.text2 = refreshed;
                            data.text3 = "00:00";
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

                if (dataManager.isShowingSharedData()) {
                    List<RowData> localBosses = dbHelper.getAllBosses();
                    for (RowData b : localBosses) {
                        long el = b.spawnTime - ((System.currentTimeMillis() - b.startTime) / 1000);
                        if (el >= 0 && el <= b.notifyTime && !b.isNotified && b.needNotify) {
                            checkAndNotify(b, el);
                            if (!hasLocalNotify) {
                                hasLocalNotify = true;
                                if (crossNotifyListener != null) crossNotifyListener.onCrossNotify();
                            }
                        }
                    }
                } else if (dataManager.isSharedMode()) {
                    String roomId = dataManager.getCurrentRoomId();
                    if (roomId != null) {
                        List<RowData> sharedBosses = dbHelper.getAllBossesByRoom(roomId);
                        for (RowData b : sharedBosses) {
                            long el = b.spawnTime - ((System.currentTimeMillis() - b.startTime) / 1000);
                            if (el >= 0 && el <= b.notifyTime && !b.isNotified && b.needNotify) {
                                checkAndNotify(b, el);
                                if (!hasSharedNotify) {
                                    hasSharedNotify = true;
                                    if (crossNotifyListener != null) crossNotifyListener.onCrossNotify();
                                }
                            }
                        }
                    }
                }

                // 跨房间提醒：扫描其他已收藏的房间的boss
                List<String> allRoomIds = dbHelper.getAllRoomIds();
                String curRoomId = dataManager.getCurrentRoomId();
                java.util.Set<String> checkedRooms = new java.util.HashSet<>();
                for (String rId : allRoomIds) {
                    if (rId == null || rId.equals(curRoomId)) continue;
                    if (checkedRooms.contains(rId)) continue;
                    checkedRooms.add(rId);
                    List<RowData> roomBosses = dbHelper.getAllBossesByRoom(rId);
                    boolean hasNotify = false;
                    for (RowData b : roomBosses) {
                        long el = b.spawnTime - ((System.currentTimeMillis() - b.startTime) / 1000);
                        if (el >= 0 && el <= b.notifyTime && !b.isNotified && b.needNotify) {
                            checkAndNotify(b, el);
                            hasNotify = true;
                        }
                    }
                    if (hasNotify) {
                        dataManager.addPendingNotifyRoom(rId);
                        if (crossNotifyListener != null) crossNotifyListener.onCrossNotify();
                    }
                }

                // 清除一次性通知抑制标志
                if (suppressNextNotify) suppressNextNotify = false;

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

    private void checkAndNotify(RowData data, long elapsedSeconds) {
        if (suppressNextNotify) return;
        if (elapsedSeconds <= data.notifyTime && !data.isNotified && data.needNotify) {
            if (notifiedBossIds.contains(data.id)) return;
            notifiedBossIds.add(data.id);
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
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setCategory(NotificationCompat.CATEGORY_EVENT)
                    .setAutoCancel(true)
                    .setOngoing(false);
            notificationManager.notify(NOTIFICATION_ID, builder.build());
            data.isNotified = true;
            dataManager.setIsNotified(data.id, true);
        }
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
        if (data.decreasingMode && data.deathCount < data.decreasingCount) {
            holder.text1.setText("▼" + data.deathCount + "/" + data.decreasingCount + " " + data.text1);
        } else {
            holder.text1.setText(data.text1);
        }
        holder.text2.setText(data.text2);
        holder.text3.setText(data.text3);

        holder.text1.setSelected(true);
        holder.text2.setSelected(true);
        holder.text3.setSelected(true);

        holder.btnReset.setOnClickListener(v -> {
            if (resetLocked) return;
            if (buttonClickListener != null) {
                buttonClickListener.onButtonClick(position, ItemAdapter.ButtonType.RESET);
            }
            resetLocked = true;
            notifyDataSetChanged();
            resetLockHandler.removeCallbacks(resetLockRunnable);
            resetLockRunnable = () -> {
                resetLocked = false;
                notifyDataSetChanged();
            };
            resetLockHandler.postDelayed(resetLockRunnable, 3000);
        });

        long elapsedSeconds = data.spawnTime - ((System.currentTimeMillis() - data.startTime) / 1000);

        boolean canShow;
        if (data.autoReset && data.spawnTime > 300) {
            canShow = false;
        } else if (elapsedSeconds <= 0) {
            canShow = !resetLocked;
        } else {
            int unrefreshedIdx = -1;
            for (int j = 0; j <= position; j++) {
                RowData d = dataList.get(j);
                if (d.spawnTime - ((System.currentTimeMillis() - d.startTime) / 1000) > 0) {
                    unrefreshedIdx++;
                }
            }
            if (unrefreshedIdx == 0 && elapsedSeconds <= 300) {
                canShow = !resetLocked;
            } else if (unrefreshedIdx == 1 && elapsedSeconds <= 180) {
                canShow = !resetLocked;
            } else {
                canShow = false;
            }
        }
        if (dataManager.isShowingSharedData()) {
            holder.btnReset.setVisibility(canShow && dataManager.canReset() ? View.VISIBLE : View.INVISIBLE);
        } else {
            holder.btnReset.setVisibility(canShow ? View.VISIBLE : View.INVISIBLE);
        }

        if (elapsedSeconds < data.notifyTime) {
            holder.text3.setTextColor(context.getResources().getColor(android.R.color.holo_red_dark));
        } else {
            holder.text3.setTextColor(context.getResources().getColor(android.R.color.white));
        }
        // 点击 text2 切换显示格式
        holder.text2.setOnClickListener(null);
        holder.text2.setOnClickListener(v -> {
            if (elapsedSeconds <= 0) {
                if (!resetLocked && buttonClickListener != null) {
                    buttonClickListener.onButtonClick(position, ItemAdapter.ButtonType.RESET);
                    resetLocked = true;
                    notifyDataSetChanged();
                    resetLockHandler.removeCallbacks(resetLockRunnable);
                    resetLockRunnable = () -> { resetLocked = false; notifyDataSetChanged(); };
                    resetLockHandler.postDelayed(resetLockRunnable, 3000);
                }
            } else {
                data.showSeconds = !data.showSeconds;
                data.setSpawnTime(context);
                notifyItemChanged(position);
            }
        });
        // ★ 分割线控制：最后一项隐藏
        View divider = holder.itemView.findViewById(R.id.divider);
        if (position == getItemCount() - 1) {
            divider.setVisibility(View.GONE);
        } else {
            divider.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public int getItemCount() {
        return dataList.size();
    }

    public void updateData(List<RowData> newData) {
        // 保存当前 showSeconds 状态
        Map<Long, Boolean> showSecondsMap = new HashMap<>();
        if (dataList != null) {
            for (RowData old : dataList) {
                showSecondsMap.put(old.id, old.showSeconds);
            }
        }

        // 过滤（只显示 showInFloat == true）
        List<RowData> filteredList = new ArrayList<>();
        for (RowData data : newData) {
            if (data.showInFloat) {
                filteredList.add(data);
            }
        }

        // 排序
        Collections.sort(filteredList, (o1, o2) -> {
            long time1 = o1.startTime + o1.spawnTime * 1000;
            long time2 = o2.startTime + o2.spawnTime * 1000;
            return Long.compare(time1, time2);
        });

        // 恢复 showSeconds 并重新生成 text2
        for (RowData data : filteredList) {
            if (showSecondsMap.containsKey(data.id)) {
                data.showSeconds = showSecondsMap.get(data.id);
            }
            data.setSpawnTime(context); // 应用当前 showSeconds 刷新 text2
        }

        dataList = filteredList;
        notifyDataSetChanged();
    }

    public void resetTime(int position) {
        if (position >= 0 && position < dataList.size()) {
            RowData data = dataList.get(position);
            data.startTime = System.currentTimeMillis();
            if (data.decreasingMode && data.deathCount < data.decreasingCount && data.decreasingSeconds > 0) {
                data.deathCount++;
                if (data.initialSpawnTime == 0) data.initialSpawnTime = data.spawnTime;
                data.spawnTime = data.initialSpawnTime - data.deathCount * data.decreasingSeconds;
                if (data.spawnTime < 0) data.spawnTime = 0;
                dbHelper.editBoss(data);
            }
            data.setSpawnTime(context);
            data.isNotified = false;
            dataManager.setIsNotified(data.id, false);
            if (dataManager.isShowingSharedData() && data.docId != null) {
                dataManager.resetBossShared(data.id, data.startTime);
            } else {
                dataManager.resetBossStartTime(data.id, data.startTime);
            }
            notifyItemChanged(position);
        }
        EventBus.getDefault().post(new UpdateFloatWindowEvent(EventTypes.RESET_ITEM, position));
    }

    public void stopTimer() {
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (resetLockHandler != null) {
            resetLockHandler.removeCallbacks(resetLockRunnable);
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