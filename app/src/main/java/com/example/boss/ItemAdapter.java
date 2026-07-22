package com.example.boss;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class ItemAdapter extends RecyclerView.Adapter<ItemAdapter.ViewHolder> {
    public List<RowData> dataList = new ArrayList<>();
    private OnRowClickListener listener;
    private DBHelper dbHelper;
    private DataManager dataManager;
    private Handler handler;
    private SimpleDateFormat timeFormat;
    private OnButtonClickListener buttonClickListener;
    public boolean isServerRunning;
    private Vibrator vibrator;
    private Context context;
    public String filteredString = "";
    private boolean hadRefreshedDay;
    private OnRowClickListener rowClickListener;
    private boolean showResetButton = false;
    private boolean showDeleteButton = false;

    public ItemAdapter(Context context) {
        this.context = context;
        EventBus.getDefault().register(this);
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        isServerRunning = false;
        dbHelper = new DBHelper(context);
        dataManager = DataManager.getInstance(context);
        timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        handler = new Handler(Looper.getMainLooper());
        hadRefreshedDay = false;
        refreshData();
        startTimeUpdate();
    }

    public enum ButtonType {
        EDIT,
        RESET,
        DELETE
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEventReceived(UpdateFloatWindowEvent event) {
        int eventType = event.type;
        switch (eventType) {
            case EventTypes.ADD_ITEM:
            case EventTypes.RESET_ITEM:
            case EventTypes.DELETE_ITEM:
            case EventTypes.NOTIFY_ITEM:
            case EventTypes.EDIT_ITEM:
                refreshData();
                break;
        }
    }

    private void refreshData() {
        if (dataManager != null) {
            List<RowData> newData = dataManager.getAllBosses();
            updateData(newData);
        }
    }

    public void updateData(List<RowData> newData) {
        List<RowData> filteredList = new ArrayList<>();
        for (RowData data : newData) {
            if (data.text1.toLowerCase().contains(filteredString.toLowerCase())) {
                filteredList.add(data);
            }
        }

        List<RowData> dataList_sorted = new ArrayList<>(filteredList);

        // 根据 spawnTime 排序（从小到大）
        Collections.sort(dataList_sorted, (o1, o2) -> {
            long time1 = o1.startTime + o1.spawnTime * 1000;
            long time2 = o2.startTime + o2.spawnTime * 1000;
            return Long.compare(time1, time2);
        });

        dataList = dataList_sorted;
        for (RowData d : dataList) d.setSpawnTime(context);
        notifyDataSetChanged();
    }

    public interface OnButtonClickListener {
        void onButtonClick(int position, ButtonType buttonType);
    }

    public interface OnRowClickListener {
        void onText1Click(int position);
        void onText2Click(int position);
        void onText3Click(int position);
    }

    public void setOnButtonClickListener(OnButtonClickListener listener) {
        this.buttonClickListener = listener;
    }

    private void startTimeUpdate() {
        Runnable timeUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (dataList.size() > 0) {
                    for (int i = 0; i < dataList.size(); i++) {
                        RowData data = dataList.get(i);
                        long elapsedSeconds = data.spawnTime - ((System.currentTimeMillis() - data.startTime) / 1000);
                        if (elapsedSeconds >= 0) {
                            // 更新剩余时间（text3）
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
                            if (!newTimeText.equals(data.text3)) {
                                data.text3 = newTimeText;
                                notifyItemChanged(i, "time");
                            }

                            // 检查是否需要通知
                            if (elapsedSeconds <= data.notifyTime && !data.isNotified && data.needNotify) {
                                if (!isServerRunning) {
                                    // 发送通知事件给 TimerService
                                    EventBus.getDefault().post(new BossNotificationEvent(data.text1, elapsedSeconds));
                                }
                                data.isNotified = true;
                                dataManager.setIsNotified(data.id, true);
                            }
                        } else if (data.autoReset && data.spawnTime > 0) {
                            // 自动重置
                            long currentTime = System.currentTimeMillis();
                            data.startTime = data.startTime + data.spawnTime * 1000;
                            while (data.startTime + data.spawnTime * 1000 < currentTime) {
                                data.startTime = data.startTime + data.spawnTime * 1000;
                            }
                            // 使用带 Context 的方法更新 text2
                            data.setSpawnTime(context);
                            data.isNotified = false;
                            if (!isServerRunning) {
                                if (dataManager.isShowingSharedData() && data.docId != null) {
                                    dataManager.resetBossShared(data.id, data.startTime);
                                } else {
                                    dataManager.resetBossStartTime(data.id, data.startTime);
                                }
                            }
                            notifyItemChanged(i);
                        } else {
                            // 已过期且不自动重置，显示“已刷新”
                            String refreshed = context.getString(R.string.refreshed);
                            if (!refreshed.equals(data.text2)) {
                                data.text2 = refreshed;
                                data.text3 = "00:00";
                                notifyItemChanged(i);
                            }
                        }
                    }

                    // 跨天刷新（每天0点重新计算日期前缀）
                    if (!hadRefreshedDay && Calendar.getInstance().get(Calendar.HOUR_OF_DAY) == 0) {
                        for (RowData data : dataList) {
                            data.setSpawnTime(context);
                        }
                        notifyDataSetChanged();
                        hadRefreshedDay = true;
                    } else if (hadRefreshedDay && Calendar.getInstance().get(Calendar.HOUR_OF_DAY) != 0) {
                        hadRefreshedDay = false;
                    }
                }
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(timeUpdateRunnable);
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
        refreshData();
    }

    public void setOnRowClickListener(OnRowClickListener listener) {
        this.listener = listener;
        this.rowClickListener = listener;
    }

    public void setShowResetButton(boolean show) {
        this.showResetButton = show;
        notifyDataSetChanged();
    }

    public boolean isShowResetButton() {
        return showResetButton;
    }

    public void setShowDeleteButton(boolean show) {
        this.showDeleteButton = show;
        notifyDataSetChanged();
    }

    public boolean isShowDeleteButton() {
        return showDeleteButton;
    }

    public void addRow(RowData data) {
        dataList.add(0, data);
        notifyItemInserted(0);
        updateData(dataList);
    }

    public void deleteRow(int position) {
        if (position >= 0 && position < dataList.size()) {
            RowData data = dataList.get(position);
            if (dataManager.isShowingSharedData()) {
                dataManager.deleteBossShared(data.id);
            } else {
                dataManager.deleteBoss(data.id);
            }
            dataList.remove(position);
            notifyItemRemoved(position);
        }
        EventBus.getDefault().post(new UpdateFloatWindowEvent(EventTypes.DELETE_ITEM, position));
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        EventBus.getDefault().unregister(this);
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_boss, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        RowData data = dataList.get(position);
        if (data.decreasingMode && data.deathCount < data.decreasingCount) {
            holder.text1.setText("▼" + data.deathCount + "/" + data.decreasingCount + " " + data.text1);
        } else {
            holder.text1.setText(data.text1);
        }
        holder.text2.setText(data.text2);
        holder.text3.setText(data.text3);

        // 计算剩余时间用于颜色
        long elapsedSeconds = data.spawnTime - ((System.currentTimeMillis() - data.startTime) / 1000);
        if (elapsedSeconds < data.notifyTime) {
            holder.text3.setTextColor(context.getResources().getColor(android.R.color.holo_red_dark));
        } else {
            holder.text3.setTextColor(context.getResources().getColor(android.R.color.black));
        }

        holder.editButton.setOnClickListener(v -> {
            if (dataManager.isShowingSharedData() && !dataManager.canEdit()) return;
            if (buttonClickListener != null) {
                buttonClickListener.onButtonClick(position, ButtonType.EDIT);
            }
        });

        holder.resetButton.setOnClickListener(v -> {
            if (dataManager.isShowingSharedData() && !dataManager.canReset()) return;
            if (buttonClickListener != null) {
                buttonClickListener.onButtonClick(position, ButtonType.RESET);
            }
        });

        holder.deleteButton.setOnClickListener(v -> {
            if (dataManager.isShowingSharedData() && !dataManager.canDelete()) return;
            if (buttonClickListener != null) {
                buttonClickListener.onButtonClick(position, ButtonType.DELETE);
            }
        });

        if (dataManager.isShowingSharedData()) {
            holder.editButton.setVisibility(dataManager.canEdit() ? View.VISIBLE : View.INVISIBLE);
            holder.resetButton.setVisibility(showResetButton && dataManager.canReset() ? View.VISIBLE : View.INVISIBLE);
            holder.deleteButton.setVisibility(showDeleteButton && dataManager.canDelete() ? View.VISIBLE : View.INVISIBLE);
        } else {
            holder.editButton.setVisibility(View.VISIBLE);
            holder.resetButton.setVisibility(showResetButton ? View.VISIBLE : View.INVISIBLE);
            holder.deleteButton.setVisibility(showDeleteButton ? View.VISIBLE : View.INVISIBLE);
        }

        holder.checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!buttonView.isPressed()) return;
            data.needNotify = isChecked;
            dataManager.setNeedNotify(data.id, isChecked);
            refreshData();
            EventBus.getDefault().post(new UpdateFloatWindowEvent(EventTypes.NOTIFY_ITEM, position));
        });

        boolean canEdit = !dataManager.isShowingSharedData() || dataManager.canEdit();
        holder.text1.setOnClickListener(v -> {
            if (canEdit && rowClickListener != null) {
                rowClickListener.onText1Click(position);
            }
        });
        holder.text2.setOnClickListener(v -> {
            if (canEdit && rowClickListener != null) {
                rowClickListener.onText2Click(position);
            }
        });
        holder.text3.setOnClickListener(v -> {
            if (canEdit && rowClickListener != null) {
                rowClickListener.onText3Click(position);
            }
        });


        holder.bind(data);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position, List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position);
            return;
        }
        if (dataList == null || position >= dataList.size()) return;
        RowData data = dataList.get(position);
        for (Object payload : payloads) {
            if ("time".equals(payload)) {
                holder.text3.setText(data.text3);
                long elapsedSeconds = data.spawnTime - ((System.currentTimeMillis() - data.startTime) / 1000);
                if (elapsedSeconds >= 0 && elapsedSeconds < data.notifyTime) {
                    holder.text3.setTextColor(context.getResources().getColor(android.R.color.holo_red_dark));
                } else {
                    holder.text3.setTextColor(context.getResources().getColor(android.R.color.black));
                }
            }
        }
    }

    @Override
    public int getItemCount() {
        return dataList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView text1, text2, text3;
        ImageButton resetButton;
        ImageButton deleteButton;
        ImageButton editButton;
        CheckBox checkBox;

        ViewHolder(View view) {
            super(view);
            text1 = view.findViewById(R.id.text1);
            text2 = view.findViewById(R.id.text2);
            text3 = view.findViewById(R.id.text3);
            editButton = view.findViewById(R.id.btn_edit);
            resetButton = view.findViewById(R.id.btn_reset);
            deleteButton = view.findViewById(R.id.btn_delete);
            checkBox = view.findViewById(R.id.chk_isNotify);
        }

        public void bind(RowData data) {
            checkBox.setChecked(data.needNotify);
        }
    }
}