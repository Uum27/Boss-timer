package com.example.boss;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DataManager {

    private static final String TAG = "DataManager";
    private static final long SYNC_INTERVAL_MS = 60000;
    private static final long SYNC_INTERVAL_NEAR_MS = 30000;
    private static final long SYNC_INTERVAL_URGENT_MS = 10000;
    private static final long SYNC_INTERVAL_IDLE_MS = 10 * 60 * 1000;
    private static final long SYNC_INTERVAL_DEEP_IDLE_MS = 30 * 60 * 1000;
    private static final long SYNC_INTERVAL_SUPER_DEEP_IDLE_MS = 60 * 60 * 1000;
    private static final String CLOUD_BASE_URL = "https://boss-timer-d2g5h1jr528322af9-1304194024.ap-shanghai.app.tcloudbase.com";
    private static final String PREFS = "boss_prefs";
    private static final String KEY_USER_ID = "userId";
    private static final String KEY_USER_NAME = "userName";
    private static final String KEY_EXPANSION_CODE = "expansionCode";
    private static final String SPECIAL_CODE = "810026494";

    private static DataManager instance;

    private Context context;
    private DBHelper dbHelper;
    private CloudHelper cloudHelper;
    private List<RowData> cachedData = new ArrayList<>();
    private java.util.Map<Long, Long> lastAutoLogTime = new java.util.HashMap<>();
    private Handler mainHandler;
    private SharedPreferences prefs;

    private boolean isSharedMode = false;
    private boolean showSharedData = false;
    private String currentRoomId;
    private String currentRoomName;
    private int currentRoomVersion;
    private String myRole = "member";
    private String myPermissions = "{}";
    private String myUserId;
    private String myUserName;
    private String expansionCode;

    private ExecutorService executor;
    private Runnable syncRunnable;
    private Handler syncHandler;
    private boolean isSyncing = false;

    private DataManager(Context context) {
        this.context = context.getApplicationContext();
        this.dbHelper = new DBHelper(this.context);
        this.cloudHelper = new CloudHelper(CLOUD_BASE_URL);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newSingleThreadExecutor();
        this.syncHandler = new Handler(Looper.getMainLooper());
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.myUserId = prefs.getString(KEY_USER_ID, null);
        this.myUserName = prefs.getString(KEY_USER_NAME, null);
        this.expansionCode = prefs.getString(KEY_EXPANSION_CODE, null);
    }

    public static synchronized DataManager getInstance(Context context) {
        if (instance == null) {
            instance = new DataManager(context);
        }
        return instance;
    }

    public static DataManager getInstance() {
        return instance;
    }

    public void updateContext(Context context) {
        this.dbHelper = new DBHelper(context);
        refreshCache();
    }

    // ---- user identity ----

    public String getUserId() { return myUserId; }
    public String getUserName() { return myUserName; }
    public boolean hasUserId() { return myUserId != null && !myUserId.isEmpty(); }

    public void setExpansionCode(String code) {
        this.expansionCode = code;
        prefs.edit().putString(KEY_EXPANSION_CODE, code).apply();
    }

    public String getExpansionCode() { return expansionCode; }

    public boolean isSpecialCode(String input) {
        return SPECIAL_CODE.equals(input);
    }

    public String resolveErrorMessage(Context c, String error) {
        if (error == null) return c.getString(R.string.error_network);
        String e = error.toLowerCase();
        if (e.contains("wrong password")) return c.getString(R.string.room_password_wrong);
        if (e.contains("max_rooms_reached")) return c.getString(R.string.max_rooms_reached);
        if (e.contains("higher_priority_locked")) return c.getString(R.string.higher_priority_locked);
        if (e.contains("max_bosses_reached") || e.contains("need_expansion_code")) return c.getString(R.string.need_expansion_code);
        if (e.contains("banned")) return c.getString(R.string.banned);
        if (e.contains("room not found") || e.contains("not found")) return c.getString(R.string.room_not_found);
        if (e.contains("password required") || e.contains("password")) return c.getString(R.string.room_password_wrong);
        String stripped = error;
        if (stripped.startsWith("HTTP ")) {
            int idx = stripped.indexOf(": ");
            if (idx > 0) stripped = stripped.substring(idx + 2);
        }
        return c.getString(R.string.room_error, stripped);
    }

    public void saveUserIdentity(String userId, String userName) {
        this.myUserId = userId;
        this.myUserName = userName;
        prefs.edit().putString(KEY_USER_ID, userId).putString(KEY_USER_NAME, userName).apply();
    }

    public void setUserId(String userId) {
        this.myUserId = userId;
        prefs.edit().putString(KEY_USER_ID, userId).apply();
    }

    public void setUserName(String userName) {
        if (userName == null || userName.equals(myUserName)) return;
        String oldName = myUserName;
        this.myUserName = userName;
        prefs.edit().putString(KEY_USER_NAME, userName).apply();
        if (isSharedMode && currentRoomId != null && myUserId != null) {
            executor.execute(() -> {
                try { cloudHelper.updateMyName(currentRoomId, myUserId, userName); }
                catch (Exception ignored) {}
            });
            addUserRenameLog(oldName, userName);
        }
    }

    private void addUserRenameLog(String oldName, String newName) {
        if (currentRoomId == null || myUserId == null) return;
        executor.execute(() -> {
            try {
                cloudHelper.addLog(currentRoomId, myUserId, "", "rename", oldName,
                        "\"newName\":\"" + escapeJson(newName) + "\",\"userId\":\"" + myUserId + "\"");
            } catch (Exception e) { Log.e(TAG, "addUserRenameLog failed", e); }
        });
    }

    public void registerUser(String name, Callback<String> callback) {
        String userId = genLocalUserId();
        saveUserIdentity(userId, name);
        mainHandler.post(() -> callback.onResult(userId));
    }

    private String genLocalUserId() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt((int) (Math.random() * chars.length())));
        }
        return sb.toString();
    }

    public void ensureUserRegistered(String name, Callback<String> callback) {
        if (myUserId != null && !myUserId.isEmpty()) {
            callback.onResult(myUserId);
            return;
        }
        registerUser(name, callback);
    }

    // ---- mode / role ----

    public boolean isSharedMode() { return isSharedMode; }
    public String getCurrentRoomId() { return currentRoomId; }
    public String getCurrentRoomName() { return currentRoomName; }
    public int getCurrentRoomVersion() { return currentRoomVersion; }
    public String getMyRole() { return myRole; }
    public boolean isOwner() { return "owner".equals(myRole) || "super_admin".equals(myRole); }
    public boolean canEdit() { return isOwner() || "admin".equals(myRole) || hasPermission("canEdit"); }
    public boolean canAdd() { return isOwner() || hasPermission("canAdd"); }
    public boolean canDelete() { return isOwner() || hasPermission("canDelete"); }
    public boolean canReset() { return isOwner() || "admin".equals(myRole) || hasPermission("canReset"); }

    // ---- cross-room notification ----
    private java.util.Set<String> pendingNotifyRoomIds = new java.util.HashSet<>();

    public void addPendingNotifyRoom(String roomId) {
        pendingNotifyRoomIds.add(roomId);
    }

    public boolean hasPendingNotifyRooms() {
        return !pendingNotifyRoomIds.isEmpty();
    }

    public List<String> getAndClearPendingRooms() {
        List<String> result = new ArrayList<>(pendingNotifyRoomIds);
        pendingNotifyRoomIds.clear();
        return result;
    }

    public boolean isRoomFavorited(String roomId) {
        try {
            JSONArray favs = new JSONArray(context.getSharedPreferences("boss_fav_rooms", Context.MODE_PRIVATE).getString("fav_ids", "[]"));
            for (int i = 0; i < favs.length(); i++) {
                if (roomId.equals(favs.getJSONObject(i).optString("roomId"))) return true;
            }
        } catch (Exception e) {}
        return false;
    }

    private boolean hasPermission(String key) {
        try {
            JSONObject perms = new JSONObject(myPermissions);
            return perms.optBoolean(key, false);
        } catch (Exception e) { return false; }
    }

    public boolean isShowingSharedData() { return showSharedData; }
    public void setShowSharedData(boolean show) {
        this.showSharedData = show;
        refreshCache();
        EventBus.getDefault().post(new DataChangedEvent("mode_switch"));
    }

    public List<RowData> getCachedData() {
        synchronized (cachedData) { return new ArrayList<>(cachedData); }
    }

    // ---- local mode ----

    public List<RowData> getAllBosses() {
        if (isSharedMode && showSharedData && currentRoomId != null) {
            return dbHelper.getAllBossesByRoom(currentRoomId);
        }
        return dbHelper.getAllBosses();
    }

    public long insertBoss(RowData data) {
        data.roomId = null;
        data.docId = null;
        long id = dbHelper.insertBoss(data);
        data.id = id;
        refreshCache();
        return id;
    }

    public void editBoss(RowData data) {
        data.roomId = null;
        data.docId = null;
        dbHelper.editBoss(data);
        refreshCache();
    }

    public void deleteBoss(long id) {
        dbHelper.deleteBoss(id);
        refreshCache();
    }

    public void resetBossStartTime(long id, long startTime) {
        dbHelper.resetBossStartTime(id, startTime);
        refreshCache();
    }

    public void setIsNotified(long id, boolean isNotified) {
        dbHelper.setIsNotified(id, isNotified);
    }

    public void setNeedNotify(long id, boolean needNotify) {
        dbHelper.setNeedNotify(id, needNotify);
    }

    // ---- cloud mode: create/join ----

    public void createRoom(String roomName, String password, Callback<String> callback) {
        createRoom(roomName, password, expansionCode, callback);
    }

    public void createRoom(String roomName, String password, String expansionCode, Callback<String> callback) {
        ensureUserRegistered(roomName, new Callback<String>() {
            @Override public void onResult(String userId) {
                executor.execute(() -> {
                    try {
                        String result = cloudHelper.createRoom(myUserId, myUserName, roomName, password, expansionCode);
                        JSONObject json = new JSONObject(result);
                        String roomId = json.getString("roomId");
                        setRoomState(roomId, roomName, json.optInt("version", 0), "owner", "{}");
                        showSharedData = true;
                        dbHelper.saveRoomInfo(roomId, 0);
                        refreshCache();
                        mainHandler.post(() -> {
                            notifyRoomChanged("room_created");
                            startPeriodicSync();
                            callback.onResult(roomId);
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "createRoom failed", e);
                        mainHandler.post(() -> callback.onError(e.getMessage()));
                    }
                });
            }
            @Override public void onError(String error) { callback.onError(error); }
        });
    }

    public void joinRoom(String roomId, String password, Callback<String> callback) {
        String userName = myUserName != null && !myUserName.isEmpty() ? myUserName : "Player";
        ensureUserRegistered(userName, new Callback<String>() {
            @Override public void onResult(String userId) {
                executor.execute(() -> {
                    try {
                        String result = cloudHelper.joinRoom(roomId, myUserId, myUserName, password);
                        JSONObject json = new JSONObject(result);
                        setRoomState(roomId, json.optString("roomName", roomId), json.optInt("version", 0),
                                json.optString("role", "member"), json.optJSONObject("permissions") != null ? json.optJSONObject("permissions").toString() : "{}");
                        showSharedData = true;
                        dbHelper.saveRoomInfo(roomId, json.optInt("version", 0));
                        mainHandler.post(() -> {
                            notifyRoomChanged("room_joined");
                            startPeriodicSync();
                            callback.onResult(roomId);
                        });
                        syncBossesFromCloud();
                    } catch (Exception e) {
                        Log.e(TAG, "joinRoom failed", e);
                        mainHandler.post(() -> callback.onError(e.getMessage()));
                    }
                });
            }
            @Override public void onError(String error) { callback.onError(error); }
        });
    }

    public void leaveRoom() {
        stopPeriodicSync();
        isSharedMode = false;
        showSharedData = false;
        currentRoomId = null;
        currentRoomName = null;
        currentRoomVersion = 0;
        myRole = "member";
        myPermissions = "{}";
        refreshCache();
        notifyRoomChanged("room_left");
    }

    private void setRoomState(String roomId, String roomName, int version, String role, String permissions) {
        isSharedMode = true;
        currentRoomId = roomId;
        currentRoomName = roomName;
        currentRoomVersion = version;
        myRole = role;
        myPermissions = permissions;
    }

    private void notifyRoomChanged(String reason) {
        EventBus.getDefault().post(new UpdateFloatWindowEvent(EventTypes.ROOM_JOINED, (RowData) null));
        EventBus.getDefault().post(new DataChangedEvent(reason));
    }

    // ---- cloud mode: boss CRUD ----

    public long insertBossShared(RowData data) {
        if (data.roomId == null) data.roomId = currentRoomId;
        long id = dbHelper.insertBoss(data);
        data.id = id;
        refreshCache();
        pushAddBoss(data);
        mainHandler.post(() -> EventBus.getDefault().post(new UpdateFloatWindowEvent(EventTypes.ADD_ITEM, data)));
        return id;
    }

    public void editBossShared(RowData data) {
        if (data.roomId == null) data.roomId = currentRoomId;
        dbHelper.editBoss(data);
        refreshCache();
        pushUpdateBoss(data, null);
    }

    public void renameBossAndSync(RowData data, String oldName, String newName) {
        if (data.roomId == null) data.roomId = currentRoomId;
        dbHelper.editBoss(data);
        refreshCache();
        pushUpdateBoss(data, "rename");
        addRenameLog(data.id, oldName, newName);
    }

    public void resetBossAndLog(RowData data, long oldEndTime) {
        if (data.roomId == null) data.roomId = currentRoomId;
        dbHelper.editBoss(data);
        refreshCache();
        pushUpdateBoss(data, "reset");
        addResetLog(data.id, data.startTime, data.spawnTime, oldEndTime);
    }

    public void deleteBossShared(long id) {
        RowData data = findCachedById(id);
        String docId = data != null ? data.docId : null;
        dbHelper.deleteBoss(id);
        refreshCache();
        if (docId != null && myUserId != null) {
            executor.execute(() -> {
                try { cloudHelper.deleteBoss(currentRoomId, myUserId, docId); }
                catch (Exception e) { Log.e(TAG, "deleteBoss cloud failed", e); }
            });
        }
    }

    public void resetBossShared(long id, long startTime) {
        RowData data = findCachedById(id);
        if (data != null && data.startTime == startTime) return;
        dbHelper.resetBossStartTime(id, startTime);
        refreshCache();
        data = findCachedById(id);
        if (data != null) {
            data.startTime = startTime;
            data.isNotified = false;
        }
    }

    public void addRestartLog(String bossListJson) {
        executor.execute(() -> {
            try {
                cloudHelper.addLog(currentRoomId, myUserId, getUserName(), "restart", "",
                        "\"bosses\":[" + bossListJson + "]");
            } catch (Exception e) { Log.e(TAG, "addRestartLog failed", e); }
        });
    }

    public void addAutoLog(long id, long startTime, long spawnTime) {
        RowData data = findCachedById(id);
        if (data == null || data.roomId == null) return;
        synchronized (lastAutoLogTime) {
            Long lastTime = lastAutoLogTime.get(id);
            if (lastTime != null && (System.currentTimeMillis() - lastTime) < 5000) return;
            lastAutoLogTime.put(id, System.currentTimeMillis());
        }
        long endTime = startTime + spawnTime * 1000;
        final String bossName = data.text1;
        final String roomId = data.roomId;
        executor.execute(() -> {
            try {
                cloudHelper.addLog(roomId, myUserId, "", "auto", bossName,
                        "\"spawn\":" + spawnTime + ",\"refreshTime\":" + endTime);
            } catch (Exception e) { Log.e(TAG, "addAutoLog failed", e); }
        });
    }

    public void addResetLog(long id, long startTime, long spawnTime, long oldEndTime) {
        RowData data = findCachedById(id);
        if (data == null || data.roomId == null) return;
        long endTime = startTime + spawnTime * 1000;
        final String bossName = data.text1;
        final String roomId = data.roomId;
        executor.execute(() -> {
            try {
                cloudHelper.addLog(roomId, myUserId, getUserName(), "reset", bossName,
                        "\"endTime\":" + endTime + ",\"oldEndTime\":" + oldEndTime + ",\"spawn\":" + spawnTime);
            } catch (Exception e) { Log.e(TAG, "addResetLog failed", e); }
        });
    }

    public void addRenameLog(long id, String oldName, String newName) {
        RowData data = findCachedById(id);
        if (data == null || data.roomId == null) return;
        final String roomId = data.roomId;
        executor.execute(() -> {
            try {
                cloudHelper.addLog(roomId, myUserId, getUserName(), "rename", oldName,
                        "\"newName\":\"" + escapeJson(newName) + "\"");
            } catch (Exception e) { Log.e(TAG, "addRenameLog failed", e); }
        });
    }

    private void pushAddBoss(RowData data) {
        long savedStartTime = data.startTime;
        executor.execute(() -> {
            try {
                String json = rowDataToJson(data);
                String result = cloudHelper.addBoss(currentRoomId, myUserId, json);
                JSONObject r = new JSONObject(result);
                String docId = r.optString("docId");
                int v = r.optInt("version", currentRoomVersion);
                dbHelper.setDocId(data.id, docId, currentRoomId);
                currentRoomVersion = v;
                dbHelper.saveRoomInfo(currentRoomId, v);
                dbHelper.updateSyncStatus(data.id, "synced");
                if (data.startTime != savedStartTime) {
                    String updateJson = rowDataToJson(data);
                    cloudHelper.updateBoss(currentRoomId, myUserId, docId, updateJson);
                }
            } catch (Exception e) { Log.e(TAG, "pushAddBoss failed", e); }
        });
    }

    private void pushUpdateBoss(RowData data, String action) {
        if (data.docId == null) return;
        executor.execute(() -> {
            try {
                JSONObject j = new JSONObject(rowDataToJson(data));
                if (action != null) j.put("action", action);
                String result = cloudHelper.updateBoss(currentRoomId, myUserId, data.docId, j.toString());
                JSONObject r = new JSONObject(result);
                currentRoomVersion = r.optInt("version", currentRoomVersion);
                dbHelper.saveRoomInfo(currentRoomId, currentRoomVersion);
                dbHelper.updateSyncStatus(data.id, "synced");
            } catch (Exception e) { Log.e(TAG, "pushUpdateBoss failed", e); }
        });
    }

    // ---- sync ----

    private void startPeriodicSync() {
        if (syncRunnable != null) return;
        syncRunnable = new Runnable() {
            @Override public void run() {
                if (!isSharedMode || currentRoomId == null) return;
                checkAndSyncVersion();
                syncHandler.postDelayed(this, getSyncIntervalMs());
            }
        };
        syncHandler.post(syncRunnable);
    }

    private long getSyncIntervalMs() {
        long minRemaining = Long.MAX_VALUE;
        long now = System.currentTimeMillis();
        List<RowData> allBosses = dbHelper.getAllBossesByRoom(currentRoomId);
        for (RowData d : allBosses) {
            if (d.spawnTime <= 0 || !d.needNotify) continue;
            long remaining = d.spawnTime - ((now - d.startTime) / 1000);
            if (remaining <= 0) continue;
            if (remaining < minRemaining) minRemaining = remaining;
        }
        if (minRemaining == Long.MAX_VALUE) {
            long latestExpireTime = 0;
            for (RowData d : allBosses) {
                long expireTime = d.startTime + d.spawnTime * 1000;
                if (expireTime > latestExpireTime) latestExpireTime = expireTime;
            }
            long elapsed = now - latestExpireTime;
            if (elapsed > 5 * 3600 * 1000L) {
                return SYNC_INTERVAL_SUPER_DEEP_IDLE_MS;
            } else if (elapsed > 2 * 3600 * 1000L) {
                return SYNC_INTERVAL_DEEP_IDLE_MS;
            }
            return SYNC_INTERVAL_IDLE_MS;
        }
        if (minRemaining <= 60) {
            return SYNC_INTERVAL_URGENT_MS;
        } else if (minRemaining <= 300) {
            return SYNC_INTERVAL_NEAR_MS;
        } else if (minRemaining <= 900) {
            return SYNC_INTERVAL_MS;
        } else {
            long safeInterval = Math.min(minRemaining / 3, 15 * 60);
            return safeInterval * 1000;
        }
    }

    private void stopPeriodicSync() {
        if (syncRunnable != null) { syncHandler.removeCallbacks(syncRunnable); syncRunnable = null; }
    }

    private void checkAndSyncVersion() {
        if (isSyncing || currentRoomId == null || myUserId == null) return;
        isSyncing = true;
        executor.execute(() -> {
            try {
                String result = cloudHelper.getRoomVersion(currentRoomId);
                JSONObject json = new JSONObject(result);
                int cloudVersion = json.optInt("version", 0);
                if (cloudVersion != currentRoomVersion) {
                    syncBossesFromCloud();
                }
            } catch (Exception e) { Log.e(TAG, "checkAndSyncVersion failed", e); }
            finally { isSyncing = false; }
        });
    }

    private void syncBossesFromCloud() {
        try {
            String result = cloudHelper.getBosses(currentRoomId, myUserId);
            JSONObject json = new JSONObject(result);
            int version = json.optInt("version", 0);
            myRole = json.optString("role", myRole);
            if (json.has("permissions")) myPermissions = json.optJSONObject("permissions").toString();
            JSONArray bosses = json.optJSONArray("bosses");
            if (bosses != null) {
                dbHelper.clearCloudBosses(currentRoomId);
                for (int i = 0; i < bosses.length(); i++) {
                    dbHelper.insertOrUpdateBoss(jsonToRowData(bosses.getJSONObject(i)));
                }
            }
            currentRoomVersion = version;
            dbHelper.saveRoomInfo(currentRoomId, version);
            refreshCache();
            mainHandler.post(() -> {
                EventBus.getDefault().post(new DataChangedEvent("sync_completed"));
                EventBus.getDefault().post(new UpdateFloatWindowEvent(EventTypes.SYNC_COMPLETED, (RowData) null));
            });
        } catch (Exception e) { Log.e(TAG, "syncBossesFromCloud failed", e); }
    }

    public void forceSync() {
        if (!isSharedMode || currentRoomId == null || myUserId == null) return;
        executor.execute(this::checkAndSyncVersion);
    }

    public void fetchRoomPassword(Callback<String> callback) {
        if (!isSharedMode || currentRoomId == null || myUserId == null) return;
        executor.execute(() -> {
            try {
                String result = cloudHelper.getRoomPassword(currentRoomId, myUserId);
                mainHandler.post(() -> callback.onResult(result));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void fetchMyRooms(Callback<String> callback) {
        if (myUserId == null) {
            mainHandler.post(() -> callback.onResult("{\"rooms\":[]}"));
            return;
        }
        executor.execute(() -> {
            try {
                String result = cloudHelper.getMyRooms(myUserId);
                mainHandler.post(() -> callback.onResult(result));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void deleteMyRoom(String roomId, Callback<Boolean> callback) {
        if (myUserId == null) return;
        executor.execute(() -> {
            try {
                cloudHelper.deleteRoom(roomId, myUserId);
                if (roomId.equals(currentRoomId)) leaveRoom();
                mainHandler.post(() -> callback.onResult(true));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void kickMember(String roomId, String targetUserId, Callback<Boolean> callback) {
        executor.execute(() -> {
            try {
                cloudHelper.kickMember(roomId, myUserId, targetUserId);
                mainHandler.post(() -> callback.onResult(true));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void fetchBannedList(String roomId, Callback<String> callback) {
        executor.execute(() -> {
            try {
                String result = cloudHelper.getBannedList(roomId, myUserId);
                mainHandler.post(() -> callback.onResult(result));
            } catch (Exception e) { mainHandler.post(() -> callback.onError(e.getMessage())); }
        });
    }

    public void unbanMember(String roomId, String targetUserId, Callback<Boolean> callback) {
        executor.execute(() -> {
            try {
                cloudHelper.unbanMember(roomId, myUserId, targetUserId);
                mainHandler.post(() -> callback.onResult(true));
            } catch (Exception e) { mainHandler.post(() -> callback.onError(e.getMessage())); }
        });
    }

    public void fetchLogs(String roomId, Callback<String> callback) {
        executor.execute(() -> {
            try {
                String result = cloudHelper.getLogs(roomId, myUserId);
                mainHandler.post(() -> callback.onResult(result));
            } catch (Exception e) { mainHandler.post(() -> callback.onError(e.getMessage())); }
        });
    }

    public void updateRoomInfo(String roomId, String roomName, String password, Callback<Boolean> callback) {
        if (myUserId == null) return;
        executor.execute(() -> {
            try {
                cloudHelper.updateRoomInfo(roomId, myUserId, roomName, 
                    (password != null && !password.isEmpty()) ? password : null);
                if (roomId.equals(currentRoomId) && roomName != null) {
                    currentRoomName = roomName;
                }
                mainHandler.post(() -> callback.onResult(true));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void fetchRoomMembers(String roomId, Callback<String> callback) {
        if (myUserId == null) return;
        executor.execute(() -> {
            try {
                String result = cloudHelper.getRoomInfo(roomId, myUserId);
                mainHandler.post(() -> callback.onResult(result));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void updateMemberRole(String roomId, String targetUserId, String role, String permissionsJson, Callback<Boolean> callback) {
        executor.execute(() -> {
            try {
                cloudHelper.updateMemberRole(roomId, myUserId, targetUserId, role, permissionsJson);
                mainHandler.post(() -> callback.onResult(true));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    // ---- cache ----

    public void refreshCache() {
        List<RowData> all = new ArrayList<>();
        all.addAll(dbHelper.getAllBosses());
        if (isSharedMode && currentRoomId != null) {
            all.addAll(dbHelper.getAllBossesByRoom(currentRoomId));
        }
        synchronized (cachedData) { cachedData = all; }
    }

    private RowData findCachedById(long id) {
        synchronized (cachedData) {
            for (RowData d : cachedData) if (d.id == id) return d;
        }
        return null;
    }

    private void notifyDataChanged(String reason) {
        EventBus.getDefault().post(new DataChangedEvent(reason));
    }

    // ---- JSON ----

    private String rowDataToJson(RowData data) {
        try {
            JSONObject j = new JSONObject();
            j.put("name", data.text1);
            j.put("spawn", data.spawnTime);
            j.put("extra", data.extraInfo != null ? data.extraInfo : "");
            j.put("startTime", data.startTime);
            j.put("notifyTime", data.notifyTime);
            j.put("needNotify", data.needNotify);
            j.put("autoReset", data.autoReset);
            j.put("showInFloat", data.showInFloat);
            if (expansionCode != null) j.put("expansionCode", expansionCode);
            j.put("decreasingMode", data.decreasingMode);
            j.put("decreasingSeconds", data.decreasingSeconds);
            j.put("decreasingCount", data.decreasingCount);
            j.put("deathCount", data.deathCount);
            j.put("initialSpawnTime", data.initialSpawnTime);
            if (data.editTimeType != null) j.put("editTimeType", data.editTimeType);
            if (data.enteredValue != 0) j.put("enteredValue", data.enteredValue);
            return j.toString();
        } catch (Exception e) { return "{}"; }
    }

    private RowData jsonToRowData(JSONObject j) {
        RowData d = new RowData();
        try {
            d.docId = j.optString("docId", null);
            d.text1 = j.optString("name", "");
            d.spawnTime = j.optLong("spawn", 0);
            d.extraInfo = j.optString("extra", "");
            d.startTime = j.optLong("startTime", 0);
            d.notifyTime = j.optLong("notifyTime", 300);
            d.needNotify = j.optBoolean("needNotify", true);
            d.autoReset = j.optBoolean("autoReset", true);
            d.showInFloat = j.optBoolean("showInFloat", true);
            d.decreasingMode = j.optBoolean("decreasingMode", false);
            d.decreasingSeconds = j.optInt("decreasingSeconds", 0);
            d.decreasingCount = j.optInt("decreasingCount", 0);
            d.deathCount = j.optInt("deathCount", 0);
            d.initialSpawnTime = j.optLong("initialSpawnTime", 0);
            d.roomId = currentRoomId;
            d.syncStatus = "synced";
        } catch (Exception e) { Log.e(TAG, "jsonToRowData", e); }
        return d;
    }

    public void verifyAuth(String code, Callback<Boolean> callback) {
        executor.execute(() -> {
            try {
                String result = cloudHelper.verifyAuth(code);
                JSONObject json = new JSONObject(result);
                boolean valid = json.optBoolean("valid", false);
                mainHandler.post(() -> callback.onResult(valid));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void onDestroy() {
        stopPeriodicSync();
        executor.shutdown();
    }

    // ---- favorites (stored in SharedPreferences) ----

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public String getFavoritesJson() {
        return context.getSharedPreferences("boss_fav_rooms", Context.MODE_PRIVATE).getString("fav_ids", "[]");
    }

    public interface Callback<T> {
        void onResult(T result);
        void onError(String error);
    }
}
