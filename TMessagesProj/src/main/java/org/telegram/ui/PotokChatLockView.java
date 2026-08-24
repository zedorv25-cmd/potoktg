package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;

/**
 * Полноэкранный оверлей ввода ОБЩЕГО PIN-кода для защищённого чата (см.
 * PotokChatLock). Намеренно НЕ является подклассом/обёрткой над
 * PasscodeView.java (системный экран блокировки всего приложения) — тот файл
 * тяжело завязан на глобальный лок-цикл (LaunchActivity, delegate, анимация
 * иконки при разлочке и т.п.), трогать/переиспользовать его напрямую для
 * задачи "пароль на один чат" рискованно (см. регрессию таббара в этом же
 * проекте — хрупкие места трогать только аддитивно). Здесь вместо этого —
 * независимая, простая копия визуального стиля: тёмный фон, анимация замка,
 * сетка цифр (PotokPinPadView) вместо клавиатуры.
 *
 * Использование: добавляется как child view поверх contentView в
 * ChatActivity (см. createView), изначально GONE. Показывается в onResume,
 * если чат в списке защищённых (PotokChatLock.isLocked). Скрывается только
 * при верном PIN.
 */
public class PotokChatLockView extends FrameLayout {

    public interface Delegate {
        void onUnlocked();
    }

    private final RLottieImageView imageView;
    private final PotokPinPadView pinPadView;
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

        pinPadView = new PotokPinPadView(context);
        pinPadView.setTitle(LocaleController.getString(R.string.PotokChatLockedTitle));
        pinPadView.setListener(pin -> {
            if (PotokChatLock.checkPassword(pin)) {
                pinPadView.reset(false);
                hide();
                if (delegate != null) {
                    delegate.onUnlocked();
                }
            } else {
                pinPadView.reset(true);
            }
        });
        addView(pinPadView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 175, 0, 0));

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
        pinPadView.reset(false);
        bringToFront();
    }

    public void hide() {
        setVisibility(GONE);
    }

    @Override
    public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
        // Оверлей полностью перехватывает касания, чтобы под ним нельзя было
        // взаимодействовать с содержимым чата, пока не введён верный PIN.
        return getVisibility() == VISIBLE;
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (getVisibility() == VISIBLE && event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
            // Назад с экрана блокировки не должен открывать содержимое чата -
            // обрабатываем сами, не даём событию уйти дальше в ChatActivity.
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
