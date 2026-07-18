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
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import org.json.JSONArray;
import org.json.JSONObject;

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
    private DataManager dataManager;
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
    private Button roomButton;
    private Button leaveRoomButton;
    private TextView sharedHeader;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase, LocaleHelper.getLanguage(newBase)));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        EventBus.getDefault().register(this);
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
        dataManager = DataManager.getInstance(this);
        dataManager.updateContext(this);
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

        roomButton = findViewById(R.id.btn_room);
        roomButton.setOnClickListener(v -> {
            if (!dataManager.isSharedMode()) {
                showRoomDialog();
            } else if (dataManager.isShowingSharedData()) {
                dataManager.setShowSharedData(false);
                updateRoomStatusDisplay();
                adapter.updateData(dataManager.getAllBosses());
            } else {
                dataManager.setShowSharedData(true);
                updateRoomStatusDisplay();
                adapter.updateData(dataManager.getAllBosses());
            }
        });
        leaveRoomButton = findViewById(R.id.btn_leave_room);
        leaveRoomButton.setOnClickListener(v -> {
            dataManager.leaveRoom();
            updateRoomStatusDisplay();
            adapter.updateData(dataManager.getAllBosses());
            Toast.makeText(this, R.string.room_left, Toast.LENGTH_SHORT).show();
        });
        Button myRoomsBtn = findViewById(R.id.btn_my_rooms);
        myRoomsBtn.setOnClickListener(v -> showMyRoomsDialog());
        sharedHeader = findViewById(R.id.shared_mode_header);
        updateRoomStatusDisplay();
    }

    private void showRoomError(String error) {
        if (error.contains("wrong password")) {
            Toast.makeText(this, R.string.room_password_wrong, Toast.LENGTH_SHORT).show();
        } else if (error.contains("room not found") || error.contains("not found")) {
            Toast.makeText(this, R.string.room_not_found, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, getString(R.string.room_error, error), Toast.LENGTH_LONG).show();
        }
    }

    private void showMyRoomsDialog() {
        dataManager.fetchMyRooms(new DataManager.Callback<String>() {
            @Override public void onResult(String result) {
                try {
                    JSONObject json = new JSONObject(result);
                    JSONArray rooms = json.optJSONArray("rooms");
                    if (rooms == null || rooms.length() == 0) {
                        Toast.makeText(MainActivity.this, R.string.my_rooms_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    LinearLayout listLayout = new LinearLayout(MainActivity.this);
                    listLayout.setOrientation(LinearLayout.VERTICAL);
                    listLayout.setPadding(0, 8, 0, 8);

                    for (int i = 0; i < rooms.length(); i++) {
                        JSONObject r = rooms.getJSONObject(i);
                        String pwd = r.optBoolean("hasPassword", false) ? getString(R.string.room_has_password) : "";

                        LinearLayout row = new LinearLayout(MainActivity.this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setPadding(12, 10, 12, 10);
                        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

                        TextView nameTv = new TextView(MainActivity.this);
                        nameTv.setText(r.optString("roomName") + " (" + r.optString("roomId") + ")" + pwd);
                        nameTv.setTextSize(15);
                        nameTv.setTextColor(0xFF333333);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
                        nameTv.setLayoutParams(lp);

                        int idx = i;
                        nameTv.setOnClickListener(v -> {
                            try {
                                JSONObject sel = rooms.getJSONObject(idx);
                                showManageRoomDialog(sel);
                            } catch (Exception ignored) {}
                        });
                        row.addView(nameTv);

                        Button joinBtn = new Button(MainActivity.this);
                        joinBtn.setText(R.string.room_join);
                        joinBtn.setTextSize(12);
                        joinBtn.setMinWidth(0);
                        joinBtn.setPadding(12, 4, 12, 4);
                        joinBtn.setOnClickListener(v -> {
                            try {
                                JSONObject sel = rooms.getJSONObject(idx);
                                String rid = sel.optString("roomId");
                                dataManager.joinRoom(rid, "", new DataManager.Callback<String>() {
                                    @Override public void onResult(String result) {
                                        updateRoomStatusDisplay();
                                        adapter.updateData(dataManager.getAllBosses());
                                    }
                                    @Override public void onError(String error) {
                                        showRoomError(error);
                                    }
                                });
                            } catch (Exception ignored) {}
                        });
                        row.addView(joinBtn);

                        listLayout.addView(row);

                        if (i < rooms.length() - 1) {
                            View divider = new View(MainActivity.this);
                            divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
                            divider.setBackgroundColor(0xFFD0D0D0);
                            listLayout.addView(divider);
                        }
                    }

                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle(R.string.my_rooms_title)
                            .setView(listLayout)
                            .show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, R.string.room_error, Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onError(String error) {
                Toast.makeText(MainActivity.this, getString(R.string.room_error, error), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showManageRoomDialog(JSONObject room) {
        String roomId = room.optString("roomId");
        String roomName = room.optString("roomName");
        boolean hasPwd = room.optBoolean("hasPassword", false);

        View v = LayoutInflater.from(this).inflate(R.layout.dialog_manage_room, null);
        EditText nameInput = v.findViewById(R.id.manage_room_name);
        EditText pwdInput = v.findViewById(R.id.manage_room_password);
        Button delBtn = v.findViewById(R.id.manage_room_delete);
        Button saveBtn = v.findViewById(R.id.manage_room_save);
        Button membersBtn = v.findViewById(R.id.manage_room_members);

        nameInput.setText(roomName);
        pwdInput.setHint(hasPwd ? R.string.room_pwd_keep : R.string.room_pwd_set);
        pwdInput.setText("");

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.manage_room_title, roomId))
                .setView(v)
                .create();

        saveBtn.setOnClickListener(w -> {
            String newName = nameInput.getText().toString().trim();
            String newPwd = pwdInput.getText().toString().trim();
            if (newName.isEmpty()) newName = null;
            if (newPwd.isEmpty()) newPwd = null;
            dataManager.updateRoomInfo(roomId, newName, newPwd, new DataManager.Callback<Boolean>() {
                @Override public void onResult(Boolean ok) {
                    Toast.makeText(MainActivity.this, R.string.edit_time_success, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                }
                @Override public void onError(String err) {
                    Toast.makeText(MainActivity.this, getString(R.string.room_error, err), Toast.LENGTH_SHORT).show();
                }
            });
        });

        delBtn.setOnClickListener(w -> {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.manage_room_delete_confirm)
                    .setMessage(getString(R.string.manage_room_delete_msg, roomId))
                    .setPositiveButton(R.string.dialog_button_ok, (dd, ww) -> {
                        dataManager.deleteMyRoom(roomId, new DataManager.Callback<Boolean>() {
                            @Override public void onResult(Boolean ok) {
                                Toast.makeText(MainActivity.this, R.string.room_left, Toast.LENGTH_SHORT).show();
                                updateRoomStatusDisplay();
                                adapter.updateData(dataManager.getAllBosses());
                                dialog.dismiss();
                            }
                            @Override public void onError(String err) {
                                Toast.makeText(MainActivity.this, getString(R.string.room_error, err), Toast.LENGTH_SHORT).show();
                            }
                        });
                    })
                    .setNegativeButton(R.string.dialog_button_cancel, null)
                    .show();
        });

        membersBtn.setOnClickListener(w -> {
            dataManager.fetchRoomMembers(roomId, new DataManager.Callback<String>() {
                @Override public void onResult(String result) {
                    try {
                        JSONObject json = new JSONObject(result);
                        JSONArray members = json.optJSONArray("members");
                        if (members == null || members.length() == 0) return;
                        String[] items = new String[members.length()];
                        for (int i = 0; i < members.length(); i++) {
                            JSONObject m = members.getJSONObject(i);
                            String role = m.optString("role");
                            String roleDisplay;
                            switch (role) {
                                case "owner": roleDisplay = getString(R.string.role_owner); break;
                                case "admin": roleDisplay = getString(R.string.role_admin); break;
                                default: roleDisplay = getString(R.string.role_member); break;
                            }
                            items[i] = m.optString("name") + " - " + roleDisplay;
                        }
                        final JSONArray mems = members;
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle(R.string.manage_room_members_title)
                                .setItems(items, (dd, idx) -> {
                                    try {
                                        JSONObject mem = mems.getJSONObject(idx);
                                        showEditMemberDialog(roomId, mem);
                                    } catch (Exception ignored) {}
                                })
                                .show();
                    } catch (Exception ignored) {}
                }
                @Override public void onError(String error) {}
            });
        });

        dialog.show();
    }

    private void showEditMemberDialog(String roomId, JSONObject member) {
        String targetUserId = member.optString("userId");
        String name = member.optString("name");
        String currentRole = member.optString("role");

        String[] roles = {getString(R.string.role_owner), getString(R.string.role_admin), getString(R.string.role_member)};
        int sel = "owner".equals(currentRole) ? 0 : "admin".equals(currentRole) ? 1 : 2;

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.edit_member_title, name))
                .setSingleChoiceItems(roles, sel, (d, which) -> {
                    String[] enRoles = {"owner", "admin", "member"};
                    String newRole = enRoles[which];
                    dataManager.updateMemberRole(roomId, targetUserId, newRole, null, new DataManager.Callback<Boolean>() {
                        @Override public void onResult(Boolean ok) {
                            Toast.makeText(MainActivity.this, R.string.edit_time_success, Toast.LENGTH_SHORT).show();
                            d.dismiss();
                        }
                        @Override public void onError(String err) {
                            Toast.makeText(MainActivity.this, getString(R.string.room_error, err), Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .show();
    }

    private String getRoleDisplay(String role) {
        switch (role) {
            case "owner": return getString(R.string.role_owner);
            case "admin": return getString(R.string.role_admin);
            default: return getString(R.string.role_member);
        }
    }

    private void updateRoomStatusDisplay() {
        if (dataManager.isSharedMode()) {
            leaveRoomButton.setVisibility(View.VISIBLE);
            if (dataManager.isShowingSharedData()) {
                roomButton.setText(R.string.float_button_local);
                sharedHeader.setText(getString(R.string.shared_header, dataManager.getCurrentRoomId(), getRoleDisplay(dataManager.getMyRole())));
                sharedHeader.setVisibility(View.VISIBLE);
                sharedHeader.setBackgroundColor(0xFF3F51B5);
                sharedHeader.setTextColor(0xFFFFFFFF);
            } else {
                roomButton.setText(R.string.room_button);
                sharedHeader.setText(R.string.mode_local);
                sharedHeader.setVisibility(View.VISIBLE);
                sharedHeader.setBackgroundColor(0xFFF0F0F0);
                sharedHeader.setTextColor(0xFF333333);
            }
        } else {
            roomButton.setText(R.string.room_button);
            leaveRoomButton.setVisibility(View.GONE);
            sharedHeader.setText(R.string.mode_local);
            sharedHeader.setVisibility(View.VISIBLE);
            sharedHeader.setBackgroundColor(0xFFF0F0F0);
            sharedHeader.setTextColor(0xFF333333);
        }
    }

    private void showRoomDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_room, null);
        EditText roomIdInput = dialogView.findViewById(R.id.room_id_input);
        EditText passwordInput = dialogView.findViewById(R.id.room_password_input);
        EditText roomNameInput = dialogView.findViewById(R.id.room_name_input);
        EditText createUserNameInput = dialogView.findViewById(R.id.create_user_name);
        Button createBtn = dialogView.findViewById(R.id.btn_create_room);
        Button joinBtn = dialogView.findViewById(R.id.btn_join_room);

        if (dataManager.isSharedMode()) {
            roomIdInput.setText(dataManager.getCurrentRoomId());
            roomIdInput.setEnabled(false);
            passwordInput.setVisibility(View.GONE);
            roomNameInput.setVisibility(View.GONE);
            createUserNameInput.setVisibility(View.GONE);
            createBtn.setVisibility(View.GONE);
            joinBtn.setText(R.string.room_leave);
        } else {
            passwordInput.setHint(R.string.room_password_hint);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.room_dialog_title)
                .setView(dialogView)
                .create();

        createBtn.setOnClickListener(v -> {
            String roomName = roomNameInput.getText().toString().trim();
            String password = passwordInput.getText().toString().trim();
            String userName = createUserNameInput.getText().toString().trim();
            if (roomName.isEmpty()) {
                Toast.makeText(this, R.string.room_name_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!userName.isEmpty()) {
                dataManager.setUserName(userName);
            }
            dialog.dismiss();
            dataManager.createRoom(roomName, password, new DataManager.Callback<String>() {
                @Override
                public void onResult(String result) {
                    updateRoomStatusDisplay();
                    adapter.updateData(dataManager.getAllBosses());
                }
                @Override
                public void onError(String error) {
                    Toast.makeText(MainActivity.this, getString(R.string.room_error, error), Toast.LENGTH_LONG).show();
                }
            });
        });

        joinBtn.setOnClickListener(v -> {
            if (dataManager.isSharedMode()) {
                dialog.dismiss();
                dataManager.leaveRoom();
                updateRoomStatusDisplay();
                adapter.updateData(dataManager.getAllBosses());
                Toast.makeText(this, R.string.room_left, Toast.LENGTH_SHORT).show();
                return;
            }
            String roomId = roomIdInput.getText().toString().trim();
            String password = passwordInput.getText().toString().trim();
            if (roomId.isEmpty()) {
                Toast.makeText(this, R.string.room_id_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            dataManager.joinRoom(roomId, password, new DataManager.Callback<String>() {
                @Override
                public void onResult(String result) {
                    updateRoomStatusDisplay();
                    adapter.updateData(dataManager.getAllBosses());
                }
                @Override
                public void onError(String error) {
                    showRoomError(error);
                }
            });
        });

        dialog.show();
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
        TextView roomInfoView = dialogView.findViewById(R.id.dialog_room_info);
        if (dataManager.isShowingSharedData()) {
            roomInfoView.setText(getString(R.string.shared_header, dataManager.getCurrentRoomId(), getRoleDisplay(dataManager.getMyRole())));
            roomInfoView.setTextColor(0xFF3F51B5);
        } else {
            roomInfoView.setText(R.string.mode_local);
            roomInfoView.setTextColor(0xFF999999);
        }
        roomInfoView.setVisibility(View.VISIBLE);
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
                    if (dataManager.isShowingSharedData()) {
                        data.id = dataManager.insertBossShared(data);
                    } else {
                        data.id = dataManager.insertBoss(data);
                    }
                    EventBus.getDefault().post(new UpdateFloatWindowEvent(EventTypes.ADD_ITEM, data));
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
        TextView roomInfoView2 = dialogView.findViewById(R.id.dialog_room_info);
        if (dataManager.isShowingSharedData()) {
            roomInfoView2.setText(getString(R.string.shared_header, dataManager.getCurrentRoomId(), getRoleDisplay(dataManager.getMyRole())));
            roomInfoView2.setTextColor(0xFF3F51B5);
        } else {
            roomInfoView2.setText(R.string.mode_local);
            roomInfoView2.setTextColor(0xFF999999);
        }
        roomInfoView2.setVisibility(View.VISIBLE);
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
                    if (dataManager.isShowingSharedData()) {
                        dataManager.editBossShared(data);
                    } else {
                        dataManager.editBoss(data);
                    }
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

        TextView roomInfoV = dialogView.findViewById(R.id.dialog_room_info);
        if (roomInfoV != null) {
            if (dataManager.isShowingSharedData()) {
                roomInfoV.setText(getString(R.string.shared_header, dataManager.getCurrentRoomId(), getRoleDisplay(dataManager.getMyRole())));
                roomInfoV.setTextColor(0xFF3F51B5);
            } else {
                roomInfoV.setText(R.string.mode_local);
                roomInfoV.setTextColor(0xFF999999);
            }
            roomInfoV.setVisibility(View.VISIBLE);
        }

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

                    if (dataManager.isShowingSharedData()) {
                        dataManager.editBossShared(data);
                    } else {
                        dataManager.editBoss(data);
                    }
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

        EditText killedDay = dialogView.findViewById(R.id.edit_killed_day);
        EditText killedHour = dialogView.findViewById(R.id.edit_killed_hour);
        EditText killedMinute = dialogView.findViewById(R.id.edit_killed_minute);
        EditText killedSecond = dialogView.findViewById(R.id.edit_killed_second);
        EditText needDay = dialogView.findViewById(R.id.edit_need_day);
        EditText needHour = dialogView.findViewById(R.id.edit_need_hour);
        EditText needMinute = dialogView.findViewById(R.id.edit_need_minute);
        EditText needSecond = dialogView.findViewById(R.id.edit_need_second);

        TextView tvEndTime = dialogView.findViewById(R.id.tv_start_time);
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd   HH:mm:ss", Locale.getDefault());
        long endTimeMillis = data.startTime + data.spawnTime * 1000;
        tvEndTime.setText(getString(R.string.end_time_label) + " " + sdf.format(new Date(endTimeMillis)));

        TextView roomInfoEt = dialogView.findViewById(R.id.dialog_room_info);
        if (roomInfoEt != null) {
            if (dataManager.isShowingSharedData()) {
                roomInfoEt.setText(getString(R.string.shared_header, dataManager.getCurrentRoomId(), getRoleDisplay(dataManager.getMyRole())));
                roomInfoEt.setTextColor(0xFF3F51B5);
            } else {
                roomInfoEt.setText(R.string.mode_local);
                roomInfoEt.setTextColor(0xFF999999);
            }
            roomInfoEt.setVisibility(View.VISIBLE);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_title_edit_time)
                .setView(dialogView)
                .setPositiveButton(R.string.dialog_button_ok, (di, which) -> {
                    String killedDayText = killedDay.getText().toString().trim();
                    String killedHourText = killedHour.getText().toString().trim();
                    String killedMinuteText = killedMinute.getText().toString().trim();
                    String killedSecondText = killedSecond.getText().toString().trim();
                    String needDayText = needDay.getText().toString().trim();
                    String spawnHourText = needHour.getText().toString().trim();
                    String spawnMinuteText = needMinute.getText().toString().trim();
                    String spawnSecondText = needSecond.getText().toString().trim();

                    boolean hasKilled = !killedDayText.isEmpty() || !killedHourText.isEmpty() ||
                            !killedMinuteText.isEmpty() || !killedSecondText.isEmpty();
                    boolean hasSpawn = !needDayText.isEmpty() || !spawnHourText.isEmpty() ||
                            !spawnMinuteText.isEmpty() || !spawnSecondText.isEmpty();

                    if (!hasKilled && !hasSpawn) {
                        Toast.makeText(this, R.string.edit_time_no_change, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // 校验范围
                    if (hasKilled) {
                        long d = parseLongOrDefault(killedDayText, 0);
                        long h = parseLongOrDefault(killedHourText, 0);
                        long m = parseLongOrDefault(killedMinuteText, 0);
                        long s = parseLongOrDefault(killedSecondText, 0);
                        if (!killedDayText.isEmpty() && d > 366) { Toast.makeText(this, R.string.edit_time_day_too_large, Toast.LENGTH_SHORT).show(); return; }
                        if (!killedHourText.isEmpty() && h > 24) { Toast.makeText(this, R.string.edit_time_hour_too_large, Toast.LENGTH_SHORT).show(); return; }
                        if (!killedMinuteText.isEmpty() && m > 60) { Toast.makeText(this, R.string.edit_time_minute_too_large, Toast.LENGTH_SHORT).show(); return; }
                        if (!killedSecondText.isEmpty() && s > 60) { Toast.makeText(this, R.string.edit_time_second_too_large, Toast.LENGTH_SHORT).show(); return; }
                    }
                    if (hasSpawn) {
                        long d = parseLongOrDefault(needDayText, 0);
                        long h = parseLongOrDefault(spawnHourText, 0);
                        long m = parseLongOrDefault(spawnMinuteText, 0);
                        long s = parseLongOrDefault(spawnSecondText, 0);
                        if (!needDayText.isEmpty() && d > 366) { Toast.makeText(this, R.string.edit_time_day_too_large, Toast.LENGTH_SHORT).show(); return; }
                        if (!spawnHourText.isEmpty() && h > 24) { Toast.makeText(this, R.string.edit_time_hour_too_large, Toast.LENGTH_SHORT).show(); return; }
                        if (!spawnMinuteText.isEmpty() && m > 60) { Toast.makeText(this, R.string.edit_time_minute_too_large, Toast.LENGTH_SHORT).show(); return; }
                        if (!spawnSecondText.isEmpty() && s > 60) { Toast.makeText(this, R.string.edit_time_second_too_large, Toast.LENGTH_SHORT).show(); return; }
                    }

                    // 优先死亡时间
                    if (hasKilled) {
                        Calendar killedCalendar = Calendar.getInstance();
                        if (!killedDayText.isEmpty()) killedCalendar.add(Calendar.DAY_OF_MONTH, -Integer.parseInt(killedDayText));
                        if (!killedHourText.isEmpty()) killedCalendar.set(Calendar.HOUR_OF_DAY, Integer.parseInt(killedHourText));
                        if (!killedMinuteText.isEmpty()) killedCalendar.set(Calendar.MINUTE, Integer.parseInt(killedMinuteText));
                        if (!killedSecondText.isEmpty()) killedCalendar.set(Calendar.SECOND, Integer.parseInt(killedSecondText));
                        data.startTime = killedCalendar.getTimeInMillis();
                    } else if (hasSpawn) {
                        Calendar spawnCalendar = Calendar.getInstance();
                        if (!needDayText.isEmpty()) {
                            spawnCalendar.add(Calendar.DAY_OF_MONTH, Integer.parseInt(needDayText));
                        }
                        if (!spawnHourText.isEmpty()) spawnCalendar.set(Calendar.HOUR_OF_DAY, Integer.parseInt(spawnHourText));
                        if (!spawnMinuteText.isEmpty()) spawnCalendar.set(Calendar.MINUTE, Integer.parseInt(spawnMinuteText));
                        if (!spawnSecondText.isEmpty()) spawnCalendar.set(Calendar.SECOND, Integer.parseInt(spawnSecondText));
                        data.startTime = (spawnCalendar.getTimeInMillis() / 1000 - data.spawnTime) * 1000;
                    }

                    data.isNotified = false;
                    data.autoReset = false;
                    data.setSpawnTime(this);
                    long remaining = data.spawnTime - ((System.currentTimeMillis() - data.startTime) / 1000);
                    if (remaining < 0) remaining = 0;
                    data.text3 = (remaining >= 3600) ?
                            String.format(Locale.getDefault(), "%02d:%02d:%02d", remaining / 3600, (remaining % 3600) / 60, remaining % 60) :
                            String.format(Locale.getDefault(), "%02d:%02d", remaining / 60, remaining % 60);

                    if (dataManager.isShowingSharedData()) {
                        dataManager.editBossShared(data);
                    } else {
                        dataManager.editBoss(data);
                    }
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

        TextView roomInfoEr = dialogView.findViewById(R.id.dialog_room_info);
        if (roomInfoEr != null) {
            if (dataManager.isShowingSharedData()) {
                roomInfoEr.setText(getString(R.string.shared_header, dataManager.getCurrentRoomId(), getRoleDisplay(dataManager.getMyRole())));
                roomInfoEr.setTextColor(0xFF3F51B5);
            } else {
                roomInfoEr.setText(R.string.mode_local);
                roomInfoEr.setTextColor(0xFF999999);
            }
            roomInfoEr.setVisibility(View.VISIBLE);
        }

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
                    if (dataManager.isShowingSharedData()) {
                        dataManager.editBossShared(data);
                    } else {
                        dataManager.editBoss(data);
                    }
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
            adapter.updateData(dataManager.getAllBosses());
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

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDataChanged(DataChangedEvent event) {
        adapter.updateData(dataManager.getAllBosses());
        updateRoomStatusDisplay();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onFloatWindowEvent(UpdateFloatWindowEvent event) {
        if (event.type == EventTypes.EDIT_ITEM || event.type == EventTypes.RESET_ITEM) {
            updateRoomStatusDisplay();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
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