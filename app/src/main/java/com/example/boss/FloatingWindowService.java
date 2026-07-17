package com.example.boss;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
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
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

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

    private static final int MAX_ITEMS = 8;
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
    private static final long DOUBLE_CLICK_TIME_DELTA = 300; // 双击间隔（毫秒）

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
        Log.d("FloatingWindow", "onLanguageChanged called");
        updateFloatingWindowTexts();
        recreateAdapter();
    }

    private void updateFloatingWindowTexts() {
        if (floatingView == null) {
            Log.e("FloatingWindow", "floatingView is null");
            return;
        }
        Context localizedContext = getLocalizedContext();
        if (tvTitleName == null) {
            tvTitleName = floatingView.findViewById(R.id.tv_title_name);
            tvTitleRefresh = floatingView.findViewById(R.id.tv_title_refresh);
            tvTitleRemaining = floatingView.findViewById(R.id.tv_title_remaining);
            tvTitleReset = floatingView.findViewById(R.id.tv_title_reset);
        }
        if (tvTitleName != null) {
            tvTitleName.setText(localizedContext.getString(R.string.float_title_name));
        }
        if (tvTitleRefresh != null) {
            tvTitleRefresh.setText(localizedContext.getString(R.string.float_title_refresh));
        }
        if (tvTitleRemaining != null) {
            tvTitleRemaining.setText(localizedContext.getString(R.string.float_title_remaining));
        }
        if (tvTitleReset != null) {
            tvTitleReset.setText(localizedContext.getString(R.string.float_title_reset));
        }

        Button minimizeBtn = floatingView.findViewById(R.id.btn_minimize);
        if (minimizeBtn != null) {
            minimizeBtn.setText(localizedContext.getString(R.string.float_button_minimize));
        }
        Log.d("FloatingWindow", "Title texts updated");
    }

    private void refreshData() {
        if (dbHelper != null) {
            List<RowData> newData = dbHelper.getAllBosses();
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
        adapter.updateData(dbHelper.getAllBosses());
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

        minimizeBtn.setOnClickListener(v -> toggleMinimize());

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

    public void destroy() {
        if (windowManager != null && floatingView != null) {
            windowManager.removeView(floatingView);
        }
        if (windowManager != null && floatingView_minimized != null) {
            windowManager.removeView(floatingView_minimized);
        }
    }
}