package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;

import java.util.ArrayList;
import java.util.List;

/**
 * Независимая (не связанная с PasscodeView.java) сетка цифр для ввода
 * 4-значного PIN-кода, визуально в похожем стиле: точки-индикаторы сверху,
 * ниже сетка 1-9 / пусто / 0 / стереть. Используется и в PotokChatLockView
 * (разблокировка чата), и в PotokChatLockDialogs (создание нового PIN).
 *
 * По конструкции фиксированная длина 4 цифры (как классический passcode
 * Telegram) — по вводу 4-й цифры автоматически вызывается
 * Listener.onPinComplete(pin).
 */
public class PotokPinPadView extends LinearLayout {

    public interface Listener {
        void onPinComplete(String pin);
    }

    private static final int PIN_LENGTH = 4;

    private final TextView titleView;
    private final List<View> dots = new ArrayList<>();
    private final LinearLayout dotsContainer;
    private final StringBuilder currentPin = new StringBuilder();
    private Listener listener;

    public PotokPinPadView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);

        titleView = new TextView(context);
        titleView.setTextColor(0xffffffff);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setGravity(Gravity.CENTER_HORIZONTAL);
        addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 20));

        dotsContainer = new LinearLayout(context);
        dotsContainer.setOrientation(HORIZONTAL);
        dotsContainer.setGravity(Gravity.CENTER_HORIZONTAL);
        addView(dotsContainer, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 30));
        for (int i = 0; i < PIN_LENGTH; i++) {
            View dot = new View(context);
            GradientDrawable dotDrawable = new GradientDrawable();
            dotDrawable.setShape(GradientDrawable.OVAL);
            dotDrawable.setColor(0x33ffffff);
            dot.setBackground(dotDrawable);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(12), dp(12));
            lp.setMargins(dp(6), 0, dp(6), 0);
            dotsContainer.addView(dot, lp);
            dots.add(dot);
        }

        LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(VERTICAL);
        grid.setGravity(Gravity.CENTER_HORIZONTAL);
        addView(grid, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        int[][] rows = {{1, 2, 3}, {4, 5, 6}, {7, 8, 9}, {-1, 0, -2}};
        for (int[] rowDigits : rows) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_HORIZONTAL);
            grid.addView(row, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            for (int digit : rowDigits) {
                row.addView(createButton(context, digit), LayoutHelper.createLinear(72, 72, 10, 10, 10, 10));
            }
        }
    }

    private View createButton(Context context, int digit) {
        if (digit == -1) {
            // пустая ячейка (левый нижний угол, как место под отпечаток в
            // оригинале - нам он не нужен, просто оставляем пустое место
            // для симметрии сетки)
            View empty = new View(context);
            return empty;
        }
        FrameLayout button = new FrameLayout(context);
        button.setBackground(org.telegram.ui.ActionBar.Theme.createSimpleSelectorRoundRectDrawable(dp(36), 0x26ffffff, 0x4cffffff));
        ScaleStateListAnimator.apply(button, .1f, 1.5f);

        if (digit == -2) {
            org.telegram.ui.Components.RLottieImageView backspaceIcon = new org.telegram.ui.Components.RLottieImageView(context);
            backspaceIcon.setImageResource(R.drawable.filled_clear);
            backspaceIcon.setColorFilter(0xffffffff);
            button.addView(backspaceIcon, LayoutHelper.createFrame(24, 24, Gravity.CENTER));
            button.setOnClickListener(v -> {
                if (currentPin.length() > 0) {
                    currentPin.deleteCharAt(currentPin.length() - 1);
                    updateDots();
                }
            });
        } else {
            TextView digitView = new TextView(context);
            digitView.setTextColor(0xffffffff);
            digitView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
            digitView.setGravity(Gravity.CENTER);
            digitView.setText(String.valueOf(digit));
            button.addView(digitView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
            button.setOnClickListener(v -> {
                if (currentPin.length() < PIN_LENGTH) {
                    currentPin.append(digit);
                    updateDots();
                    if (currentPin.length() == PIN_LENGTH) {
                        String pin = currentPin.toString();
                        AndroidUtilities.runOnUIThread(() -> {
                            if (listener != null) {
                                listener.onPinComplete(pin);
                            }
                        }, 120);
                    }
                }
            });
        }
        return button;
    }

    private void updateDots() {
        for (int i = 0; i < dots.size(); i++) {
            GradientDrawable d = (GradientDrawable) dots.get(i).getBackground();
            d.setColor(i < currentPin.length() ? 0xffffffff : 0x33ffffff);
        }
    }

    public void setTitle(CharSequence text) {
        titleView.setText(text);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Сброс введённых точек после ошибки/успеха, с лёгкой тряской при ошибке. */
    public void reset(boolean shake) {
        currentPin.setLength(0);
        updateDots();
        if (shake) {
            AndroidUtilities.shakeView(dotsContainer);
        }
    }
}
