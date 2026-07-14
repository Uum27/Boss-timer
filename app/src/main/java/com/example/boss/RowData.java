package com.example.boss;

import android.content.Context;
import java.util.Calendar;
import java.util.Locale;

public class RowData {
    public long id;
    public String text1;
    public String text2;
    public String text3;
    public String extraInfo;
    public long startTime;
    public long spawnTime;
    public boolean isNotified = false;
    public boolean needNotify;
    public long notifyTime;
    boolean autoReset;
    boolean showInFloat;

    public RowData() {
        this.isNotified = false;
    }

    /**
     * 根据当前时间和开始时间计算显示文本（刷新日期+时间，或“已刷新”）
     * 如果是今天（相差0天），则不显示日期前缀，只显示时间（HH:mm:ss）
     * @param context 用于获取资源
     */
    public void setSpawnTime(Context context) {
        Calendar respawnCalendar = Calendar.getInstance();
        respawnCalendar.setTimeInMillis(this.startTime + this.spawnTime * 1000);

        Calendar currentCalendar = Calendar.getInstance();
        currentCalendar.setTimeInMillis(System.currentTimeMillis());

        long elapsedSeconds = this.startTime / 1000 + this.spawnTime - System.currentTimeMillis() / 1000;

        if (elapsedSeconds > 0) {
            // 构建时间字符串 HH:mm:ss
            String timeString = String.format(Locale.getDefault(), "%02d:%02d:%02d",
                    respawnCalendar.get(Calendar.HOUR_OF_DAY),
                    respawnCalendar.get(Calendar.MINUTE),
                    respawnCalendar.get(Calendar.SECOND));

            // 计算相差天数，用于前缀
            respawnCalendar.set(Calendar.HOUR_OF_DAY, 0);
            respawnCalendar.set(Calendar.MINUTE, 0);
            respawnCalendar.set(Calendar.SECOND, 0);
            respawnCalendar.set(Calendar.MILLISECOND, 0);

            currentCalendar.set(Calendar.HOUR_OF_DAY, 0);
            currentCalendar.set(Calendar.MINUTE, 0);
            currentCalendar.set(Calendar.SECOND, 0);
            currentCalendar.set(Calendar.MILLISECOND, 0);

            long diffInMillis = respawnCalendar.getTimeInMillis() - currentCalendar.getTimeInMillis();
            int daysDiff = (int) (diffInMillis / (1000 * 60 * 60 * 24));

            // 如果是今天（daysDiff == 0），不添加前缀，只显示时间
            if (daysDiff == 0) {
                this.text2 = timeString;
            } else {
                // 从 strings.xml 的 day_labels 数组中获取前缀
                String[] dayLabels = context.getResources().getStringArray(R.array.day_labels);
                String prefix;
                if (daysDiff > 0 && daysDiff < dayLabels.length) {
                    prefix = dayLabels[daysDiff];
                } else {
                    prefix = dayLabels[dayLabels.length - 1]; // 默认最后一项（“久”或类似）
                }
                this.text2 = prefix + " " + timeString;
            }
        } else {
            // 已刷新
            this.text2 = context.getString(R.string.refreshed);
        }
    }

    /**
     * 获取开始时间的显示文本（日期前缀 + 时间）
     * @param context 用于获取资源
     * @return 格式化的开始时间字符串
     */
    public String getStartTime(Context context) {
        long currentTime = System.currentTimeMillis();
        Calendar startCalendar = Calendar.getInstance();
        startCalendar.setTimeInMillis(this.startTime);

        Calendar currentCalendar = Calendar.getInstance();
        currentCalendar.setTimeInMillis(currentTime);

        String startTimeText = String.format(Locale.getDefault(), "%02d:%02d:%02d",
                startCalendar.get(Calendar.HOUR_OF_DAY),
                startCalendar.get(Calendar.MINUTE),
                startCalendar.get(Calendar.SECOND));

        // 计算相差天数
        startCalendar.set(Calendar.HOUR_OF_DAY, 0);
        startCalendar.set(Calendar.MINUTE, 0);
        startCalendar.set(Calendar.SECOND, 0);
        startCalendar.set(Calendar.MILLISECOND, 0);

        currentCalendar.set(Calendar.HOUR_OF_DAY, 0);
        currentCalendar.set(Calendar.MINUTE, 0);
        currentCalendar.set(Calendar.SECOND, 0);
        currentCalendar.set(Calendar.MILLISECOND, 0);

        long diffInMillis = currentCalendar.getTimeInMillis() - startCalendar.getTimeInMillis();
        int daysDiff = (int) (diffInMillis / (1000 * 60 * 60 * 24));

        // 从 strings.xml 的 start_day_labels 数组中获取前缀
        String[] startDayLabels = context.getResources().getStringArray(R.array.start_day_labels);
        String prefix;
        if (daysDiff >= 0 && daysDiff < startDayLabels.length) {
            prefix = startDayLabels[daysDiff];
        } else {
            prefix = startDayLabels[startDayLabels.length - 1]; // 默认最后一项
        }

        return prefix + " " + startTimeText;
    }
}