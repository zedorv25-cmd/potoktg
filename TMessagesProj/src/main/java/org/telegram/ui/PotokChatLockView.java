package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;

/**
 * Полноэкранный оверлей ввода ОБЩЕГО пароля для защищённого чата (см.
 * PotokChatLock). Намеренно НЕ является подклассом/обёрткой над
 * PasscodeView.java (системный экран блокировки всего приложения) — тот файл
 * тяжело завязан на глобальный лок-цикл (LaunchActivity, delegate, анимация
 * иконки при разлочке и т.п.), трогать/переиспользовать его напрямую для
 * задачи "пароль на один чат" рискованно (см. регрессию таббара в этом же
 * проекте — хрупкие места трогать только аддитивно). Здесь вместо этого —
 * независимая, простая копия визуального стиля: тёмный фон, анимация замка,
 * поле ввода, кнопка "Готово".
 *
 * Использование: добавляется как child view поверх contentView в
 * ChatActivity (см. createView), изначально GONE. Показывается в onResume,
 * если чат в списке защищённых (PotokChatLock.isLocked). Скрывается только
 * при верном пароле.
 */
public class PotokChatLockView extends FrameLayout {

    public interface Delegate {
        void onUnlocked();
    }

    private final RLottieImageView imageView;
    private final TextView titleView;
    private final EditTextBoldCursor passwordEditText;
    private final TextView errorView;
    private Delegate delegate;

    public PotokChatLockView(Context context) {
        super(context);

        setBackgroundColor(0xff1c1c1e);
        setClickable(true);
        setFocusableInTouchMode(true);

        imageView = new RLottieImageView(context);
        imageView.setAnimation(R.raw.passcode_lock, 58, 58);
        imageView.setAutoRepeat(false);
        addView(imageView, LayoutHelper.createFrame(58, 58, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 90, 0, 0));

        titleView = new TextView(context);
        titleView.setTextColor(0xffffffff);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        titleView.setGravity(Gravity.CENTER_HORIZONTAL);
        titleView.setText(LocaleController.getString(R.string.PotokChatLockedTitle));
        addView(titleView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 40, 170, 40, 0));

        passwordEditText = new EditTextBoldCursor(context);
        passwordEditText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        passwordEditText.setTextColor(0xffffffff);
        passwordEditText.setCursorColor(0xffffffff);
        passwordEditText.setCursorSize(dp(24));
        passwordEditText.setMaxLines(1);
        passwordEditText.setLines(1);
        passwordEditText.setSingleLine(true);
        passwordEditText.setGravity(Gravity.CENTER_HORIZONTAL);
        passwordEditText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordEditText.setTypeface(android.graphics.Typeface.DEFAULT);
        passwordEditText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        passwordEditText.setBackgroundDrawable(null);
        addView(passwordEditText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 60, 230, 60, 0));
        passwordEditText.setOnEditorActionListener((textView, i, keyEvent) -> {
            if (i == EditorInfo.IME_ACTION_DONE) {
                checkPassword();
                return true;
            }
            return false;
        });

        errorView = new TextView(context);
        errorView.setTextColor(0xffff6666);
        errorView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        errorView.setGravity(Gravity.CENTER_HORIZONTAL);
        errorView.setAlpha(0f);
        errorView.setText(LocaleController.getString(R.string.PotokChatLockWrongPassword));
        addView(errorView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 40, 280, 40, 0));

        setVisibility(GONE);
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    /**
     * Показать оверлей поверх чата. Вызывается из ChatActivity.onResume, если
     * dialogId защищён и ещё не разблокирован в рамках этого открытия.
     */
    public void show() {
        setVisibility(VISIBLE);
        setTranslationX(0);
        setAlpha(1f);
        passwordEditText.setText("");
        errorView.setAlpha(0f);
        AndroidUtilities.runOnUIThread(() -> {
            passwordEditText.requestFocus();
            AndroidUtilities.showKeyboard(passwordEditText);
        }, 100);
        bringToFront();
    }

    public void hide() {
        AndroidUtilities.hideKeyboard(passwordEditText);
        setVisibility(GONE);
    }

    private void checkPassword() {
        String password = passwordEditText.getText().toString();
        if (password.length() == 0) {
            return;
        }
        if (PotokChatLock.checkPassword(password)) {
            errorView.setAlpha(0f);
            hide();
            if (delegate != null) {
                delegate.onUnlocked();
            }
        } else {
            errorView.animate().alpha(1f).setDuration(150).start();
            AndroidUtilities.shakeView(passwordEditText);
            passwordEditText.setText("");
        }
    }

    @Override
    public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
        // Оверлей полностью перехватывает касания, чтобы под ним нельзя было
        // взаимодействовать с содержимым чата, пока не введён верный пароль.
        return getVisibility() == VISIBLE;
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (getVisibility() == VISIBLE && event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
            // Назад с экрана блокировки не должен открывать содержимое чата -
            // обрабатываем сами (сворачиваем клавиатуру), не даём событию уйти
            // дальше в ChatActivity.
            AndroidUtilities.hideKeyboard(passwordEditText);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
