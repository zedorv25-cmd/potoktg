package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
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
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;

import org.telegram.ui.Components.BlurredFrameLayout;
import org.telegram.ui.Components.FragmentContextView;
import org.telegram.ui.Components.RoundVideoPlayingDrawable;
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
    // Публичный доступ нужен LaunchActivity — чтобы при решении открывать ли
    // боковую шторку по свайпу вправо проверить, не начался ли жест внутри
    // карусели медиа одного из видимых постов (см. isPointInsideMediaCarousel).
    public RecyclerListView getListView() {
        return listView;
    }
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefreshLayout;
    private boolean refreshingFeed = false;
    private LinearLayoutManager listViewLayoutManager;
    private org.telegram.ui.Components.RecyclerAnimationScrollHelper scrollHelper;
    private FrameLayout scrollToTopButton;
    // Мини-плеер сверху ленты — используем РЕАЛЬНЫЙ компонент Telegram
    // (org.telegram.ui.Components.FragmentContextView), тот же самый класс, что
    // рисует полоску воспроизведения в Чатах/ChatActivity/CallLogActivity. Он
    // полностью самодостаточен: сам подписывается на NotificationCenter
    // (messagePlayingDidStart/PlayStateChanged/DidReset) в onAttachedToWindow(),
    // сам показывает/скрывает себя, сам открывает полноэкранный плеер по тапу
    // (через parentFragment). Раньше здесь был самописный FrameLayout+TextView+
    // Path-иконка — это и была причина "кривого" плеера и бага с паузой по тапу.
    private FragmentContextView fragmentContextView;
    private RoundedBarContainer miniPlayerContainer;
    private BlurredFrameLayout miniPlayerGapBlur;

    // --- Инлайн-плеер видеокружка ---
    // 1:1 с ChatActivity.createTextureView()/destroyTextureView(): ОДИН общий
    // плавающий контейнер (не по контейнеру на ячейку), который переставляется
    // поверх той видимой ячейки, что сейчас держит играющий кружок. Если такая
    // ячейка не найдена среди видимых — MediaController сам переключает вывод
    // в PIP через setCurrentVideoVisible(false), см. updateRoundVideoTexturePosition().
    private FrameLayout roundVideoPlayerContainer;
    private AspectRatioFrameLayout roundVideoAspectRatioFrameLayout;
    private TextureView roundVideoTextureView;
    // ⚠️ ФИКС "надкус"/неправильная форма кружка (Сборка 1): раньше пустой
    // контейнер прятался через setVisibility(GONE). GONE-view пропускается
    // layout-проходом целиком и НЕ получает реальных measured-размеров — из-за
    // этого к моменту setTextureView(true) на новом видео контейнер ещё ни разу
    // не был измерен (containerMeasuredW/H=0 в логах ROUNDVID_CROP), и
    // ExoPlayer/TextureView стартовали с нулевого кадра. В оригинале
    // (ChatActivity.videoPlayerContainer) контейнер НИКОГДА не уходит в GONE —
    // он всегда VISIBLE и просто уводится за пределы экрана через
    // translationY (см. updateTextureViewPosition), поэтому у него всегда есть
    // реальный измеренный размер из фиксированных LayoutParams. Повторяем этот
    // подход 1:1: контейнер всегда VISIBLE, "спрятан" только этим смещением.
    // Значение — фиксированный размер контейнера (px) + запас 100px, считается
    // один раз при создании (см. createRoundVideoTextureView), используется
    // во всех местах, где раньше стоял setVisibility(GONE).
    private float roundVideoHiddenTranslationY;
    // Сообщение, для которого СЕЙЧАС зарегистрирован textureView в MediaController —
    // нужно, чтобы не дёргать setTextureView повторно на каждый чих (скролл),
    // а только при реальной смене играющего видеокружка.
    private int roundVideoTextureRegisteredForMessageId;
    // Кэш "было ли открыто" на момент, когда ячейка последний раз была видима —
    // нужен, чтобы решить PIP-или-пауза уже ПОСЛЕ того, как ячейка ушла с экрана
    // и её больше нельзя спросить напрямую (её не найти среди видимых детей).
    private boolean roundVideoOpenedCached;
    // Ячейка, поверх которой СЕЙЧАС стоит плавающий контейнер — единственный
    // источник правды о том, куда слать тап/перемотку и что открывать/закрывать.
    // Обновляется на каждый updateRoundVideoTexturePosition().
    private PotokFeedPostCell roundVideoActiveCell;
    // Кэш последнего известного сообщения/прогресса играющего кружка — нужен
    // ТОЛЬКО для надёжной детекции "доиграл естественно до конца" на
    // messagePlayingDidReset (см. didReceivedNotification) — messagePlayingDidReset
    // не несёт сам объект сообщения, только id.
    private MessageObject roundVideoLastKnownObject;
    private float roundVideoLastKnownProgress;
    // Живая "хрома" поверх видео — кольцо-прогресс с точкой-ручкой, плашка
    // прошло/всего, бегущий эквалайзер. Раньше это дублировалось В КАЖДОЙ
    // ячейке ПОД плавающим контейнером — из-за чего было видно кольцо максимум
    // на ~10% (видео сверху перекрывало) и тапы долетали через раз в
    // зависимости от точного совпадения границ. Теперь единственная копия,
    // нарисованная прямо здесь, поверх TextureView — то, что физически видно,
    // то и интерактивно, без рассинхрона.
    private RoundVideoRingView roundVideoRingView;
    private TextView roundVideoDurationView;
    private View roundVideoEqualizerView;
    private RoundVideoPlayingDrawable roundVideoPlayingDrawable;
    // Боковой отступ "острова" мини-плеера — 4dp с каждой стороны, ровно как в
    // DialogsActivityTopPanelLayout (реальный код Telegram для этого же бара).
    private static final int MINI_PLAYER_SIDE_MARGIN_DP = 4;
    private static final int MINI_PLAYER_HEIGHT_DP = 38;
    // Зазор между шапкой и мини-плеером ("пару миллиметров"), заполненный реальным
    // блюром обоев (тот же BlurredFrameLayout/SizeNotifierFrameLayout.drawBlurRect,
    // которым в самом Telegram блюрятся панели поверх чата — не самопальная имитация)
    // + лёгкое затемнение, точь-в-точь как под action bar/topPanel в оригинале.
    private static final int MINI_PLAYER_GAP_DP = 6;
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
     * Обёртка-"остров" вокруг настоящего FragmentContextView: скругление и боковые
     * отступы, посчитанные ТОЧНО как в реальном коде Telegram
     * (org.telegram.ui.Components.DialogsActivityTopPanelLayout): радиус =
     * min(24dp, высота/2), боковой отступ = 4dp с каждой стороны. Сам
     * FragmentContextView переводится в режим isInsideBubble = true (см. его
     * onDraw — рисует прозрачный фон вместо прямоугольной заливки на весь экран),
     * а закраску + скругление берёт на себя эта обёртка через clipPath.
     */
    private static class RoundedBarContainer extends FrameLayout {
        private final Path clipPath = new Path();
        private final RectF rectF = new RectF();
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        RoundedBarContainer(Context context) {
            super(context);
            setWillNotDraw(false);
            bgPaint.setColor(Theme.getColor(Theme.key_inappPlayerBackground));
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            rectF.set(0, 0, w, h);
            float radius = Math.min(AndroidUtilities.dp(24), h / 2f);
            clipPath.reset();
            clipPath.addRoundRect(rectF, radius, radius, Path.Direction.CW);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            canvas.drawPath(clipPath, bgPaint);
            int save = canvas.save();
            canvas.clipPath(clipPath);
            super.dispatchDraw(canvas);
            canvas.restoreToCount(save);
        }
    }

    private void setupActionBar(Context context) {
        if (actionBar == null) {
            return;
        }
        // Заголовок "typefeed" (по референсу пользователя, один тон, без разбивки
        // цветом) — раньше было "POTOK " + акцентным цветом "ЛЕНТА". Шрифт менять не
        // нужно: у ActionBar.titleTextView уже стоит AndroidUtilities.bold() по
        // умолчанию (см. ActionBar.java) — тот же жирный шрифт, что использует сам
        // Telegram, так что внешний вид уже совпадает с референсом без доп. правок.
        actionBar.setTitle("typefeed");
        // Кастомный шрифт ТОЛЬКО для этого заголовка — по референсу пользователя
        // (округлый геометричный жирный шрифт, визуально не похожий на стандартный
        // AndroidUtilities.bold(), который использует весь остальной интерфейс).
        // Poppins Bold — ближайший бесплатный аналог с открытой лицензией (OFL),
        // файл лежит в assets/fonts/poppins_bold.ttf. Применяется точечно через
        // getTitleTextView(), не через глобальный AndroidUtilities.bold() — весь
        // остальной интерфейс (включая другие заголовки) остаётся на оригинальном
        // шрифте Telegram, как того требует правило проекта "копировать 1:1".
        // Кастомный шрифт заголовка — Avenir Black (жирное начертание), по просьбе
        // пользователя. ВАЖНО: Avenir — платный лицензионный шрифт (Linotype/
        // Monotype), ассистент не может ни сгенерировать, ни скачать сам файл —
        // его должен предоставить пользователь (например, .ttf/.otf с личным
        // лицензионным экземпляром). Ожидаемый путь ниже. Раньше здесь стояла
        // ссылка на "fonts/poppins_bold.ttf" — этого файла в assets НЕ было
        // физически, попытка загрузки всегда падала в catch, и заголовок всё это
        // время рисовался обычным жирным шрифтом Telegram (AndroidUtilities.bold())
        // без пользователя заметно об этом — теперь хотя бы честно логируется.
        try {
            android.graphics.Typeface typefeedFont = android.graphics.Typeface.createFromAsset(
                context.getAssets(), "fonts/avenir_black.ttf"
            );
            if (actionBar.getTitleTextView() != null) {
                actionBar.getTitleTextView().setTypeface(typefeedFont);
            }
        } catch (Exception e) {
            // Файл fonts/avenir_black.ttf не найден в assets — заголовок остаётся
            // на дефолтном жирном шрифте Telegram, пока файл не будет добавлен.
            PotokDebugLog.log("PotokFeedLogo", "typefeed: fonts/avenir_black.ttf не найден в assets, использован дефолтный шрифт: " + e.getMessage());
        }

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
        // ФИКС теории "внешний слой обрезает видеокружок в закрытом состоянии":
        // setClipToPadding(false) выше отключает клип ТОЛЬКО по паддингу (тому,
        // что резервирует место под ActionBar сверху и таббар снизу — см. комментарий
        // над listView.setPadding()). Это НЕ то же самое, что clipChildren — отдельный
        // флаг ViewGroup, который у RecyclerView по умолчанию true и обрезает детей
        // строго по СОБСТВЕННЫМ пиксельным границам listView, независимо от паддинга.
        // roundVideoContainer сидит близко к верхнему краю карточки поста
        // (topMargin=10dp) — когда пост при скролле частично уезжает за верхнюю
        // границу listView (под ActionBar/мини-плеер) или за нижнюю (под таббар),
        // clipChildren=true режет кружок ровно по этой линии — визуально это и есть
        // "полумесяц". clipToPadding(false) без парного clipChildren(false) не решает
        // проблему до конца, т.к. clipChildren трогает границы самого view, а не
        // паддинга. НЕ ПОДТВЕРЖДЕНО тестом на живом баге — см. CLIP_CHECK-лог ниже.
        listView.setClipChildren(false);

        // --- Мини-плеер сверху ленты ---
        // РЕАЛЬНЫЙ компонент Telegram, не свой: тот же org.telegram.ui.Components.
        // FragmentContextView, что используется в ChatActivity/CallLogActivity/
        // DialogsActivity. Класс сам подписывается на NotificationCenter.
        // messagePlayingDidStart/PlayStateChanged/DidReset в onAttachedToWindow(),
        // сам решает когда показываться/скрываться, сам открывает полноэкранный
        // плеер по тапу — ничего из этого руками дублировать не нужно.
        // isInsideBubble = true — тот же флаг, что выставляют DialogsActivity/
        // ChatActivity, когда хостят этот бар внутри скруглённого "острова":
        // отключает его собственную прямоугольную заливку на весь экран (см.
        // FragmentContextView — frameLayout.setBackgroundColor(isInsideBubble ? 0
        // : ...)), заливку и скругление берёт на себя RoundedBarContainer ниже.
        fragmentContextView = new FragmentContextView(context, this, false) {
            @Override
            public void setVisibility(int visibility) {
                super.setVisibility(visibility);
                if (miniPlayerContainer != null) {
                    miniPlayerContainer.setVisibility(visibility);
                }
                if (miniPlayerGapBlur != null) {
                    miniPlayerGapBlur.setVisibility(visibility);
                }
                applyTopLayout(lastAppliedTopInset >= 0 ? lastAppliedTopInset : cachedStatusBarHeight);
            }
        };
        fragmentContextView.isInsideBubble = true;
        miniPlayerContainer = new RoundedBarContainer(context);
        miniPlayerContainer.setVisibility(View.GONE);
        miniPlayerContainer.addView(fragmentContextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // Полоса-зазор между шапкой и мини-плеером: "пару миллиметров" воздуха, а не
        // плеер впритык к заголовку. Заполнена РЕАЛЬНЫМ блюром обоев чата — тем же
        // механизмом (SizeNotifierFrameLayout.drawBlurRect через BlurredFrameLayout),
        // которым в оригинальном Telegram блюрятся панели поверх контента (topPanel,
        // FragmentContextView сам по себе при isInsideBubble=false и т.д.) — не
        // самопальная имитация полупрозрачным прямоугольником без блюра. Поверх блюра —
        // лёгкое затемнение тем же способом, что и заливка мини-плеера в самом
        // Telegram (полупрозрачный чёрный поверх блюра, см. backgroundColor ниже).
        miniPlayerGapBlur = new BlurredFrameLayout(context, frameLayout);
        miniPlayerGapBlur.backgroundColor = 0x40000000;
        miniPlayerGapBlur.isTopView = true;
        miniPlayerGapBlur.setVisibility(View.GONE);
        // ВАЖНО: addView(...) сюда НЕ вставляем — FrameLayout рисует детей в порядке
        // добавления (последний = поверх), а swipeRefreshLayout с listView внутри (во
        // весь экран, добавляется ниже по коду) перекрыл бы и зазор, и мини-плеер
        // целиком. addView вызывается ПОСЛЕ swipeRefreshLayout — см. дальше по методу,
        // сразу за ним.

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
        // Мини-плеер и зазор-блюр добавляются ЗДЕСЬ, после swipeRefreshLayout — иначе
        // список (во весь экран) рисуется поверх и полностью их перекрывает (см.
        // комментарий у создания fragmentContextView/miniPlayerContainer выше).
        // Зазор — на всю ширину без боковых отступов (сплошная блюр-полоса под
        // заголовком, как под action bar в оригинале), мини-плеер — с боковыми
        // отступами 4dp, как в оригинале (DialogsActivityTopPanelLayout). Точная Y-
        // позиция обоих считается в applyTopLayout() ниже.
        frameLayout.addView(miniPlayerGapBlur, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, MINI_PLAYER_GAP_DP, Gravity.TOP));
        frameLayout.addView(miniPlayerContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, MINI_PLAYER_HEIGHT_DP, Gravity.TOP,
                MINI_PLAYER_SIDE_MARGIN_DP, 0, MINI_PLAYER_SIDE_MARGIN_DP, 0));

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

        createRoundVideoTextureView(context);
        // ⚠️ ФИКС (эта сессия): раньше тут была СЫРАЯ AndroidUtilities.roundPlayingMessageSize(false)
        // — та же формула, что была найдена неверной для PotokFeedPostCell.getCorrectedRoundVideoBigSize()
        // (не учитывает отступы поста в ленте). Контейнер с РЕАЛЬНЫМ играющим видео
        // жил по старой, большей формуле, а видимый круг под ним — по скорректированной,
        // меньшей. Из-за этого кольцо-прогресс и зона перемотки (обе считаются от
        // фактической ширины/высоты этого контейнера) не совпадали с видимым кругом:
        // кольцо рисовалось у края НЕВИДИМОГО большего контейнера, а не у края видимого
        // круга, и палец на перемотке промахивался мимо реальной зоны касания.
        int correctedBigSize = org.telegram.ui.Cells.PotokFeedPostCell.getCorrectedRoundVideoBigSize();
        frameLayout.addView(roundVideoPlayerContainer, new FrameLayout.LayoutParams(
                correctedBigSize, correctedBigSize));
        // ⚠️ ФИКС (см. комментарий у поля roundVideoHiddenTranslationY выше):
        // никогда не GONE — прячем уводом за экран, контейнер остаётся частью
        // обычного layout-прохода и всегда имеет реальный измеренный размер.
        roundVideoHiddenTranslationY = -correctedBigSize - 100;
        roundVideoPlayerContainer.setTranslationY(roundVideoHiddenTranslationY);
        // ⚠️ ФИКС (наезд открытого кружка на верхнюю полосу мини-плеера при
        // скролле ленты): roundVideoPlayerContainer добавлен в frameLayout
        // последним из всех плавающих элементов (после miniPlayerGapBlur,
        // miniPlayerContainer и scrollToTopButton) — в обычном z-order
        // Android то, что добавлено позже, рисуется поверх того, что раньше.
        // Явно возвращаем полосу мини-плеера (и зазор-блюр под ней) и кнопку
        // "наверх" на самый верх стека, чтобы кружок при скролле проходил
        // ПОД ними, а не поверх — порядок остального addView не меняем.
        frameLayout.bringChildToFront(miniPlayerGapBlur);
        frameLayout.bringChildToFront(miniPlayerContainer);
        frameLayout.bringChildToFront(scrollToTopButton);

        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView rv, int newState) {
                PotokDebugLog.d("SCROLL_FEED", "onScrollStateChanged newState="
                        + (newState == RecyclerView.SCROLL_STATE_IDLE ? "IDLE"
                        : newState == RecyclerView.SCROLL_STATE_DRAGGING ? "DRAGGING"
                        : newState == RecyclerView.SCROLL_STATE_SETTLING ? "SETTLING(fling)" : String.valueOf(newState)));
            }

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
                // ⚠️ TARGETPOST/CLIP_CHECK — проверка теории "listView.clipChildren
                // режет кружок по своей верхней/нижней границе". Пишется БЕЗУСЛОВНО
                // на каждый onScrolled, пока целевой пост присутствует среди
                // attached-детей listView (не троттлится — скролл-тестов немного,
                // объём терпимый). Сверяет реальные экранные Y кружка (уже с учётом
                // scale) против экранных Y самого listView (0/height listView на
                // экране = ровно та линия, где обрежет clipChildren).
                for (int a = 0; a < listView.getChildCount(); a++) {
                    View child = listView.getChildAt(a);
                    if (!(child instanceof PotokFeedPostCell)) continue;
                    PotokFeedPostCell cell = (PotokFeedPostCell) child;
                    if (cell.getRoundVideoMessageId() != PotokDebugLog.TARGET_MESSAGE_ID) continue;
                    int[] bounds = cell.getRoundVideoVisibleScreenBoundsForLog();
                    if (bounds == null) continue;
                    int[] listLoc = new int[2];
                    listView.getLocationOnScreen(listLoc);
                    int listTop = listLoc[1];
                    int listBottom = listLoc[1] + listView.getHeight();
                    boolean clippedTop = bounds[1] < listTop;
                    boolean clippedBottom = bounds[3] > listBottom;
                    PotokDebugLog.d("TARGETPOST", "CLIP_CHECK msgId=" + PotokDebugLog.TARGET_MESSAGE_ID
                            + " circleTop=" + bounds[1] + " circleBottom=" + bounds[3]
                            + " listViewTop=" + listTop + " listViewBottom=" + listBottom
                            + " CLIPPED_BY_TOP=" + clippedTop + " CLIPPED_BY_BOTTOM=" + clippedBottom
                            + " clipChildren=" + listView.getClipChildren());
                }
                // ⚠️ THEORYCHECK (пост 967) — теория: swipeRefreshLayout, который
                // ОБОРАЧИВАЕТ listView, тоже ViewGroup со своим clipChildren=true
                // по умолчанию, который никогда не трогали. Сверяем экранные
                // границы кружка против экранных границ именно swipeRefreshLayout
                // (не listView — это разные view, у swipeRefreshLayout свои
                // границы, обычно совпадают с listView, но не обязаны).
                for (int a = 0; a < listView.getChildCount(); a++) {
                    View child = listView.getChildAt(a);
                    if (!(child instanceof PotokFeedPostCell)) continue;
                    PotokFeedPostCell cell = (PotokFeedPostCell) child;
                    if (cell.getRoundVideoMessageId() != PotokDebugLog.THEORY_SWIPEREFRESH_MSGID) continue;
                    int[] bounds = cell.getRoundVideoVisibleScreenBoundsForLog();
                    if (bounds == null || swipeRefreshLayout == null) continue;
                    int[] srlLoc = new int[2];
                    swipeRefreshLayout.getLocationOnScreen(srlLoc);
                    int srlTop = srlLoc[1];
                    int srlBottom = srlLoc[1] + swipeRefreshLayout.getHeight();
                    boolean clippedTop = bounds[1] < srlTop;
                    boolean clippedBottom = bounds[3] > srlBottom;
                    PotokDebugLog.d("THEORYCHECK", "SWIPEREFRESH_CLIP msgId=" + PotokDebugLog.THEORY_SWIPEREFRESH_MSGID
                            + " circleTop=" + bounds[1] + " circleBottom=" + bounds[3]
                            + " swipeRefreshTop=" + srlTop + " swipeRefreshBottom=" + srlBottom
                            + " CLIPPED_BY_TOP=" + clippedTop + " CLIPPED_BY_BOTTOM=" + clippedBottom
                            + " swipeRefreshClipChildren=" + swipeRefreshLayout.getClipChildren());
                }
                // ⚠️ THEORYCHECK (пост 968) — теория: сверху рисуется оверлей
                // (miniPlayerGapBlur — блюр-полоса под мини-плеер, добавлен ПОСЛЕ
                // swipeRefreshLayout в frameLayout.addView, значит рисуется ПОВЕРХ
                // ленты по z-order), который может перекрывать часть круга, если
                // пост оказывается рядом с верхом экрана. Проверяем пересечение
                // прямоугольников экранных координат круга и оверлея.
                for (int a = 0; a < listView.getChildCount(); a++) {
                    View child = listView.getChildAt(a);
                    if (!(child instanceof PotokFeedPostCell)) continue;
                    PotokFeedPostCell cell = (PotokFeedPostCell) child;
                    if (cell.getRoundVideoMessageId() != PotokDebugLog.THEORY_OVERLAY_MSGID) continue;
                    int[] bounds = cell.getRoundVideoVisibleScreenBoundsForLog();
                    if (bounds == null || miniPlayerGapBlur == null) continue;
                    int[] overlayLoc = new int[2];
                    miniPlayerGapBlur.getLocationOnScreen(overlayLoc);
                    int overlayTop = overlayLoc[1];
                    int overlayBottom = overlayLoc[1] + miniPlayerGapBlur.getHeight();
                    boolean intersects = bounds[1] < overlayBottom && bounds[3] > overlayTop
                            && miniPlayerGapBlur.getVisibility() == View.VISIBLE;
                    PotokDebugLog.d("THEORYCHECK", "OVERLAY_CHECK msgId=" + PotokDebugLog.THEORY_OVERLAY_MSGID
                            + " circleTop=" + bounds[1] + " circleBottom=" + bounds[3]
                            + " overlayTop=" + overlayTop + " overlayBottom=" + overlayBottom
                            + " overlayVisibility=" + miniPlayerGapBlur.getVisibility()
                            + " INTERSECTS_OVERLAY=" + intersects);
                }
                // Пост, докрутившийся до видимой области экрана, считается
                // просмотренным — засчитываем это как прочтение в чате канала.
                checkVisibleFeedItemsRead();
                updateRoundVideoTexturePosition();
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
     * мини-плеера. Вызывается и из WindowInsets-колбэка, и из переопределённого
     * fragmentContextView.setVisibility() при появлении/скрытии бара — иначе
     * появление бара наезжало бы на первый пост,
     * не сдвигая содержимое ленты вниз (как это происходит в самом Telegram, когда
     * появляется полоска воспроизведения).
     */
    private void applyTopLayout(int topInset) {
        int actionBarBottom = topInset + ActionBar.getCurrentActionBarHeight();
        boolean miniPlayerShown = miniPlayerContainer != null && miniPlayerContainer.getVisibility() == View.VISIBLE;
        int gapPx = miniPlayerShown ? AndroidUtilities.dp(MINI_PLAYER_GAP_DP) : 0;
        int miniPlayerHeightPx = miniPlayerShown ? AndroidUtilities.dp(MINI_PLAYER_HEIGHT_DP) : 0;
        if (listView != null) {
            listView.setPadding(0, actionBarBottom + gapPx + miniPlayerHeightPx, 0, AndroidUtilities.dp(56));
        }
        if (miniPlayerGapBlur != null) {
            ViewGroup.LayoutParams gapLpRaw = miniPlayerGapBlur.getLayoutParams();
            if (gapLpRaw instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams gapLp = (FrameLayout.LayoutParams) gapLpRaw;
                // Фикс "слабый блюр на части постов при первом открытии ленты":
                // setLayoutParams() у Android ВСЕГДА вызывает requestLayout(),
                // даже если реальные значения не изменились. WindowInsets-колбэк
                // на части устройств (в т.ч. Samsung) повторно диспатчится на
                // каждом layout-проходе окна — получалась цепная реакция insets
                // -> applyTopLayout() -> setLayoutParams() -> requestLayout() ->
                // снова insets, ~90 раз за первые ~1.5с. Каждый такой проход
                // перекладывал ВСЁ окно, из-за чего видимые посты в карусели
                // ленты биндились заново на каждый кадр — асинхронная декодировка/
                // блюр их thumbnail'ов обрывалась и перезапускалась 90 раз подряд,
                // не успевая докрутиться (см. лог [BLUR], спам на post=49308,
                // начавшийся в ту же миллисекунду, что первый WindowInsets-лог).
                // Теперь дёргаем requestLayout() только если margin реально другой.
                if (gapLp.topMargin != actionBarBottom) {
                    gapLp.topMargin = actionBarBottom;
                    miniPlayerGapBlur.setLayoutParams(gapLp);
                }
            }
        }
        if (miniPlayerContainer != null) {
            ViewGroup.LayoutParams lpRaw = miniPlayerContainer.getLayoutParams();
            if (lpRaw instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) lpRaw;
                int newTopMargin = actionBarBottom + gapPx;
                if (lp.topMargin != newTopMargin) {
                    lp.topMargin = newTopMargin;
                    miniPlayerContainer.setLayoutParams(lp);
                }
            }
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

        // РЕШЕНИЕ (по прямому подтверждению пользователя со скриншотами настоящего
        // канала): опрос и медиа-пост, идущие подряд без ничего между ними, в самом
        // Telegram-канале — это ДВА полностью независимых сообщения со своими
        // отдельными шапками, реакциями и просмотрами (не один "альбом"). Склейка их
        // в одну визуальную карточку ленты (была здесь раньше — см. историю коммитов
        // ec111ce4c и позже, POLL_MERGE) визуально выглядела как один слипшийся пост
        // без отступа между медиа и опросом, что не соответствует реальному виду в
        // канале. Склейка полностью убрана — opros-only и media-only FeedItem теперь
        // остаются двумя отдельными элементами result, как и обычные посты.
        return result;
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
        if (roundVideoTextureRegisteredForMessageId != 0 && roundVideoTextureView != null) {
            MediaController.getInstance().setTextureView(roundVideoTextureView, roundVideoAspectRatioFrameLayout, roundVideoPlayerContainer, false);
            roundVideoTextureRegisteredForMessageId = 0;
        }
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
            // Кэшируем последний известный прогресс играющего видеокружка — нужно
            // ТОЛЬКО для детекции "доиграл естественно до конца" на
            // messagePlayingDidReset ниже (см. подробный комментарий там). Живая
            // отрисовка кольца/текста в плавающем контейнере обновляется отдельно,
            // в самом ring-view через invalidate() по таймеру, см. createRoundVideoTextureView.
            MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (playing != null && playing.isRoundVideo() && playing.getId() == mid) {
                roundVideoLastKnownObject = playing;
                roundVideoLastKnownProgress = playing.audioProgress;
            }
            if (roundVideoRingView != null) roundVideoRingView.invalidate();
            if (roundVideoDurationView != null) updateRoundVideoChromeTexts();
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
        } else if (id == NotificationCenter.messagePlayingDidReset) {
            // ⚠️ Единственное место, где решаем "видеокружок доиграл ЕСТЕСТВЕННО
            // до конца" (а не просто пользователь переключился на что-то другое
            // или свернул приложение) — раньше это пытались ловить по потоку
            // messagePlayingProgressDidChanged (progress>=0.999), но это гонка:
            // после перезапуска playMessage(mo,true) следующий же progress-тик
            // мог снова оказаться близко к 1.0 ДО того, как реально обнулился,
            // и цикл перезапускался раз за разом без остановки (баг "полоса идёт
            // заново без остановки", который явно описал пользователь).
            // messagePlayingDidReset — одноразовое дискретное событие на сессию
            // воспроизведения, гонки внутри одного события быть не может.
            int resetMid = args.length > 0 && args[0] instanceof Integer ? (Integer) args[0] : 0;
            // ⚠️ ФИКС "истукан после завершения" (этот заход): в оригинале
            // (ChatActivity.didReceivedNotification, ветка messagePlayingDidReset)
            // НЕТ никакого порога прогресса — просто проверяется, что это
            // round-видео и что оно СЕЙЧАС не играет, и сразу сбрасывается.
            // Мой прежний порог roundVideoLastKnownProgress >= 0.98f был
            // придуман с головы, а не из оригинала — и для короткого ролика
            // (в жалобе пользователя длительность 0:04) обновления прогресса
            // идут слишком редко, порог мог просто не успеть накопиться до
            // 0.98 к моменту сброса — тогда ветка вообще не срабатывала, и
            // кружок оставался замороженным большим стоп-кадром навсегда.
            boolean wasNaturalRoundVideoFinish = roundVideoLastKnownObject != null
                    && roundVideoLastKnownObject.getId() == resetMid
                    && roundVideoActiveCell != null
                    && roundVideoActiveCell.isRoundVideoOpened();
            if (wasNaturalRoundVideoFinish) {
                // ⚠️ ФИКС (эта сессия): раньше здесь ПОВТОРНО звали
                // MediaController.playMessage(finished, true) — то есть даже
                // после архитектурного фикса автоплея закрытого кружка на
                // ImageReceiver, именно в момент "доиграл до конца" старый баг
                // возвращался бы обратно (снова поднимал верхнюю полосу плеера
                // для беззвучного состояния). setRoundVideoOpenVisual(false, true)
                // теперь САМ отвечает за возврат в состояние C — внутри него
                // ImageReceiver.startAnimation() заново запускает беззвучный
                // цикл, MediaController здесь больше не нужен вообще.
                roundVideoActiveCell.setRoundVideoOpenVisual(false, true);
                // ⚠️ ФИКС "истукан" (подстраховка, 1:1 с духом оригинала): в
                // ChatActivity на этом же событии ячейка не просто патчится
                // вручную — вызывается messageObject.forceUpdate=true +
                // notifyItemChanged(position), то есть состояние гарантированно
                // проходит через чистый путь ребайнда, а не полагается только на
                // ручной вызов одного метода. Делаем то же самое здесь —
                // подчищаем этим же путём, если ручной сброс почему-то не взялся.
                if (listView != null) {
                    final PotokFeedPostCell cellForRebindCheck = roundVideoActiveCell;
                    listView.postDelayed(() -> {
                        // Подстраховка выполняется ПОСЛЕ 250мс-анимации сжатия
                        // (см. applyRoundVideoScale) — если она сама прошла
                        // успешно, ребайнд просто ничего визуально не меняет
                        // (то же самое состояние C); если что-то не взялось —
                        // гарантированно чинит через чистый путь bind().
                        if (cellForRebindCheck.isRoundVideoOpened()) return; // уже переоткрыли — не мешаем
                        int pos = listView.getChildAdapterPosition(cellForRebindCheck);
                        if (pos != RecyclerView.NO_POSITION && listView.getAdapter() != null) {
                            listView.getAdapter().notifyItemChanged(pos);
                        }
                    }, 300);
                }
            }
            roundVideoLastKnownObject = null;
            roundVideoLastKnownProgress = 0f;
            updateRoundVideoTexturePosition();
        } else if (id == NotificationCenter.messagePlayingDidStart || id == NotificationCenter.messagePlayingPlayStateChanged) {
            // В отличие от messagePlayingProgressDidChanged (шлётся только для
            // конкретного messageId) — это событие общее для ЛЮБОЙ смены трека,
            // поэтому обходим ВСЕ видимые ячейки (не только совпадающую) — иначе
            // кнопка play/pause внутри поста, который играл раньше, осталась бы
            // показывать устаревшее состояние. Сам мини-плеер (fragmentContextView)
            // обновляет себя сам — он подписан на эти же события напрямую.
            if (listView != null) {
                int count = listView.getChildCount();
                for (int a = 0; a < count; a++) {
                    View child = listView.getChildAt(a);
                    if (child instanceof PotokFeedPostCell) {
                        ((PotokFeedPostCell) child).refreshAudioPlaybackState();
                    }
                }
            }
            updateRoundVideoTexturePosition();
            updateRoundVideoChromeTexts();
        }
    }

    /**
     * 1:1 с ChatActivity.createTextureView() — один общий контейнер
     * (FrameLayout -> AspectRatioFrameLayout -> TextureView), круглая обрезка
     * через ViewOutlineProvider (у нас всегда круг, в отличие от ChatActivity,
     * которому нужны и прямоугольные варианты — поэтому проще: всегда setOval).
     */
    private void createRoundVideoTextureView(Context context) {
        if (roundVideoPlayerContainer != null) return;
        roundVideoPlayerContainer = new FrameLayout(context);
        roundVideoPlayerContainer.setOutlineProvider(new ViewOutlineProvider() {
            private int lastLoggedW = -1, lastLoggedH = -1;
            @Override
            public void getOutline(View view, Outline outline) {
                int w = view.getMeasuredWidth();
                int h = view.getMeasuredHeight();
                // ⚠️ ДИАГНОСТИКА (логи ROUNDVID_CROP): логируем только при ИЗМЕНЕНИИ
                // размера (не на каждый вызов — getOutline может дёргаться очень
                // часто при анимациях). Если w != h в момент бага "крестообразной"
                // обрезки — это прямое доказательство, что клип строится по
                // неквадратному прямоугольнику (сам оверлей не квадратный в этот
                // момент), а не проблема где-то глубже в MediaController/текстуре.
                if (w != lastLoggedW || h != lastLoggedH) {
                    lastLoggedW = w;
                    lastLoggedH = h;
                    PotokDebugLog.d("ROUNDVID_CROP", "getOutline size changed w=" + w + " h=" + h
                        + " scaleX=" + view.getScaleX() + " scaleY=" + view.getScaleY()
                        + " square=" + (w == h));
                }
                outline.setOval(0, 0, w, h);
            }
        });
        roundVideoPlayerContainer.setClipToOutline(true);
        roundVideoPlayerContainer.setWillNotDraw(false);

        roundVideoAspectRatioFrameLayout = new AspectRatioFrameLayout(context);
        roundVideoAspectRatioFrameLayout.setBackgroundColor(0);
        roundVideoPlayerContainer.addView(roundVideoAspectRatioFrameLayout,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

        roundVideoTextureView = new TextureView(context);
        roundVideoTextureView.setOpaque(false);
        roundVideoAspectRatioFrameLayout.addView(roundVideoTextureView,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        // ⚠️ ДИАГНОСТИКА (логи ROUNDVID_CROP): раньше логировался только размер
        // КОНТЕЙНЕРА (всегда 984x984, подтверждено). Ни разу не логировался
        // реальный размер самой TextureView ПОСЛЕ того, как AspectRatioFrameLayout
        // её переизмерил под соотношение сторон видео — а именно это финальное
        // значение и есть то, что физически видно под маской. Если w/h тут
        // меньше, чем у контейнера — видео физически не долетает до края круга,
        // это и есть источник "надкуса", без предположений.
        roundVideoTextureView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            private int lastW = -1, lastH = -1;
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int w = right - left;
                int h = bottom - top;
                if (w != lastW || h != lastH) {
                    lastW = w;
                    lastH = h;
                    int containerW = roundVideoPlayerContainer != null ? roundVideoPlayerContainer.getMeasuredWidth() : -1;
                    int containerH = roundVideoPlayerContainer != null ? roundVideoPlayerContainer.getMeasuredHeight() : -1;
                    PotokDebugLog.d("ROUNDVID_CROP", "textureView real layout w=" + w + " h=" + h
                        + " containerW=" + containerW + " containerH=" + containerH
                        + " gapW=" + (containerW - w) + " gapH=" + (containerH - h));
                    // ⚠️ ДИАГНОСТИКА (ROUNDVID_CROP, самый глубокий уровень): читаем
                    // (не переустанавливаем!) transform matrix текущего буфера, если он
                    // уже доступен — покажет реальное растяжение/обрезку кадра декодера
                    // внутри TextureView, независимо от layout/outline (оба уже square).
                    if (roundVideoTextureView != null && roundVideoTextureView.isAvailable()) {
                        try {
                            android.graphics.SurfaceTexture st = roundVideoTextureView.getSurfaceTexture();
                            if (st != null) {
                                float[] m = new float[16];
                                st.getTransformMatrix(m);
                                PotokDebugLog.d("ROUNDVID_CROP", "surfaceTexture matrix=" + java.util.Arrays.toString(m));
                            }
                        } catch (Throwable t) {
                            PotokDebugLog.d("ROUNDVID_CROP", "surfaceTexture matrix read failed: " + t);
                        }
                    }
                }
            }
        });

        // ⚠️ ДИАГНОСТИКА (ROUNDVID_CROP, самый глубокий уровень): матрица трансформации
        // реального буфера SurfaceTexture. НЕ используем setSurfaceTextureListener —
        // ExoPlayer сам вызывает textureView.setSurfaceTextureListener(...) внутри
        // player.setVideoTextureView() при каждом playMessage() (см.
        // ExoPlayerImpl.java:1380) и перезаписал бы наш listener, сломав нам
        // диагностику после первого воспроизведения. Вместо этого читаем
        // getSurfaceTexture() из уже существующего, безопасного onLayoutChangeListener
        // выше — только чтение, ничего не переустанавливаем.
        // рисует детей в порядке добавления). Единственная копия, физически
        // видимая = единственная копия, за которую отвечает тач-обработчик ниже.
        roundVideoRingView = new RoundVideoRingView(context);
        roundVideoPlayerContainer.addView(roundVideoRingView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        roundVideoPlayingDrawable = new RoundVideoPlayingDrawable(roundVideoPlayerContainer, null);

        roundVideoDurationView = new TextView(context);
        roundVideoDurationView.setTextColor(Color.WHITE);
        roundVideoDurationView.setTextSize(12);
        roundVideoDurationView.setTypeface(org.telegram.messenger.AndroidUtilities.bold());
        roundVideoDurationView.setBackground(Theme.createRoundRectDrawable(org.telegram.messenger.AndroidUtilities.dp(10), 0x66000000));
        roundVideoDurationView.setPadding(org.telegram.messenger.AndroidUtilities.dp(6), org.telegram.messenger.AndroidUtilities.dp(2),
                org.telegram.messenger.AndroidUtilities.dp(6), org.telegram.messenger.AndroidUtilities.dp(2));
        roundVideoDurationView.setVisibility(View.GONE);
        roundVideoPlayerContainer.addView(roundVideoDurationView,
                LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 8, 8));

        roundVideoEqualizerView = new View(context) {
            @Override
            protected void onDraw(Canvas canvas) {
                if (roundVideoPlayingDrawable == null) return;
                roundVideoPlayingDrawable.setBounds(0, 0, getWidth(), getHeight());
                roundVideoPlayingDrawable.draw(canvas);
            }
        };
        roundVideoEqualizerView.setWillNotDraw(false);
        roundVideoEqualizerView.setVisibility(View.GONE);
        roundVideoPlayerContainer.addView(roundVideoEqualizerView,
                LayoutHelper.createFrame(14, 14, Gravity.BOTTOM | Gravity.LEFT, 8, 0, 0, 8));

        // Тап — play/pause (или "открыть", если кружок ещё не был открыт — тап на
        // МАЛЕНЬКОМ беззвучном автоплее переводит в большое состояние со звуком).
        // Перемотка — только когда открыт И на паузе, в кольцевой зоне у края.
        // 1:1 с checkRoundSeekbar из ChatMessageCell, но теперь на ЕДИНСТВЕННОМ
        // физически видимом слое, а не под видео.
        roundVideoPlayerContainer.setOnTouchListener(new View.OnTouchListener() {
            private boolean dragging;
            private long lastSeekUpdateTime;
            private float downX, downY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // ⚠️ ДИАГНОСТИКА (логи ROUNDVID_SEEK): лог САМОГО ВЕРХА метода,
                // безусловно, на ACTION_DOWN — до всех return false выше. Если при
                // воспроизведении бага (шторка открылась) в логе НЕТ этой строки —
                // значит палец физически не попал в этот оверлей вообще (например
                // оверлей не успел встать на позицию активной ячейки), и проблема
                // не в disallowIntercept ниже, а в синхронизации позиции. Если
                // строка ЕСТЬ — идём разбирать remainder лога по этому же жесту.
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    int[] locOnScreen = new int[2];
                    v.getLocationOnScreen(locOnScreen);
                    PotokDebugLog.d("ROUNDVID_SEEK", "onTouch ACTION_DOWN ENTRY"
                        + " rawX=" + event.getRawX() + " rawY=" + event.getRawY()
                        + " localX=" + event.getX() + " localY=" + event.getY()
                        + " viewScreenX=" + locOnScreen[0] + " viewScreenY=" + locOnScreen[1]
                        + " viewW=" + v.getWidth() + " viewH=" + v.getHeight()
                        + " scaleX=" + v.getScaleX() + " scaleY=" + v.getScaleY()
                        + " roundVideoActiveCell=" + (roundVideoActiveCell != null));
                }
                if (roundVideoActiveCell == null) return false;
                // ⚠️ ФИКС "тап открывает через раз": источник MessageObject — сама
                // ячейка (живёт независимо от MediaController), а не
                // MediaController.getPlayingMessageObject() (тот null для закрытого
                // тихого автоплея через ImageReceiver — см. коммент у геттера).
                MessageObject mo = roundVideoActiveCell.getRoundVideoMessageObject();
                if (mo == null || !mo.isRoundVideo()) return false;
                // ⚠️ ФИКС (эта сессия): раньше этот оверлей ловил ACTION_DOWN
                // безусловно (return true), даже для ЗАКРЫТОГО кружка — то есть
                // пытался сам решать "открыть или нет" на ACTION_UP. Проблема: этот
                // оверлей не всегда синхронизирован/видим именно над той ячейкой,
                // на которую реально смотрит и тапает пользователь (синхронизация
                // идёт по редким событиям плеера/скролла, не по каждому кадру) —
                // отсюда 3-8 попыток и случайные "перекидывания" в других местах.
                // Теперь для ЗАКРЫТОГО состояния оверлей вообще не забирает
                // касание — return false пропускает его дальше по стандартной
                // Android-цепочке, и его надёжно ловит OnClickListener прямо на
                // самой ячейке (roundVideoContainer в PostCell, см. openRoundVideo()) —
                // как в оригинале, где тап на круглое видео ловится самой
                // ChatMessageCell напрямую, а не внешним слушателем.
                if (!roundVideoActiveCell.isRoundVideoOpened()) return false;
                float cx = v.getWidth() / 2f;
                float cy = v.getHeight() / 2f;
                float dx = event.getX() - cx;
                float dy = event.getY() - cy;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                boolean paused = roundVideoActiveCell.isRoundVideoOpened()
                        && MediaController.getInstance().isPlayingMessage(mo)
                        && MediaController.getInstance().isMessagePaused();
                // ⚠️ ФИКС "шторка иногда перехватывает перемотку": зона касания
                // раньше была фиксированной (28dp от края), а кольцо на паузе
                // теперь визуально уходит внутрь на ~17.5dp (см. onDraw кольца —
                // 1.5dp + 16dp при паузе). Палец на реальном кольце иногда
                // промахивался мимо старой зоны касания → dragging=false →
                // requestDisallowInterceptTouchEvent не вызывался → шторка
                // перехватывала жест. Расширяем зону так, чтобы гарантированно
                // накрывать фактическую (сдвинутую при паузе) позицию кольца.
                float ringZoneWidth = paused
                        ? org.telegram.messenger.AndroidUtilities.dp(28) + org.telegram.messenger.AndroidUtilities.dp(20)
                        : org.telegram.messenger.AndroidUtilities.dp(28);
                boolean insideSeekRing = dist >= (v.getWidth() / 2f - ringZoneWidth);

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        dragging = paused && insideSeekRing;
                        // ⚠️ ДИАГНОСТИКА (ROUNDVID_SEEK): все значения, от которых
                        // зависит решение блокировать перехват шторкой или нет.
                        PotokDebugLog.d("ROUNDVID_SEEK", "ACTION_DOWN decision"
                            + " dist=" + dist + " ringZoneWidth=" + ringZoneWidth
                            + " paused=" + paused + " insideSeekRing=" + insideSeekRing
                            + " dragging=" + dragging
                            + " roundVideoOpened=" + roundVideoActiveCell.isRoundVideoOpened()
                            + " isPlayingMessage=" + MediaController.getInstance().isPlayingMessage(mo)
                            + " isMessagePaused=" + MediaController.getInstance().isMessagePaused());
                        // ⚠️ ФИКС "шторка иногда всё равно перехватывает": раньше
                        // requestDisallowInterceptTouchEvent(true) вызывался ТОЛЬКО
                        // если dragging уже true (палец точно попал в узкую
                        // кольцевую зону на ACTION_DOWN). Если палец на паузе
                        // касался чуть мимо этой зоны (но всё ещё внутри видимого
                        // круга) — dragging оставался false, запрет на перехват
                        // не выставлялся, и на следующем ACTION_MOVE предок
                        // (шторка) успевал перехватить жест раньше, чем мы
                        // могли передумать. В оригинале это невозможно — там
                        // ВЕСЬ onTouchEvent ячейки идёт первым (см. checkRoundSeekbar
                        // в самом начале onTouchEvent), у нас же это отдельный
                        // оверлей-view, и его решение "не мешать шторке" нужно
                        // принимать более осторожно. Теперь на паузе блокируем
                        // перехват шторкой при касании в ЛЮБОМ месте видимого
                        // круга, не только в узкой кольцевой полосе — обычный тап
                        // в центр (play/pause) это не ломает, там движения almost
                        // нет и жест всё равно останется коротким тапом.
                        if (paused && v.getParent() != null) {
                            // ⚠️ ДИАГНОСТИКА (ROUNDVID_SEEK): фиксируем ФАКТ вызова и
                            // на каком именно классе родителя — чтобы убедиться, что
                            // цепочка parent'ов реально та, что мы предполагаем
                            // (fragmentView -> containerView -> ActionBarLayout), а не
                            // что-то ещё встряло посередине.
                            PotokDebugLog.d("ROUNDVID_SEEK", "requestDisallowInterceptTouchEvent(true) called on "
                                + v.getParent().getClass().getSimpleName());
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        } else {
                            // ⚠️ ДИАГНОСТИКА: если paused=false на ACTION_DOWN, мы
                            // НЕ блокируем перехват вообще — это ожидаемо для обычного
                            // тапа play/pause, но если окажется, что в момент бага
                            // paused почему-то false, хотя пользователь визуально видит
                            // паузу — вот где это будет видно.
                            PotokDebugLog.d("ROUNDVID_SEEK", "requestDisallowInterceptTouchEvent NOT called (paused=" + paused + ")");
                        }
                        return true; // сами решаем на UP — тап это или нет
                    case MotionEvent.ACTION_MOVE:
                        if (!dragging) {
                            // ⚠️ ДИАГНОСТИКА (ROUNDVID_SEEK): ACTION_MOVE, который мы
                            // игнорируем (dragging=false) — если шторка открывается
                            // именно в такие моменты, лог покажет где палец находился
                            // относительно круга, когда его "отпустили" на волю других
                            // перехватчиков.
                            PotokDebugLog.d("ROUNDVID_SEEK", "ACTION_MOVE ignored (dragging=false)"
                                + " localX=" + event.getX() + " localY=" + event.getY());
                            return true;
                        }
                        double angleDeg = Math.toDegrees(Math.atan2(dy, dx)) + 90;
                        if (angleDeg < 0) angleDeg += 360;
                        float progress = (float) (angleDeg / 360.0);
                        // ⚠️ ФИКС "рубленые ступени при перемотке" (1:1 с оригиналом
                        // checkRoundSeekbar в ChatMessageCell): визуальное обновление
                        // (audioProgress + перерисовка кольца/текста) должно идти на
                        // КАЖДЫЙ ACTION_MOVE, плавно вслед за пальцем. Троттлить
                        // нужно ТОЛЬКО сам вызов seekToProgress() (реальная перемотка
                        // в плеере, дорогая операция) — не отрисовку. Раньше и то, и
                        // другое было внутри одного throttle-блока на 100мс, поэтому
                        // палец двигался плавно, а кольцо/ручка визуально прыгали
                        // скачками раз в 100мс.
                        mo.audioProgress = progress;
                        roundVideoRingView.invalidate();
                        updateRoundVideoChromeTexts();
                        long now = System.currentTimeMillis();
                        if (now - lastSeekUpdateTime > 100) {
                            lastSeekUpdateTime = now;
                            MediaController.getInstance().seekToProgress(mo, progress);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        boolean wasDragging = dragging;
                        dragging = false;
                        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
                        if (event.getActionMasked() != MotionEvent.ACTION_UP) return true;
                        if (wasDragging) {
                            // Отпустили после перемотки — воспроизведение продолжается
                            // с новой позиции, 1:1 с оригиналом.
                            MediaController.getInstance().playMessage(mo);
                            return true;
                        }
                        float upDist = (float) Math.hypot(event.getX() - downX, event.getY() - downY);
                        if (upDist > org.telegram.messenger.AndroidUtilities.dp(8)) return true; // это был свайп/скролл, не тап
                        // Обычный тап на УЖЕ ОТКРЫТОМ кружке — play/pause. Открытие
                        // закрытого кружка теперь целиком на OnClickListener самой
                        // ячейки (см. комментарий у ACTION_DOWN выше) — эта ветка
                        // сюда для закрытого состояния больше не доходит вообще.
                        if (MediaController.getInstance().isPlayingMessage(mo) && !MediaController.getInstance().isMessagePaused()) {
                            MediaController.getInstance().pauseMessage(mo);
                        } else {
                            MediaController.getInstance().playMessage(mo, false);
                        }
                        updateRoundVideoChromeTexts();
                        return true;
                }
                return false;
            }
        });
    }

    /**
     * Обновляет живую плашку "прошло/всего" и видимость кольца/ручки/эквалайзера —
     * ВСЁ это только пока кружок ОТКРЫТ (в маленьком беззвучном автоплее — чисто
     * видео без элементов управления поверх, как обычный автоплей в любой ленте).
     */
    private void updateRoundVideoChromeTexts() {
        if (roundVideoDurationView == null) return;
        MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
        boolean opened = roundVideoActiveCell != null && roundVideoActiveCell.isRoundVideoOpened();
        if (mo == null || !mo.isRoundVideo() || !opened) {
            roundVideoDurationView.setVisibility(View.GONE);
            roundVideoEqualizerView.setVisibility(View.GONE);
            if (roundVideoPlayingDrawable != null) roundVideoPlayingDrawable.stop();
            return;
        }
        roundVideoDurationView.setVisibility(View.VISIBLE);
        boolean playing = MediaController.getInstance().isPlayingMessage(mo) && !MediaController.getInstance().isMessagePaused();
        int totalSec = 0;
        TLRPC.Document document = mo.getDocument();
        if (document != null) {
            for (TLRPC.DocumentAttribute attr : document.attributes) {
                if (attr instanceof TLRPC.TL_documentAttributeVideo) {
                    totalSec = (int) attr.duration;
                    break;
                }
            }
        }
        if (playing) {
            roundVideoDurationView.setText(org.telegram.messenger.AndroidUtilities.formatShortDuration(mo.audioProgressSec, totalSec));
        } else {
            roundVideoDurationView.setText(org.telegram.messenger.AndroidUtilities.formatShortDuration(totalSec));
        }
        boolean isPlayingNow = MediaController.getInstance().isPlayingMessage(mo);
        roundVideoEqualizerView.setVisibility(isPlayingNow ? View.VISIBLE : View.GONE);
        if (playing) {
            roundVideoPlayingDrawable.start();
        } else {
            roundVideoPlayingDrawable.stop();
        }
        roundVideoEqualizerView.invalidate();
    }

    /**
     * Кольцо-прогресс воспроизведения по контуру круга + отдельная жирная
     * точка-ручка на текущей позиции (видна только на паузе — подсказка "здесь
     * можно перемотать пальцем", см. скриншот из реального канала в этой сессии).
     * 1:1 с математикой дуги из ChatMessageCell (canvas.drawArc(rect, -90,
     * 360 * audioProgress, ...)). Видно ТОЛЬКО когда кружок открыт.
     */
    private class RoundVideoRingView extends View {
        private final RectF ringRect = new RectF();
        private final Paint ringPaint;
        private final Paint handlePaint;
        // ⚠️ ПРАВКА ПО ПРЯМОМУ ТРЕБОВАНИЮ ПОЛЬЗОВАТЕЛЯ: раньше переход inset
        // (тонкая полоса играя -> жирная на паузе) происходил мгновенным
        // скачком в одном кадре (см. старый комментарий ниже про "без анимации
        // перехода — не критично"). Теперь плавно анимируем 0..1 прогресс
        // перехода через ValueAnimator, а не boolean-скачок — onDraw больше не
        // читает paused напрямую для расчёта inset/радиуса ручки, только
        // текущее анимированное значение pausedTransition.
        private float pausedTransition = 0f;
        private android.animation.ValueAnimator pausedTransitionAnimator;
        private boolean lastPausedState = false;

        RoundVideoRingView(Context context) {
            super(context);
            ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(org.telegram.messenger.AndroidUtilities.dp(2));
            ringPaint.setStrokeCap(Paint.Cap.ROUND);
            ringPaint.setColor(Color.WHITE);
            handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            handlePaint.setStyle(Paint.Style.FILL);
            handlePaint.setColor(Color.WHITE);
            setWillNotDraw(false);
        }

        private void animatePausedTransition(boolean paused) {
            if (paused == lastPausedState && pausedTransitionAnimator != null && pausedTransitionAnimator.isRunning()) return;
            lastPausedState = paused;
            if (pausedTransitionAnimator != null) pausedTransitionAnimator.cancel();
            float target = paused ? 1f : 0f;
            pausedTransitionAnimator = android.animation.ValueAnimator.ofFloat(pausedTransition, target);
            pausedTransitionAnimator.setDuration(200);
            pausedTransitionAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
            pausedTransitionAnimator.addUpdateListener(anim -> {
                pausedTransition = (float) anim.getAnimatedValue();
                invalidate();
            });
            pausedTransitionAnimator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (roundVideoActiveCell == null || !roundVideoActiveCell.isRoundVideoOpened()) return;
            MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            if (mo == null || !mo.isRoundVideo() || !MediaController.getInstance().isPlayingMessage(mo)) return;
            boolean paused = MediaController.getInstance().isMessagePaused();
            if (paused != lastPausedState) animatePausedTransition(paused);
            // ⚠️ ФИКС (эта сессия): раньше инсет был фиксированным (~1dp) вне
            // зависимости от паузы — кольцо всегда лепилось прямо к краю круга.
            // В оригинале (drawRoundProgress, ChatMessageCell) инсет динамический:
            // ~1.5dp играя, и + до 16dp когда на паузе (место под "тень"-подложку
            // и увеличенную ручку). 1:1 переносим саму логику, но inset теперь
            // плавно анимирован через pausedTransition (0..1), а не boolean-скачок.
            float baseInset = org.telegram.messenger.AndroidUtilities.dpf2(1.5f);
            float pausedInset = org.telegram.messenger.AndroidUtilities.dp(16) * pausedTransition;
            float inset = baseInset + pausedInset;
            ringRect.set(inset, inset, getWidth() - inset, getHeight() - inset);
            float sweep = 360 * mo.audioProgress;
            canvas.drawArc(ringRect, -90, sweep, false, ringPaint);
            if (pausedTransition > 0.01f) {
                // ⚠️ ФИКС "точка-ручка как лилипут": в оригинале радиус ручки на
                // паузе — dp(3) + dp(5) (без активного касания) = dp(8), а не dp(4).
                // Радиус и прозрачность ручки тоже плавно следуют pausedTransition.
                double angleRad = Math.toRadians(sweep - 90);
                float radius = ringRect.width() / 2f;
                float handleX = ringRect.centerX() + radius * (float) Math.cos(angleRad);
                float handleY = ringRect.centerY() + radius * (float) Math.sin(angleRad);
                handlePaint.setAlpha(Math.round(255 * pausedTransition));
                canvas.drawCircle(handleX, handleY, org.telegram.messenger.AndroidUtilities.dp(8) * pausedTransition, handlePaint);
            }
        }
    }

    /**
     * Ищет среди видимых ячеек ленты ту, что сейчас держит играющий видеокружок,
     * и переставляет общий roundVideoPlayerContainer точно поверх неё — 1:1 с
     * циклом foundTextureViewMessage в ChatActivity. Если такая ячейка НЕ
     * найдена (проскроллили) — прячем свой контейнер и явно говорим
     * MediaController, что видео сейчас невидимо; дальше он САМ включает PIP.
     * Вызывается при скролле ленты и при любой смене состояния плеера.
     */
    private void updateRoundVideoTexturePosition() {
        if (roundVideoPlayerContainer == null || listView == null || fragmentView == null) return;
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        // ⚠️ ФИКС (эта сессия, причина рассинхрона кружков на Канале после
        // захода в Ленту): подписка на messagePlayingDidStart/
        // PlayStateChanged/DidReset снимается только в onFragmentDestroy(),
        // а не при уходе со вкладки — значит Лента продолжает получать ЭТИ
        // ГЛОБАЛЬНЫЕ уведомления, даже когда реально видна не она, а Канал
        // (ChatActivity, свой независимый TextureView). Раньше здесь любое
        // играющее round-видео (playing!=null) считалось "моё" и Лента
        // безусловно перетягивала общий MediaController.currentTextureView
        // СЕБЕ — даже если это видео физически открыто и показывается на
        // Канале. В результате Канал продолжал рисовать свой UI (полосу,
        // реагировать на тап), но реальный видеопоток уже принадлежал
        // скрытому контейнеру Ленты — отсюда "тап не ставит на паузу" /
        // "полоса не двигается" именно после связки Канал→Лента→Канал.
        // Теперь: считаем видео "своим" ТОЛЬКО если оно реально принадлежит
        // одной из СЕЙЧАС прикреплённых ячеек Ленты (roundVideoMessageObject
        // совпадает) — иначе ведём себя как playing==null (не претендуем на
        // текстуру вообще), и её сохраняет тот экран, который её реально
        // открыл.
        boolean belongsToFeed = false;
        if (playing != null && playing.isRoundVideo()) {
            int count = listView.getChildCount();
            for (int a = 0; a < count; a++) {
                View child = listView.getChildAt(a);
                if (child instanceof PotokFeedPostCell) {
                    MessageObject cellRoundVideo = ((PotokFeedPostCell) child).getRoundVideoMessageObject();
                    if (cellRoundVideo != null && cellRoundVideo.getId() == playing.getId()) {
                        belongsToFeed = true;
                        break;
                    }
                }
            }
        }
        if (!belongsToFeed) {
            playing = null;
        }
        if (playing == null || !playing.isRoundVideo()) {
            // ⚠️ ФИКС: не GONE — см. комментарий у roundVideoHiddenTranslationY.
            roundVideoPlayerContainer.setTranslationY(roundVideoHiddenTranslationY);
            if (roundVideoTextureRegisteredForMessageId != 0) {
                MediaController.getInstance().setTextureView(roundVideoTextureView, roundVideoAspectRatioFrameLayout, roundVideoPlayerContainer, false);
                roundVideoTextureRegisteredForMessageId = 0;
            }
            return;
        }
        if (roundVideoTextureRegisteredForMessageId != playing.getId()) {
            // ⚠️ ДИАГНОСТИКА (логи ROUNDVID_CROP): первая регистрация текстуры для
            // НОВОГО видео — логируем реальное соотношение сторон исходного видео
            // (TL_documentAttributeVideo.w/h, через готовые статические геттеры
            // MessageObject.getVideoWidth/Height — НЕ выдумываем новый метод) и
            // измеренные размеры контейнеров в этот момент. Если видео НЕ
            // квадратное (например 720x1280 вместо ожидаемого квадрата) — это
            // прямая причина "надкуса" при setOval(0,0,w,h) на квадратном
            // контейнере, если AspectRatioFrameLayout внутри масштабирует видео
            // не по центру или неверно кадрирует под квадрат.
            TLRPC.Document doc = playing.getDocument();
            int videoAttrW = doc != null ? org.telegram.messenger.MessageObject.getVideoWidth(doc) : 0;
            int videoAttrH = doc != null ? org.telegram.messenger.MessageObject.getVideoHeight(doc) : 0;
            PotokDebugLog.d("ROUNDVID_CROP", "setTextureView(true) new messageId=" + playing.getId()
                + " videoAttrW=" + videoAttrW + " videoAttrH=" + videoAttrH
                + " containerMeasuredW=" + roundVideoPlayerContainer.getMeasuredWidth()
                + " containerMeasuredH=" + roundVideoPlayerContainer.getMeasuredHeight()
                + " containerW=" + roundVideoPlayerContainer.getWidth()
                + " containerH=" + roundVideoPlayerContainer.getHeight()
                + " aspectFrameW=" + roundVideoAspectRatioFrameLayout.getWidth()
                + " aspectFrameH=" + roundVideoAspectRatioFrameLayout.getHeight()
                + " aspectFrameMeasuredW=" + roundVideoAspectRatioFrameLayout.getMeasuredWidth()
                + " aspectFrameMeasuredH=" + roundVideoAspectRatioFrameLayout.getMeasuredHeight());
            MediaController.getInstance().setTextureView(roundVideoTextureView, roundVideoAspectRatioFrameLayout, roundVideoPlayerContainer, true);
            roundVideoTextureRegisteredForMessageId = playing.getId();

            // ⚠️ ФИКС (эта сессия): раньше матрица трансформации SurfaceTexture
            // логировалась ТОЛЬКО внутри onLayoutChangeListener, который сам
            // залогирован только при ИЗМЕНЕНИИ w/h контейнера. Т.к. контейнер
            // всегда 984x984 (не меняется между разными видео) — после первого
            // раза этот лог больше НИКОГДА не срабатывал, хотя проблема crop
            // могла проявляться заново при каждом новом видео. Здесь — читаем
            // матрицу безусловно, привязано к КАЖДОМУ новому messageId, а не
            // к изменению размера. Два захода (200мс и 800мс) — на случай если
            // SurfaceTexture ещё не готов (isAvailable()==false) на первом.
            final int loggedMessageId = playing.getId();
            for (long delayMs : new long[]{200, 800}) {
                roundVideoPlayerContainer.postDelayed(() -> {
                    if (roundVideoTextureView == null || roundVideoTextureRegisteredForMessageId != loggedMessageId) return;
                    int outlineW = roundVideoPlayerContainer.getWidth();
                    int outlineH = roundVideoPlayerContainer.getHeight();
                    int tvW = roundVideoTextureView.getWidth();
                    int tvH = roundVideoTextureView.getHeight();
                    if (roundVideoTextureView.isAvailable()) {
                        try {
                            android.graphics.SurfaceTexture st = roundVideoTextureView.getSurfaceTexture();
                            float[] m = st != null ? new float[16] : null;
                            if (st != null) st.getTransformMatrix(m);
                            PotokDebugLog.d("ROUNDVID_CROP", "delayed(" + delayMs + "ms) matrix check messageId=" + loggedMessageId
                                + " outlineW=" + outlineW + " outlineH=" + outlineH
                                + " textureViewW=" + tvW + " textureViewH=" + tvH
                                + " matrix=" + (m == null ? "null(surfaceTexture null)" : java.util.Arrays.toString(m)));
                        } catch (Throwable t) {
                            PotokDebugLog.d("ROUNDVID_CROP", "delayed(" + delayMs + "ms) matrix read failed messageId=" + loggedMessageId + ": " + t);
                        }
                    } else {
                        PotokDebugLog.d("ROUNDVID_CROP", "delayed(" + delayMs + "ms) matrix check messageId=" + loggedMessageId
                            + " outlineW=" + outlineW + " outlineH=" + outlineH
                            + " textureViewW=" + tvW + " textureViewH=" + tvH
                            + " surfaceNotAvailableYet=true");
                    }
                }, delayMs);
            }
        }

        PotokFeedPostCell foundCell = null;
        int count = listView.getChildCount();
        for (int a = 0; a < count; a++) {
            View child = listView.getChildAt(a);
            if (child instanceof PotokFeedPostCell && ((PotokFeedPostCell) child).getRoundVideoMessageId() == playing.getId()) {
                foundCell = (PotokFeedPostCell) child;
                break;
            }
        }

        if (foundCell != null) {
            // Плавающий контейнер зарезервирован под БОЛЬШОЙ размер — подгоняем
            // его под текущий видимый масштаб ячейки (маленькое/большое
            // состояние), тем же pivot(0,0), что и у самой ячейки, иначе видео
            // будет вылезать за пределы маленького круга.
            roundVideoActiveCell = foundCell;
            // ⚠️ ФИКС "два круга": пересинхронизируем позицию/размер оверлея на
            // каждый кадр анимации роста/сжатия круга в ячейке, а не только по
            // редким уведомлениям плеера — см. комментарий у поля в PostCell.
            foundCell.setOnRoundVideoVisualScaleChanged(this::updateRoundVideoTexturePosition);
            roundVideoOpenedCached = foundCell.isRoundVideoOpened();
            float scale = foundCell.getRoundVideoVisualScale();
            roundVideoPlayerContainer.setPivotX(0);
            roundVideoPlayerContainer.setPivotY(0);
            roundVideoPlayerContainer.setScaleX(scale);
            roundVideoPlayerContainer.setScaleY(scale);

            View roundImage = foundCell.getRoundVideoImageView();
            int[] loc = new int[2];
            roundImage.getLocationOnScreen(loc);
            int[] rootLoc = new int[2];
            fragmentView.getLocationOnScreen(rootLoc);
            roundVideoPlayerContainer.setTranslationX(loc[0] - rootLoc[0]);
            roundVideoPlayerContainer.setTranslationY(loc[1] - rootLoc[1]);
            roundVideoPlayerContainer.setVisibility(View.VISIBLE);
            MediaController.getInstance().setCurrentVideoVisible(true);
            if (!roundVideoOpenedCached && MediaController.getInstance().isMessagePaused()) {
                // Маленький беззвучный автоплей вернулся в кадр после того, как
                // был поставлен на паузу при уходе с экрана (см. ветку "не
                // найдена" ниже) — возобновляем беззвучно, без нового bind().
                MediaController.getInstance().playMessage(playing, true);
            }
            if (roundVideoRingView != null) roundVideoRingView.invalidate();
            updateRoundVideoChromeTexts();
        } else {
            roundVideoActiveCell = null;
            // ⚠️ ФИКС: не GONE — см. комментарий у roundVideoHiddenTranslationY.
            roundVideoPlayerContainer.setTranslationY(roundVideoHiddenTranslationY);
            if (roundVideoOpenedCached) {
                // Кружок был ОТКРЫТ пользователем (со звуком) и ушёл с экрана —
                // не прерываем, включаем PIP. См. обсуждение этой сессии:
                // PIP только для осознанно открытого просмотра, не для фонового
                // беззвучного автоплея.
                MediaController.getInstance().setCurrentVideoVisible(false);
            } else {
                // Маленький беззвучный автоплей ушёл с экрана — просто ставим на
                // паузу, как обычный автоплей видео в любой ленте; возобновится
                // сам, когда снова попадёт в кадр (см. bind() в PotokFeedPostCell —
                // автоплей стартует заново при следующем реальном показе, если
                // ViewHolder переиспользуется под тот же пост, либо сработает
                // автоплей-ветка при повторном bind).
                // ⚠️ КРИТИЧНО: MediaController.pauseMessage() шлёт
                // messagePlayingPlayStateChanged БЕЗУСЛОВНО, даже если плеер уже
                // на паузе (проверено чтением его кода). Это уведомление снова
                // долетает в didReceivedNotification -> updateRoundVideoTexturePosition
                // -> сюда же -> pauseMessage() -> уведомление -> ... — без этой
                // проверки получается бесконечная синхронная рекурсия и
                // StackOverflowError (реальный краш, пойманный на телефоне:
                // видеокружок, только что опубликованный в канал, вызывает
                // playMessage() прямо из onBindViewHolder ДО того, как ячейка
                // становится видимым child'ом listView — foundCell==null,
                // ветка "не найдено" срабатывает раньше, чем ячейка успевает
                // встать на место, и без проверки isMessagePaused() уходит в
                // цикл). Проверяем реальное состояние плеера, а не пытаемся
                // угадать по локальным флагам.
                if (!MediaController.getInstance().isMessagePaused()) {
                    MediaController.getInstance().pauseMessage(playing);
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
