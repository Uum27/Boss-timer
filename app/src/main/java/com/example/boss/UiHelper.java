package com.example.boss;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class UiHelper {

    static String formatSeconds(long s) {
        return String.format(Locale.getDefault(),"%02d:%02d:%02d",Math.abs(s)/3600,(Math.abs(s)/60)%60,Math.abs(s)%60);
    }

    static String formatTime(long m) {
        Calendar n = Calendar.getInstance(), t = Calendar.getInstance(); t.setTimeInMillis(m);
        if (n.get(Calendar.DAY_OF_YEAR)==t.get(Calendar.DAY_OF_YEAR)&&n.get(Calendar.YEAR)==t.get(Calendar.YEAR))
            return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(m));
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(m));
    }

    public static void showLogs(Context ctx, DataManager dm, String roomId) {
        dm.fetchLogs(roomId, new DataManager.Callback<String>() {
            @Override public void onResult(String result) {
                try {
                    JSONArray all = new JSONObject(result).optJSONArray("logs");
                    if (all == null || all.length() == 0) return;
                    JSONArray logs = new JSONArray();
                    for (int i = 0; i < all.length(); i++) {
                        JSONObject l = all.getJSONObject(i);
                        String u = l.optString("userName","").toLowerCase();
                        String t = l.optString("target","").toLowerCase();
                        String a = l.optString("action");
                        String lb = "add".equals(a)?ctx.getString(R.string.log_add):"delete".equals(a)?ctx.getString(R.string.log_delete):"kick".equals(a)?ctx.getString(R.string.log_kick):ctx.getString(R.string.log_edit);
                        logs.put(l);
                    }
                    LinearLayout root = new LinearLayout(ctx); root.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout top = new LinearLayout(ctx); top.setOrientation(LinearLayout.HORIZONTAL); top.setPadding(4,4,4,4);
                    TextView ti = new TextView(ctx); ti.setText(ctx.getString(R.string.logs_button)); ti.setTextSize(16);
                    ti.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1)); top.addView(ti);
                    EditText sr = new EditText(ctx); sr.setHint(ctx.getString(R.string.log_search_hint)); sr.setSingleLine(true); sr.setWidth(350); top.addView(sr);
                    root.addView(top);
                    LinearLayout li = new LinearLayout(ctx); li.setOrientation(LinearLayout.VERTICAL);
                    SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
                    refreshLogList(ctx, dm, roomId, logs, li, sdf, "");
                    sr.addTextChangedListener(new android.text.TextWatcher() {
                        public void afterTextChanged(android.text.Editable s) {
                            refreshLogList(ctx, dm, roomId, logs, li, sdf, s.toString().trim().toLowerCase());
                        }
                        public void beforeTextChanged(CharSequence ss,int st,int co,int af){}
                        public void onTextChanged(CharSequence ss,int st,int be,int af){}
                    });
                    ScrollView sv = new ScrollView(ctx); sv.setLayoutParams(new LinearLayout.LayoutParams(-1,0,1)); sv.addView(li); root.addView(sv);
                    AlertDialog d = new AlertDialog.Builder(ctx).setTitle(R.string.logs_button).setView(root).create();
                    d.show(); Window w = d.getWindow();
                    if (w != null) w.setLayout((int)(ctx.getResources().getDisplayMetrics().widthPixels*0.9),(int)(ctx.getResources().getDisplayMetrics().heightPixels*0.8));
                } catch(Exception ignored){}
            }
            @Override public void onError(String e){}
        });
    }

    static String getActionLabels(String action) {
        switch (action) {
            case "add": return "添加 add 추가";
            case "edit": return "修改 edit 편집";
            case "delete": return "删除 delete 삭제";
            case "kick": return "踢出 kick 추방";
            default: return action;
        }
    }

    static void refreshLogList(Context ctx, DataManager dm, String rid, JSONArray logs, LinearLayout li, SimpleDateFormat sdf, String filter) {
        li.removeAllViews();
        for (int i = 0; i < logs.length(); i++) {
            try {
                JSONObject l = logs.getJSONObject(i); String a = l.optString("action");
                String u = l.optString("userName","").toLowerCase(), t = l.optString("target","").toLowerCase();
                String lb = "add".equals(a)?ctx.getString(R.string.log_add):"delete".equals(a)?ctx.getString(R.string.log_delete):"kick".equals(a)?ctx.getString(R.string.log_kick):ctx.getString(R.string.log_edit);
                if (!filter.isEmpty() && !u.contains(filter) && !t.contains(filter) && !lb.contains(filter)
                    && !getActionLabels(a).contains(filter)) continue;
                StringBuilder tx = new StringBuilder();
                tx.append(sdf.format(new Date(l.optLong("time")))).append(" ").append(l.optString("userName")).append(" | ").append(l.optString("target")).append(" 【").append(lb).append("】");
                JSONArray chs = l.optJSONArray("changes");
                if (chs != null) for (int c=0; c<chs.length(); c++) {
                    JSONObject ch = chs.getJSONObject(c); String f = ch.optString("field");
                    if ("startTime".equals(f)) {
                        tx.append("\n  ").append(ctx.getString(R.string.log_end_time)).append(": ").append(formatTime(ch.optLong("oldRefresh"))).append(" → ").append(formatTime(ch.optLong("newRefresh")));
                        long sp = ch.optLong("spawn",0); if (sp>0) tx.append("\n  ").append(ctx.getString(R.string.log_reset_time)).append(": ").append(formatSeconds(sp));
                    } else if ("name".equals(f)) tx.append("\n  ").append(ctx.getString(R.string.log_name)).append(": ").append(ch.optString("old")).append(" → ").append(ch.optString("new"));
                    else if ("notifyTime".equals(f)) tx.append("\n  ").append(ctx.getString(R.string.log_notify)).append(": ").append(formatSeconds(ch.optLong("old"))).append(" → ").append(formatSeconds(ch.optLong("new")));
                    else if ("autoReset".equals(f)) tx.append("\n  ").append(ctx.getString(R.string.log_auto_reset)).append(": ").append(ch.optBoolean("old")?ctx.getString(R.string.yes):ctx.getString(R.string.no)).append(" → ").append(ch.optBoolean("new")?ctx.getString(R.string.yes):ctx.getString(R.string.no));
                    else if ("spawn".equals(f)) tx.append("\n  ").append(ctx.getString(R.string.log_reset_time)).append(": ").append(formatSeconds(ch.optLong("old"))).append(" → ").append(formatSeconds(ch.optLong("new")));
                }
                if ("add".equals(a)) {
                    long rt = l.optLong("refreshTime",0); if (rt>0) tx.append("\n  ").append(ctx.getString(R.string.log_end_time)).append(": ").append(formatTime(rt));
                    long sp = l.optLong("spawn",0); if (sp>0) tx.append("\n  ").append(ctx.getString(R.string.log_reset_time)).append(": ").append(formatSeconds(sp));
                }
                if ("delete".equals(a)) {
                    long rt = l.optLong("refreshTime",0); if (rt>0) tx.append("\n  ").append(ctx.getString(R.string.log_end_time)).append(": ").append(formatTime(rt));
                    long sp = l.optLong("spawn",0); if (sp>0) tx.append("\n  ").append(ctx.getString(R.string.log_reset_time)).append(": ").append(formatSeconds(sp));
                }
                TextView tv = new TextView(ctx); tv.setTextSize(13); tv.setPadding(12,8,12,8); tv.setText(tx.toString()); li.addView(tv);
                View dv = new View(ctx); dv.setLayoutParams(new LinearLayout.LayoutParams(-1,1)); dv.setBackgroundColor(0x80D0D0D0); li.addView(dv);
            } catch(Exception ignored){}
        }
    }

    public static void showBlacklist(Context ctx, DataManager dm, String roomId) {
        dm.fetchBannedList(roomId, new DataManager.Callback<String>() {
            @Override public void onResult(String result) {
                try {
                    JSONArray banned = new JSONObject(result).optJSONArray("banned");
                    if (banned==null||banned.length()==0){Toast.makeText(ctx,R.string.blacklist_empty,Toast.LENGTH_SHORT).show();return;}
                    LinearLayout l=new LinearLayout(ctx); l.setOrientation(LinearLayout.VERTICAL);
                    for(int i=0;i<banned.length();i++){String uid=banned.optString(i);
                        LinearLayout r=new LinearLayout(ctx);r.setOrientation(LinearLayout.HORIZONTAL);r.setPadding(16,10,16,10);
                        TextView tv=new TextView(ctx);tv.setText(uid);tv.setTextSize(15);tv.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));r.addView(tv);
                        Button ub=new Button(ctx);ub.setText(R.string.unban);ub.setTextSize(12);String t=uid;
                        ub.setOnClickListener(v->dm.unbanMember(roomId,t,new DataManager.Callback<Boolean>(){@Override public void onResult(Boolean ok){showBlacklist(ctx,dm,roomId);}@Override public void onError(String e){}}));
                        r.addView(ub);l.addView(r);}
                    new AlertDialog.Builder(ctx).setTitle(R.string.blacklist).setView(l).show();
                }catch(Exception ignored){}
            }
            @Override public void onError(String e){}
        });
    }
}
