package com.example.boss;

public class BossNotificationEvent {
    public String bossName;
    public long remainingTime;

    public BossNotificationEvent(String bossName, long remainingTime) {
        this.bossName = bossName;
        this.remainingTime = remainingTime;
    }

    public String getBossName() {
        return bossName;
    }

    public long getRemainingTime() {
        return remainingTime;
    }
}
