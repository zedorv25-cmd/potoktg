package org.telegram.ui;

import android.app.Dialog;
import android.content.Context;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;

/**
 * Диалоги установки/снятия пароля на конкретный чат (пункт меню трёх точек
 * в ChatActivity). Пароль общий на все защищённые чаты (PotokChatLock).
 * Ввод — сетка цифр (PotokPinPadView), не клавиатура.
 */
public class PotokChatLockDialogs {

    /**
     * Если общий пароль уже установлен ранее — просто ставит защиту на этот
     * чат без повторного ввода пароля (пароль один на все чаты). Если пароля
     * ещё нет вообще — сначала просит его придумать (два шага: придумать +
     * повторить, чтобы не ошибиться, ввод не показывается на экране).
     */
    public static void showLockChatFlow(BaseFragment fragment, int currentAccount, long dialogId, Runnable onSuccess) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        if (PotokChatLock.hasPassword()) {
            PotokChatLock.lockDialog(currentAccount, dialogId);
            showSimpleBulletin(fragment, LocaleController.getString(R.string.PotokChatPasswordSet));
            if (onSuccess != null) {
                onSuccess.run();
            }
            return;
        }
        showCreatePinDialog(fragment, currentAccount, dialogId, onSuccess);
    }

    private static void showCreatePinDialog(BaseFragment fragment, int currentAccount, long dialogId, Runnable onSuccess) {
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }

        Dialog dialog = new Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
        dialog.setCancelable(true);

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(0xff1c1c1e);

        RLottieImageView imageView = new RLottieImageView(context);
        imageView.setAnimation(R.raw.passcode_lock, 58, 58);
        imageView.setAutoRepeat(false);
        root.addView(imageView, LayoutHelper.createFrame(58, 58, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 90, 0, 0));

        PotokPinPadView pinPadView = new PotokPinPadView(context);
        pinPadView.setTitle(LocaleController.getString(R.string.PotokEnterNewChatPassword));
        root.addView(pinPadView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 175, 0, 0));

        dialog.setContentView(root);

        final String[] firstPin = new String[1];
        pinPadView.setListener(pin -> {
            if (firstPin[0] == null) {
                firstPin[0] = pin;
                pinPadView.reset(false);
                pinPadView.setTitle(LocaleController.getString(R.string.PotokRepeatChatPassword));
            } else {
                if (pin.equals(firstPin[0])) {
                    PotokChatLock.setPassword(pin);
                    PotokChatLock.lockDialog(currentAccount, dialogId);
                    dialog.dismiss();
                    showSimpleBulletin(fragment, LocaleController.getString(R.string.PotokChatPasswordSet));
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                } else {
                    // пароли не совпали - начинаем сначала, с первого шага
                    firstPin[0] = null;
                    pinPadView.reset(true);
                    pinPadView.setTitle(LocaleController.getString(R.string.PotokChatPasswordMismatch));
                    org.telegram.messenger.AndroidUtilities.runOnUIThread(() ->
                            pinPadView.setTitle(LocaleController.getString(R.string.PotokEnterNewChatPassword)), 1200);
                }
            }
        });

        dialog.show();
    }

    /**
     * Снятие пароля с одного конкретного чата (не трогает пароль остальных
     * защищённых чатов). Повторный ввод PIN не требуется — пользователь уже
     * прошёл проверку, раз находится внутри открытого чата.
     */
    public static void showUnlockChatConfirm(BaseFragment fragment, int currentAccount, long dialogId, Runnable onSuccess) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.PotokRemoveChatPassword));
        builder.setPositiveButton(LocaleController.getString(R.string.PotokRemoveChatPassword), (d, which) -> {
            PotokChatLock.unlockDialog(currentAccount, dialogId);
            showSimpleBulletin(fragment, LocaleController.getString(R.string.PotokChatPasswordRemoved));
            if (onSuccess != null) {
                onSuccess.run();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    private static void showSimpleBulletin(BaseFragment fragment, String text) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, text).show();
    }
}
