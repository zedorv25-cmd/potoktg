package org.telegram.ui;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.view.MotionEvent;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

public class PotokDrawerView extends FrameLayout {

    private BaseFragment parentFragment;
    private OnDrawerItemClickListener listener;

    public interface OnDrawerItemClickListener {
        void onItemClick(int id);
    }

    public static final int ID_CONTACTS = 1;
    public static final int ID_CALLS = 2;
    public static final int ID_SAVED = 3;
    public static final int ID_SETTINGS = 4;
    public static final int ID_NEW_GROUP = 5;
    public static final int ID_NEW_CHANNEL = 6;
    public static final int ID_FOLDERS = 7;
    public static final int ID_MY_PROFILE = 8;
    public static final int ID_WALLET = 9;
    public static final int ID_THEME_TOGGLE = 10;
    public static final int ID_SWITCH_ACCOUNT = 11;

    private View themeToggleButton;

    /**
     * Векторная стрелка-шеврон вниз тем же подходом, что ArrowUpView/PlayTriangleView
     * из прошлых сессий: собственный Path вместо растровой иконки, не мутнеет на любом dpi.
     */
    private static class ChevronDownView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        ChevronDownView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(AndroidUtilities.dp(1.6f));
            paint.setColor(Color.WHITE);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            path.reset();
            float pad = AndroidUtilities.dp(3);
            path.moveTo(pad, h / 3f);
            path.lineTo(w / 2f, h - pad);
            path.lineTo(w - pad, h / 3f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawPath(path, paint);
        }
    }

    private static View createHeaderIconButton(Context context, int iconRes) {
        ImageView button = new ImageView(context);
        button.setImageResource(iconRes);
        button.setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
        button.setBackground(Theme.createSelectorDrawable(0x33ffffff, Theme.RIPPLE_MASK_CIRCLE_20DP));
        button.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        return button;
    }

    public PotokDrawerView(Context context, BaseFragment fragment) {
        super(context);
        this.parentFragment = fragment;

        setBackgroundColor(Theme.getColor(Theme.key_chats_menuBackground));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        TLRPC.User user = MessagesController.getInstance(UserConfig.selectedAccount)
                .getUser(UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId());

        // Header — квадратная область 1:1 на всю ширину шторки.
        // Высота квадрата принудительно равна измеренной ширине (см. onMeasure ниже).
        FrameLayout header = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int width = MeasureSpec.getSize(widthMeasureSpec);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY));
            }
        };

        boolean hasPhoto = user != null && user.photo != null && user.photo.photo_big != null
                && !(user.photo instanceof TLRPC.TL_userProfilePhotoEmpty);

        if (hasPhoto) {
            // Фото профиля заполняет весь квадрат целиком (cover), не круглый аватар в углу.
            BackupImageView avatarView = new BackupImageView(context);
            avatarView.getImageReceiver().setRoundRadius(0);
            AvatarDrawable avatarDrawable = new AvatarDrawable(user);
            avatarView.setForUserOrChat(user, avatarDrawable);
            header.addView(avatarView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            // Затемнение снизу для читаемости имени/телефона на любом фото.
            View shadow = new View(context) {
                @Override
                protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                    super.onSizeChanged(w, h, oldw, oldh);
                    setBackground(new GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            new int[]{0x00000000, 0x9A000000}));
                }
            };
            header.addView(shadow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 120, Gravity.BOTTOM));
        } else {
            // Нет фото — заливка однотонным цветом, отличающимся от фона остальной шторки.
            boolean dark = Theme.isCurrentThemeDark();
            int baseColor = Theme.getColor(Theme.key_chats_menuBackground);
            int blendTarget = dark ? Color.WHITE : Color.BLACK;
            int placeholderColor = ColorUtils.blendARGB(baseColor, blendTarget, dark ? 0.10f : 0.06f);
            header.setBackgroundColor(placeholderColor);
        }

        // Переключатель темы (солнце) — верх квадрата, справа.
        themeToggleButton = createHeaderIconButton(context, R.drawable.menu_night_mode_24);
        themeToggleButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(ID_THEME_TOGGLE);
            }
        });
        header.addView(themeToggleButton, LayoutHelper.createFrame(40, 40, Gravity.TOP | Gravity.RIGHT, 0, 12, 12, 0));

        // Кнопка избранных сообщений — под переключателем темы.
        View favoritesButton = createHeaderIconButton(context, R.drawable.msg_saved);
        favoritesButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(ID_SAVED);
            }
        });
        header.addView(favoritesButton, LayoutHelper.createFrame(40, 40, Gravity.TOP | Gravity.RIGHT, 0, 60, 12, 0));

        // Низ квадрата, поверх фото: имя + шеврон в одной строке, телефон строкой ниже.
        LinearLayout nameRow = new LinearLayout(context);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView nameView = new TextView(context);
        nameView.setTextSize(1, 16);
        nameView.setTypeface(AndroidUtilities.bold());
        nameView.setTextColor(Color.WHITE);
        nameView.setSingleLine();
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (user != null) {
            nameView.setText(user.first_name + (user.last_name != null ? " " + user.last_name : ""));
        }
        nameRow.addView(nameView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 0, 0));

        FrameLayout chevronTouchArea = new FrameLayout(context);
        chevronTouchArea.setBackground(Theme.createSelectorDrawable(0x33ffffff, Theme.RIPPLE_MASK_CIRCLE_20DP));
        chevronTouchArea.addView(new ChevronDownView(context), LayoutHelper.createFrame(16, 16, Gravity.CENTER));
        chevronTouchArea.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(ID_SWITCH_ACCOUNT);
            }
        });
        nameRow.addView(chevronTouchArea, LayoutHelper.createLinear(32, 32, Gravity.CENTER_VERTICAL, 4, 0, 0, 0));

        header.addView(nameRow, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.BOTTOM, 10, 0, 16, 26));

        TextView phoneView = new TextView(context);
        phoneView.setTextSize(1, 13);
        phoneView.setTextColor(0xCCFFFFFF);
        if (user != null && user.phone != null) {
            phoneView.setText("+" + user.phone);
        }
        header.addView(phoneView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.BOTTOM, 16, 0, 52, 9));

        content.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Профиль и кошелёк
        content.addView(createMenuItem(context, ID_MY_PROFILE, R.drawable.settings_account, LocaleController.getString("MyProfile", R.string.MyProfile)));
        content.addView(createMenuItem(context, ID_WALLET, R.drawable.settings_wallet, LocaleController.getString("PotokWallet", R.string.PotokWallet)));
        content.addView(createDivider(context));

        // Пункты меню
        content.addView(createMenuItem(context, ID_NEW_GROUP, R.drawable.msg_groups, LocaleController.getString("NewGroup", R.string.NewGroup)));
        content.addView(createMenuItem(context, ID_NEW_CHANNEL, R.drawable.msg_channel, LocaleController.getString("NewChannel", R.string.NewChannel)));
        content.addView(createDivider(context));
        content.addView(createMenuItem(context, ID_CONTACTS, R.drawable.msg_contacts, LocaleController.getString("Contacts", R.string.Contacts)));
        content.addView(createMenuItem(context, ID_FOLDERS, R.drawable.msg_folders, LocaleController.getString("Filters", R.string.Filters)));
        content.addView(createMenuItem(context, ID_SAVED, R.drawable.msg_saved, LocaleController.getString("SavedMessages", R.string.SavedMessages)));
        content.addView(createMenuItem(context, ID_CALLS, R.drawable.msg_calls, LocaleController.getString("Calls", R.string.Calls)));
        content.addView(createDivider(context));
        content.addView(createMenuItem(context, ID_SETTINGS, R.drawable.msg_settings_old, LocaleController.getString("Settings", R.string.Settings)));

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(content);
        addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    private View createMenuItem(Context context, int id, int iconRes, String title) {
        FrameLayout item = new FrameLayout(context);
        item.setMinimumHeight(AndroidUtilities.dp(48));
        item.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));

        ImageView icon = new ImageView(context);
        icon.setImageResource(iconRes);
        icon.setColorFilter(Theme.getColor(Theme.key_chats_menuItemIcon), android.graphics.PorterDuff.Mode.MULTIPLY);
        item.addView(icon, LayoutHelper.createFrame(24, 24, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 0, 0, 0));

        TextView text = new TextView(context);
        text.setTextSize(1, 15);
        text.setText(title);
        text.setTextColor(Theme.getColor(Theme.key_chats_menuItemText));
        item.addView(text, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 72, 0, 16, 0));

        item.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(id);
            }
        });

        return item;
    }

    private View createDivider(Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        return divider;
    }

    public void setOnDrawerItemClickListener(OnDrawerItemClickListener listener) {
        this.listener = listener;
    }
    @Override
public boolean onTouchEvent(MotionEvent event) {
    switch (event.getAction()) {
        case MotionEvent.ACTION_DOWN:
            break;
        case MotionEvent.ACTION_MOVE:
            float dy = event.getY() - event.getHistoricalY(0);
            if (dy > 0) {
                setTranslationY(getTranslationY() + dy * 0.15f);
            }
            break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
            animate().translationY(0).setDuration(200).start();
            break;
    }
    return super.onTouchEvent(event);
}
}
