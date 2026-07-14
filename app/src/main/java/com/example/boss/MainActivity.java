package com.example.boss;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private Button addButton;
    private ItemAdapter adapter;
    private int rowCount = 1;
    private DBHelper dbHelper;
    private boolean isServiceRunning = false;
    public static int appUsableWidth;
    public static int appUsableHeight;

    private static final String PREFS_NAME = "AppPrefs";
    private static final String FIRST_LAUNCH = "FirstLaunch";
    private Handler searchHandler = new Handler();
    private Runnable searchRunnable;
    private EditText searchInput;
    private View mainLayout;
    private static final int REQUEST_CODE_BATTERY_OPTIMIZATION = 1001;
    private PowerManager.WakeLock wakeLock;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase, LocaleHelper.getLanguage(newBase)));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // 通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }

        // 悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, 0);
            }
        }
        checkAndRequestBatteryOptimization();

        searchInput = findViewById(R.id.search_input);
        mainLayout = findViewById(R.id.main);
        View mainView = mainLayout;

        mainView.post(() -> appUsableWidth = mainView.getWidth());

        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int statusBarHeight = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusBarHeight = getResources().getDimensionPixelSize(resourceId);
        }
        int navigationBarHeight = 0;
        resourceId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (resourceId > 0) {
            navigationBarHeight = getResources().getDimensionPixelSize(resourceId);
        }
        appUsableHeight = displayMetrics.heightPixels - statusBarHeight - navigationBarHeight;

        recyclerView = findViewById(R.id.recycler_view);
        addButton = findViewById(R.id.add_button);

        dbHelper = new DBHelper(this);
        // 如需清空数据，取消注释
        // dbHelper.clearAllBosses();

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ItemAdapter(this);
        recyclerView.setAdapter(adapter);

        adapter.setOnButtonClickListener((position, buttonType) -> {
            switch (buttonType) {
                case EDIT:
                    showEditDialog(adapter, position);
                    break;
                case RESET:
                    adapter.resetTime(position);
                    break;
                case DELETE:
                    adapter.deleteRow(position);
                    break;
            }
        });

        addButton.setOnClickListener(v -> showInputDialog());

        // 启动 TimerService
        Intent bossServiceIntent = new Intent(this, TimerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(bossServiceIntent);
        } else {
            startService(bossServiceIntent);
        }

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::WakeLockTag");
        wakeLock.acquire();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean isFirstLaunch = prefs.getBoolean(FIRST_LAUNCH, true);
        if (isFirstLaunch) {
            showAuthorDialog();
            prefs.edit().putBoolean(FIRST_LAUNCH, false).apply();
        }

        // 悬浮窗控制按钮
        Button toggleFloatingButton = findViewById(R.id.toggle_floating);
        toggleFloatingButton.setOnClickListener(v -> {
            if (!isServiceRunning) {
                Intent serviceIntent = new Intent(this, FloatingWindowService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                toggleFloatingButton.setText(R.string.button_toggle_float_off);
                adapter.isServerRunning = true;
                isServiceRunning = true;
            } else {
                stopService(new Intent(this, FloatingWindowService.class));
                toggleFloatingButton.setText(R.string.button_toggle_float);
                adapter.isServerRunning = false;
                isServiceRunning = false;
            }
        });

        // 默认启动悬浮窗
        Intent serviceIntent = new Intent(this, FloatingWindowService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        toggleFloatingButton.setText(R.string.button_toggle_float_off);
        adapter.isServerRunning = true;
        isServiceRunning = true;

        Button donateButton = findViewById(R.id.toggle_money);
        donateButton.setOnClickListener(v -> showDonateDialog());


        setupSearchInput();

        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String searchText = searchInput.getText().toString().trim();
                performSearch(searchText);
                searchInput.requestFocus();
                return true;
            }
            return false;
        });

        // ★ 语言切换按钮
        findViewById(R.id.btn_language).setOnClickListener(v -> showLanguageSwitchDialog());
    }

    // ★ 语言切换对话框
    private void showLanguageSwitchDialog() {
        String[] languages = {
                getString(R.string.language_chinese),
                getString(R.string.language_english),
                getString(R.string.language_korean)
        };
        String[] codes = {"zh", "en", "ko"};
        new AlertDialog.Builder(this)
                .setTitle(R.string.language_select_title)
                .setItems(languages, (dialog, which) -> {
                    String lang = codes[which];
                    LocaleHelper.saveLanguage(this, lang);
                    // 重新创建 Activity 应用新语言
                    recreate();
                    // 通知悬浮窗更新语言
                    EventBus.getDefault().post(new LanguageChangeEvent());
                })
                .show();
    }

    private void setupSearchInput() {
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                performSearch(s.toString().trim());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
        searchInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                performSearch(searchInput.getText().toString().trim());
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            if (searchInput != null && searchInput.hasFocus()) {
                Rect outRect = new Rect();
                searchInput.getGlobalVisibleRect(outRect);
                if (!outRect.contains((int) ev.getRawX(), (int) ev.getRawY())) {
                    searchInput.clearFocus();
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    // 添加新项目对话框
    private void showInputDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.input_information, null);
        EditText nameInput = dialogView.findViewById(R.id.input_name);
        EditText spawnHourInput = dialogView.findViewById(R.id.spawn_hour);
        EditText spawnMinuteInput = dialogView.findViewById(R.id.spawn_minute);
        EditText spawnSecondInput = dialogView.findViewById(R.id.spawn_second);
        EditText notifyHourInput = dialogView.findViewById(R.id.notify_hour);
        EditText notifyMinuteInput = dialogView.findViewById(R.id.notify_minute);
        EditText notifySecondInput = dialogView.findViewById(R.id.notify_second);
        EditText extraInput = dialogView.findViewById(R.id.input_extra);
        CheckBox autoReset = dialogView.findViewById(R.id.chk_auto_reset);
        CheckBox showInFloat = dialogView.findViewById(R.id.chk_show_in_float);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_title_add)
                .setView(dialogView)
                .setPositiveButton(R.string.dialog_button_ok, (dialogInterface, i) -> {
                    String name = nameInput.getText().toString().trim();
                    String hourText = spawnHourInput.getText().toString().trim();
                    String minuteText = spawnMinuteInput.getText().toString().trim();
                    String secondText = spawnSecondInput.getText().toString().trim();
                    String extra = extraInput.getText().toString().trim();
                    String notifyHourText = notifyHourInput.getText().toString().trim();
                    String notifyMinuteText = notifyMinuteInput.getText().toString().trim();
                    String notifySecondText = notifySecondInput.getText().toString().trim();
                    boolean autoResetChecked = autoReset.isChecked();
                    boolean showInFloatChecked = showInFloat.isChecked();

                    long spawn = 0;
                    if (!hourText.isEmpty()) spawn += Long.parseLong(hourText) * 3600;
                    if (!minuteText.isEmpty()) spawn += Long.parseLong(minuteText) * 60;
                    if (!secondText.isEmpty()) spawn += Long.parseLong(secondText);

                    long notify = 0;
                    if (!notifyHourText.isEmpty()) notify += Long.parseLong(notifyHourText) * 3600;
                    if (!notifyMinuteText.isEmpty()) notify += Long.parseLong(notifyMinuteText) * 60;
                    if (!notifySecondText.isEmpty()) notify += Long.parseLong(notifySecondText);
                    if (notify == 0) notify = 300; // 默认5分钟

                    RowData data = new RowData();
                    data.text1 = name.isEmpty() ? getString(R.string.default_unknown) : name;
                    data.spawnTime = spawn;
                    data.extraInfo = extra.isEmpty() ? getString(R.string.default_none) : extra;
                    data.startTime = System.currentTimeMillis();
                    data.setSpawnTime(this); // 使用当前语言
                    if (data.spawnTime / 3600 > 0) {
                        data.text3 = String.format(Locale.getDefault(), "%02d:%02d:%02d",
                                data.spawnTime / 3600,
                                (data.spawnTime % 3600) / 60,
                                data.spawnTime % 60);
                    } else {
                        data.text3 = String.format(Locale.getDefault(), "%02d:%02d",
                                (data.spawnTime % 3600) / 60,
                                data.spawnTime % 60);
                    }
                    data.needNotify = true;
                    data.notifyTime = notify;
                    data.autoReset = autoResetChecked;
                    data.showInFloat = showInFloatChecked;
                    data.id = dbHelper.insertBoss(data);
                    EventBus.getDefault().post(new UpdateFloatWindowEvent(EventTypes.ADD_ITEM, data));
                    adapter.addRow(data);
                    recyclerView.smoothScrollToPosition(0);
                    rowCount++;
                })
                .setNegativeButton(R.string.dialog_button_cancel, null)
                .create();
        dialog.show();
    }

    // 编辑对话框
    private void showEditDialog(ItemAdapter adapter, int position) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.edit_information, null);
        EditText nameInput = dialogView.findViewById(R.id.edit_name);
        EditText spawnHourInput = dialogView.findViewById(R.id.edit_spawn_hour);
        EditText spawnMinuteInput = dialogView.findViewById(R.id.edit_spawn_minute);
        EditText spawnSecondInput = dialogView.findViewById(R.id.edit_spawn_second);
        EditText notifyHourInput = dialogView.findViewById(R.id.edit_notify_hour);
        EditText notifyMinuteInput = dialogView.findViewById(R.id.edit_notify_minute);
        EditText notifySecondInput = dialogView.findViewById(R.id.edit_notify_second);
        EditText extraInput = dialogView.findViewById(R.id.edit_extra);
        TextView currentInfo = dialogView.findViewById(R.id.current_info);
        CheckBox autoReset = dialogView.findViewById(R.id.chk_auto_reset);
        CheckBox showInFloat = dialogView.findViewById(R.id.chk_show_in_float);
        EditText killedDayInput = dialogView.findViewById(R.id.edit_killed_day);
        EditText killedHourInput = dialogView.findViewById(R.id.edit_killed_hour);
        EditText killedMinuteInput = dialogView.findViewById(R.id.edit_killed_minute);
        EditText killedSecondInput = dialogView.findViewById(R.id.edit_killed_second);
        EditText needDayInput = dialogView.findViewById(R.id.edit_need_day);
        EditText needHourInput = dialogView.findViewById(R.id.edit_need_hour);
        EditText needMinuteInput = dialogView.findViewById(R.id.edit_need_minute);
        EditText needSecondInput = dialogView.findViewById(R.id.edit_need_second);

        currentInfo.setMovementMethod(new ScrollingMovementMethod());
        RowData data = adapter.dataList.get(position);
        autoReset.setChecked(data.autoReset);
        showInFloat.setChecked(data.showInFloat);

        // 使用资源格式化当前信息
        String info = String.format(Locale.getDefault(),
                getString(R.string.edit_current_info_format),
                data.text1,
                data.spawnTime / 3600,
                (data.spawnTime % 3600) / 60,
                data.spawnTime % 60,
                data.autoReset ? getString(R.string.yes) : getString(R.string.no),
                data.getStartTime(this),
                data.needNotify ? getString(R.string.yes) : getString(R.string.no),
                data.notifyTime / 3600,
                (data.notifyTime % 3600) / 60,
                data.notifyTime % 60,
                data.showInFloat ? getString(R.string.yes) : getString(R.string.no),
                data.extraInfo
        );
        currentInfo.setText(info);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_title_edit)
                .setView(dialogView)
                .setPositiveButton(R.string.dialog_button_ok, (dialogInterface, i) -> {
                    String name = nameInput.getText().toString().trim();
                    String hourText = spawnHourInput.getText().toString().trim();
                    String minuteText = spawnMinuteInput.getText().toString().trim();
                    String secondText = spawnSecondInput.getText().toString().trim();
                    String extra = extraInput.getText().toString().trim();
                    String notifyHourText = notifyHourInput.getText().toString().trim();
                    String notifyMinuteText = notifyMinuteInput.getText().toString().trim();
                    String notifySecondText = notifySecondInput.getText().toString().trim();
                    String killedDayText = killedDayInput.getText().toString().trim();
                    String killedHourText = killedHourInput.getText().toString().trim();
                    String killedMinuteText = killedMinuteInput.getText().toString().trim();
                    String killedSecondText = killedSecondInput.getText().toString().trim();
                    String spawnDayText = needDayInput.getText().toString().trim();
                    String spawnHourText = needHourInput.getText().toString().trim();
                    String spawnMinuteText = needMinuteInput.getText().toString().trim();
                    String spawnSecondText = needSecondInput.getText().toString().trim();
                    boolean autoResetChecked = autoReset.isChecked();

                    long killedTime = 0;
                    long spawn = 0;
                    boolean spawnTime = false;
                    if (!spawnDayText.isEmpty() || !spawnHourText.isEmpty() || !spawnMinuteText.isEmpty() || !spawnSecondText.isEmpty()) {
                        spawnTime = true;
                    }

                    if (!killedDayText.isEmpty()) killedTime += Long.parseLong(killedDayText) * 24 * 3600;
                    if (!killedHourText.isEmpty()) killedTime += Long.parseLong(killedHourText) * 3600;
                    if (!killedMinuteText.isEmpty()) killedTime += Long.parseLong(killedMinuteText) * 60;
                    if (!killedSecondText.isEmpty()) killedTime += Long.parseLong(killedSecondText);

                    if (!hourText.isEmpty()) spawn += Long.parseLong(hourText) * 3600;
                    if (!minuteText.isEmpty()) spawn += Long.parseLong(minuteText) * 60;
                    if (!secondText.isEmpty()) spawn += Long.parseLong(secondText);

                    long notify = 0;
                    if (!notifyHourText.isEmpty()) notify += Long.parseLong(notifyHourText) * 3600;
                    if (!notifyMinuteText.isEmpty()) notify += Long.parseLong(notifyMinuteText) * 60;
                    if (!notifySecondText.isEmpty()) notify += Long.parseLong(notifySecondText);

                    if (!name.isEmpty()) data.text1 = name;
                    if (!extra.isEmpty()) data.extraInfo = extra;

                    // 处理时间更新
                    if (spawnTime) {
                        Calendar spawnCalendar = Calendar.getInstance();
                        if (!spawnDayText.isEmpty()) spawnCalendar.add(Calendar.DAY_OF_MONTH, Integer.parseInt(spawnDayText));
                        if (!spawnHourText.isEmpty()) spawnCalendar.set(Calendar.HOUR_OF_DAY, Integer.parseInt(spawnHourText));
                        if (!spawnMinuteText.isEmpty()) spawnCalendar.set(Calendar.MINUTE, Integer.parseInt(spawnMinuteText));
                        if (!spawnSecondText.isEmpty()) spawnCalendar.set(Calendar.SECOND, Integer.parseInt(spawnSecondText));
                        data.startTime = (spawnCalendar.getTimeInMillis() / 1000 - data.spawnTime) * 1000;
                        data.setSpawnTime(this);
                        long spawnKilled = data.startTime / 1000 + data.spawnTime - System.currentTimeMillis() / 1000;
                        if (spawnKilled >= 3600) {
                            data.text3 = String.format(Locale.getDefault(), "%02d:%02d:%02d",
                                    spawnKilled / 3600, (spawnKilled % 3600) / 60, spawnKilled % 60);
                        } else {
                            data.text3 = String.format(Locale.getDefault(), "%02d:%02d",
                                    spawnKilled / 60, spawnKilled % 60);
                        }
                        data.isNotified = false;
                    } else if (killedTime != 0) {
                        Calendar killedCalendar = Calendar.getInstance();
                        if (!killedDayText.isEmpty()) killedCalendar.add(Calendar.DAY_OF_MONTH, -Integer.parseInt(killedDayText));
                        if (!killedHourText.isEmpty()) killedCalendar.set(Calendar.HOUR_OF_DAY, Integer.parseInt(killedHourText));
                        if (!killedMinuteText.isEmpty()) killedCalendar.set(Calendar.MINUTE, Integer.parseInt(killedMinuteText));
                        if (!killedSecondText.isEmpty()) killedCalendar.set(Calendar.SECOND, Integer.parseInt(killedSecondText));
                        data.startTime = killedCalendar.getTimeInMillis();
                        data.setSpawnTime(this);
                        long spawnKilled = data.startTime / 1000 + data.spawnTime - System.currentTimeMillis() / 1000;
                        if (spawnKilled >= 3600) {
                            data.text3 = String.format(Locale.getDefault(), "%02d:%02d:%02d",
                                    spawnKilled / 3600, (spawnKilled % 3600) / 60, spawnKilled % 60);
                        } else {
                            data.text3 = String.format(Locale.getDefault(), "%02d:%02d",
                                    spawnKilled / 60, spawnKilled % 60);
                        }
                        data.isNotified = false;
                    } else if (spawn != 0) {
                        data.startTime = System.currentTimeMillis() + spawn * 1000 - data.spawnTime * 1000;
                        data.setSpawnTime(this);
                        if (spawn >= 3600) {
                            data.text3 = String.format(Locale.getDefault(), "%02d:%02d:%02d",
                                    spawn / 3600, (spawn % 3600) / 60, spawn % 60);
                        } else {
                            data.text3 = String.format(Locale.getDefault(), "%02d:%02d",
                                    spawn / 60, spawn % 60);
                        }
                        data.isNotified = false;
                    }

                    if (notify != 0) {
                        data.notifyTime = notify;
                        data.isNotified = false;
                    }
                    data.autoReset = autoResetChecked;
                    data.showInFloat = showInFloat.isChecked();
                    dbHelper.editBoss(data);
                    EventBus.getDefault().post(new UpdateFloatWindowEvent(EventTypes.EDIT_ITEM, data));
                })
                .setNegativeButton(R.string.dialog_button_cancel, null)
                .create();
        dialog.show();
        // ★ 调整对话框窗口大小，使其高度为屏幕的 80%，并允许 ScrollView 滚动
        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9);
            // 关键：将高度设为屏幕高度的 80%，这样 ScrollView 就能在有限高度内滚动
            params.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.8);
            window.setAttributes(params);
            // 当软键盘弹出时自动调整布局，避免遮挡
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    // 作者声明对话框
    private void showAuthorDialog() {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_author);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.9),
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }
        Button btnOk = dialog.findViewById(R.id.btn_ok);
        btnOk.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    // 查看数据库对话框
    private void showDatabase() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.database, null);
        ListView databaseListView = dialogView.findViewById(R.id.listViewBosses);
        List<RowData> bossList = dbHelper.getDatabase();
        List<String> displayList = new ArrayList<>();
        for (RowData data : bossList) {
            displayList.add("ID: " + data.id + " - " + data.text1 + " - " +
                    data.spawnTime/3600 + ":" + data.spawnTime%3600/60 + ":" + data.spawnTime%60 + " - " +
                    data.startTime/1000/3600 + ":" + data.startTime/1000%3600/60 + ":" + data.startTime/1000%60 + " - " +
                    data.notifyTime/3600 + ":" + data.notifyTime%3600/60 + ":" + data.notifyTime%60);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, displayList);
        databaseListView.setAdapter(adapter);
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_title_database)
                .setView(dialogView)
                .setPositiveButton(R.string.dialog_button_ok, null)
                .setNegativeButton(R.string.dialog_button_cancel, null)
                .show();
    }

    // 打赏对话框
    private void showDonateDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_donate, null);
        Button closeButton = dialogView.findViewById(R.id.close_button);
        AlertDialog dialog = builder.setView(dialogView).create();
        closeButton.setOnClickListener(v -> dialog.dismiss());
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9);
            window.setAttributes(params);
        }
    }

    // 搜索功能
    private void performSearch(String searchText) {
        if (searchRunnable != null) {
            searchHandler.removeCallbacks(searchRunnable);
        }
        searchRunnable = () -> {
            adapter.filteredString = searchText.isEmpty() ? "" : searchText;
            adapter.updateData(dbHelper.getAllBosses());
        };
        searchHandler.postDelayed(searchRunnable, 300);
    }

    // 电池优化提醒
    private void checkAndRequestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.battery_opt_title)
                        .setMessage(R.string.battery_opt_message)
                        .setPositiveButton(R.string.battery_opt_positive, (dialog, which) -> {
                            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivityForResult(intent, REQUEST_CODE_BATTERY_OPTIMIZATION);
                        })
                        .setNegativeButton(R.string.battery_opt_negative, null)
                        .setCancelable(false)
                        .show();
            }
        }
    }

    // 通知权限引导（保留但未使用，可根据需要调用）
    private void openNotificationSettings() {
        try {
            Intent intent = new Intent();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
                intent.putExtra("app_package", getPackageName());
                intent.putExtra("app_uid", getApplicationInfo().uid);
            } else {
                intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.addCategory(Intent.CATEGORY_DEFAULT);
                intent.setData(Uri.parse("package:" + getPackageName()));
            }
            startActivity(intent);
            Toast.makeText(this, R.string.notification_settings_toast, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            e.printStackTrace();
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (adapter != null) {
            recyclerView.setAdapter(null);
        }
        stopService(new Intent(this, TimerService.class));
        if (isServiceRunning) {
            stopService(new Intent(this, FloatingWindowService.class));
            isServiceRunning = false;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
}