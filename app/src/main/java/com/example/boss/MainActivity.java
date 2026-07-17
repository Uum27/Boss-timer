package com.example.boss;

import android.Manifest;
import android.app.Dialog;
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
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.LinearLayout;

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

import java.text.SimpleDateFormat;
import java.util.Date;
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }

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
        // dbHelper.clearAllBosses();

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ItemAdapter(this);
        recyclerView.setAdapter(adapter);

        // ★ 修改：使用显式匿名内部类代替 Lambda，避免编译歧义
        adapter.setOnButtonClickListener(new ItemAdapter.OnButtonClickListener() {
            @Override
            public void onButtonClick(int position, ItemAdapter.ButtonType buttonType) {
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
            }
        });

        // ★ 行点击监听（实现两个方法）
        adapter.setOnRowClickListener(new ItemAdapter.OnRowClickListener() {
            @Override
            public void onText1Click(int position) {
                showEditNameDialog(adapter, position);
            }
            @Override
            public void onText2Click(int position) {
                showEditTimeDialog(adapter, position);
            }

            @Override
            public void onText3Click(int position) {
                showEditRemainingDialog(adapter, position);
            }
        });

        addButton.setOnClickListener(v -> showInputDialog());

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

        findViewById(R.id.btn_language).setOnClickListener(v -> showLanguageSwitchDialog());
    }

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
                    recreate();
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

    // 添加对话框
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
                    if (notify == 0) notify = 300;

                    RowData data = new RowData();
                    data.text1 = name.isEmpty() ? getString(R.string.default_unknown) : name;
                    data.spawnTime = spawn;
                    data.extraInfo = extra.isEmpty() ? getString(R.string.default_none) : extra;
                    data.startTime = System.currentTimeMillis();
                    data.setSpawnTime(this);
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

    // 完整编辑对话框
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
        // 折叠功能（使用单个 TextView 切换文本）
        TextView toggleText = dialogView.findViewById(R.id.advanced_toggle);
        LinearLayout contentLayout = dialogView.findViewById(R.id.advanced_content);

// 初始状态为折叠
        contentLayout.setVisibility(View.GONE);
        toggleText.setText(getString(R.string.advanced_time_title) + " ▶");

        toggleText.setOnClickListener(v -> {
            if (contentLayout.getVisibility() == View.GONE) {
                contentLayout.setVisibility(View.VISIBLE);
                toggleText.setText(getString(R.string.advanced_time_title) + " ▼");
            } else {
                contentLayout.setVisibility(View.GONE);
                toggleText.setText(getString(R.string.advanced_time_title) + " ▶");
            }
        });
        dialog.show();
        // 优化窗口高度
        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(params);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    // ★ 修改名称和刷新周期（不重置开始时间，保留当前周期数值）
    private void showEditNameDialog(ItemAdapter adapter, int position) {
        RowData data = adapter.dataList.get(position);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_name, null);

        EditText nameInput = dialogView.findViewById(R.id.edit_name);
        EditText hourInput = dialogView.findViewById(R.id.edit_hour);
        EditText minuteInput = dialogView.findViewById(R.id.edit_minute);
        EditText secondInput = dialogView.findViewById(R.id.edit_second);

        // 预填当前名称
        nameInput.setText(data.text1);

        // ★ 预填当前周期（时/分/秒）
        long spawn = data.spawnTime;
        long hours = spawn / 3600;
        long minutes = (spawn % 3600) / 60;
        long seconds = spawn % 60;
        hourInput.setText(String.valueOf(hours));
        minuteInput.setText(String.valueOf(minutes));
        secondInput.setText(String.valueOf(seconds));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_title_edit_name)
                .setView(dialogView)
                .setPositiveButton(R.string.dialog_button_ok, (di, which) -> {
                    // 更新名称
                    String newName = nameInput.getText().toString().trim();
                    if (!newName.isEmpty()) {
                        data.text1 = newName;
                    }

                    // 解析新周期（仅时/分/秒）
                    long h = parseLongOrDefault(hourInput.getText().toString().trim(), 0);
                    long m = parseLongOrDefault(minuteInput.getText().toString().trim(), 0);
                    long s = parseLongOrDefault(secondInput.getText().toString().trim(), 0);
                    long newSpawn = h * 3600 + m * 60 + s;  // ★ 确保 newSpawn 定义在此

                    // 若输入了有效周期，则更新 spawnTime，保留 startTime 不变
                    if (newSpawn > 0) {
                        data.spawnTime = newSpawn;
                        data.isNotified = false; // 重置通知状态
                    }

                    // 重新计算显示
                    data.setSpawnTime(this);
                    long remaining = data.spawnTime - ((System.currentTimeMillis() - data.startTime) / 1000);
                    if (remaining < 0) remaining = 0;
                    if (remaining >= 3600) {
                        data.text3 = String.format(Locale.getDefault(), "%02d:%02d:%02d",
                                remaining / 3600, (remaining % 3600) / 60, remaining % 60);
                    } else {
                        data.text3 = String.format(Locale.getDefault(), "%02d:%02d",
                                remaining / 60, remaining % 60);
                    }

                    dbHelper.editBoss(data);
                    EventBus.getDefault().post(new UpdateFloatWindowEvent(EventTypes.EDIT_ITEM, data));
                    adapter.notifyDataSetChanged();
                    Toast.makeText(this, R.string.edit_name_success, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.dialog_button_cancel, null)
                .create();
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(params);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    // ★ 修改时间对话框（死亡时间 + 下一次刷新周期）
    private void showEditTimeDialog(ItemAdapter adapter, int position) {
        RowData data = adapter.dataList.get(position);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_time, null);
        TextView tvBossName = dialogView.findViewById(R.id.tv_boss_name);
        tvBossName.setText(getString(R.string.boss_name_prefix) + data.text1);

        EditText killedDay = dialogView.findViewById(R.id.edit_killed_day);
        EditText killedHour = dialogView.findViewById(R.id.edit_killed_hour);
        EditText killedMinute = dialogView.findViewById(R.id.edit_killed_minute);
        EditText killedSecond = dialogView.findViewById(R.id.edit_killed_second);
        EditText needDay = dialogView.findViewById(R.id.edit_need_day);      // 忽略，不校验
        EditText needHour = dialogView.findViewById(R.id.edit_need_hour);
        EditText needMinute = dialogView.findViewById(R.id.edit_need_minute);
        EditText needSecond = dialogView.findViewById(R.id.edit_need_second);

        // 左下角显示结束时间
        TextView tvEndTime = dialogView.findViewById(R.id.tv_start_time);
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd   HH:mm:ss", Locale.getDefault());
        long endTimeMillis = data.startTime + data.spawnTime * 1000;
        tvEndTime.setText(getString(R.string.end_time_label) + " " + sdf.format(new Date(endTimeMillis)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_title_edit_time)
                .setView(dialogView)
                .setPositiveButton(R.string.dialog_button_ok, (di, which) -> {
                    // 1. 读取输入
                    String killedDayText = killedDay.getText().toString().trim();
                    String killedHourText = killedHour.getText().toString().trim();
                    String killedMinuteText = killedMinute.getText().toString().trim();
                    String killedSecondText = killedSecond.getText().toString().trim();
                    String spawnHourText = needHour.getText().toString().trim();
                    String spawnMinuteText = needMinute.getText().toString().trim();
                    String spawnSecondText = needSecond.getText().toString().trim();

                    boolean hasKilled = !killedDayText.isEmpty() || !killedHourText.isEmpty() ||
                            !killedMinuteText.isEmpty() || !killedSecondText.isEmpty();
                    boolean hasSpawn = !spawnHourText.isEmpty() || !spawnMinuteText.isEmpty() || !spawnSecondText.isEmpty();

                    if (!hasKilled && !hasSpawn) {
                        Toast.makeText(this, R.string.edit_time_no_change, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // 2. 校验范围（只校验有输入的字段）
                    // 2.1 校验“上一只死亡时间”
                    if (hasKilled) {
                        long d = parseLongOrDefault(killedDayText, 0);
                        long h = parseLongOrDefault(killedHourText, 0);
                        long m = parseLongOrDefault(killedMinuteText, 0);
                        long s = parseLongOrDefault(killedSecondText, 0);
                        if (!killedDayText.isEmpty() && d > 366) {
                            Toast.makeText(this, R.string.edit_time_day_too_large, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (!killedHourText.isEmpty() && h > 24) {
                            Toast.makeText(this, R.string.edit_time_hour_too_large, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (!killedMinuteText.isEmpty() && m > 60) {
                            Toast.makeText(this, R.string.edit_time_minute_too_large, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (!killedSecondText.isEmpty() && s > 60) {
                            Toast.makeText(this, R.string.edit_time_second_too_large, Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }

                    // 2.2 校验“下一只刷新时间”
                    if (hasSpawn) {
                        long h = parseLongOrDefault(spawnHourText, 0);
                        long m = parseLongOrDefault(spawnMinuteText, 0);
                        long s = parseLongOrDefault(spawnSecondText, 0);
                        if (!spawnHourText.isEmpty() && h > 24) {
                            Toast.makeText(this, R.string.edit_time_hour_too_large, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (!spawnMinuteText.isEmpty() && m > 60) {
                            Toast.makeText(this, R.string.edit_time_minute_too_large, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (!spawnSecondText.isEmpty() && s > 60) {
                            Toast.makeText(this, R.string.edit_time_second_too_large, Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }

                    // 3. 应用修改
                    if (hasKilled) {
                        // ★ 优先处理“上一只死亡时间”
                        Calendar killedCalendar = Calendar.getInstance();
                        if (!killedDayText.isEmpty()) killedCalendar.add(Calendar.DAY_OF_MONTH, -Integer.parseInt(killedDayText));
                        if (!killedHourText.isEmpty()) killedCalendar.set(Calendar.HOUR_OF_DAY, Integer.parseInt(killedHourText));
                        if (!killedMinuteText.isEmpty()) killedCalendar.set(Calendar.MINUTE, Integer.parseInt(killedMinuteText));
                        if (!killedSecondText.isEmpty()) killedCalendar.set(Calendar.SECOND, Integer.parseInt(killedSecondText));
                        data.startTime = killedCalendar.getTimeInMillis();
                    } else if (hasSpawn) {
                        // 仅当未填写死亡时间时，才处理“下一只刷新时间”
                        Calendar spawnCalendar = Calendar.getInstance();
                        if (!spawnHourText.isEmpty()) spawnCalendar.set(Calendar.HOUR_OF_DAY, Integer.parseInt(spawnHourText));
                        if (!spawnMinuteText.isEmpty()) spawnCalendar.set(Calendar.MINUTE, Integer.parseInt(spawnMinuteText));
                        if (!spawnSecondText.isEmpty()) spawnCalendar.set(Calendar.SECOND, Integer.parseInt(spawnSecondText));
                        data.startTime = (spawnCalendar.getTimeInMillis() / 1000 - data.spawnTime) * 1000;
                    }

                    // 4. 禁用自动重置，防止剩余时间为负时自动重置覆盖“已刷新”
                    data.isNotified = false;
                    data.autoReset = false;

                    data.setSpawnTime(this);
                    long remaining = data.spawnTime - ((System.currentTimeMillis() - data.startTime) / 1000);
                    if (remaining < 0) remaining = 0;
                    if (remaining >= 3600) {
                        data.text3 = String.format(Locale.getDefault(), "%02d:%02d:%02d",
                                remaining / 3600, (remaining % 3600) / 60, remaining % 60);
                    } else {
                        data.text3 = String.format(Locale.getDefault(), "%02d:%02d",
                                remaining / 60, remaining % 60);
                    }

                    dbHelper.editBoss(data);
                    EventBus.getDefault().post(new UpdateFloatWindowEvent(EventTypes.EDIT_ITEM, data));
                    adapter.notifyDataSetChanged();
                    Toast.makeText(this, R.string.edit_time_success, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.dialog_button_cancel, null)
                .create();
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(params);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    // 辅助方法
    private long parseLongOrDefault(String str, long defaultValue) {
        if (str == null || str.isEmpty()) return defaultValue;
        try {
            return Long.parseLong(str);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ★ 修改剩余时间对话框
    private void showEditRemainingDialog(ItemAdapter adapter, int position) {
        RowData data = adapter.dataList.get(position);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_remaining, null);
        TextView tvBossName = dialogView.findViewById(R.id.tv_boss_name);
        tvBossName.setText(getString(R.string.boss_name_prefix) + data.text1);

        EditText hourInput = dialogView.findViewById(R.id.edit_spawn_hour);
        EditText minuteInput = dialogView.findViewById(R.id.edit_spawn_minute);
        EditText secondInput = dialogView.findViewById(R.id.edit_spawn_second);

        long remaining = data.spawnTime - ((System.currentTimeMillis() - data.startTime) / 1000);
        if (remaining < 0) remaining = 0;
        hourInput.setText(String.valueOf(remaining / 3600));
        minuteInput.setText(String.valueOf((remaining % 3600) / 60));
        secondInput.setText(String.valueOf(remaining % 60));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_title_edit_remaining)
                .setView(dialogView)
                .setPositiveButton(R.string.dialog_button_ok, (di, which) -> {
                    String hourText = hourInput.getText().toString().trim();
                    String minuteText = minuteInput.getText().toString().trim();
                    String secondText = secondInput.getText().toString().trim();

                    long newRemaining = 0;
                    if (!hourText.isEmpty()) newRemaining += Long.parseLong(hourText) * 3600;
                    if (!minuteText.isEmpty()) newRemaining += Long.parseLong(minuteText) * 60;
                    if (!secondText.isEmpty()) newRemaining += Long.parseLong(secondText);

                    if (newRemaining > 0) {
                        data.startTime = System.currentTimeMillis() + newRemaining * 1000 - data.spawnTime * 1000;
                        data.isNotified = false;
                        if (newRemaining >= 3600) {
                            data.text3 = String.format(Locale.getDefault(), "%02d:%02d:%02d",
                                    newRemaining / 3600, (newRemaining % 3600) / 60, newRemaining % 60);
                        } else {
                            data.text3 = String.format(Locale.getDefault(), "%02d:%02d",
                                    newRemaining / 60, newRemaining % 60);
                        }
                        data.setSpawnTime(this);
                        dbHelper.editBoss(data);
                        EventBus.getDefault().post(new UpdateFloatWindowEvent(EventTypes.EDIT_ITEM, data));
                        adapter.notifyDataSetChanged();
                    } else {
                        Toast.makeText(this, R.string.edit_remaining_invalid, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.dialog_button_cancel, null)
                .create();
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(params);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    // 作者声明对话框（原样保留）
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

    // 数据库查看（原样保留）
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

    // 打赏对话框（原样保留）
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