package com.example.boss;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class CloudHelper {

    private String baseUrl;

    private static final String API_PREFIX = "/boss-timer";

    public CloudHelper(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    private String request(String method, String path, String body) throws Exception {
        URL url = new URL(baseUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        if (body != null) {
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();
        }
        int code = conn.getResponseCode();
        if (code >= 200 && code < 300) {
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();
            return sb.toString();
        } else {
            String errorBody = "";
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                br.close();
                errorBody = sb.toString();
            } catch (Exception e) { Log.e("CloudHelper", "read error", e); }
            Log.e("CloudHelper", "HTTP " + code + " " + path + " -> " + errorBody);
            throw new Exception("HTTP " + code + ": " + errorBody);
        }
    }

    public String registerUser(String userName) throws Exception {
        String body = "{\"name\":\"" + escapeJson(userName) + "\"}";
        return request("POST", API_PREFIX + "/registerUser", body);
    }

    public String createRoom(String userId, String userName, String roomName, String password, String expansionCode) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"userId\":\"").append(escapeJson(userId))
          .append("\",\"userName\":\"").append(escapeJson(userName))
          .append("\",\"roomName\":\"").append(escapeJson(roomName)).append("\"");
        if (password != null && !password.isEmpty()) {
            sb.append(",\"password\":\"").append(escapeJson(password)).append("\"");
        }
        if (expansionCode != null && !expansionCode.isEmpty()) {
            sb.append(",\"expansionCode\":\"").append(escapeJson(expansionCode)).append("\"");
        }
        sb.append("}");
        return request("POST", API_PREFIX + "/createRoom", sb.toString());
    }

    public String joinRoom(String roomId, String userId, String userName, String password) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"roomId\":\"").append(escapeJson(roomId))
          .append("\",\"userId\":\"").append(escapeJson(userId))
          .append("\",\"userName\":\"").append(escapeJson(userName)).append("\"");
        if (password != null && !password.isEmpty()) {
            sb.append(",\"password\":\"").append(escapeJson(password)).append("\"");
        }
        sb.append("}");
        return request("POST", API_PREFIX + "/joinRoom", sb.toString());
    }

    public String getRoomInfo(String roomId, String userId) throws Exception {
        return request("GET", API_PREFIX + "/getRoomInfo?roomId=" + encodeURI(roomId) + "&userId=" + encodeURI(userId), null);
    }

    public String updateMemberRole(String roomId, String ownerUserId, String targetUserId, String role, String permissionsJson) throws Exception {
        String body = "{\"roomId\":\"" + escapeJson(roomId) + "\",\"ownerUserId\":\"" + escapeJson(ownerUserId) + "\",\"targetUserId\":\"" + escapeJson(targetUserId) + "\",\"role\":\"" + escapeJson(role) + "\",\"permissions\":" + permissionsJson + "}";
        return request("POST", API_PREFIX + "/updateMemberRole", body);
    }

    public String getBosses(String roomId, String userId) throws Exception {
        return request("GET", API_PREFIX + "/getBosses?roomId=" + encodeURI(roomId) + "&userId=" + encodeURI(userId), null);
    }

    public String addBoss(String roomId, String userId, String bossJson) throws Exception {
        String body = "{\"roomId\":\"" + escapeJson(roomId) + "\",\"userId\":\"" + escapeJson(userId) + "\",\"boss\":" + bossJson + "}";
        return request("POST", API_PREFIX + "/addBoss", body);
    }

    public String updateBoss(String roomId, String userId, String docId, String bossJson) throws Exception {
        String body = "{\"roomId\":\"" + escapeJson(roomId) + "\",\"userId\":\"" + escapeJson(userId) + "\",\"docId\":\"" + escapeJson(docId) + "\",\"boss\":" + bossJson + "}";
        return request("POST", API_PREFIX + "/updateBoss", body);
    }

    public String deleteBoss(String roomId, String userId, String docId) throws Exception {
        String body = "{\"roomId\":\"" + escapeJson(roomId) + "\",\"userId\":\"" + escapeJson(userId) + "\",\"docId\":\"" + escapeJson(docId) + "\"}";
        return request("POST", API_PREFIX + "/deleteBoss", body);
    }

    public String getRoomVersion(String roomId) throws Exception {
        return request("GET", API_PREFIX + "/getRoomVersion?roomId=" + encodeURI(roomId), null);
    }

    public String setRoomPassword(String roomId, String userId, String password) throws Exception {
        String body = "{\"roomId\":\"" + escapeJson(roomId) + "\",\"userId\":\"" + escapeJson(userId) + "\"";
        if (password != null && !password.isEmpty()) {
            body += ",\"password\":\"" + escapeJson(password) + "\"";
        }
        body += "}";
        return request("POST", API_PREFIX + "/setRoomPassword", body);
    }

    public String getRoomPassword(String roomId, String userId) throws Exception {
        return request("GET", API_PREFIX + "/getRoomPassword?roomId=" + encodeURI(roomId) + "&userId=" + encodeURI(userId), null);
    }

    public String getMyRooms(String userId) throws Exception {
        return request("GET", API_PREFIX + "/getMyRooms?userId=" + encodeURI(userId), null);
    }

    public String deleteRoom(String roomId, String userId) throws Exception {
        String body = "{\"roomId\":\"" + escapeJson(roomId) + "\",\"userId\":\"" + escapeJson(userId) + "\"}";
        return request("POST", API_PREFIX + "/deleteRoom", body);
    }

    public String updateRoomInfo(String roomId, String userId, String roomName, String password) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"roomId\":\"").append(escapeJson(roomId))
          .append("\",\"userId\":\"").append(escapeJson(userId)).append("\"");
        if (roomName != null) sb.append(",\"roomName\":\"").append(escapeJson(roomName)).append("\"");
        if (password != null) sb.append(",\"password\":\"").append(escapeJson(password)).append("\"");
        sb.append("}");
        return request("POST", API_PREFIX + "/updateRoomInfo", sb.toString());
    }

    public String verifyAuth(String code) throws Exception {
        return request("POST", API_PREFIX + "/verifyAuth", "{\"code\":\"" + escapeJson(code) + "\"}");
    }

    public String updateMyName(String roomId, String userId, String userName, String password) throws Exception {
        String body = "{\"roomId\":\"" + escapeJson(roomId) + "\",\"userId\":\"" + escapeJson(userId) + "\",\"userName\":\"" + escapeJson(userName) + "\",\"password\":\"" + escapeJson(password != null ? password : "") + "\"}";
        return request("POST", API_PREFIX + "/joinRoom", body);
    }

    public String kickMember(String roomId, String ownerUserId, String targetUserId) throws Exception {
        String body = "{\"roomId\":\"" + escapeJson(roomId) + "\",\"ownerUserId\":\"" + escapeJson(ownerUserId) + "\",\"targetUserId\":\"" + escapeJson(targetUserId) + "\"}";
        return request("POST", API_PREFIX + "/kickMember", body);
    }

    public String getBannedList(String roomId, String userId) throws Exception {
        return request("GET", API_PREFIX + "/getBannedList?roomId=" + encodeURI(roomId) + "&userId=" + encodeURI(userId), null);
    }

    public String unbanMember(String roomId, String userId, String targetUserId) throws Exception {
        String body = "{\"roomId\":\"" + escapeJson(roomId) + "\",\"userId\":\"" + escapeJson(userId) + "\",\"targetUserId\":\"" + escapeJson(targetUserId) + "\"}";
        return request("POST", API_PREFIX + "/unbanMember", body);
    }

    public String getLogs(String roomId, String userId) throws Exception {
        return request("GET", API_PREFIX + "/getLogs?roomId=" + encodeURI(roomId) + "&userId=" + encodeURI(userId), null);
    }

    public String addLog(String roomId, String userId, String userName, String action, String target, String extraJson) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"roomId\":\"").append(escapeJson(roomId))
          .append("\",\"userId\":\"").append(escapeJson(userId))
          .append("\",\"userName\":\"").append(escapeJson(userName))
          .append("\",\"action\":\"").append(escapeJson(action))
          .append("\",\"target\":\"").append(escapeJson(target != null ? target : ""))
          .append("\",\"time\":").append(System.currentTimeMillis());
        if (extraJson != null && !extraJson.isEmpty()) {
            sb.append(",").append(extraJson);
        }
        sb.append("}");
        return request("POST", API_PREFIX + "/addLog", sb.toString());
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private String encodeURI(String s) {
        if (s == null) return "";
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
