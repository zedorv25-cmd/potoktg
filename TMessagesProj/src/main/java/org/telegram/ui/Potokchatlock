package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;

import java.util.HashSet;
import java.util.Set;

/**
 * Пароль на отдельные чаты (не путать с системным passcode всего приложения,
 * см. SharedConfig.passcodeHash/passcodeSalt — это полностью независимый
 * механизм, ничего общего с ним по хранению не имеет).
 *
 * Текущая версия (по решению пользователя): один общий пароль на ВСЕ чаты,
 * помеченные как защищённые. Список защищённых dialogId и хеш+соль пароля
 * хранятся локально на устройстве в отдельном SharedPreferences-файле.
 *
 * Хеширование — та же схема, что и у системного passcode (соль 16 байт +
 * SHA-256 от соль+пароль+соль), см. SharedConfig.checkPasscode для образца.
 * Поля СВОИ, отдельные от passcodeHash/passcodeSalt.
 */
public class PotokChatLock {

    private static final String PREFS_NAME = "potok_chat_lock";
    private static final String KEY_HASH = "lockHash";
    private static final String KEY_SALT = "lockSalt";
    private static final String KEY_LOCKED_IDS = "lockedDialogIds";

    private static SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Есть ли вообще установленный общий пароль (независимо от того, есть ли
     * уже хоть один защищённый им чат).
     */
    public static boolean hasPassword() {
        return getPrefs().getString(KEY_HASH, "").length() > 0;
    }

    public static boolean isLocked(long dialogId) {
        Set<String> ids = getPrefs().getStringSet(KEY_LOCKED_IDS, null);
        return ids != null && ids.contains(String.valueOf(dialogId));
    }

    private static byte[] hash(String password, byte[] salt) throws Exception {
        byte[] passwordBytes = password.getBytes("UTF-8");
        byte[] bytes = new byte[32 + passwordBytes.length];
        System.arraycopy(salt, 0, bytes, 0, 16);
        System.arraycopy(passwordBytes, 0, bytes, 16, passwordBytes.length);
        System.arraycopy(salt, 0, bytes, passwordBytes.length + 16, 16);
        return Utilities.computeSHA256(bytes, 0, bytes.length);
    }

    public static boolean checkPassword(String password) {
        if (password == null) {
            return false;
        }
        SharedPreferences prefs = getPrefs();
        String storedHash = prefs.getString(KEY_HASH, "");
        String storedSaltString = prefs.getString(KEY_SALT, "");
        if (storedHash.length() == 0 || storedSaltString.length() == 0) {
            return false;
        }
        try {
            byte[] salt = android.util.Base64.decode(storedSaltString, android.util.Base64.DEFAULT);
            String hash = Utilities.bytesToHex(hash(password, salt));
            return storedHash.equals(hash);
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    /**
     * Устанавливает (или переустанавливает) общий пароль. Не трогает список
     * уже защищённых чатов — только сам пароль.
     */
    public static void setPassword(String password) {
        if (password == null || password.length() == 0) {
            return;
        }
        try {
            byte[] salt = new byte[16];
            Utilities.random.nextBytes(salt);
            String hash = Utilities.bytesToHex(hash(password, salt));
            getPrefs().edit()
                    .putString(KEY_HASH, hash)
                    .putString(KEY_SALT, android.util.Base64.encodeToString(salt, android.util.Base64.DEFAULT))
                    .apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * Ставит защиту на конкретный чат (пароль должен быть уже установлен
     * отдельным вызовом setPassword, если ещё не был). Заодно выключает
     * превью сообщений этого чата в уведомлениях через уже существующий
     * нативный механизм Telegram (content_preview_<dialogId> в
     * getNotificationsSettings, см. NotificationsController.java) — отдельно
     * этот механизм трогать не нужно.
     */
    public static void lockDialog(int currentAccount, long dialogId) {
        SharedPreferences prefs = getPrefs();
        Set<String> ids = new HashSet<>(prefs.getStringSet(KEY_LOCKED_IDS, new HashSet<>()));
        ids.add(String.valueOf(dialogId));
        prefs.edit().putStringSet(KEY_LOCKED_IDS, ids).apply();
        try {
            MessagesController.getNotificationsSettings(currentAccount)
                    .edit()
                    .putBoolean("content_preview_" + dialogId, false)
                    .apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * Снимает защиту с чата и возвращает превью уведомлений к обычному
     * поведению (true — как у остальных, непащещённых чатов).
     */
    public static void unlockDialog(int currentAccount, long dialogId) {
        SharedPreferences prefs = getPrefs();
        Set<String> ids = new HashSet<>(prefs.getStringSet(KEY_LOCKED_IDS, new HashSet<>()));
        ids.remove(String.valueOf(dialogId));
        prefs.edit().putStringSet(KEY_LOCKED_IDS, ids).apply();
        try {
            MessagesController.getNotificationsSettings(currentAccount)
                    .edit()
                    .putBoolean("content_preview_" + dialogId, true)
                    .apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
