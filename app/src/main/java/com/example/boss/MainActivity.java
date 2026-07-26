package com.example.boss;

import android.Manifest;
import android.app.AlarmManager;
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
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.util.DisplayMetrics;
import android.util.Log;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;

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

    private static final String PREFS_AUTH = "boss_auth";
    private static final String KEY_AUTHED = "authed";
    private static final String PREFS_NAME = "AppPrefs";
    private static final String FIRST_LAUNCH = "FirstLaunch";
    private Handler searchHandler = new Handler();
    private Runnable searchRunnable;
    private EditText searchInput;
    private View mainLayout;
    private static final int REQUEST_CODE_BATTERY_OPTIMIZATION = 1001;
    private Button roomButton;
    private Button leaveRoomButton;
    private View sharedHeader;
    private TextView headerText;
    private TextView headerFavIcon;
    private TextView headerFavText;
    private TextView headerUserName;
    private TextView headerReset;
    private TextView headerDelete;

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
        checkAndRequestExactAlarmPermission();

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

        headerReset = findViewById(R.id.header_reset);
        headerReset.setOnClickListener(v -> {
            boolean show = !adapter.isShowResetButton();
            adapter.setShowResetButton(show);
            headerReset.setTextColor(show ? 0xFF2196F3 : 0xFF333333);
        });

        headerDelete = findViewById(R.id.header_delete);
        headerDelete.setOnClickListener(v -> {
            boolean show = !adapter.isShowDeleteButton();
            adapter.setShowDeleteButton(show);
            headerDelete.setTextColor(show ? 0xFFE57373 : 0xFF333333);
        });

        TextView headerEdit = findViewById(R.id.header_edit);
        headerEdit.setOnClickListener(v -> {
            if (!dataManager.isShowingSharedData()
                    || (dataManager.isOwner() || "super_admin".equals(dataManager.getMyRole()))) {
                showFloatBatchDialog();
            }
        });

        addButton.setOnClickListener(v -> showInputDialog());

        Intent bossServiceIntent = new Intent(this, TimerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(bossServiceIntent);
        } else {
            startService(bossServiceIntent);
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        showAuthorDialog();

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
        headerText = findViewById(R.id.header_text);
        headerFavIcon = findViewById(R.id.header_fav_icon);
        headerFavText = findViewById(R.id.header_fav_text);
        headerUserName = findViewById(R.id.header_user_name);
        headerUserName.setOnClickListener(v -> {
            EditText input = new EditText(this);
            input.setText(dataManager.getUserName());
            input.setHint(R.string.join_room_name_hint);
            new AlertDialog.Builder(this)
                .setTitle(R.string.change_name)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (!newName.isEmpty()) {
                        dataManager.setUserName(newName);
                        updateRoomStatusDisplay();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        });
        headerFavIcon.setOnClickListener(v -> toggleMainFav());
        headerFavText.setOnClickListener(v -> toggleMainFav());
        headerText.setOnClickListener(v -> {
            if (!dataManager.isShowingSharedData()
                    || (dataManager.isOwner() || "super_admin".equals(dataManager.getMyRole()))) {
                showHeaderPopupMenu(v);
            }
        });
        updateRoomStatusDisplay();
    }

    private void showMainPwdInput(String rid, String name, LinearLayout parent) {
        EditText pwdInput = new EditText(this);
        pwdInput.setHint(R.string.room_password_hint);
        pwdInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pwdInput.setPadding(16, 16, 16, 16);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.enter_new_password))
                .setView(pwdInput)
                .setPositiveButton(R.string.confirm_btn, (d, w) -> {
                    String newPwd = pwdInput.getText().toString().trim();
                    dataManager.joinRoom(rid, newPwd, new DataManager.Callback<String>() {
                        @Override public void onResult(String result) {
                            getSharedPreferences("boss_room_pwds", MODE_PRIVATE).edit().putString(rid, newPwd).apply();
                            updateRoomStatusDisplay();
                            adapter.updateData(dataManager.getAllBosses());
                        }
                        @Override public void onError(String error) {
                            Toast.makeText(MainActivity.this, R.string.room_password_wrong, Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .setNegativeButton(R.string.dialog_button_cancel, null)
                .create().show();
    }

    private boolean checkAuth() {
        if (getSharedPreferences(PREFS_AUTH, MODE_PRIVATE).getBoolean(KEY_AUTHED, false)) return true;
        EditText input = new EditText(this);
        input.setHint(R.string.auth_hint);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle(R.string.auth_title)
                .setView(input)
                .setPositiveButton(R.string.confirm_btn, (d, w) -> {
                    String code = input.getText().toString().trim();
                    dataManager.verifyAuth(code, new DataManager.Callback<Boolean>() {
                        @Override public void onResult(Boolean valid) {
                            if (valid) {
                                getSharedPreferences(PREFS_AUTH, MODE_PRIVATE).edit().putBoolean(KEY_AUTHED, true).apply();
                                Toast.makeText(MainActivity.this, R.string.auth_success, Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(MainActivity.this, R.string.auth_failed, Toast.LENGTH_SHORT).show();
                            }
                        }
                        @Override public void onError(String error) {
                            Toast.makeText(MainActivity.this, R.string.auth_failed, Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .setNegativeButton(R.string.dialog_button_cancel, null)
                .show();
        return false;
    }

    private boolean isRoomFav() {
        try {
            String favJson = getSharedPreferences("boss_fav_rooms", MODE_PRIVATE).getString("fav_ids", "[]");
            org.json.JSONArray favs = new org.json.JSONArray(favJson);
            for (int i = 0; i < favs.length(); i++) {
                if (dataManager.getCurrentRoomId().equals(favs.getJSONObject(i).optString("roomId"))) return true;
            }
        } catch (Exception e) {}
        return false;
    }

    private void toggleMainFav() {
        if (!dataManager.isSharedMode()) return;
        String rid = dataManager.getCurrentRoomId();
        String rname = dataManager.getCurrentRoomName();
        try {
            String favJson = getSharedPreferences("boss_fav_rooms", MODE_PRIVATE).getString("fav_ids", "[]");
            org.json.JSONArray favs = new org.json.JSONArray(favJson);
            org.json.JSONArray newFavs = new org.json.JSONArray();
            boolean removed = false;
            for (int i = 0; i < favs.length(); i++) {
                org.json.JSONObject f = favs.getJSONObject(i);
                if (rid.equals(f.optString("roomId"))) { removed = true; }
                else { newFavs.put(f); }
            }
            if (!removed) {
                org.json.JSONObject obj = new org.json.JSONObject();
                obj.put("roomId", rid);
                obj.put("roomName", rname != null ? rname : rid);
                newFavs.put(obj);
            }
            getSharedPreferences("boss_fav_rooms", MODE_PRIVATE).edit().putString("fav_ids", newFavs.toString()).apply();
            headerFavIcon.setText(removed ? "☆" : "★");
            headerFavText.setText(removed ? getString(R.string.favorite) : getString(R.string.favorite));
            EventBus.getDefault().post(new DataChangedEvent("fav"));
        } catch (Exception e) { Log.e("MainActivity", "toggleFav", e); }
    }

    private void showRoomError(String error) {
        String msg = dataManager.resolveErrorMessage(this, error);
        Toast.makeText(this, msg, error.contains("not found") ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
    }

    private void removeFavIfNeeded(String rid, String error) {
        if (!error.contains("room not found") && !error.contains("not found")) return;
        try {
            String favJson = getSharedPreferences("boss_fav_rooms", MODE_PRIVATE).getString("fav_ids", "[]");
            org.json.JSONArray favs = new org.json.JSONArray(favJson);
            org.json.JSONArray newFavs = new org.json.JSONArray();
            for (int i = 0; i < favs.length(); i++) {
                org.json.JSONObject f = favs.getJSONObject(i);
                if (!rid.equals(f.optString("roomId"))) newFavs.put(f);
            }
            getSharedPreferences("boss_fav_rooms", MODE_PRIVATE).edit().putString("fav_ids", newFavs.toString()).apply();
        } catch (Exception e) { Log.e("MainActivity", "toggleFav", e); }
    }

    private void addRoomListItem(LinearLayout parent, String name, String rid, String pwd, String icon, int idx, JSONArray rooms, String role) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(12, 10, 12, 10);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView tv = new TextView(this);
        tv.setText(icon + " " + name + " (" + rid + ")" + pwd);
        tv.setTextSize(15);
        tv.setTextColor(0xFF333333);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        tv.setLayoutParams(lp);
        row.addView(tv);

        boolean canManage = "owner".equals(role) || "super_admin".equals(role) || "admin".equals(role);
        if (canManage) {
            Button mgrBtn = new Button(this);
            mgrBtn.setText(R.string.manage_room_title_simple);
            if (idx >= 0 && rooms != null) {
                mgrBtn.setOnClickListener(v -> {
                    try { showManageRoomDialog(rooms.getJSONObject(idx), role); } catch (Exception ignored) {}
                });
            } else {
                mgrBtn.setOnClickListener(v -> {
                    try {
                        JSONObject roomObj = new JSONObject();
                        roomObj.put("roomId", rid);
                        roomObj.put("roomName", name);
                        roomObj.put("hasPassword", !pwd.isEmpty());
                        showManageRoomDialog(roomObj, role);
                    } catch (Exception e) { Log.e("MainActivity", "toggleFav", e); }
                });
            }
            mgrBtn.setTextSize(12);
            mgrBtn.setMinWidth(0);
            mgrBtn.setPadding(12, 4, 12, 4);
            row.addView(mgrBtn);
        }

        Button joinBtn = new Button(this);
        joinBtn.setText(R.string.room_join);
        joinBtn.setOnClickListener(v -> {
            String savedPwd = getSharedPreferences("boss_room_pwds", MODE_PRIVATE).getString(rid, "");
            dataManager.joinRoom(rid, savedPwd, new DataManager.Callback<String>() {
                @Override public void onResult(String result) {
                    if (!savedPwd.isEmpty()) {
                        getSharedPreferences("boss_room_pwds", MODE_PRIVATE).edit().putString(rid, savedPwd).apply();
                    }
                    updateRoomStatusDisplay();
                    adapter.updateData(dataManager.getAllBosses());
                    if (myRoomsDialog != null) myRoomsDialog.dismiss();
                }
                @Override public void onError(String error) {
                    if (idx >= 0) { showRoomError(error); return; }
                    removeFavIfNeeded(rid, error);
                    if (error.contains("wrong password")) {
                        showMainPwdInput(rid, name, parent);
                    } else {
                        showRoomError(error);
                    }
                }
            });
        });
        joinBtn.setTextSize(12);
        joinBtn.setMinWidth(0);
        joinBtn.setPadding(12, 4, 12, 4);
        row.addView(joinBtn);
        parent.addView(row);

        View div = new View(this);
        div.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        div.setBackgroundColor(0xFFD0D0D0);
        parent.addView(div);
    }

    private AlertDialog myRoomsDialog;

    private void showMyRoomsDialog() {
        if (!getSharedPreferences(PREFS_AUTH, MODE_PRIVATE).getBoolean(KEY_AUTHED, false)) return;
        dataManager.fetchMyRooms(new DataManager.Callback<String>() {
            @Override public void onResult(String result) {
                try {
                    JSONObject json = new JSONObject(result);
                    JSONArray rooms = json.optJSONArray("rooms");
                    JSONArray favs = new JSONArray(dataManager.getFavoritesJson());
                    if ((rooms == null || rooms.length() == 0) && favs.length() == 0) {
                        Toast.makeText(MainActivity.this, R.string.my_rooms_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    LinearLayout listLayout = new LinearLayout(MainActivity.this);
                    listLayout.setOrientation(LinearLayout.VERTICAL);
                    listLayout.setPadding(0, 8, 0, 8);

                    java.util.Set<String> myIds = new java.util.HashSet<>();
                    java.util.Set<String> favIds = new java.util.HashSet<>();
                    if (favs != null) {
                        for (int i = 0; i < favs.length(); i++) {
                            favIds.add(favs.getJSONObject(i).optString("roomId"));
                        }
                    }
                    // 房主房间优先
                    if (rooms != null) {
                        for (int i = 0; i < rooms.length(); i++) {
                            JSONObject r = rooms.getJSONObject(i);
                            String role = r.optString("role", "member");
                            if ("owner".equals(role) || "super_admin".equals(role)) {
                                String rid = r.optString("roomId");
                                myIds.add(rid);
                                String pwd = r.optBoolean("hasPassword", false) ? getString(R.string.room_has_password) : "";
                                boolean isFav = favIds.contains(rid);
                                String icon = "◆";
                                addRoomListItem(listLayout, r.optString("roomName"), rid, pwd, icon, i, rooms, role);
                            }
                        }
                        for (int i = 0; i < rooms.length(); i++) {
                            JSONObject r = rooms.getJSONObject(i);
                            String role = r.optString("role", "member");
                            if (!"owner".equals(role) && !"super_admin".equals(role)) {
                                String rid = r.optString("roomId");
                                myIds.add(rid);
                                String pwd = r.optBoolean("hasPassword", false) ? getString(R.string.room_has_password) : "";
                                boolean isFav = favIds.contains(rid);
                                String icon = isFav ? "★" : "";
                                addRoomListItem(listLayout, r.optString("roomName"), rid, pwd, icon, -1, null, role);
                            }
                        }
                    }
                    for (int i = 0; i < favs.length(); i++) {
                        JSONObject f = favs.getJSONObject(i);
                        String fid = f.optString("roomId");
                        if (!myIds.contains(fid)) {
                            addRoomListItem(listLayout, f.optString("roomName"), fid, "", "★", -1, null, null);
                        }
                    }

                    myRoomsDialog = new AlertDialog.Builder(MainActivity.this)
                            .setTitle(R.string.my_rooms_title)
                            .setView(listLayout)
                            .create();
                    myRoomsDialog.show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, R.string.room_error, Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onError(String error) {
                Toast.makeText(MainActivity.this, R.string.my_rooms_empty, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showManageRoomDialog(JSONObject room, String role) {
        String roomId = room.optString("roomId");
        String roomName = room.optString("roomName");
        boolean hasPwd = room.optBoolean("hasPassword", false);
        boolean isOwner = "owner".equals(role);
        boolean isSuperAdmin = "super_admin".equals(role);

        View v = LayoutInflater.from(this).inflate(R.layout.dialog_manage_room, null);
        EditText nameInput = v.findViewById(R.id.manage_room_name);
        EditText pwdInput = v.findViewById(R.id.manage_room_password);
        Button delBtn = v.findViewById(R.id.manage_room_delete);
        Button saveBtn = v.findViewById(R.id.manage_room_save);
        Button membersBtn = v.findViewById(R.id.manage_room_members);
        View blacklistBtn = v.findViewById(R.id.manage_room_blacklist);

        nameInput.setText(roomName);
        pwdInput.setHint(hasPwd ? R.string.room_pwd_keep : R.string.room_pwd_set);
        pwdInput.setText("");

        if ("admin".equals(role)) {
            nameInput.setEnabled(false);
            saveBtn.setVisibility(View.GONE);
        }

        if (!isOwner && !isSuperAdmin) {
            pwdInput.setVisibility(View.GONE);
            delBtn.setVisibility(View.GONE);
            membersBtn.setVisibility(View.GONE);
            blacklistBtn.setVisibility(View.GONE);
        } else if (isSuperAdmin) {
            delBtn.setVisibility(View.GONE);
        }

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
                    updateRoomStatusDisplay();
                    Toast.makeText(MainActivity.this, R.string.edit_time_success, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                }
                @Override public void onError(String err) {
                    showRoomError(err);
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
                                showRoomError(err);
                            }
                        });
                    })
                    .setNegativeButton(R.string.dialog_button_cancel, null)
                    .show();
        });

        membersBtn.setOnClickListener(w -> showMemberList(roomId));
        v.findViewById(R.id.manage_room_blacklist).setOnClickListener(bb -> UiHelper.showBlacklist(this, dataManager, roomId));
        v.findViewById(R.id.manage_room_logs).setOnClickListener(lb -> UiHelper.showLogs(this, dataManager, roomId));

        dialog.show();
    }

    private void showMemberList(String roomId) {
        dataManager.fetchRoomMembers(roomId, new DataManager.Callback<String>() {
            @Override public void onResult(String result) {
                try {
                    JSONObject json = new JSONObject(result);
                    JSONArray members = json.optJSONArray("members");
                    if (members == null || members.length() == 0) return;

                    LinearLayout listLayout = new LinearLayout(MainActivity.this);
                    listLayout.setOrientation(LinearLayout.VERTICAL);
                    listLayout.setPadding(0, 8, 0, 8);

                    for (int i = 0; i < members.length(); i++) {
                        JSONObject m = members.getJSONObject(i);
                        String role = m.optString("role");
                        String targetUserId = m.optString("userId");
                        String roleDisplay;
                        switch (role) {
                            case "owner": roleDisplay = getString(R.string.role_owner); break;
                            case "super_admin": roleDisplay = getString(R.string.role_super_admin); break;
                            case "admin": roleDisplay = getString(R.string.role_admin); break;
                            default: roleDisplay = getString(R.string.role_member); break;
                        }

                        LinearLayout row = new LinearLayout(MainActivity.this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setPadding(16, 10, 16, 10);
                        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

                        TextView tv = new TextView(MainActivity.this);
                        tv.setText(m.optString("name") + " - " + roleDisplay);
                        tv.setTextSize(16);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
                        tv.setLayoutParams(lp);
                        int idx = i;
                        String myRole = dataManager.getMyRole();
                        boolean isOwner = "owner".equals(myRole);
                        boolean canEditMember = isOwner ||
                            ("super_admin".equals(myRole) && !"owner".equals(role) && !"super_admin".equals(role));
                        if (canEditMember && !targetUserId.equals(dataManager.getUserId())) {
                            tv.setOnClickListener(v -> {
                                try { showEditMemberDialog(roomId, members.getJSONObject(idx)); } catch (Exception ignored) {}
                            });
                        }
                        row.addView(tv);

                        if (!"owner".equals(role) && !"super_admin".equals(role)
                                && (isOwner || "super_admin".equals(myRole))
                                && !targetUserId.equals(dataManager.getUserId())) {
                            Button kickBtn = new Button(MainActivity.this);
                            kickBtn.setText(R.string.kick_member);
                            kickBtn.setTextSize(12);
                            kickBtn.setTextColor(0xFFCC0000);
                            kickBtn.setBackgroundColor(0x00000000);
                            kickBtn.setMinWidth(0);
                            kickBtn.setPadding(12, 4, 12, 4);
                            kickBtn.setOnClickListener(v -> {
                                new AlertDialog.Builder(MainActivity.this)
                                        .setTitle(getString(R.string.kick_confirm, m.optString("name")))
                                        .setPositiveButton(R.string.dialog_button_ok, (dd, ww) -> {
                                            dataManager.kickMember(roomId, targetUserId, new DataManager.Callback<Boolean>() {
                                                @Override public void onResult(Boolean ok) {
                                                    Toast.makeText(MainActivity.this, R.string.edit_time_success, Toast.LENGTH_SHORT).show();
                                                    showMemberList(roomId);
                                                }
                                                @Override public void onError(String err) {
                                                    showRoomError(err);
                                                }
                                            });
                                        })
                                        .setNegativeButton(R.string.dialog_button_cancel, null)
                                        .show();
                            });
                            row.addView(kickBtn);
                        }

                        listLayout.addView(row);

                        View div = new View(MainActivity.this);
                        div.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
                        div.setBackgroundColor(0xFFD0D0D0);
                        listLayout.addView(div);
                    }

                    LinearLayout root = new LinearLayout(MainActivity.this);
                    root.setOrientation(LinearLayout.VERTICAL);

                    LinearLayout titleBar = new LinearLayout(MainActivity.this);
                    titleBar.setOrientation(LinearLayout.HORIZONTAL); titleBar.setPadding(12,8,12,8);
                    titleBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    TextView titleTv = new TextView(MainActivity.this);
                    titleTv.setText(getString(R.string.manage_room_members_title)); titleTv.setTextSize(18);
                    titleTv.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
                    titleBar.addView(titleTv);
                    Button refreshBtn = new Button(MainActivity.this); refreshBtn.setText("🔄");
                    refreshBtn.setOnClickListener(v -> { showMemberList(roomId); });
                    titleBar.addView(refreshBtn);
                    root.addView(titleBar);

                    ScrollView scrollView = new ScrollView(MainActivity.this);
                    scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
                    scrollView.addView(listLayout);
                    root.addView(scrollView);

                    AlertDialog memberDialog = new AlertDialog.Builder(MainActivity.this).setView(root).create();
                    memberDialog.show();
                    Window w = memberDialog.getWindow();
                    if (w != null) {
                        w.setLayout((int)(getResources().getDisplayMetrics().widthPixels * 0.9),
                                    (int)(getResources().getDisplayMetrics().heightPixels * 0.7));
                    }
                } catch (Exception e) { Log.e("MainActivity", "toggleFav", e); }
            }
            @Override public void onError(String error) {}
        });
    }

    private void showEditMemberDialog(String roomId, JSONObject member) {
        String targetUserId = member.optString("userId");
        if (targetUserId.equals(dataManager.getUserId())) {
            Toast.makeText(this, R.string.cannot_edit_own_role, Toast.LENGTH_SHORT).show();
            return;
        }
        String name = member.optString("name");
        String currentRole = member.optString("role");

        String[] roles = {getString(R.string.role_super_admin), getString(R.string.role_admin), getString(R.string.role_member)};
        int sel = "super_admin".equals(currentRole) ? 0 : "admin".equals(currentRole) ? 1 : 2;

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.edit_member_title, name))
                .setSingleChoiceItems(roles, sel, (d, which) -> {
                    String[] enRoles = {"super_admin", "admin", "member"};
                    String newRole = enRoles[which];
                    dataManager.updateMemberRole(roomId, targetUserId, newRole, null, new DataManager.Callback<Boolean>() {
                        @Override public void onResult(Boolean ok) {
                            Toast.makeText(MainActivity.this, R.string.edit_time_success, Toast.LENGTH_SHORT).show();
                            d.dismiss();
                        }
                        @Override public void onError(String err) {
                            showRoomError(err);
                        }
                    });
                })
                .show();
    }

    private String getRoleDisplay(String role) {
        switch (role) {
            case "owner": return getString(R.string.role_owner);
            case "super_admin": return getString(R.string.role_super_admin);
            case "admin": return getString(R.string.role_admin);
            default: return getString(R.string.role_member);
        }
    }

    private void updateRoomStatusDisplay() {
        if (dataManager.isSharedMode()) {
            leaveRoomButton.setVisibility(View.VISIBLE);
            if (dataManager.isShowingSharedData()) {
                roomButton.setText(R.string.float_button_local);
                addButton.setVisibility(dataManager.canAdd() ? View.VISIBLE : View.GONE);
                headerText.setText(getString(R.string.shared_header, dataManager.getCurrentRoomId(), getRoleDisplay(dataManager.getMyRole())));
                headerFavIcon.setText(isRoomFav() ? "★" : "☆");
                headerFavIcon.setVisibility(View.VISIBLE);
                headerFavText.setVisibility(View.VISIBLE);
                sharedHeader.setVisibility(View.VISIBLE);
                sharedHeader.setBackgroundColor(0xFF3F51B5);
                headerText.setTextColor(0xFFFFFFFF);
                String name = dataManager.getUserName();
                headerUserName.setText(name != null && !name.isEmpty() ? name : "");
                headerUserName.setVisibility(name != null && !name.isEmpty() ? View.VISIBLE : View.GONE);
            } else {
                roomButton.setText(R.string.room_button);
                addButton.setVisibility(View.VISIBLE);
                headerText.setText(R.string.mode_local);
                headerText.setTextColor(0xFF333333);
                headerFavIcon.setVisibility(View.GONE);
                headerFavText.setVisibility(View.GONE);
                headerUserName.setVisibility(View.GONE);
                sharedHeader.setVisibility(View.VISIBLE);
                sharedHeader.setBackgroundColor(0xFFF0F0F0);
            }
        } else {
            roomButton.setText(R.string.room_button);
            leaveRoomButton.setVisibility(View.GONE);
            addButton.setVisibility(View.VISIBLE);
            headerText.setText(R.string.mode_local);
            headerText.setTextColor(0xFF333333);
            headerFavIcon.setVisibility(View.GONE);
            headerFavText.setVisibility(View.GONE);
            headerUserName.setVisibility(View.GONE);
            sharedHeader.setVisibility(View.VISIBLE);
            sharedHeader.setBackgroundColor(0xFFF0F0F0);
        }
        if (headerReset != null) {
            headerReset.setTextColor(adapter.isShowResetButton() ? 0xFF2196F3 : 0xFF333333);
        }
        if (headerDelete != null) {
            headerDelete.setTextColor(adapter.isShowDeleteButton() ? 0xFFE57373 : 0xFF333333);
        }
    }

    private void showRoomDialog() {
        if (!checkAuth()) return;
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
                    showRoomError(error);
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
                    if (password != null && !password.isEmpty()) {
                        getSharedPreferences("boss_room_pwds", MODE_PRIVATE).edit().putString(roomId, password).apply();
                    }
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
        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.9),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
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
                    new AlertDialog.Builder(MainActivity.this)
                        .setMessage(R.string.language_restart)
                        .setPositiveButton(android.R.string.ok, (d, w) -> {
                            LocaleHelper.saveLanguage(this, lang);
                            EventBus.getDefault().post(new LanguageChangeEvent());
                            Intent intent = getIntent();
                            startActivity(intent);
                            finish();
                            overridePendingTransition(0, 0);
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
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
        boolean wasSharedMode = dataManager.isShowingSharedData();
        View dialogView = LayoutInflater.from(this).inflate(R.layout.input_information, null);
        EditText nameInput = dialogView.findViewById(R.id.input_name);
        TextView roomInfoView = dialogView.findViewById(R.id.dialog_room_info);
        if (wasSharedMode) {
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
                    if (wasSharedMode) {
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
        Window window = dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    // 完整编辑对话框
    private void showEditDialog(ItemAdapter adapter, int position) {
        boolean wasSharedMode = dataManager.isShowingSharedData();
        View dialogView = LayoutInflater.from(this).inflate(R.layout.edit_information, null);
        EditText nameInput = dialogView.findViewById(R.id.edit_name);
        TextView roomInfoView2 = dialogView.findViewById(R.id.dialog_room_info);
        if (wasSharedMode) {
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
                        data.editTimeType = "refreshTime";
                        data.enteredValue = spawnCalendar.getTimeInMillis();
                        data.startTime = (data.enteredValue / 1000 - data.spawnTime) * 1000;
                        if (data.decreasingMode && data.deathCount < data.decreasingCount && data.decreasingSeconds > 0) {
                            data.deathCount++;
                            if (data.initialSpawnTime == 0) data.initialSpawnTime = data.spawnTime;
                            data.spawnTime = data.initialSpawnTime - data.deathCount * data.decreasingSeconds;
                            if (data.spawnTime < 0) data.spawnTime = 0;
                        }
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
                        data.editTimeType = "killTime";
                        data.enteredValue = killedCalendar.getTimeInMillis();
                        data.startTime = data.enteredValue;
                        if (data.decreasingMode && data.deathCount < data.decreasingCount && data.decreasingSeconds > 0) {
                            data.deathCount++;
                            if (data.initialSpawnTime == 0) data.initialSpawnTime = data.spawnTime;
                            data.spawnTime = data.initialSpawnTime - data.deathCount * data.decreasingSeconds;
                            if (data.spawnTime < 0) data.spawnTime = 0;
                        }
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
                        data.editTimeType = "remainingTime";
                        data.enteredValue = spawn;
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
                    if (wasSharedMode) {
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
        boolean wasSharedMode = dataManager.isShowingSharedData();
        RowData data = adapter.dataList.get(position);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_name, null);

        EditText nameInput = dialogView.findViewById(R.id.edit_name);
        EditText hourInput = dialogView.findViewById(R.id.edit_hour);
        EditText minuteInput = dialogView.findViewById(R.id.edit_minute);
        EditText secondInput = dialogView.findViewById(R.id.edit_second);
        Button btnDecrease = dialogView.findViewById(R.id.btn_open_decrease);
        Button btnRestart = dialogView.findViewById(R.id.btn_restart_decrease);
        TextView tvDecreaseStatus = dialogView.findViewById(R.id.tv_decrease_status);
        TextView tvDecreaseCountInfo = dialogView.findViewById(R.id.tv_decrease_count_info);
        TextView tvDecreaseTimeInfo = dialogView.findViewById(R.id.tv_decrease_time_info);

        if (data.decreasingMode && data.deathCount < data.decreasingCount) {
            tvDecreaseStatus.setText(String.format(getString(R.string.decrease_current), data.deathCount, data.decreasingCount));
            tvDecreaseCountInfo.setText(getString(R.string.decrease_count) + " " + data.decreasingCount);
            tvDecreaseCountInfo.setVisibility(View.VISIBLE);
            tvDecreaseTimeInfo.setText(formatDecreaseSeconds(data.decreasingSeconds));
            tvDecreaseTimeInfo.setVisibility(View.VISIBLE);
            btnRestart.setVisibility(View.VISIBLE);
        } else if (data.decreasingMode) {
            tvDecreaseStatus.setText(getString(R.string.decrease_mode) + " ✓");
            tvDecreaseCountInfo.setText(getString(R.string.decrease_count) + " " + data.decreasingCount);
            tvDecreaseCountInfo.setVisibility(View.VISIBLE);
            tvDecreaseTimeInfo.setText(formatDecreaseSeconds(data.decreasingSeconds));
            tvDecreaseTimeInfo.setVisibility(View.VISIBLE);
            btnRestart.setVisibility(View.VISIBLE);
        } else {
            tvDecreaseStatus.setText("");
            tvDecreaseCountInfo.setVisibility(View.GONE);
            tvDecreaseTimeInfo.setVisibility(View.GONE);
            btnRestart.setVisibility(View.GONE);
        }

        // 预填当前名称
        nameInput.setText(data.text1);

        TextView roomInfoV = dialogView.findViewById(R.id.dialog_room_info);
        if (roomInfoV != null) {
            if (wasSharedMode) {
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
                    String oldName = data.text1;
                    long oldSpawn = data.spawnTime;
                    // 更新名称
                    String newName = nameInput.getText().toString().trim();
                    if (!newName.isEmpty()) {
                        data.text1 = newName;
                    }

                    // 解析新周期（仅时/分/秒）
                    long h = parseLongOrDefault(hourInput.getText().toString().trim(), 0);
                    long m = parseLongOrDefault(minuteInput.getText().toString().trim(), 0);
                    long s = parseLongOrDefault(secondInput.getText().toString().trim(), 0);
                    long newSpawn = h * 3600 + m * 60 + s;

                    boolean spawnChanged = newSpawn > 0 && newSpawn != oldSpawn;
                    // 若输入了有效周期，则更新 spawnTime，保留 startTime 不变
                    if (spawnChanged) {
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

                    if (wasSharedMode) {
                        if (!spawnChanged && !oldName.equals(data.text1)) {
                            dataManager.renameBossAndSync(data, oldName, data.text1);
                        } else {
                            dataManager.editBossShared(data);
                        }
                    } else {
                        dataManager.editBoss(data);
                    }
                    EventBus.getDefault().post(new UpdateFloatWindowEvent(EventTypes.EDIT_ITEM, data));
                    adapter.notifyDataSetChanged();
                    Toast.makeText(this, R.string.edit_name_success, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.dialog_button_cancel, null)
                .create();
        dialogView.setTag(dialog);
        btnDecrease.setOnClickListener(v -> {
            showDecreaseDialog(adapter, position);
            ((AlertDialog) dialogView.getTag()).dismiss();
        });
        btnRestart.setOnClickListener(v -> {
            data.deathCount = 0;
            if (data.initialSpawnTime > 0) data.spawnTime = data.initialSpawnTime;
            data.initialSpawnTime = 0;
            data.startTime = System.currentTimeMillis() - data.spawnTime * 1000;
            data.isNotified = false;
            data.text2 = getString(R.string.refreshed);
            data.text3 = "00:00";
            if (wasSharedMode && data.docId != null) {
                dataManager.editBossShared(data);
            } else {
                dbHelper.editBoss(data);
            }
            adapter.updateData(dataManager.getAllBosses());
            EventBus.getDefault().post(new UpdateFloatWindowEvent(EventTypes.RESET_ITEM, position));
            ((AlertDialog) dialogView.getTag()).dismiss();
        });
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
        boolean wasSharedMode = dataManager.isShowingSharedData();
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
            String infoText;
            if (wasSharedMode) {
                infoText = getString(R.string.shared_header, dataManager.getCurrentRoomId(), getRoleDisplay(dataManager.getMyRole()));
                roomInfoEt.setTextColor(0xFF3F51B5);
            } else {
                infoText = getString(R.string.mode_local);
                roomInfoEt.setTextColor(0xFF999999);
            }
            roomInfoEt.setText(infoText + "\n" + getString(R.string.boss_name_prefix) + data.text1);
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
                        data.editTimeType = "killTime";
                        Calendar killedCalendar = Calendar.getInstance();
                        if (!killedDayText.isEmpty()) killedCalendar.add(Calendar.DAY_OF_MONTH, -Integer.parseInt(killedDayText));
                        if (!killedHourText.isEmpty()) killedCalendar.set(Calendar.HOUR_OF_DAY, Integer.parseInt(killedHourText));
                        if (!killedMinuteText.isEmpty()) killedCalendar.set(Calendar.MINUTE, Integer.parseInt(killedMinuteText));
                        if (!killedSecondText.isEmpty()) killedCalendar.set(Calendar.SECOND, Integer.parseInt(killedSecondText));
                        data.enteredValue = killedCalendar.getTimeInMillis();
                        data.startTime = data.enteredValue;
                        if (data.decreasingMode && data.deathCount < data.decreasingCount && data.decreasingSeconds > 0) {
                            data.deathCount++;
                            if (data.initialSpawnTime == 0) data.initialSpawnTime = data.spawnTime;
                            data.spawnTime = data.initialSpawnTime - data.deathCount * data.decreasingSeconds;
                            if (data.spawnTime < 0) data.spawnTime = 0;
                        }
                    } else if (hasSpawn) {
                        data.editTimeType = "refreshTime";
                        Calendar spawnCalendar = Calendar.getInstance();
                        if (!needDayText.isEmpty()) {
                            spawnCalendar.add(Calendar.DAY_OF_MONTH, Integer.parseInt(needDayText));
                        }
                        if (!spawnHourText.isEmpty()) spawnCalendar.set(Calendar.HOUR_OF_DAY, Integer.parseInt(spawnHourText));
                        if (!spawnMinuteText.isEmpty()) spawnCalendar.set(Calendar.MINUTE, Integer.parseInt(spawnMinuteText));
                        if (!spawnSecondText.isEmpty()) spawnCalendar.set(Calendar.SECOND, Integer.parseInt(spawnSecondText));
                        data.enteredValue = spawnCalendar.getTimeInMillis();
                        data.startTime = (data.enteredValue / 1000 - data.spawnTime) * 1000;
                        if (data.decreasingMode && data.deathCount < data.decreasingCount && data.decreasingSeconds > 0) {
                            data.deathCount++;
                            if (data.initialSpawnTime == 0) data.initialSpawnTime = data.spawnTime;
                            data.spawnTime = data.initialSpawnTime - data.deathCount * data.decreasingSeconds;
                            if (data.spawnTime < 0) data.spawnTime = 0;
                        }
                    }

                    data.isNotified = false;
                    data.setSpawnTime(this);
                    long remaining = data.spawnTime - ((System.currentTimeMillis() - data.startTime) / 1000);
                    if (remaining < 0) remaining = 0;
                    data.text3 = (remaining >= 3600) ?
                            String.format(Locale.getDefault(), "%02d:%02d:%02d", remaining / 3600, (remaining % 3600) / 60, remaining % 60) :
                            String.format(Locale.getDefault(), "%02d:%02d", remaining / 60, remaining % 60);

                    if (wasSharedMode) {
                        dataManager.editBossShared(data);
                    } else {
                        dataManager.editBoss(data);
                    }
                    EventBus.getDefault().post(new UpdateFloatWindowEvent(EventTypes.EDIT_ITEM, data));
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

    private String formatDecreaseSeconds(long seconds) {
        if (seconds <= 0) return "";
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) {
            return getString(R.string.decrease_time) + " " + h + ":" + String.format(Locale.getDefault(), "%02d", m) + ":" + String.format(Locale.getDefault(), "%02d", s);
        } else if (m > 0) {
            return getString(R.string.decrease_time) + " " + m + ":" + String.format(Locale.getDefault(), "%02d", s);
        } else {
            return getString(R.string.decrease_time) + " " + s + getString(R.string.input_second_hint);
        }
    }

    // ★ 修改剩余时间对话框
    private void showEditRemainingDialog(ItemAdapter adapter, int position) {
        boolean wasSharedMode = dataManager.isShowingSharedData();
        RowData data = adapter.dataList.get(position);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_remaining, null);

        TextView roomInfoEr = dialogView.findViewById(R.id.dialog_room_info);
        if (roomInfoEr != null) {
            String infoText;
            if (wasSharedMode) {
                infoText = getString(R.string.shared_header, dataManager.getCurrentRoomId(), getRoleDisplay(dataManager.getMyRole()));
                roomInfoEr.setTextColor(0xFF3F51B5);
            } else {
                infoText = getString(R.string.mode_local);
                roomInfoEr.setTextColor(0xFF999999);
            }
            roomInfoEr.setText(infoText + "\n" + getString(R.string.boss_name_prefix) + data.text1);
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
                        data.editTimeType = "remainingTime";
                        data.enteredValue = newRemaining;
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
                    if (wasSharedMode) {
                        dataManager.editBossShared(data);
                    } else {
                        dataManager.editBoss(data);
                    }
                        EventBus.getDefault().post(new UpdateFloatWindowEvent(EventTypes.EDIT_ITEM, data));
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

    // 作者声明对话框
    private void showAuthorDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!prefs.getBoolean("no_more_author", false)) {
            Dialog dialog = new Dialog(this);
            dialog.setContentView(R.layout.dialog_author);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setLayout(
                        (int) (getResources().getDisplayMetrics().widthPixels * 0.9),
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
            }
            CheckBox chkNoMore = dialog.findViewById(R.id.chk_no_more);
            Button btnOk = dialog.findViewById(R.id.btn_ok);
            btnOk.setOnClickListener(v -> {
                if (chkNoMore.isChecked()) {
                    prefs.edit().putBoolean("no_more_author", true).apply();
                }
                dialog.dismiss();
            });
            dialog.show();
        }
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
        ImageView qr1 = dialogView.findViewById(R.id.qr_code_image);
        ImageView qr2 = dialogView.findViewById(R.id.qr_code_image2);
        AlertDialog dialog = builder.setView(dialogView).create();
        closeButton.setOnClickListener(v -> dialog.dismiss());

        View.OnClickListener qrClickListener = v -> {
            dialog.dismiss();
            ImageView zoomView = new ImageView(this);
            zoomView.setImageDrawable(((ImageView) v).getDrawable());
            zoomView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            zoomView.setAdjustViewBounds(true);
            AlertDialog zoomDialog = new AlertDialog.Builder(this)
                    .setView(zoomView)
                    .setCancelable(true)
                    .create();
            zoomDialog.setOnCancelListener(d -> zoomDialog.dismiss());
            zoomDialog.show();
            Window w = zoomDialog.getWindow();
            if (w != null) {
                w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.90),
                        (int) (getResources().getDisplayMetrics().heightPixels * 0.6));
            }
        };
        qr1.setOnClickListener(qrClickListener);
        qr2.setOnClickListener(qrClickListener);

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
        if (dataManager.isSpecialCode(searchText)) {
            dataManager.setExpansionCode(searchText);
            Toast.makeText(this, R.string.auth_success, Toast.LENGTH_SHORT).show();
            searchInput.setText("");
            return;
        }
        if (searchRunnable != null) {
            searchHandler.removeCallbacks(searchRunnable);
        }
        searchRunnable = () -> {
            adapter.filteredString = searchText.isEmpty() ? "" : searchText;
            adapter.updateData(dataManager.getAllBosses());
        };
        searchHandler.postDelayed(searchRunnable, 300);
    }

    private void checkAndRequestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null && !alarmManager.canScheduleExactAlarms()) {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                if (prefs.getBoolean("no_more_alarm_prompt", false)) return;
                new AlertDialog.Builder(this)
                        .setTitle(R.string.alarm_permission_title)
                        .setMessage(R.string.alarm_permission_message)
                        .setPositiveButton(R.string.go_to_settings, (dialog, which) -> {
                            prefs.edit().putBoolean("no_more_alarm_prompt", true).apply();
                            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        })
                        .setNegativeButton(R.string.battery_opt_negative, null)
                        .setCancelable(false)
                        .show();
            }
        }
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
    protected void onPause() {
        super.onPause();
        if (adapter != null) {
            adapter.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        dataManager.throttledSync();
        if (adapter != null) {
            adapter.resume();
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
    }

    private void showDecreaseDialog(ItemAdapter adapter, int position) {
        boolean wasSharedMode = dataManager.isShowingSharedData();
        RowData data = adapter.dataList.get(position);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 16, 32, 16);

        CheckBox enableCb = new CheckBox(this);
        enableCb.setText(getString(R.string.decrease_mode));
        enableCb.setChecked(data.decreasingMode);
        enableCb.setTextSize(16);
        root.addView(enableCb);

        TextView timeLabel = new TextView(this);
        timeLabel.setText(getString(R.string.decrease_time) + " (H:M:S):");
        timeLabel.setTextSize(14);
        timeLabel.setPadding(0, 12, 0, 4);
        root.addView(timeLabel);

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText etH = new EditText(this); etH.setHint(R.string.input_hour_hint); etH.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        EditText etM = new EditText(this); etM.setHint(R.string.input_minute_hint); etM.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        EditText etS = new EditText(this); etS.setHint(R.string.input_second_hint); etS.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        for (EditText et : new EditText[]{etH, etM, etS}) {
            et.setLayoutParams(new LinearLayout.LayoutParams(0, (int)(40 * getResources().getDisplayMetrics().density), 1));
            et.setTextSize(14);
            et.setGravity(android.view.Gravity.CENTER);
            et.setPadding(8, 8, 8, 8);
            timeRow.addView(et);
        }
        root.addView(timeRow);

        TextView countLabel = new TextView(this);
        countLabel.setText(getString(R.string.decrease_count) + ":");
        countLabel.setTextSize(14);
        countLabel.setPadding(0, 12, 0, 4);
        root.addView(countLabel);

        EditText etCount = new EditText(this);
        etCount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etCount.setTextSize(14);
        etCount.setGravity(android.view.Gravity.CENTER);
        etCount.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int)(40 * getResources().getDisplayMetrics().density)));
        root.addView(etCount);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, 16, 0, 0);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.decrease_config)
            .setView(root)
            .create();

        if (data.decreasingMode) {
            Button restartBtn = new Button(this);
            restartBtn.setText(getString(R.string.decrease_restart));
            restartBtn.setTextSize(13);
            restartBtn.setOnClickListener(v -> {
                data.deathCount = 0;
                if (data.initialSpawnTime > 0) data.spawnTime = data.initialSpawnTime;
                data.initialSpawnTime = 0;
                data.startTime = System.currentTimeMillis() - data.spawnTime * 1000;
                data.isNotified = false;
                data.text2 = getString(R.string.refreshed);
                data.text3 = "00:00";
                if (wasSharedMode && data.docId != null) {
                    dataManager.editBossShared(data);
                } else {
                    dbHelper.editBoss(data);
                }
                dialog.dismiss();
                adapter.updateData(dataManager.getAllBosses());
                EventBus.getDefault().post(new UpdateFloatWindowEvent(EventTypes.RESET_ITEM, position));
            });
            btnRow.addView(restartBtn);
        }

        Button okBtn = new Button(this);
        okBtn.setText(android.R.string.ok);
        okBtn.setTextSize(13);
        okBtn.setOnClickListener(v -> {
            boolean enabled = enableCb.isChecked();
            int h = etH.getText().toString().isEmpty() ? 0 : Integer.parseInt(etH.getText().toString());
            int m = etM.getText().toString().isEmpty() ? 0 : Integer.parseInt(etM.getText().toString());
            int s = etS.getText().toString().isEmpty() ? 0 : Integer.parseInt(etS.getText().toString());
            int cnt = etCount.getText().toString().isEmpty() ? 0 : Integer.parseInt(etCount.getText().toString());

            data.decreasingMode = enabled;
            data.decreasingSeconds = h * 3600 + m * 60 + s;
            data.decreasingCount = cnt;
            if (data.initialSpawnTime == 0) data.initialSpawnTime = data.spawnTime;
            dbHelper.editBoss(data);
            adapter.notifyItemChanged(position);
            dialog.dismiss();
            EventBus.getDefault().post(new UpdateFloatWindowEvent(EventTypes.EDIT_ITEM, position));
        });
        btnRow.addView(okBtn);

        root.addView(btnRow);
        dialog.show();
    }

    private void showFloatBatchDialog() {
        boolean wasSharedMode = dataManager.isShowingSharedData();
        java.util.List<RowData> allBosses = dataManager.getAllBosses();
        if (allBosses.isEmpty()) {
            Toast.makeText(this, R.string.my_rooms_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        long now = System.currentTimeMillis();
        java.util.Collections.sort(allBosses, (a, b) -> {
            long ra = a.spawnTime - ((now - a.startTime) / 1000);
            long rb = b.spawnTime - ((now - b.startTime) / 1000);
            return Long.compare(ra, rb);
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 8, 16, 8);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, 0, 0, 8);

        Button selectAllBtn = new Button(this);
        selectAllBtn.setText(R.string.float_batch_select_all);
        selectAllBtn.setTextSize(12);
        btnRow.addView(selectAllBtn);

        Button deselectAllBtn = new Button(this);
        deselectAllBtn.setText(R.string.float_batch_deselect_all);
        deselectAllBtn.setTextSize(12);
        btnRow.addView(deselectAllBtn);

        root.addView(btnRow);

        LinearLayout listLayout = new LinearLayout(this);
        listLayout.setOrientation(LinearLayout.VERTICAL);

        java.util.List<CheckBox> checkBoxes = new java.util.ArrayList<>();
        for (RowData data : allBosses) {
            CheckBox cb = new CheckBox(this);
            cb.setText(data.text1);
            cb.setChecked(data.showInFloat);
            cb.setTextSize(15);
            cb.setPadding(4, 8, 4, 8);
            listLayout.addView(cb);
            checkBoxes.add(cb);
        }

        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
        sv.addView(listLayout);
        root.addView(sv);

        selectAllBtn.setOnClickListener(v -> {
            for (CheckBox cb : checkBoxes) cb.setChecked(true);
        });
        deselectAllBtn.setOnClickListener(v -> {
            for (CheckBox cb : checkBoxes) cb.setChecked(false);
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.float_batch_title)
                .setView(root)
                .setPositiveButton(R.string.float_batch_apply, (d, w) -> {
                    StringBuilder changedNames = new StringBuilder();
                    for (int i = 0; i < allBosses.size(); i++) {
                        RowData data = allBosses.get(i);
                        boolean newVal = checkBoxes.get(i).isChecked();
                        if (data.showInFloat != newVal) {
                            data.showInFloat = newVal;
                            if (wasSharedMode) {
                                dataManager.editBossShared(data);
                            } else {
                                dataManager.editBoss(data);
                            }
                            if (changedNames.length() > 0) changedNames.append(",");
                            changedNames.append(data.text1);
                        }
                    }
                    if (changedNames.length() > 0) {
                        adapter.updateData(dataManager.getAllBosses());
                        EventBus.getDefault().post(new UpdateFloatWindowEvent(EventTypes.EDIT_ITEM, (RowData) null));
                        Toast.makeText(MainActivity.this, R.string.edit_time_success, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.dialog_button_cancel, null)
                .create();
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout((int)(getResources().getDisplayMetrics().widthPixels * 0.9),
                    (int)(getResources().getDisplayMetrics().heightPixels * 0.7));
        }
    }

    private void showHeaderPopupMenu(View anchor) {
        TextView tv = new TextView(this);
        tv.setText(getString(R.string.server_restart));
        tv.setTextSize(18);
        tv.setTextColor(0xFFE53935);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setPadding(32, 24, 32, 24);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(tv)
                .create();
        tv.setOnClickListener(v -> {
            dialog.dismiss();
            showServerRestartConfirm();
        });
        dialog.show();
    }

    private void showServerRestartConfirm() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.server_restart)
                .setMessage(R.string.server_restart_confirm)
                .setPositiveButton(R.string.dialog_button_ok, (d, w) -> performServerRestart())
                .setNegativeButton(R.string.dialog_button_cancel, null)
                .show();
    }

    private void performServerRestart() {
        java.util.List<RowData> bosses = dataManager.getAllBosses();
        long now = System.currentTimeMillis();
        StringBuilder bossListJson = new StringBuilder();
        for (RowData data : bosses) {
            if (data.autoReset) continue;
            long oldEndTime = data.startTime + data.spawnTime * 1000;
            if (data.decreasingMode) {
                data.deathCount = 0;
                if (data.initialSpawnTime > 0) data.spawnTime = data.initialSpawnTime;
                data.initialSpawnTime = 0;
            }
            data.startTime = now - data.spawnTime * 1000;
            data.isNotified = true;
            dbHelper.editBoss(data);
            if (bossListJson.length() > 0) bossListJson.append(",");
            bossListJson.append("{\"name\":\"").append(escapeJson(data.text1))
                .append("\",\"endTime\":").append(data.startTime + data.spawnTime * 1000)
                .append(",\"oldEndTime\":").append(oldEndTime)
                .append(",\"spawn\":").append(data.spawnTime)
                .append(",\"decreasing\":").append(data.decreasingMode)
                .append(",\"decreasingSeconds\":").append(data.decreasingSeconds)
                .append(",\"decreasingCount\":").append(data.decreasingCount)
                .append("}");
        }
        dataManager.refreshCache();
        dataManager.addRestartLog(bossListJson.toString());
        adapter.updateData(dataManager.getAllBosses());
        EventBus.getDefault().post(new UpdateFloatWindowEvent(EventTypes.SYNC_COMPLETED, (RowData) null));
        Toast.makeText(this, R.string.edit_time_success, Toast.LENGTH_SHORT).show();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}