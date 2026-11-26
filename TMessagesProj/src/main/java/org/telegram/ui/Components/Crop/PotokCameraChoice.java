package org.telegram.ui.Components;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;

/**
 * Выбор камеры перед записью видеокружка (typefeed).
 * Меню появляется при долгом нажатии на кнопку записи в режиме видео.
 */
public class PotokCameraChoice {

    private static final String PREFS = "potok_camera_choice";
    private static final String KEY_ASK = "ask";
    private static final String KEY_LAST_FRONT = "last_front";

    /** Камера для ближайшей записи: true = фронтальная, false = задняя, null = как обычно. Читается в InstantCameraView.showCamera(). */
    public static Boolean pendingFront = null;

    public interface Callback {
        void onCameraChosen(boolean front);
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isAskEnabled() {
        return prefs().getBoolean(KEY_ASK, true);
    }

    public static void setAskEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_ASK, enabled).apply();
    }

    public static boolean isLastFront() {
        return prefs().getBoolean(KEY_LAST_FRONT, true);
    }

    public static void setLastFront(boolean front) {
        prefs().edit().putBoolean(KEY_LAST_FRONT, front).apply();
    }

    public static ItemOptions showMenu(BaseFragment fragment, View anchor, Callback callback, Runnable onDismiss) {
        ItemOptions options = ItemOptions.makeOptions(fragment, anchor);
        options.setBlur(true);
        options.add(R.drawable.msg_cancel, getString(R.string.PotokCameraNoAsk), () -> {
            setAskEnabled(false);
            BulletinFactory.of(fragment).createSimpleBulletin(
                    R.raw.info,
                    getString(R.string.PotokCameraAskOff),
                    getString(R.string.PotokCameraAskUndo),
                    () -> setAskEnabled(true)
            ).show();
        });
        options.addGap();
        options.add(R.drawable.msg_camera, getString(R.string.PotokCameraBack), () -> {
            setLastFront(false);
            callback.onCameraChosen(false);
        });
        options.add(R.drawable.msg_openprofile, getString(R.string.PotokCameraFront), () -> {
            setLastFront(true);
            callback.onCameraChosen(true);
        });
        options.setOnDismiss(onDismiss);
        options.show();
        return options;
    }
}
