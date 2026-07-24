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
    public boolean showSeconds = false;
    public String docId;
    public String roomId;
    public long updateTime;
    public String syncStatus = "synced";
    public boolean decreasingMode = false;
    public int decreasingSeconds = 0;
    public int decreasingCount = 0;
    public int deathCount = 0;
    public long initialSpawnTime = 0;
    public String editTimeType = null;
    public long enteredValue = 0;

    public RowData() {
        this.isNotified = false;
    }

    public void setSpawnTime(Context context) {
        long elapsedSeconds = this.startTime / 1000 + this.spawnTime - System.currentTimeMillis() / 1000;

        if (elapsedSeconds > 0) {
            setSpawnTimeDisplay(context, this.startTime + this.spawnTime * 1000);
        } else if (this.autoReset && this.spawnTime > 0) {
            long currentTime = System.currentTimeMillis();
            long cycle = this.spawnTime * 1000;
            long nextSpawn = this.startTime + ((currentTime - this.startTime) / cycle + 1) * cycle;
            setSpawnTimeDisplay(context, nextSpawn);
        } else {
            this.text2 = context.getString(R.string.refreshed);
        }
    }

    private void setSpawnTimeDisplay(Context context, long spawnTimeMillis) {
        Calendar respawnCalendar = Calendar.getInstance();
        respawnCalendar.setTimeInMillis(spawnTimeMillis);

        Calendar currentCalendar = Calendar.getInstance();
        currentCalendar.setTimeInMillis(System.currentTimeMillis());

        String timeString;
        if (showSeconds) {
            timeString = String.format(Locale.getDefault(), "%02d:%02d:%02d",
                    respawnCalendar.get(Calendar.HOUR_OF_DAY),
                    respawnCalendar.get(Calendar.MINUTE),
                    respawnCalendar.get(Calendar.SECOND));
        } else {
            timeString = String.format(Locale.getDefault(), "%02d:%02d",
                    respawnCalendar.get(Calendar.HOUR_OF_DAY),
                    respawnCalendar.get(Calendar.MINUTE));
        }

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

        if (daysDiff == 0) {
            this.text2 = timeString;
        } else {
            String[] dayLabels = context.getResources().getStringArray(R.array.day_labels);
            String prefix;
            if (daysDiff > 0 && daysDiff < dayLabels.length) {
                prefix = dayLabels[daysDiff];
            } else {
                prefix = dayLabels[dayLabels.length - 1];
            }
            this.text2 = prefix + " " + timeString;
        }
    }

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

        String[] startDayLabels = context.getResources().getStringArray(R.array.start_day_labels);
        String prefix;
        if (daysDiff >= 0 && daysDiff < startDayLabels.length) {
            prefix = startDayLabels[daysDiff];
        } else {
            prefix = startDayLabels[startDayLabels.length - 1];
        }

        return prefix + " " + startTimeText;
    }
}
