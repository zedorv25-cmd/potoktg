package org.telegram.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import static org.telegram.messenger.AndroidUtilities.dp;

/**
 * Диалоги установки/снятия пароля на конкретный чат (пункт меню трёх точек
 * в ChatActivity). Пароль общий на все защищённые чаты (PotokChatLock).
 */
public class PotokChatLockDialogs {

    /**
     * Если общий пароль уже установлен ранее — просто ставит защиту на этот
     * чат без повторного ввода пароля (пароль один на все чаты). Если пароля
     * ещё нет вообще — сначала просит его придумать.
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
        showCreatePasswordDialog(fragment, currentAccount, dialogId, onSuccess);
    }

    private static void showCreatePasswordDialog(BaseFragment fragment, int currentAccount, long dialogId, Runnable onSuccess) {
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }

        final EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setBackground(null);
        editText.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.PotokSetChatPassword));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        builder.setView(linearLayout);

        final TextView message = new TextView(context);
        message.setText(LocaleController.getString(R.string.PotokEnterNewChatPassword));
        message.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        message.setPadding(dp(23), dp(12), dp(23), dp(6));
        message.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        linearLayout.addView(message, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setMaxLines(1);
        editText.setLines(1);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        editText.setHint(LocaleController.getString(R.string.PotokChatPasswordHint));
        editText.setGravity(Gravity.LEFT | Gravity.TOP);
        editText.setSingleLine(true);
        editText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setCursorSize(dp(20));
        editText.setCursorWidth(1.5f);
        editText.setPadding(0, dp(4), 0, 0);
        linearLayout.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, Gravity.TOP | Gravity.LEFT, 24, 6, 24, 0));

        builder.setPositiveButton(LocaleController.getString(R.string.PotokSetChatPassword), (dialog, which) -> {
            String password = editText.getText().toString();
            if (password.length() < 4) {
                showSimpleBulletin(fragment, LocaleController.getString(R.string.PotokChatPasswordTooShort));
                return;
            }
            PotokChatLock.setPassword(password);
            PotokChatLock.lockDialog(currentAccount, dialogId);
            showSimpleBulletin(fragment, LocaleController.getString(R.string.PotokChatPasswordSet));
            if (onSuccess != null) {
                onSuccess.run();
            }
        });

        final AlertDialog alertDialog = builder.create();
        alertDialog.setOnShowListener(dialog -> AndroidUtilities.runOnUIThread(() -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        }));
        fragment.showDialog(alertDialog);
        editText.setOnEditorActionListener((textView, i, keyEvent) -> {
            if (i == EditorInfo.IME_ACTION_DONE) {
                alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
                return true;
            }
            return false;
        });
    }

    /**
     * Снятие пароля с одного конкретного чата (не трогает пароль остальных
     * защищённых чатов). Повторный ввод пароля не требуется — пользователь
     * уже прошёл проверку, раз находится внутри открытого чата.
     */
    public static void showUnlockChatConfirm(BaseFragment fragment, int currentAccount, long dialogId, Runnable onSuccess) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.PotokRemoveChatPassword));
        builder.setPositiveButton(LocaleController.getString(R.string.PotokRemoveChatPassword), (dialog, which) -> {
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
