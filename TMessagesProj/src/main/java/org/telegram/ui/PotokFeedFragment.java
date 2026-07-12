package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Adapters.DialogsSearchAdapter;
import org.telegram.ui.Cells.PotokFeedPostCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Лента — показывает посты из всех каналов на которые подписан пользователь,
 * смешанные и отсортированные по дате (свежие сверху).
 */
public class PotokFeedFragment extends BaseFragment implements MainTabsActivity.TabFragmentDelegate, NotificationCenter.NotificationCenterDelegate {

    private static final int MESSAGES_TO_LOAD_PER_CHANNEL = 30;
    private static final int MAX_POSTS_PER_CHANNEL = 10;
    private static final int MAX_RECENT_SEARCH_CHANNELS = 10;
    private static final String PREFS_NAME = "potok_feed_filter";
    private static final String PREFS_KEY_HIDDEN = "hidden_channels";
    // Автозагрузка медиа в ленте — по умолчанию включена для всех трёх типов (как и
    // положено при первом запуске, ровно как в самом Telegram). Выключение конкретного
    // типа не блокирует ручную загрузку по тапу — оно только останавливает АВТОМАТИЧЕСКУЮ
    // подгрузку полноразмерного превью при показе поста (см. PotokFeedPostCell.CarouselAdapter).
    private static final String PREFS_KEY_AUTOLOAD_PHOTO = "autoload_photo";
    private static final String PREFS_KEY_AUTOLOAD_VIDEO = "autoload_video";
    private static final String PREFS_KEY_AUTOLOAD_AUDIO = "autoload_audio";

    public static boolean isAutoloadPhotoEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREFS_KEY_AUTOLOAD_PHOTO, true);
    }

    public static boolean isAutoloadVideoEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREFS_KEY_AUTOLOAD_VIDEO, true);
    }

    public static boolean isAutoloadAudioEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREFS_KEY_AUTOLOAD_AUDIO, true);
    }

    private static void setAutoloadEnabled(Context context, String key, boolean enabled) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(key, enabled).apply();
    }

    // Потолок размера файла для АВТОматической (не по тапу) загрузки — те же самые
    // цифры, что в системных настройках самого Telegram ("Соединение..." ->
    // "Автозагрузка медиа", см. скрин пользователя): видео — 10 МБ через мобильную
    // сеть / 15 МБ через Wi-Fi; аудио/файлы — 1 МБ / 3 МБ. У фото отдельного лимита
    // в самом Telegram нет (сервер и так уже отдаёт сжатую версию) — поэтому для
    // фото проверка размера не применяется, только переключатель вкл/выкл.
    // Ручная загрузка по тапу на кнопку — всегда без лимита, независимо от размера.
    private static final long VIDEO_AUTOLOAD_MAX_MOBILE = 10L * 1024 * 1024;
    private static final long VIDEO_AUTOLOAD_MAX_WIFI = 15L * 1024 * 1024;
    private static final long AUDIO_AUTOLOAD_MAX_MOBILE = 1L * 1024 * 1024;
    private static final long AUDIO_AUTOLOAD_MAX_WIFI = 3L * 1024 * 1024;

    public static boolean isSizeOkForVideoAutoload(long sizeBytes) {
        long max = org.telegram.messenger.ApplicationLoader.isConnectedOrConnectingToWiFi() ? VIDEO_AUTOLOAD_MAX_WIFI : VIDEO_AUTOLOAD_MAX_MOBILE;
        return sizeBytes <= max;
    }

    public static boolean isSizeOkForAudioAutoload(long sizeBytes) {
        long max = org.telegram.messenger.ApplicationLoader.isConnectedOrConnectingToWiFi() ? AUDIO_AUTOLOAD_MAX_WIFI : AUDIO_AUTOLOAD_MAX_MOBILE;
        return sizeBytes <= max;
    }

    private RecyclerListView listView;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefreshLayout;
    private boolean refreshingFeed = false;
    private LinearLayoutManager listViewLayoutManager;
    private org.telegram.ui.Components.RecyclerAnimationScrollHelper scrollHelper;
    private FrameLayout scrollToTopButton;
    // Мини-плеер сверху ленты (название трека + play/pause) — тот же принцип, что
    // и постоянная полоска воспроизведения аудио в самом Telegram (появляется под
    // шапкой, пока что-то играет или стоит на паузе, исчезает при полной остановке).
    private FrameLayout miniPlayerBar;
    private TextView miniPlayerTitle;
    private MiniPlayerButton miniPlayerButton;
    private static final int MINI_PLAYER_HEIGHT_DP = 38;
    private int lastAppliedTopInset = -1;
    private int cachedStatusBarHeight = 0;
    private MainTabsActivityController mainTabsActivityController;
    private final ArrayList<FeedItem> items = new ArrayList<>();
    // username -> уже резолвленный канал (или null, если ещё не резолвлен)
    private final java.util.Map<String, TLRPC.Chat> resolvedChannels = new java.util.HashMap<>();
    // username -> посты этого канала, уже собранные в FeedItem (альбомы объединены)
    private final java.util.Map<String, ArrayList<FeedItem>> channelItems = new java.util.HashMap<>();
    private final java.util.Set<String> historyInFlight = new java.util.HashSet<>();
    // Когда каждый канал последний раз реально успешно подгружался — нужно, чтобы
    // onBecomeFullyVisible() (срабатывает на КАЖДОЕ возвращение в ленту, в т.ч. из
    // комментариев) не долбил messages.getHistory по всем каналам заново каждый
    // раз, если данные и так свежие (см. addChannelToFeed()).
    private final java.util.Map<String, Long> lastChannelFetchTime = new java.util.HashMap<>();
    private static final long MIN_CHANNEL_REFETCH_INTERVAL_MS = 20_000;
    // "Тряска" постов на пару пикселей была вызвана вот чем: у ленты обычно 10-20+
    // каналов, и каждый отвечает на messages.getHistory В СВОЙ момент времени
    // (сетевой round-trip у каждого разный). Раньше КАЖДЫЙ такой ответ поодиночке
    // звал rebuildAndShowAllItems() -> notifyDataSetChanged() по ВСЕМУ списку —
    // то есть один заход в ленту после комментариев мог вызвать 10-20 отдельных
    // notifyDataSetChanged() подряд за секунду-другую, и на каждом RecyclerView
    // заново раскладывал видимые ячейки (сортировка ленты пересчитывается заново
    // и порядок постов НАД текущим скроллом мог на пиксель-два сместиться) — это и
    // выглядело как "трясётся и потом останавливается": по одному лёгкому дёрганию
    // на каждый ответ канала. Дебаунс схлопывает всю пачку ответов, пришедших
    // почти одновременно, в ОДИН финальный rebuild уже после того, как всё утихло.
    private final Runnable debouncedRebuildRunnable = this::rebuildAndShowAllItems;
    private static final long REBUILD_DEBOUNCE_MS = 220;

    // Все каналы в ленте (подписки + поиск) — для фильтра
    private final ArrayList<TLRPC.Chat> allChannels = new ArrayList<>();
    // ID каналов скрытых фильтром
    private Set<String> hiddenChannelIds = new HashSet<>();

    private static class FeedItem {
        TLRPC.Chat channel;
        ArrayList<MessageObject> messages = new ArrayList<>();
    }

    public void setMainTabsActivityController(MainTabsActivityController controller) {
        mainTabsActivityController = controller;
    }

    @Override
    public View createView(Context context) {
        PotokDebugLog.log("PotokFeedLogo", "createView: НАЧАЛО");
        try {
            // Раньше здесь стоял actionBar.setAddToContainer(false) — полностью прятали
            // ActionBar, т.к. он же был источником "пустой полосы" (см. историю выше).
            // Теперь используем его штатно: заголовок "POTOK ЛЕНТА" (второе слово другим
            // цветом) + кнопка трёх точек. Настройка вынесена в setupActionBar(context).
            setupActionBar(context);
            View result = createViewInternal(context);
            PotokDebugLog.log("PotokFeedLogo", "createView: УСПЕШНО завершён, вернул " + (result != null ? result.getClass().getSimpleName() : "null"));
            return result;
        } catch (Throwable t) {
            PotokDebugLog.log("PotokFeedLogo", "createView: ИСКЛЮЧЕНИЕ " + t.getClass().getName() + ": " + t.getMessage()
                + "\n" + android.util.Log.getStackTraceString(t));
            throw t;
        }
    }

    private static final int MENU_ITEM_FILTER = 1;

    /**
     * Play/pause-иконка для мини-плеера сверху ленты — рисуется через Path (как
     * PlayTriangleView в PotokFeedPostCell), а не через drawable-ресурсы, чтобы не
     * зависеть от конкретных иконок из res/ и не размываться на разном dpi.
     */
    private static class MiniPlayerButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private boolean playing = false;

        MiniPlayerButton(Context context) {
            super(context);
            paint.setStyle(Paint.Style.FILL);
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidate();
        }

        void setPlaying(boolean p) {
            if (playing == p) return;
            playing = p;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (playing) {
                float barW = w * 0.16f, gap = w * 0.18f;
                float totalW = barW * 2 + gap;
                float left = (w - totalW) / 2f;
                canvas.drawRect(left, h * 0.18f, left + barW, h * 0.82f, paint);
                canvas.drawRect(left + barW + gap, h * 0.18f, left + barW + gap + barW, h * 0.82f, paint);
            } else {
                path.reset();
                float r = Math.min(w, h) / 2f;
                float cx = w / 2f, cy = h / 2f;
                path.moveTo(cx - r * 0.55f, cy - r * 0.85f);
                path.lineTo(cx - r * 0.55f, cy + r * 0.85f);
                path.lineTo(cx + r * 0.85f, cy);
                path.close();
                canvas.drawPath(path, paint);
            }
        }
    }

    private void setupActionBar(Context context) {
        if (actionBar == null) {
            return;
        }
        // "POTOK " обычным цветом заголовка + "ЛЕНТА" акцентным цветом (тот же акцент,
        // что используется у спиннера pull-to-refresh — key_featuredStickers_addButton).
        android.text.SpannableStringBuilder title = new android.text.SpannableStringBuilder();
        title.append("POTOK ");
        int accentStart = title.length();
        title.append("ЛЕНТА");
        title.setSpan(new android.text.style.ForegroundColorSpan(Theme.getColor(Theme.key_featuredStickers_addButton)),
                accentStart, title.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        actionBar.setTitle(title);

        ActionBarMenu menu = actionBar.createMenu();
        org.telegram.ui.ActionBar.ActionBarMenuItem menuItem = menu.addItem(MENU_ITEM_FILTER, org.telegram.messenger.R.drawable.ic_ab_other);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == MENU_ITEM_FILTER) {
                    PotokDebugLog.log("PotokFeedLogo", "Клик: три точки — открываем меню ленты");
                    showThreeDotsMenu(context, menuItem);
                }
            }
        });
    }

    private View createViewInternal(Context context) {
        // Загружаем сохранённые скрытые каналы из SharedPreferences
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        hiddenChannelIds = new HashSet<>(prefs.getStringSet(PREFS_KEY_HIDDEN, new HashSet<>()));

        // AndroidUtilities.statusBarHeight в этом проекте всегда 0 (fillStatusBarHeight
        // здесь не вызывается), поэтому реальную высоту статусбара получаем синхронно
        // через системный ресурс "status_bar_height" — без WindowInsets/post{}, которые
        // раньше давали верное значение слишком поздно и нестабильно. Считаем один раз
        // и переиспользуем во всех местах, где раньше подставлялся ненадёжный 0.
        final int statusBarH = AndroidUtilities.getStatusBarHeight(context);
        cachedStatusBarHeight = statusBarH;
        lastAppliedTopInset = statusBarH;

        org.telegram.ui.Components.SizeNotifierFrameLayout frameLayout = new org.telegram.ui.Components.SizeNotifierFrameLayout(context);
        fragmentView = frameLayout;
        // Фикс "карнавал полосок": лента раньше рисовала сплошной фон темы под
        // карточками, из-за чего фон не совпадал с обоями, которые пользователь
        // выставил в самих чатах. Теперь используем тот же механизм, что и ChatActivity —
        // SizeNotifierFrameLayout.setBackgroundImage с текущими обоями темы.
        frameLayout.setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion());

        listView = new RecyclerListView(context);
        listViewLayoutManager = new LinearLayoutManager(context);
        listView.setLayoutManager(listViewLayoutManager);
        // Лента обновляется полным notifyDataSetChanged() (см. notifyWhenReady()) —
        // при loadFeed() на КАЖДЫЙ реальный возврат на вкладку (в т.ч. возврат из
        // комментариев или закрытие полноэкранного просмотра фото/видео, которые
        // временно скрывают эту вкладку — см. комментарий у onBecomeFullyVisible()).
        // Стандартный ItemAnimator RecyclerView по умолчанию (DefaultItemAnimator)
        // на полный notifyDataSetChanged() запускает анимацию "сдвига" уже видимых
        // элементов на их (по факту те же самые) позиции — именно это и davало
        // видимое дёрганье постов на пару пикселей вверх-вниз, которое само
        // прекращается, как только анимация доигрывает. Список не полагается на
        // покадровые move/change-анимации между обновлениями (это полная
        // перестройка данных, а не точечное изменение одного элемента), поэтому
        // отключаем анимации переиспользования элементов целиком — так же, как это
        // сделано в других местах Telegram для списков, которые часто обновляются
        // целиком, а не точечно через DiffUtil.
        listView.setItemAnimator(null);
        scrollHelper = new org.telegram.ui.Components.RecyclerAnimationScrollHelper(listView, listViewLayoutManager);

        // Теперь у нас есть настоящий ActionBar (заголовок "POTOK ЛЕНТА" + три точки,
        // см. setupActionBar) — он добавляется отдельным view поверх контента (общий
        // механизм ViewPagerActivity), и резервирует статусбар + свою высоту (56dp,
        // ActionBar.getCurrentActionBarHeight() — т.к. occupyStatusBar по умолчанию
        // true). Чтобы пост не заезжал под него НИ НА ПИКСЕЛЬ, отступ ленты считаем
        // ТОЧНО той же формулой: statusBarInset + getCurrentActionBarHeight().
        // statusBar берём из настоящих WindowInsets (см. предыдущую диагностику — это
        // совпало с getStatusBarHeight на тесте, но insets надёжнее в общем случае).
        listView.setPadding(0, statusBarH + ActionBar.getCurrentActionBarHeight(), 0, AndroidUtilities.dp(56));
        ViewCompat.setOnApplyWindowInsetsListener(frameLayout, (v, insets) -> {
            int topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            lastAppliedTopInset = topInset;
            applyTopLayout(topInset);
            PotokDebugLog.log("PotokFeedLogo", "WindowInsets: statusBars().top=" + topInset
                    + " + actionBarHeight=" + ActionBar.getCurrentActionBarHeight());
            return insets;
        });
        frameLayout.requestApplyInsets();
        listView.setClipToPadding(false);

        // --- Мини-плеер сверху ленты ---
        // Тот же принцип, что и постоянная полоска воспроизведения аудио в самом
        // Telegram: появляется под шапкой, пока что-то играет или стоит на паузе
        // (см. updateMiniPlayer(), вызывается по NotificationCenter.messagePlayingDidStart/
        // PlayStateChanged/DidReset — см. didReceivedNotification), исчезает при
        // полной остановке. Тап по полоске — play/pause того же трека.
        miniPlayerBar = new FrameLayout(context);
        miniPlayerBar.setBackgroundColor(Theme.getColor(Theme.key_actionBarDefault));
        miniPlayerBar.setVisibility(View.GONE);
        miniPlayerBar.setClickable(true);

        miniPlayerButton = new MiniPlayerButton(context);
        miniPlayerButton.setColor(Theme.getColor(Theme.key_actionBarDefaultIcon));
        miniPlayerBar.addView(miniPlayerButton, LayoutHelper.createFrame(22, 22, Gravity.CENTER_VERTICAL | Gravity.LEFT, 14, 0, 0, 0));

        miniPlayerTitle = new TextView(context);
        miniPlayerTitle.setTextColor(Theme.getColor(Theme.key_actionBarDefaultTitle));
        miniPlayerTitle.setTextSize(14);
        miniPlayerTitle.setSingleLine(true);
        miniPlayerTitle.setEllipsize(TextUtils.TruncateAt.END);
        miniPlayerBar.addView(miniPlayerTitle, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.LEFT, 48, 0, 48, 0));

        miniPlayerBar.setOnClickListener(v -> {
            MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (playing == null) return;
            if (MediaController.getInstance().isMessagePaused()) {
                MediaController.getInstance().playMessage(playing);
            } else {
                MediaController.getInstance().pauseMessage(playing);
            }
        });
        // ВАЖНО: addView(miniPlayerBar, ...) сюда НЕ вставляем — FrameLayout рисует
        // детей в порядке добавления (последний = поверх), а swipeRefreshLayout с
        // listView внутри (во весь экран, добавляется ниже по коду) перекрыл бы
        // мини-плеер целиком, даже когда он VISIBLE. addView вызывается ПОСЛЕ
        // swipeRefreshLayout — см. дальше по методу, сразу за ним.

        listView.setAdapter(new RecyclerView.Adapter<RecyclerListView.Holder>() {
            @Override
            public RecyclerListView.Holder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
                PotokFeedPostCell cell = new PotokFeedPostCell(context, null);
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT);
                // Фикс "карточки впритык": раньше пост занимал всю ширину без отступов
                // и соседние посты стыковались друг с другом. Теперь — заметные, но не
                // большие отступы со всех 4 сторон, как отдельные карточки в канале.
                lp.leftMargin = AndroidUtilities.dp(8);
                lp.rightMargin = AndroidUtilities.dp(8);
                lp.topMargin = AndroidUtilities.dp(6);
                lp.bottomMargin = AndroidUtilities.dp(6);
                cell.setLayoutParams(lp);
                cell.setParentActivity(getParentActivity());
                cell.setParentFragment(PotokFeedFragment.this);
                return new RecyclerListView.Holder(cell);
            }

            @Override
            public void onBindViewHolder(RecyclerListView.Holder holder, int position) {
                FeedItem item = items.get(position);
                ((PotokFeedPostCell) holder.itemView).setPost(item.messages, item.channel);
            }

            @Override
            public int getItemCount() {
                return items.size();
            }
        });

        // Фикс: свайп сверху вниз на самом верху ленты — обновляет посты (как в
        // большинстве соцсетей), со стандартным материальным спиннером-индикатором.
        swipeRefreshLayout = new androidx.swiperefreshlayout.widget.SwipeRefreshLayout(context);
        int actionBarTotalHeight = statusBarH + ActionBar.getCurrentActionBarHeight();
        swipeRefreshLayout.setProgressViewOffset(false, actionBarTotalHeight + AndroidUtilities.dp(20), actionBarTotalHeight + AndroidUtilities.dp(76));
        swipeRefreshLayout.setColorSchemeColors(Theme.getColor(Theme.key_featuredStickers_addButton));
        swipeRefreshLayout.setOnRefreshListener(() -> {
            refreshingFeed = true;
            loadFeed();
            // Страховка: если запросы по какой-то причине зависнут (обрыв сети),
            // спиннер всё равно скроется через 8 секунд, а не будет висеть вечно.
            AndroidUtilities.runOnUIThread(() -> {
                if (refreshingFeed) {
                    refreshingFeed = false;
                    if (swipeRefreshLayout != null) {
                        swipeRefreshLayout.setRefreshing(false);
                    }
                }
            }, 8000);
        });
        swipeRefreshLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        frameLayout.addView(swipeRefreshLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        // Мини-плеер добавляется ЗДЕСЬ, после swipeRefreshLayout — иначе список
        // (во весь экран) рисуется поверх и полностью его перекрывает (см. комментарий
        // у создания miniPlayerBar выше). Именно это было причиной того, что бар не
        // появлялся вообще, хотя видимость переключалась корректно.
        frameLayout.addView(miniPlayerBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, MINI_PLAYER_HEIGHT_DP, Gravity.TOP, 0, 0, 0, 0));

        // --- Кнопка "наверх" ---
        scrollToTopButton = new FrameLayout(context);
        GradientDrawable circleBg = new GradientDrawable();
        circleBg.setShape(GradientDrawable.OVAL);
        circleBg.setColor(Theme.getColor(Theme.key_dialogFloatingButton));
        scrollToTopButton.setBackground(circleBg);
        scrollToTopButton.setElevation(AndroidUtilities.dp(4));
        scrollToTopButton.setVisibility(View.GONE);
        scrollToTopButton.setAlpha(0f);

        // Векторная стрелка-шеврон + стержень — рисуется через Path, поэтому одинаково
        // чёткая на любом dpi (системный android.R.drawable.arrow_up_float — низкого
        // разрешения и визуально выглядит как треугольник, а не как стрелка).
        ArrowUpView arrowUp = new ArrowUpView(context);
        arrowUp.setColor(Theme.getColor(Theme.key_dialogFloatingIcon));
        scrollToTopButton.addView(arrowUp, LayoutHelper.createFrame(24, 24, Gravity.CENTER));

        scrollToTopButton.setOnClickListener(v -> {
            if (listView == null || scrollHelper == null) {
                return;
            }
            // Раньше здесь был smoothScrollToPosition(0) — он прокручивает
            // последовательно через ВСЕ посты между текущей позицией и началом,
            // что на длинной ленте выглядит как долгое пролистывание. scrollHelper
            // (тот же паттерн, что у кнопки "к началу" в DialogsActivity) мгновенно
            // переносит layout к позиции 0 и красиво доезжают только элементы,
            // уже видимые на экране — без полного перебора истории.
            scrollHelper.setScrollDirection(org.telegram.ui.Components.RecyclerAnimationScrollHelper.SCROLL_DIRECTION_UP);
            scrollHelper.scrollToPosition(0, 0, false, true);
        });

        // Отступ снизу должен учитывать реальную высоту плавающего таббара
        // (DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS = 56 + 8*2 = 72dp, не 56dp)
        // и системный navigationBarHeight (жестовая панель/кнопки) — без него
        // кнопка уходит под таббар на части устройств, см. AndroidUtilities.navigationBarHeight.
        int scrollButtonBottomMarginPx = AndroidUtilities.navigationBarHeight
            + AndroidUtilities.dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS)
            + AndroidUtilities.dp(16);
        int scrollButtonBottomMarginDp = (int) (scrollButtonBottomMarginPx / AndroidUtilities.density);
        frameLayout.addView(scrollToTopButton, LayoutHelper.createFrame(48, 48, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 16, scrollButtonBottomMarginDp));

        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm == null) return;
                boolean shouldShow = lm.findFirstVisibleItemPosition() > 1;
                if (shouldShow && scrollToTopButton.getVisibility() != View.VISIBLE) {
                    scrollToTopButton.setVisibility(View.VISIBLE);
                    scrollToTopButton.animate().alpha(1f).setDuration(150).start();
                } else if (!shouldShow && scrollToTopButton.getVisibility() == View.VISIBLE) {
                    scrollToTopButton.animate().alpha(0f).setDuration(150)
                        .withEndAction(() -> scrollToTopButton.setVisibility(View.GONE)).start();
                }
                // Пост, докрутившийся до видимой области экрана, считается
                // просмотренным — засчитываем это как прочтение в чате канала.
                checkVisibleFeedItemsRead();
            }
        });

        if (mainTabsActivityController != null) {
            listView.addOnScrollListener(new TabBarScrollHider(mainTabsActivityController));
        }

        loadFeed();

        return frameLayout;
    }

    /**
     * Пересчитывает верхний паддинг ленты и позицию мини-плеера с учётом реального
     * inset статусбара + высоты ActionBar + (если сейчас показан) высоты самого
     * мини-плеера. Вызывается и из WindowInsets-колбэка, и из updateMiniPlayer()
     * при появлении/скрытии бара — иначе появление бара наезжало бы на первый пост,
     * не сдвигая содержимое ленты вниз (как это происходит в самом Telegram, когда
     * появляется полоска воспроизведения).
     */
    private void applyTopLayout(int topInset) {
        int actionBarBottom = topInset + ActionBar.getCurrentActionBarHeight();
        boolean miniPlayerShown = miniPlayerBar != null && miniPlayerBar.getVisibility() == View.VISIBLE;
        int miniPlayerHeightPx = miniPlayerShown ? AndroidUtilities.dp(MINI_PLAYER_HEIGHT_DP) : 0;
        if (listView != null) {
            listView.setPadding(0, actionBarBottom + miniPlayerHeightPx, 0, AndroidUtilities.dp(56));
        }
        if (miniPlayerBar != null) {
            ViewGroup.LayoutParams lpRaw = miniPlayerBar.getLayoutParams();
            if (lpRaw instanceof FrameLayout.LayoutParams) {
                ((FrameLayout.LayoutParams) lpRaw).topMargin = actionBarBottom;
                miniPlayerBar.setLayoutParams(lpRaw);
            }
        }
    }

    /**
     * Обновляет мини-плеер сверху ленты — вызывается по NotificationCenter.
     * messagePlayingDidStart/messagePlayingPlayStateChanged/messagePlayingDidReset
     * (см. didReceivedNotification), а также один раз при возврате на вкладку
     * (onBecomeFullyVisible), т.к. трек мог начать/закончить играть, пока лента
     * была не видна (например, пользователь запустил его из чата, а не из ленты).
     */
    private void updateMiniPlayer() {
        if (miniPlayerBar == null) return;
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        boolean show = playing != null && (playing.isVoice() || playing.isMusic());
        boolean wasShown = miniPlayerBar.getVisibility() == View.VISIBLE;
        if (show) {
            miniPlayerButton.setPlaying(!MediaController.getInstance().isMessagePaused());
            String title;
            if (playing.isVoice()) {
                title = "Голосовое сообщение";
            } else {
                String musicTitle = playing.getMusicTitle();
                title = !TextUtils.isEmpty(musicTitle) ? musicTitle : "Аудио";
            }
            miniPlayerTitle.setText(title);
        }
        if (show != wasShown) {
            miniPlayerBar.setVisibility(show ? View.VISIBLE : View.GONE);
            applyTopLayout(lastAppliedTopInset >= 0 ? lastAppliedTopInset : cachedStatusBarHeight);
        }
    }

    private void loadFeed() {
        // Берём все диалоги пользователя и фильтруем: только каналы (не группы, не боты)
        ArrayList<TLRPC.Dialog> dialogs = getMessagesController().getAllDialogs();
        ArrayList<TLRPC.Chat> channels = new ArrayList<>();
        for (TLRPC.Dialog dialog : dialogs) {
            if (!(dialog instanceof TLRPC.TL_dialog)) continue;
            long did = dialog.id;
            if (did >= 0) continue; // не чат/канал
            TLRPC.Chat chat = getMessagesController().getChat(-did);
            if (chat == null) continue;
            // Канал (broadcast), не мегагруппа и не деактивирован
            if (chat.broadcast && !chat.megagroup && !chat.deactivated && !chat.left && !chat.kicked) {
                channels.add(chat);
            }
        }
        if (channels.isEmpty()) {
            // Диалоги ещё не загружены — ждём и пробуем снова
            AndroidUtilities.runOnUIThread(this::loadFeed, 1500);
            return;
        }
        StringBuilder channelNames = new StringBuilder();
        for (TLRPC.Chat channel : channels) {
            channelNames.append(channel.title).append(" (id=").append(channel.id).append("); ");
        }
        PotokDebugLog.log("PotokFeedLogo", "loadFeed: найдено каналов = " + channels.size() + ": " + channelNames);
        for (TLRPC.Chat channel : channels) {
            addChannelToFeed(channel);
        }
        // Загружаем посты из последних 10 каналов из истории поиска
        loadRecentSearchChannels();
    }

    private void addChannelToFeed(TLRPC.Chat channel) {
        String key = String.valueOf(channel.id);
        // Баг был здесь: loadHistory() раньше вызывался ТОЛЬКО внутри этого if,
        // то есть ровно один раз за всё время жизни фрагмента на канал — второй и
        // любой последующий вызов addChannelToFeed() для уже известного канала
        // (обновление свайпом вниз, повторный loadFeed() на onResume) ничего не
        // делал вообще. Поэтому новые посты не подтягивались никогда, кроме
        // самого первого раза — а он происходит только при пересоздании
        // фрагмента с нуля, то есть при полном рестарте приложения. Теперь
        // "канал уже известен" (resolvedChannels/allChannels — нужно только
        // чтобы не дублировать канал в списке фильтра) и "нужно перезапросить
        // историю" — две независимые вещи: второе происходит при каждом вызове.
        if (!resolvedChannels.containsKey(key)) {
            resolvedChannels.put(key, channel);
            boolean alreadyInList = false;
            for (TLRPC.Chat ch : allChannels) {
                if (ch.id == channel.id) { alreadyInList = true; break; }
            }
            if (!alreadyInList) allChannels.add(channel);
        }
        // Троттлинг: onBecomeFullyVisible() дёргает loadFeed() -> addChannelToFeed()
        // по ВСЕМ каналам при каждом реальном возврате в ленту (в т.ч. закрыл
        // комментарии и вернулся) — если у канала данные и так свежие (недавно уже
        // подгружали), повторный запрос не нужен. Явный pull-to-refresh
        // (refreshingFeed == true) всегда игнорирует троттлинг — пользователь явно
        // попросил обновить именно сейчас.
        if (!refreshingFeed) {
            Long lastFetch = lastChannelFetchTime.get(key);
            if (lastFetch != null && System.currentTimeMillis() - lastFetch < MIN_CHANNEL_REFETCH_INTERVAL_MS) {
                return;
            }
        }
        loadHistory(key, channel);
    }

    private void loadRecentSearchChannels() {
        DialogsSearchAdapter.loadRecentSearch(currentAccount, 0, (arrayList, hashMap) -> {
            ArrayList<TLRPC.Chat> recentChannels = new ArrayList<>();
            for (int i = 0; i < arrayList.size() && recentChannels.size() < MAX_RECENT_SEARCH_CHANNELS; i++) {
                DialogsSearchAdapter.RecentSearchObject obj = arrayList.get(i);
                if (obj.object instanceof TLRPC.Chat) {
                    TLRPC.Chat chat = (TLRPC.Chat) obj.object;
                    // chat.left = "вы не состоите в этом чате" — для каналов из истории
                    // поиска, на которые пользователь НЕ подписан (именно такие и нужны
                    // в ленте), этот флаг всегда true, поэтому раньше их отфильтровывало.
                    if (chat.broadcast && !chat.megagroup && !chat.deactivated && !chat.kicked) {
                        recentChannels.add(chat);
                    }
                }
            }
            PotokDebugLog.log("PotokFeedLogo", "История поиска: сырых записей=" + arrayList.size() + " каналов после фильтра=" + recentChannels.size());
            AndroidUtilities.runOnUIThread(() -> {
                for (TLRPC.Chat channel : recentChannels) {
                    PotokDebugLog.log("PotokFeedLogo", "История поиска: добавляю в ленту канал id=" + channel.id + " (" + channel.title + ")");
                    addChannelToFeed(channel);
                }
            });
        });
    }

    /**
     * Скрывает канал из ленты через тот же механизм, что и общий фильтр каналов
     * по кнопке в шапке (hiddenChannelIds/SharedPreferences). Вызывается из
     * пункта "Не показывать посты из этого канала" в меню поста (PotokFeedPostCell).
     */
    public void hideChannel(TLRPC.Chat channel) {
        if (channel == null) {
            return;
        }
        String id = String.valueOf(channel.id);
        hiddenChannelIds.add(id);
        Context context = getParentActivity();
        if (context != null) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putStringSet(PREFS_KEY_HIDDEN, hiddenChannelIds).apply();
        }
        PotokDebugLog.log("PotokFeedLogo", "Канал скрыт из поста: id=" + id + " скрыто всего=" + hiddenChannelIds.size());
        rebuildAndShowAllItems();
    }

    private ActionBarPopupWindow threeDotsMenuWindow;

    /**
     * Меню по кнопке "три точки" в шапке ленты — тот же тёмный стиль попапа,
     * что и у меню поста (ActionBarPopupWindowLayout + popup_fixed_alert4).
     * "Фильтр каналов" и "Настройки медиа" открываются ВНУТРИ этого же попапа
     * через встроенный в Telegram механизм swipe-back (FLAG_USE_SWIPEBACK/
     * openForeground) — то же самое, чем в оригинальном приложении открывается,
     * например, подменю "добавить в папку". "Отметить всё просмотренным" и
     * "Очистка кэша" действуют сразу, без подменю. "Обратная связь" пока
     * остаётся заглушкой.
     */
    private void showThreeDotsMenu(Context context, View anchor) {
        int flags = ActionBarPopupWindow.ActionBarPopupWindowLayout.FLAG_USE_SWIPEBACK;
        ActionBarPopupWindow.ActionBarPopupWindowLayout layout =
            new ActionBarPopupWindow.ActionBarPopupWindowLayout(context, org.telegram.messenger.R.drawable.popup_fixed_alert4, null, flags);

        // ------------------------------------------------------------ подменю "Фильтр каналов"
        LinearLayout filterMenuView = new LinearLayout(context);
        filterMenuView.setOrientation(LinearLayout.VERTICAL);

        ActionBarMenuSubItem backItem = new ActionBarMenuSubItem(context, true, false, null);
        backItem.setTextAndIcon("Назад", org.telegram.messenger.R.drawable.ic_ab_back);
        backItem.setMinimumWidth(AndroidUtilities.dp(220));
        backItem.setOnClickListener(v -> layout.getSwipeBack().closeForeground());
        filterMenuView.addView(backItem);
        filterMenuView.addView(new ActionBarPopupWindow.GapView(context, null), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));

        // Скроллится, если каналов много — максимум 40% высоты экрана / 360dp,
        // тот же приём, что и у оригинального подменю папок в DialogsActivity.
        android.widget.ScrollView scrollView = new android.widget.ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, android.view.View.MeasureSpec.makeMeasureSpec(
                    (int) Math.min(
                        android.view.View.MeasureSpec.getSize(heightMeasureSpec),
                        Math.min(AndroidUtilities.displaySize.y * 0.4f, AndroidUtilities.dp(360))
                    ),
                    android.view.View.MeasureSpec.getMode(heightMeasureSpec)
                ));
            }
        };
        scrollView.setVerticalScrollBarEnabled(false);
        LinearLayout channelsList = new LinearLayout(context);
        channelsList.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(channelsList);

        // Изменения копятся в pendingHidden и применяются только по кнопке
        // "Применить" — так же, как раньше вело себя "Отмена" в AlertDialog.
        // TextCheckCell вместо CheckBoxCell (обычная "птичка") — тот же переключатель
        // с анимацией скольжения, что и в системных настройках Telegram (см. скрин
        // "Соединение..." — Switch справа), для визуальной консистентности с
        // "Настройками медиа" ниже, которые используют тот же компонент.
        final Set<String> pendingHidden = new HashSet<>(hiddenChannelIds);
        for (TLRPC.Chat ch : allChannels) {
            final String chId = String.valueOf(ch.id);
            TextCheckCell cell = new TextCheckCell(context, 16);
            cell.setTextAndCheck(ch.title, !pendingHidden.contains(chId), false);
            cell.setOnClickListener(v -> {
                boolean nowChecked = pendingHidden.contains(chId);
                if (nowChecked) {
                    pendingHidden.remove(chId);
                } else {
                    pendingHidden.add(chId);
                }
                ((TextCheckCell) v).setChecked(nowChecked);
            });
            channelsList.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        }
        filterMenuView.addView(scrollView);
        filterMenuView.addView(new ActionBarPopupWindow.GapView(context, null), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));

        ActionBarMenuSubItem applyItem = new ActionBarMenuSubItem(context, false, true, null);
        applyItem.setTextAndIcon("Применить", org.telegram.messenger.R.drawable.msg_check_s);
        applyItem.setMinimumWidth(AndroidUtilities.dp(220));
        applyItem.setOnClickListener(v -> {
            hiddenChannelIds = new HashSet<>(pendingHidden);
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putStringSet(PREFS_KEY_HIDDEN, hiddenChannelIds).apply();
            PotokDebugLog.log("PotokFeedLogo", "Фильтр применён: скрыто=" + hiddenChannelIds.size());
            rebuildAndShowAllItems();
            if (threeDotsMenuWindow != null) threeDotsMenuWindow.dismiss();
        });
        filterMenuView.addView(applyItem);

        int filterMenuIndex = layout.addViewToSwipeBack(filterMenuView);

        // ------------------------------------------------------------ подменю "Настройки медиа"
        LinearLayout mediaMenuView = new LinearLayout(context);
        mediaMenuView.setOrientation(LinearLayout.VERTICAL);

        ActionBarMenuSubItem mediaBackItem = new ActionBarMenuSubItem(context, true, false, null);
        mediaBackItem.setTextAndIcon("Назад", org.telegram.messenger.R.drawable.ic_ab_back);
        mediaBackItem.setMinimumWidth(AndroidUtilities.dp(220));
        mediaBackItem.setOnClickListener(v -> layout.getSwipeBack().closeForeground());
        mediaMenuView.addView(mediaBackItem);
        mediaMenuView.addView(new ActionBarPopupWindow.GapView(context, null), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));

        // Каждый переключатель отвечает ТОЛЬКО за автозагрузку — то есть за то,
        // подгружается ли полноразмерное превью САМО при показе поста в ленте.
        // Выключение не блокирует загрузку по тапу пользователя — тап по фото/видео/
        // аудио всегда качает и показывает/проигрывает независимо от этих настроек
        // (см. использование isAutoload*Enabled в PotokFeedPostCell.CarouselAdapter).
        TextCheckCell photoCell = new TextCheckCell(context, 16);
        photoCell.setTextAndCheck("Скачивать фото", isAutoloadPhotoEnabled(context), true);
        photoCell.setOnClickListener(v -> {
            boolean newValue = !isAutoloadPhotoEnabled(context);
            setAutoloadEnabled(context, PREFS_KEY_AUTOLOAD_PHOTO, newValue);
            ((TextCheckCell) v).setChecked(newValue);
        });
        mediaMenuView.addView(photoCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

        TextCheckCell videoCell = new TextCheckCell(context, 16);
        videoCell.setTextAndCheck("Скачивать видео", isAutoloadVideoEnabled(context), true);
        videoCell.setOnClickListener(v -> {
            boolean newValue = !isAutoloadVideoEnabled(context);
            setAutoloadEnabled(context, PREFS_KEY_AUTOLOAD_VIDEO, newValue);
            ((TextCheckCell) v).setChecked(newValue);
        });
        mediaMenuView.addView(videoCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

        TextCheckCell audioCell = new TextCheckCell(context, 16);
        audioCell.setTextAndCheck("Скачивать аудио", isAutoloadAudioEnabled(context), false);
        audioCell.setOnClickListener(v -> {
            boolean newValue = !isAutoloadAudioEnabled(context);
            setAutoloadEnabled(context, PREFS_KEY_AUTOLOAD_AUDIO, newValue);
            ((TextCheckCell) v).setChecked(newValue);
        });
        mediaMenuView.addView(audioCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

        int mediaMenuIndex = layout.addViewToSwipeBack(mediaMenuView);

        // ------------------------------------------------------------ главный список пунктов
        ActionBarMenuSubItem filterItem = new ActionBarMenuSubItem(context, true, false, null);
        filterItem.setTextAndIcon("Фильтр каналов", org.telegram.messenger.R.drawable.menu_tag_filter);
        filterItem.setMinimumWidth(AndroidUtilities.dp(220));
        filterItem.setOnClickListener(v -> {
            if (allChannels.isEmpty()) {
                PotokDebugLog.log("PotokFeedLogo", "Фильтр: каналов пока нет");
                return;
            }
            layout.getSwipeBack().openForeground(filterMenuIndex);
        });
        layout.addView(filterItem);

        ActionBarMenuSubItem markReadItem = new ActionBarMenuSubItem(context, false, false, null);
        markReadItem.setTextAndIcon("Отметить всё просмотренным", org.telegram.messenger.R.drawable.msg_markread);
        markReadItem.setMinimumWidth(AndroidUtilities.dp(220));
        markReadItem.setOnClickListener(v -> {
            if (threeDotsMenuWindow != null) threeDotsMenuWindow.dismiss();
            markAllFeedItemsAsRead();
        });
        layout.addView(markReadItem);

        ActionBarMenuSubItem mediaSettingsItem = new ActionBarMenuSubItem(context, false, false, null);
        mediaSettingsItem.setTextAndIcon("Настройки медиа", org.telegram.messenger.R.drawable.msg_photo_settings);
        mediaSettingsItem.setMinimumWidth(AndroidUtilities.dp(220));
        mediaSettingsItem.setOnClickListener(v -> layout.getSwipeBack().openForeground(mediaMenuIndex));
        layout.addView(mediaSettingsItem);

        ActionBarMenuSubItem clearCacheItem = new ActionBarMenuSubItem(context, false, false, null);
        clearCacheItem.setTextAndIcon("Очистка кэша", org.telegram.messenger.R.drawable.msg_clearcache);
        clearCacheItem.setMinimumWidth(AndroidUtilities.dp(220));
        clearCacheItem.setOnClickListener(v -> {
            if (threeDotsMenuWindow != null) threeDotsMenuWindow.dismiss();
            clearFeedCache(context);
        });
        layout.addView(clearCacheItem);

        ActionBarMenuSubItem feedbackItem = new ActionBarMenuSubItem(context, false, true, null);
        feedbackItem.setTextAndIcon("Обратная связь", org.telegram.messenger.R.drawable.msg_help);
        feedbackItem.setMinimumWidth(AndroidUtilities.dp(220));
        layout.addView(feedbackItem);

        threeDotsMenuWindow = new ActionBarPopupWindow(layout, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
        threeDotsMenuWindow.setFocusable(true);
        threeDotsMenuWindow.setOutsideTouchable(true);
        threeDotsMenuWindow.setClippingEnabled(true);
        threeDotsMenuWindow.setAnimationStyle(org.telegram.messenger.R.style.PopupAnimation);
        threeDotsMenuWindow.setOnDismissListener(() -> threeDotsMenuWindow = null);

        layout.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        );

        int[] location = new int[2];
        anchor.getLocationInWindow(location);
        int x = location[0] + anchor.getWidth() - layout.getMeasuredWidth();
        int y = location[1] + anchor.getHeight();
        threeDotsMenuWindow.showAtLocation(anchor, android.view.Gravity.LEFT | android.view.Gravity.TOP, x, y);
        ActionBarPopupWindow.startAnimation(layout);
    }

    private void loadHistory(String key, TLRPC.Chat channel) {
        if (historyInFlight.contains(key)) {
            return;
        }
        historyInFlight.add(key);

        long dialogId = -channel.id;
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = getMessagesController().getInputPeer(dialogId);
        req.limit = MESSAGES_TO_LOAD_PER_CHANNEL;
        req.offset_id = 0;
        req.offset_date = 0;
        req.add_offset = 0;
        req.max_id = 0;
        req.min_id = 0;
        req.hash = 0;

        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            historyInFlight.remove(key);
            if (refreshingFeed && historyInFlight.isEmpty()) {
                refreshingFeed = false;
                if (swipeRefreshLayout != null) {
                    swipeRefreshLayout.setRefreshing(false);
                }
            }
            if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                PotokDebugLog.log("PotokFeedLogo", "loadHistory: ОШИБКА для канала id=" + channel.id + " (" + channel.title + ") error="
                        + (error != null ? (error.code + " " + error.text) : "response не messages_Messages: " + response));
                return;
            }
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            getMessagesController().putUsers(res.users, false);
            getMessagesController().putChats(res.chats, false);

            ArrayList<MessageObject> messageObjects = new ArrayList<>();
            for (TLRPC.Message message : res.messages) {
                // Служебные сообщения (TL_messageService) — это не посты канала, а
                // системные строки вида "X закрепил(а) фотографию", "канал создан",
                // смена фото канала и т.п. Раньше они не отфильтровывались нигде и
                // попадали в ленту как пустой/странный пост. Пропускаем их целиком —
                // в ленте должны быть только настоящие посты (TL_message).
                if (message instanceof TLRPC.TL_messageService) {
                    continue;
                }
                messageObjects.add(new MessageObject(currentAccount, message, true, true));
            }
            channelItems.put(key, buildChannelItems(messageObjects, channel));
            lastChannelFetchTime.put(key, System.currentTimeMillis());
            scheduleRebuild();
        }));
    }

    private ArrayList<FeedItem> buildChannelItems(ArrayList<MessageObject> messageObjects, TLRPC.Chat channel) {
        ArrayList<FeedItem> result = new ArrayList<>();
        FeedItem currentItem = null;
        long currentGroupId = 0;

        for (MessageObject mo : messageObjects) {
            if (mo == null || mo.messageOwner == null) {
                continue;
            }
            long groupId = mo.messageOwner.grouped_id;
            boolean continuesCurrentGroup = groupId != 0 && currentItem != null && currentGroupId == groupId;

            if (!continuesCurrentGroup && result.size() >= MAX_POSTS_PER_CHANNEL) {
                break;
            }

            if (continuesCurrentGroup) {
                currentItem.messages.add(mo);
            } else {
                currentItem = new FeedItem();
                currentItem.channel = channel;
                currentItem.messages.add(mo);
                result.add(currentItem);
                currentGroupId = groupId;
            }
        }

        for (FeedItem item : result) {
            if (item.messages.size() > 1) {
                Collections.sort(item.messages, (a, b) -> Integer.compare(a.getId(), b.getId()));
            }
        }

        // Склейка опроса с соседним медиа-постом в один визуальный "пост" ленты.
        // Опрос физически не может иметь grouped_id с фото/видео (разные типы
        // media одного сообщения несовместимы в принципе) — то, что в канале
        // выглядит как "один пост" (медиа сверху, опрос снизу), на самом деле два
        // ОТДЕЛЬНЫХ сообщения одного автора, отправленных подряд без ничего между
        // ними (подтверждено скриншотом канала). Поэтому здесь — не группировка по
        // общему id, а склейка по чистой ПОСЛЕДОВАТЕЛЬНОЙ смежности в `result`
        // (`result` уже отражает порядок исходных messageObjects, так что соседние
        // элементы здесь гарантированно значат "между ними в истории канала не было
        // никакого другого сообщения"). Порядок отображения — всегда медиа сверху,
        // опрос снизу, независимо от того, в каком порядке они реально пришли в
        // history (getHistory обычно отдаёт сообщения от новых к старым).
        for (int i = 0; i < result.size() - 1; i++) {
            FeedItem a = result.get(i);
            FeedItem b = result.get(i + 1);
            boolean aIsPollOnly = isPollOnlyItem(a);
            boolean bIsPollOnly = isPollOnlyItem(b);
            if (aIsPollOnly == bIsPollOnly) continue; // нужен ровно один опрос из пары
            FeedItem pollItem = aIsPollOnly ? a : b;
            FeedItem mediaItem = aIsPollOnly ? b : a;
            if (!isMediaOnlyItem(mediaItem)) continue;
            FeedItem merged = new FeedItem();
            merged.channel = channel;
            merged.messages.addAll(mediaItem.messages);
            merged.messages.addAll(pollItem.messages);
            result.set(i, merged);
            result.remove(i + 1);
        }
        return result;
    }

    /** Пост состоит ровно из одного сообщения, и это сообщение — опрос. */
    private static boolean isPollOnlyItem(FeedItem item) {
        if (item.messages.size() != 1) return false;
        MessageObject mo = item.messages.get(0);
        return mo.messageOwner != null && mo.messageOwner.media instanceof TLRPC.TL_messageMediaPoll;
    }

    /** Пост НЕ содержит опроса и реально содержит хотя бы одно фото/видео (не просто текст). */
    private static boolean isMediaOnlyItem(FeedItem item) {
        if (item.messages.isEmpty()) return false;
        boolean hasMedia = false;
        for (MessageObject mo : item.messages) {
            if (mo.messageOwner == null) continue;
            if (mo.messageOwner.media instanceof TLRPC.TL_messageMediaPoll) return false;
            if (mo.photoThumbs != null && !mo.photoThumbs.isEmpty()) hasMedia = true;
        }
        return hasMedia;
    }

    /** Дата поста для сортировки общей ленты — берём дату первого сообщения в группе. */
    private int postDate(FeedItem item) {
        if (item.messages.isEmpty() || item.messages.get(0).messageOwner == null) {
            return 0;
        }
        return item.messages.get(0).messageOwner.date;
    }

    /**
     * Debounce-обёртка над rebuildAndShowAllItems() для сетевых колбэков loadHistory():
     * ответы от 10-20 каналов приходят почти одновременно, но не строго синхронно —
     * без дебаунса каждый из них поодиночке вызывал полный notifyDataSetChanged(),
     * из-за чего лента визуально "трясло" (см. подробный комментарий у
     * lastChannelFetchTime выше). Каждый новый вызов откладывает пересборку ещё на
     * REBUILD_DEBOUNCE_MS — реальная пересборка происходит один раз, уже после того,
     * как вся пачка ответов, пришедшая почти одновременно, утихла.
     * Instant-действия пользователя (применить фильтр каналов, скрыть канал) по-
     * прежнему зовут rebuildAndShowAllItems() напрямую, без дебаунса — там задержка
     * в отклике на явный тап была бы, наоборот, вредна.
     */
    private void scheduleRebuild() {
        AndroidUtilities.cancelRunOnUIThread(debouncedRebuildRunnable);
        AndroidUtilities.runOnUIThread(debouncedRebuildRunnable, REBUILD_DEBOUNCE_MS);
    }

    private void rebuildAndShowAllItems() {
        items.clear();
        for (java.util.Map.Entry<String, ArrayList<FeedItem>> entry : channelItems.entrySet()) {
            if (hiddenChannelIds.contains(entry.getKey())) continue;
            items.addAll(entry.getValue());
        }
        // смешиваем посты разных каналов в одну ленту, свежие сверху
        Collections.sort(items, (a, b) -> Integer.compare(postDate(b), postDate(a)));
        notifyWhenReady();
    }

    private void notifyWhenReady() {
        if (listView == null || listView.getAdapter() == null) {
            return;
        }
        if (listView.isComputingLayout()) {
            listView.post(this::notifyWhenReady);
        } else {
            listView.getAdapter().notifyDataSetChanged();
            // После обновления списка нужно дождаться прохода layout (позиции ещё не
            // известны сразу после notifyDataSetChanged), иначе findFirstVisibleItemPosition
            // ниже вернёт NO_POSITION.
            listView.post(this::checkVisibleFeedItemsRead);
        }
    }

    /**
     * Просмотр поста В ЛЕНТЕ засчитывается как просмотр этого же сообщения в самом
     * чате канала — иначе цифра непрочитанных в списке чатов продолжает показывать
     * то, что пользователь уже увидел здесь. Логика зеркалит то, как ChatActivity
     * помечает видимые сообщения прочитанными при скролле (см. markDialogAsRead
     * с maxPositiveId/maxDate, вычисленными по видимым сообщениям) — тут вместо
     * позиций сообщений в чате берём позиции постов, реально видимых в RecyclerView
     * ленты прямо сейчас.
     */
    private void checkVisibleFeedItemsRead() {
        if (listView == null || listViewLayoutManager == null || items.isEmpty()) {
            return;
        }
        int first = listViewLayoutManager.findFirstVisibleItemPosition();
        int last = listViewLayoutManager.findLastVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) {
            return;
        }
        for (int i = Math.max(0, first); i <= last && i < items.size(); i++) {
            markFeedItemAsRead(items.get(i));
        }
    }

    /**
     * Помечает прочитанным (локально + серверу через отложенную read-задачу внутри
     * markDialogAsRead) сообщение(я) одного поста в чате его канала. Пост в нашей
     * ленте может быть альбомом из нескольких MessageObject — берём максимальный id
     * и максимальную дату среди них, ровно как это делает сам Telegram при чтении
     * альбома целиком. Если в посте нет ни одного реально непрочитанного сообщения
     * (уже было прочитано раньше — в т.ч. нами же на предыдущем скролле), ничего не
     * делаем, чтобы не слать лишние сетевые запросы при каждом скролле ленты.
     */
    private void markFeedItemAsRead(FeedItem item) {
        if (item == null || item.messages.isEmpty()) {
            return;
        }
        int maxId = 0;
        int maxDate = 0;
        long dialogId = 0;
        for (MessageObject mo : item.messages) {
            if (mo == null || mo.messageOwner == null) continue;
            dialogId = mo.getDialogId();
            if (mo.getId() > maxId) maxId = mo.getId();
            if (mo.messageOwner.date > maxDate) maxDate = mo.messageOwner.date;
        }
        if (maxId <= 0 || dialogId == 0) {
            return;
        }
        // Баг был здесь: mo.isUnread() читает messageOwner.unread — а это поле
        // выставляется MessagesController/MessagesStorage ТОЛЬКО когда сообщение
        // проходит через обычный локальный пайплайн загрузки диалога. Наши посты
        // ленты строятся напрямую из ответа messages.getHistory (см. loadHistory())
        // в обход этого пайплайна, поэтому messageOwner.unread у них всегда false
        // по умолчанию — hasUnread был всегда false, и markDialogAsRead ниже
        // молча никогда не вызывался. Реальное "прочитано ли" сравниваем вручную
        // с read_inbox_max_id диалога канала: пост непрочитан, если его id больше.
        TLRPC.Dialog dialog = getMessagesController().getDialog(dialogId);
        if (dialog == null || maxId <= dialog.read_inbox_max_id) {
            return;
        }
        for (MessageObject mo : item.messages) {
            if (mo != null) mo.setIsRead();
        }
        getMessagesController().markDialogAsRead(dialogId, maxId, maxId, maxDate, false, 0, 0, true, 0);
    }

    /**
     * "Отметить всё просмотренным" из меню трёх точек — в отличие от автоматического
     * скролл-триггера (markFeedItemAsRead выше, который отмечает только то, что
     * реально проскроллил пользователь), здесь помечаются ВСЕ уже загруженные посты
     * во всех каналах ленты разом, по явному запросу.
     *
     * Реальный сетевой вызов уходит и в сам канал тоже (markDialogAsRead внутри
     * markFeedItemAsRead — это тот же самый API-метод, которым Telegram помечает
     * диалог прочитанным при обычном пролистывании канала руками, просто здесь это
     * делается программно сразу по многим каналам). Метод не эксклюзивен для ленты
     * и сам по себе не является чем-то "запрещённым" — но чтобы пачка read-запросов
     * по многим каналам подряд не выглядела на сервере как подозрительный паттерн
     * автоматизации, разносим вызовы по разным каналам с небольшим шагом по времени,
     * а не бьём все каналы разом в один тик. markFeedItemAsRead сам по себе не шлёт
     * лишний запрос, если пост и так уже прочитан, — поэтому массовый вызов безопасен.
     */
    private void markAllFeedItemsAsRead() {
        java.util.Map<Long, Integer> dialogDelay = new java.util.HashMap<>();
        int slot = 0;
        for (FeedItem item : items) {
            if (item == null || item.messages.isEmpty()) continue;
            MessageObject first = item.messages.get(0);
            if (first == null) continue;
            long dialogId = first.getDialogId();
            Integer delay = dialogDelay.get(dialogId);
            if (delay == null) {
                delay = slot * 200;
                dialogDelay.put(dialogId, delay);
                slot++;
            }
            final FeedItem fi = item;
            AndroidUtilities.runOnUIThread(() -> markFeedItemAsRead(fi), delay);
        }
        if (listView != null && listView.getAdapter() != null) {
            listView.getAdapter().notifyDataSetChanged();
        }
    }

    /**
     * "Очистка кэша" из меню трёх точек — удаляет весь реально скачанный медиаконтент
     * постов ленты (по всем загруженным каналам), с тем же подтверждением "Да/Отмена",
     * что и у "Удалить из кэша" в меню отдельного поста (см. PotokFeedPostCell) — по
     * образцу Plus Messenger, который пользователь прислал как референс.
     */
    private void clearFeedCache(Context context) {
        ArrayList<java.io.File> filesToDelete = new ArrayList<>();
        long totalSize = 0;
        for (FeedItem item : items) {
            if (item == null) continue;
            for (MessageObject mo : item.messages) {
                if (mo == null) continue;
                boolean hasMedia = mo.isVoice() || mo.isMusic() || mo.isVideo()
                    || (mo.photoThumbs != null && !mo.photoThumbs.isEmpty());
                if (!hasMedia) continue;
                mo.checkMediaExistance(false);
                if (mo.mediaExists) {
                    java.io.File f = FileLoader.getInstance(mo.currentAccount).getPathToMessage(mo.messageOwner, false);
                    if (f != null && f.exists()) {
                        filesToDelete.add(f);
                        totalSize += f.length();
                    }
                }
            }
        }
        if (filesToDelete.isEmpty()) {
            android.widget.Toast.makeText(context, "Кэш ленты пуст", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final long finalTotalSize = totalSize;
        final int account = UserConfig.selectedAccount;
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Очистка кэша");
        builder.setMessage("Удалить весь скачанный медиаконтент ленты?\n\nОчистить "
            + AndroidUtilities.formatFileSize(finalTotalSize) + "?\n\nВы можете скачать файлы позже");
        builder.setNegativeButton("Отмена", null);
        builder.setPositiveButton("Да", (d, w) -> {
            FileLoader.getInstance(account).deleteFiles(filesToDelete, 0);
            if (listView != null && listView.getAdapter() != null) {
                listView.getAdapter().notifyDataSetChanged();
            }
        });
        builder.show();
    }

    private PhotoViewer.PhotoViewerProvider photoViewerProvider;

    /**
     * Провайдер для PhotoViewer.openPhoto() — заменяет EmptyPhotoViewerProvider,
     * который раньше стоял в PotokFeedPostCell.openMediaViewer() и намеренно не давал
     * никакой информации об исходной миниатюре. getPlaceForPhoto() здесь реализован
     * ровно по тому же принципу, что и одноимённый приватный метод в самом
     * ChatActivity (для ChatMessageCell) — найти реально видимый на экране
     * ImageReceiver миниатюры и вернуть его координаты, чтобы PhotoViewer сыграл
     * анимацию разворота карточки на весь экран (и обратно при закрытии) вместо
     * простого появления/исчезновения.
     */
    public PhotoViewer.PhotoViewerProvider getPhotoViewerProvider() {
        if (photoViewerProvider == null) {
            photoViewerProvider = new PhotoViewer.EmptyPhotoViewerProvider() {
                @Override
                public PhotoViewer.PlaceProviderObject getPlaceForPhoto(MessageObject messageObject, TLRPC.FileLocation fileLocation, int index, boolean needPreview, boolean closing) {
                    return PotokFeedFragment.this.getPlaceForPhoto(messageObject);
                }
            };
        }
        return photoViewerProvider;
    }

    private PhotoViewer.PlaceProviderObject getPlaceForPhoto(MessageObject messageObject) {
        if (listView == null || messageObject == null) {
            return null;
        }
        int count = listView.getChildCount();
        for (int a = 0; a < count; a++) {
            View cellView = listView.getChildAt(a);
            if (!(cellView instanceof PotokFeedPostCell)) continue;
            PotokFeedPostCell cell = (PotokFeedPostCell) cellView;
            if (!cell.containsMessageId(messageObject.getId())) continue;
            // Координаты нужно брать у САМОГО BackupImageView с фото (вложен в
            // карусель внутри ячейки), а не у ячейки поста целиком — иначе
            // PhotoViewer разворачивает анимацию от верха всей карточки (шапки
            // канала), а не от реального места фото. См. подробный комментарий в
            // PotokFeedPostCell.getPhotoImageViewForMessage().
            org.telegram.ui.Components.BackupImageView photoImageView = cell.getPhotoImageViewForMessage(messageObject);
            if (photoImageView == null) continue;
            ImageReceiver imageReceiver = photoImageView.getImageReceiver();
            int[] coords = new int[2];
            photoImageView.getLocationInWindow(coords);
            PhotoViewer.PlaceProviderObject object = new PhotoViewer.PlaceProviderObject();
            object.viewX = coords[0];
            object.viewY = coords[1];
            object.parentView = listView;
            object.imageReceiver = imageReceiver;
            object.thumb = imageReceiver.getBitmapSafe();
            object.radius = imageReceiver.getRoundRadius(true);
            return object;
        }
        return null;
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        return true;
    }

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        getNotificationCenter().addObserver(this, NotificationCenter.didSetNewTheme);
        getNotificationCenter().addObserver(this, NotificationCenter.didUpdatePollResults);
        getNotificationCenter().addObserver(this, NotificationCenter.messagePlayingDidStart);
        getNotificationCenter().addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        getNotificationCenter().addObserver(this, NotificationCenter.messagePlayingDidReset);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        AndroidUtilities.cancelRunOnUIThread(debouncedRebuildRunnable);
        getNotificationCenter().removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        getNotificationCenter().removeObserver(this, NotificationCenter.didSetNewTheme);
        getNotificationCenter().removeObserver(this, NotificationCenter.didUpdatePollResults);
        getNotificationCenter().removeObserver(this, NotificationCenter.messagePlayingDidStart);
        getNotificationCenter().removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        getNotificationCenter().removeObserver(this, NotificationCenter.messagePlayingDidReset);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagePlayingProgressDidChanged) {
            if (listView == null) return;
            int mid = (Integer) args[0];
            int count = listView.getChildCount();
            for (int a = 0; a < count; a++) {
                View child = listView.getChildAt(a);
                if (child instanceof PotokFeedPostCell) {
                    ((PotokFeedPostCell) child).updateAudioProgressIfPlaying(mid);
                }
            }
        } else if (id == NotificationCenter.didSetNewTheme) {
            updateWallpaper();
        } else if (id == NotificationCenter.didUpdatePollResults) {
            // Тот же паттерн, что и messagePlayingProgressDidChanged выше: центр
            // уведомлений один на фрагмент, а не подписка в каждой отдельной ячейке —
            // рассылаем обновление только по реально видимым сейчас ячейкам ленты.
            if (listView == null) return;
            long pollId = (Long) args[0];
            TLRPC.TL_poll poll = (TLRPC.TL_poll) args[1];
            TLRPC.PollResults results = (TLRPC.PollResults) args[2];
            int count = listView.getChildCount();
            for (int a = 0; a < count; a++) {
                View child = listView.getChildAt(a);
                if (child instanceof PotokFeedPostCell) {
                    ((PotokFeedPostCell) child).updatePollIfMatching(pollId, poll, results);
                }
            }
        } else if (id == NotificationCenter.messagePlayingDidStart || id == NotificationCenter.messagePlayingPlayStateChanged || id == NotificationCenter.messagePlayingDidReset) {
            // В отличие от messagePlayingProgressDidChanged (шлётся только для
            // конкретного messageId) — эти три события общие для ЛЮБОЙ смены трека,
            // поэтому обновляем мини-плеер и обходим ВСЕ видимые ячейки (не только
            // совпадающую) — иначе кнопка play/pause внутри поста, который играл
            // раньше, осталась бы показывать устаревшее состояние.
            updateMiniPlayer();
            if (listView != null) {
                int count = listView.getChildCount();
                for (int a = 0; a < count; a++) {
                    View child = listView.getChildAt(a);
                    if (child instanceof PotokFeedPostCell) {
                        ((PotokFeedPostCell) child).refreshAudioPlaybackState();
                    }
                }
            }
        }
    }

    private void updateWallpaper() {
        if (fragmentView instanceof org.telegram.ui.Components.SizeNotifierFrameLayout) {
            ((org.telegram.ui.Components.SizeNotifierFrameLayout) fragmentView)
                .setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateWallpaper();
    }

    // ВАЖНО: раньше loadFeed() вызывался из onResume(). Проблема в том, что
    // onResume() здесь дёргается НЕ только при реальном переключении на вкладку
    // "Лента" — в архитектуре ViewPagerActivity (см. setVisibility()) он также
    // срабатывает при любом возврате фокуса ко всей Activity целиком, например
    // при закрытии PhotoViewer/системных диалогов/смене приложений, даже если
    // пользователь всё это время сидел на вкладке "Лента" и никуда с неё не
    // уходил. Из-за этого лента лишний раз дёргала messages.getHistory по всем
    // каналам — та самая "лишняя трата ресурсов". onBecomeFullyVisible(), в
    // отличие от onResume(), вызывается строго тогда, когда эта вкладка реально
    // становится полностью видимой страницей пейджера (см. ViewPagerActivity.
    // PageAdapter.setVisibility(), fragment.onBecomeFullyVisible() при
    // newVisibility >= 1) — это и есть корректный третий триггер обновления
    // ("вернулся в ленту с другого раздела"), не срабатывающий на посторонние
    // события. Итого обновление ленты теперь строго по 3 триггерам: первый показ
    // (createView() -> loadFeed(), см. выше), свайп-обновление (swipeRefreshLayout)
    // и вот этот — реальный возврат на вкладку.
    @Override
    public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
        loadFeed();
        updateMiniPlayer();
    }

    /**
     * Простой шеврон "^" (две сходящиеся вверх линии, без стержня) — по просьбе
     * убрать "стрелу", оставить только галочку.
     */
    private static class ArrowUpView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        ArrowUpView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(AndroidUtilities.dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            path.reset();
            float cx = w / 2f;
            float cy = h / 2f;
            float halfWidth = w * 0.34f;
            float halfHeight = h * 0.15f;

            path.moveTo(cx - halfWidth, cy + halfHeight);
            path.lineTo(cx, cy - halfHeight);
            path.lineTo(cx + halfWidth, cy + halfHeight);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawPath(path, paint);
        }
    }
}
