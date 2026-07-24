package com.example.boss;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

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
    private List<RowData> dataList;
    private Context context;
    private DBHelper dbHelper;
    private DataManager dataManager;
    private ItemAdapter.OnButtonClickListener buttonClickListener;
    private RecyclerView recyclerView;
    private boolean hadRefreshedDay;
    private boolean resetLocked = false;
    private final android.os.Handler resetLockHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable resetLockRunnable;

    public FloatingWindowAdapter(List<RowData> dataList, Context context) {
        this.context = context;
        this.dataList = dataList;
        dbHelper = new DBHelper(context);
        dataManager = DataManager.getInstance(context);
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
    }

    public void setOnButtonClickListener(ItemAdapter.OnButtonClickListener listener) {
        this.buttonClickListener = listener;
    }

    public void onTick() {
        if (dataList == null || dataList.size() == 0) return;
        for (int i = 0; i < dataList.size(); i++) {
            RowData data = dataList.get(i);
            long elapsedSeconds = data.spawnTime - ((System.currentTimeMillis() - data.startTime) / 1000);

            if (elapsedSeconds > 0) {
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
            } else if (data.autoReset && data.spawnTime > 0) {
                long currentTime = System.currentTimeMillis();
                long cycle = data.spawnTime * 1000;
                long cycles = (currentTime - data.startTime) / cycle;
                data.startTime = data.startTime + cycles * cycle;
                while (data.startTime + cycle <= currentTime) {
                    data.startTime = data.startTime + cycle;
                }
                data.setSpawnTime(context);
                if (dataManager.isShowingSharedData() && data.docId != null) {
                    dataManager.resetBossShared(data.id, data.startTime);
                } else {
                    dataManager.resetBossStartTime(data.id, data.startTime);
                }
                long newElapsed = data.spawnTime - ((currentTime - data.startTime) / 1000);
                if (newElapsed >= 3600) {
                    data.text3 = String.format(Locale.getDefault(), "%02d:%02d:%02d", newElapsed / 3600, (newElapsed % 3600) / 60, newElapsed % 60);
                } else {
                    data.text3 = String.format(Locale.getDefault(), "%02d:%02d", (newElapsed % 3600) / 60, newElapsed % 60);
                }
                notifyItemChanged(i);
            } else {
                String refreshed = context.getString(R.string.refreshed);
                if (!refreshed.equals(data.text2)) {
                    data.text2 = refreshed;
                    data.text3 = "00:00";
                dataManager.addAutoLog(data.id, data.startTime, data.spawnTime);
                notifyItemChanged(i);
                }
            }
        }

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
            holder.text3.setTextColor(0xFFC0392B);
        } else {
            holder.text3.setTextColor(context.getResources().getColor(android.R.color.white));
        }
        // 点击 text2 切换显示格式
        holder.text2.setOnClickListener(null);
        if (elapsedSeconds > 0) {
            holder.text2.setOnClickListener(v -> {
                data.showSeconds = !data.showSeconds;
                data.setSpawnTime(context);
                notifyItemChanged(position);
            });
        }
        // ★ 分割线控制：最后一项隐藏
        View divider = holder.itemView.findViewById(R.id.divider);
        if (position == getItemCount() - 1) {
            divider.setVisibility(View.GONE);
        } else {
            divider.setVisibility(View.VISIBLE);
        }
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
                    holder.text3.setTextColor(0xFFC0392B);
                } else {
                    holder.text3.setTextColor(context.getResources().getColor(android.R.color.white));
                }
            }
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

        // 排序：已刷新且>2小时的排到底部，其余按结束时间升序
        Collections.sort(filteredList, (o1, o2) -> {
            long now = System.currentTimeMillis();
            long elapsed1 = o1.spawnTime - ((now - o1.startTime) / 1000);
            long elapsed2 = o2.spawnTime - ((now - o2.startTime) / 1000);
            boolean isOld1 = elapsed1 < 0 && !o1.autoReset && Math.abs(elapsed1) > 7200;
            boolean isOld2 = elapsed2 < 0 && !o2.autoReset && Math.abs(elapsed2) > 7200;
            if (isOld1 && !isOld2) return 1;
            if (!isOld1 && isOld2) return -1;
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
            long elapsed = data.spawnTime - ((System.currentTimeMillis() - data.startTime) / 1000);
            if (elapsed >= 3600) {
                data.text3 = String.format(Locale.getDefault(), "%02d:%02d:%02d", elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60);
            } else if (elapsed >= 0) {
                data.text3 = String.format(Locale.getDefault(), "%02d:%02d", (elapsed % 3600) / 60, elapsed % 60);
            } else {
                data.text3 = "00:00";
            }
        }

        dataList = filteredList;
        notifyDataSetChanged();
    }

    public void resetTime(int position) {
        if (position >= 0 && position < dataList.size()) {
            RowData data = dataList.get(position);
            long oldEndTime = data.startTime + data.spawnTime * 1000;
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
                dataManager.resetBossAndLog(data, oldEndTime);
            } else {
                dataManager.resetBossStartTime(data.id, data.startTime);
            }
            notifyItemChanged(position);
        }
        EventBus.getDefault().post(new UpdateFloatWindowEvent(EventTypes.RESET_ITEM, position));
    }

    public void stopTimer() {
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