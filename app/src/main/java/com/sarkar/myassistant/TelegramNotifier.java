package com.sarkar.myassistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TelegramNotifier integrates the hardcoded Telegram Bot API to send automated
 * status alerts (app start, username registration, call initiation, and diagnostics)
 * to the admin chat without blocking offline P2P operations.
 */
public final class TelegramNotifier {

    private static final String TAG = "TelegramNotifier";

    // Hardcoded Telegram Bot API Token as requested
    public static final String BOT_TOKEN = "8839310378:AAHyFTE4SFwJ5AxFdRFIG1f_w2detVfNXBU";
    private static final String BASE_URL = "https://api.telegram.org/bot" + BOT_TOKEN;

    private static final String PREFS_NAME = "my_assistant_prefs";
    private static final String KEY_USERNAME = "registered_username";
    private static final String KEY_ADMIN_CHAT_ID = "telegram_admin_chat_id";

    private static final ExecutorService telegramExecutor = Executors.newSingleThreadExecutor();

    public interface StatusCallback {
        void onResult(boolean success, String statusMessage);
    }

    private TelegramNotifier() {}

    public static String getRegisteredUsername(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_USERNAME, Build.MODEL);
    }

    public static void saveRegisteredUsername(Context context, String username) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_USERNAME, username).apply();
    }

    public static String getAdminChatId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_ADMIN_CHAT_ID, "");
    }

    public static void saveAdminChatId(Context context, String chatId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_ADMIN_CHAT_ID, chatId == null ? "" : chatId.trim()).apply();
    }

    /**
     * Sends an automated status alert message via Telegram HTTP API.
     * If no Admin Chat ID is saved yet, attempts to auto-discover it via /getUpdates first.
     */
    public static void sendStatusAlert(
            Context context,
            String eventTitle,
            String details,
            StatusCallback callback
    ) {
        final Context appCtx = context.getApplicationContext();
        telegramExecutor.execute(() -> {
            try {
                String chatId = getAdminChatId(appCtx);
                if (chatId.isEmpty()) {
                    chatId = discoverChatIdSync();
                    if (chatId != null && !chatId.isEmpty()) {
                        saveAdminChatId(appCtx, chatId);
                    }
                }

                if (chatId == null || chatId.isEmpty()) {
                    String msg = "Telegram Bot ready (Send /start to bot or set Admin Chat ID)";
                    Log.w(TAG, msg);
                    if (callback != null) callback.onResult(false, msg);
                    return;
                }

                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
                String username = getRegisteredUsername(appCtx);
                String text = "📡 *My Assistant Alert*\n"
                        + "*Event:* " + escapeMarkdown(eventTitle) + "\n"
                        + "*User:* " + escapeMarkdown(username) + " (" + escapeMarkdown(Build.MODEL) + ")\n"
                        + "*Time:* " + timestamp + "\n"
                        + "*Details:* " + escapeMarkdown(details);

                boolean sent = postSendMessageSync(chatId, text);
                if (callback != null) {
                    callback.onResult(sent, sent ? "Telegram alert sent (" + eventTitle + ")" : "Telegram sendMessage failed");
                }
            } catch (Exception e) {
                Log.w(TAG, "Telegram alert skipped (offline or error): " + e.getMessage());
                if (callback != null) {
                    callback.onResult(false, "Offline / Telegram unreachable");
                }
            }
        });
    }

    /**
     * Auto-discovers the most recent chat ID from Telegram /getUpdates.
     */
    public static void syncAdminChatId(Context context, StatusCallback callback) {
        final Context appCtx = context.getApplicationContext();
        telegramExecutor.execute(() -> {
            try {
                String chatId = discoverChatIdSync();
                if (chatId != null && !chatId.isEmpty()) {
                    saveAdminChatId(appCtx, chatId);
                    if (callback != null) callback.onResult(true, "Synced Admin Chat ID: " + chatId);
                } else {
                    if (callback != null) callback.onResult(false, "No bot messages found. Send /start to your bot on Telegram first.");
                }
            } catch (Exception e) {
                if (callback != null) callback.onResult(false, "Cannot reach Telegram API (No internet)");
            }
        });
    }

    private static String discoverChatIdSync() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(BASE_URL + "/getUpdates?limit=10");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4500);
            conn.setReadTimeout(4500);

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                return null;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            JSONObject root = new JSONObject(sb.toString());
            if (!root.optBoolean("ok", false)) return null;

            JSONArray results = root.optJSONArray("result");
            if (results == null || results.length() == 0) return null;

            for (int i = results.length() - 1; i >= 0; i--) {
                JSONObject update = results.optJSONObject(i);
                if (update == null) continue;
                JSONObject message = update.optJSONObject("message");
                if (message == null) message = update.optJSONObject("channel_post");
                if (message != null) {
                    JSONObject chat = message.optJSONObject("chat");
                    if (chat != null && chat.has("id")) {
                        return String.valueOf(chat.getLong("id"));
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "discoverChatIdSync failed: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private static boolean postSendMessageSync(String chatId, String text) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(BASE_URL + "/sendMessage");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(4500);
            conn.setReadTimeout(4500);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            JSONObject payload = new JSONObject();
            payload.put("chat_id", chatId);
            payload.put("text", text);
            payload.put("parse_mode", "Markdown");

            byte[] out = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(out);
            }

            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            Log.d(TAG, "postSendMessageSync error: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String escapeMarkdown(String s) {
        if (s == null) return "";
        return s.replace("_", "\\_")
                .replace("*", "\\*")
                .replace("`", "\\`");
    }
}
