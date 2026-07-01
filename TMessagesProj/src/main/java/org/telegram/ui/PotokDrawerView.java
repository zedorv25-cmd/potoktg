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
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

public class PotokDrawerView extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    private static final String LOG_TAG = "DRAWER";
    private final long createdAtMs = System.currentTimeMillis();

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
    private LinearLayout accountsContainer;
    private View chevronView;
    private boolean accountsExpanded = false;
    private ScrollView scrollViewRef;
    private LinearLayout contentRef;
    private int lastAppliedTopInset = -1;
    private android.animation.ValueAnimator accountsHeightAnimator;

    // Фикс "серого квадрата": ссылки на элементы шапки, чтобы можно было обновить их
    // (фото/имя/номер) в момент открытия шторки, а не только один раз при создании view.
    private BackupImageView avatarView;
    private FrameLayout headerRef;
    private TextView nameViewRef;
    private TextView phoneViewRef;

    // Фикс 1: список коллбэков перекраски — вызывается целиком при смене темы, чтобы
    // все элементы шторки (фон, иконки, текст, разделители) подхватили новые цвета
    // без пересоздания всей вьюхи.
    private final java.util.List<Runnable> themeUpdaters = new java.util.ArrayList<>();

    // Фикс 1: шеврон округлённый — уменьшен угол за счёт поднятия вершины (h/2.5f вместо h-pad),
    // увеличена толщина линии (2.0dp вместо 1.6dp), что даёт более мягкий и менее острый вид.
    private static class ChevronDownView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        ChevronDownView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(AndroidUtilities.dp(2.0f));
            paint.setColor(Color.WHITE);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            path.reset();
            float pad = AndroidUtilities.dp(2);
            // Вершина шеврона поднята до h*0.65f вместо h-pad для более тупого (не острого) угла
            path.moveTo(pad, h * 0.25f);
            path.lineTo(w / 2f, h * 0.75f);
            path.lineTo(w - pad, h * 0.25f);
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

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int topInset = 0;
        androidx.core.view.WindowInsetsCompat insetsCompat = androidx.core.view.ViewCompat.getRootWindowInsets(this);
        if (insetsCompat != null) {
            topInset = insetsCompat.getInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.systemBars()).top;
        }
        if (topInset != lastAppliedTopInset && contentRef != null) {
            lastAppliedTopInset = topInset;
            contentRef.setPadding(0, topInset, 0, 0);
            PotokDebugLog.log(LOG_TAG, "onMeasure: topInset=" + topInset + "px (применён к content, не к scrollView)");
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    public PotokDrawerView(Context context, BaseFragment fragment) {
        super(context);
        this.parentFragment = fragment;
        PotokDebugLog.log(LOG_TAG, "Конструктор начат, t=0ms");

        setBackgroundColor(Theme.getColor(Theme.key_chats_menuBackground));
        themeUpdaters.add(() -> setBackgroundColor(Theme.getColor(Theme.key_chats_menuBackground)));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        // Фикс 2: берём User из UserConfig, а не из MessagesController.
        // UserConfig загружается с диска первым при старте приложения и всегда содержит
        // актуальные данные аккаунта локально — без ожидания инициализации MessagesController.
        TLRPC.User user = UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser();

        PotokDebugLog.log(LOG_TAG, "user=null? " + (user == null)
                + (user != null ? (", hasPhoto=" + (user.photo != null
                    && !(user.photo instanceof TLRPC.TL_userProfilePhotoEmpty))) : ""));

        // Header — квадратная область 1:1 на всю ширину шторки.
        FrameLayout header = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int width = MeasureSpec.getSize(widthMeasureSpec);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY));
            }
        };

        boolean hasPhoto = user != null && user.photo != null && user.photo.photo_big != null
                && !(user.photo instanceof TLRPC.TL_userProfilePhotoEmpty);

        // Заглушка-цвет всегда как фон — видна пока фото грузится или если фото нет вообще.
        boolean dark = Theme.isCurrentThemeDark();
        int baseColor = Theme.getColor(Theme.key_chats_menuBackground);
        int placeholderColor = ColorUtils.blendARGB(baseColor, dark ? Color.WHITE : Color.BLACK, dark ? 0.10f : 0.06f);
        header.setBackgroundColor(placeholderColor);
        themeUpdaters.add(() -> {
            boolean d = Theme.isCurrentThemeDark();
            int base = Theme.getColor(Theme.key_chats_menuBackground);
            header.setBackgroundColor(ColorUtils.blendARGB(base, d ? Color.WHITE : Color.BLACK, d ? 0.10f : 0.06f));
        });

        // avatarView создаём ВСЕГДА (а не только если hasPhoto), чтобы можно было
        // подставить фото позже через refreshHeaderData(), когда оно догрузится —
        // без пересоздания всей шторки. Пока фото нет, view прозрачный, виден placeholderColor.
        avatarView = new BackupImageView(context);
        avatarView.getImageReceiver().setRoundRadius(0);
        avatarView.getImageReceiver().setDelegate((imageReceiver, set, thumb, memCache) -> {
            long elapsed = System.currentTimeMillis() - createdAtMs;
            PotokDebugLog.log(LOG_TAG, "Фото: set=" + set + ", thumb=" + thumb
                    + ", memCache=" + memCache + ", t=+" + elapsed + "ms");
        });
        header.addView(avatarView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        if (hasPhoto) {
            AvatarDrawable avatarDrawable = new AvatarDrawable(user);
            avatarView.getImageReceiver().setForUserOrChat(user, avatarDrawable, null, true, 0, true);
        }

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

        // Кнопки темы и избранного — верх квадрата, справа.
        themeToggleButton = createHeaderIconButton(context, R.drawable.menu_night_mode_24);
        themeToggleButton.setOnClickListener(v -> {
            PotokDebugLog.log(LOG_TAG, "Клик: тема");
            if (listener != null) listener.onItemClick(ID_THEME_TOGGLE);
        });
        header.addView(themeToggleButton, LayoutHelper.createFrame(40, 40, Gravity.TOP | Gravity.RIGHT, 0, 12, 12, 0));

        View favoritesButton = createHeaderIconButton(context, R.drawable.msg_saved);
        favoritesButton.setOnClickListener(v -> {
            long elapsed = System.currentTimeMillis() - createdAtMs;
            PotokDebugLog.log(LOG_TAG, "Клик: избранное (закладка), t=+" + elapsed + "ms");
            if (listener != null) listener.onItemClick(ID_SAVED);
        });
        header.addView(favoritesButton, LayoutHelper.createFrame(40, 40, Gravity.TOP | Gravity.RIGHT, 0, 60, 12, 0));

        // Имя — снизу слева квадрата.
        TextView nameView = new TextView(context);
        nameView.setTextSize(1, 16);
        nameView.setTypeface(AndroidUtilities.bold());
        nameView.setTextColor(Color.WHITE);
        nameView.setSingleLine();
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (user != null) {
            nameView.setText(user.first_name + (user.last_name != null ? " " + user.last_name : ""));
        }
        header.addView(nameView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.BOTTOM, 16, 0, 60, 28));
        nameViewRef = nameView;

        // Фикс 3: шеврон перенесён в правый угол, выровнен по вертикали с именем.
        // Клик убран отсюда — теперь обрабатывается общей зоной accountToggleTouchArea ниже,
        // это только визуальная иконка.
        FrameLayout chevronTouchArea = new FrameLayout(context);
        chevronView = new ChevronDownView(context);
        chevronTouchArea.addView(chevronView, LayoutHelper.createFrame(18, 18, Gravity.CENTER));
        // Правый угол, по вертикали — на уровне имени (bottom=22, чтобы центр кнопки 36dp
        // совпадал с центром строки имени высотой ~20dp при bottom=28 у nameView).
        header.addView(chevronTouchArea, LayoutHelper.createFrame(36, 36, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 8, 20));

        // Телефон — ниже имени.
        TextView phoneView = new TextView(context);
        phoneView.setTextSize(1, 13);
        phoneView.setTextColor(0xCCFFFFFF);
        if (user != null && user.phone != null) {
            phoneView.setText("+" + user.phone);
        }
        header.addView(phoneView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.BOTTOM, 16, 0, 52, 9));
        phoneViewRef = phoneView;

        // Общая кликабельная зона поверх имени/номера/шеврона — тап в любом месте этой
        // полосы раскрывает список аккаунтов, плюс единый ripple на всю ширину.
        FrameLayout accountToggleTouchArea = new FrameLayout(context);
        accountToggleTouchArea.setBackground(Theme.createSelectorDrawable(0x26ffffff, 2));
        accountToggleTouchArea.setOnClickListener(v -> {
            PotokDebugLog.log(LOG_TAG, "Клик: зона имени/номера/шеврона, t=+" + (System.currentTimeMillis() - createdAtMs) + "ms");
            if (listener != null) listener.onItemClick(ID_SWITCH_ACCOUNT);
        });
        header.addView(accountToggleTouchArea, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 64, Gravity.LEFT | Gravity.BOTTOM));

        content.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        headerRef = header;

        // Фикс 4: разделитель ПОСЛЕ accountsContainer (перед пунктами меню) заменён на
        // разделитель ВНУТРИ самого accountsContainer снизу — чтобы он появлялся только
        // когда список раскрыт, и визуально отделял аккаунты от остальных пунктов.
        accountsContainer = new LinearLayout(context);
        accountsContainer.setOrientation(LinearLayout.VERTICAL);
        accountsContainer.setBackgroundColor(Theme.getColor(Theme.key_chats_menuBackground));
        themeUpdaters.add(() -> accountsContainer.setBackgroundColor(Theme.getColor(Theme.key_chats_menuBackground)));
        accountsContainer.setVisibility(GONE);
        content.addView(accountsContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        // Постоянный разделитель перед пунктами меню — всегда виден.
        content.addView(createDivider(context));

        content.addView(createMenuItem(context, ID_MY_PROFILE, R.drawable.potok_ic_profile_outline, LocaleController.getString("MyProfile", R.string.MyProfile)));
        content.addView(createMenuItem(context, ID_WALLET, R.drawable.potok_ic_wallet_outline, LocaleController.getString("PotokWallet", R.string.PotokWallet)));
        content.addView(createDivider(context));
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
        scrollView.setClipToPadding(false);
        scrollViewRef = scrollView;
        contentRef = content;
        scrollView.addView(content);
        addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        PotokDebugLog.log(LOG_TAG, "Конструктор завершён, t=+" + (System.currentTimeMillis() - createdAtMs) + "ms");
    }

    private View createMenuItem(Context context, int id, int iconRes, String title) {
        View item = createSimpleRow(context, iconRes, title);
        item.setOnClickListener(v -> {
            PotokDebugLog.log(LOG_TAG, "Клик: \"" + title + "\" (id=" + id + ")");
            if (listener != null) listener.onItemClick(id);
        });
        ImageView icon = (ImageView) ((FrameLayout) item).getChildAt(0);
        TextView text = (TextView) ((FrameLayout) item).getChildAt(1);
        themeUpdaters.add(() -> {
            icon.setColorFilter(Theme.getColor(Theme.key_chats_menuItemIcon), android.graphics.PorterDuff.Mode.MULTIPLY);
            text.setTextColor(Theme.getColor(Theme.key_chats_menuItemText));
            item.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
        });
        return item;
    }

    public static View createSimpleRow(Context context, int iconRes, String title) {
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
        item.addView(text, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL, 72, 0, 16, 0));

        return item;
    }

    private View createDivider(Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        themeUpdaters.add(() -> divider.setBackgroundColor(Theme.getColor(Theme.key_divider)));
        return divider;
    }

    public void setOnDrawerItemClickListener(OnDrawerItemClickListener listener) {
        this.listener = listener;
    }

    public void setAccountsContent(java.util.List<View> accountRows, View addAccountRow) {
        PotokDebugLog.log(LOG_TAG, "setAccountsContent: " + accountRows.size() + " аккаунт(ов)");
        accountsContainer.removeAllViews();
        for (View row : accountRows) {
            forceLightTextColor(row);
            accountsContainer.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        }
        if (addAccountRow != null) {
            forceLightTextColor(addAccountRow);
            accountsContainer.addView(addAccountRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        }
        // Фикс 4: разделитель внутри accountsContainer снизу — отделяет аккаунты от меню
        // только когда список раскрыт.
        View divider = new View(accountsContainer.getContext());
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        accountsContainer.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));
    }

    private void forceLightTextColor(View view) {
        if (view instanceof TextView) {
            ((TextView) view).setTextColor(Theme.getColor(Theme.key_chats_menuItemText));
        } else if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                forceLightTextColor(group.getChildAt(i));
            }
        }
    }

    public boolean isAccountsExpanded() {
        return accountsExpanded;
    }

    public void toggleAccountsList() {
        accountsExpanded = !accountsExpanded;
        chevronView.animate().rotation(accountsExpanded ? 180f : 0f).setDuration(220).start();

        if (accountsHeightAnimator != null) {
            accountsHeightAnimator.cancel();
        }

        android.view.ViewGroup.LayoutParams lp = accountsContainer.getLayoutParams();

        if (accountsExpanded) {
            accountsContainer.setVisibility(VISIBLE);
            accountsContainer.setAlpha(0f);
            int parentWidth = getWidth() > 0 ? getWidth() : AndroidUtilities.dp(280);
            accountsContainer.measure(
                    MeasureSpec.makeMeasureSpec(parentWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int targetHeight = accountsContainer.getMeasuredHeight();
            lp.height = 0;
            accountsContainer.setLayoutParams(lp);

            accountsHeightAnimator = android.animation.ValueAnimator.ofInt(0, targetHeight);
            accountsHeightAnimator.addUpdateListener(a -> {
                lp.height = (int) a.getAnimatedValue();
                accountsContainer.setLayoutParams(lp);
            });
            accountsHeightAnimator.setDuration(240);
            accountsHeightAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
            accountsHeightAnimator.start();
            accountsContainer.animate().alpha(1f).setDuration(240).start();
        } else {
            int startHeight = accountsContainer.getHeight();
            accountsHeightAnimator = android.animation.ValueAnimator.ofInt(startHeight, 0);
            accountsHeightAnimator.addUpdateListener(a -> {
                lp.height = (int) a.getAnimatedValue();
                accountsContainer.setLayoutParams(lp);
            });
            accountsHeightAnimator.setDuration(200);
            accountsHeightAnimator.setInterpolator(new android.view.animation.AccelerateInterpolator());
            accountsHeightAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    accountsContainer.setVisibility(GONE);
                    lp.height = LayoutHelper.WRAP_CONTENT;
                    accountsContainer.setLayoutParams(lp);
                }
            });
            accountsHeightAnimator.start();
            accountsContainer.animate().alpha(0f).setDuration(160).start();
        }
        PotokDebugLog.log(LOG_TAG, "toggleAccountsList: " + (accountsExpanded ? "развёрнут" : "свёрнут"));
    }

    // Фикс "серого квадрата": вызывается при каждом открытии шторки — подтягивает актуальные
    // имя/номер/фото из UserConfig и, если фото ещё не было показано (например, догрузилось
    // уже после создания view), подставляет его сейчас.
    public void refreshHeaderData() {
        TLRPC.User user = UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser();
        if (user == null) {
            return;
        }
        if (nameViewRef != null) {
            nameViewRef.setText(user.first_name + (user.last_name != null ? " " + user.last_name : ""));
        }
        if (phoneViewRef != null && user.phone != null) {
            phoneViewRef.setText("+" + user.phone);
        }
        boolean hasPhoto = user.photo != null && user.photo.photo_big != null
                && !(user.photo instanceof TLRPC.TL_userProfilePhotoEmpty);
        if (hasPhoto && avatarView != null && !avatarView.getImageReceiver().hasBitmapImage()) {
            AvatarDrawable avatarDrawable = new AvatarDrawable(user);
            avatarView.getImageReceiver().setForUserOrChat(user, avatarDrawable, null, true, 0, true);
            PotokDebugLog.log(LOG_TAG, "refreshHeaderData: фото подставлено при открытии, t=+" + (System.currentTimeMillis() - createdAtMs) + "ms");
        }
    }

    // Фикс 1: применяет актуальные цвета темы ко всем зарегистрированным элементам —
    // вызывается при смене дневной/ночной темы, чтобы шторка не оставалась "старого" цвета.
    public void applyTheme() {
        for (Runnable updater : themeUpdaters) {
            updater.run();
        }
        invalidate();
        PotokDebugLog.log(LOG_TAG, "applyTheme: цвета шторки обновлены (" + themeUpdaters.size() + " элементов)");
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didSetNewTheme) {
            applyTheme();
        }
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
