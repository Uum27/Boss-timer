package com.example.boss;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
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
    private Handler handler;
    private static final long REFRESH_INTERVAL = 1000;
    private boolean isMinimized = false;
    private boolean isTransitioning = false;
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;
    private WindowManager.LayoutParams params;
    private WindowManager.LayoutParams params_minimized;
    private TextView minimizedTimeText;
    private Handler timeHandler = new Handler(Looper.getMainLooper());
    private Runnable timeUpdateRunnable;
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
    private PowerManager.WakeLock wakeLock;

    // 标题栏控件缓存
    private TextView tvTitleName, tvTitleRefresh, tvTitleRemaining, tvTitleReset;

    // ★ 最小化锁定相关
    private boolean isMinimizedLocked = false;
    private long lastClickTime = 0;
    private static final long DOUBLE_CLICK_TIME_DELTA = 300;

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
    private RecyclerView floatingRecyclerView;

    @Override
    public void onCreate() {
        super.onCreate();
        if (instance != null) {
            return;
        }

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::WakeLockTag");
        wakeLock.acquire();

        instance = this;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        EventBus.getDefault().register(this);
        dbHelper = new DBHelper(this);
        dataManager = DataManager.getInstance(this);
        dataManager.updateContext(this);
        dataManager.setShowSharedData(false);

        initFloatingWindow();
        refreshData();
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
            if (!dataManager.isSharedMode()) {
                shareBtnView.setText(c.getString(R.string.float_button_share));
            } else if (dataManager.isShowingSharedData()) {
                shareBtnView.setText(c.getString(R.string.float_button_local));
            } else {
                shareBtnView.setText(c.getString(R.string.float_button_share));
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
        } else {
            if (roomBarLayout != null) roomBarLayout.setVisibility(View.GONE);
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
            dataManager.setShowSharedData(!dataManager.isShowingSharedData());
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
                if (error.contains("wrong password")) {
                    Toast.makeText(FloatingWindowService.this, R.string.room_password_wrong, Toast.LENGTH_SHORT).show();
                } else if (error.contains("room not found") || error.contains("not found")) {
                    Toast.makeText(FloatingWindowService.this, R.string.room_not_found, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(FloatingWindowService.this, getString(R.string.room_error, error), Toast.LENGTH_LONG).show();
                }
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
        } catch (Exception ignored) {}
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
                        Toast.makeText(FloatingWindowService.this, error, Toast.LENGTH_SHORT).show();
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
        } catch (Exception ignored) {}
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
                            myRoomIds.add(r.optString("roomId"));
                            addRoomRow(panel, r.optString("roomName"), r.optString("roomId"),
                                    r.optBoolean("hasPassword", false), "◆");
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
        } else {
            if (showingLogs) {
                showingLogs = false;
                roomListPanel.setVisibility(View.GONE);
                floatingRecyclerView.setVisibility(View.VISIBLE);
                return;
            }
            updateRoomInfoDisplay();
            roomInfoBar.setVisibility(View.VISIBLE);
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

        roomBar = floatingView.findViewById(R.id.tv_room_bar);
        roomBarLayout = floatingView.findViewById(R.id.room_bar_layout);
        roomBarText = floatingView.findViewById(R.id.tv_room_bar);
        favoriteArea = floatingView.findViewById(R.id.favorite_area);
        favoriteIcon = floatingView.findViewById(R.id.tv_favorite_icon);
        favoriteText = floatingView.findViewById(R.id.tv_favorite_text);
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
            int maxHeight = (int) (MAX_ITEMS * ITEM_HEIGHT_DP * density);
            int itemCount = adapter.getItemCount();
            int currentHeight = (int) (Math.min(itemCount, MAX_ITEMS) * ITEM_HEIGHT_DP * density);
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

                timeUpdateRunnable = new Runnable() {
                    @Override
                    public void run() {
                        updateTime();
                        timeHandler.postDelayed(this, 1000);
                    }
                };

                touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

                // ★ 移除 setOnClickListener，改用 onTouch 处理单击/双击
                // floatingView_minimized.setOnClickListener(v -> restoreFromMinimize());

                floatingView_minimized.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        floatingView_minimized.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        floatingViewWidth_minimized = floatingView_minimized.getWidth();
                        floatingViewHeight_minimized = floatingView_minimized.getHeight();

                        // ★ 设置触摸事件，支持双击锁定/解锁
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
                                            // 仅当未锁定时允许移动
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
                                        // 双击检测：两次点击间隔小于阈值且没有移动
                                        if (now - lastClickTime < DOUBLE_CLICK_TIME_DELTA && !isMoving) {
                                            // 双击：切换锁定状态
                                            isMinimizedLocked = !isMinimizedLocked;
                                            // 可添加提示（如 Toast），但为避免干扰，暂不添加
                                            lastClickTime = 0; // 重置
                                            return true;
                                        }
                                        lastClickTime = now;
                                        // 单击：如果未锁定且未移动，则展开
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
                startTimeUpdate();
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

    private void startTimeUpdate() {
        if (timeUpdateRunnable != null) {
            timeHandler.post(timeUpdateRunnable);
        }
    }

    private void stopTimeUpdate() {
        if (timeHandler != null && timeUpdateRunnable != null) {
            timeHandler.removeCallbacks(timeUpdateRunnable);
        }
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
        startTimeUpdate();
        isMinimized = true;
        isTransitioning = false;
    }

    private void restoreFromMinimize() {
        if (isTransitioning) return;
        isTransitioning = true;

        // ★ 展开时重置锁定状态
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
        stopTimeUpdate();
        isMinimized = false;
        isTransitioning = false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (instance == this) {
            instance = null;
        }

        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                wakeLock = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
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
                    for (int i = 0; i < Math.min(2, logs.length()); i++) {
                        JSONObject l = logs.getJSONObject(i); String a = l.optString("action");
                        String lb = "add".equals(a)?c.getString(R.string.log_add):"delete".equals(a)?c.getString(R.string.log_delete):"kick".equals(a)?c.getString(R.string.log_kick):"edit".equals(a)?c.getString(R.string.log_edit):"join".equals(a)?c.getString(R.string.log_join):c.getString(R.string.log_edit);
                        StringBuilder tx = new StringBuilder();
                        tx.append(sdf.format(new java.util.Date(l.optLong("time")))).append(" | ").append(l.optString("userName")).append(" | ").append(l.optString("target")).append("【").append(lb).append("】");
                        JSONArray chs = l.optJSONArray("changes");
                        if (chs != null) for (int ct=0; ct<chs.length(); ct++) {
                            JSONObject ch = chs.getJSONObject(ct); String f = ch.optString("field");
                            if ("startTime".equals(f)) {
                                tx.append("\n").append(c.getString(R.string.log_end_time)).append(":").append(formatFloatTime(ch.optLong("oldRefresh"))).append("→").append(formatFloatTime(ch.optLong("newRefresh")));
                                long sp = ch.optLong("spawn", 0);
                                if (sp > 0) tx.append("\n").append(c.getString(R.string.log_reset_time)).append(":").append(formatSeconds(sp));
                            } else if ("name".equals(f)) {
                                tx.append("\n").append(c.getString(R.string.log_name)).append(":").append(ch.optString("old")).append("→").append(ch.optString("new"));
                            } else if ("spawn".equals(f)) {
                                tx.append("\n").append(c.getString(R.string.log_reset_time)).append(":").append(formatSeconds(ch.optLong("old"))).append("→").append(formatSeconds(ch.optLong("new")));
                            } else if ("autoReset".equals(f)) {
                                tx.append("\n").append(c.getString(R.string.log_auto_reset)).append(":").append(ch.optBoolean("old")?c.getString(R.string.yes):c.getString(R.string.no)).append("→").append(ch.optBoolean("new")?c.getString(R.string.yes):c.getString(R.string.no));
                            }
                        }
                        if ("add".equals(a)) {
                            long rt = l.optLong("refreshTime", 0); if (rt > 0) tx.append("\n").append(c.getString(R.string.log_end_time)).append(":").append(formatFloatTime(rt));
                            long sp = l.optLong("spawn", 0); if (sp > 0) tx.append("\n").append(c.getString(R.string.log_reset_time)).append(":").append(formatSeconds(sp));
                        }
                        if ("delete".equals(a)) {
                            long rt = l.optLong("refreshTime", 0); if (rt > 0) tx.append("\n").append(c.getString(R.string.log_end_time)).append(":").append(formatFloatTime(rt));
                            long sp = l.optLong("spawn", 0); if (sp > 0) tx.append("\n").append(c.getString(R.string.log_reset_time)).append(":").append(formatSeconds(sp));
                        }
                        TextView tv = new TextView(c);
                        tv.setText(tx.toString()); tv.setTextSize(12); tv.setTextColor(0xFFFFFFFF); tv.setPadding(8,6,8,6);
                        panel.addView(tv);
                        View dv = new View(c);
                        dv.setLayoutParams(new LinearLayout.LayoutParams(-1,1)); dv.setBackgroundColor(0x40FFFFFF); panel.addView(dv);
                    }
                    roomInfoBar.setVisibility(View.GONE);
                    panel.setVisibility(View.VISIBLE);
                    showingLogs = true;
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

    public void destroy() {
        if (windowManager != null && floatingView != null) {
            windowManager.removeView(floatingView);
        }
        if (windowManager != null && floatingView_minimized != null) {
            windowManager.removeView(floatingView_minimized);
        }
    }
}