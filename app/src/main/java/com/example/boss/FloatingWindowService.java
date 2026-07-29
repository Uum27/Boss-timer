package com.example.boss;

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class FloatingWindowService extends Service {
    private WindowManager windowManager;
    private View floatingView;
    private View floatingView_minimized;
    private RecyclerView recyclerView;
    private FloatingWindowAdapter adapter;
    private boolean isShowing = false;
    private static final String CHANNEL_ID = "FloatingWindowServiceChannel";
    private static final int NOTIFICATION_ID = 1;
    private DBHelper dbHelper;
    private DataManager dataManager;
    private boolean isMinimized = false;
    private boolean isTransitioning = false;
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;
    private WindowManager.LayoutParams params;
    private WindowManager.LayoutParams params_minimized;
    private TextView minimizedTimeText;
    private float touchSlop;
    private boolean isMoving;

    private int appUsableWidth;
    private int appUsableHeight;
    private int floatingViewWidth;
    private int floatingViewHeight;
    private int floatingViewWidth_minimized;
    private int floatingViewHeight_minimized;

    private static final int MAX_ITEMS = 6;
    private int ITEM_HEIGHT_DP = 32;
    private TextView batteryText;
    private BroadcastReceiver batteryReceiver;
    private boolean isBatteryReceiverRegistered = false;
    private static FloatingWindowService instance;

    public static FloatingWindowService getInstance() {
        return instance;
    }

    private Handler globalTickHandler = new Handler(Looper.getMainLooper());
    private Runnable globalTickRunnable;
    private Runnable scheduledNotifyRunnable;
    private AlarmManager alarmManager;
    private PendingIntent alarmPendingIntent;
    private boolean isCheckingNotifications = false;
    private Vibrator vibrator;
    private NotificationManager notificationManager;
    private static final int BOSS_NOTIFICATION_ID = 100;
    private boolean hasLocalNotify = false;
    private boolean hasSharedNotify = false;
    private boolean suppressNextNotify = false;
    private java.util.Set<Long> notifiedBossIds = new java.util.HashSet<>();

    private PowerManager.WakeLock wakeLock;
    private static final long MIN_WAKELOCK_TIMEOUT_SEC = 5 * 60;
    private long lastVibrateTime = 0;
    private long lastSyncTime = 0;
    private long lastAlarmTarget = 0;

    void onAlarmFired() {
        lastSyncTime = 0;
    }

    // 标题栏控件缓存
    private TextView tvTitleName, tvTitleRefresh, tvTitleRemaining, tvTitleReset;

    // ★ 最小化锁定相关
    private boolean isMinimizedLocked = false;
    private long lastClickTime = 0;
    private static final long DOUBLE_CLICK_TIME_DELTA = 300;

    // ★ 展开同步节流
    private long lastExpandSyncTime = 0;
    private static final long EXPAND_SYNC_COOLDOWN_MS = 15 * 1000;

    private View authPanel;
    private EditText authInput;
    private TextView authTitle;
    private Button authConfirmBtn;
    private Button authCancelBtn;
    private View joinRoomPanel;
    private View roomListPanel;
    private EditText joinRoomIdInput;
    private EditText joinRoomPasswordInput;
    private EditText joinRoomNameInput;
    private Button shareBtn;
    private Button roomBtn;
    private View roomInfoBar;
    private TextView roomInfoText;
    private View roomBar;
    private View roomBarLayout;
    private TextView roomBarText;
    private View favoriteArea;
    private TextView favoriteIcon;
    private TextView favoriteText;
    private TextView tvUserName;
    private RecyclerView floatingRecyclerView;

    @Override
    public void onCreate() {
        super.onCreate();
        if (instance != null) {
            return;
        }

        instance = this;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel bossChannel = new NotificationChannel(
                    "boss_timer",
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            bossChannel.setDescription(getString(R.string.notification_channel_description));
            bossChannel.enableLights(true);
            bossChannel.enableVibration(false);
            bossChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            notificationManager.createNotificationChannel(bossChannel);
        }

        alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

        EventBus.getDefault().register(this);
        dbHelper = new DBHelper(this);
        dataManager = DataManager.getInstance(this);
        dataManager.updateContext(this);
        dataManager.setShowSharedData(false);

        initFloatingWindow();
        refreshData();
        startGlobalTick();
    }

    private Context getLocalizedContext() {
        return LocaleHelper.setLocale(this, LocaleHelper.getLanguage(this));
    }

    private void recreateAdapter() {
        Context localizedContext = getLocalizedContext();
        if (adapter != null) {
            adapter.updateContext(localizedContext);
        } else {
            adapter = new FloatingWindowAdapter(new ArrayList<>(), localizedContext);
            recyclerView.setAdapter(adapter);
        }
        refreshData();
    }

    private void initBatteryMonitor() {
        if (isBatteryReceiverRegistered) return;
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                    int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    int batteryPct = (int) ((level / (float) scale) * 100);
                    if (batteryText != null) {
                        batteryText.setText(batteryPct + "%");
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(batteryReceiver, filter);
        isBatteryReceiverRegistered = true;
        updateBatteryLevel();
    }

    private void updateBatteryLevel() {
        BatteryManager batteryManager = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        if (batteryManager != null) {
            int batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (batteryText != null) {
                batteryText.setText(batteryLevel + "%");
            }
        }
    }

    private void updateScreenBounds() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(displayMetrics);
        Rect windowRect = new Rect();
        floatingView.getWindowVisibleDisplayFrame(windowRect);
        appUsableWidth = windowRect.width() > 0 ? windowRect.width() : displayMetrics.widthPixels;
        appUsableHeight = windowRect.height() > 0 ? windowRect.height() : displayMetrics.heightPixels;

        if (params != null) {
            if (params.x > appUsableWidth - floatingViewWidth) {
                params.x = appUsableWidth - floatingViewWidth;
            }
            if (params.y > appUsableHeight - floatingViewHeight) {
                params.y = appUsableHeight - floatingViewHeight;
            }
            if (floatingView != null && floatingView.getParent() != null) {
                windowManager.updateViewLayout(floatingView, params);
            }
        }

        if (params_minimized != null) {
            if (params_minimized.x > appUsableWidth - floatingViewWidth_minimized) {
                params_minimized.x = appUsableWidth - floatingViewWidth_minimized;
            }
            if (params_minimized.y > appUsableHeight - floatingViewHeight_minimized) {
                params_minimized.y = appUsableHeight - floatingViewHeight_minimized;
            }
            if (floatingView_minimized != null && floatingView_minimized.getParent() != null) {
                windowManager.updateViewLayout(floatingView_minimized, params_minimized);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
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
                updateRecyclerViewHeight();
                scheduleNextNotification();
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onLanguageChanged(LanguageChangeEvent event) {
        dataManager.updateContext(getLocalizedContext());
        updateFloatingWindowTexts();
        recreateAdapter();
        updateModeIndicator();
        showingLogs = false;
        if (roomInfoBar != null && roomInfoBar.getVisibility() == View.VISIBLE) {
            roomInfoBar.setVisibility(View.GONE);
        }
        if (roomListPanel != null) {
            roomListPanel.setVisibility(View.GONE);
            ((LinearLayout) roomListPanel).removeAllViews();
        }
        floatingRecyclerView.setVisibility(View.VISIBLE);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDataChanged(DataChangedEvent event) {
        refreshData();
        updateRecyclerViewHeight();
        updateShareButtonText();
        updateModeIndicator();
        scheduleNextNotification();
    }

    private void updateFloatingWindowTexts() {
        if (floatingView == null) return;
        Context c = getLocalizedContext();
        if (tvTitleName == null) {
            tvTitleName = floatingView.findViewById(R.id.tv_title_name);
            tvTitleRefresh = floatingView.findViewById(R.id.tv_title_refresh);
            tvTitleRemaining = floatingView.findViewById(R.id.tv_title_remaining);
            tvTitleReset = floatingView.findViewById(R.id.tv_title_reset);
        }
        if (tvTitleName != null) tvTitleName.setText(c.getString(R.string.float_title_name));
        if (tvTitleRefresh != null) tvTitleRefresh.setText(c.getString(R.string.float_title_refresh));
        if (tvTitleRemaining != null) tvTitleRemaining.setText(c.getString(R.string.float_title_remaining));
        if (tvTitleReset != null) tvTitleReset.setText(c.getString(R.string.float_title_reset));

        Button minimizeBtn = floatingView.findViewById(R.id.btn_minimize);
        if (minimizeBtn != null) minimizeBtn.setText(c.getString(R.string.float_button_minimize));

        Button roomBtn = floatingView.findViewById(R.id.btn_room);
        if (roomBtn != null) roomBtn.setText(c.getString(R.string.float_button_room));

        TextView leaveBtn = floatingView.findViewById(R.id.btn_leave_room);
        if (leaveBtn != null) leaveBtn.setText(c.getString(R.string.room_leave));

        TextView logsBtn = floatingView.findViewById(R.id.btn_logs);
        if (logsBtn != null) logsBtn.setText(c.getString(R.string.logs_button));

        TextView switchBtn = floatingView.findViewById(R.id.btn_switch_room);
        if (switchBtn != null) switchBtn.setText(c.getString(R.string.switch_room));

        Button joinConfirm = floatingView.findViewById(R.id.btn_join_confirm);
        if (joinConfirm != null) joinConfirm.setText(c.getString(R.string.join_room_btn));

        Button joinCancel = floatingView.findViewById(R.id.btn_join_cancel);
        if (joinCancel != null) joinCancel.setText(c.getString(R.string.dialog_button_cancel));

        TextView joinTitle = floatingView.findViewById(R.id.join_room_title);
        if (joinTitle != null) joinTitle.setText(c.getString(R.string.join_room_title));

        EditText ridInput = floatingView.findViewById(R.id.join_room_id);
        if (ridInput != null) ridInput.setHint(c.getString(R.string.join_room_id_hint));

        EditText rpwdInput = floatingView.findViewById(R.id.join_room_password);
        if (rpwdInput != null) rpwdInput.setHint(c.getString(R.string.room_password_hint));

        EditText rnameInput = floatingView.findViewById(R.id.join_room_name);
        if (rnameInput != null) rnameInput.setHint(c.getString(R.string.join_room_name_hint));

        if (favoriteText != null) favoriteText.setText(c.getString(R.string.favorite));

        if (authTitle != null) authTitle.setText(c.getString(R.string.auth_title));
        if (authInput != null) authInput.setHint(c.getString(R.string.auth_hint));
        if (authConfirmBtn != null) authConfirmBtn.setText(c.getString(R.string.confirm_btn));
        if (authCancelBtn != null) authCancelBtn.setText(c.getString(R.string.dialog_button_cancel));

        updateShareButtonText();
    }

    private void updateShareButtonText() {
        Button shareBtnView = floatingView != null ? floatingView.findViewById(R.id.btn_share) : null;
        if (shareBtnView != null) {
            Context c = getLocalizedContext();
            String text;
            boolean hasNotify = false;
            if (!dataManager.isSharedMode()) {
                text = c.getString(R.string.float_button_share);
            } else if (dataManager.isShowingSharedData()) {
                text = c.getString(R.string.float_button_local);
                hasNotify = hasLocalNotify;
            } else {
                text = c.getString(R.string.float_button_share);
                hasNotify = hasSharedNotify;
            }
            if (hasNotify) {
                android.text.SpannableString sp = new android.text.SpannableString(text + " ●");
                sp.setSpan(new android.text.style.ForegroundColorSpan(0xFFFF0000),
                    text.length() + 1, sp.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                shareBtnView.setText(sp);
            } else {
                shareBtnView.setText(text);
            }
        }
    }

    private void updateRoomButtonText() {
        Button roomBtnView = floatingView != null ? floatingView.findViewById(R.id.btn_room) : null;
        if (roomBtnView != null) {
            Context c = getLocalizedContext();
            String text = c.getString(R.string.float_button_room);
            boolean roomInfoVisible = roomInfoBar != null && roomInfoBar.getVisibility() == View.VISIBLE;
            if (!roomInfoVisible && dataManager.hasPendingNotifyRooms()) {
                android.text.SpannableString sp = new android.text.SpannableString(text + " ●");
                sp.setSpan(new android.text.style.ForegroundColorSpan(0xFFFF0000),
                    text.length() + 1, sp.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                roomBtnView.setText(sp);
            } else {
                roomBtnView.setText(text);
            }
        }
    }

    private void updateSwitchRoomButtonText() {
        TextView switchBtn = floatingView != null ? floatingView.findViewById(R.id.btn_switch_room) : null;
        if (switchBtn != null) {
            Context c = getLocalizedContext();
            String text = c.getString(R.string.switch_room);
            if (dataManager.hasPendingNotifyRooms()) {
                android.text.SpannableString sp = new android.text.SpannableString(text + " ●");
                sp.setSpan(new android.text.style.ForegroundColorSpan(0xFFFF0000),
                    text.length() + 1, sp.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                switchBtn.setText(sp);
            } else {
                switchBtn.setText(text);
            }
        }
    }

    private void updateModeIndicator() {
        if (floatingView == null) return;
        Context c = getLocalizedContext();
        if (dataManager.isSharedMode() && dataManager.isShowingSharedData()) {
            if (roomBarText != null) {
                roomBarText.setText(c.getString(R.string.float_room_bar,
                        dataManager.getCurrentRoomName(), dataManager.getCurrentRoomId()));
            }
            if (favoriteIcon != null) {
                favoriteIcon.setText(isRoomFavorite(dataManager.getCurrentRoomId()) ? "★" : "☆");
            }
            if (favoriteText != null) {
                favoriteText.setText(c.getString(R.string.favorite));
            }
            if (roomBarLayout != null) roomBarLayout.setVisibility(View.VISIBLE);
            String name = dataManager.getUserName();
            if (tvUserName != null) {
                tvUserName.setText(name != null && !name.isEmpty() ? name : "");
                tvUserName.setVisibility(name != null && !name.isEmpty() ? View.VISIBLE : View.GONE);
            }
        } else {
            if (roomBarLayout != null) roomBarLayout.setVisibility(View.GONE);
            if (tvUserName != null) tvUserName.setVisibility(View.GONE);
        }
    }

    private void showAuthPanel() {
        params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        windowManager.updateViewLayout(floatingView, params);
        authPanel.setVisibility(View.VISIBLE);
        floatingRecyclerView.setVisibility(View.GONE);
        joinRoomPanel.setVisibility(View.GONE);
        if (roomInfoBar != null) roomInfoBar.setVisibility(View.GONE);
        authInput.requestFocus();
    }

    private void hideAuthPanel() {
        params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        windowManager.updateViewLayout(floatingView, params);
        authPanel.setVisibility(View.GONE);
        floatingRecyclerView.setVisibility(View.VISIBLE);
    }

    private void submitAuth() {
        String code = authInput.getText().toString().trim();
        dataManager.verifyAuth(code, new DataManager.Callback<Boolean>() {
            @Override public void onResult(Boolean valid) {
                if (valid) {
                    getSharedPreferences("boss_auth", MODE_PRIVATE).edit().putBoolean("authed", true).apply();
                    hideAuthPanel();
                    showJoinPanel();
                } else {
                    Toast.makeText(FloatingWindowService.this, getLocalizedContext().getString(R.string.auth_failed), Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onError(String error) {
                Toast.makeText(FloatingWindowService.this, getLocalizedContext().getString(R.string.auth_failed), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void toggleShareMode() {
        if (dataManager.isSharedMode()) {
            boolean current = dataManager.isShowingSharedData();
            dataManager.setShowSharedData(!current);
            if (current) {
                hasLocalNotify = false;
            } else {
                hasSharedNotify = false;
            }
            updateShareButtonText();
            updateModeIndicator();
            refreshData();
            return;
        }
        if (!getSharedPreferences("boss_auth", MODE_PRIVATE).getBoolean("authed", false)) {
            showAuthPanel();
            return;
        }
        if (joinRoomPanel != null && joinRoomPanel.getVisibility() == View.VISIBLE) {
            hideJoinPanel();
            return;
        }
        showJoinPanel();
    }

    private void showJoinPanel() {
        if (joinRoomPanel != null) {
            joinRoomPanel.setVisibility(View.VISIBLE);
            floatingRecyclerView.setVisibility(View.GONE);
            if (roomInfoBar != null) roomInfoBar.setVisibility(View.GONE);
            params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            windowManager.updateViewLayout(floatingView, params);
        }
    }

    private void hideJoinPanel() {
        if (joinRoomPanel != null) {
            joinRoomPanel.setVisibility(View.GONE);
            floatingRecyclerView.setVisibility(View.VISIBLE);
            params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            windowManager.updateViewLayout(floatingView, params);
        }
    }

    private void handleJoinRoom() {
        String roomId = joinRoomIdInput.getText().toString().trim();
        String password = joinRoomPasswordInput.getText().toString().trim();
        String name = joinRoomNameInput.getText().toString().trim();
        if (roomId.isEmpty()) {
            Toast.makeText(this, R.string.room_id_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        if (name.isEmpty()) {
            name = dataManager.getUserName();
            if (name == null || name.isEmpty()) {
                Toast.makeText(this, R.string.room_name_empty, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        dataManager.setUserName(name);
        dataManager.joinRoom(roomId, password, new DataManager.Callback<String>() {
            @Override
            public void onResult(String result) {
                if (password != null && !password.isEmpty()) {
                    saveRoomPassword(roomId, password);
                }
                        dataManager.setShowSharedData(true);
                hideJoinPanel();
                updateShareButtonText();
                updateModeIndicator();
                refreshData();
            }
            @Override
            public void onError(String error) {
                Toast.makeText(FloatingWindowService.this, dataManager.resolveErrorMessage(getLocalizedContext(), error), Toast.LENGTH_LONG).show();
            }
        });
    }

    private static final String FAV_PREFS = "boss_fav_rooms";
    private static final String FAV_KEY = "fav_ids";
    private static final String PWD_PREFS = "boss_room_pwds";

    private void saveRoomPassword(String roomId, String password) {
        if (password == null || password.isEmpty()) return;
        getSharedPreferences(PWD_PREFS, MODE_PRIVATE).edit().putString(roomId, password).apply();
    }

    private String getSavedRoomPassword(String roomId) {
        return getSharedPreferences(PWD_PREFS, MODE_PRIVATE).getString(roomId, "");
    }

    private boolean isRoomFavorite(String roomId) {
        try {
            JSONArray favs = getFavoriteRooms();
            for (int i = 0; i < favs.length(); i++) {
                if (roomId.equals(favs.getJSONObject(i).optString("roomId"))) return true;
            }
        } catch (Exception e) {}
        return false;
    }

    private void toggleFavoriteRoom() {
        if (!dataManager.isSharedMode() || dataManager.getCurrentRoomId() == null) return;
        String rid = dataManager.getCurrentRoomId();
        String rname = dataManager.getCurrentRoomName();
        SharedPreferences sp = getSharedPreferences(FAV_PREFS, MODE_PRIVATE);
        String favJson = sp.getString(FAV_KEY, "[]");
        try {
            JSONArray favs = new JSONArray(favJson);
            JSONArray newFavs = new JSONArray();
            boolean removed = false;
            for (int i = 0; i < favs.length(); i++) {
                JSONObject f = favs.getJSONObject(i);
                if (rid.equals(f.optString("roomId"))) {
                    removed = true;
                } else {
                    newFavs.put(f);
                }
            }
            if (!removed) {
                JSONObject obj = new JSONObject();
                obj.put("roomId", rid);
                obj.put("roomName", rname != null ? rname : rid);
                newFavs.put(obj);
            }
            sp.edit().putString(FAV_KEY, newFavs.toString()).apply();
            if (favoriteIcon != null) favoriteIcon.setText(removed ? "☆" : "★");
            EventBus.getDefault().post(new DataChangedEvent("fav"));
        } catch (Exception e) { Log.e("FloatingWindow", "fav", e); }
    }

    private JSONArray getFavoriteRooms() {
        try {
            String favJson = getSharedPreferences(FAV_PREFS, MODE_PRIVATE).getString(FAV_KEY, "[]");
            return new JSONArray(favJson);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void showInlinePasswordInput(LinearLayout panel, String roomId, String roomName, boolean hasPwd, String icon, Button joinBtn, TextView nameTv) {
        Context c = getLocalizedContext();
        joinBtn.setVisibility(View.GONE);
        nameTv.setText(icon + " " + roomName + " (" + roomId + ")");

        EditText pwdInput = new EditText(FloatingWindowService.this);
        pwdInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pwdInput.setTextSize(11);
        pwdInput.setTextColor(0xFFFFFFFF);
        pwdInput.setHintTextColor(0xAAFFFFFF);
        pwdInput.setHint(c.getString(R.string.room_password_hint));
        pwdInput.setBackgroundColor(0x30000000);
        pwdInput.setPadding(4, 4, 4, 4);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0, dpToPx(26), 0.6f);
        pwdInput.setLayoutParams(elp);

        Button confirmBtn = new Button(FloatingWindowService.this);
        confirmBtn.setText(c.getString(R.string.confirm_btn));
        confirmBtn.setTextSize(11);
        confirmBtn.setTextColor(0xFFFFFFFF);
        confirmBtn.setBackgroundColor(0x00000000);
        confirmBtn.setMinWidth(0);
        confirmBtn.setPadding(8, 0, 8, 0);

        // Remove the old join button and add new views
        ViewGroup row = (ViewGroup) joinBtn.getParent();
        int idx = row.indexOfChild(joinBtn);
        row.removeView(joinBtn);
        row.addView(pwdInput, idx);
        row.addView(confirmBtn, idx + 1);

        confirmBtn.setOnClickListener(cv -> {
            String newPwd = pwdInput.getText().toString().trim();
            dataManager.joinRoom(roomId, newPwd, new DataManager.Callback<String>() {
                @Override public void onResult(String result) {
                    saveRoomPassword(roomId, newPwd);
                    params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                    windowManager.updateViewLayout(floatingView, params);
                    panel.setVisibility(View.GONE);
                    floatingRecyclerView.setVisibility(View.VISIBLE);
                    updateShareButtonText();
                    updateModeIndicator();
                    refreshData();
                }
                @Override public void onError(String error) {
                    Toast.makeText(FloatingWindowService.this, R.string.room_password_wrong, Toast.LENGTH_SHORT).show();
                }
            });
        });

        params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        windowManager.updateViewLayout(floatingView, params);
        pwdInput.requestFocus();
    }

    private void addRoomRow(LinearLayout panel, String roomName, String roomId, boolean hasPwd, String icon) {
        String pwd = hasPwd ? " [pw]" : "";

        LinearLayout row = new LinearLayout(FloatingWindowService.this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(8, 0, 8, 0);
        row.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(32)));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView tv = new TextView(FloatingWindowService.this);
        tv.setText(icon + " " + roomName + " (" + roomId + ")" + pwd);
        tv.setTextSize(12);
        tv.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        tv.setLayoutParams(lp);
        tv.setOnLongClickListener(v -> {
            if (icon.equals("★")) {
                removeFavoriteRoom(roomId);
                floatingRecyclerView.setVisibility(View.VISIBLE);
                panel.setVisibility(View.GONE);
            }
            return true;
        });
        row.addView(tv);

        Button btn = new Button(FloatingWindowService.this);
        btn.setText(getLocalizedContext().getString(R.string.room_join));
        btn.setTextSize(11);
        btn.setTextColor(0xFFFFFFFF);
        btn.setBackgroundColor(0x00000000);
        btn.setMinWidth(0);
        btn.setPadding(12, 0, 12, 0);
        btn.setOnClickListener(v -> {
            String savedPwd = getSavedRoomPassword(roomId);
            dataManager.joinRoom(roomId, savedPwd, new DataManager.Callback<String>() {
                @Override public void onResult(String result) {
                    dataManager.setShowSharedData(true);
                    panel.setVisibility(View.GONE);
                    floatingRecyclerView.setVisibility(View.VISIBLE);
                    updateShareButtonText();
                    updateModeIndicator();
                    refreshData();
                }
                @Override public void onError(String error) {
                    if (error.contains("room not found") || error.contains("not found")) {
                        removeFavoriteRoom(roomId);
                        panel.setVisibility(View.GONE);
                        floatingRecyclerView.setVisibility(View.VISIBLE);
                        Toast.makeText(FloatingWindowService.this, R.string.room_not_found, Toast.LENGTH_SHORT).show();
                    } else if (error.contains("wrong password")) {
                        showInlinePasswordInput(panel, roomId, roomName, hasPwd, icon, btn, tv);
                    } else {
                        Toast.makeText(FloatingWindowService.this, dataManager.resolveErrorMessage(getLocalizedContext(), error), Toast.LENGTH_SHORT).show();
                    }
                }
            });
        });
        row.addView(btn);
        panel.addView(row);

        View div = new View(FloatingWindowService.this);
        div.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        div.setBackgroundColor(0x20FFFFFF);
        panel.addView(div);
    }

    private void removeFavoriteRoom(String roomId) {
        try {
            JSONArray favs = getFavoriteRooms();
            JSONArray newFavs = new JSONArray();
            for (int i = 0; i < favs.length(); i++) {
                JSONObject f = favs.getJSONObject(i);
                if (!roomId.equals(f.optString("roomId"))) {
                    newFavs.put(f);
                }
            }
            getSharedPreferences(FAV_PREFS, MODE_PRIVATE).edit().putString(FAV_KEY, newFavs.toString()).apply();
        } catch (Exception e) { Log.e("FloatingWindow", "fav", e); }
    }

    private void showMyRoomsInFloat() {
        LinearLayout panel = (LinearLayout) roomListPanel;
        if (panel.getVisibility() == View.VISIBLE) {
            panel.setVisibility(View.GONE);
            floatingRecyclerView.setVisibility(View.VISIBLE);
            return;
        }
        if (dataManager.isSharedMode()) return;
        dataManager.fetchMyRooms(new DataManager.Callback<String>() {
            @Override public void onResult(String result) {
                try {
                    JSONObject json = new JSONObject(result);
                    JSONArray rooms = json.optJSONArray("rooms");
                    JSONArray favs = getFavoriteRooms();
                    if ((rooms == null || rooms.length() == 0) && favs.length() == 0) {
                        Toast.makeText(FloatingWindowService.this, getLocalizedContext().getString(R.string.my_rooms_empty), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    LinearLayout panel = (LinearLayout) roomListPanel;
                    panel.removeAllViews();

                    java.util.Set<String> myRoomIds = new java.util.HashSet<>();
                    if (rooms != null) {
                        for (int i = 0; i < rooms.length(); i++) {
                            JSONObject r = rooms.getJSONObject(i);
                            String rid = r.optString("roomId");
                            myRoomIds.add(rid);
                            boolean isOwner = "owner".equals(r.optString("role", "member"))
                                    || "super_admin".equals(r.optString("role", "member"));
                            String icon;
                            if (isOwner) {
                                icon = "◆";
                            } else if (isRoomFavorite(rid)) {
                                icon = "★";
                            } else {
                                icon = "";
                            }
                            addRoomRow(panel, r.optString("roomName"), rid,
                                    r.optBoolean("hasPassword", false), icon);
                        }
                    }
                    for (int i = 0; i < favs.length(); i++) {
                        JSONObject f = favs.getJSONObject(i);
                        String fid = f.optString("roomId");
                        if (!myRoomIds.contains(fid)) {
                            addRoomRow(panel, f.optString("roomName"), fid, false, "★");
                        }
                    }

                    panel.setVisibility(View.VISIBLE);
                    floatingRecyclerView.setVisibility(View.GONE);
                    if (roomInfoBar != null) roomInfoBar.setVisibility(View.GONE);
                } catch (Exception e) {
                    Toast.makeText(FloatingWindowService.this, R.string.room_error, Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onError(String error) {
                Toast.makeText(FloatingWindowService.this, getLocalizedContext().getString(R.string.my_rooms_empty), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateRoomInfoDisplay() {
        boolean canSeeLogs = dataManager.isOwner() || "admin".equals(dataManager.getMyRole());
        floatingView.findViewById(R.id.btn_logs).setVisibility(canSeeLogs ? View.VISIBLE : View.GONE);
        floatingView.findViewById(R.id.btn_leave_room).setVisibility(View.VISIBLE);
        floatingView.findViewById(R.id.btn_switch_room).setVisibility(View.VISIBLE);
        Context c = getLocalizedContext();
        String roleText;
        switch (dataManager.getMyRole()) {
            case "owner": roleText = c.getString(R.string.role_owner); break;
            case "super_admin": roleText = c.getString(R.string.role_super_admin); break;
            case "admin": roleText = c.getString(R.string.role_admin); break;
            default: roleText = c.getString(R.string.role_member); break;
        }
        StringBuilder info = new StringBuilder();
        info.append(c.getString(R.string.room_info_prefix)).append(dataManager.getCurrentRoomId())
            .append(" · ").append(roleText)
            .append("\n").append(c.getString(R.string.room_version_label)).append(dataManager.getCurrentRoomVersion());
        roomInfoText.setText(info.toString());
        roomInfoText.setMaxLines(3);

        if (dataManager.isOwner()) {
            dataManager.fetchRoomPassword(new DataManager.Callback<String>() {
                @Override public void onResult(String result) {
                    try {
                        JSONObject json = new JSONObject(result);
                        String pwdInfo;
                        if (json.optBoolean("hasPassword", false)) {
                            String pwd = json.optString("password", "");
                            pwdInfo = " | " + c.getString(R.string.room_password_label) + (pwd.isEmpty() ? "******" : pwd);
                        } else {
                            pwdInfo = " | " + c.getString(R.string.room_no_password);
                        }
                        String text = info.toString();
                        String[] lines = text.split("\n");
                        lines[1] = lines[1] + pwdInfo;
                        roomInfoText.setText(lines[0] + "\n" + lines[1]);
                    } catch (Exception e) {}
                }
                @Override public void onError(String error) {}
            });
        }
    }

    private void toggleRoomInfo() {
        if (!dataManager.isSharedMode()) {
            showMyRoomsInFloat();
            return;
        }
        if (roomInfoBar == null || roomInfoText == null) return;
        if (roomInfoBar.getVisibility() == View.VISIBLE) {
            showingLogs = false;
            roomInfoBar.setVisibility(View.GONE);
            roomListPanel.setVisibility(View.GONE);
            floatingRecyclerView.setVisibility(View.VISIBLE);
            updateRoomButtonText();
            updateRecyclerViewHeight();
        } else {
            if (showingLogs) {
                showingLogs = false;
                roomListPanel.setVisibility(View.GONE);
                updateRoomInfoDisplay();
                roomInfoBar.setVisibility(View.VISIBLE);
                updateRoomButtonText();
                updateSwitchRoomButtonText();
                updateRecyclerViewHeight();
                return;
            }
            roomListPanel.setVisibility(View.GONE);
            updateRoomInfoDisplay();
            roomInfoBar.setVisibility(View.VISIBLE);
            updateRoomButtonText();
            updateSwitchRoomButtonText();
            updateRecyclerViewHeight();
        }
    }

    private void refreshData() {
        if (dataManager != null) {
            List<RowData> newData = dataManager.getAllBosses();
            if (adapter != null) {
                adapter.updateData(newData);
                updateRecyclerViewHeight();
            }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.floating_service_notification_title),
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            serviceChannel.setSound(null, null);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.floating_service_notification_title))
                .setContentText(getString(R.string.floating_service_notification_text))
                .setSmallIcon(R.drawable.recluse)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private void initFloatingWindow() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            Log.e("FloatingWindow", "WindowManager is null");
            return;
        }

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window, null);
        if (floatingView == null) {
            Log.e("FloatingWindow", "Failed to inflate floating view");
            return;
        }

        tvTitleName = floatingView.findViewById(R.id.tv_title_name);
        tvTitleRefresh = floatingView.findViewById(R.id.tv_title_refresh);
        tvTitleRemaining = floatingView.findViewById(R.id.tv_title_remaining);
        tvTitleReset = floatingView.findViewById(R.id.tv_title_reset);

        recyclerView = floatingView.findViewById(R.id.floating_recycler_view);
        Context localizedContext = getLocalizedContext();
        adapter = new FloatingWindowAdapter(new ArrayList<>(), localizedContext);
        adapter.setRecyclerView(recyclerView);
        adapter.updateData(dataManager.getAllBosses());
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        float density = getResources().getDisplayMetrics().density;
        int maxHeight = (int) (MAX_ITEMS * ITEM_HEIGHT_DP * density);
        int itemCount = adapter.getItemCount();
        int initialHeight = (int) (Math.min(itemCount, MAX_ITEMS) * ITEM_HEIGHT_DP * density);
        ViewGroup.LayoutParams recyclerViewParams = recyclerView.getLayoutParams();
        recyclerViewParams.height = Math.min(initialHeight, maxHeight);
        recyclerView.setLayoutParams(recyclerViewParams);

        params = new WindowManager.LayoutParams(
                dpToPx(260),
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 100;

        floatingView.setVisibility(View.VISIBLE);
        windowManager.addView(floatingView, params);

        recyclerView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                recyclerView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                updateRecyclerViewHeight();
            }
        });

        Button minimizeBtn = floatingView.findViewById(R.id.btn_minimize);
        ImageButton moveBtn = floatingView.findViewById(R.id.btn_floating_window_move);
        shareBtn = floatingView.findViewById(R.id.btn_share);
        roomBtn = floatingView.findViewById(R.id.btn_room);
        joinRoomPanel = floatingView.findViewById(R.id.join_room_panel);
        roomListPanel = floatingView.findViewById(R.id.room_list_panel);
        authPanel = floatingView.findViewById(R.id.auth_panel);
        authInput = floatingView.findViewById(R.id.auth_input);
        authTitle = floatingView.findViewById(R.id.auth_title);
        authConfirmBtn = floatingView.findViewById(R.id.btn_auth_confirm);
        authCancelBtn = floatingView.findViewById(R.id.btn_auth_cancel);
        joinRoomIdInput = floatingView.findViewById(R.id.join_room_id);
        joinRoomPasswordInput = floatingView.findViewById(R.id.join_room_password);
        joinRoomNameInput = floatingView.findViewById(R.id.join_room_name);
        roomInfoBar = floatingView.findViewById(R.id.room_info_bar);
        roomInfoText = floatingView.findViewById(R.id.room_info_text);
        TextView leaveBtn = floatingView.findViewById(R.id.btn_leave_room);
        leaveBtn.setOnClickListener(v -> {
            dataManager.leaveRoom();
            roomInfoBar.setVisibility(View.GONE);
            dataManager.setShowSharedData(false);
            updateShareButtonText();
            updateModeIndicator();
            refreshData();
        });
        floatingView.findViewById(R.id.btn_logs).setOnClickListener(v -> showRoomLogs());
        floatingView.findViewById(R.id.btn_switch_room).setOnClickListener(v -> showSwitchRoomPanel());

        roomBar = floatingView.findViewById(R.id.tv_room_bar);
        roomBarLayout = floatingView.findViewById(R.id.room_bar_layout);
        roomBarText = floatingView.findViewById(R.id.tv_room_bar);
        favoriteArea = floatingView.findViewById(R.id.favorite_area);
        favoriteIcon = floatingView.findViewById(R.id.tv_favorite_icon);
        favoriteText = floatingView.findViewById(R.id.tv_favorite_text);
        tvUserName = floatingView.findViewById(R.id.tv_user_name);
        favoriteArea.setOnClickListener(v -> toggleFavoriteRoom());
        floatingRecyclerView = recyclerView;

        Button joinConfirmBtn = floatingView.findViewById(R.id.btn_join_confirm);
        Button joinCancelBtn = floatingView.findViewById(R.id.btn_join_cancel);

        minimizeBtn.setOnClickListener(v -> toggleMinimize());
        shareBtn.setOnClickListener(v -> toggleShareMode());
        roomBtn.setOnClickListener(v -> toggleRoomInfo());
        joinConfirmBtn.setOnClickListener(v -> handleJoinRoom());
        joinCancelBtn.setOnClickListener(v -> hideJoinPanel());
        authConfirmBtn.setOnClickListener(v -> submitAuth());
        authCancelBtn.setOnClickListener(v -> hideAuthPanel());
        updateShareButtonText();
        updateModeIndicator();

        View titleBar = floatingView.findViewById(R.id.floating_title);

        floatingView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                floatingView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                floatingViewWidth = floatingView.getWidth();
                floatingViewHeight = floatingView.getHeight();

                floatingView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        boolean panelShowing = (authPanel != null && authPanel.getVisibility() == View.VISIBLE)
                                || (joinRoomPanel != null && joinRoomPanel.getVisibility() == View.VISIBLE);
                        if (panelShowing) return;
                        updateScreenBounds();
                        int maxH = appUsableHeight - dpToPx(20);
                        if (floatingView != null && floatingView.getParent() != null) {
                            int currentH = floatingView.getHeight();
                            boolean isCapped = (params.height != WindowManager.LayoutParams.WRAP_CONTENT);
                            if (currentH > maxH && !isCapped) {
                                params.height = maxH;
                                windowManager.updateViewLayout(floatingView, params);
                            } else if (currentH < maxH - 50 && isCapped) {
                                params.height = WindowManager.LayoutParams.WRAP_CONTENT;
                                windowManager.updateViewLayout(floatingView, params);
                            }
                        }
                    }
                });

                titleBar.setOnTouchListener(new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        switch (event.getAction()) {
                            case MotionEvent.ACTION_DOWN:
                                updateScreenBounds();
                                initialX = params.x;
                                initialY = params.y;
                                initialTouchX = event.getRawX();
                                initialTouchY = event.getRawY();
                                return true;
                            case MotionEvent.ACTION_MOVE:
                                params.x = initialX + (int) (event.getRawX() - initialTouchX);
                                params.y = initialY + (int) (event.getRawY() - initialTouchY);
                                if (params.x < 0) params.x = 0;
                                if (params.x > appUsableWidth - floatingViewWidth)
                                    params.x = appUsableWidth - floatingViewWidth;
                                if (params.y < 0) params.y = 0;
                                if (params.y > appUsableHeight - floatingViewHeight)
                                    params.y = appUsableHeight - floatingViewHeight;
                                windowManager.updateViewLayout(floatingView, params);
                                return true;
                        }
                        return false;
                    }
                });

                moveBtn.setOnTouchListener(new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        switch (event.getAction()) {
                            case MotionEvent.ACTION_DOWN:
                                updateScreenBounds();
                                initialX = params.x;
                                initialY = params.y;
                                initialTouchX = event.getRawX();
                                initialTouchY = event.getRawY();
                                return true;
                            case MotionEvent.ACTION_MOVE:
                                params.x = initialX + (int) (event.getRawX() - initialTouchX);
                                params.y = initialY + (int) (event.getRawY() - initialTouchY);
                                if (params.x < 0) params.x = 0;
                                if (params.x > appUsableWidth - floatingViewWidth)
                                    params.x = appUsableWidth - floatingViewWidth;
                                if (params.y < 0) params.y = 0;
                                if (params.y > appUsableHeight - floatingViewHeight)
                                    params.y = appUsableHeight - floatingViewHeight;
                                windowManager.updateViewLayout(floatingView, params);
                                return true;
                        }
                        return false;
                    }
                });

                roomBarLayout.setOnTouchListener(new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        switch (event.getAction()) {
                            case MotionEvent.ACTION_DOWN:
                                updateScreenBounds();
                                initialX = params.x;
                                initialY = params.y;
                                initialTouchX = event.getRawX();
                                initialTouchY = event.getRawY();
                                return true;
                            case MotionEvent.ACTION_MOVE:
                                params.x = initialX + (int) (event.getRawX() - initialTouchX);
                                params.y = initialY + (int) (event.getRawY() - initialTouchY);
                                if (params.x < 0) params.x = 0;
                                if (params.x > appUsableWidth - floatingViewWidth)
                                    params.x = appUsableWidth - floatingViewWidth;
                                if (params.y < 0) params.y = 0;
                                if (params.y > appUsableHeight - floatingViewHeight)
                                    params.y = appUsableHeight - floatingViewHeight;
                                windowManager.updateViewLayout(floatingView, params);
                                return true;
                        }
                        return false;
                    }
                });
            }
        });

        adapter.setOnButtonClickListener(new ItemAdapter.OnButtonClickListener() {
            @Override
            public void onButtonClick(int position, ItemAdapter.ButtonType buttonType) {
                if (buttonType == ItemAdapter.ButtonType.RESET) {
                    adapter.resetTime(position);
                }
            }
        });

        updateFloatingWindowTexts();
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private void updateRecyclerViewHeight() {
        if (recyclerView == null || adapter == null) return;
        recyclerView.post(() -> {
            float density = getResources().getDisplayMetrics().density;
            int maxItems = (showingLogs || (roomInfoBar != null && roomInfoBar.getVisibility() == View.VISIBLE)
                    || (roomListPanel != null && roomListPanel.getVisibility() == View.VISIBLE)) ? 3 : MAX_ITEMS;
            int maxHeight = (int) (maxItems * ITEM_HEIGHT_DP * density);
            int itemCount = adapter.getItemCount();
            int currentHeight = (int) (Math.min(itemCount, maxItems) * ITEM_HEIGHT_DP * density);
            ViewGroup.LayoutParams params = recyclerView.getLayoutParams();
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            params.height = Math.min(currentHeight, maxHeight);
            recyclerView.setLayoutParams(params);
            recyclerView.setVerticalScrollBarEnabled(itemCount > MAX_ITEMS);
        });
    }

    private void toggleMinimize() {
        if (isTransitioning) return;

        if (!isMinimized) {
            if (floatingView_minimized == null) {
                isTransitioning = true;
                floatingView_minimized = LayoutInflater.from(this).inflate(R.layout.floating_window_minimized, null);
                params_minimized = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                                WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT);
                params_minimized.gravity = Gravity.TOP | Gravity.START;
                params_minimized.x = params.x;
                params_minimized.y = params.y;

                minimizedTimeText = floatingView_minimized.findViewById(R.id.minimized_time);
                batteryText = floatingView_minimized.findViewById(R.id.minimized_battery);
                initBatteryMonitor();
                updateBatteryLevel();

                touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

                floatingView_minimized.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        floatingView_minimized.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        floatingViewWidth_minimized = floatingView_minimized.getWidth();
                        floatingViewHeight_minimized = floatingView_minimized.getHeight();

                        floatingView_minimized.setOnTouchListener(new View.OnTouchListener() {
                            @Override
                            public boolean onTouch(View v, MotionEvent event) {
                                switch (event.getAction()) {
                                    case MotionEvent.ACTION_DOWN:
                                        updateScreenBounds();
                                        initialX = params_minimized.x;
                                        initialY = params_minimized.y;
                                        initialTouchX = event.getRawX();
                                        initialTouchY = event.getRawY();
                                        isMoving = false;
                                        return false;
                                    case MotionEvent.ACTION_MOVE:
                                        float dx = Math.abs(event.getRawX() - initialTouchX);
                                        float dy = Math.abs(event.getRawY() - initialTouchY);
                                        if (dx > touchSlop || dy > touchSlop) {
                                            isMoving = true;
                                            if (!isMinimizedLocked) {
                                                params_minimized.x = initialX + (int) (event.getRawX() - initialTouchX);
                                                params_minimized.y = initialY + (int) (event.getRawY() - initialTouchY);
                                                if (params_minimized.x < 0) params_minimized.x = 0;
                                                if (params_minimized.x > appUsableWidth - floatingViewWidth_minimized)
                                                    params_minimized.x = appUsableWidth - floatingViewWidth_minimized;
                                                if (params_minimized.y < 0) params_minimized.y = 0;
                                                if (params_minimized.y > appUsableHeight - floatingViewHeight_minimized)
                                                    params_minimized.y = appUsableHeight - floatingViewHeight_minimized;
                                                windowManager.updateViewLayout(floatingView_minimized, params_minimized);
                                            }
                                        }
                                        return false;
                                    case MotionEvent.ACTION_UP:
                                        long now = System.currentTimeMillis();
                                        if (now - lastClickTime < DOUBLE_CLICK_TIME_DELTA && !isMoving) {
                                            isMinimizedLocked = !isMinimizedLocked;
                                            lastClickTime = 0;
                                            return true;
                                        }
                                        lastClickTime = now;
                                        if (!isMoving && !isMinimizedLocked) {
                                            restoreFromMinimize();
                                        }
                                        isMoving = false;
                                        return false;
                                }
                                return false;
                            }
                        });
                    }
                });

                floatingView.setVisibility(View.GONE);
                windowManager.addView(floatingView_minimized, params_minimized);
                isMinimized = true;
            } else {
                minimize();
            }
            isTransitioning = false;
        }
    }

    private void updateTime() {
        if (minimizedTimeText != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            String currentTime = sdf.format(new Date());
            minimizedTimeText.setText(currentTime);
        }
    }

    private void startGlobalTick() {
        if (globalTickRunnable != null) return;
        globalTickRunnable = new Runnable() {
            @Override
            public void run() {
                if (isMinimized) {
                    updateTime();
                    globalTickHandler.postDelayed(this, 1000);
                } else {
                    boolean hasOverlay = (authPanel != null && authPanel.getVisibility() == View.VISIBLE)
                            || (joinRoomPanel != null && joinRoomPanel.getVisibility() == View.VISIBLE);
                    if (!hasOverlay && adapter != null) {
                        adapter.onTick();
                    }
                    if (!hasOverlay) {
                        scheduleNextNotification();
                    }
                    globalTickHandler.postDelayed(this, 1000);
                }
            }
        };
        globalTickHandler.post(globalTickRunnable);
        scheduleNextNotification();
    }

    private void stopGlobalTick() {
        if (globalTickHandler != null) {
            if (globalTickRunnable != null) {
                globalTickHandler.removeCallbacks(globalTickRunnable);
                globalTickRunnable = null;
            }
            if (scheduledNotifyRunnable != null) {
                globalTickHandler.removeCallbacks(scheduledNotifyRunnable);
                scheduledNotifyRunnable = null;
            }
        }
        cancelAlarm();
    }

    private void setAlarm(long triggerTimeMs) {
        if (alarmManager == null) {
            alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        }
        if (alarmManager == null) return;
        cancelAlarm();
        alarmPendingIntent = AlarmReceiver.createPendingIntent(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMs, alarmPendingIntent);
            } else {
                alarmManager.setAlarmClock(
                        new AlarmManager.AlarmClockInfo(triggerTimeMs, alarmPendingIntent),
                        alarmPendingIntent);
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMs, alarmPendingIntent);
        }
    }

    private void cancelAlarm() {
        if (alarmManager != null && alarmPendingIntent != null) {
            alarmManager.cancel(alarmPendingIntent);
            alarmPendingIntent = null;
        }
    }

    private void scheduleNextNotification() {
        if (globalTickHandler == null) return;
        if (scheduledNotifyRunnable != null) {
            globalTickHandler.removeCallbacks(scheduledNotifyRunnable);
            scheduledNotifyRunnable = null;
        }

        long now = System.currentTimeMillis();
        long nextNotifyTime = Long.MAX_VALUE;

        List<RowData> allBosses = new ArrayList<>();
        allBosses.addAll(dbHelper.getAllBosses());
        String curRoomId = dataManager.getCurrentRoomId();
        if (curRoomId != null) {
            allBosses.addAll(dbHelper.getAllBossesByRoom(curRoomId));
        }
        List<String> otherRooms = dbHelper.getAllRoomIds();
        for (String rId : otherRooms) {
            if (!rId.equals(curRoomId) && isRoomFavorite(rId)) {
                allBosses.addAll(dbHelper.getAllBossesByRoom(rId));
            }
        }

        java.util.Map<Long, RowData> deduped = new java.util.HashMap<>();
        for (RowData d : allBosses) {
            deduped.put(d.id, d);
        }

        for (RowData data : deduped.values()) {
            if (!data.autoReset || data.spawnTime <= 0) continue;
            long elapsedSeconds = data.spawnTime - ((now - data.startTime) / 1000);
            if (elapsedSeconds <= 0) {
                long cycle = data.spawnTime * 1000L;
                long cycles = (now - data.startTime) / cycle;
                long newStartTime = data.startTime + cycles * cycle;
                while (newStartTime + cycle <= now) {
                    newStartTime += cycle;
                }
                if (data.docId != null) {
                    dataManager.resetBossShared(data.id, newStartTime);
                } else {
                    dataManager.resetBossStartTime(data.id, newStartTime);
                }
                data.startTime = newStartTime;
                data.isNotified = false;
            }
        }

        for (RowData data : deduped.values()) {
            if (!data.autoReset || data.spawnTime <= 0) continue;
            long expireTimestamp = data.startTime + data.spawnTime * 1000L;
            if (expireTimestamp <= now) continue;
            if (expireTimestamp < nextNotifyTime) {
                nextNotifyTime = expireTimestamp;
            }
        }

        boolean isNotifyAlarm = false;
        for (RowData data : deduped.values()) {
            if (!data.needNotify || data.isNotified || data.spawnTime <= 0) continue;
            long elapsedSeconds = data.spawnTime - ((now - data.startTime) / 1000);
            if (elapsedSeconds <= 0) continue;
            long notifyAtTimestamp = data.startTime + (data.spawnTime - data.notifyTime) * 1000;
            if (notifyAtTimestamp <= now) {
                notifyAtTimestamp = now + 1;
            }
            if (notifyAtTimestamp < nextNotifyTime) {
                nextNotifyTime = notifyAtTimestamp;
                isNotifyAlarm = true;
            }
        }

        if (nextNotifyTime <= now) {
            doNotificationChecks();
        } else if (nextNotifyTime < Long.MAX_VALUE) {
            long delay = nextNotifyTime - now;
            scheduledNotifyRunnable = () -> {
                doNotificationChecks();
            };
            globalTickHandler.postDelayed(scheduledNotifyRunnable, delay);
            long alarmOffset = isNotifyAlarm ? 15 * 60 * 1000L : 5000L;
            long alarmTime = Math.max(nextNotifyTime - alarmOffset, now + 1000);
            if (now >= lastAlarmTarget || Math.abs(alarmTime - lastAlarmTarget) >= 1000) {
                lastAlarmTarget = alarmTime;
                setAlarm(alarmTime);
            }
        } else {
            lastAlarmTarget = 0;
            cancelAlarm();
        }
    }

    void doNotificationChecks() {
        if (isCheckingNotifications) return;
        isCheckingNotifications = true;
        boolean anyNotified = false;
        try {

        manageWakeLock();

        if (dataManager.isSharedMode()) {
            long minRemaining = Long.MAX_VALUE;
            boolean nearNotify = false;
            List<RowData> all = dataManager.getAllBosses();
            for (RowData d : all) {
                if (d.spawnTime <= 0 || !d.needNotify) continue;
                long r = d.spawnTime - ((System.currentTimeMillis() - d.startTime) / 1000);
                if (r > 0 && r < minRemaining) minRemaining = r;
                if (r > 0 && r <= d.notifyTime && !d.isNotified) nearNotify = true;
            }
            long cooldown;
            if (nearNotify) {
                cooldown = 5000;
            } else if (minRemaining <= 420) {
                cooldown = 140 * 1000L;
            } else if (minRemaining <= 900) {
                cooldown = 7 * 60 * 1000L;
            } else if (minRemaining <= 1800) {
                cooldown = 30 * 60 * 1000L;
            } else if (minRemaining <= 3600) {
                cooldown = 60 * 60 * 1000L;
            } else {
                cooldown = 120 * 60 * 1000L;
            }
            if (System.currentTimeMillis() - lastSyncTime >= cooldown) {
                lastSyncTime = System.currentTimeMillis();
                dataManager.forceSync();
            }
        }

        notifiedBossIds.clear();

        List<RowData> currentData = dataManager.getAllBosses();
        for (RowData data : currentData) {
            long elapsedSeconds = data.spawnTime - ((System.currentTimeMillis() - data.startTime) / 1000);
            if (elapsedSeconds >= 0) {
                if (checkAndNotify(data, elapsedSeconds)) anyNotified = true;
            }
        }

        if (dataManager.isShowingSharedData()) {
            List<RowData> localBosses = dbHelper.getAllBosses();
            for (RowData b : localBosses) {
                long el = b.spawnTime - ((System.currentTimeMillis() - b.startTime) / 1000);
                if (el >= 0 && el <= b.notifyTime && !b.isNotified && b.needNotify) {
                    if (checkAndNotify(b, el)) anyNotified = true;
                    if (!hasLocalNotify) {
                        hasLocalNotify = true;
                        updateShareButtonText();
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
                        if (checkAndNotify(b, el)) anyNotified = true;
                        if (!hasSharedNotify) {
                            hasSharedNotify = true;
                            updateShareButtonText();
                        }
                    }
                }
            }
        }

        List<String> allRoomIds = dbHelper.getAllRoomIds();
        String curRoomId = dataManager.getCurrentRoomId();
        java.util.Set<String> checkedRooms = new java.util.HashSet<>();
        for (String rId : allRoomIds) {
            if (rId == null || rId.equals(curRoomId)) continue;
            if (!isRoomFavorite(rId)) continue;
            if (checkedRooms.contains(rId)) continue;
            checkedRooms.add(rId);
            List<RowData> roomBosses = dbHelper.getAllBossesByRoom(rId);
            boolean hasNotify = false;
            for (RowData b : roomBosses) {
                long el = b.spawnTime - ((System.currentTimeMillis() - b.startTime) / 1000);
                if (el >= 0 && el <= b.notifyTime && !b.isNotified && b.needNotify) {
                    if (checkAndNotify(b, el)) anyNotified = true;
                    hasNotify = true;
                }
            }
            if (hasNotify) {
                dataManager.addPendingNotifyRoom(rId);
                updateRoomButtonText();
                updateSwitchRoomButtonText();
            }
        }

        if (anyNotified && vibrator != null && vibrator.hasVibrator()) {
            long now = System.currentTimeMillis();
            if (now - lastVibrateTime > 5000) {
                lastVibrateTime = now;
                vibrator.cancel();
                vibrator.vibrate(1000);
            }
        }

        if (suppressNextNotify) suppressNextNotify = false;
        scheduleNextNotification();
        } finally {
            isCheckingNotifications = false;
        }
    }

    private void manageWakeLock() {
        long now = System.currentTimeMillis();
        boolean needLock = false;
        long maxNotifyTime = MIN_WAKELOCK_TIMEOUT_SEC;

        List<RowData> allBosses = new ArrayList<>();
        allBosses.addAll(dbHelper.getAllBosses());
        String curRoomId = dataManager.getCurrentRoomId();
        if (curRoomId != null) {
            allBosses.addAll(dbHelper.getAllBossesByRoom(curRoomId));
        }
        List<String> otherRooms = dbHelper.getAllRoomIds();
        for (String rId : otherRooms) {
            if (!rId.equals(curRoomId) && isRoomFavorite(rId)) {
                allBosses.addAll(dbHelper.getAllBossesByRoom(rId));
            }
        }
        java.util.Map<Long, RowData> deduped = new java.util.HashMap<>();
        for (RowData d : allBosses) {
            deduped.put(d.id, d);
        }
        for (RowData data : deduped.values()) {
            long remaining = data.spawnTime - ((now - data.startTime) / 1000);
            if (remaining > 0 && remaining <= data.notifyTime + 900 && data.needNotify && !data.isNotified) {
                needLock = true;
                if (data.notifyTime > maxNotifyTime) maxNotifyTime = data.notifyTime;
            }
        }

        if (needLock) {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BossTimer:notify");
                wakeLock.acquire(maxNotifyTime * 1000);
            }
        } else {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                wakeLock = null;
            }
        }
    }

    private boolean checkAndNotify(RowData data, long elapsedSeconds) {
        if (suppressNextNotify) return false;
        if (elapsedSeconds <= data.notifyTime && !data.isNotified && data.needNotify) {
            if (notifiedBossIds.contains(data.id)) return false;
            notifiedBossIds.add(data.id);
            String title = getString(R.string.notification_title);
            String content = String.format(Locale.getDefault(),
                    getString(R.string.notification_content),
                    data.text1,
                    elapsedSeconds / 3600,
                    (elapsedSeconds % 3600) / 60,
                    elapsedSeconds % 60);
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "boss_timer")
                    .setSmallIcon(R.drawable.recluse)
                    .setContentTitle(title)
                    .setContentText(content)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setCategory(NotificationCompat.CATEGORY_EVENT)
                    .setAutoCancel(true)
                    .setOngoing(false);
            notificationManager.notify(BOSS_NOTIFICATION_ID, builder.build());
            data.isNotified = true;
            dataManager.setIsNotified(data.id, true);
            return true;
        }
        return false;
    }

    private void minimize() {
        if (isTransitioning) return;
        isTransitioning = true;
        if (floatingView != null && floatingView.getParent() != null) {
            floatingView.setVisibility(View.GONE);
        }
        if (floatingView_minimized != null && floatingView_minimized.getParent() == null) {
            params_minimized.x = params.x;
            params_minimized.y = params.y;
            windowManager.addView(floatingView_minimized, params_minimized);
        }
        isMinimized = true;
        isTransitioning = false;
    }

    private void restoreFromMinimize() {
        if (isTransitioning) return;
        isTransitioning = true;

        isMinimizedLocked = false;

        if (floatingView_minimized != null && floatingView_minimized.getParent() != null) {
            windowManager.removeView(floatingView_minimized);
        }
        if (floatingView != null) {
            params.x = params_minimized.x;
            params.y = params_minimized.y;
            windowManager.updateViewLayout(floatingView, params);
            floatingView.setVisibility(View.VISIBLE);
        }
        refreshData();
        if (dataManager.isSharedMode()
                && System.currentTimeMillis() - lastExpandSyncTime >= EXPAND_SYNC_COOLDOWN_MS) {
            lastExpandSyncTime = System.currentTimeMillis();
            dataManager.forceSync();
        }
        showingLogs = false;
        if (roomInfoBar != null) roomInfoBar.setVisibility(View.GONE);
        if (roomListPanel != null) roomListPanel.setVisibility(View.GONE);
        if (floatingRecyclerView != null) floatingRecyclerView.setVisibility(View.VISIBLE);
        updateRecyclerViewHeight();
        updateRoomButtonText();
        isMinimized = false;
        isTransitioning = false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (instance == this) {
            instance = null;
        }

        stopGlobalTick();

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }

        if (adapter != null) {
            adapter.stopTimer();
            recyclerView.setAdapter(null);
            adapter = null;
        }

        stopForeground(true);

        if (floatingView != null && floatingView.getParent() != null) {
            windowManager.removeView(floatingView);
        }
        if (floatingView_minimized != null && floatingView_minimized.getParent() != null) {
            windowManager.removeView(floatingView_minimized);
        }

        if (batteryReceiver != null && isBatteryReceiverRegistered) {
            unregisterReceiver(batteryReceiver);
            batteryReceiver = null;
            isBatteryReceiverRegistered = false;
        }

        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public boolean isShowing() {
        return isShowing;
    }

    private boolean showingLogs = false;

    private void showRoomLogs() {
        if (roomListPanel == null) return;
        if (!dataManager.isOwner() && !"admin".equals(dataManager.getMyRole())) return;
        if (showingLogs) {
            showingLogs = false;
            roomListPanel.setVisibility(View.GONE);
            updateRecyclerViewHeight();
            return;
        }
        Context c = getLocalizedContext();
        dataManager.fetchLogs(dataManager.getCurrentRoomId(), new DataManager.Callback<String>() {
            @Override public void onResult(String result) {
                try {
                    JSONArray logs = new JSONObject(result).optJSONArray("logs");
                    LinearLayout panel = (LinearLayout) roomListPanel;
                    panel.removeAllViews();
                    if (logs == null || logs.length() == 0) return;
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd | HH:mm", java.util.Locale.getDefault());
                    int shown = 0;
                    for (int i = 0; i < logs.length() && shown < 2; i++) {
                        JSONObject l = logs.getJSONObject(i); String a = l.optString("action");
                        if (!"delete".equals(a) && !"edit".equals(a) && !"reset".equals(a)) continue;
                        JSONArray chs = l.optJSONArray("changes");
                        boolean hasTimeChange = false;
                        if (chs != null) for (int ct = 0; ct < chs.length(); ct++) {
                            String ff = chs.optJSONObject(ct).optString("field");
                            if ("startTime".equals(ff) || "spawn".equals(ff)) { hasTimeChange = true; break; }
                        }
                        if (!"delete".equals(a) && !"reset".equals(a) && !hasTimeChange) continue;

                        String lb;
                        if ("delete".equals(a)) lb = c.getString(R.string.log_delete);
                        else if ("reset".equals(a)) lb = c.getString(R.string.log_reset);
                        else lb = c.getString(R.string.log_edit);
                        StringBuilder tx = new StringBuilder();
                        tx.append(sdf.format(new java.util.Date(l.optLong("time")))).append(" | ").append(l.optString("userName")).append(" | ").append(l.optString("target")).append("【").append(lb).append("】");
                        if (chs != null) for (int ct=0; ct<chs.length(); ct++) {
                            JSONObject ch = chs.getJSONObject(ct); String f = ch.optString("field");
                            if ("startTime".equals(f)) {
                                tx.append("\n").append(c.getString(R.string.log_end_time)).append(":").append(formatFloatTime(ch.optLong("oldRefresh"))).append("→").append(formatFloatTime(ch.optLong("newRefresh")));
                                long sp = ch.optLong("spawn", 0);
                                if (sp > 0) tx.append("\n").append(c.getString(R.string.log_reset_time)).append(":").append(formatSeconds(sp));
                            } else if ("spawn".equals(f)) {
                                tx.append("\n").append(c.getString(R.string.log_reset_time)).append(":").append(formatSeconds(ch.optLong("old"))).append("→").append(formatSeconds(ch.optLong("new")));
                            }
                        }
                        if ("delete".equals(a)) {
                            long rt = l.optLong("refreshTime", 0); if (rt > 0) tx.append("\n").append(c.getString(R.string.log_end_time)).append(":").append(formatFloatTime(rt));
                            long sp = l.optLong("spawn", 0); if (sp > 0) tx.append("\n").append(c.getString(R.string.log_reset_time)).append(":").append(formatSeconds(sp));
                        } else if ("reset".equals(a)) {
                            long oldRt = l.optLong("oldEndTime", 0);
                            long rt = l.optLong("endTime", 0);
                            if (rt > 0) {
                                tx.append("\n").append(c.getString(R.string.log_end_time)).append(":");
                                if (oldRt > 0) tx.append(formatFloatTime(oldRt)).append("→");
                                tx.append(formatFloatTime(rt));
                            }
                            long sp = l.optLong("spawn", 0);
                            if (sp > 0) tx.append("\n").append(c.getString(R.string.log_reset_time)).append(":").append(formatSeconds(sp));
                        }
                        TextView tv = new TextView(c);
                        tv.setText(tx.toString()); tv.setTextSize(12); tv.setTextColor(0xFFFFFFFF); tv.setPadding(8,6,8,6);
                        panel.addView(tv);
                        View dv = new View(c);
                        dv.setLayoutParams(new LinearLayout.LayoutParams(-1,1)); dv.setBackgroundColor(0x40FFFFFF); panel.addView(dv);
                        shown++;
                    }
                    roomInfoBar.setVisibility(View.GONE);
                    panel.setVisibility(View.VISIBLE);
                    showingLogs = true;
                    updateRecyclerViewHeight();
                } catch(Exception e){}
            }
            @Override public void onError(String e){}
        });
    }

    private String formatSeconds(long s) {
        return String.format(java.util.Locale.getDefault(), "%02d:%02d:%02d", Math.abs(s) / 3600, (Math.abs(s) / 60) % 60, Math.abs(s) % 60);
    }

    private String formatFloatTime(long millis) {
        java.util.Calendar n = java.util.Calendar.getInstance(), t = java.util.Calendar.getInstance(); t.setTimeInMillis(millis);
        if (n.get(java.util.Calendar.DAY_OF_YEAR) == t.get(java.util.Calendar.DAY_OF_YEAR)
            && n.get(java.util.Calendar.YEAR) == t.get(java.util.Calendar.YEAR))
            return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date(millis));
        return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(millis));
    }

    private void showSwitchRoomPanel() {
        if (roomListPanel == null) return;
        LinearLayout panel = (LinearLayout) roomListPanel;
        if (panel.getVisibility() == View.VISIBLE) {
            panel.setVisibility(View.GONE);
            roomInfoBar.setVisibility(View.VISIBLE);
            updateRecyclerViewHeight();
            return;
        }
        List<String> pendingRooms = dataManager.getAndClearPendingRooms();
        updateRoomButtonText();
        Context c = getLocalizedContext();
        java.util.Set<String> addedIds = new java.util.HashSet<>();
        panel.removeAllViews();

        // 1. 有通知的房间（红点高亮置顶）
        for (String rid : pendingRooms) {
            if (addedIds.contains(rid)) continue;
            addedIds.add(rid);
            JSONArray favs = getFavoriteRooms();
            String name = rid;
            for (int i = 0; i < favs.length(); i++) {
                JSONObject f = favs.optJSONObject(i);
                if (rid.equals(f.optString("roomId"))) {
                    name = f.optString("roomName", rid);
                    break;
                }
            }
            addSwitchRoomRow(panel, name, rid, "●");
        }

        // 2. 获取自己的房主房间 + 3. 其他收藏房间
        String curRoomId = dataManager.getCurrentRoomId();
        JSONArray favs = getFavoriteRooms();
        dataManager.fetchMyRooms(new DataManager.Callback<String>() {
            @Override public void onResult(String result) {
                try {
                    JSONObject json = new JSONObject(result);
                    JSONArray rooms = json.optJSONArray("rooms");
                    java.util.Set<String> ownerIds = new java.util.HashSet<>();

                    // 2. 房主房间
                    if (rooms != null) {
                        for (int i = 0; i < rooms.length(); i++) {
                            JSONObject r = rooms.getJSONObject(i);
                            String rid = r.optString("roomId");
                            String role = r.optString("role", "member");
                            if ("owner".equals(role) || "super_admin".equals(role)) {
                                ownerIds.add(rid);
                                if (addedIds.contains(rid) || rid.equals(curRoomId)) continue;
                                addedIds.add(rid);
                                String name = r.optString("roomName", rid);
                                addSwitchRoomRow(panel, name, rid, "◆");
                            }
                        }
                    }

                    // 3. 其他收藏房间
                    for (int i = 0; i < favs.length(); i++) {
                        JSONObject f = favs.optJSONObject(i);
                        String fid = f.optString("roomId");
                        if (addedIds.contains(fid) || fid.equals(curRoomId) || ownerIds.contains(fid)) continue;
                        addedIds.add(fid);
                        addSwitchRoomRow(panel, f.optString("roomName"), fid, "★");
                    }

                    if (panel.getChildCount() == 0) {
                        Toast.makeText(FloatingWindowService.this, c.getString(R.string.my_rooms_empty), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    panel.setVisibility(View.VISIBLE);
                    roomInfoBar.setVisibility(View.GONE);
                    updateRecyclerViewHeight();
                } catch (Exception e) {
                    Toast.makeText(FloatingWindowService.this, R.string.room_error, Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onError(String error) {
                Toast.makeText(FloatingWindowService.this, c.getString(R.string.my_rooms_empty), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void addSwitchRoomRow(LinearLayout panel, String name, String roomId, String icon) {
        Context c = getLocalizedContext();
        TextView tv = new TextView(c);
        String prefix = icon + " ";
        String fullText = prefix + name + " (" + roomId + ")";
        if ("●".equals(icon)) {
            android.text.SpannableString sp = new android.text.SpannableString(fullText);
            sp.setSpan(new android.text.style.ForegroundColorSpan(0xFFFF4444), 0, 2, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new android.text.style.ForegroundColorSpan(0xFFFFFFFF), 2, fullText.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tv.setText(sp);
        } else {
            tv.setText(fullText);
            tv.setTextColor(0xFFFFFFFF);
        }
        tv.setTextSize(13);
        tv.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tv.setPadding(8, 0, 8, 0);
        tv.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(32)));
        tv.setOnClickListener(v -> {
            String savedPwd = getSavedRoomPassword(roomId);
            dataManager.joinRoom(roomId, savedPwd, new DataManager.Callback<String>() {
                @Override public void onResult(String result) {
                    dataManager.setShowSharedData(true);
                    roomListPanel.setVisibility(View.GONE);
                    roomInfoBar.setVisibility(View.VISIBLE);
                    suppressNextNotify = true;
                    updateRoomInfoDisplay();
                    updateShareButtonText();
                    updateRoomButtonText();
                    updateModeIndicator();
                    refreshData();
                }
                @Override public void onError(String error) {
                    Toast.makeText(FloatingWindowService.this,
                        getLocalizedContext().getString(R.string.room_password_wrong), Toast.LENGTH_SHORT).show();
                }
            });
        });
        panel.addView(tv);
        View divider = new View(c);
        divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)));
        divider.setBackgroundColor(0x20FFFFFF);
        panel.addView(divider);
    }

    public void destroy() {
        if (windowManager != null && floatingView != null) {
            windowManager.removeView(floatingView);
        }
        if (windowManager != null && floatingView_minimized != null) {
            windowManager.removeView(floatingView_minimized);
        }
    }
}