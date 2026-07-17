package com.example.boss;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DBHelper extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "boss.db";
    private static final int DATABASE_VERSION = 1;

    public static final String TABLE_BOSS = "boss";
    public static final String COLUMN_ID = "id";
    public static final String COLUMN_NAME = "name";
    public static final String COLUMN_SPAWN = "spawn";
    public static final String COLUMN_EXTRA = "extra";
    public static final String COLUMN_START_TIME = "start_time";
    public static final String COLUMN_IS_NOTIFIED = "is_notify";
    public static final String COLUMN_NEED_NOTIFY = "need_notify";
    public static final String COLUMN_NOTIFY_TIME = "notify_time";
    public static final String COLUMN_AUTO_RESET = "auto_reset";
    public static final String COLUMN_SHOW_IN_FLOAT = "show_in_float";

    private static final String CREATE_TABLE_BOSS =
            "CREATE TABLE " + TABLE_BOSS + "("
                    + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + COLUMN_NAME + " TEXT NOT NULL,"
                    + COLUMN_SPAWN + " INTEGER NOT NULL,"
                    + COLUMN_EXTRA + " TEXT,"
                    + COLUMN_START_TIME + " INTEGER,"
                    + COLUMN_IS_NOTIFIED + " INTEGER DEFAULT 0,"
                    + COLUMN_NEED_NOTIFY + " INTEGER DEFAULT 1,"
                    + COLUMN_NOTIFY_TIME + " INTEGER NOT NULL,"
                    + COLUMN_AUTO_RESET + " INTEGER DEFAULT 1,"
                    + COLUMN_SHOW_IN_FLOAT + " INTEGER DEFAULT 1)";

    // 新增 Context 成员变量
    private Context context;

    public DBHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        this.context = context;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_TABLE_BOSS);
    }

    public static void deleteDatabase(Context context) {
        context.deleteDatabase(DATABASE_NAME);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_BOSS);
        onCreate(db);
    }

    public long insertBoss(RowData data) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_NAME, data.text1);
        values.put(COLUMN_SPAWN, data.spawnTime);
        values.put(COLUMN_EXTRA, data.extraInfo);
        values.put(COLUMN_START_TIME, data.startTime);
        values.put(COLUMN_IS_NOTIFIED, data.isNotified ? 1 : 0);
        values.put(COLUMN_NEED_NOTIFY, data.needNotify ? 1 : 0);
        values.put(COLUMN_NOTIFY_TIME, data.notifyTime);
        values.put(COLUMN_AUTO_RESET, data.autoReset ? 1 : 0);
        values.put(COLUMN_SHOW_IN_FLOAT, data.showInFloat ? 1 : 0);

        long id = db.insert(TABLE_BOSS, null, values);
        db.close();
        return id;
    }

    public List<RowData> getDatabase() {
        List<RowData> bossList = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();

        String[] columns = {
                COLUMN_ID,
                COLUMN_NAME,
                COLUMN_SPAWN,
                COLUMN_EXTRA,
                COLUMN_START_TIME,
                COLUMN_IS_NOTIFIED,
                COLUMN_NEED_NOTIFY,
                COLUMN_NOTIFY_TIME,
                COLUMN_AUTO_RESET,
                COLUMN_SHOW_IN_FLOAT
        };

        Cursor cursor = db.query(TABLE_BOSS, columns, null, null, null, null, COLUMN_ID + " ASC");

        if (cursor.moveToFirst()) {
            do {
                RowData data = new RowData();
                data.id = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ID));
                data.text1 = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NAME));
                data.spawnTime = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_SPAWN));
                data.extraInfo = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_EXTRA));
                data.startTime = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_START_TIME));
                data.isNotified = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_IS_NOTIFIED)) == 1;
                data.needNotify = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_NEED_NOTIFY)) == 1;
                data.notifyTime = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_NOTIFY_TIME));
                data.autoReset = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_AUTO_RESET)) == 1;
                data.showInFloat = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_SHOW_IN_FLOAT)) == 1;
                // 注意：此方法用于数据库查看，不涉及多语言，所以不调用 setSpawnTime
                bossList.add(data);
            } while (cursor.moveToNext());
        }

        cursor.close();
        db.close();
        return bossList;
    }

    public RowData getLatestBoss() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(TABLE_BOSS, null, null, null, null, null,
                COLUMN_ID + " DESC", "1");

        RowData data = null;
        if (cursor.moveToFirst()) {
            data = new RowData();
            data.text1 = cursor.getString(cursor.getColumnIndex(COLUMN_NAME));
            data.spawnTime = cursor.getLong(cursor.getColumnIndex(COLUMN_SPAWN));
            data.startTime = cursor.getLong(cursor.getColumnIndex(COLUMN_START_TIME));
            long elapsedSeconds = data.spawnTime - ((System.currentTimeMillis() - data.startTime) / 1000);
            if (elapsedSeconds < 0)
                elapsedSeconds = 0;
            if (elapsedSeconds / 3600 > 0)
                data.text3 = String.format(Locale.getDefault(), "%02d:%02d:%02d",
                        elapsedSeconds / 3600,
                        (elapsedSeconds % 3600) / 60,
                        elapsedSeconds % 60);
            else
                data.text3 = String.format(Locale.getDefault(), "%02d:%02d",
                        (elapsedSeconds % 3600) / 60,
                        elapsedSeconds % 60);
            data.isNotified = cursor.getInt(cursor.getColumnIndex(COLUMN_IS_NOTIFIED)) == 1;
            data.needNotify = cursor.getInt(cursor.getColumnIndex(COLUMN_NEED_NOTIFY)) == 1;
            data.notifyTime = cursor.getLong(cursor.getColumnIndex(COLUMN_NOTIFY_TIME));
            data.autoReset = cursor.getInt(cursor.getColumnIndex(COLUMN_AUTO_RESET)) == 1;
            data.showInFloat = cursor.getInt(cursor.getColumnIndex(COLUMN_SHOW_IN_FLOAT)) == 1;
        }

        cursor.close();
        return data;
    }

    public List<RowData> getAllBosses() {
        List<RowData> bossList = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();

        Cursor cursor = db.query(TABLE_BOSS, null, null, null, null, null, null);

        if (cursor.moveToFirst()) {
            do {
                RowData data = new RowData();
                data.id = cursor.getLong(cursor.getColumnIndex(COLUMN_ID));
                data.text1 = cursor.getString(cursor.getColumnIndex(COLUMN_NAME));
                data.spawnTime = cursor.getLong(cursor.getColumnIndex(COLUMN_SPAWN));
                data.startTime = cursor.getLong(cursor.getColumnIndex(COLUMN_START_TIME));
                long elapsedSeconds = data.spawnTime - ((System.currentTimeMillis() - data.startTime) / 1000);
                if (elapsedSeconds < 0)
                    elapsedSeconds = 0;
                if (elapsedSeconds / 3600 > 0)
                    data.text3 = String.format(Locale.getDefault(), "%02d:%02d:%02d",
                            elapsedSeconds / 3600,
                            (elapsedSeconds % 3600) / 60,
                            elapsedSeconds % 60);
                else
                    data.text3 = String.format(Locale.getDefault(), "%02d:%02d",
                            (elapsedSeconds % 3600) / 60,
                            elapsedSeconds % 60);
                data.extraInfo = cursor.getString(cursor.getColumnIndex(COLUMN_EXTRA));
                data.isNotified = cursor.getInt(cursor.getColumnIndex(COLUMN_IS_NOTIFIED)) == 1;
                data.needNotify = cursor.getInt(cursor.getColumnIndex(COLUMN_NEED_NOTIFY)) == 1;
                // ★ 关键修改：传入 context，使日期前缀和“已刷新”支持多语言
                data.setSpawnTime(context);
                data.notifyTime = cursor.getLong(cursor.getColumnIndex(COLUMN_NOTIFY_TIME));
                data.autoReset = cursor.getInt(cursor.getColumnIndex(COLUMN_AUTO_RESET)) == 1;
                data.showInFloat = cursor.getInt(cursor.getColumnIndex(COLUMN_SHOW_IN_FLOAT)) == 1;
                bossList.add(0, data);
            } while (cursor.moveToNext());
        }

        cursor.close();
        return bossList;
    }

    public void deleteBoss(long id) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_BOSS, COLUMN_ID + " = ?", new String[]{String.valueOf(id)});
    }

    public void resetBossStartTime(long id, long startTime) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_START_TIME, startTime);
        values.put(COLUMN_IS_NOTIFIED, 0);  // 0 表示 false
        db.update(TABLE_BOSS, values, COLUMN_ID + " = ?", new String[]{String.valueOf(id)});
    }

    public void editBoss(RowData data) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        long id = data.id;
        values.put(COLUMN_NAME, data.text1);
        values.put(COLUMN_START_TIME, data.startTime);
        values.put(COLUMN_SPAWN, data.spawnTime);
        values.put(COLUMN_EXTRA, data.extraInfo);
        values.put(COLUMN_IS_NOTIFIED, data.isNotified ? 1 : 0);
        values.put(COLUMN_NOTIFY_TIME, data.notifyTime);
        values.put(COLUMN_AUTO_RESET, data.autoReset ? 1 : 0);
        values.put(COLUMN_SHOW_IN_FLOAT, data.showInFloat ? 1 : 0);
        db.update(TABLE_BOSS, values, COLUMN_ID + " = ?", new String[]{String.valueOf(id)});
    }

    public void setNeedNotify(long id, boolean needNotify) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_NEED_NOTIFY, needNotify ? 1 : 0);
        values.put(COLUMN_IS_NOTIFIED, 0);  // 重置通知状态
        db.update(TABLE_BOSS, values, COLUMN_ID + " = ?", new String[]{String.valueOf(id)});
    }

    public void setIsNotified(long id, boolean isNotified) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_IS_NOTIFIED, isNotified ? 1 : 0);
        db.update(TABLE_BOSS, values, COLUMN_ID + " = ?", new String[]{String.valueOf(id)});
    }

    public void clearAllBosses() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_BOSS, null, null);
        db.close();
    }
}