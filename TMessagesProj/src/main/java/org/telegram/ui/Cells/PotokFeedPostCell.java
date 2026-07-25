package org.telegram.ui.Cells;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Region;
import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import org.telegram.ui.Components.spoilers.SpoilerEffect2;
import android.graphics.RectF;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewParent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MediaActionDrawable;
import org.telegram.ui.Components.RadialProgress2;
import org.telegram.ui.Components.SeekBar;
import org.telegram.ui.Components.SeekBarWaveform;
import org.telegram.ui.PhotoViewer;
import org.telegram.ui.PotokFeedFragment;
import org.telegram.ui.PotokDebugLog;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.TranslateAlert2;
import org.telegram.ui.ReportBottomSheet;

import java.util.ArrayList;

/**
 * Карточка поста в Ленте — этап 1+2+3 (текст, медиа, карусель).
 */
public class PotokFeedPostCell extends LinearLayout {

    private static final int MAX_TEXT_LINES   = 7;
    private static final int MAX_MEDIA_HEIGHT_DP = 560;
    private static final int MIN_MEDIA_HEIGHT_DP = 140;

    // --- Шапка ---
    private final BackupImageView avatarView;
    private final TextView titleView;
    private final TextView timeView;

    // --- Текст + раскрытие ---
    private final TextView textView;
    private final TextView expandButton;
    private boolean isExpanded = false;

    // --- Медиа: карусель ---
    private final RecyclerView carouselView;
    private final DotsIndicator dotsIndicator;
    private CarouselAdapter carouselAdapter;

    // --- Медиа, отправленные КАК ФАЙЛ (MessageObject.TYPE_FILE) ---
    // 1:1 переносим реальный SharedDocumentCell (тот же компонент, которым Telegram
    // рисует строки "Общих файлов"/загрузок) — он сам умеет иконку по расширению,
    // имя, размер, прогресс/кнопку скачивания. Не карусель: файлы рисуются
    // отдельными строками друг под другом, как документы в чате, а не как
    // разворачиваемое инлайн-превью.
    private final LinearLayout documentsContainer;

    // --- Аудио ---
    private final AudioPlayButton audioPlayButton;
    private final TextView audioTitleView;
    private final TextView audioPerformerView;
    private final FrameLayout audioContainer;
    private final AudioSeekBarView audioSeekBarView;
    private final AudioWaveformView audioWaveformView;
    // Строка 3 (константа): "0:00 / 3:45" — видна ВСЕГДА, не зависит от того, играет
    // трек или нет. 1:1 с ChatMessageCell.durationLayout (см. y=dp(57) в оригинале —
    // отдельная строка ПОД строкой 2, не приклеена сбоку к полосе перемотки).
    private final TextView audioTimeView;
    // Строка 2 (переключаемая): performer ИЛИ seekBar/waveform — оба ребёнка занимают
    // одно и то же место и кроссфейдятся между собой (см. updateAudioSeekRowVisibility),
    // 1:1 с ChatMessageCell.toSeekBarProgress-анимацией (performerLayout/seekBar рисуются
    // на одной Y-координате, alpha-переход между ними).
    private final FrameLayout audioSecondRow;
    private final LinearLayout audioSeekRow;

    // --- Опрос ---
    private final PollView pollView;
    // Сообщение группы, чей media — конкретно опрос (см. setPost()); нужно отдельно
    // от pollView, чтобы по NotificationCenter.didUpdatePollResults сверить id опроса
    // и передать актуальные media.poll/results обратно в MessageObject перед перерисовкой.
    private MessageObject pollMessageObject;

    // --- Футер ---
    private final ImageView viewsIcon;
    private final TextView viewsView;
    private final ReactionEmojiView reactionEmojiView;
    private final TextView reactionView;
    private final LinearLayout commentsRow;
    private final TextView commentsView;
    private boolean commentsLoading = false;

    private MessageObject currentMessage;
    private ArrayList<MessageObject> currentMessages;
    private android.app.Activity parentActivity;
    private TLRPC.Chat currentChannel;
    private ImageView menuButton;
    private ImageView forwardButton;
    private org.telegram.ui.ActionBar.BaseFragment parentFragment;

    public void setParentFragment(org.telegram.ui.ActionBar.BaseFragment fragment) {
        parentFragment = fragment;
    }

    // Temporary holder for forward
    private static class PotokForwardHolder {
        static MessageObject message;
        static boolean noAuthor;
    }

    public void setParentActivity(android.app.Activity activity) {
        parentActivity = activity;
    }

    // ДИАГНОСТИКА (не фикс). Сейчас на уровне ВСЕЙ ячейки ленты (не внутренней
    // карусели, а внешнего RecyclerView в PotokFeedFragment) нет НИ ОДНОГО
    // отслеживания attach/detach — я проверил PotokFeedFragment.java: адаптер там
    // не переопределяет onViewAttachedToWindow/onViewDetachedFromWindow вообще.
    // Раз PotokFeedPostCell — обычная View и одновременно itemView внешнего
    // RecyclerView.Holder, её собственные onAttachedToWindow/onDetachedFromWindow
    // штатно вызываются Android'ом при attach/detach окна независимо от адаптера —
    // это не мои домыслы, а стандартное поведение View, но раньше это никак не
    // логировалось, и мы не знали, что здесь реально происходит при скролле
    // внешней ленты. Только логируем состояние текущей карусели, никакого
    // notifyItemChanged/restart отсюда не вызываем.
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        PotokDebugLog.d("VIDEOPLAY", "OUTER_FEED_CELL ATTACH post="
            + (currentMessage != null ? currentMessage.getId() : -1)
            + " cellHash=" + System.identityHashCode(this));
        logCarouselMediaDiagState("OUTER_ATTACH");
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        PotokDebugLog.d("VIDEOPLAY", "OUTER_FEED_CELL DETACH post="
            + (currentMessage != null ? currentMessage.getId() : -1)
            + " cellHash=" + System.identityHashCode(this));
        logCarouselMediaDiagState("OUTER_DETACH");
    }

    // Проходит по ВИДИМЫМ сейчас holder'ам внутренней карусели и логирует их
    // реальное состояние — вызывается из attach/detach внешней ячейки выше.
    private void logCarouselMediaDiagState(String moment) {
        if (carouselAdapter == null || carouselView == null) return;
        int count = carouselView.getChildCount();
        for (int i = 0; i < count; i++) {
            android.view.View child = carouselView.getChildAt(i);
            RecyclerView.ViewHolder vh = carouselView.getChildViewHolder(child);
            if (vh instanceof CarouselAdapter.MediaHolder) {
                CarouselAdapter.MediaHolder mh = (CarouselAdapter.MediaHolder) vh;
                PotokDebugLog.d("VIDEOPLAY", moment + " childIdx=" + i
                    + " lastAutoplayDocumentId=" + mh.lastAutoplayDocumentId
                    + " " + mediaDiagSnapshot(mh.img));
            }
        }
    }

    public PotokFeedPostCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        setOrientation(VERTICAL);
        // Фикс "карнавал полосок": раньше карточка была просто сплошным прямоугольником
        // без обводки, и на фоне похожего по цвету экрана границы между постами были
        // почти не видны. Теперь — скруглённые углы + тонкая обводка + фон подчёркнуто
        // отличается от фона ленты (который теперь — обои чата, см. PotokFeedFragment).
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        int cardColor = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);
        cardBg.setColor(cardColor);
        cardBg.setCornerRadius(AndroidUtilities.dp(12));
        // Фикс: раньше обводка бралась из key_divider, который в тёмной теме почти
        // сливается с фоном карточки — контраста почти не было. Теперь цвет обводки
        // считается как смешение фона карточки с белым (в тёмной теме) или чёрным
        // (в светлой) на фиксированный процент — контраст гарантирован в любой теме.
        boolean isDark = Theme.isCurrentThemeDark();
        int borderColor = androidx.core.graphics.ColorUtils.blendARGB(cardColor, isDark ? Color.WHITE : Color.BLACK, isDark ? 0.22f : 0.14f);
        cardBg.setStroke(AndroidUtilities.dp(1), borderColor);
        setBackground(cardBg);
        // Дочерние вью (шапка с квадратными углами) обрезаются по той же скруглённой
        // форме — иначе углы headerRow торчали бы за пределы скруглённой карточки.
        setClipToOutline(true);
        setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), AndroidUtilities.dp(12));
            }
        });

        // --- Шапка ---
        // Фикс: полоса с названием канала теперь отдельного цвета от остального поста.
        // ВАЖНО: margin именно 1dp (не 0!) — ровно по ширине обводки карточки (см.
        // cardBg.setStroke(dp(1), ...) выше). При margin=0 полоса рисуется поверх
        // рамки и полностью её закрывает — это и был регресс в прошлой правке
        // ("обводка пропала"). При margin=1dp полоса сидит ВНУТРИ рамки, оставляя её
        // видимой по всему периметру. Собственное скругление верхних углов полосы
        // (те же 12dp, что и у карточки) при этом убирает разрыв дуги в углах —
        // раньше здесь была ПРЯМОУГОЛЬНАЯ полоса почти вплотную к краю, чей квадратный
        // угол резал по дуге скругления карточки.
        LinearLayout headerRow = new LinearLayout(context);
        headerRow.setOrientation(HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(dp(12), dp(12), dp(8), dp(10));
        android.graphics.drawable.GradientDrawable headerBg = new android.graphics.drawable.GradientDrawable();
        headerBg.setColor(Theme.getColor(Theme.key_graySection, resourcesProvider));
        float topRadius = AndroidUtilities.dp(12);
        headerBg.setCornerRadii(new float[]{topRadius, topRadius, topRadius, topRadius, 0, 0, 0, 0});
        headerRow.setBackground(headerBg);
        addView(headerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 1, 1, 1, 0));

        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(dp(18));
        headerRow.addView(avatarView, LayoutHelper.createLinear(36, 36));
        avatarView.setOnClickListener(v -> openChannelProfile());

        LinearLayout titleColumn = new LinearLayout(context);
        titleColumn.setOrientation(VERTICAL);
        headerRow.addView(titleColumn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 10, 0, 0, 0));
        titleColumn.setOnClickListener(v -> openChannelProfile());

        titleView = new TextView(context);
        titleView.setTextSize(15);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleColumn.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        timeView = new TextView(context);
        timeView.setTextSize(13);
        timeView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        titleColumn.addView(timeView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // --- Кнопка "переслать" (быстрая пересылка одним тапом) ---
        // Ставится ЛЕВЕЕ кнопки "три точки", тот же размер/стиль кружка. По тапу
        // сразу открывает диалог выбора получателя (openForwardDialog, тот же метод,
        // что и пункт "Переслать" в выпадающем меню ниже), без промежуточного меню.
        // Иконка msg_forward — та же, что используется для пункта "Переслать" в
        // showPostMenu() (см. ниже), для единообразия.
        forwardButton = new ImageView(context);
        forwardButton.setImageResource(org.telegram.messenger.R.drawable.msg_forward);
        forwardButton.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        forwardButton.setScaleType(ImageView.ScaleType.CENTER);
        forwardButton.setPadding(dp(8), dp(8), dp(8), dp(8));
        forwardButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), Theme.RIPPLE_MASK_CIRCLE_20DP));
        forwardButton.setOnClickListener(v -> openForwardDialog(false));
        headerRow.addView(forwardButton, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));

        // --- Кнопка меню ---
        // Фикс: раньше это был текстовый символ "⋮" с неровными отступами (8/4) —
        // отсюда и "кривизна". Теперь нормальная иконка (ic_ab_other, стандартная
        // "три точки" из самого Telegram) с симметричными отступами, крупнее.
        menuButton = new ImageView(context);
        menuButton.setImageResource(org.telegram.messenger.R.drawable.ic_ab_other);
        menuButton.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        menuButton.setScaleType(ImageView.ScaleType.CENTER);
        menuButton.setPadding(dp(8), dp(8), dp(8), dp(8));
        menuButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), Theme.RIPPLE_MASK_CIRCLE_20DP));
        menuButton.setOnClickListener(v -> showPostMenu(v));
        headerRow.addView(menuButton, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));

        // --- Текст ---
        textView = new TextView(context);
        textView.setTextSize(15);
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        textView.setMaxLines(MAX_TEXT_LINES);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textView.setLineSpacing(dp(2), 1f);
        // Фикс долгого нажатия: TextView сам может перехватывать long-press под выделение
        // текста, форвардим на тот же обработчик, что и для фото/остальной карточки.
        textView.setLongClickable(true);
        textView.setOnLongClickListener(v -> {
            openPostInChannel();
            return true;
        });
        addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 8, 12, 0));

        // --- Кнопка «ещё» ---
        expandButton = new TextView(context);
        expandButton.setTextSize(14);
        expandButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider));
        expandButton.setText("ещё");
        expandButton.setVisibility(GONE);
        expandButton.setPadding(dp(12), dp(2), dp(12), 0);
        expandButton.setOnClickListener(v -> {
            isExpanded = !isExpanded;
            // Плавная анимация вместо мгновенного скачка: TransitionManager сам
            // анимирует итоговое изменение layout (высоту textView и сдвиг всего, что
            // ниже — карусели, футера с реакциями и т.д.), не требуя вручную считать
            // высоту под конкретный текст (он у каждого поста разный, а текст ещё и
            // может переноситься по-разному на разных ширинах экрана).
            android.transition.TransitionSet transition = new android.transition.TransitionSet()
                .addTransition(new android.transition.ChangeBounds())
                .setDuration(220)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT);
            android.transition.TransitionManager.beginDelayedTransition(this, transition);
            if (isExpanded) {
                textView.setMaxLines(Integer.MAX_VALUE);
                textView.setEllipsize(null);
                expandButton.setText("свернуть");
            } else {
                textView.setMaxLines(MAX_TEXT_LINES);
                textView.setEllipsize(TextUtils.TruncateAt.END);
                expandButton.setText("ещё");
            }
            requestLayout();
        });
        addView(expandButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        // --- Карусель ---
        carouselView = new RecyclerView(context) {
            private float startX, startY;

            @Override
            public boolean onInterceptTouchEvent(MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = e.getX();
                        startY = e.getY();
                        getParent().requestDisallowInterceptTouchEvent(false);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float dx = Math.abs(e.getX() - startX);
                        float dy = Math.abs(e.getY() - startY);
                        if (dx > dy) {
                            // Горизонтальный — блокируем родителя
                            getParent().requestDisallowInterceptTouchEvent(true);
                        } else {
                            // Вертикальный — отдаём родителю
                            getParent().requestDisallowInterceptTouchEvent(false);
                        }
                        break;
                }
                return super.onInterceptTouchEvent(e);
            }
        };
        carouselView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        PagerSnapHelper snapHelper = new PagerSnapHelper();
        snapHelper.attachToRecyclerView(carouselView);
        carouselView.setVisibility(GONE);
        // Фикс "раздвоение кадров" (лог GHOST): дефолтный RecyclerView.ItemAnimator
        // при повторном/replacement-биндe одной и той же позиции (например, после
        // полного relayout окна из-за смены window insets/actionbar height — см.
        // лог "onLayoutChildren -> ViewRootImpl.performTraversals" перед вторым
        // onBindViewHolder) запускает cross-fade: старый и новый ViewHolder
        // ФИЗИЧЕСКИ существуют и рисуются одновременно, пока идёт анимация смены
        // содержимого. По логу видно именно это: holder B (новый) успевает
        // ATTACH'нуться с уже готовым битмапом за ~280мс ДО того, как holder A
        // (старый) реально DETACH'ится — то есть оба кадра видны на экране
        // одновременно. В карусели одновременно виден только один элемент
        // (PagerSnapHelper), поэтому анимация смены содержимого тут не нужна
        // вообще — отключаем её полностью, чтобы старый holder убирался сразу,
        // без анимационного окна перекрытия.
        carouselView.setItemAnimator(null);
        addView(carouselView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, MIN_MEDIA_HEIGHT_DP, 1, 10, 1, 0));

        // --- Точки-индикатор ---
        dotsIndicator = new DotsIndicator(context, resourcesProvider);
        dotsIndicator.setVisibility(GONE);
        addView(dotsIndicator, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 20, 0, 4, 0, 0));

        carouselView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm != null) {
                    dotsIndicator.setCurrentPage(lm.findFirstVisibleItemPosition());
                }
            }
        });

        // --- Файлы (медиа, отправленное в канале КАК ФАЙЛ) ---
        documentsContainer = new LinearLayout(context);
        documentsContainer.setOrientation(VERTICAL);
        documentsContainer.setVisibility(GONE);
        addView(documentsContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        // --- Аудио ---
        // Раньше здесь стоял SharedAudioCell — это компонент из ДРУГОГО контекста
        // оригинала (список "Общие медиа/музыка" в профиле — маленькая иконка+строка),
        // а не то, как аудио/войс выглядит в самом посте канала (ChatMessageCell) —
        // круглая play-кнопка слева, название+исполнитель (или просто "Голосовое
        // сообщение"), и волна/сикбар под этим, видимые ВСЕГДА, а не только во время
        // воспроизведения. Заменено на компоновку из тех же реальных компонентов,
        // которыми это рисует сам ChatMessageCell (RadialProgress2, SeekBarWaveform,
        // SeekBar), просто собранных в обычные Android View вместо ручной отрисовки
        // в одном гигантском canvas-методе.
        audioContainer = new FrameLayout(context);
        // Не обрезать детей — см. подробный комментарий у audioTopRow.setClipChildren
        // ниже: мини-бейдж загрузки на аудио-кнопке рисуется с небольшим выходом за
        // пределы своего 44x44dp View, и клип на ЛЮБОМ уровне между кнопкой и экраном
        // обрежет его точно так же. Отключаем на обоих уровнях-предках.
        audioContainer.setClipChildren(false);
        addView(audioContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, 10, 8, 0));

        LinearLayout audioColumn = new LinearLayout(context);
        audioColumn.setOrientation(VERTICAL);
        audioColumn.setClipChildren(false);
        audioContainer.addView(audioColumn, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout audioTopRow = new LinearLayout(context);
        audioTopRow.setOrientation(HORIZONTAL);
        audioTopRow.setGravity(Gravity.CENTER_VERTICAL);
        // Фикс "бейдж загрузки — белый неполноценный круг": RadialProgress2 рисует
        // мини-бейдж СО СДВИГОМ +16dp от центра главной иконки (см. RadialProgress2.draw,
        // ветка drawMiniIcon — cx/cy = progressRect.center + dp(16), радиус ~11dp).
        // AudioPlayButton ниже жёстко занимает ровно 44x44dp (onMeasure), поэтому правый
        // нижний край бейджа (~49dp от левого верхнего угла) на несколько dp вылезал за
        // границы САМОЙ КНОПКИ — а ViewGroup по умолчанию (clipChildren=true) обрезает
        // отрисовку ребёнка ровно по его layout-границам, отсюда и "срезанный" круг.
        // В оригинале (SharedAudioCell) это не проявляется, потому что там иконка
        // рисуется прямо на канвасе всей ячейки (без отдельного дочернего View со своими
        // границами) — там обрезать нечем. Здесь конструкция другая (реальные Android
        // View, не Canvas), поэтому разрешаем родителю не обрезать детей.
        audioTopRow.setClipChildren(false);
        audioColumn.addView(audioTopRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        audioPlayButton = new AudioPlayButton(context, resourcesProvider);
        // Без этого listener onClick() кнопки не сможет реально запустить
        // воспроизведение — то же самое, для чего раньше был нужен
        // setNeedPlayMessageListener у SharedAudioCell.
        audioPlayButton.setOnPlayRequested(messageObject -> {
            boolean started = MediaController.getInstance().playMessage(messageObject);
            if (started && currentMessage == messageObject) {
                updateAudioTimeText(messageObject);
            }
            return started;
        });
        audioTopRow.addView(audioPlayButton, LayoutHelper.createLinear(44, 44));

        LinearLayout audioTitleColumn = new LinearLayout(context);
        audioTitleColumn.setOrientation(VERTICAL);
        // Фикс "иконка play и название/автор на разных высотах": раньше колонка
        // [название] и блок [автор/полоса] (audioSecondRow) были РАЗНЫМИ строками —
        // название жило внутри audioTopRow (рядом с иконкой), а автор/полоса —
        // отдельным сиблингом НИЖЕ всего audioTopRow целиком, из-за чего иконка
        // визуально "плавала" одна, а название с автором ехали ниже неё. Теперь
        // audioSecondRow — часть ЭТОЙ ЖЕ колонки, вместе с названием, и вся колонка
        // (название + автор/полоса, ~44dp суммарно) стоит РЯДОМ с иконкой (44dp) в
        // одной строке — 1:1 с тем, как это выглядело в самой первой версии до
        // разделения на 3 отдельные строки. Строка 3 (время) остаётся отдельной
        // константной строкой НИЖЕ этого блока целиком (см. audioColumn.addView(audioTimeView...)).
        audioTopRow.addView(audioTitleColumn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 10, 0, 0, 0));

        // Строка 1 (внутри audioTitleColumn): название.
        audioTitleView = new TextView(context);
        audioTitleView.setTextSize(15);
        audioTitleView.setTypeface(AndroidUtilities.bold());
        audioTitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        audioTitleView.setMaxLines(1);
        audioTitleView.setEllipsize(TextUtils.TruncateAt.END);
        audioTitleColumn.addView(audioTitleView);

        // Строка 2 (переключаемая, с анимацией, тоже внутри audioTitleColumn, сразу
        // под названием): исполнитель ИЛИ полоса перемотки — 1:1 с ChatMessageCell,
        // где performerLayout и seekBar рисуются на ОДНОЙ и той же Y-координате и
        // кроссфейдятся (alpha) друг в друга. Оба ребёнка лежат в одном FrameLayout
        // один поверх другого; видимость/прозрачность переключается в
        // updateAudioSeekRowVisibility().
        audioPerformerView = new TextView(context);
        audioPerformerView.setTextSize(13);
        audioPerformerView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        audioPerformerView.setMaxLines(1);
        audioPerformerView.setEllipsize(TextUtils.TruncateAt.END);

        // Ползунок/волна прогресса воспроизведения — по тому же паттерну, что и
        // ChatMessageCell (SeekBar/SeekBarWaveform + NotificationCenter
        // messagePlayingProgressDidChanged для обновления, см. updateAudioProgressIfPlaying).
        // Видны ОБА варианта заранее, переключается только visibility в setPost() в
        // зависимости от isVoice()/isMusic() — как и в оригинале, где это тоже два
        // разных пути отрисовки внутри одной и той же ячейки. Время сюда больше НЕ
        // добавляется сбоку — оно отдельная константная строка 3 (см. ниже).
        audioSeekBarView = new AudioSeekBarView(context, resourcesProvider);
        audioWaveformView = new AudioWaveformView(context, resourcesProvider);

        LinearLayout audioSeekRow = new LinearLayout(context);
        audioSeekRow.setOrientation(HORIZONTAL);
        audioSeekRow.setGravity(Gravity.CENTER_VERTICAL);
        audioSeekRow.addView(audioSeekBarView, LayoutHelper.createLinear(0, 24, 1f));
        audioSeekRow.addView(audioWaveformView, LayoutHelper.createLinear(0, 24, 1f));
        this.audioSeekRow = audioSeekRow;

        audioSecondRow = new FrameLayout(context);
        audioTitleColumn.addView(audioSecondRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 24, 0, 2, 0, 0));
        audioSecondRow.addView(audioPerformerView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        audioSecondRow.addView(audioSeekRow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // Строка 3 (константа, отдельно ПОД всем блоком иконка+название+автор/полоса):
        // "0:00 / 3:45" — 1:1 с ChatMessageCell.durationLayout, видна ВСЕГДА, для
        // музыки без размера файла (см. подробности в предыдущих комментариях сессии).
        audioTimeView = new TextView(context);
        audioTimeView.setTextSize(13);
        audioTimeView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        audioColumn.addView(audioTimeView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 54, 4, 8, 0));


        // --- Опрос ---
        // Раньше посты-опросы показывались в ленте пустыми: TL_messageMediaPoll не
        // подходит ни под одну из веток (не фото/видео, не аудио), а вопрос опроса
        // лежит не в messageOwner.message (как обычный текст поста), а отдельно в
        // media.poll.question — findPostCaption() его никогда не находил. Теперь
        // под опрос отдельный вью, максимально похожий на оригинальный Telegram:
        // вопрос жирным, варианты ответов строками с шкалой % (когда результаты
        // видны), пометка "Анонимный опрос"/"Опрос" и число проголосовавших внизу.
        pollView = new PollView(context, resourcesProvider);
        pollView.setVisibility(GONE);
        // Строки вариантов ответа кликабельны до голосования и перехватывают touch —
        // без этого долгое нажатие на пост с опросом не открывало канал (в отличие
        // от обычных постов с медиа/текстом), см. PollView.setOnLongPressListener.
        pollView.setOnLongPressListener(this::openPostInChannel);
        addView(pollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 10, 12, 0));

        // --- Футер ---
        // Фикс: раньше было тесно (иконки 16dp, текст 13sp) и комментарии шли сразу
        // после реакции слева. Теперь — 4 условные колонки: просмотры | реакция |
        // (гибкий отступ) | комментарии — комментарии прижаты к правому краю.
        LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        addView(footer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 12, 12, 14));

        viewsIcon = new ImageView(context);
        viewsIcon.setImageResource(org.telegram.messenger.R.drawable.msg_views);
        viewsIcon.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        footer.addView(viewsIcon, LayoutHelper.createLinear(20, 20, 0, Gravity.CENTER_VERTICAL, 0, 0, 5, 0));

        viewsView = new TextView(context);
        viewsView.setTextSize(14);
        viewsView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        footer.addView(viewsView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, Gravity.CENTER_VERTICAL, 0, 0, 18, 0));

        reactionEmojiView = new ReactionEmojiView(context);
        reactionEmojiView.setVisibility(GONE);
        footer.addView(reactionEmojiView, LayoutHelper.createLinear(20, 20, 0, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

        reactionView = new TextView(context);
        reactionView.setTextSize(14);
        reactionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        footer.addView(reactionView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, Gravity.CENTER_VERTICAL));

        // Гибкий отступ — толкает блок комментариев к правому краю.
        View footerSpacer = new View(context);
        footer.addView(footerSpacer, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        // --- Комментарии ---
        // Видна только если у поста есть привязанная группа обсуждений
        // (messageOwner.replies.comments == true — то же поле, по которому
        // ChatMessageCell решает, рисовать ли кнопку комментариев в обычном чате).
        commentsRow = new LinearLayout(context);
        commentsRow.setOrientation(HORIZONTAL);
        commentsRow.setGravity(Gravity.CENTER_VERTICAL);
        commentsRow.setVisibility(GONE);
        // Увеличенный внутренний паддинг — больше площадь нажатия (жалоба на то,
        // что тап срабатывает не с первого раза) и больше "воздуха" под пальцем.
        commentsRow.setPadding(dp(10), dp(8), dp(4), dp(8));
        footer.addView(commentsRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, Gravity.CENTER_VERTICAL));

        ImageView commentsIcon = new ImageView(context);
        commentsIcon.setImageResource(org.telegram.messenger.R.drawable.msg_discussion);
        commentsIcon.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider));
        commentsRow.addView(commentsIcon, LayoutHelper.createLinear(20, 20, 0, Gravity.CENTER_VERTICAL, 0, 0, 5, 0));

        commentsView = new TextView(context);
        commentsView.setTextSize(14);
        commentsView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider));
        commentsRow.addView(commentsView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, Gravity.CENTER_VERTICAL));

        commentsRow.setOnClickListener(v -> openComments());

        // Разделитель убран: раньше был отдельной серой полосой 8dp внутри ячейки,
        // теперь посты — отдельные карточки с отступами снаружи (см. PotokFeedFragment,
        // где выставляются margins у RecyclerView.LayoutParams), полоса стала не нужна.

        // Долгое нажатие по карточке -> открыть пост в канале
        setLongClickable(true);
        setOnLongClickListener(v -> {
            openPostInChannel();
            return true;
        });
    }

    // ------------------------------------------------------------------ setPost

    private long lastSetPostStackLogTime = 0; // ВРЕМЕННОЕ поле для диагностики двоения кадров

    public void setPost(ArrayList<MessageObject> messages, TLRPC.Chat channel) {
        if (messages == null || messages.isEmpty()) return;
        // ВРЕМЕННАЯ диагностика двоения кадров (см. переписку с пользователем):
        // дедуп в CarouselAdapter.setMessages() не остановил повторный бинд на
        // каждый кадр — значит, дело не в notifyDataSetChanged() оттуда. Ловим
        // РЕАЛЬНЫЙ стек вызова setPost(), троттлированно (раз в секунду на пост),
        // чтобы увидеть, кто и как часто его дёргает.
        {
            long now = System.currentTimeMillis();
            if (now - lastSetPostStackLogTime > 1000) {
                lastSetPostStackLogTime = now;
                PotokDebugLog.d("GHOST", "setPost CALLED post=" + messages.get(0).getId() + " stack="
                    + android.util.Log.getStackTraceString(new Throwable()).replace("\n", " <- "));
            }
        }
        currentMessages = messages;
        currentChannel = channel;
        MessageObject messageObject = messages.get(0);
        currentMessage = messageObject;

        // Сброс состояния при переиспользовании
        isExpanded = false;
        textView.setMaxLines(MAX_TEXT_LINES);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        expandButton.setText("ещё");
        expandButton.setVisibility(GONE);
        // Фикс: если ячейка переиспользуется, пока старый запрос комментариев ещё летит —
        // сбрасываем флаг и прозрачность, иначе кнопка комментариев нового поста может
        // навсегда остаться полупрозрачной/заблокированной.
        commentsLoading = false;
        commentsRow.setAlpha(1f);

        // Шапка
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(channel);
        avatarView.setForUserOrChat(channel, avatarDrawable);
        titleView.setText(channel != null ? channel.title : "");
        timeView.setText(LocaleController.formatDate(messageObject.messageOwner.date));

        // Текст / caption
        CharSequence caption = findPostCaption(messages, messageObject);
        if (TextUtils.isEmpty(caption)) {
            textView.setVisibility(GONE);
            expandButton.setVisibility(GONE);
        } else {
            textView.setVisibility(VISIBLE);
            if (caption instanceof android.text.Spannable) {
                AndroidUtilities.addLinksSafe((android.text.Spannable) caption, android.text.util.Linkify.WEB_URLS, false, true);
            } else {
                android.text.SpannableString sp = new android.text.SpannableString(caption);
                AndroidUtilities.addLinksSafe(sp, android.text.util.Linkify.WEB_URLS, false, true);
                caption = sp;
            }
            textView.setText(caption);
            textView.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            textView.setLinksClickable(true);

            final CharSequence finalCaption = caption;
            textView.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    textView.getViewTreeObserver().removeOnPreDrawListener(this);
                    expandButton.setVisibility(
                        textView.getLayout() != null && textView.getLayout().getLineCount() > MAX_TEXT_LINES
                            ? VISIBLE : GONE);
                    return true;
                }
            });
        }

        // Медиа
        boolean isVoiceOrMusic = messageObject.isVoice() || messageObject.isMusic();
        // Опрос — media.poll.question лежит отдельно от messageOwner.message,
        // поэтому findPostCaption() выше его не находит и textView для опроса
        // всегда пуст; весь контент опроса рисует отдельный pollView (см. ниже).
        // Опрос ищем по ВСЕЙ группе, а не только в messages.get(0) — пост-альбом
        // может состоять из сообщения с опросом + отдельных сообщений с фото/видео.
        //
        // ВАЖНО (исправлено — прошлое предположение здесь было неверным): у
        // TL_messageMediaPoll ЕСТЬ собственное поле attached_media (см.
        // TLRPC.TL_messageMediaPoll) — это официальный Telegram-функционал
        // "прикрепить фото/видео к вопросу опроса", и в этом случае фото лежит
        // ВНУТРИ того же самого TL-сообщения с опросом, а не в соседнем. Раньше
        // считалось, что медиа опроса всегда физически лежит в соседнем
        // TL-сообщении той же группы — это верно ТОЛЬКО для случая, когда фото
        // отправлено отдельным постом рядом с опросом (см. buildChannelItems
        // склейку в PotokFeedFragment); случай attached_media обрабатывается
        // отдельно ниже.
        MessageObject pollMessage = null;
        for (MessageObject mo : messages) {
            if (mo.messageOwner != null && mo.messageOwner.media instanceof TLRPC.TL_messageMediaPoll) {
                pollMessage = mo;
                break;
            }
        }
        boolean isPoll = pollMessage != null;
        TLRPC.MessageMedia postMedia = isPoll ? pollMessage.messageOwner.media : null;
        this.pollMessageObject = pollMessage;

        // Собираем медиа-сообщения из группы (только с фото/видео, БЕЗ файлов —
        // см. fileMessages ниже). Раньше TYPE_FILE не исключался, из-за чего медиа,
        // отправленное в канале специально КАК ФАЙЛ, разворачивалось в карусели как
        // обычное фото/видео — пользователь явно требует показывать его как файл,
        // как в оригинальном канале.
        ArrayList<MessageObject> mediaMessages = new ArrayList<>();
        // Медиа, отправленные КАК ФАЙЛ — рисуются отдельными строками через
        // documentsContainer/SharedDocumentCell (см. ниже), не в карусели.
        ArrayList<MessageObject> fileMessages = new ArrayList<>();
        for (MessageObject mo : messages) {
            if (mo.isVoice() || mo.isMusic()) continue;
            if (mo.type == MessageObject.TYPE_FILE) {
                fileMessages.add(mo);
            } else if (mo.photoThumbs != null && !mo.photoThumbs.isEmpty()) {
                mediaMessages.add(mo);
            }
        }
        // Фото/видео, прикреплённое ПРЯМО К ОПРОСУ (TL_messageMediaPoll.attached_media,
        // см. комментарий выше про pollMessage) — это НЕ отдельное сообщение-сосед,
        // а поле на media самого опроса. Заворачиваем в облегчённый клон
        // TLRPC.Message (id/dialog/from — как у исходного поста, media подменена на
        // attached_media) и переиспользуем ТУ ЖЕ карусель, что и для обычных
        // фото/видео постов — так бесплатно достаются blur/spoiler и открытие по
        // тапу, уже реализованные там. Ставим ПЕРВЫМ в mediaMessages, т.к. в
        // реальном Telegram (ChatMessageCell/PollContentDrawable) это медиа рисуется
        // НАД текстом вопроса, а не после соседних постов группы.
        if (isPoll && postMedia instanceof TLRPC.TL_messageMediaPoll) {
            TLRPC.MessageMedia attachedMedia = ((TLRPC.TL_messageMediaPoll) postMedia).attached_media;
            if (attachedMedia instanceof TLRPC.TL_messageMediaPhoto
                    || attachedMedia instanceof TLRPC.TL_messageMediaDocument) {
                TLRPC.Message src = pollMessage.messageOwner;
                TLRPC.Message clone = new TLRPC.TL_message();
                clone.id = src.id;
                clone.date = src.date;
                clone.dialog_id = src.dialog_id;
                clone.peer_id = src.peer_id;
                clone.from_id = src.from_id;
                clone.out = src.out;
                clone.post = src.post;
                clone.flags = src.flags;
                clone.media = attachedMedia;
                MessageObject attachedMo = new MessageObject(UserConfig.selectedAccount, clone, true, true);
                if (attachedMo.photoThumbs != null && !attachedMo.photoThumbs.isEmpty()) {
                    mediaMessages.add(0, attachedMo);
                }
            }
        }
        // НОВАЯ ДИАГНОСТИКА: подтверждаем на стороне отрисовки то, что уже
        // залогировано на стороне склейки (PotokFeedFragment.POLL_MERGE) — если
        // здесь messages.size()==1 для поста-опроса, значит склейка либо не
        // произошла (см. POLL_MERGE лог), либо произошла, но до setPost дошёл
        // только один из двух messages (баг где-то между buildChannelItems и
        // сюда, например при пересборке FeedItem->cellData).
        if (isPoll) {
            PotokDebugLog.d("PotokFeedLogo", "POLL_RENDER post=" + pollMessage.getId()
                + " messages.size()=" + messages.size()
                + " mediaMessages.size()=" + mediaMessages.size()
                + " всеIds=" + java.util.Arrays.toString(
                    messages.stream().map(MessageObject::getId).toArray()));
        }

        if (isPoll) {
            hideAudio();
            pollView.bind((TLRPC.TL_messageMediaPoll) postMedia, pollMessage);
        } else {
            pollView.setVisibility(GONE);
        }

        // Раньше карусель у постов с опросом принудительно скрывалась (hideCarousel())
        // безусловно, из-за чего медиа соседних сообщений той же группы никогда не
        // показывалось, даже если оно реально есть (баг, замеченный пользователем —
        // в самом канале медиа видно, а в ленте нет). Опрос и медиа не взаимоисключающие
        // друг друга — просто дальше используется тот же самый mediaMessages/carousel
        // путь, что и для обычных постов, независимо от isPoll.
        if (isVoiceOrMusic) {
            hideCarousel();
            audioContainer.setVisibility(VISIBLE);
            boolean voice = messageObject.isVoice();
            audioWaveformView.setVisibility(voice ? VISIBLE : GONE);
            audioSeekBarView.setVisibility(voice ? GONE : VISIBLE);
            if (voice) {
                audioTitleView.setText("Голосовое сообщение");
                audioWaveformView.setMessageObject(messageObject);
                audioSeekBarView.setMessageObject(null);
            } else {
                String title = messageObject.getMusicTitle();
                String performer = messageObject.getMusicAuthor();
                audioTitleView.setText(!TextUtils.isEmpty(title) ? title : "Аудио");
                // Текст выставляем всегда (даже если сейчас будет скрыт) — итоговую
                // видимость/кроссфейд строки 2 авторитетно решает
                // updateAudioSeekRowVisibility() ниже.
                audioPerformerView.setText(performer);
                audioSeekBarView.setMessageObject(messageObject);
                audioWaveformView.setMessageObject(null);
            }
            // Строка 2 (исполнитель <-> полоса перемотки) переключается строго по
            // тому, играет ли трек ПРЯМО СЕЙЧАС — 1:1 с оригиналом: до первого тапа
            // play и после паузы видна строка исполнителя, полоса появляется только
            // во время активного воспроизведения (см. updateAudioSeekRowVisibility).
            // animated=false — это свежий bind ячейки, а не живой переход на глазах
            // у пользователя.
            messageObject.checkMediaExistance(false);
            audioPlayButton.bind(messageObject);
            updateAudioSeekRowVisibility(messageObject, false);

            // Автозагрузка аудио в кэш — настройка из меню трёх точек + потолок
            // размера (1 МБ моб. / 3 МБ Wi-Fi, те же цифры, что у видео, см.
            // PotokFeedFragment) — то, чего у SharedAudioCell не было вообще: он
            // на автозагрузку в принципе не смотрел, просто сам решал скачивать ли
            // по своей внутренней логике "Общих медиа", не связанной с нашим
            // переключателем в ленте.
            boolean audioAutoload = PotokFeedFragment.isAutoloadAudioEnabled(getContext())
                && PotokFeedFragment.isSizeOkForAudioAutoload(messageObject.getDocument() != null ? messageObject.getDocument().size : 0);
            if (audioAutoload && !messageObject.mediaExists) {
                messageObject.putInDownloadsStore = true;
                FileLoader.getInstance(messageObject.currentAccount).loadFile(messageObject.getDocument(), messageObject, FileLoader.PRIORITY_LOW, 0);
            }
        } else if (!mediaMessages.isEmpty()) {
            hideAudio();

            // Вычисляем высоту по первому медиа
            MessageObject firstMedia = mediaMessages.get(0);
            int mediaHeightDp = calcMediaHeight(firstMedia);

            // Обновляем высоту карусели
            LayoutParams lp = (LayoutParams) carouselView.getLayoutParams();
            lp.height = dp(mediaHeightDp);
            carouselView.setLayoutParams(lp);

            carouselView.setVisibility(VISIBLE);

            // Карусель
            if (carouselAdapter == null) {
                carouselAdapter = new CarouselAdapter();
                carouselView.setAdapter(carouselAdapter);
            }
            carouselAdapter.setMessages(mediaMessages, mediaHeightDp);

            // Точки — показываем только если больше 1 медиа
            if (mediaMessages.size() > 1) {
                dotsIndicator.setPageCount(mediaMessages.size());
                dotsIndicator.setCurrentPage(0);
                dotsIndicator.setVisibility(VISIBLE);
                carouselView.scrollToPosition(0);
            } else {
                dotsIndicator.setVisibility(GONE);
            }
        } else {
            hideCarousel();
            hideAudio();
        }

        // --- Файлы (медиа, отправленное в канале КАК ФАЙЛ) ---
        // Независимо от ветки выше (voice/media/none) — файл может стоять рядом с
        // чем угодно (например, у поста-опроса caption пуст и медиа нет, но файл
        // всё равно должен показаться). Пересобираем строки заново на каждый bind —
        // самих файлов у одного поста обычно 0-1, так что пул не нужен.
        documentsContainer.removeAllViews();
        if (fileMessages.isEmpty()) {
            documentsContainer.setVisibility(GONE);
        } else {
            documentsContainer.setVisibility(VISIBLE);
            for (int i = 0; i < fileMessages.size(); i++) {
                SharedDocumentCell documentCell = new SharedDocumentCell(getContext());
                documentCell.setDocument(fileMessages.get(i), i < fileMessages.size() - 1);
                documentsContainer.addView(documentCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }
        }

        // Футер
        int views = messageObject.messageOwner != null ? messageObject.messageOwner.views : 0;
        viewsView.setText(views > 0 ? LocaleController.formatShortNumber(views, null) : "0");

        TLRPC.ReactionCount topReaction = getTopReaction(messageObject);
        if (topReaction != null) {
            if (topReaction.reaction instanceof TLRPC.TL_reactionCustomEmoji) {
                // Кастомная эмодзи-реакция — рисуем как стикер через AnimatedEmojiDrawable,
                // рядом только число (без места под юникод-символ).
                long documentId = ((TLRPC.TL_reactionCustomEmoji) topReaction.reaction).document_id;
                reactionEmojiView.setDocumentId(documentId);
                reactionEmojiView.setVisibility(VISIBLE);
                reactionView.setText(String.valueOf(topReaction.count));
            } else {
                reactionEmojiView.setVisibility(GONE);
                reactionEmojiView.setDocumentId(0);
                String emoji = "";
                if (topReaction.reaction instanceof TLRPC.TL_reactionEmoji) {
                    emoji = ((TLRPC.TL_reactionEmoji) topReaction.reaction).emoticon;
                }
                reactionView.setText(emoji + " " + topReaction.count);
            }
            reactionView.setVisibility(VISIBLE);
        } else {
            reactionEmojiView.setVisibility(GONE);
            reactionEmojiView.setDocumentId(0);
            reactionView.setVisibility(GONE);
        }

        // Комментарии — та же проверка, что ChatMessageCell использует для своей
        // кнопки комментариев в обычном чате: replies.comments означает, что
        // у этого конкретного поста есть привязанное обсуждение (не у каждого
        // поста канала оно обязательно есть, даже если у канала в целом
        // подключена группа обсуждений).
        TLRPC.MessageReplies replies = messageObject.messageOwner != null ? messageObject.messageOwner.replies : null;
        if (replies != null && replies.comments) {
            commentsView.setText(replies.replies > 0
                ? LocaleController.formatPluralString("CommentsCount", replies.replies)
                : LocaleController.getString(org.telegram.messenger.R.string.LeaveAComment));
            commentsRow.setVisibility(VISIBLE);
        } else {
            commentsRow.setVisibility(GONE);
        }
    }

    /**
     * Открыть тред комментариев поста — переиспользует тот же TL-запрос и тот же
     * публичный ChatActivity.setThreadMessages(...), которыми ChatActivity открывает
     * обсуждение по нажатию кнопки комментариев в обычном чате (см.
     * ChatActivity.openDiscussionMessageChat / processLoadedDiscussionMessage).
     * Упрощено: без хореографии загрузки-индикаторов самого ChatActivity (она
     * привязана к его внутренним полям типа commentLoadingMessageId/chatListView,
     * которые не имеют смысла здесь) — ChatActivity сам подгрузит остальную
     * историю комментариев своим обычным механизмом при открытии.
     */
    private void openComments() {
        if (currentMessage == null || currentChannel == null || parentFragment == null) return;
        TLRPC.MessageReplies replies = currentMessage.messageOwner != null ? currentMessage.messageOwner.replies : null;
        if (replies == null || !replies.comments) return;
        // Фикс "нажимаю 2-3-4 раза": раньше каждый тап отправлял новый сетевой запрос —
        // при нескольких быстрых тапах улетало несколько запросов, и по мере того как они
        // возвращались, экран комментариев мог попытаться открыться повторно. Теперь —
        // пока первый запрос не отработал, повторные тапы игнорируются.
        if (commentsLoading) return;
        commentsLoading = true;
        commentsRow.setAlpha(0.5f);

        final int originalMsgId = currentMessage.getId();
        final TLRPC.Chat originalChat = currentChannel;
        final int maxReadId = replies.read_max_id;

        TLRPC.TL_messages_getDiscussionMessage req = new TLRPC.TL_messages_getDiscussionMessage();
        req.peer = org.telegram.messenger.MessagesController.getInputPeer(originalChat);
        req.msg_id = originalMsgId;

        org.telegram.messenger.MessagesController controller = parentFragment.getMessagesController();
        parentFragment.getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            commentsLoading = false;
            commentsRow.setAlpha(1f);
            if (parentFragment == null || parentFragment.getParentActivity() == null) return;
            if (!(response instanceof TLRPC.TL_messages_discussionMessage)) {
                FileLog.e("PotokFeedPostCell: getDiscussionMessage failed, error=" + (error != null ? error.text : "null response"));
                return;
            }
            TLRPC.TL_messages_discussionMessage discussionMessage = (TLRPC.TL_messages_discussionMessage) response;
            controller.putUsers(discussionMessage.users, false);
            controller.putChats(discussionMessage.chats, false);

            ArrayList<MessageObject> threadMessages = new ArrayList<>();
            for (TLRPC.Message message : discussionMessage.messages) {
                if (message instanceof TLRPC.TL_messageEmpty) continue;
                message.isThreadMessage = true;
                threadMessages.add(new MessageObject(org.telegram.messenger.UserConfig.selectedAccount, message, true, true));
            }
            if (threadMessages.isEmpty()) {
                if (parentFragment.getParentActivity() != null) {
                    org.telegram.ui.Components.BulletinFactory.of(parentFragment)
                        .createErrorBulletin(LocaleController.getString(org.telegram.messenger.R.string.ChannelPostDeleted))
                        .show();
                }
                return;
            }

            long dialogId = threadMessages.get(0).getDialogId();
            android.os.Bundle args = new android.os.Bundle();
            args.putLong("chat_id", -dialogId);
            args.putInt("message_id", Math.max(1, discussionMessage.read_inbox_max_id));
            args.putInt("unread_count", discussionMessage.unread_count);
            ChatActivity chatActivity = new ChatActivity(args);
            chatActivity.setThreadMessages(threadMessages, originalChat, originalMsgId, discussionMessage.read_inbox_max_id, discussionMessage.read_outbox_max_id, null);
            parentFragment.presentFragment(chatActivity);
        }));
    }

    private void hideCarousel() {
        carouselView.setVisibility(GONE);
        dotsIndicator.setVisibility(GONE);
    }

    /**
     * Реальный текст поста: сначала ищем caption среди всех сообщений альбома
     * (caption у альбома хранится только на одном из сообщений группы, обычно
     * первом с непустой подписью), и только если это одиночное текстовое
     * сообщение без медиа — берём messageText. Этот метод — единая точка входа
     * для текста поста, используется и в setPost (отображение), и в showPostMenu
     * (копирование), чтобы они не могли разойтись.
     *
     * Раньше "Копировать" в меню брал currentMessage.messageText напрямую: для
     * первого сообщения альбома без своего caption это служебная строка вида
     * "Альбом" (так Telegram обозначает медиагруппу в списках), а не реальный текст.
     */
    private CharSequence findPostCaption(ArrayList<MessageObject> messages, MessageObject firstMessage) {
        for (MessageObject mo : messages) {
            if (!TextUtils.isEmpty(mo.caption)) return mo.caption;
        }
        if (messages.size() == 1 && firstMessage.type == MessageObject.TYPE_TEXT) {
            return firstMessage.messageText;
        }
        return null;
    }

    /**
     * Единая точка скрытия аудио-блока — используется во всех ветках setPost(),
     * где поста-аудио сейчас нет (опрос, обычное медиа, текст без вложений).
     * Раньше в каждой из трёх веток была своя копия одного и того же кода
     * (снять messageObject с сикбара/волны, скрыть контейнер) — не критично, но
     * при добавлении нового audioPlayButton/audioWaveformView пришлось бы менять
     * все три места одинаково; DRY.
     */
    private void hideAudio() {
        audioContainer.setVisibility(GONE);
        audioSeekBarView.setMessageObject(null);
        audioWaveformView.setMessageObject(null);
        audioPlayButton.unbind();
        // Отменяем любую недоигравшую анимацию кроссфейда и сбрасываем оба ребёнка
        // строки 2 в чистое состояние — иначе при переиспользовании этого ViewHolder'а
        // под другой пост (RecyclerView recycling) можно унаследовать "застрявшую"
        // альфу от прошлой анимации.
        audioSeekRow.animate().cancel();
        audioPerformerView.animate().cancel();
        audioSeekRow.setAlpha(1f);
        audioSeekRow.setVisibility(GONE);
        audioPerformerView.setAlpha(1f);
        audioPerformerView.setVisibility(GONE);
    }

    /**
     * Строка 3 (константа): "0:00 / 3:45". 1:1 с ChatMessageCell — текущая позиция
     * показывается ТОЛЬКО пока трек реально играет (не на паузе), иначе всегда "0:00",
     * даже если mo.audioProgressSec ещё хранит позицию последней паузы.
     */
    private void updateAudioTimeText(MessageObject mo) {
        boolean activelyPlaying = MediaController.getInstance().isPlayingMessage(mo)
            && !MediaController.getInstance().isMessagePaused();
        int durationSec = (int) mo.getDuration();
        int playedSec = activelyPlaying ? mo.audioProgressSec : 0;
        audioTimeView.setText(AndroidUtilities.formatShortDuration(playedSec, durationSec));
    }

    /** Плавный кроссфейд между двумя детьми audioSecondRow (аналог alpha-перехода
     * ChatMessageCell.toSeekBarProgress между performerLayout и seekBar). animated=false
     * используется на свежем bind() ViewHolder'а — переключение должно быть мгновенным,
     * а не анимированным, когда пользователь ещё не видел предыдущего состояния. */
    private void crossfadeSecondRow(View show, View hide, boolean animated) {
        show.animate().cancel();
        hide.animate().cancel();
        if (!animated) {
            show.setAlpha(1f);
            show.setVisibility(VISIBLE);
            hide.setAlpha(0f);
            hide.setVisibility(GONE);
            return;
        }
        if (show.getVisibility() != VISIBLE || show.getAlpha() < 1f) {
            show.setVisibility(VISIBLE);
            show.animate().alpha(1f).setDuration(220).start();
        }
        if (hide.getVisibility() == VISIBLE) {
            hide.animate().alpha(0f).setDuration(220).withEndAction(() -> {
                if (hide.getAlpha() <= 0f) hide.setVisibility(GONE);
            }).start();
        }
    }

    /**
     * Переключает строку 2 (audioSecondRow: исполнитель <-> полоса перемотки) — строго
     * по тому, играет ли ЭТОТ трек ПРЯМО СЕЙЧАС (не на паузе), с анимированным
     * кроссфейдом вместо резкого GONE/VISIBLE. Строка 3 (audioTimeView) больше не
     * зависит от activelyPlaying — она обновляется всегда, отдельно от переключения.
     */
    private void updateAudioSeekRowVisibility(MessageObject mo, boolean animated) {
        boolean activelyPlaying = MediaController.getInstance().isPlayingMessage(mo)
            && !MediaController.getInstance().isMessagePaused();
        boolean hasPerformerRow = !mo.isVoice() && !TextUtils.isEmpty(mo.getMusicAuthor());
        if (hasPerformerRow) {
            if (activelyPlaying) {
                crossfadeSecondRow(audioSeekRow, audioPerformerView, animated);
            } else {
                crossfadeSecondRow(audioPerformerView, audioSeekRow, animated);
            }
        } else {
            // Войс или музыка без указанного исполнителя — исполнителю нечего
            // показывать, полоса/волна просто показывается или прячется без пары.
            audioPerformerView.animate().cancel();
            audioPerformerView.setAlpha(1f);
            audioPerformerView.setVisibility(GONE);
            audioSeekRow.animate().cancel();
            audioSeekRow.setAlpha(1f);
            audioSeekRow.setVisibility(activelyPlaying ? VISIBLE : GONE);
        }
        updateAudioTimeText(mo);
    }

    /**
     * Вызывается извне (PotokFeedFragment) по NotificationCenter.messagePlayingProgressDidChanged,
     * когда играющее сейчас сообщение совпадает с тем, что показано в этой карточке.
     * Без этого вызова ползунок и время не двигались бы во время воспроизведения —
     * SeekBar обновляется только когда ему явно передают новый progress.
     */
    public void updateAudioProgressIfPlaying(int messageId) {
        if (currentMessage == null || currentMessage.getId() != messageId) return;
        if (audioContainer.getVisibility() != VISIBLE) return;
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null) return;
        currentMessage.audioProgress = playing.audioProgress;
        currentMessage.audioProgressSec = playing.audioProgressSec;
        currentMessage.bufferedProgress = playing.bufferedProgress;
        if (audioSeekRow.getVisibility() != VISIBLE) {
            // Прогресс потёк — значит трек реально играет; если полоса ещё не
            // показана (например, статус playing/paused сменился без отдельного
            // messagePlayingPlayStateChanged), досчитаем видимость прямо здесь.
            // Без анимации — это "досчитывание" пропущенного состояния, а не живой
            // пользовательский переход.
            updateAudioSeekRowVisibility(currentMessage, false);
        }
        audioSeekBarView.updateProgress();
        audioWaveformView.updateProgress();
        updateAudioTimeText(currentMessage);
    }

    /**
     * Вызывается извне при messagePlayingDidStart/messagePlayingPlayStateChanged/
     * messagePlayingDidReset (см. PotokFeedFragment) — эти уведомления шлются НЕ
     * только для конкретного messageId (в отличие от messagePlayingProgressDidChanged
     * выше), а вообще при любой смене состояния плеера, поэтому вызывается по ВСЕМ
     * видимым ячейкам, а не только по совпадающей — иначе если заиграл другой трек,
     * кнопка play/pause этой ячейки не узнала бы об этом и осталась показывать
     * устаревшее состояние. Именно отсюда приходит анимированное переключение
     * исполнитель↔полоса при постановке на паузу/остановке — а не только на старте.
     */
    public void refreshAudioPlaybackState() {
        if (audioContainer.getVisibility() == VISIBLE && currentMessage != null) {
            audioPlayButton.refresh();
            updateAudioSeekRowVisibility(currentMessage, true);
        }
    }

    /**
     * Тот же паттерн, что и updateAudioProgressIfPlaying выше: PotokFeedFragment
     * подписан на NotificationCenter.didUpdatePollResults централизованно (один раз
     * на фрагмент) и рассылает по всем реально видимым сейчас ячейкам ленты — эта
     * ячейка перерисовывает опрос, только если он у неё сейчас реально показан
     * (id опроса совпадает), в точности как обрабатывает то же самое уведомление
     * ChatActivity.didReceivedNotification для обычных чатов.
     */
    public void updatePollIfMatching(long pollId, TLRPC.TL_poll poll, TLRPC.PollResults results) {
        if (pollMessageObject == null || pollMessageObject.getPollId() != pollId) return;
        TLRPC.TL_messageMediaPoll media = (TLRPC.TL_messageMediaPoll) pollMessageObject.messageOwner.media;
        if (poll != null) {
            media.poll = poll;
        }
        MessageObject.updatePollResults(media, results);
        pollView.bind(media, pollMessageObject);
    }

    /** Входит ли сообщение с этим id в пост, который сейчас показывает эта ячейка. */
    public boolean containsMessageId(int messageId) {
        if (currentMessages != null) {
            for (MessageObject mo : currentMessages) {
                if (mo != null && mo.getId() == messageId) return true;
            }
        }
        return currentMessage != null && currentMessage.getId() == messageId;
    }

    /**
     * BackupImageView конкретного медиа-сообщения из карусели поста — нужен
     * PotokFeedFragment.getPlaceForPhoto() (см. там) для анимации "разворота"
     * PhotoViewer из миниатюры в полный экран (и обратно).
     *
     * Раньше метод возвращал только ImageReceiver, а фрагмент брал координаты через
     * getLocationInWindow() у ЭТОЙ ячейки (PotokFeedPostCell) целиком — это в корне
     * неверно: в ChatActivity ImageReceiver рисуется прямо в onDraw() самой ячейки
     * (ChatMessageCell), поэтому там его координаты действительно относительны
     * ячейке. У нас же фото лежит во ВЛОЖЕННОМ BackupImageView внутри вложенного
     * RecyclerView (карусели) — координаты ImageReceiver относительны именно этому
     * BackupImageView, а не всей карточке поста. Из-за этого анимация открытия
     * "разворачивалась" от верха карточки (шапки канала), а не от самого фото.
     * Теперь отдаём сам BackupImageView — фрагмент возьмёт его собственные
     * getLocationInWindow(), а не координаты внешней ячейки.
     *
     * Возвращает null, если это медиа сейчас реально не видно на экране (карусель
     * прокручена дальше, или ViewHolder ещё не создан) — тогда PhotoViewer просто
     * откроется без анимации разворота, без падений.
     */
    public BackupImageView getPhotoImageViewForMessage(MessageObject mo) {
        if (mo == null || carouselAdapter == null || carouselView == null || carouselView.getVisibility() != VISIBLE) {
            return null;
        }
        int index = -1;
        for (int i = 0; i < carouselAdapter.items.size(); i++) {
            MessageObject item = carouselAdapter.items.get(i);
            if (item != null && item.getId() == mo.getId()) {
                index = i;
                break;
            }
        }
        if (index < 0) return null;
        int childCount = carouselView.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = carouselView.getChildAt(i);
            if (carouselView.getChildAdapterPosition(child) != index) continue;
            RecyclerView.ViewHolder vh = carouselView.getChildViewHolder(child);
            if (vh instanceof CarouselAdapter.MediaHolder) {
                return ((CarouselAdapter.MediaHolder) vh).img;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private int calcMediaHeight(MessageObject mo) {
        int w = 0, h = 0;
        TLRPC.MessageMedia media = mo.messageOwner != null ? mo.messageOwner.media : null;
        if (media instanceof TLRPC.TL_messageMediaPhoto && media.photo != null) {
            TLRPC.PhotoSize biggest = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, 1280, false, null, true);
            if (biggest != null) { w = biggest.w; h = biggest.h; }
        } else if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null) {
            for (TLRPC.DocumentAttribute attr : media.document.attributes) {
                if (attr instanceof TLRPC.TL_documentAttributeVideo) { w = attr.w; h = attr.h; break; }
            }
            // Если у видео нет атрибута с размерами (или они нулевые) — берём размер
            // из миниатюры самого документа (document.thumbs), а не photoThumbs:
            // photoThumbs относится к фото-сообщениям и у видео почти всегда пуст.
            if (w <= 0 || h <= 0) {
                TLRPC.PhotoSize ps = FileLoader.getClosestPhotoSizeWithSize(media.document.thumbs, 1280, false, null, true);
                if (ps != null) { w = ps.w; h = ps.h; }
            }
        }
        if (w == 0 && mo.photoThumbs != null) {
            TLRPC.PhotoSize ps = FileLoader.getClosestPhotoSizeWithSize(mo.photoThumbs, 1280, false, null, true);
            if (ps != null) { w = ps.w; h = ps.h; }
        }
        if (w <= 0 || h <= 0) return MIN_MEDIA_HEIGHT_DP;
        int screenWidthPx = AndroidUtilities.displaySize.x;
        int calcPx = Math.round(screenWidthPx * (h / (float) w));
        int calcDp = (int) (calcPx / AndroidUtilities.density);
        return Math.max(MIN_MEDIA_HEIGHT_DP, Math.min(MAX_MEDIA_HEIGHT_DP, calcDp));
    }

    private void openMediaViewer(MessageObject mo, int index, ArrayList<MessageObject> all) {
        if (mo == null || parentActivity == null) return;
        PotokDebugLog.d("GHOST", "openMediaViewer post=" + mo.getId()
            + " isVideo=" + (mo.isVideo() || mo.isGif()) + " isGif=" + mo.isGif() + " groupSize=" + (all != null ? all.size() : 1));
        PhotoViewer.getInstance().setParentActivity(parentActivity);
        long dialogId = mo.getDialogId();
        // Раньше здесь стоял EmptyPhotoViewerProvider — он намеренно НЕ даёт PhotoViewer
        // никакой информации об исходной миниатюре (getPlaceForPhoto у него всегда
        // возвращает null), из-за чего просмотрщик открывался/закрывался простым
        // появлением/исчезновением без анимации "разворота" из карточки. Теперь
        // передаём провайдер, который умеет найти реальный ImageReceiver миниатюры в
        // карусели ленты (см. PotokFeedFragment.getPhotoViewerProvider() /
        // getPlaceForPhoto()) — тот же самый механизм getPlaceForPhoto, которым в
        // самом ChatActivity анимируется открытие фото/видео из обычного чата.
        PhotoViewer.PhotoViewerProvider provider = (parentFragment instanceof org.telegram.ui.PotokFeedFragment)
            ? ((org.telegram.ui.PotokFeedFragment) parentFragment).getPhotoViewerProvider()
            : new PhotoViewer.EmptyPhotoViewerProvider();
        if (all != null && all.size() > 1) {
            // Группа из нескольких медиа (альбом) — открываем со списком и индексом,
            // независимо от того видео это или фото, чтобы PhotoViewer мог свайпать
            // между элементами и правильно инициализировать видеоплеер в контексте группы.
            PhotoViewer.getInstance().openPhoto(all, index, dialogId, 0L, 0L, provider);
        } else {
            // Одиночное медиа — старая логика подходит, отдельный путь для видео не нужен
            PhotoViewer.getInstance().openPhoto(mo, dialogId, 0, 0, provider, true);
        }
    }

    private ActionBarPopupWindow postMenuWindow;

    private void openPostInChannel() {
        if (currentMessage == null || currentChannel == null || parentFragment == null) return;
        android.os.Bundle args = new android.os.Bundle();
        args.putLong("chat_id", currentChannel.id);
        args.putInt("message_id", currentMessage.getId());
        parentFragment.presentFragment(new ChatActivity(args));
    }

    // Клик по названию канала или аватарке -> профиль канала.
    private void openChannelProfile() {
        if (currentChannel == null || parentFragment == null) return;
        android.os.Bundle args = new android.os.Bundle();
        args.putLong("chat_id", currentChannel.id);
        parentFragment.presentFragment(new org.telegram.ui.ProfileActivity(args));
    }

    private void showPostMenu(View anchor) {
        if (getContext() == null || currentMessage == null || parentActivity == null) return;
        if (postMenuWindow != null) {
            postMenuWindow.dismiss();
            postMenuWindow = null;
        }

        String username = currentChannel != null ? currentChannel.username : null;
        int msgId = currentMessage.getId();
        String postUrl = (username != null) ? "https://t.me/" + username + "/" + msgId : null;
        ArrayList<MessageObject> groupMessages = currentMessages != null ? currentMessages : new ArrayList<>(java.util.Collections.singletonList(currentMessage));
        CharSequence msgText = findPostCaption(groupMessages, currentMessage);

        ActionBarPopupWindow.ActionBarPopupWindowLayout layout =
            new ActionBarPopupWindow.ActionBarPopupWindowLayout(getContext(), org.telegram.messenger.R.drawable.popup_fixed_alert4, null);
        layout.setMinimumWidth(AndroidUtilities.dp(200));
        layout.setBackgroundColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground));

        int idx = 0;

        // Копировать текст
        if (!android.text.TextUtils.isEmpty(msgText)) {
            final CharSequence finalText = msgText;
            ActionBarMenuSubItem copyText = new ActionBarMenuSubItem(getContext(), idx == 0, false, null);
            copyText.setMinimumWidth(AndroidUtilities.dp(200));
            copyText.setTextAndIcon(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.Copy), org.telegram.messenger.R.drawable.msg_copy);
            layout.addView(copyText);
            copyText.setOnClickListener(v -> {
                AndroidUtilities.addToClipboard(finalText);
                android.widget.Toast.makeText(getContext(), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TextCopied), android.widget.Toast.LENGTH_SHORT).show();
                if (postMenuWindow != null) postMenuWindow.dismiss();
            });
            idx++;
        }

        // Скопировать ссылку
        if (postUrl != null) {
            final String finalUrl = postUrl;
            ActionBarMenuSubItem copyLink = new ActionBarMenuSubItem(getContext(), idx == 0, false, null);
            copyLink.setMinimumWidth(AndroidUtilities.dp(200));
            copyLink.setTextAndIcon(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.CopyLink), org.telegram.messenger.R.drawable.msg_link);
            layout.addView(copyLink);
            copyLink.setOnClickListener(v -> {
                AndroidUtilities.addToClipboard(finalUrl);
                android.widget.Toast.makeText(getContext(), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.LinkCopied), android.widget.Toast.LENGTH_SHORT).show();
                if (postMenuWindow != null) postMenuWindow.dismiss();
            });
            idx++;
        }

        // Удалить из кэша — по образцу пункта меню сообщения в самом Telegram
        // (между "Статистика сообщения" и "Сохранить в галерею"). Проверяем реальное
        // наличие файла на диске тем же методом, что и оригинал (MessageObject.
        // checkMediaExistance() + getPathToMessage()), а не своей отдельной логикой —
        // так поведение гарантированно совпадает с тем, что считает "скачанным" сам
        // Telegram. Пункт виден, только если хотя бы один медиафайл поста реально
        // лежит в кэше. Удаление идёт по конкретным файлам этого поста (FileLoader.
        // deleteFiles), а не по всему кэшу целиком — т.к. кэш общий для чата и ленты
        // (см. диагностику из предыдущей сессии), удаление здесь удаляет файл и для
        // канала тоже, это ожидаемое поведение, а не побочный эффект.
        final ArrayList<java.io.File> cachedFilesToDelete = new ArrayList<>();
        for (MessageObject mo : groupMessages) {
            boolean hasMedia = mo.isVoice() || mo.isMusic() || mo.isVideo() || mo.isGif()
                || (mo.photoThumbs != null && !mo.photoThumbs.isEmpty());
            if (!hasMedia) continue;
            mo.checkMediaExistance(false);
            if (mo.mediaExists) {
                java.io.File f = FileLoader.getInstance(mo.currentAccount).getPathToMessage(mo.messageOwner, false);
                if (f != null && f.exists()) {
                    cachedFilesToDelete.add(f);
                }
            }
        }
        if (!cachedFilesToDelete.isEmpty()) {
            final int deleteAccount = groupMessages.get(0).currentAccount;
            long totalDeleteSize = 0;
            for (java.io.File f : cachedFilesToDelete) totalDeleteSize += f.length();
            final long finalTotalDeleteSize = totalDeleteSize;
            // Название для диалога: если файл один — его реальное имя (как в
            // референсе Plus Messenger), если несколько — просто количество.
            final String deleteLabel = cachedFilesToDelete.size() == 1
                ? cachedFilesToDelete.get(0).getName()
                : cachedFilesToDelete.size() + " файлов";
            ActionBarMenuSubItem clearCache = new ActionBarMenuSubItem(getContext(), idx == 0, false, null);
            clearCache.setMinimumWidth(AndroidUtilities.dp(200));
            clearCache.setTextAndIcon("Удалить из кэша", org.telegram.messenger.R.drawable.msg_clearcache);
            layout.addView(clearCache);
            clearCache.setOnClickListener(v -> {
                if (postMenuWindow != null) postMenuWindow.dismiss();
                // Подтверждение "Да/Отмена" перед реальным удалением — по образцу
                // Plus Messenger, который пользователь прислал как референс: тот же
                // текст ("Удалить из кэша" / имя файла / "Очистить X?" / "Вы можете
                // скачать файл позже"), но с явным выбором вместо одной кнопки "OK".
                AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                builder.setTitle("Удалить из кэша");
                builder.setMessage(deleteLabel + "\n\nОчистить " + AndroidUtilities.formatFileSize(finalTotalDeleteSize) + "?\n\nВы можете скачать файл позже");
                builder.setNegativeButton("Отмена", null);
                builder.setPositiveButton("Да", (d, w) -> {
                    FileLoader.getInstance(deleteAccount).deleteFiles(cachedFilesToDelete, 0);
                    // Видео в этом посте (если было) было проигрываемым напрямую из кэша —
                    // теперь файла нет, нужно перепривязать карусель, чтобы плашка загрузки
                    // появилась заново (тот же путь, что и notifyItemChanged после докачки,
                    // см. CarouselAdapter — но здесь вызов идёт из клика по меню, а не из
                    // асинхронного колбэка загрузки, так что мы НЕ внутри layout/scroll
                    // прохода RecyclerView и notifyDataSetChanged() безопасен напрямую).
                    if (carouselAdapter != null) {
                        carouselAdapter.notifyDataSetChanged();
                    }
                });
                builder.show();
            });
            idx++;
        }

        // Скачать медиа (фото/видео/аудио поста)
        ArrayList<MessageObject> mediaToSave = new ArrayList<>();
        for (MessageObject mo : groupMessages) {
            boolean hasMedia = mo.isVoice() || mo.isMusic() || mo.isVideo() || mo.isGif()
                || (mo.photoThumbs != null && !mo.photoThumbs.isEmpty());
            if (hasMedia) mediaToSave.add(mo);
        }
        if (!mediaToSave.isEmpty() && parentFragment != null) {
            // Раньше кнопка всегда была "Сохранить в галерею" с иконкой галереи —
            // для чисто аудио-поста это и звучало, и выглядело неуместно (видна
            // была "кнопка для фото-видео"). Теперь текст/иконка зависят от
            // реального типа: музыка -> "Сохранить в музыку", остальное -> галерея.
            boolean isMusicOnly = true;
            for (MessageObject mo : mediaToSave) {
                if (!mo.isMusic() && !mo.isVoice()) { isMusicOnly = false; break; }
            }
            final boolean finalIsMusicOnly = isMusicOnly;
            ActionBarMenuSubItem downloadMedia = new ActionBarMenuSubItem(getContext(), idx == 0, false, null);
            downloadMedia.setMinimumWidth(AndroidUtilities.dp(200));
            downloadMedia.setTextAndIcon(
                org.telegram.messenger.LocaleController.getString(finalIsMusicOnly
                    ? org.telegram.messenger.R.string.SaveToMusic
                    : org.telegram.messenger.R.string.SaveToGallery),
                finalIsMusicOnly ? org.telegram.messenger.R.drawable.msg_download : org.telegram.messenger.R.drawable.msg_gallery
            );
            layout.addView(downloadMedia);
            downloadMedia.setOnClickListener(v -> {
                if (postMenuWindow != null) postMenuWindow.dismiss();
                if (parentActivity == null) {
                    // saveFilesFromMessages требует Context для прогресс-диалога —
                    // без него MediaLoader падает с NPE внутри фонового потока,
                    // что выглядит как "молча не скачалось".
                    FileLog.e("PotokFeedPostCell: cannot save media, parentActivity is null");
                    return;
                }
                try {
                    MediaController.saveFilesFromMessages(parentActivity, parentFragment.getAccountInstance(), mediaToSave, count -> {
                        if (count > 0 && parentActivity != null && parentFragment != null) {
                            org.telegram.ui.Components.BulletinFactory.of(parentFragment)
                                .createDownloadBulletin(finalIsMusicOnly
                                    ? org.telegram.ui.Components.BulletinFactory.FileType.AUDIOS
                                    : org.telegram.ui.Components.BulletinFactory.FileType.UNKNOWNS, count, null)
                                .show();
                        } else {
                            FileLog.e("PotokFeedPostCell: saveFilesFromMessages finished with count=" + count);
                        }
                    });
                } catch (Exception e) {
                    FileLog.e("PotokFeedPostCell: saveFilesFromMessages threw", e);
                }
            });
            idx++;
        }

        // Переслать
        layout.addView(new ActionBarPopupWindow.GapView(getContext(), null), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));
        ActionBarMenuSubItem forward = new ActionBarMenuSubItem(getContext(), true, false, null);
        forward.setMinimumWidth(AndroidUtilities.dp(200));
        forward.setTextAndIcon(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.Forward), org.telegram.messenger.R.drawable.msg_forward);
        layout.addView(forward);
        forward.setOnClickListener(v -> {
            if (postMenuWindow != null) postMenuWindow.dismiss();
            openForwardDialog(false);
        });

        // Переслать без автора
        ActionBarMenuSubItem forwardNoAuthor = new ActionBarMenuSubItem(getContext(), false, false, null);
        forwardNoAuthor.setMinimumWidth(AndroidUtilities.dp(200));
        forwardNoAuthor.setTextAndIcon("Переслать без автора", org.telegram.messenger.R.drawable.msg_forward_replace);
        layout.addView(forwardNoAuthor);
        forwardNoAuthor.setOnClickListener(v -> {
            if (postMenuWindow != null) postMenuWindow.dismiss();
            openForwardDialog(true);
        });

        // Перевести
        layout.addView(new ActionBarPopupWindow.GapView(getContext(), null), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));
        ActionBarMenuSubItem translate = new ActionBarMenuSubItem(getContext(), true, false, null);
        translate.setMinimumWidth(AndroidUtilities.dp(200));
        translate.setTextAndIcon(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TranslateMessage), org.telegram.messenger.R.drawable.msg_translate);
        layout.addView(translate);
        final CharSequence finalMsgText = msgText;
        translate.setOnClickListener(v -> {
            if (postMenuWindow != null) postMenuWindow.dismiss();
            if (parentActivity != null && !android.text.TextUtils.isEmpty(finalMsgText)) {
                String toLang = TranslateAlert2.getToLanguage();
                TranslateAlert2.showAlert(parentActivity, null,
                    org.telegram.messenger.UserConfig.selectedAccount,
                    null, toLang, finalMsgText, null, false, null, null);
            }
        });

        // Сохранить в избранное
        ActionBarMenuSubItem saveFav = new ActionBarMenuSubItem(getContext(), false, false, null);
        saveFav.setMinimumWidth(AndroidUtilities.dp(200));
        saveFav.setTextAndIcon(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.AddToFavorites), org.telegram.messenger.R.drawable.msg_saved);
        layout.addView(saveFav);
        saveFav.setOnClickListener(v -> {
            if (postMenuWindow != null) postMenuWindow.dismiss();
            // Пересылаем в Избранное (Saved Messages)
            long selfId = org.telegram.messenger.UserConfig.getInstance(org.telegram.messenger.UserConfig.selectedAccount).getClientUserId();
            ArrayList<MessageObject> msgs = new ArrayList<>();
            if (currentMessages != null) msgs.addAll(currentMessages); else msgs.add(currentMessage);
            org.telegram.messenger.SendMessagesHelper.getInstance(org.telegram.messenger.UserConfig.selectedAccount)
                .sendMessage(msgs, selfId, false, false, true, 0, 0);
            android.widget.Toast.makeText(getContext(), org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.FwdMessageToSavedMessages), android.widget.Toast.LENGTH_SHORT).show();
        });

        // Не показывать посты из этого канала — тот же механизм hiddenChannelIds/
        // SharedPreferences, что и общий фильтр каналов по кнопке в шапке ленты.
        if (currentChannel != null) {
            final TLRPC.Chat channelToHide = currentChannel;
            ActionBarMenuSubItem hideChannel = new ActionBarMenuSubItem(getContext(), false, false, null);
            hideChannel.setMinimumWidth(AndroidUtilities.dp(200));
            hideChannel.setTextAndIcon("Не показывать посты из этого канала", org.telegram.messenger.R.drawable.msg_block2);
            hideChannel.setColors(Theme.getColor(Theme.key_text_RedRegular), Theme.getColor(Theme.key_text_RedRegular));
            layout.addView(hideChannel);
            hideChannel.setOnClickListener(v -> {
                if (postMenuWindow != null) postMenuWindow.dismiss();
                if (parentFragment instanceof org.telegram.ui.PotokFeedFragment) {
                    ((org.telegram.ui.PotokFeedFragment) parentFragment).hideChannel(channelToHide);
                }
            });
        }

        // Пожаловаться
        layout.addView(new ActionBarPopupWindow.GapView(getContext(), null), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));
        ActionBarMenuSubItem report = new ActionBarMenuSubItem(getContext(), true, true, null);
        report.setMinimumWidth(AndroidUtilities.dp(200));
        report.setTextAndIcon(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.ReportChat), org.telegram.messenger.R.drawable.msg_report);
        report.setColors(Theme.getColor(Theme.key_text_RedRegular), Theme.getColor(Theme.key_text_RedRegular));
        layout.addView(report);
        report.setOnClickListener(v -> {
            if (postMenuWindow != null) postMenuWindow.dismiss();
            if (parentFragment != null) {
                ReportBottomSheet.openMessage(parentFragment, currentMessage);
            }
        });

        postMenuWindow = new ActionBarPopupWindow(layout, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
        postMenuWindow.setFocusable(true);
        postMenuWindow.setOutsideTouchable(true);
        postMenuWindow.setClippingEnabled(true);
        postMenuWindow.setAnimationStyle(org.telegram.messenger.R.style.PopupAnimation);
        postMenuWindow.setOnDismissListener(() -> postMenuWindow = null);

        layout.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        );

        int[] location = new int[2];
        anchor.getLocationInWindow(location);
        int x = location[0] + anchor.getWidth() - layout.getMeasuredWidth();
        int y = location[1] + anchor.getHeight();
        postMenuWindow.showAtLocation(anchor, android.view.Gravity.LEFT | android.view.Gravity.TOP, x, y);
        ActionBarPopupWindow.startAnimation(layout);
    }

    private void openForwardDialog(boolean noAuthor) {
        if (currentMessage == null || parentActivity == null) return;
        android.os.Bundle args = new android.os.Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        args.putInt("messagesCount", 1);
        args.putBoolean("canSelectTopics", true);
        // Store message for forward; use intent or static holder
        PotokForwardHolder.message = currentMessage;
        PotokForwardHolder.noAuthor = noAuthor;
        DialogsActivity fragment = new DialogsActivity(args);
        fragment.setDelegate((fragment1, dids, message2, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids == null || dids.isEmpty()) return false;
            long targetId = dids.get(0).dialogId;
            ArrayList<MessageObject> msgs = new ArrayList<>();
            if (currentMessages != null) msgs.addAll(currentMessages); else msgs.add(currentMessage);
            org.telegram.messenger.SendMessagesHelper.getInstance(org.telegram.messenger.UserConfig.selectedAccount)
                .sendMessage(msgs, targetId, noAuthor, false, true, 0, 0);
            fragment1.finishFragment();
            return true;
        });
        if (parentFragment != null) {
            parentFragment.presentFragment(fragment);
        }
    }

    private TLRPC.ReactionCount getTopReaction(MessageObject messageObject) {
        if (messageObject.messageOwner == null || messageObject.messageOwner.reactions == null) return null;
        ArrayList<TLRPC.ReactionCount> results = messageObject.messageOwner.reactions.results;
        if (results == null || results.isEmpty()) return null;
        TLRPC.ReactionCount top = results.get(0);
        for (int i = 1; i < results.size(); i++) {
            if (results.get(i).count > top.count) top = results.get(i);
        }
        return top;
    }

    // ------------------------------------------------------------------ MEDIA_DIAG
    // ТОЛЬКО ДИАГНОСТИКА, ничего не чинит и ни на что не влияет — по прямому
    // требованию пользователя: не патчить блюр/зависание видео вслепую ещё раз,
    // а сначала собрать логи, которых хватит понять причину БЕЗ гаданий.
    // Единая точка снэпшота реального состояния ImageReceiver/AnimatedFileDrawable,
    // используется из нескольких мест ниже (bind, отложенные проверки, attach/detach,
    // докачка, снятие спойлера), чтобы не разъезжались форматы логов между собой.
    private static String mediaDiagSnapshot(BackupImageView img) {
        if (img == null) return "img=null";
        org.telegram.messenger.ImageReceiver ir = img.getImageReceiver();
        if (ir == null) return "imageReceiver=null";
        StringBuilder sb = new StringBuilder();
        sb.append("hasImageSet=").append(ir.hasImageSet())
          .append(" hasBitmapImage=").append(ir.hasBitmapImage())
          .append(" hasImageLoaded=").append(ir.hasImageLoaded())
          .append(" hasNotThumb=").append(ir.hasNotThumb())
          .append(" hasStaticThumb=").append(ir.hasStaticThumb())
          .append(" isAnimationRunning=").append(ir.isAnimationRunning())
          .append(" bitmapWxH=").append(ir.getBitmapWidth()).append("x").append(ir.getBitmapHeight())
          .append(" viewWxH=").append(img.getWidth()).append("x").append(img.getHeight())
          .append(" currentAlpha=").append(ir.getCurrentAlpha());
        org.telegram.ui.Components.AnimatedFileDrawable anim = ir.getAnimation();
        if (anim != null) {
            sb.append(" | ANIM isRunning=").append(anim.isRunning())
              .append(" hasBitmap=").append(anim.hasBitmap())
              .append(" isLoadingStream=").append(anim.isLoadingStream())
              .append(" durationMs=").append(anim.getDurationMs())
              .append(" renderWxH=").append(anim.getRenderingWidth()).append("x").append(anim.getRenderingHeight());
        } else {
            sb.append(" | ANIM=null(нет объекта анимации — значит ImageReceiver вообще");
            sb.append(" не создал AnimatedFileDrawable для текущего currentImageDrawable)");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ CarouselAdapter

    private class CarouselAdapter extends RecyclerView.Adapter<CarouselAdapter.MediaHolder> {
        private final ArrayList<MessageObject> items = new ArrayList<>();
        private int heightDp = MIN_MEDIA_HEIGHT_DP;
        private final java.util.HashMap<Integer, Long> lastBindStackLogTime = new java.util.HashMap<>(); // ВРЕМЕННОЕ поле для диагностики

        CarouselAdapter() {
            // Фикс "раздвоение кадров" (лог GHOST), часть 2: без stable ID
            // RecyclerView сопоставляет ViewHolder с элементом ТОЛЬКО по текущей
            // позиции в момент бинда. После структурного relayout (например,
            // полного relayout окна — см. стек onLayoutChildren ->
            // ViewRootImpl.performTraversals в логе) RecyclerView не может
            // однозначно определить, что уже существующий (прикреплённый или
            // закэшированный) holder на позиции 0 — это тот же самый пост, что и
            // раньше, и на всякий случай создаёт/биндит НОВЫЙ holder вместо
            // переиспользования старого — отсюда и два holder'а с разными
            // ImageReceiver для одного и того же поста одновременно на экране.
            // getItemId() на основе реального id сообщения даёт RecyclerView
            // точный критерий "это тот же элемент", а не только позицию.
            setHasStableIds(true);
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).getId();
        }

        void setMessages(ArrayList<MessageObject> msgs, int hDp) {
            heightDp = hDp;
            // Фикс "двоение кадров видео/GIF" (см. лог GHOST: "carousel
            // bind+startAnimation" повторялся каждые ~16мс — то есть КАЖДЫЙ кадр,
            // хотя пользователь не листал и не менял пост). Раньше здесь стояло
            // items.clear()+items.addAll()+notifyDataSetChanged() БЕЗУСЛОВНО при
            // каждом вызове setMessages() — а setPost() этой ячейки дёргается
            // родительской лентой заметно чаще, чем реально меняется набор медиа
            // в посте. notifyDataSetChanged() на RecyclerView с непустым (дефолтным)
            // ItemAnimator запускает анимацию "смены содержимого": старый и новый
            // ViewHolder на короткое время СУЩЕСТВУЮТ И РИСУЮТСЯ ОДНОВРЕМЕННО, пока
            // идёт кросс-фейд — а у нас каждый из них независимо декодирует и
            // проигрывает СВОЙ экземпляр видео/GIF. Два независимых декодера одного
            // и того же файла, рисующихся друг поверх друга на разных кадрах — это и
            // есть визуальное "двоение". Пропускаем notifyDataSetChanged(), если
            // набор медиа (id сообщений, в том же порядке) фактически не изменился.
            boolean same = items.size() == msgs.size();
            if (same) {
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i).getId() != msgs.get(i).getId()) {
                        same = false;
                        break;
                    }
                }
            }
            if (same) {
                return;
            }
            items.clear();
            items.addAll(msgs);
            notifyDataSetChanged();
        }

        @Override
        public MediaHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            FrameLayout wrapper = new FrameLayout(parent.getContext());
            RecyclerView.LayoutParams wrapperLp = new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.MATCH_PARENT
            );
            wrapper.setLayoutParams(wrapperLp);

            BackupImageView img = new BackupImageView(parent.getContext());
            img.setRoundRadius(dp(8));
            wrapper.addView(img, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            // Как в оригинальном Telegram (ChatMessageCell) — это ДВЕ разные кнопки,
            // не одна:
            // 1) Большая play-кнопка по центру, с фоновым кругом — всегда видна на
            //    видео независимо от того, скачано оно или нет. Тап по ней (как и по
            //    самому кадру) открывает видео в полноэкранном просмотрщике.
            PlayIndicatorView playIndicator = new PlayIndicatorView(parent.getContext());
            playIndicator.setVisibility(GONE);
            wrapper.addView(playIndicator, LayoutHelper.createFrame(48, 48, Gravity.CENTER));

            // 2) Единая тёмная плашка загрузки — левый верхний угол, как в референсе
            //    настоящего Telegram, который прислал пользователь: стрелка загрузки
            //    слева + справа от неё в две строки длительность ("0:12") и размер
            //    файла ("385,4 KB"), всё внутри одной скруглённой тёмной подложки.
            //    Пока видео не скачано — плашка видна целиком. Во время загрузки —
            //    вместо стрелки крутится кольцо прогресса + крестик отмены. Как только
            //    файл скачан — плашка пропадает целиком (не по частям), остаётся
            //    только play-кнопка по центру. Если файл потом удалили из кэша
            //    (например, через системную очистку) — при следующем показе ячейки
            //    (возврат в ленту, notifyDataSetChanged) плашка появляется снова,
            //    т.к. bind() каждый раз заново проверяет реальное наличие файла на
            //    диске, а не полагается на старое состояние.
            VideoDownloadPlate downloadPlate = new VideoDownloadPlate(parent.getContext());
            downloadPlate.setVisibility(GONE);

            // Оверлей притемнения + кнопки загрузки для фото (аналог downloadPlate,
            // но для фото, а не видео) — во весь размер карточки, поверх фото.
            PhotoDownloadOverlay photoOverlay = new PhotoDownloadOverlay(parent.getContext());
            photoOverlay.setVisibility(GONE);
            wrapper.addView(photoOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            // Спойлер — поверх фото/видео и play-кнопки, НО не поверх плашки
            // загрузки (см. ниже). Раньше здесь стоял комментарий "спойлер скрывает
            // даже сам факт что надо качать" — это было ОШИБОЧНОЕ решение прошлой
            // сессии, никогда не сверенное с оригиналом и напрямую противоречащее
            // жалобе пользователя: "если фото-видео под спойлером, то кнопка
            // загрузки вообще не появляется". В оригинальном Telegram индикатор
            // загрузки/прогресса виден И под спойлером — пользователю нужно знать,
            // что там видео, сколько весит и что оно (не) скачивается, независимо
            // от того, снят спойлер или нет.
            SpoilerOverlay spoilerOverlay = new SpoilerOverlay(parent.getContext());
            spoilerOverlay.setVisibility(GONE);
            wrapper.addView(spoilerOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            // ФИКС "под спойлером не видно кнопки загрузки": downloadPlate теперь
            // добавляется ПОСЛЕДНИМ (после spoilerOverlay) — в Android более поздний
            // addView() рисуется ПОВЕРХ более раннего. Раньше порядок был обратный
            // (downloadPlate добавлялся до spoilerOverlay), поэтому спойлер полностью
            // перекрывал плашку — она технически была VISIBLE и bind() отрабатывал
            // правильно, просто визуально пряталась под непрозрачным блюром/частицами.
            wrapper.addView(downloadPlate, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 6, 6, 0, 0));

            return new MediaHolder(wrapper, img, playIndicator, downloadPlate, photoOverlay, spoilerOverlay);
        }

        @Override
        public void onViewRecycled(MediaHolder holder) {
            super.onViewRecycled(holder);
            // Обязательно отписываемся от DownloadController — иначе при переиспользовании
            // ViewHolder'а (RecyclerView) колбэки о загрузке будут прилетать в "чужую",
            // уже переиспользованную под другое видео ячейку.
            holder.downloadPlate.unbind();
            holder.photoOverlay.unbind();
            holder.spoilerOverlay.unbind();
            holder.lastAutoplayDocumentId = 0;
        }

        @Override
        public void onViewAttachedToWindow(MediaHolder holder) {
            super.onViewAttachedToWindow(holder);
            // ДИАГНОСТИКА: раньше здесь логировались только hasBitmap/isAnimation
            // (куцый формат) — теперь полный mediaDiagSnapshot, потому что именно
            // это место — главный подозреваемый (скролл карусели туда-обратно).
            PotokDebugLog.d("VIDEOPLAY", "carousel ATTACH holder=" + System.identityHashCode(holder)
                + " lastAutoplayDocumentId=" + holder.lastAutoplayDocumentId
                + " " + mediaDiagSnapshot(holder.img));
            // ФИКС "видео зависает/не воспроизводится после скролла туда-обратно"
            // (уже существовал ДО этой сессии, не трогаю поведение — только лог
            // выше добавлен):
            // ImageReceiver освобождает decoded-анимацию при onDetachedFromWindow
            // (экономия памяти), а guard lastAutoplayDocumentId (см. onBindViewHolder,
            // ветка canDecodeFromVideo) намеренно НЕ вызывает повторно setImage+
            // startAnimation() для того же document.id — это верно для обычного
            // повторного bind'а БЕЗ смены видео, но ломается, если RecyclerView
            // просто заново прикрепляет ту же вьюху БЕЗ нового onBindViewHolder
            // (типичный сценарий быстрого скролла туда-обратно): анимация уже
            // потеряна, а guard всё ещё думает, что видео "играет", и новый bind
            // может вообще не случиться. Если видим именно такое рассогласование —
            // сбрасываем guard и форсируем настоящий re-bind этой позиции.
            if (holder.lastAutoplayDocumentId != 0 && holder.img.getImageReceiver().getAnimation() == null) {
                PotokDebugLog.d("VIDEOPLAY", "carousel ATTACH holder=" + System.identityHashCode(holder)
                    + " ОБНАРУЖЕНО РАССОГЛАСОВАНИЕ: lastAutoplayDocumentId != 0, но getAnimation()==null"
                    + " -> сбрасываю guard и форсирую safeNotifyItemChanged (существующий фикс, не новый)");
                holder.lastAutoplayDocumentId = 0;
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    safeNotifyItemChanged(pos);
                }
            }
        }

        @Override
        public void onViewDetachedFromWindow(MediaHolder holder) {
            super.onViewDetachedFromWindow(holder);
            PotokDebugLog.d("VIDEOPLAY", "carousel DETACH holder=" + System.identityHashCode(holder)
                + " lastAutoplayDocumentId=" + holder.lastAutoplayDocumentId
                + " " + mediaDiagSnapshot(holder.img));
        }

        // ФИКС КРАША "IllegalStateException: Cannot call this method while
        // RecyclerView is computing a layout or scrolling": прошлый фикс уже
        // откладывал notifyItemChanged() через carouselView.post(), но этого
        // оказалось недостаточно — если карусель продолжает скроллиться/лейаутиться
        // кадр за кадром (например, во время быстрого fling), то к моменту
        // выполнения ОТЛОЖЕННОГО runnable'а она всё ещё может быть "in layout or
        // scroll" (это подтверждено реальным стектрейсом краша: исключение вылетело
        // ИЗНУТРИ уже отложенного через post() вызова). Это единая точка входа для
        // всех notifyItemChanged() в этом адаптере: перед вызовом проверяем
        // carouselView.isComputingLayout() — если true, переоткладываем себя ещё
        // на кадр вперёд (а не вызываем в любом случае), и вдобавок оборачиваем сам
        // notifyItemChanged() в try-catch как абсолютно последнюю страховку —
        // если состояние всё равно не синхронизировалось по какой-то ещё не
        // учтённой причине, пропускаем этот конкретный ребинд вместо краша всего
        // приложения; следующий естественный bind (скролл/notifyDataSetChanged)
        // всё равно подхватит актуальные данные.
        private void safeNotifyItemChanged(int position) {
            if (carouselView == null) {
                return;
            }
            if (carouselView.isComputingLayout()) {
                carouselView.post(() -> safeNotifyItemChanged(position));
                return;
            }
            try {
                notifyItemChanged(position);
            } catch (IllegalStateException e) {
                PotokDebugLog.d("CRASH", "safeNotifyItemChanged(" + position
                    + ") подавил IllegalStateException (RecyclerView всё ещё в layout/scroll"
                    + " несмотря на проверку isComputingLayout()): " + e);
            }
        }

        @Override
        public void onBindViewHolder(MediaHolder holder, int position) {
            MessageObject mo = items.get(position);
            BackupImageView img = holder.img;
            // См. комментарий у поля MediaHolder.bindGeneration — новое поколение
            // на каждый bind, используется ниже отложенными diag-снэпшотами, чтобы
            // не подписать состояние чужого видео чужим post_id при быстром скролле.
            final long myBindGeneration = ++holder.bindGeneration;

            // ВРЕМЕННАЯ диагностика двоения кадров: ловим реальный стек вызова
            // onBindViewHolder, троттлированно (раз в секунду на пост), чтобы
            // увидеть настоящую причину повторного бинда на каждый кадр — раз
            // дедуп в setMessages() не помог, значит notifyDataSetChanged() оттуда
            // не единственный источник (или не источник вовсе).
            {
                long now = System.currentTimeMillis();
                Long last = lastBindStackLogTime.get(mo.getId());
                if (last == null || now - last > 1000) {
                    lastBindStackLogTime.put(mo.getId(), now);
                    PotokDebugLog.d("GHOST", "onBindViewHolder CALLED post=" + mo.getId() + " pos=" + position + " stack="
                        + android.util.Log.getStackTraceString(new Throwable()).replace("\n", " <- "));
                }
            }

            TLRPC.MessageMedia media = mo.messageOwner != null ? mo.messageOwner.media : null;

            // Спойлер — до ветвления на фото/видео/GIF, применяется одинаково к
            // любому типу медиа (как и в оригинале, флаг лежит в самом media, а
            // не привязан к конкретному типу вложения).
            // callback onRevealed: когда пользователь снимает спойлер тапом,
            // ячейку нужно перебиндить (notifyItemChanged), чтобы currentPhotoFilter/
            // currentPhotoFilterThumb ниже пересчитались уже БЕЗ принудительного
            // "_b2" (см. spoilerActive) — иначе после снятия спойлера частицы уходят,
            // а сам блюр остаётся висеть, потому что img уже загружен с заблюренным
            // фильтром и не перезапрашивается сам по себе.
            holder.spoilerOverlay.bind(mo, () -> {
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    safeNotifyItemChanged(pos);
                }
            });
            // GIF в Telegram технически хранится как тот же немой зацикленный
            // video/mp4-документ (TL_documentAttributeAnimated) — тот же контейнер,
            // что и обычное видео, поэтому дальше по пайплайну (инлайн-превью,
            // докачка, автовоспроизведение из кэша) обрабатывается идентично.
            // Раньше mo.isGif() нигде не проверялся, и такие посты не считались
            // медиа вообще (см. hasMedia-проверки выше) — GIF просто не отображался.
            // ФИКС "видео, отправленное в канал КАК ФАЙЛ, не отображается как видео":
            // mo.isVideo()/isVideoDocument() смотрят ИСКЛЮЧИТЕЛЬНО на наличие атрибута
            // TL_documentAttributeVideo — а когда видео отправляют именно "как файл"
            // (не "как видео"), клиент-отправитель обычно СОЗНАТЕЛЬНО не прикладывает
            // этот атрибут (это и есть разница между "видео" и "файл" на уровне
            // протокола). Документ при этом всё равно honestly видео по содержимому
            // (mime_type начинается с "video/") — просто без специальных метаданных.
            // Раньше такие посты проваливались в ФОТО-ветку ниже: показывали только
            // статичный кадр без плей-кнопки и автовоспроизведения. Добавлена
            // подстраховка по MIME-типу — round-видеосообщения (кружки) отдельно
            // исключены явно, у них своя, отдельная от карусели обработка.
            boolean isVideoAsFile = false;
            if (!mo.isVideo() && !mo.isGif() && media instanceof TLRPC.TL_messageMediaDocument
                    && media.document != null && media.document.mime_type != null
                    && media.document.mime_type.startsWith("video/")) {
                boolean isRoundMessage = false;
                for (TLRPC.DocumentAttribute attr : media.document.attributes) {
                    if (attr instanceof TLRPC.TL_documentAttributeVideo && attr.round_message) {
                        isRoundMessage = true;
                        break;
                    }
                }
                isVideoAsFile = !isRoundMessage;
            }
            boolean isVideo = mo.isVideo() || mo.isGif() || isVideoAsFile;
            // Спойлер + блюр (и для фото, и для видео/GIF): пока спойлер не снят,
            // финальное изображение должно оставаться заблюренным ДАЖЕ ПОСЛЕ полной
            // загрузки в кэш — обычная (не-спойлерная) прогрузка убирает блюр по
            // готовности, а спойлерная — нет, специально. См. использование ниже
            // и в фото-ветке.
            boolean spoilerActive = mo.hasMediaSpoilers() && !mo.isSpoilersRevealed;

            if (isVideo && media instanceof TLRPC.TL_messageMediaDocument
                    && media.document != null) {
                TLRPC.Document document = media.document;

                TLRPC.PhotoSize currentPhotoObject = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, AndroidUtilities.getPhotoSize());
                TLRPC.PhotoSize currentPhotoObjectThumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 40);
                if (currentPhotoObject == currentPhotoObjectThumb) currentPhotoObjectThumb = null;

                // Фикс "разный блюр на разных постах" (часть 3, главная причина): раньше
                // здесь стояло "if (strippedThumb != null) currentPhotoObjectThumb =
                // null" — то есть strippedThumb и currentPhotoObjectThumb считались
                // ВЗАИМОИСКЛЮЧАЮЩИМИ. В оригинале (ChatMessageCell, см. например строку
                // с photoImage.setImage(..., ImageLocation.getForDocument(currentPhotoObjectThumb,
                // documentAttach), currentPhotoFilterThumb, currentPhotoObjectThumbStripped, ...))
                // они передаются ОДНОВРЕМЕННО как РАЗНЫЕ уровни одного и того же
                // setImage-вызова: currentPhotoObjectThumbStripped (=strippedThumb) —
                // мгновенный сырой Drawable, показывается, пока даже маленький сетевой
                // currentPhotoObjectThumb ещё не скачался; currentPhotoObjectThumb с
                // currentPhotoFilterThumb — отдельный сетевой уровень С БЛЮРОМ (в нашем
                // случае — сильным "_b2", см. GroupMedia.java для постов/альбомов).
                // Дополнительно: сам strippedThumb печётся в MessageObject.createStrippedThumb()
                // со СЛАБЫМ фильтром "b" (blurType=1, а не "b2"/blurType=3) — он и
                // задумывался как быстрый слабо-заблюренный мгновенный заполнитель,
                // а не финальная картинка. Раз у нас strippedThumb есть почти всегда
                // (это обычные встроенные байты сообщения), взаимоисключение означало,
                // что почти ВСЕ посты проваливались в этот слабый мгновенный вариант,
                // а правильный сильно-заблюренный сетевой уровень просто никогда не
                // подключался — отсюда и "то сильно, то слабо" в зависимости от
                // случайного наличия/отсутствия strippedThumb у конкретного поста.
                BitmapDrawable strippedThumb = mo.strippedThumb;

                // ФИКС "видео без спойлера стало пустым местом вместо превью":
                // прошлый фикс обнулял currentPhotoObject/currentPhotoObjectThumb,
                // когда это TL_photoStrippedSize (см. ниже) — это правильно устраняло
                // блюр (см. ImageLoader.CacheOutTask, строка ~874: любой объект
                // TL_photoStrippedSize, попавший в ImageLocation, блюрится ЖЁСТКО,
                // фильтр из setImage() не учитывается вообще), НО заодно убирало
                // единственное доступное превью целиком — у большинства видео в
                // document.thumbs ЕДИНСТВЕННЫЙ элемент это как раз TL_photoStrippedSize
                // (сервер ещё не сгенерировал/не прислал полноразмерный сетевой thumb).
                // Решение: те же самые сырые байты декодируем САМИ, напрямую,
                // МИНУЯ ImageLoader.CacheOutTask целиком (так же, как это делает
                // MessageObject.createStrippedThumb() для strippedThumb выше) — но
                // с ПУСТЫМ фильтром вместо жёстко зашитого там "b", поэтому blurBitmap()
                // внутри getStrippedPhotoBitmap() не вызывается (см. ImageLoader.java:
                // "if (filter.contains("b")) { Utilities.blurBitmap(...) }"). Результат —
                // тот же самый маленький (обычно 40-50px) кадр видео, но резкий, без
                // затемнения/блюра. Разрешение низкое, пока не докачается кадр
                // побольше/не начнётся автовоспроизведение — это ожидаемо и лучше,
                // чем пустое место.
                // ФИКС "снятие спойлера топорное": теперь img ВСЕГДА должен быть готов
                // показать резкое содержимое (даже пока спойлер активен) — во время
                // круговой reveal-анимации растущий круг на SpoilerOverlay открывает
                // именно img снизу, и там не должно быть пусто. Раньше это декодировалось
                // только при !spoilerActive.
                BitmapDrawable sharpStrippedThumb = null;
                {
                    try {
                        for (TLRPC.PhotoSize size : document.thumbs) {
                            if (size instanceof TLRPC.TL_photoStrippedSize) {
                                android.graphics.Bitmap sharpBmp = org.telegram.messenger.ImageLoader.getStrippedPhotoBitmap(
                                    ((TLRPC.TL_photoStrippedSize) size).bytes, "");
                                if (sharpBmp != null) {
                                    sharpStrippedThumb = new BitmapDrawable(
                                        org.telegram.messenger.ApplicationLoader.applicationContext.getResources(), sharpBmp);
                                }
                                break;
                            }
                        }
                    } catch (Throwable e) {
                        // декодирование крошечного превью не должно ронять бинд ячейки
                        PotokDebugLog.d("VIDEO_THUMB", "post=" + mo.getId()
                            + " sharpStrippedThumb decode failed: " + e);
                    }
                }

                if (currentPhotoObject != null && (currentPhotoObject.w == 0 || currentPhotoObject.h == 0
                        || currentPhotoObject instanceof TLRPC.TL_photoStrippedSize)) {
                    for (TLRPC.DocumentAttribute attr : document.attributes) {
                        if (attr instanceof TLRPC.TL_documentAttributeVideo) {
                            // ФИКС КРАША: у свежесозданного видео сервер иногда ещё не
                            // прислал реальные attr.w/attr.h (оба 0) — тогда
                            // Math.max(0,0)/50f=0 и деление attr.w/scale=0/0=NaN,
                            // (int)NaN=0. Не даём scale быть 0 — пропускаем присвоение
                            // размеров вовсе, если attr.w/attr.h ещё не пришли, чтобы
                            // ниже сработал безопасный плейсхолдер вместо фильтра "0_0".
                            if (attr.w > 0 && attr.h > 0) {
                                if (currentPhotoObject instanceof TLRPC.TL_photoStrippedSize) {
                                    float scale = Math.max(attr.w, attr.h) / 50.0f;
                                    currentPhotoObject.w = (int) (attr.w / scale);
                                    currentPhotoObject.h = (int) (attr.h / scale);
                                } else {
                                    currentPhotoObject.w = attr.w;
                                    currentPhotoObject.h = attr.h;
                                }
                            }
                            break;
                        }
                    }
                }

                // ГЛАВНЫЙ ФИКС "блюр на видео не уходит": ImageLoader.CacheOutTask.run()
                // (см. ImageLoader.java, строка ~874) содержит ЖЁСТКО ЗАШИТУЮ проверку
                // "if (photoSize instanceof TL_photoStrippedSize) { getStrippedPhotoBitmap(
                // bytes, "b") }" — если объект превью, который мы передаём в setImage(),
                // САМ является TL_photoStrippedSize (протокольная мини-картинка, встроенная
                // прямо в байты сообщения), ImageLoader принудительно блюрит её ВСЕГДА,
                // ПОЛНОСТЬЮ ИГНОРИРУЯ переданный нами currentPhotoFilter/currentPhotoFilterThumb.
                // Блок выше только досчитывал currentPhotoObject.w/h для такого объекта, но
                // НЕ менял его тип — объект как был TL_photoStrippedSize, так им и остаётся,
                // и попадает в этот блюр-путь в обход любых наших фильтров. Это и есть причина,
                // почему предыдущий фикс (уборка "_b2" из фильтра и strippedThumb-заглушки)
                // блюр не убрал до конца: сам currentPhotoObject/currentPhotoObjectThumb
                // оставался TL_photoStrippedSize.
                // Для видео обнуляем такие объекты ВСЕГДА (не только без спойлера) —
                // теперь img всегда должен грузиться резким (см. правку выше: "_b2"
                // больше не дописывается в фильтр при spoilerActive) — блюр рисует
                // отдельным слоем SpoilerOverlay, а не сам img.
                {
                    if (currentPhotoObject instanceof TLRPC.TL_photoStrippedSize) {
                        currentPhotoObject = null;
                    }
                    if (currentPhotoObjectThumb instanceof TLRPC.TL_photoStrippedSize) {
                        currentPhotoObjectThumb = null;
                    }
                }

                int pw = currentPhotoObject != null ? currentPhotoObject.w : AndroidUtilities.displaySize.x;
                int ph = currentPhotoObject != null ? currentPhotoObject.h : AndroidUtilities.displaySize.x;
                // ФИКС КРАША "видео из только что отправленного поста ломает приложение":
                // у свежего видео (только что закинутого в канал) сервер иногда ещё не
                // успевает прислать реальные attr.w/attr.h (документ уже есть, но метаданные
                // ffprobe на стороне сервера ещё не готовы) — тогда выше scale=Math.max(0,0)/50f=0,
                // и currentPhotoObject.w/h = (int)(0/0f) = (int)NaN = 0. С pw=0/ph=0 фильтр
                // получается буквально "0_0" — ImageLoader пытается декодировать/смасштабировать
                // битмап нулевого размера, что на части Android-версий бросает
                // IllegalArgumentException необработанно (краш всего процесса — ровно то,
                // что и было: открыл ленту сразу после публикации видео → вылет). Пока
                // сервер не прислал реальные размеры, используем безопасный плейсхолдер-квадрат
                // вместо 0 — как только метаданные подтянутся (следующий bind/notifyItemChanged),
                // пересчитается корректно.
                if (pw <= 0 || ph <= 0) {
                    PotokDebugLog.d("CRASH", "post=" + mo.getId()
                        + " video zero-size guard fired: attrW/H отсутствуют или равны 0,"
                        + " подставлен safe-плейсхолдер вместо currentPhotoFilter=\"0_0\"");
                    pw = AndroidUtilities.getPhotoSize();
                    ph = AndroidUtilities.getPhotoSize();
                }
                String currentPhotoFilter = pw + "_" + ph;
                // Фикс "разный блюр на разных постах": здесь раньше был суффикс "_b"
                // (слабый блюр, 1 проход) — тот же баг, что уже чинили для фото (см.
                // "50_50_b2" в фото-ветке ниже). У видео/GIF (эта ветка) суффикс остался
                // "_b" по недосмотру — отсюда и "некоторые посты правильно заблюрены,
                // некоторые слегка": фото уже показывали сильный блюр "_b2", а видео/GIF-
                // превью — слабый "_b". Приведено к тому же сильному "_b2", что и у фото.
                // Фикс "разный блюр на разных постах" (часть 2): когда у поста НЕТ
                // встроенного PhotoSize-превью (currentPhotoObjectThumb == null, есть
                // только strippedThumb) — раньше сюда попадал ГОЛЫЙ фильтр "b2" без
                // размеров. ImageLoader.java (см. createImage(): "if (args.length >= 2)
                // {w_filter=...; h_filter=...}") распознаёт размер ТОЛЬКО если в
                // фильтре есть "ширина_высота" ПЕРЕД суффиксом блюра — без них весь
                // блок даунскейла перед блюром (opts.inSampleSize по photoW/photoH)
                // просто пропускается, картинка декодируется почти в полном
                // разрешении, и тот же фиксированный радиус блюра (3 прохода, ~7px)
                // на большой картинке визуально выглядит куда слабее, чем на
                // уменьшенной до 50x50 — отсюда разница "один канал блюрится сильно,
                // другой еле-еле" в зависимости от того, есть ли у поста встроенный
                // PhotoSize. Добавлены те же фиксированные размеры "50_50", что и в
                // видео/GIF-ветке ниже — теперь любой путь даунскейлится перед блюром
                // одинаково, независимо от исходного разрешения.
                // УБРАН блюр у видео/GIF (по просьбе): в настоящем Telegram видео/GIF
                // в каналах НЕ показываются с блюр-плейсхолдером — только у фото. Раньше
                // здесь стоял суффикс "_b2" (сильный блюр), теперь — обычный фильтр без
                // блюра, тех же размеров, что и currentPhotoFilter у самого видео (чтобы
                // thumb не выглядел мельче/крупнее финального кадра при подмене).
                String currentPhotoFilterThumb = currentPhotoObjectThumb != null
                    ? currentPhotoObjectThumb.w + "_" + currentPhotoObjectThumb.h : currentPhotoFilter;

                // СПОЙЛЕР + ВИДЕО/GIF: раньше блюр от спойлера НЕ было видно вообще —
                // единственным слоем, скрывающим контент, были редкие частицы
                // SpoilerEffect2, а сам currentPhotoFilter/currentPhotoFilterThumb
                // (см. выше) всегда были БЕЗ блюра (это правильно для ОБЫЧНОГО
                // видео/GIF без спойлера — блюр у них теперь принципиально не
                // нужен, см. комментарий выше), из-за чего финальный кадр
                // прогружался и показывался чётким ПРЯМО СКВОЗЬ частицы спойлера.
                // Пока спойлер активен (флаг не снят), к обоим фильтрам
                // принудительно добавляется тот же сильный блюр "_b2", что и у фото
                // (см. фото-ветку ниже) — так на экране одновременно два слоя:
                // блюр + частицы, как и должно быть для чувствительного медиа.
                // Как только спойлер снимается тапом (см. SpoilerOverlay.startReveal
                // -> onRevealed callback ниже), ячейка перебиндивается
                // (notifyItemChanged) и spoilerActive становится false — тогда сюда
                // попадает обычный, уже НЕ заблюренный фильтр.
                // ФИКС "снятие спойлера топорное/рывком, не как в самом Telegram":
                // раньше здесь ДОПИСЫВАЛСЯ "_b2" в фильтр загрузки img, если спойлер
                // активен — блюр оказывался ЗАПЕЧЁН В ПИКСЕЛЯХ самого img. Круговой
                // reveal-анимация (см. SpoilerOverlay.startReveal/onDraw, 1:1 портирован
                // из ChatMessageCell.startRevealMedia/drawBlurredPhoto) анимировала ТОЛЬКО
                // частицы поверх — сам блюр под ними менялся одним кадром в САМОМ КОНЦЕ
                // анимации (полный notifyItemChanged), потому что перезапросить img с
                // другим фильтром "на лету", посередине анимации, нельзя. В оригинальном
                // Telegram (ChatMessageCell) фото/видео ВСЕГДА грузится резким, а блюр —
                // ОТДЕЛЬНЫЙ слой (blurredPhotoImage), рисуемый ПОВЕРХ, вырезаемый ТЕМ ЖЕ
                // растущим кругом, что и частицы (drawBlurredPhotoParticles) — то есть
                // блюр и частицы анимируются как ЕДИНОЕ целое. Теперь у нас так же: img
                // всегда грузится обычным (не заблюренным) фильтром — сам блюр рисует
                // SpoilerOverlay ПОВЕРХ (см. его onDraw), той же самой revealPath, что
                // и частицы. currentPhotoFilter/currentPhotoFilterThumb больше НЕ
                // мутируются под спойлер — sharpMainFilter/sharpThumbFilterCaptured
                // оставлены как есть (используются ниже в onRevealed для запуска
                // автовоспроизведения) и теперь всегда равны исходным.
                final String sharpMainFilter = currentPhotoFilter;
                final String sharpThumbFilterCaptured = currentPhotoFilterThumb;

                // ДИАГНОСТИКА (по просьбе): пользователь заметил, что посты из разных
                // каналов блюрятся по-разному (Манчестер Юнайтед — нормально, Реальный
                // Футбол LIVE — слабо), хотя видимые параметры фильтра одинаковые.
                // Добавлено название канала прямо в лог + фильтр, чтобы не собирать
                // вручную сотни строк по всем каналам сразу — пишем только эти два,
                // остальные каналы намеренно пропускаем (return без лога).
                String channelTitleDbg = currentChannel != null ? currentChannel.title : "?";
                // Сравнение по ID, а не по названию строкой — надёжнее (title мог бы не
                // совпасть из-за лишнего пробела/регистра, id совпадает всегда).
                // 1391358048 = Манчестер Юнайтед, 1365921811 = Реальный Футбол LIVE
                // (id взяты из твоих же логов loadFeed).
                boolean isTrackedChannelDbg = currentChannel != null
                    && (currentChannel.id == 1391358048L || currentChannel.id == 1365921811L);
                if (isTrackedChannelDbg) {
                    PotokDebugLog.d("BLUR", "channel=[" + channelTitleDbg + "] post=" + mo.getId()
                        + " strippedThumb=" + (strippedThumb != null)
                        + " photoObjThumb=" + (currentPhotoObjectThumb != null
                            ? (currentPhotoObjectThumb.w + "x" + currentPhotoObjectThumb.h) : "null")
                        + " filterThumb=" + currentPhotoFilterThumb
                        + " isVideo=" + isVideo);
                }
                // ГЛАВНОЕ ИЗМЕНЕНИЕ: видео больше НЕ подгружается/не стримится само по
                // себе при показе поста. canDecodeFromVideo (декодирование реального
                // кадра через стриминг) теперь разрешено ТОЛЬКО если файл уже реально
                // лежит в кэше (пользователь явно нажал кнопку загрузки раньше) — иначе
                // просто статичный превью-thumbnail с сервера, без единого байта самого
                // видео.
                // ВАЖНО: forceCache=false (не true!). getPathToAttach(doc, true) всегда
                // смотрит в общую директорию MEDIA_DIR_CACHE независимо от типа файла —
                // а видео реально сохраняется Telegram-ом в отдельную MEDIA_DIR_VIDEO.
                // С forceCache=true проверка ВСЕГДА возвращала "файла нет", даже для
                // уже скачанных видео (это и был баг "плашка не пропадает после
                // скачивания" — не спасали ни выход из вкладки, ни рестарт приложения,
                // потому что проверялась в принципе не та папка). См. также
                // MessageObject.checkMediaExistance(), где оригинал делает точно так же:
                // forceCache=false для документов, кроме wallpaper.
                java.io.File cacheFile = FileLoader.getInstance(mo.currentAccount).getPathToAttach(document, false);
                boolean fileExists = cacheFile != null && cacheFile.exists();
                // СПОЙЛЕР + АВТОВОСПРОИЗВЕДЕНИЕ: ветка ниже (canDecodeFromVideo)
                // проигрывает РЕАЛЬНЫЙ ролик через AUTOPLAY_FILTER — отдельный
                // путь, который НЕ проходит через currentPhotoFilter/
                // currentPhotoFilterThumb (туда мы принудительно добавляем блюр
                // при активном спойлере, см. ниже) — то есть спойлерное видео/GIF
                // после докачки начало бы само проигрываться в обход блюра и
                // частиц. Пока спойлер активен, принудительно не пускаем в эту
                // ветку — видео просто покажет статичный (заблюренный) кадр под
                // спойлером, как и фото, до тапа.
                // ФИКС "видео/гиф не воспроизводится, стоит с блюром": mo.canStreamVideo()
                // проверяет флаг supports_streaming — он относится к проигрыванию ВО ВРЕМЯ
                // докачки (частичный файл). Мы же уже требуем fileExists==true (файл
                // ПОЛНОСТЬЮ в кэше) — для локального декодирования уже скачанного файла
                // этот флаг не имеет значения и ошибочно блокировал автовоспроизведение
                // для видео/гиф без этого флага (частый случай). Оставлена только проверка
                // на зашифрованные документы (секретные чаты) вместо canStreamVideo().
                // Настройка "Скачивать видео" из меню трёх точек (по умолчанию включена).
                // Если выключена и сам видеофайл ещё не докачан пользователем вручную —
                // НЕ подгружаем даже полноразмерный статичный превью-кадр с сервера сам
                // по себе (это отдельный сетевой запрос за картинкой) — только маленький
                // стрип-thumb, который и так приходит вместе с самим сообщением бесплатно.
                // Перенесено ВЫШЕ canDecodeFromVideo — теперь используется и там же.
                boolean videoAutoload = PotokFeedFragment.isAutoloadVideoEnabled(getContext())
                    && PotokFeedFragment.isSizeOkForVideoAutoload(document.size);
                // ФИКС "блюр на видео без спойлера, ~90% постов" (Блок C, вариант А,
                // согласован с пользователем): раньше canDecodeFromVideo требовал
                // fileExists==true (файл ПОЛНОСТЬЮ в кэше) — до этого момента
                // единственным видимым содержимым был крошечный (~40-50px)
                // sharpStrippedThumb, растянутый на всю ширину поста — визуально
                // неотличимо от блюра просто из-за апскейла. У большинства видео в
                // document.thumbs НЕТ другого источника картинки (только
                // TL_photoStrippedSize, см. комментарии выше), поэтому это состояние
                // было ПОСТОЯННЫМ, а не временным, как в оригинальном Telegram.
                // В оригинале (ChatMessageCell.java, DOCUMENT_ATTACH_TYPE_VIDEO,
                // ~строка 8526-8529) условие входа в декодирование ИМЕННО ТАКОЕ:
                // "(mediaExists || attachPathExists) || canStreamVideo() &&
                // canDownloadMedia(...)" — то есть декодирование запускается И когда
                // файл уже скачан, И когда его можно частично стримить (supports_streaming
                // на документе) при разрешённой автозагрузке — FileLoader/AnimatedFileDrawable
                // сами прогрессивно докачивают и декодируют кадры по мере поступления
                // байт, не дожидаясь 100% файла. Это та же самая инфраструктура (форк
                // полного Telegram, FileLoader не переписан), просто раньше мы её не
                // пускали в этот путь. mo.canStreamVideo() проверяет флаг
                // supports_streaming на document.attributes — если сервер его не
                // выставил (редкие старые/специфичные контейнеры), условие просто не
                // сработает и останется прежнее поведение (статичный маленький кадр
                // до полной докачки).
                boolean canDecodeFromVideo = !mo.isRepostPreview
                    && !(document instanceof TLRPC.TL_documentEncrypted) && !spoilerActive
                    && (fileExists || (mo.canStreamVideo() && videoAutoload));

                // ДИАГНОСТИКА (видео/GIF всё ещё выглядит заблюренным после удаления
                // "_b2" из currentPhotoFilterThumb) — суффикса "_b2" в этой ветке
                // (currentPhotoFilter/currentPhotoFilterThumb) больше нет, код
                // перепроверен построчно. Гипотеза: видимый блюр — это НЕ наш
                // искусственный фильтр, а strippedThumb (всегда слегка заблюрен
                // "b"-фильтром при создании, MessageObject.createStrippedThumb()) —
                // он передаётся ВО ВСЕ ветки setImage ниже как нижний слой, и если
                // currentPhotoObject/currentPhotoObjectThumb по какой-то причине не
                // догружаются, на экране надолго остаётся именно он, растянутый на
                // всю ширину поста (тиньк-картинка растянутая до ~1080px и выглядит
                // блюром/пикселями, даже с одним слабым проходом фильтра).
                // ВАЖНО: раньше лог был обёрнут в "if (isTrackedChannelDbg)" — то же
                // условие, что и для СОВСЕМ ДРУГОЙ задачи (сравнение силы блюра ФОТО
                // между двумя конкретными каналами). Проблема с видео/гиф НИКАК не
                // привязана именно к этим двум каналам, поэтому лог физически не мог
                // появиться, если тестовое видео было из любого другого канала — а
                // это и объясняет, почему лога не было ни разу за 8-9 попыток. Теперь
                // логируем для ЛЮБОГО видео/гиф без привязки к каналу.
                PotokDebugLog.d("BLUR", "VIDEO/GIF post=" + mo.getId()
                    + " channel=[" + channelTitleDbg + "]"
                    + " canDecodeFromVideo=" + canDecodeFromVideo
                    + " fileExists=" + fileExists
                    + " videoAutoload=" + videoAutoload
                    + " canStreamVideo=" + mo.canStreamVideo()
                    + " currentPhotoObject=" + (currentPhotoObject != null
                        ? (currentPhotoObject.w + "x" + currentPhotoObject.h) : "null")
                    + " currentPhotoObjectThumb=" + (currentPhotoObjectThumb != null
                        ? (currentPhotoObjectThumb.w + "x" + currentPhotoObjectThumb.h) : "null")
                    + " strippedThumb=" + (strippedThumb != null)
                    + " branch=" + (canDecodeFromVideo ? "DECODE_VIDEO"
                        : (!videoAutoload && !fileExists) ? "THUMB_ONLY_NO_AUTOLOAD"
                        : (currentPhotoObjectThumb != null || strippedThumb != null) ? "THUMB_PLUS_FULL"
                        : "FULL_ONLY"));
                // ДИАГНОСТИКА (не фикс): что РЕАЛЬНО оказалось на экране через
                // 800мс и 2500мс после bind — независимо от того, какая ветка
                // сработала выше. Решает вопрос "это правда блюр из-за маленького
                // источника, или главный кадр всё-таки загрузился, а размытым
                // выглядит что-то другое (растяжение, альфа, недогруженный слой)".
                {
                    final long diagPostId = mo.getId();
                    final java.lang.ref.WeakReference<BackupImageView> imgRef = new java.lang.ref.WeakReference<>(img);
                    img.postDelayed(() -> {
                        BackupImageView i = imgRef.get();
                        if (i == null) return;
                        if (holder.bindGeneration != myBindGeneration) {
                            PotokDebugLog.d("BLUR", "post=" + diagPostId + " +800ms STALE (holder уже переиспользован под другой пост, снэпшот пропущен)");
                            return;
                        }
                        PotokDebugLog.d("BLUR", "post=" + diagPostId + " +800ms " + mediaDiagSnapshot(i));
                    }, 800);
                    img.postDelayed(() -> {
                        BackupImageView i = imgRef.get();
                        if (i == null) return;
                        if (holder.bindGeneration != myBindGeneration) {
                            PotokDebugLog.d("BLUR", "post=" + diagPostId + " +2500ms STALE (holder уже переиспользован под другой пост, снэпшот пропущен)");
                            return;
                        }
                        PotokDebugLog.d("BLUR", "post=" + diagPostId + " +2500ms " + mediaDiagSnapshot(i));
                    }, 2500);
                }
                // ФИКС "видео продолжает играть после удаления из кэша" (Блок E,
                // сценарий в, согласовано с пользователем): раньше остановка
                // анимации целиком полагалась на побочный эффект setImage() ниже —
                // но в ImageReceiver.setImage() (см. ~строку 679 в
                // ImageReceiver.java, оригинал Telegram) есть ранний return: если
                // новый imageKey (статичный currentPhotoObject) СОВПАДАЕТ с уже
                // закэшированным currentImageKey (а он совпадает — currentPhotoObject
                // один и тот же что во время "видео играет", что после удаления
                // файла), метод выходит ДО того места, где вызывается
                // recycleBitmap(mediaKey, TYPE_MEDIA) — именно этот вызов должен был
                // остановить/освободить AnimatedFileDrawable. То есть смена ветки
                // canDecodeFromVideo: true -> false сама по себе НИЧЕГО не
                // останавливала, анимация продолжала играть на уже раньше
                // декодированных/закэшированных кадрах, хотя plate и play-кнопка уже
                // корректно показывались (они зависят только от fileExists, не от
                // состояния анимации). В оригинальном Telegram
                // (ChatMessageCell.checkVideoPlayback()) на этот побочный эффект
                // никогда не полагаются — там всегда явный photoImage.stopAnimation().
                // Делаем то же самое явно, до входа в ветки ниже.
                if (!canDecodeFromVideo && holder.lastAutoplayDocumentId != 0) {
                    PotokDebugLog.d("VIDEOPLAY", "post=" + mo.getId() + " pos=" + position
                        + " ФИКС zombie-playback: canDecodeFromVideo стало false, но"
                        + " lastAutoplayDocumentId=" + holder.lastAutoplayDocumentId
                        + " (видео уже играло в этом holder'е) -> явный stopAnimation()."
                        + " ДО: " + mediaDiagSnapshot(img));
                    img.getImageReceiver().setAllowStartAnimation(false);
                    img.getImageReceiver().stopAnimation();
                    holder.lastAutoplayDocumentId = 0;
                    PotokDebugLog.d("VIDEOPLAY", "post=" + mo.getId() + " pos=" + position
                        + " ПОСЛЕ явного stopAnimation(): " + mediaDiagSnapshot(img));
                }

                if (canDecodeFromVideo) {
                    // Бесшумное инлайн-автовоспроизведение кэшированного видео — как GIF,
                    // точная копия ветки DOCUMENT_ATTACH_TYPE_VIDEO из оригинального
                    // ChatMessageCell.setMessageObject(): AUTOPLAY_FILTER (не NONLOOP —
                    // видео должно зацикливаться, а не проигрываться один раз и
                    // застревать на первом кадре) + setAllowStartAnimation(true) +
                    // явный startAnimation(). Именно setAllowStartAnimation(false) в
                    // предыдущей версии и было причиной "дёргания" — декодер получал
                    // кадры, но анимации явно запрещалось стартовать, поэтому
                    // ImageReceiver дёргался на новый кадр и тут же откатывался обратно
                    // на статичный.
                    img.getImageReceiver().setAllowDecodeSingleFrame(true);
                    img.getImageReceiver().setAllowStartAnimation(true);
                    // См. комментарий у поля MediaHolder.lastAutoplayDocumentId — не
                    // перезапускаем декодер/анимацию, если тот же самый документ уже
                    // играет в этом holder'е (повторный onBindViewHolder на ту же
                    // позицию без реальной смены видео).
                    if (holder.lastAutoplayDocumentId != document.id) {
                        holder.lastAutoplayDocumentId = document.id;
                        // ДИАГНОСТИКА "задержка 3-4 сек перед стартом видео" (Блок E,
                        // не фикс): coldStreamStart=true — файла ещё НЕТ целиком на
                        // диске, canDecodeFromVideo пустил сюда через
                        // mo.canStreamVideo() && videoAutoload — AnimatedFileDrawable
                        // декодирует кадры ПРЯМО ИЗ ПОТОКА байт по мере докачки.
                        // Гипотеза: сама задержка может быть просто временем сетевой
                        // докачки первого играбельного куска (не бага) — особенно
                        // если одновременно докачивается несколько видео сразу
                        // (карусель/лента может забиндить соседние посты заранее,
                        // см. OUTER_FEED_CELL ATTACH/DETACH лог). Снэпшоты чаще в
                        // первые секунды (было всего 4 точки 0/500/1500/4000мс без
                        // данных о сети) + прогресс докачки/isLoadingFile на каждой
                        // точке — покажет, тратится ли время на сеть (isLoadingFile=
                        // true, progress растёт) или байты уже все на месте, а
                        // декодер/анимация всё равно не стартует (isLoadingFile=
                        // false, но hasBitmap/isAnimationRunning всё ещё false).
                        boolean coldStreamStart = !fileExists;
                        long bindStartRealtime = android.os.SystemClock.elapsedRealtime();
                        String diagFileName = FileLoader.getAttachFileName(document);
                        // НОВАЯ ДИАГНОСТИКА (гипотеза причины задержки 3-4с из лога поста
                        // 34877): fileExists=true подтверждён через getPathToAttach(document,
                        // false), НО AnimatedFileDrawable создаётся с streamFileSize=document.size
                        // (см. setImage ниже, параметр size) — нативный декодер сравнивает
                        // РЕАЛЬНЫЙ размер файла на диске с этим ожидаемым размером; если они не
                        // совпадают (например, document — другой TLRPC.Document-инстанс с тем же
                        // физическим файлом dc_id_id, но другим полем size, из-за merge/repost
                        // логики ленты), декодер считает файл неполным и запускает докачку
                        // "недостающих" байт через тот же FileLoader-стрим, даже если файл на
                        // самом деле уже целиком на диске. Логируем оба числа прямо здесь, ДО
                        // setImage, чтобы точно увидеть, совпадают они или нет.
                        long realOnDiskLength = cacheFile != null ? cacheFile.length() : -1;
                        PotokDebugLog.d("VIDEOPLAY", "post=" + mo.getId() + " pos=" + position
                            + " ДИАГ_РАЗМЕР_ФАЙЛА document.id=" + document.id
                            + " document.dc_id=" + document.dc_id
                            + " document.size(ожидается декодером)=" + document.size
                            + " realOnDiskLength(реально на диске)=" + realOnDiskLength
                            + " SIZE_MISMATCH=" + (realOnDiskLength >= 0 && realOnDiskLength != document.size)
                            + " documentIdentity=" + System.identityHashCode(document));
                        PotokDebugLog.d("GHOST", "carousel bind+startAnimation post=" + mo.getId()
                            + " pos=" + position + " holder=" + System.identityHashCode(holder)
                            + " img=" + System.identityHashCode(img)
                            + " coldStreamStart=" + coldStreamStart);
                        img.getImageReceiver().setImage(
                            ImageLocation.getForDocument(document), org.telegram.messenger.ImageLoader.AUTOPLAY_FILTER,
                            ImageLocation.getForObject(currentPhotoObject, document), currentPhotoFilter,
                            ImageLocation.getForObject(currentPhotoObjectThumb, document), currentPhotoFilterThumb,
                            // Тот же фикс блюра, что и в ветках выше: strippedThumb
                            // только при активном спойлере, иначе null.
                            sharpStrippedThumb, document.size, (String) null, mo, 0
                        );
                        img.getImageReceiver().startAnimation();
                        final long diagPostId2 = mo.getId();
                        final java.lang.ref.WeakReference<BackupImageView> imgRef2 = new java.lang.ref.WeakReference<>(img);
                        int[] delays = {0, 100, 250, 500, 1000, 1500, 2000, 3000, 4000, 6000};
                        for (int d : delays) {
                            img.postDelayed(() -> {
                                BackupImageView i = imgRef2.get();
                                if (i == null) return;
                                if (holder.bindGeneration != myBindGeneration) {
                                    PotokDebugLog.d("VIDEOPLAY", "post=" + diagPostId2 + " +" + d + "ms STALE (holder уже переиспользован под другой пост, снэпшот пропущен)");
                                    return;
                                }
                                long realElapsed = android.os.SystemClock.elapsedRealtime() - bindStartRealtime;
                                boolean isLoadingNow = FileLoader.getInstance(mo.currentAccount).isLoadingFile(diagFileName);
                                Float progressNow = org.telegram.messenger.ImageLoader.getInstance().getFileProgress(diagFileName);
                                PotokDebugLog.d("VIDEOPLAY", "post=" + diagPostId2 + " +" + d + "ms(план)/" + realElapsed + "ms(факт) "
                                    + "coldStreamStart=" + coldStreamStart
                                    + " isLoadingFile=" + isLoadingNow
                                    + " downloadProgress=" + (progressNow != null ? String.format(java.util.Locale.US, "%.0f%%", progressNow * 100) : "null")
                                    + " " + mediaDiagSnapshot(i));
                            }, d);
                        }
                    } else {
                        // РАНЬШЕ ЭТОТ СЛУЧАЙ ВООБЩЕ НЕ ЛОГИРОВАЛСЯ — то есть если
                        // именно guard "тот же document.id" маскировал зависший
                        // кадр (реальная анимация уже мертва, а мы думаем, что
                        // она играет, и поэтому ничего не делаем), мы бы этого
                        // никогда не увидели ни в одном логе. Теперь видно явно.
                        PotokDebugLog.d("VIDEOPLAY", "post=" + mo.getId() + " pos=" + position
                            + " GUARD_SKIP (тот же document.id=" + document.id
                            + ", setImage/startAnimation НЕ вызывались) " + mediaDiagSnapshot(img));
                    }
                } else if (!videoAutoload && !fileExists) {
                    // Автозагрузка выключена — только стрип-thumb/маленькая миниатюра,
                    // без полноразмерного превью. Тап по кадру (openMediaViewer, см. ниже)
                    // всё равно скачает и покажет видео целиком независимо от этой настройки.
                    // ФИКС "блюр на видео без спойлера" (первая часть, уже была): strippedThumb
                    // печётся в MessageObject.createStrippedThumb() с зашитым на уровне пикселей
                    // блюром (фильтр "b" применяется прямо при декодировании байтов,
                    // см. ImageLoader.getStrippedPhotoBitmap) — сам этот битмап ВСЕГДА
                    // визуально заблюрен, независимо от currentPhotoFilterThumb (там
                    // "_b2" добавляется только при spoilerActive). Раньше strippedThumb
                    // передавался сюда безусловно — поэтому видео выглядело заблюренным
                    // даже без спойлера, просто пока не докачалось полноразмерное превью.
                    // Теперь передаём его только если спойлер реально активен.
                    //
                    // ФИКС "блюр на видео без спойлера" (вторая часть, найдено логом
                    // [BLUR] на постах 34842/49418): этот же кусок кода ВООБЩЕ НЕ передавал
                    // currentPhotoObject (нормальный превью-объект, обычно 180x320 или похожий,
                    // сервер присылает его отдельно от видео-файла и от stripped-миниатюры)
                    // в setImage() — второй слот (imageLocation) был жёстко захардкожен как
                    // (ImageLocation) null. То есть реально запрашивался только
                    // currentPhotoObjectThumb (часто сам по себе null, как в этих постах) и
                    // strippedThumb-заглушка снизу. Раз currentPhotoObjectThumb==null и
                    // основной imageLocation тоже null — на экране оставалась только 22x40
                    // заглушка, растянутая на всю карусель — визуально неотличимо от блюра,
                    // хотя по факту это была не блюр-фильтрация, а просто нет запроса на
                    // нормальный превью вообще. Теперь currentPhotoObject передаётся как
                    // основной imageLocation (currentPhotoFilter — тот же фильтр, что и
                    // в ветке автовоспроизведения выше, без лишнего блюр-суффикса).
                    img.getImageReceiver().setImage(
                        (ImageLocation) null, (String) null,
                        currentPhotoObject != null ? ImageLocation.getForObject(currentPhotoObject, document) : null, currentPhotoFilter,
                        currentPhotoObjectThumb != null ? ImageLocation.getForObject(currentPhotoObjectThumb, document) : null, currentPhotoFilterThumb,
                        sharpStrippedThumb, document.size, (String) null, mo, 0
                    );
                } else if (currentPhotoObjectThumb != null || strippedThumb != null) {
                    // ФИКС "блюр на видео до сих пор не уходит": эта ветка — САМАЯ
                    // ЧАСТАЯ для обычного просмотра ленты (автозагрузка видео включена,
                    // файл ещё не докачан). Раньше здесь использовалась перегрузка
                    // setImage() БЕЗ слота под Drawable-заглушку вообще (10-param:
                    // mediaLocation/mediaFilter/imageLocation/imageFilter/thumbLocation/
                    // thumbFilter/ext/size/cacheType/parentObject) — то есть фикс
                    // sharpStrippedThumb/strippedThumb, добавленный в СОСЕДНИЕ ветки
                    // (videoAutoload==false и fallback-else), сюда вообще не попадал:
                    // эта ветка молча продолжала показывать то, что получалось из
                    // currentPhotoObject/currentPhotoObjectThumb "как есть" — включая
                    // случаи, когда один из них ещё TL_photoStrippedSize с остаточным
                    // блюром по другим путям рендера, либо просто оставалась пустой/
                    // низкодетализированной без нижнего слоя-заглушки вовсе. Переходим
                    // на прямой ImageReceiver.setImage() (тот же 11-параметрический
                    // вариант, что и в ветке автовоспроизведения выше) — он поддерживает
                    // ОДНОВРЕМЕННО mediaLocation+imageLocation (как раньше) И
                    // Drawable-заглушку нижним слоем, куда теперь так же подставляется
                    // sharpStrippedThumb (резкий, без блюра) либо strippedThumb
                    // (заблюренный, только если реально активен спойлер).
                    img.getImageReceiver().setImage(
                        ImageLocation.getForObject(currentPhotoObject, document), currentPhotoFilter,
                        ImageLocation.getForObject(currentPhotoObjectThumb, document), currentPhotoFilterThumb,
                        (ImageLocation) null, (String) null,
                        sharpStrippedThumb,
                        0, (String) null, mo, 0
                    );
                } else {
                    // 9-param: imageLocation, imageFilter, thumbLocation, thumbFilter, thumb(Drawable), ext, size, cacheType, parentObject
                    // Тот же фикс, что и в ветке выше: strippedThumb передаём только
                    // если активен спойлер, иначе null — без встроенного блюра.
                    img.setImage(
                        ImageLocation.getForObject(currentPhotoObject, document), currentPhotoFilter,
                        (ImageLocation) null, (String) null,
                        sharpStrippedThumb, (String) null, 0, 0, mo
                    );
                }

                // Пока видео уже в кэше и проигрывается инлайн бесшумно (как GIF) —
                // ни play-кнопка, ни плашка загрузки не нужны, ролик и так виден и
                // воспроизводится. Они возвращаются, только если файл больше не в
                // кэше (canDecodeFromVideo пересчитывается в bind() заново на каждый
                // показ ячейки — в т.ч. после удаления из кэша через меню поста).
                holder.playIndicator.setVisibility(canDecodeFromVideo ? GONE : VISIBLE);
                holder.photoOverlay.setVisibility(GONE);
                holder.photoOverlay.unbind();
                // Плашка сама решает, показываться ли ей (видно только пока файл не
                // скачан/качается) — bind() пересчитывает fileExists и duration/size
                // текст заново на каждый вызов.
                final int bindPosition = position;
                final long downloadDiagDocId = document.id;
                final long downloadDiagPostId = mo.getId();
                holder.downloadPlate.bind(document, mo.currentAccount, () -> {
                    // Файл докачался — перепривязываем ячейку: canDecodeFromVideo теперь
                    // увидит fileExists=true и покажет уже настоящий декодированный кадр.
                    // КРИТИЧНО: этот колбэк прилетает из DownloadController в произвольный
                    // момент — в том числе прямо во время скролла/layout-прохода самой
                    // карусели (пользователь свайпает, пока видео докачивается). Раньше
                    // здесь был "carouselView.post(() -> notifyItemChanged(...))" — этого
                    // оказалось недостаточно (реальный краш подтвердил: исключение вылетело
                    // ИЗНУТРИ уже отложенного через post() вызова, когда карусель всё ещё
                    // была "in layout or scroll" на момент выполнения). Теперь используем
                    // safeNotifyItemChanged(), которая сама проверяет isComputingLayout()
                    // и при необходимости переоткладывает себя ещё раз.
                    // ДИАГНОСТИКА (не фикс): раньше здесь не было НИ ОДНОЙ строки лога —
                    // если именно этот путь (докачка кнопкой) и есть источник Блока E
                    // (зависший кадр после 100% докачки), мы бы этого никогда не увидели.
                    // bindPosition захвачен в момент bind() — если к моменту колбэка
                    // getAdapterPosition() у holder'а разошёлся с bindPosition, это тоже
                    // будет видно и станет отдельной подсказкой.
                    int liveAdapterPos = holder.getAdapterPosition();
                    boolean staleDownloadCallback = holder.bindGeneration != myBindGeneration;
                    PotokDebugLog.d("VIDEOPLAY", "post=" + downloadDiagPostId + " docId=" + downloadDiagDocId
                        + " ЗАВЕРШЕНА ДОКАЧКА callback, bindPosition=" + bindPosition
                        + " liveAdapterPosition=" + liveAdapterPos
                        + (staleDownloadCallback ? " STALE_CALLBACK (holder уже перебинден на другой пост/поколение, следующие данные могут относиться к ДРУГОМУ документу)" : "")
                        + " lastAutoplayDocumentId(до safeNotifyItemChanged)=" + holder.lastAutoplayDocumentId
                        + " " + mediaDiagSnapshot(holder.img));
                    safeNotifyItemChanged(bindPosition);
                    // Ещё один снэпшот через 1000мс ПОСЛЕ safeNotifyItemChanged — если
                    // rebind прошёл, тут должно быть видно новое состояние; если нет —
                    // значит либо rebind не случился, либо случился, но декодер всё
                    // равно не ожил.
                    final java.lang.ref.WeakReference<BackupImageView> imgRef3 = new java.lang.ref.WeakReference<>(holder.img);
                    holder.img.postDelayed(() -> {
                        BackupImageView i = imgRef3.get();
                        if (i == null) return;
                        if (holder.bindGeneration != myBindGeneration) {
                            PotokDebugLog.d("VIDEOPLAY", "post=" + downloadDiagPostId + " docId=" + downloadDiagDocId
                                + " +1000ms STALE (holder уже переиспользован под другой пост, снэпшот пропущен)");
                            return;
                        }
                        PotokDebugLog.d("VIDEOPLAY", "post=" + downloadDiagPostId + " docId=" + downloadDiagDocId
                            + " +1000ms ПОСЛЕ докачки/rebind " + mediaDiagSnapshot(i));
                    }, 1000);
                });

                // СПОЙЛЕР + ВИДЕО/GIF, снятие: раньше единственным способом "ожить"
                // после тапа-снятия был notifyItemChanged(holder.getAdapterPosition())
                // из общего callback'а bind() (см. начало onBindViewHolder) —
                // адаптерная позиция в момент тапа теоретически может быть
                // невалидна (RecyclerView.NO_POSITION) в каких-то граничных
                // случаях, и тогда перебиндивания просто не происходило — кадр
                // оставался зависшим статичным/заблюренным навсегда, ровно как
                // сообщал пользователь. Ниже — прямой обработчик: не полагается на
                // позицию в адаптере вообще, все нужные объекты (img, document, mo,
                // currentPhotoObject/Thumb, strippedThumb, holder) уже захвачены из
                // замыкания напрямую. Пересчитывает актуальное состояние (файл мог
                // докачаться за время, пока спойлер был активен) и либо запускает
                // автовоспроизведение, либо показывает чёткий (без "_b2") статичный
                // кадр — на выбор, в зависимости от того, можно ли уже стримить.
                final TLRPC.PhotoSize sharpThumbObj = currentPhotoObjectThumb;
                final TLRPC.PhotoSize sharpPhotoObj = currentPhotoObject;
                final BitmapDrawable sharpStripped = strippedThumb;
                holder.spoilerOverlay.setOnRevealed(() -> {
                    java.io.File freshCacheFile = FileLoader.getInstance(mo.currentAccount).getPathToAttach(document, false);
                    boolean freshFileExists = freshCacheFile != null && freshCacheFile.exists();
                    // ФИКС "воспроизведение стартует через 3-4 секунды после снятия
                    // спойлера" (согласовано с пользователем как часть варианта А,
                    // Блок C): раньше здесь проверялся ТОЛЬКО freshFileExists (файл
                    // полностью в кэше) — если спойлерное видео ещё не было докачано
                    // целиком к моменту тапа, снятие спойлера показывало только
                    // статичный резкий кадр, а настоящее воспроизведение стартовало
                    // ПОЗЖЕ, отдельно, когда downloadPlate.bind()-колбэк (см. ниже)
                    // сообщал о завершении докачки — отсюда и ощутимая задержка.
                    // Теперь используем ТО ЖЕ расширенное условие, что и у
                    // canDecodeFromVideo выше — mo.canStreamVideo() && videoAutoload
                    // запускает прогрессивное стриминг-декодирование сразу, без
                    // ожидания полной докачки, точно как в оригинальном Telegram
                    // (ChatMessageCell: revealingMediaSpoilers пускает в тот же
                    // декодирующий путь ДО завершения анимации снятия).
                    boolean freshCanDecode = !mo.isRepostPreview
                        && !(document instanceof TLRPC.TL_documentEncrypted)
                        && (freshFileExists || (mo.canStreamVideo() && videoAutoload));
                    // ДИАГНОСТИКА (не фикс): раньше здесь лога не было вообще — не видно
                    // было, какое именно решение принято в момент тапа по спойлеру.
                    PotokDebugLog.d("SPOILER_BLUR", "post=" + mo.getId()
                        + " onRevealed freshCanDecode=" + freshCanDecode
                        + " freshFileExists=" + freshFileExists
                        + " canStreamVideo=" + mo.canStreamVideo()
                        + " videoAutoload=" + videoAutoload);
                    if (freshCanDecode) {
                        img.getImageReceiver().setAllowDecodeSingleFrame(true);
                        img.getImageReceiver().setAllowStartAnimation(true);
                        holder.lastAutoplayDocumentId = document.id;
                        img.getImageReceiver().setImage(
                            ImageLocation.getForDocument(document), org.telegram.messenger.ImageLoader.AUTOPLAY_FILTER,
                            ImageLocation.getForObject(sharpPhotoObj, document), sharpMainFilter,
                            ImageLocation.getForObject(sharpThumbObj, document), sharpThumbFilterCaptured,
                            sharpStripped, document.size, (String) null, mo, 0
                        );
                        img.getImageReceiver().startAnimation();
                        holder.playIndicator.setVisibility(GONE);
                        holder.downloadPlate.setVisibility(GONE);
                        {
                            final long diagPostId4 = mo.getId();
                            final java.lang.ref.WeakReference<BackupImageView> imgRef4 = new java.lang.ref.WeakReference<>(img);
                            int[] delays4 = {0, 500, 1500, 4000};
                            for (int d : delays4) {
                                img.postDelayed(() -> {
                                    BackupImageView i = imgRef4.get();
                                    if (i == null) return;
                                    if (holder.bindGeneration != myBindGeneration) {
                                        PotokDebugLog.d("VIDEOPLAY", "post=" + diagPostId4 + " ПОСЛЕ СНЯТИЯ СПОЙЛЕРА +" + d + "ms STALE (holder уже переиспользован под другой пост, снэпшот пропущен)");
                                        return;
                                    }
                                    PotokDebugLog.d("VIDEOPLAY", "post=" + diagPostId4 + " ПОСЛЕ СНЯТИЯ СПОЙЛЕРА +" + d + "ms "
                                        + mediaDiagSnapshot(i));
                                }, d);
                            }
                        }
                    } else {
                        img.setImage(
                            ImageLocation.getForObject(sharpPhotoObj, document), sharpMainFilter,
                            ImageLocation.getForObject(sharpThumbObj, document), sharpThumbFilterCaptured,
                            (ImageLocation) null, (String) null,
                            (String) null, 0, 0, mo
                        );
                    }
                    // Плюс обычный notifyItemChanged как второй, подстраховочный
                    // путь — синхронизирует остальное состояние ячейки (плашки,
                    // индикаторы), если адаптерная позиция всё же валидна.
                    int pos = holder.getAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION && carouselAdapter != null) {
                        safeNotifyItemChanged(pos);
                    }
                });
            } else {
                // ФИКС: раньше здесь не было "} else {" — весь код обработки ФОТО
                // ниже физически находился ВНУТРИ условия "if (isVideo && ...)" и
                // поэтому либо никогда не выполнялся (для настоящих фото-постов —
                // отсюда пустые места вместо фото), либо выполнялся СРАЗУ ПОСЛЕ
                // видео-кода для видео/гиф-постов и перезаписывал тот же img другим
                // setImage()-вызовом, обнуляя только что запущенное
                // автовоспроизведение (отсюда "видео зависает статичным кадром").
                // Теперь это отдельная, полноценная else-ветка "медиа — фото".
                // ФИКС "иконка плей/загрузки на фото-постах": playIndicator и
                // downloadPlate относятся только к видео-ветке выше и никогда не
                // трогаются здесь — при переиспользовании ViewHolder'а RecyclerView'ом
                // (тот же holder раньше показывал видео, теперь показывает фото)
                // они оставались VISIBLE от предыдущего bind'а. unbind() у
                // downloadPlate теперь тоже сам прячет себя (см. VideoDownloadPlate),
                // но дублируем здесь явно — на случай перебиндинга в обход
                // onViewRecycled (например, через notifyItemChanged на тот же holder).
                holder.playIndicator.setVisibility(GONE);
                holder.downloadPlate.unbind();
                ArrayList<TLRPC.PhotoSize> sizes = mo.photoThumbs;
                TLRPC.PhotoSize photoSizeClosest = FileLoader.getClosestPhotoSizeWithSize(sizes, 1280, false, null, true);
                // Раньше здесь было "if (photoSize == null) photoSize = ...;" —
                // переприсваивание делает переменную НЕ effectively final, из-за чего
                // javac не даёт использовать её внутри лямбды (photoOverlay.bind(...)
                // ниже) — "local variables referenced from a lambda expression must be
                // final or effectively final". Заменено на тернарник с одним
                // присваиванием — поведение то же самое, просто без reassignment.
                final TLRPC.PhotoSize photoSize = photoSizeClosest != null
                    ? photoSizeClosest
                    : FileLoader.getClosestPhotoSizeWithSize(sizes, 1280);
                // 1:1 с оригиналом (ChatMessageCell.java:8296-8306): thumbSize (сетевой
                // маленький размер) запрашивается ТОЛЬКО если у сообщения нет
                // strippedThumb (встроенного в сообщение мини-превью, приезжающего без
                // сети вместе с самим сообщением). Если strippedThumb есть — сетевой
                // thumb вообще не тянем, используем только его. Раньше мы всегда тянули
                // thumbSize независимо от наличия strippedThumb — реальное расхождение
                // с оригиналом, найденное сверкой кода.
                TLRPC.PhotoSize thumbSize = mo.strippedThumb == null
                    ? FileLoader.getClosestPhotoSizeWithSize(sizes, 40, false, null, true)
                    : null;
                if (thumbSize == photoSize) thumbSize = null;
                // Filter-таргет: было "50_50" (блюр слишком слабый, картинка перед
                // блюром оставалась ~150-170px), затем "8_8" (после сборки и визуальной
                // проверки на устройстве — блюр стал СЛИШКОМ сильным: картинка перед
                // блюром схлопывалась до ~20-40px, из-за чего 3 прохода радиусом 7
                // стирали вообще любую структуру и оставляли ровное цветовое пятно —
                // в отличие от настоящего Telegram, где сквозь блюр всё ещё угадываются
                // силуэты). "16_16" — промежуточное значение, шаг к балансу между
                // "структура ещё видна" и "фото нечитаемо"; проверить на устройстве и
                // при необходимости подвинуть ещё раз в ту или иную сторону.
                // "16_16" (предыдущая проверка на устройстве, 17.07) — всё ещё СЛИШКОМ
                // сильно: фото превращается в однородное пятно без всякой структуры, в
                // отличие от настоящего Telegram, где сквозь блюр видны силуэты/пятна.
                // "24_24" — следующий шаг в сторону увеличения; если опять слишком
                // сильно/слабо — подвинуть ещё раз.
                String thumbFilter = "24_24_b2";
                // СПОЙЛЕР + ФОТО: обычно главный (финальный, полноразмерный) слой
                // фото грузится и показывается БЕЗ блюра (filter=null, см. вызов
                // img.setImage() ниже) — блюр есть только у thumbFilter, и это
                // ПРАВИЛЬНО для обычных фото (задумано как временный плейсхолдер на
                // время загрузки, не постоянный эффект). Но пока активен спойлер
                // (flag mo.hasMediaSpoilers() && !isSpoilersRevealed), финальный
                // слой ТОЖЕ должен оставаться заблюренным — иначе после полной
                // загрузки в кэш фото становится чётким ПРЯМО СКВОЗЬ редкие частицы
                // спойлера (см. SpoilerOverlay), которые сами по себе ничего не
                // закрывают. Используем тот же сильный "24_24_b2", что и у thumb —
                // после снятия спойлера тапом (SpoilerOverlay.onRevealed callback)
                // ячейка перебиндивается и spoilerActive становится false, тогда
                // здесь снова окажется null (нормальное чёткое фото).
                // ФИКС "снятие спойлера топорное/рывком, не как в самом Telegram":
                // раньше здесь ПРИНУДИТЕЛЬНО подставлялся тот же "24_24_b2" на весь
                // срок активности спойлера — главный слой img оставался заблюренным
                // ЗАПЕЧЁННЫМ В ПИКСЕЛЯХ, круговой reveal анимировал только частицы
                // поверх, а сам блюр снимался одним кадром в конце (полный
                // notifyItemChanged) — рывком. В оригинале (ChatMessageCell.
                // drawBlurredPhoto/startRevealMedia) фото ВСЕГДА грузится резким, а
                // блюр — ОТДЕЛЬНЫЙ слой поверх, вырезаемый ТЕМ ЖЕ растущим кругом,
                // что и частицы. Теперь так же: mainPhotoFilter всегда null (img
                // всегда резкий), спойлерный блюр рисует SpoilerOverlay поверх (см.
                // его onDraw) — той же revealPath, что и частицы.
                String mainPhotoFilter = null;
                ImageLocation thumbLocation = thumbSize != null
                    ? ImageLocation.getForObject(thumbSize, mo.photoThumbsObject)
                    : null;
                Drawable thumbDrawable = mo.strippedThumb;
                String channelTitleDbgPhoto = currentChannel != null ? currentChannel.title : "?";
                boolean isTrackedChannelDbgPhoto = currentChannel != null
                    && (currentChannel.id == 1391358048L || currentChannel.id == 1365921811L);
                boolean photoAutoload = PotokFeedFragment.isAutoloadPhotoEnabled(getContext());
                mo.checkMediaExistance(false);
                if (isTrackedChannelDbgPhoto) {
                    // Полный дамп ВСЕХ доступных вариантов размера фото (не только
                    // выбранных thumbSize/photoSize) — тип, w x h И реальный вес файла
                    // в байтах (PhotoSize.size). Раньше сравнивали только объявленные
                    // w/h, которые оказались одинаковыми у обоих каналов — но при
                    // одинаковом w/h реальный вес (степень сжатия/детализация
                    // исходного JPEG) может отличаться в разы, и именно это не
                    // попадало в лог до сих пор.
                    StringBuilder allSizesDbg = new StringBuilder();
                    if (sizes != null) {
                        for (TLRPC.PhotoSize s : sizes) {
                            if (s == null) continue;
                            allSizesDbg.append("[type=").append(s.type)
                                .append(" ").append(s.w).append("x").append(s.h)
                                .append(" bytes=").append(s.size).append("]");
                        }
                    }
                    PotokDebugLog.d("BLUR", "channel=[" + channelTitleDbgPhoto + "] post=" + mo.getId()
                        + " (photo-branch) thumbSize=" + (thumbSize != null ? (thumbSize.w + "x" + thumbSize.h + " bytes=" + thumbSize.size) : "NULL")
                        + " photoSize=" + (photoSize != null ? (photoSize.w + "x" + photoSize.h + " bytes=" + photoSize.size) : "NULL")
                        + " filterThumb=" + thumbFilter
                        + " strippedThumb=" + (mo.strippedThumb != null
                            ? ("yes intrinsicW=" + (mo.strippedThumb.getIntrinsicWidth()) ) : "no")
                        + " sizesCount=" + (sizes != null ? sizes.size() : -1)
                        + " allSizes=" + allSizesDbg
                        + " photoAutoload=" + photoAutoload
                        + " mediaExists=" + mo.mediaExists);
                }
                if (photoAutoload || mo.mediaExists) {
                    // Автозагрузка включена (или файл и так уже реально в кэше) —
                    // грузим/показываем штатным путём ImageReceiver: сразу выставляем
                    // mo.strippedThumb пятым параметром (тот самый клиентский блюр-
                    // плейсхолдер, приезжающий прямо с сообщением, без сети) — пока
                    // грузится thumbSize/photoSize, видно ЕГО, а не пустоту. Отдельная
                    // кнопка загрузки тут не нужна и не должна показываться — фото и
                    // так грузится само, поэтому оверлей принудительно скрываем/
                    // отвязываем.
                    //
                    // ОТКАТ forcePreview/forceCrossfade: сверка с оригиналом
                    // (ChatMessageCell.java, строка ~8575) показала, что для обычного
                    // фото-сообщения там НЕТ ни setForcePreview, ни setForceCrossfade —
                    // это был самодельный, неправильно понятый костыль (forcePreview в
                    // оригинале используется ТОЛЬКО для TTL/спойлеров и НИКОГДА не
                    // снимается вручную — поэтому фото зависало заблюренным навсегда).
                    // Убрано полностью, вызов приведён к чистому виду как в оригинале.
                    img.setImage(
                        ImageLocation.getForObject(photoSize, mo.photoThumbsObject), mainPhotoFilter,
                        thumbLocation, thumbFilter,
                        thumbDrawable, (String) null, 0, 0, mo
                    );
                    holder.photoOverlay.setVisibility(GONE);
                    holder.photoOverlay.unbind();
                } else {
                    // Автозагрузка выключена и файла ещё нет — грузим ТОЛЬКО маленький
                    // thumb ("50_50", копеечный по размеру, качается независимо от
                    // настройки автозагрузки полного размера — так же, как в самом
                    // Telegram: превью всегда бесплатное, ограничивается только full-size).
                    img.setImage(
                        (ImageLocation) null, (String) null,
                        thumbLocation, thumbFilter,
                        thumbDrawable, (String) null, 0, 0, mo
                    );
                    holder.photoOverlay.bind(photoSize, mo.photoThumbsObject, mo.currentAccount, () -> {
                        // Фикс "переход в чёткое фото — дёргано/медленно": раньше здесь
                        // был notifyItemChanged() -> полный повторный onBindViewHolder()
                        // для всей карусельной ячейки — лишний layout-проход посреди
                        // анимации, из-за которого встроенный кроссфейд ImageReceiver
                        // (тот же самый механизм, что и в ChatMessageCell настоящего
                        // Telegram) не успевал доиграть гладко. В самом Telegram при
                        // завершении докачки фото ничего не пересобирается — тот же
                        // ImageReceiver просто получает то же изображение ещё раз (уже
                        // с файлом в кэше) и доигрывает штатный кроссфейд
                        // (DEFAULT_CROSSFADE_DURATION = 150мс) сам по себе. Делаем 1:1
                        // так же: photoOverlay уже скрыл себя (setVisibility(GONE) в
                        // finishHide()) до вызова этого колбэка — остаётся просто
                        // переставить картинку в тот же img, без notifyItemChanged.
                        img.setImage(
                            ImageLocation.getForObject(photoSize, mo.photoThumbsObject), mainPhotoFilter,
                            thumbLocation, thumbFilter,
                            thumbDrawable, (String) null, 0, 0, mo
                        );
                    });
                }
            }

            final int idx = position;
            img.setOnClickListener(v -> openMediaViewer(mo, idx, items));
            // Тап по play-кнопке в центре — то же самое действие, что и тап по кадру
            // (открыть в полноэкранном просмотрщике), она не занимается загрузкой.
            holder.playIndicator.setOnClickListener(v -> openMediaViewer(mo, idx, items));
            // Фикс: карусель (RecyclerView) сама перехватывает долгое нажатие для своих
            // touch-жестов (скролл/свайп), поэтому долгий тап по фото не долетал до
            // long-click на самой карточке поста. Дублируем обработчик прямо здесь.
            img.setOnLongClickListener(v -> {
                openPostInChannel();
                return true;
            });
        }

        @Override public int getItemCount() { return items.size(); }

        class MediaHolder extends RecyclerView.ViewHolder {
            final BackupImageView img;
            final PlayIndicatorView playIndicator;
            final VideoDownloadPlate downloadPlate;
            final PhotoDownloadOverlay photoOverlay;
            final SpoilerOverlay spoilerOverlay;
            // Фикс "раздвоение кадров" (лог GHOST), часть 3 — САМАЯ ЧАСТАЯ причина по
            // новым логам: RecyclerView может вызывать onBindViewHolder на тот же
            // holder/позицию много раз подряд (каждый кадр во время settle/scroll —
            // само по себе штатное поведение LinearLayoutManager, НЕ обязательно баг).
            // Раньше видео-ветка на КАЖДЫЙ такой вызов безусловно заново дёргала
            // img.getImageReceiver().setImage(...) + startAnimation() — то есть
            // декодер и его анимация ПЕРЕЗАПУСКАЛИСЬ С НУЛЯ на каждый повторный bind,
            // даже если показывать нужно было ТО ЖЕ САМОЕ видео что и секунду назад.
            // Рестарт декодера посреди воспроизведения — и есть видимое раздвоение/
            // дёргание кадра. Запоминаем id уже забинженного автоплей-документа —
            // если при повторном bind() это тот же документ, setImage/startAnimation
            // просто пропускаем, декодер продолжает играть как играл.
            long lastAutoplayDocumentId = 0;
            // Счётчик поколений bind'а — растёт на каждый onBindViewHolder этого
            // holder'а. Нужен ТОЛЬКО чтобы отложенные (postDelayed) диагностические
            // снэпшоты ниже могли проверить "а не переехал ли этот holder за время
            // ожидания на СОВСЕМ ДРУГОЙ пост" (типичный сценарий быстрого скролла) —
            // без этой проверки лог мог бы подписать состояние чужого видео чужим
            // post_id, что сделало бы диагностику недостоверной именно в сценарии
            // "быстрый скролл", который нас и интересует больше всего.
            long bindGeneration = 0;
            MediaHolder(View wrapper, BackupImageView img, PlayIndicatorView playIndicator, VideoDownloadPlate downloadPlate, PhotoDownloadOverlay photoOverlay, SpoilerOverlay spoilerOverlay) {
                super(wrapper);
                this.img = img;
                this.playIndicator = playIndicator;
                this.downloadPlate = downloadPlate;
                this.photoOverlay = photoOverlay;
                this.spoilerOverlay = spoilerOverlay;
            }
        }
    }

    // ------------------------------------------------------------------ VideoDownloadPlate
    /**
     * Единая плашка загрузки видео — как в оригинальном Telegram (референс, который
     * прислал пользователь): тёмная скруглённая подложка, внутри слева иконка
     * загрузки (RadialProgress2, без своего фонового круга — фон рисует сама
     * плашка), справа от неё в две строки длительность видео и размер файла.
     *
     * Состояния:
     * - Файла нет в кэше: плашка целиком видна, иконка — стрелка "скачать".
     * - Идёт загрузка: иконка меняется на кольцо прогресса + крестик отмены,
     *   текст (длительность/размер) остаётся на месте.
     * - Файл скачан: плашка целиком пропадает (setVisibility(GONE)) — не по
     *   частям, как раньше, а вся сразу, остаётся только play-кнопка по центру.
     *
     * Если файл потом удалили из кэша (вручную или системной очисткой), это
     * само по себе не отслеживается никаким колбэком — TDLib/DownloadController
     * не уведомляют о удалении файлов извне. Вместо постоянного опроса диска
     * (лишняя нагрузка на каждый кадр) состояние честно пересчитывается заново
     * при каждом bind() — то есть при любом пересоздании/переприкреплении ячейки:
     * возврат на вкладку ленты (PotokFeedFragment.onResume -> loadFeed ->
     * notifyDataSetChanged), обновление свайпом вниз, скролл с переиспользованием
     * ViewHolder'а. Поэтому если пользователь удалил видео из кэша, уйдя из ленты
     * и вернувшись — плашка появится снова, кнопка загрузки корректно вернётся.
     */
    private static class VideoDownloadPlate extends View implements DownloadController.FileDownloadProgressListener {
        private static final int ICON_AREA = dp(24);
        private static final int PAD_H = dp(8);
        private static final int PAD_V = dp(5);
        private static final int GAP = dp(6);

        private final RadialProgress2 radialProgress;
        private final android.text.TextPaint textPaint;
        private final int TAG;
        private TLRPC.Document document;
        private int currentAccount;
        private String fileName;
        private Runnable onReady;
        private int buttonState; // -1 = скачан/ничего не показываем, 1 = грузится, 2 = скачать
        private String durationText = "";
        // sizeText — статичный размер файла целиком ("5 MB"), показывается пока
        // загрузка не идёт. progressText — "2,5 MB / 5 MB", показывается вместо
        // sizeText только во время реальной закачки (buttonState == 1). Оба текста
        // выводятся во второй строке — они никогда не показываются одновременно.
        private String sizeText = "";
        private String progressText = "";

        // measuredContentWidth — фактическая ширина, которую плашка заявляет системе
        // компоновки (то, что вернёт onMeasure). Растёт МГНОВЕННО, как только новому
        // тексту не хватает места (это безопасно — канвас становится шире, обрезать
        // нечего), а уменьшается только ПОСЛЕ того, как анимация до нового (меньшего)
        // значения полностью доиграет в onDraw — иначе границы View схлопнулись бы
        // раньше, чем анимация закончится, и обрезали бы недорисованный текст.
        // -1 — ещё ни разу не считалось (до первого bind()).
        private int measuredContentWidth = -1;
        private final RectF bgRect = new RectF();
        private final Paint bgPaint;
        // animatedWidth анимирует ТОЛЬКО визуально нарисованную ширину подложки в
        // onDraw (см. там) — реальные границы View (measuredContentWidth) меняются
        // отдельно по правилам выше, а не одномоментно с текстом, как было раньше.
        private final AnimatedFloat animatedWidth;

        VideoDownloadPlate(Context context) {
            super(context);
            radialProgress = new RadialProgress2(this);
            radialProgress.setColorKeys(Theme.key_chat_mediaLoaderPhoto, Theme.key_chat_mediaLoaderPhotoSelected,
                    Theme.key_chat_mediaLoaderPhotoIcon, Theme.key_chat_mediaLoaderPhotoIconSelected);
            // Фон-круг под иконкой не нужен — вся плашка уже тёмная (см. bg ниже),
            // поэтому у самой иконки фона нет, как и раньше у стрелки.
            radialProgress.setDrawBackground(false);
            // ВАЖНО: радиус кольца НЕ равен половине области иконки (ICON_AREA/2 = dp(12)).
            // В оригинале (ChatMessageCell.videoRadialProgress) для точно такой же плашки
            // используется videoRadialProgress.setCircleRadius(dp(15)) при области иконки
            // ровно dp(24) — то есть кольцо специально крупнее своей области и "вылезает"
            // за неё, из-за чего между кольцом и иконкой отмены (крестиком) внутри остаётся
            // нормальный зазор. С радиусом ровно dp(12) (ICON_AREA/2) кольцо плотно
            // заполняло всю область встык — отсюда и слипшийся с крестиком вид, который
            // был замечен. Область тапа/раскладки (ICON_AREA=24) не меняется, меняется
            // только визуальный радиус самого кольца — один в один как в оригинале.
            radialProgress.setCircleRadius(dp(15));

            textPaint = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(0xFFFFFFFF);
            textPaint.setTextSize(dp(11));
            textPaint.setTypeface(AndroidUtilities.bold());

            // Фон рисуем вручную в onDraw (см. там), а не через setBackground —
            // setBackground всегда закрашивает ровно текущие границы View целиком, а
            // подложке нужно уметь анимированно менять ширину независимо от реальных
            // границ (см. measuredContentWidth/animatedWidth выше).
            bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgPaint.setColor(0x99000000);
            animatedWidth = new AnimatedFloat(this, 220, CubicBezierInterpolator.EASE_OUT);

            TAG = DownloadController.getInstance(UserConfig.selectedAccount).generateObserverTag();
            setOnClickListener(v -> onClick());
        }

        void bind(TLRPC.Document doc, int account, Runnable onReadyCallback) {
            unbind();
            document = doc;
            currentAccount = account;
            onReady = onReadyCallback;
            fileName = FileLoader.getAttachFileName(doc);

            long durationSec = 0;
            for (TLRPC.DocumentAttribute attr : doc.attributes) {
                if (attr instanceof TLRPC.TL_documentAttributeVideo) {
                    durationSec = (long) attr.duration;
                    break;
                }
            }
            durationText = durationSec > 0 ? AndroidUtilities.formatShortDuration((int) durationSec) : "";
            sizeText = AndroidUtilities.formatFileSize(doc.size);
            progressText = sizeText;

            // Ячейка переиспользуется RecyclerView'ом под другой пост — ширина здесь
            // не "переход состояния внутри одного поста", а смена контента целиком,
            // поэтому выставляем её МГНОВЕННО (force), без анимации между чужими
            // друг другу значениями.
            measuredContentWidth = computeDesiredWidth();
            animatedWidth.force(measuredContentWidth);

            // ФИКС "видео после скачивания стоит истуканом/не сразу играет":
            // updateState(true-путь) ниже вызывает onReady.run() (=
            // safeNotifyItemChanged) КАЖДЫЙ раз, когда видит fileExists==true —
            // включая ЭТОТ самый первый вызов из bind(), если файл УЖЕ был в кэше
            // на момент обычного (не асинхронного) байнда ячейки. Но в этом случае
            // canDecodeFromVideo в onBindViewHolder УЖЕ корректно обработал
            // воспроизведение в ЭТОМ ЖЕ проходе (см. ветку выше, до вызова этого
            // bind()) — лишний повторный safeNotifyItemChanged() тут же, поверх
            // только что стартовавшей анимации, оказывается избыточным ребиндом,
            // который может сбить/сбросить только что запущенное воспроизведение
            // (отсюда "стоит статичным кадром, хотя уже скачано"). Настоящее
            // асинхронное завершение закачки (onSuccessDownload ниже) — совсем
            // другой случай, там onReady нужен обязательно. Различаем их через
            // allowReadyCallback.
            updateState(false, false);
        }

        // Общая формула ширины подложки под текущий текст (длительность + вторая
        // строка — прогресс или статичный размер). Вызывается и из onMeasure (что
        // фактически заявлено системе компоновки), и из syncWidth() (что реально
        // нужно ПРЯМО СЕЙЧАС по актуальному тексту).
        private int computeDesiredWidth() {
            float w1 = textPaint.measureText(durationText);
            String secondLine = buttonState == 1 ? progressText : sizeText;
            float w2 = textPaint.measureText(secondLine);
            int textWidth = (int) Math.ceil(Math.max(w1, w2));
            // Симметрия: правый паддинг (текст -> край) точно равен левому (край -> иконка),
            // оба PAD_H. Слева иконка тоже центрирована в своей области (см. onSizeChanged).
            return PAD_H + ICON_AREA + GAP + textWidth + PAD_H;
        }

        void unbind() {
            if (fileName != null) {
                DownloadController.getInstance(currentAccount).removeLoadingFileObserver(this);
            }
            document = null;
            fileName = null;
            onReady = null;
            // ФИКС "плей/загрузка на фото-постах": unbind() раньше только отписывался
            // от DownloadController, но НЕ прятал саму плашку — при переиспользовании
            // ViewHolder'а RecyclerView'ом (видео-пост -> фото-пост в том же holder'е)
            // плашка оставалась VISIBLE с текстом/иконкой от предыдущего видео,
            // потому что фото-ветка onBindViewHolder никогда её не трогает вообще
            // (она относится только к видео). Теперь unbind() гарантированно прячет
            // себя — фото-ветка сама явно не обязана об этом знать.
            setVisibility(GONE);
        }

        private void updateState(boolean animated) {
            updateState(animated, true);
        }

        private void updateState(boolean animated, boolean allowReadyCallback) {
            if (document == null || fileName == null) {
                return;
            }
            // forceCache=false — см. подробное объяснение у аналогичной проверки в
            // onBindViewHolder выше. С forceCache=true плашка всегда "видела" файл как
            // отсутствующий (смотрела не в ту папку), поэтому не пропадала после
            // скачивания вообще никогда, даже после полного рестарта приложения.
            java.io.File cacheFile = FileLoader.getInstance(currentAccount).getPathToAttach(document, false);
            boolean fileExists = cacheFile != null && cacheFile.exists();
            boolean isLoading = FileLoader.getInstance(currentAccount).isLoadingFile(fileName);
            if (fileExists) {
                // Файл на месте — плашка (стрелка/прогресс + длительность + размер)
                // пропадает ВСЯ целиком, ничего от неё не остаётся видимым.
                DownloadController.getInstance(currentAccount).removeLoadingFileObserver(this);
                buttonState = -1;
                setVisibility(GONE);
                // allowReadyCallback=false — вызов из bind() (см. фикс "видео после
                // скачивания стоит истуканом" там): если файл УЖЕ был в кэше на
                // момент обычного байнда ячейки, воспроизведение уже корректно
                // запущено в ЭТОМ ЖЕ проходе onBindViewHolder (через
                // canDecodeFromVideo) — лишний onReady.run() здесь был бы
                // избыточным повторным ребиндом поверх только что стартовавшей
                // анимации. onReady нужен только для настоящего асинхронного
                // перехода (onSuccessDownload), где allowReadyCallback=true.
                if (onReady != null && allowReadyCallback) {
                    onReady.run();
                }
            } else {
                // Файла нет (либо ещё не качали, либо его удалили из кэша уже после
                // того, как раньше он был скачан) — плашка снова видна целиком,
                // кнопка загрузки доступна для повторного скачивания.
                setVisibility(VISIBLE);
                DownloadController.getInstance(currentAccount).addLoadingFileObserver(fileName, this);
                if (isLoading) {
                    buttonState = 1;
                    Float progress = org.telegram.messenger.ImageLoader.getInstance().getFileProgress(fileName);
                    radialProgress.setProgress(progress != null ? progress : 0, animated);
                    radialProgress.setIcon(MediaActionDrawable.ICON_CANCEL, false, animated);
                } else {
                    buttonState = 2;
                    // Загрузка не идёт (ещё не начата, отменена или упала с ошибкой) —
                    // вторая строка возвращается к статичному полному размеру файла.
                    progressText = sizeText;
                    radialProgress.setIcon(MediaActionDrawable.ICON_DOWNLOAD, false, animated);
                }
            }
            // Подложка должна быть адаптивной по ширине под текущий текст (как в
            // оригинале), а не зарезервированной заранее под "худший случай". Раньше
            // requestLayout() звался здесь только на переходах состояния, а тики
            // прогресса (onProgressDownload) обновляли текст напрямую, минуя пересчёт
            // ширины — из-за этого при докачке текст ("2,5 MB / 5 MB") становился
            // длиннее подложки, замеренной ещё под короткое "0 B / 5 MB", и обрезался.
            // syncWidth() теперь пересчитывает ширину на КАЖДОМ изменении текста —
            // и здесь, и на каждом тике прогресса (см. onProgressDownload).
            syncWidth();
        }

        private void onClick() {
            if (document == null) return;
            if (buttonState == 2) {
                // Тап по стрелке — запускаем реальную загрузку в кэш, ровно как жмут
                // кнопку загрузки в самом Telegram/Plus Messenger. Выставляем "0 MB / X"
                // сразу, не дожидаясь первого колбэка onProgressDownload — иначе на
                // долю секунды видна старая надпись со статичным полным размером.
                progressText = AndroidUtilities.formatFileSize(0) + " / " + sizeText;
                FileLoader.getInstance(currentAccount).loadFile(document, null, FileLoader.PRIORITY_NORMAL, 0);
                updateState(true);
            } else if (buttonState == 1) {
                // Тап по крестику во время загрузки — отмена.
                FileLoader.getInstance(currentAccount).cancelLoadFile(document);
                updateState(true);
            }
        }

        // Вызывается при любом реальном изменении текста второй строки: и из
        // updateState() (переход состояния), и из onProgressDownload() (каждый тик
        // закачки). Раньше тик прогресса не пересчитывал ширину вообще — отсюда и
        // была обрезка текста.
        private void syncWidth() {
            int desired = computeDesiredWidth();
            if (measuredContentWidth < 0 || desired > measuredContentWidth) {
                // Расширяем МГНОВЕННО — канвас становится шире, обрезать нечего, а
                // визуальный рост подложки всё равно доиграет через animatedWidth в
                // onDraw (см. там).
                measuredContentWidth = desired;
                requestLayout();
            }
            // Если desired МЕНЬШЕ текущего — реальные границы View пока не трогаем:
            // тронуть их сейчас значило бы обрезать canvas раньше, чем анимация
            // сужения доиграет. Это доделает сам onDraw(), когда animatedWidth дойдёт
            // до цели (см. проверку в конце onDraw).
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (measuredContentWidth < 0) {
                measuredContentWidth = computeDesiredWidth();
            }
            int height = PAD_V + ICON_AREA + PAD_V;
            setMeasuredDimension(measuredContentWidth, height);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            int top = (h - ICON_AREA) / 2;
            radialProgress.setProgressRect(PAD_H, top, PAD_H + ICON_AREA, top + ICON_AREA);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            // Целевая ширина берётся из АКТУАЛЬНОГО текста прямо сейчас (а не из
            // measuredContentWidth, который может быть шире — см. syncWidth) — именно
            // к этому значению едет анимация подложки.
            int targetWidth = computeDesiredWidth();
            float drawWidth = animatedWidth.set(targetWidth);
            bgRect.set(0, 0, drawWidth, getHeight());
            canvas.drawRoundRect(bgRect, dp(14), dp(14), bgPaint);

            radialProgress.draw(canvas);
            // Во время закачки вторая строка — живой прогресс, иначе — статичный
            // полный размер файла (см. updateState()/onProgressDownload()).
            String secondLine = buttonState == 1 ? progressText : sizeText;
            if (!TextUtils.isEmpty(durationText) || !TextUtils.isEmpty(secondLine)) {
                float textX = PAD_H + ICON_AREA + GAP;
                float centerY = getHeight() / 2f;
                // Симметрия по вертикали: раньше строки позиционировались через грубое
                // приближение (centerY ± фиксированный dp), из-за чего блок текста
                // визуально "провисал" ниже центра плашки и сидел ближе к нижнему краю,
                // чем к верхнему — это и была замеченная асимметрия. Теперь блок из
                // одной/двух строк целиком центрируется вокруг centerY через реальные
                // метрики шрифта (ascent/descent), точно как центрируется иконка слева
                // (top = (h - ICON_AREA) / 2, см. onSizeChanged) — оба элемента получают
                // одинаковые отступы сверху/снизу от центра плашки.
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                float lineH = fm.descent - fm.ascent;
                float lineGap = dp(2);
                if (!TextUtils.isEmpty(durationText) && !TextUtils.isEmpty(secondLine)) {
                    float blockTop = centerY - (2 * lineH + lineGap) / 2f;
                    float baseline1 = blockTop - fm.ascent;
                    float baseline2 = baseline1 + lineH + lineGap;
                    canvas.drawText(durationText, textX, baseline1, textPaint);
                    canvas.drawText(secondLine, textX, baseline2, textPaint);
                } else {
                    String single = !TextUtils.isEmpty(durationText) ? durationText : secondLine;
                    float baseline = centerY - (fm.ascent + fm.descent) / 2f;
                    canvas.drawText(single, textX, baseline, textPaint);
                }
            }

            // Фикс "двоение кадров видео": раньше это условие проверялось БЕЗ учёта
            // buttonState — а во время активной докачки/стриминга (buttonState==1)
            // progressText меняется почти на каждом тике, targetWidth пересчитывается
            // из него в КАЖДОМ onDraw() и практически никогда точно не совпадает с
            // measuredContentWidth — condition срабатывала (и следом requestLayout())
            // ПОЧТИ НА КАЖДЫЙ КАДР, пока играло/стримилось видео. Это и был реальный
            // источник (см. лог GHOST: onBindViewHolder вызывался из
            // LinearLayoutManager.onLayoutChildren на каждый Choreographer.doFrame —
            // то есть КТО-ТО помимо скролла форсировал requestLayout() всего окна
            // каждый кадр, и это оказалось именно здесь). "Усадочная" анимация подложки
            // после докачки нужна ОДИН РАЗ, когда прогресс уже остановился — поэтому
            // теперь проверяем это только когда buttonState != 1 (докачка не идёт).
            // Пока докачка активна, рост ширины уже покрыт syncWidth() (см. выше) —
            // сужать подложку кадр за кадром во время самой докачки не нужно.
            if (buttonState != 1 && !animatedWidth.isInProgress() && measuredContentWidth != targetWidth) {
                // Анимация сужения подложки доиграла — теперь можно безопасно уменьшить
                // реальные границы View до фактической цели. Делать это раньше (сразу
                // на смене текста) было бы неверно: границы схлопнулись бы ДО того, как
                // анимация закончится, и обрезали бы недорисованный кадр.
                measuredContentWidth = targetWidth;
                post(() -> requestLayout());
            }
        }

        @Override
        public void onFailedDownload(String name, boolean canceled) {
            updateState(true);
        }

        @Override
        public void onSuccessDownload(String name) {
            radialProgress.setProgress(1, true);
            updateState(true);
        }

        @Override
        public void onProgressDownload(String name, long downloadedSize, long totalSize) {
            radialProgress.setProgress(Math.min(1f, downloadedSize / (float) totalSize), true);
            // Пункт 1 из ТЗ: пока файл качается, вторая строка показывает живой
            // прогресс "2,5 MB / 5 MB" вместо статичного "5 MB". Плашка адаптивна по
            // ширине (см. onMeasure()/updateState()), но пересчитывается только на
            // переходах состояния, а не на каждый тик — как и в оригинале, ширина не
            // "гуляет" на каждое обновление процента, достаточно invalidate().
            progressText = AndroidUtilities.formatFileSize(downloadedSize) + " / " + AndroidUtilities.formatFileSize(totalSize);
            if (buttonState != 1) {
                updateState(true);
            } else {
                // Раньше здесь звался только invalidate() — текст обновлялся, а
                // ширина подложки нет, отсюда и была обрезка на каждом тике закачки.
                // syncWidth() пересчитывает ширину на каждом тике; сама перерисовка
                // при этом не "спамит" requestLayout 20 раз в секунду — расширение
                // происходит мгновенно только когда реально нужно больше места, а
                // визуальная анимация — забота animatedWidth в onDraw.
                syncWidth();
            }
        }

        @Override
        public void onProgressUpload(String name, long uploadedSize, long totalSize, boolean isEncrypted) {
        }

        @Override
        public int getObserverTag() {
            return TAG;
        }
    }

    // ------------------------------------------------------------------ PhotoDownloadOverlay
    /**
     * Аналог VideoDownloadPlate, но для фото — один в один по поведению с
     * оригинальным Telegram: пока полноразмерное фото не в кэше, вся область
     * притемняется (как photoImage.setAlpha(.5f) + полупрозрачная тёмная
     * подложка поверх в ChatMessageCell.drawPhotoBlurRect), а по центру — круглая
     * кнопка загрузки (RadialProgress2 с фоном, как в оригинале — не как у
     * видео-плашки, где фон отключён, потому что там своя тёмная "таблетка").
     * Тап — скачивает полноразмерное фото; во время загрузки иконка меняется на
     * крестик отмены с кольцом прогресса. Как только файл на диске — оверлей
     * пропадает целиком, показывается уже чёткое фото. Состояние, как и у
     * VideoDownloadPlate, пересчитывается заново на каждый bind() — если фото
     * удалили из кэша, при следующем показе ячейки оверлей вернётся сам.
     */
    private static class PhotoDownloadOverlay extends View implements DownloadController.FileDownloadProgressListener {
        private final RadialProgress2 radialProgress;
        private final int TAG;
        private TLRPC.PhotoSize photoSize;
        private TLObject parentObject;
        private int currentAccount;
        private String fileName;
        private Runnable onReady;
        private int buttonState; // -1 = скачано/скрыто, 1 = грузится, 2 = скачать
        // Фикс "спиннер загрузки не виден на маленьких/быстрых файлах": маленькие
        // фото-превью качаются иногда за один кадр, и onSuccessDownload() приходил
        // раньше, чем кольцо загрузки успевало хоть раз отрисоваться — кнопка
        // просто исчезала, будто ничего не произошло. Гарантируем, что с момента
        // тапа кольцо провисит на экране минимум MIN_LOADING_VISIBLE_MS, даже если
        // реальная докачка уже закончилась раньше.
        private static final long MIN_LOADING_VISIBLE_MS = 400;
        private long loadingStartedAt;
        private Runnable pendingHideRunnable;

        PhotoDownloadOverlay(Context context) {
            super(context);
            radialProgress = new RadialProgress2(this);
            // Круг под иконкой рисует сам RadialProgress2 через заданные ColorKeys —
            // отдельного фона/затемнения поверх всего фото здесь НЕ нужно: в оригинале
            // "тусклый" вид недокачанного фото — это сам блюр-плейсхолдер
            // (strippedThumb, см. CarouselAdapter), а не дополнительный полупрозрачный
            // прямоугольник поверх. Раньше здесь рисовался ещё и dimPaint 40% чёрным
            // поверх ВСЕГО фото — из-за этого поверх уже автозагруженного чёткого фото
            // (когда оверлей ошибочно оставался видимым) получался странный "чуть
            // тусклый" эффект, о котором и был отзыв.
            radialProgress.setColorKeys(Theme.key_chat_mediaLoaderPhoto, Theme.key_chat_mediaLoaderPhotoSelected,
                    Theme.key_chat_mediaLoaderPhotoIcon, Theme.key_chat_mediaLoaderPhotoIconSelected);
            radialProgress.setCircleRadius(dp(20));

            TAG = DownloadController.getInstance(UserConfig.selectedAccount).generateObserverTag();
            setOnClickListener(v -> onClick());
        }

        void bind(TLRPC.PhotoSize size, TLObject parent, int account, Runnable onReadyCallback) {
            unbind();
            photoSize = size;
            parentObject = parent;
            currentAccount = account;
            onReady = onReadyCallback;
            fileName = FileLoader.getAttachFileName(size);
            updateState(false);
        }

        void unbind() {
            if (fileName != null) {
                DownloadController.getInstance(currentAccount).removeLoadingFileObserver(this);
            }
            if (pendingHideRunnable != null) {
                removeCallbacks(pendingHideRunnable);
                pendingHideRunnable = null;
            }
            loadingStartedAt = 0;
            photoSize = null;
            parentObject = null;
            fileName = null;
            onReady = null;
        }

        private void finishHide() {
            buttonState = -1;
            setVisibility(GONE);
            if (onReady != null) {
                onReady.run();
            }
        }

        private void updateState(boolean animated) {
            if (photoSize == null || fileName == null) {
                return;
            }
            // forceCache=false — та же причина, что и у VideoDownloadPlate.updateState():
            // с forceCache=true проверка всегда смотрит не в ту папку и никогда не видит
            // уже скачанный файл.
            java.io.File cacheFile = FileLoader.getInstance(currentAccount).getPathToAttach(photoSize, false);
            boolean fileExists = cacheFile != null && cacheFile.exists();
            boolean isLoading = FileLoader.getInstance(currentAccount).isLoadingFile(fileName);
            if (fileExists) {
                DownloadController.getInstance(currentAccount).removeLoadingFileObserver(this);
                // Фикс "спиннер не виден на быстрых докачках": если кольцо загрузки
                // было показано меньше MIN_LOADING_VISIBLE_MS назад, не прячем кнопку
                // мгновенно — докручиваем кольцо до 100% и прячем чуть позже, по
                // таймеру, а не в момент реального завершения файла.
                long elapsed = loadingStartedAt == 0 ? MIN_LOADING_VISIBLE_MS : System.currentTimeMillis() - loadingStartedAt;
                if (buttonState == 1 && elapsed < MIN_LOADING_VISIBLE_MS && pendingHideRunnable == null) {
                    radialProgress.setProgress(1, true);
                    pendingHideRunnable = () -> {
                        pendingHideRunnable = null;
                        finishHide();
                    };
                    postDelayed(pendingHideRunnable, MIN_LOADING_VISIBLE_MS - elapsed);
                } else if (pendingHideRunnable == null) {
                    finishHide();
                }
            } else {
                setVisibility(VISIBLE);
                DownloadController.getInstance(currentAccount).addLoadingFileObserver(fileName, this);
                if (isLoading) {
                    if (buttonState != 1) {
                        loadingStartedAt = System.currentTimeMillis();
                    }
                    buttonState = 1;
                    Float progress = org.telegram.messenger.ImageLoader.getInstance().getFileProgress(fileName);
                    radialProgress.setProgress(progress != null ? progress : 0, animated);
                    radialProgress.setIcon(MediaActionDrawable.ICON_CANCEL, false, animated);
                } else {
                    buttonState = 2;
                    radialProgress.setIcon(MediaActionDrawable.ICON_DOWNLOAD, false, animated);
                }
            }
            invalidate();
        }

        private void onClick() {
            if (photoSize == null) return;
            if (buttonState == 2) {
                // Фикс "спиннер не появляется вообще, кнопка сразу пропадает": раньше
                // buttonState=1 выставлялся ПОСЛЕ вызова loadFile(). Если FileLoader
                // считает файл уже полностью в кэше (или докачка завершается почти
                // мгновенно) и вызывает onSuccessDownload СИНХРОННО, внутри самого
                // loadFile() — на тот момент buttonState всё ещё был 2 ("скачать"), и
                // наша защита от мгновенного скрытия в updateState()
                // (if buttonState == 1 && elapsed < MIN_LOADING_VISIBLE_MS) не
                // срабатывала: код проваливался в else-ветку и прятал кнопку
                // немедленно, ещё до единого отрисованного кадра кольца. Выставляем
                // buttonState=1 и стартуем таймер ДО вызова loadFile() — тогда даже
                // синхронный колбэк застаёт buttonState уже равным 1.
                buttonState = 1;
                loadingStartedAt = System.currentTimeMillis();
                radialProgress.setProgress(0, false);
                radialProgress.setIcon(MediaActionDrawable.ICON_CANCEL, false, true);
                invalidate();
                FileLoader.getInstance(currentAccount).loadFile(
                    ImageLocation.getForObject(photoSize, parentObject), parentObject, null,
                    FileLoader.PRIORITY_NORMAL, 0
                );
                // Не полагаемся на isLoadingFile() сразу после loadFile() — FileLoader
                // может поставить файл в очередь асинхронно, и в момент проверки
                // формально ещё не считается "грузящимся" (та же гонка, что уже чинили
                // в VideoDownloadPlate.onClick() ранее) — из-за неё кнопка визуально
                // не реагировала на тап ("как будто нажимаю на кирпич"), хотя загрузка
                // реально стартовала. buttonState=1 выставлен заранее (см. выше) —
                // дальнейшие реальные апдейты придут через onProgressDownload/
                // onSuccessDownload и просто подтвердят то же самое состояние.
            } else if (buttonState == 1) {
                FileLoader.getInstance(currentAccount).cancelLoadFile(photoSize);
                updateState(true);
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            int r = dp(24);
            int cx = w / 2, cy = h / 2;
            radialProgress.setProgressRect(cx - r, cy - r, cx + r, cy + r);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (buttonState == -1) {
                return;
            }
            radialProgress.draw(canvas);
        }

        @Override
        public void onFailedDownload(String name, boolean canceled) {
            updateState(true);
        }

        @Override
        public void onSuccessDownload(String name) {
            radialProgress.setProgress(1, true);
            updateState(true);
        }

        @Override
        public void onProgressDownload(String name, long downloadedSize, long totalSize) {
            radialProgress.setProgress(Math.min(1f, downloadedSize / (float) totalSize), true);
            if (buttonState != 1) {
                updateState(true);
            } else {
                invalidate();
            }
        }

        @Override
        public void onProgressUpload(String name, long uploadedSize, long totalSize, boolean isEncrypted) {
        }

        @Override
        public int getObserverTag() {
            return TAG;
        }
    }

    // ------------------------------------------------------------------ SpoilerOverlay
    /**
     * Спойлер для чувствительного медиа — перенесён 1:1 из механизма оригинального
     * Telegram (ChatMessageCell.java: mediaSpoilerEffect2 + isSpoilerRevealing;
     * SharedPhotoVideoCell2.java: startRevealMedia/canRevealSpoiler — та же анимация
     * кругового раскрытия, но в самодостаточной, проще переносимой форме, т.к.
     * SharedPhotoVideoCell2 сама рисует картинку, а у нас картинку рисует отдельный
     * img (BackupImageView) НИЖЕ этого оверлея в том же wrapper).
     *
     * Логика: если у сообщения выставлен флаг спойлера (mo.hasMediaSpoilers() —
     * то же самое условие, что и в оригинале, ничего своего не придумано), этот
     * View добавляется ПОВЕРХ img (последним в wrapper, см. onCreateViewHolder) и
     * рисует анимированные частицы SpoilerEffect2 на весь кадр — полностью
     * закрывая фото/видео под ним. Сам img продолжает грузить и показывать
     * реальное фото/видео как обычно (в т.ч. свой блюр-плейсхолдер) — оверлей
     * просто рисуется НАД ним, пока не снят.
     *
     * По тапу (onTouchEvent, ACTION_UP) запускается круговая анимация раскрытия
     * от точки тапа (как в оригинале): растущий круг вырезается из области
     * отрисовки частиц через canvas.clipPath(..., Region.Op.DIFFERENCE) — внутри
     * круга оверлей ничего не рисует, и там становится виден реальный img под
     * ним. По завершении анимации оверлей скрывается целиком (GONE), после чего
     * повторный тап уже обычным образом открывает медиа в полноэкранном
     * просмотрщике (клик на img, как обычно).
     *
     * mo.isSpoilersRevealed — то же самое поле MessageObject, что использует
     * оригинал (ChatMessageCell), поэтому раз раскрытое состояние не сбрасывается
     * при пересборке/повторном заходе в ленту в рамках одной сессии приложения —
     * так же, как в настоящем Telegram.
     */
    private static class SpoilerOverlay extends View {
        private SpoilerEffect2 effect;
        private MessageObject boundMessage;
        private Runnable onRevealed;
        private boolean attachedToWindow;
        private final Path revealPath = new Path();
        private float revealX, revealY, revealMaxRadius, revealProgress;
        // ФИКС "снятие спойлера топорное": блюр теперь рисуется этим оверлеем
        // (см. onDraw), а не запечён в пикселях img — нужны свои Paint/RectF
        // для растяжения маленького заблюренного strippedThumb на весь размер
        // вьюхи (BitmapDrawable-исходник обычно ~40-50px).
        private final RectF blurDstRect = new RectF();
        private final Paint blurPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        // ФИКС "сквозь спойлер видны детали" (Блок D, найдено по коду оригинала):
        // раньше здесь рисовался НАПРЯМУЮ boundMessage.strippedThumb.getBitmap() —
        // это тот же СЛАБЫЙ 1-проходный блюр (filter "b", blurType=1), которым
        // MessageObject.createStrippedThumb() создаёт мгновенный заполнитель. Он
        // задумывался как быстрый placeholder, а не как маскировка спойлера, и
        // сквозь него видны силуэты/цвета/детали — ровно то, что видно на скрине
        // пользователя. В оригинале (ChatMessageCell.drawBlurredPhoto/строка ~8588)
        // блюр под спойлером — это Utilities.stackBlurBitmapMax(), сильный
        // многопроходный stack-blur. Здесь применяем его к тому же источнику
        // (strippedThumb), но ОДИН РАЗ при бинде (не на каждый onDraw — stack-blur
        // не бесплатный, а onDraw дёргается по 60 раз/сек во время анимации частиц)
        // и кэшируем результат. stackBlurBitmapMax сам уменьшает картинку до ~20dp
        // и блюрит её радиусом >=10px — на таком крошечном холсте это полностью
        // уничтожает любую узнаваемую структуру (силуэты/контуры), остаются только
        // усреднённые цветовые пятна — то, что и требуется для спойлера.
        private Bitmap cachedBlurredBitmap;
        private MessageObject cachedBlurredSource;

        SpoilerOverlay(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        void bind(MessageObject mo, Runnable onRevealedCallback) {
            boundMessage = mo;
            onRevealed = onRevealedCallback;
            revealProgress = (mo != null && mo.isSpoilersRevealed) ? 1f : 0f;
            updateBlurredBitmap();
            updateEffect();
            updateVisibility();
        }

        private void updateBlurredBitmap() {
            if (boundMessage == null) {
                cachedBlurredBitmap = null;
                cachedBlurredSource = null;
                return;
            }
            if (cachedBlurredSource == boundMessage && cachedBlurredBitmap != null) {
                return;
            }
            try {
                Bitmap sourceBitmap = null;
                // Быстрый путь: если MessageObject.strippedThumb уже создан (бывает
                // не всегда, см. ниже) — используем его как есть.
                if (boundMessage.strippedThumb != null) {
                    sourceBitmap = boundMessage.strippedThumb.getBitmap();
                }
                // ФИКС (найдено по логу [BLUR]: strippedThumb=false АБСОЛЮТНО на
                // всех постах, и фото, и видео, без исключений): mo.strippedThumb
                // заполняется MessageObject.createStrippedThumb(), а тот метод
                // целиком пропускает свою работу, если
                // SharedConfig.getDevicePerformanceClass() != PERFORMANCE_CLASS_HIGH
                // (см. MessageObject.canCreateStripedThubms()) — то есть на
                // среднем/слабом устройстве (как у пользователя при тесте)
                // mo.strippedThumb ВСЕГДА null, независимо от спойлера или блюра.
                // Раньше SpoilerOverlay полагался только на это поле — отсюда и
                // "блюр не появился вообще", частицы летали поверх голого кадра.
                // Фикс: если strippedThumb не создан, декодируем те же сырые байты
                // TL_photoStrippedSize САМИ, напрямую из photoThumbs — в обход
                // canCreateStripedThubms() целиком. Это тот же самый приём, что уже
                // применён для sharpStrippedThumb чуть выше в setImage-логике видео
                // (см. комментарий "ФИКС ImageLoader.CacheOutTask ЖЁСТКО блюрит..."),
                // с пустым фильтром "" — сырое, без запечённого блюра, потому что
                // блюрим сами через stackBlurBitmapMax() ниже.
                if (sourceBitmap == null && boundMessage.photoThumbs != null) {
                    for (int i = 0; i < boundMessage.photoThumbs.size(); i++) {
                        TLRPC.PhotoSize size = boundMessage.photoThumbs.get(i);
                        if (size instanceof TLRPC.TL_photoStrippedSize) {
                            sourceBitmap = org.telegram.messenger.ImageLoader.getStrippedPhotoBitmap(
                                ((TLRPC.TL_photoStrippedSize) size).bytes, "");
                            break;
                        }
                    }
                }
                if (sourceBitmap != null && !sourceBitmap.isRecycled()) {
                    cachedBlurredBitmap = org.telegram.messenger.Utilities.stackBlurBitmapMax(sourceBitmap);
                    cachedBlurredSource = boundMessage;
                } else {
                    cachedBlurredBitmap = null;
                    cachedBlurredSource = null;
                    PotokDebugLog.d("SPOILER_BLUR", "post=" + boundMessage.getId()
                        + " NO source bitmap available (strippedThumb null AND no TL_photoStrippedSize in photoThumbs) — блюр не будет нарисован");
                }
            } catch (Throwable e) {
                // Сильный блюр не должен ронять бинд ячейки — при любой ошибке просто
                // остаёмся без кэшированного блюра, onDraw ниже это проверяет.
                cachedBlurredBitmap = null;
                cachedBlurredSource = null;
                PotokDebugLog.d("SPOILER_BLUR", "post=" + boundMessage.getId()
                    + " stackBlurBitmapMax failed: " + e);
            }
        }

        // Позволяет ЗАМЕНИТЬ/дополнить callback снятия спойлера уже ПОСЛЕ основного
        // bind() — используется, чтобы дать прямой (не через notifyItemChanged/
        // adapterPosition, который в момент тапа теоретически может быть невалиден)
        // перерендер img с уже правильными (незаблюренными) фильтрами конкретно для
        // этого holder'а. См. использование в CarouselAdapter.onBindViewHolder.
        void setOnRevealed(Runnable onRevealedCallback) {
            onRevealed = onRevealedCallback;
        }

        void unbind() {
            boundMessage = null;
            onRevealed = null;
            revealProgress = 0f;
            cachedBlurredBitmap = null;
            cachedBlurredSource = null;
            updateEffect();
            setVisibility(GONE);
        }

        private boolean shouldShow() {
            return boundMessage != null && boundMessage.hasMediaSpoilers() && !boundMessage.isSpoilersRevealed;
        }

        // ФИКС "неправильный порядок тап -> загрузка -> снятие спойлера": было —
        // тап сразу снимал спойлер (startReveal), а загрузка (если медиа не в
        // кэше) начиналась только ПОСЛЕ, уже на открытом медиа. Нужно наоборот:
        // первый тап на ещё не скачанном медиа должен ЗАПУСТИТЬ загрузку (через
        // уже существующую кнопку PhotoDownloadOverlay/VideoDownloadPlate,
        // которая рисуется НИЖЕ этого оверлея в том же wrapper — см.
        // onCreateViewHolder), а спойлер должен ОСТАВАТЬСЯ поверх на всё время
        // загрузки. Только когда файл РЕАЛЬНО уже в кэше, тап должен запускать
        // startReveal(). Проверяем это заново на каждый тап (а не один раз при
        // bind()), т.к. состояние кэша могло измениться (докачалось) уже после
        // bind() этой ячейки.
        private boolean isMediaDownloaded() {
            if (boundMessage == null) return false;
            TLRPC.MessageMedia media = boundMessage.messageOwner != null ? boundMessage.messageOwner.media : null;
            if (media == null) return false;
            try {
                if (media.document != null) {
                    java.io.File f = FileLoader.getInstance(boundMessage.currentAccount)
                        .getPathToAttach(media.document, false);
                    return f != null && f.exists();
                }
                if (media.photo != null && media.photo.sizes != null) {
                    TLRPC.PhotoSize best = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, AndroidUtilities.getPhotoSize());
                    if (best == null) return false;
                    java.io.File f = FileLoader.getInstance(boundMessage.currentAccount)
                        .getPathToAttach(best, false);
                    return f != null && f.exists();
                }
            } catch (Exception ignore) {
            }
            return false;
        }

        private void updateVisibility() {
            setVisibility(shouldShow() ? VISIBLE : GONE);
        }

        private void updateEffect() {
            if (shouldShow() && SpoilerEffect2.supports()) {
                if (effect == null || effect.destroyed) {
                    effect = SpoilerEffect2.getInstance(this);
                    if (attachedToWindow && effect != null) {
                        effect.attach(this);
                    }
                }
            } else if (effect != null) {
                effect.detach(this);
                effect = null;
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            attachedToWindow = true;
            if (effect != null) {
                if (effect.destroyed) {
                    effect = SpoilerEffect2.getInstance(this);
                } else {
                    effect.attach(this);
                }
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            attachedToWindow = false;
            if (effect != null) {
                effect.detach(this);
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            updateEffect();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!shouldShow()) {
                return false;
            }
            boolean downloaded = isMediaDownloaded();
            if (!downloaded) {
                // Медиа ещё не в кэше — НЕ перехватываем тап, пропускаем его дальше
                // (в wrapper это дойдёт до PhotoDownloadOverlay/VideoDownloadPlate,
                // которые теперь лежат ВЫШЕ по z-порядку и сами обработают тап и
                // запустят загрузку — см. addView() выше). Спойлер при этом остаётся
                // видимым как есть — мы просто не меняем revealProgress/видимость здесь.
                return false;
            }
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                // ФИКС "спойлер снимается только тапом строго по центру" (Блок H):
                // раньше здесь не было явного requestDisallowInterceptTouchEvent —
                // родительские scroll/swipe-контейнеры этой ячейки (горизонтальная
                // карусель постов + вертикальная лента) МОГЛИ перехватить жест через
                // свой onInterceptTouchEvent при малейшем сдвиге пальца между DOWN и
                // UP (стандартный touch slop у Android). Тап строго в центре
                // статистически почти никогда не даёт горизонтального сдвига (рука
                // тапает более-менее прямо), поэтому там жест долетал до UP
                // надёжно, а ближе к краям карточки — даже минимальный дрожащий
                // сдвиг пальца интерпретировался родителем как начало свайпа
                // карусели, и spoilerOverlay просто не получал ACTION_UP вообще.
                // requestDisallowInterceptTouchEvent(true) явно запрещает предкам
                // перехватывать этот конкретный жест, пока спойлер сам его
                // обрабатывает — тап срабатывает из любой точки медиа одинаково.
                ViewParent parent = getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(true);
                }
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP && revealProgress == 0f) {
                startReveal(event.getX(), event.getY());
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                ViewParent parent = getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(false);
                }
            }
            return true;
        }

        private void startReveal(float x, float y) {
            revealX = x;
            revealY = y;
            revealMaxRadius = (float) Math.sqrt(Math.pow(getWidth(), 2) + Math.pow(getHeight(), 2));
            long duration = (long) Math.max(250, Math.min(550, revealMaxRadius * 0.3f));
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f).setDuration(duration);
            animator.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
            final MessageObject revealingMessage = boundMessage;
            animator.addUpdateListener(a -> {
                revealProgress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (revealingMessage != null) {
                        revealingMessage.isSpoilersRevealed = true;
                    }
                    if (boundMessage == revealingMessage) {
                        updateVisibility();
                        // Перебиндиваем ячейку, чтобы currentPhotoFilter/
                        // currentPhotoFilterThumb в CarouselAdapter пересчитались уже
                        // без принудительного "_b2" (см. spoilerActive в
                        // onBindViewHolder) — иначе картинка/видео останется
                        // заблюренной даже после того как частицы спойлера ушли.
                        if (onRevealed != null) {
                            onRevealed.run();
                        }
                    }
                }
            });
            animator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!shouldShow()) return;
            canvas.save();
            canvas.clipRect(0, 0, getWidth(), getHeight());
            if (revealProgress != 0f) {
                revealPath.rewind();
                revealPath.addCircle(revealX, revealY, revealMaxRadius * revealProgress, Path.Direction.CW);
                canvas.clipPath(revealPath, Region.Op.DIFFERENCE);
            }
            // ФИКС "снятие спойлера топорное/рывком, не как в самом Telegram":
            // раньше этот onDraw() рисовал ТОЛЬКО частицы — сам блюр жил ОТДЕЛЬНО,
            // запечённый в пикселях img (через фильтр "_b2"), и снимался одним
            // кадром в конце анимации (полный notifyItemChanged), а не вместе с
            // растущим кругом. В оригинале (ChatMessageCell.drawBlurredPhoto)
            // блюр и частицы — ОДИН слой, вырезаемый ОДНИМ и тем же растущим
            // кругом, поверх ВСЕГДА уже резкого фото снизу (см. правки выше в
            // onBindViewHolder — img теперь всегда грузится без "_b2"). Теперь
            // здесь то же самое: сначала рисуем сам блюр (растянутый на весь
            // размер вьюхи strippedThumb — маленький, уже заблюренный битмап,
            // тот же источник, что раньше использовался как заглушка), ЗАТЕМ
            // частицы поверх — оба вырезаны ОДНОЙ и той же revealPath, поэтому
            // растущий круг одновременно открывает резкий img снизу И убирает
            // блюр+частицы сверху, как единое целое.
            // ФИКС "сквозь спойлер видны детали": рисуем закэшированный СИЛЬНЫЙ
            // stack-blur (см. updateBlurredBitmap/cachedBlurredBitmap выше), а не
            // сырой слабо-заблюренный strippedThumb напрямую.
            if (cachedBlurredBitmap != null && !cachedBlurredBitmap.isRecycled()) {
                blurDstRect.set(0, 0, getWidth(), getHeight());
                canvas.drawBitmap(cachedBlurredBitmap, null, blurDstRect, blurPaint);
            }
            if (effect != null) {
                effect.draw(canvas, this, getWidth(), getHeight());
            }
            canvas.restore();
        }
    }

    // ------------------------------------------------------------------ PlayIndicatorView
    /**
     * Большая play-кнопка по центру видео — с фоновым кругом, как в оригинальном
     * Telegram/Plus Messenger. В отличие от VideoDownloadOverlay, она НЕ занимается
     * загрузкой и не меняет иконку в зависимости от состояния кэша — всегда
     * показывает треугольник play, потому что тап по ней (как и тап по самому
     * кадру) просто открывает видео в полноэкранном просмотрщике, независимо от
     * того, скачано оно в кэш или нет.
     */
    private static class PlayIndicatorView extends View {
        private final RadialProgress2 radialProgress;

        PlayIndicatorView(Context context) {
            super(context);
            radialProgress = new RadialProgress2(this);
            radialProgress.setColorKeys(Theme.key_chat_mediaLoaderPhoto, Theme.key_chat_mediaLoaderPhotoSelected,
                    Theme.key_chat_mediaLoaderPhotoIcon, Theme.key_chat_mediaLoaderPhotoIconSelected);
            radialProgress.setIcon(MediaActionDrawable.ICON_PLAY, false, false);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            radialProgress.setProgressRect(0, 0, w, h);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            radialProgress.draw(canvas);
        }
    }

    // ------------------------------------------------------------------ AudioSeekBarView

    /**
     * Обёртка-View вокруг компонента SeekBar (org.telegram.ui.Components.SeekBar).
     * SeekBar сам по себе не является View — он рисует себя и обрабатывает touch
     * через переданный родительский View, ровно как это сделано в ChatMessageCell.
     */
    private static class AudioSeekBarView extends View implements SeekBar.SeekBarDelegate {
        private final SeekBar seekBar;
        private MessageObject messageObject;

        AudioSeekBarView(Context context, Theme.ResourcesProvider rp) {
            super(context);
            seekBar = new SeekBar(this);
            seekBar.setDelegate(this);
            seekBar.setColors(
                Theme.getColor(Theme.key_chat_inAudioSeekbar, rp),
                Theme.getColor(Theme.key_chat_inAudioCacheSeekbar, rp),
                Theme.getColor(Theme.key_chat_inAudioSeekbarFill, rp),
                Theme.getColor(Theme.key_chat_inAudioSeekbarFill, rp),
                Theme.getColor(Theme.key_chat_inAudioSeekbarSelected, rp)
            );
        }

        void setMessageObject(MessageObject mo) {
            messageObject = mo;
            seekBar.setProgress(mo != null ? mo.audioProgress : 0f);
            invalidate();
        }

        void updateProgress() {
            if (messageObject == null || seekBar.isDragging()) return;
            seekBar.setProgress(messageObject.audioProgress);
            seekBar.setBufferedProgress(messageObject.bufferedProgress);
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            seekBar.setSize(w, h);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            seekBar.draw(canvas);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            // Фикс "перемотка не работает, вместо неё срабатывает переход/скролл ленты":
            // без requestDisallowInterceptTouchEvent родительский RecyclerListView
            // (вертикальный список постов) продолжает следить за жестом ПОСЛЕ ACTION_DOWN
            // и, как только палец сдвигается хоть немного по вертикали (обычное дело при
            // ручной горизонтальной перемотке на телефоне), перехватывает поток —
            // AudioSeekBarView получает ACTION_CANCEL вместо ACTION_MOVE/UP, и
            // onSeekBarDrag() ни разу не вызывается. Запрещаем перехват на всё время
            // одного жеста — тот же приём, что использует сам ChatMessageCell (SeekBar
            // внутри списка сообщений) неявно через свою обработку тача на уровне ячейки.
            int action = event.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
            }
            boolean result = seekBar.onTouch(action, event.getX(), event.getY());
            if (result) invalidate();
            return result || super.onTouchEvent(event);
        }

        @Override
        public void onSeekBarDrag(float progress) {
            if (messageObject == null) return;
            messageObject.audioProgress = progress;
            MediaController.getInstance().seekToProgress(messageObject, progress);
            invalidate();
        }

        @Override
        public void onSeekBarContinuousDrag(float progress) {
            if (messageObject == null) return;
            messageObject.audioProgress = progress;
            messageObject.audioProgressSec = (int) (messageObject.getDuration() * progress);
            invalidate();
        }
    }

    // ------------------------------------------------------------------ AudioWaveformView

    /**
     * То же самое, что AudioSeekBarView выше, но для ГОЛОСОВЫХ сообщений — в
     * оригинале у войсов рисуется волна (SeekBarWaveform, тот самый реальный класс,
     * которым это рисует ChatMessageCell), а не гладкая линия прогресса как у
     * музыки/аудио. Раньше в ленте у войсов использовался тот же гладкий SeekBar,
     * что и у музыки — визуально это неверно и было частью жалобы "не один в один".
     */
    private static class AudioWaveformView extends View implements SeekBar.SeekBarDelegate {
        private final SeekBarWaveform seekBarWaveform;
        private MessageObject messageObject;

        AudioWaveformView(Context context, Theme.ResourcesProvider rp) {
            super(context);
            seekBarWaveform = new SeekBarWaveform(context);
            seekBarWaveform.setDelegate(this);
            seekBarWaveform.setColors(
                Theme.getColor(Theme.key_chat_inVoiceSeekbar, rp),
                Theme.getColor(Theme.key_chat_inVoiceSeekbarFill, rp),
                Theme.getColor(Theme.key_chat_inVoiceSeekbarSelected, rp)
            );
        }

        void setMessageObject(MessageObject mo) {
            messageObject = mo;
            if (mo != null) {
                seekBarWaveform.setMessageObject(mo);
                seekBarWaveform.setWaveform(mo.getWaveform());
                seekBarWaveform.setProgress(mo.audioProgress);
            }
            invalidate();
        }

        void updateProgress() {
            if (messageObject == null) return;
            seekBarWaveform.setProgress(messageObject.audioProgress);
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            seekBarWaveform.setSize(w, h);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            seekBarWaveform.draw(canvas, this);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            // См. подробный комментарий у AudioSeekBarView.onTouchEvent выше — тот же
            // фикс перехвата жеста родительским RecyclerListView.
            int action = event.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
            }
            boolean result = seekBarWaveform.onTouch(action, event.getX(), event.getY());
            if (result) invalidate();
            return result || super.onTouchEvent(event);
        }

        @Override
        public void onSeekBarDrag(float progress) {
            if (messageObject == null) return;
            messageObject.audioProgress = progress;
            MediaController.getInstance().seekToProgress(messageObject, progress);
            invalidate();
        }
    }

    // ------------------------------------------------------------------ AudioPlayButton

    /**
     * Круглая play/pause/download-кнопка аудио-сообщения — тот же паттерн, что
     * VideoDownloadPlate/PhotoDownloadOverlay (RadialProgress2 + DownloadController.
     * FileDownloadProgressListener), плюс два дополнительных состояния play/pause
     * (у фото/видео их не было — там кнопка только скачивает, а не проигрывает).
     * Состояния: 2 — не скачано (стрелка загрузки); 1 — качается (кольцо+крестик);
     * 0 — скачано, не играет (play); 3 — скачано, играет (pause).
     */
    /**
     * Круглая play/pause-кнопка аудио-сообщения с ОТДЕЛЬНЫМ маленьким бейджем
     * загрузки поверх неё — 1:1 с ChatMessageCell для DOCUMENT_ATTACH_TYPE_MUSIC
     * (см. getMiniIconForCurrentState()/hasMiniProgress): большая кнопка ВСЕГДА
     * показывает play/pause (никогда не показывает "стрелку загрузки" сама по
     * себе), а отдельный маленький кружок-бейдж (RadialProgress2.setMiniIcon —
     * это штатная, встроенная в сам RadialProgress2 функция, не самопальная)
     * показывает: стрелку загрузки (не скачано), кольцо-крестик с прогрессом
     * (качается), или пропадает целиком (скачано). Тап по кнопке ВСЕГДА и играет,
     * и запускает докачку в кэш одновременно — ровно как в реальном Telegram для
     * музыкальных файлов (потоковое воспроизведение параллельно с докачкой).
     */
    private static class AudioPlayButton extends View implements DownloadController.FileDownloadProgressListener {
        private static final int STATE_PLAY = 0;
        private static final int STATE_PAUSE = 1;

        private final RadialProgress2 radialProgress;
        private final int TAG;
        private MessageObject messageObject;
        private TLRPC.Document document;
        private String fileName;
        private int currentAccount;
        private int buttonState = -1;
        /** -1 — скачано (бейдж скрыт), 0 — не скачано (стрелка), 1 — качается (крестик+кольцо). */
        private int miniButtonState = -1;
        /** Возвращает true, если воспроизведение реально стартовало (см. конструктор ячейки). */
        private Utilities.CallbackReturn<MessageObject, Boolean> onPlayRequested;

        AudioPlayButton(Context context, Theme.ResourcesProvider rp) {
            super(context);
            radialProgress = new RadialProgress2(this);
            // Фикс "бейдж загрузки — белый": раньше здесь стояла пара
            // key_chat_inAudioProgress/key_chat_inAudioSelectedProgress. В реальной
            // теме (см. night.attheme/darkblue.attheme) chat_inAudioProgress=-1 —
            // буквально непрозрачный белый (0xFFFFFFFF), и оригинальный ChatMessageCell
            // НИКОГДА не использует этот ключ для заливки круга play/pause-кнопки —
            // только key_chat_inLoader/key_chat_inLoaderSelected (см.
            // radialProgress.setColorKeys(Theme.key_chat_inLoader, ...) в оригинале
            // для входящих медиа/документов/аудио). key_chat_inAudioProgress
            // зарезервирован под другое — линию прогресса самого сикбара, а не под
            // круглую кнопку. Из-за неверного ключа круг главной кнопки был не виден
            // (замаскирован обложкой трека), а вот НЕЗАВИСИМЫЙ от обложки мини-бейдж
            // честно рисовал этот белый цвет — отсюда "белая кнопка".
            radialProgress.setColorKeys(Theme.key_chat_inLoader, Theme.key_chat_inLoaderSelected,
                    Theme.key_chat_inMediaIcon, Theme.key_chat_inMediaIconSelected);
            radialProgress.setCircleRadius(dp(22));
            TAG = DownloadController.getInstance(UserConfig.selectedAccount).generateObserverTag();
            // Фикс "тап по маленькой кнопке загрузки запускает play/pause": раньше
            // весь View целиком висел на одном setOnClickListener(onClick), который
            // ВСЕГДА играл/ставил на паузу — независимо от того, куда именно на
            // кнопке попал палец. В оригинале (ChatMessageCell.checkAudioMotionEvent)
            // область мини-бейджа хит-тестится ОТДЕЛЬНО от основной кнопки и вызывает
            // СВОЙ обработчик didPressMiniButton() — только старт/отмена докачки,
            // без единого обращения к play/pause. Реализовано ниже в onTouchEvent().
            setClickable(true);
        }

        void setOnPlayRequested(Utilities.CallbackReturn<MessageObject, Boolean> listener) {
            onPlayRequested = listener;
        }

        void bind(MessageObject mo) {
            messageObject = mo;
            document = mo.getDocument();
            currentAccount = mo.currentAccount;
            fileName = FileLoader.getAttachFileName(document);
            // 1:1 с SharedAudioCell.setMessageObject(): обложка трека рисуется самим
            // RadialProgress2 через setImageOverlay(), а не отдельным ImageReceiver.
            // Порядок источников строго как в оригинале: thumb документа -> audioCover
            // (Bitmap, уже извлечённый из ID3-тегов) -> artworkUrl (last.fm/архив) ->
            // пусто (тогда RadialProgress2 рисует однотонный фон под иконкой).
            final TLRPC.PhotoSize thumb = document != null ? FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 360) : null;
            if (thumb instanceof TLRPC.TL_photoSize || thumb instanceof TLRPC.TL_photoSizeProgressive) {
                radialProgress.setImageOverlay(thumb, document, messageObject);
            } else {
                Bitmap cover = messageObject.audioCover;
                if (cover != null) {
                    radialProgress.setImageOverlay(cover);
                } else {
                    final String artworkUrl = messageObject.getArtworkUrl(true);
                    if (!TextUtils.isEmpty(artworkUrl)) {
                        radialProgress.setImageOverlay(artworkUrl);
                    } else {
                        radialProgress.setImageOverlay(null, null, null);
                    }
                }
            }
            updateState(false);
        }

        void unbind() {
            if (fileName != null) {
                DownloadController.getInstance(currentAccount).removeLoadingFileObserver(this);
            }
            messageObject = null;
            document = null;
            fileName = null;
            buttonState = -1;
            miniButtonState = -1;
        }

        /**
         * Обновить иконку play/pause извне — вызывается при NotificationCenter.
         * messagePlayingDidStart/messagePlayingPlayStateChanged/messagePlayingDidReset
         * (см. PotokFeedFragment), а не только на тик прогресса — иначе если
         * заиграл ДРУГОЙ трек, кнопка этой ячейки осталась бы показывать "пауза",
         * хотя реально сейчас играет не она.
         */
        void refresh() {
            if (messageObject != null) updateState(true);
        }

        private void updateState(boolean animated) {
            if (document == null || fileName == null) return;
            // Главная кнопка: ВСЕГДА play/pause, независимо от того, скачан файл или
            // нет — 1:1 с веткой hasMiniProgress в ChatMessageCell (для музыки тап
            // по play одновременно стартует и воспроизведение, и докачку в кэш).
            boolean playing = MediaController.getInstance().isPlayingMessage(messageObject)
                    && !MediaController.getInstance().isMessagePaused();
            buttonState = playing ? STATE_PAUSE : STATE_PLAY;
            radialProgress.setIcon(playing ? MediaActionDrawable.ICON_PAUSE : MediaActionDrawable.ICON_PLAY, false, animated);

            // Маленький бейдж поверх — отдельно отражает состояние ДОКАЧКИ файла в
            // кэш, никак не завязан на play/pause главной иконки.
            boolean fileExists = messageObject.mediaExists;
            if (fileExists) {
                DownloadController.getInstance(currentAccount).removeLoadingFileObserver(this);
                miniButtonState = -1;
                radialProgress.setMiniIcon(MediaActionDrawable.ICON_NONE, false, animated);
            } else {
                DownloadController.getInstance(currentAccount).addLoadingFileObserver(fileName, this);
                boolean isLoading = FileLoader.getInstance(currentAccount).isLoadingFile(fileName);
                if (isLoading) {
                    miniButtonState = 1;
                    Float progress = org.telegram.messenger.ImageLoader.getInstance().getFileProgress(fileName);
                    radialProgress.setProgress(progress != null ? progress : 0, animated);
                    radialProgress.setMiniIcon(MediaActionDrawable.ICON_CANCEL, false, animated);
                } else {
                    miniButtonState = 0;
                    radialProgress.setMiniIcon(MediaActionDrawable.ICON_DOWNLOAD, false, animated);
                }
            }
            invalidate();
        }

        private void onClick() {
            if (document == null) return;
            // Тап по кнопке = play/pause, КАК ОБЫЧНО. Если файла ещё нет в кэше —
            // докачку запускаем ПАРАЛЛЕЛЬНО тем же тапом (не отдельным состоянием
            // кнопки, как было раньше) — тот же эффект, что в реальном Telegram у
            // потокового воспроизведения музыки: играть начинает сразу, бейдж
            // загрузки просто показывает прогресс докачки на фоне.
            if (buttonState == STATE_PLAY) {
                if (!messageObject.mediaExists) {
                    messageObject.putInDownloadsStore = true;
                    FileLoader.getInstance(currentAccount).loadFile(document, messageObject, FileLoader.PRIORITY_NORMAL, 0);
                }
                boolean started = onPlayRequested != null && onPlayRequested.run(messageObject);
                if (started) {
                    buttonState = STATE_PAUSE;
                    radialProgress.setIcon(MediaActionDrawable.ICON_PAUSE, false, true);
                    invalidate();
                }
            } else if (buttonState == STATE_PAUSE) {
                boolean result = MediaController.getInstance().pauseMessage(messageObject);
                if (result) {
                    buttonState = STATE_PLAY;
                    radialProgress.setIcon(MediaActionDrawable.ICON_PLAY, false, true);
                    invalidate();
                }
            }
        }

        /**
         * Тап именно по маленькому бейджу загрузки — ТОЛЬКО старт/отмена докачки,
         * play/pause не трогает вообще. 1:1 с ChatMessageCell.didPressMiniButton()
         * (ветка для AUDIO/MUSIC, без записи звонка/видео).
         */
        private void didPressMiniButton() {
            if (document == null || messageObject == null) return;
            if (miniButtonState == 0) {
                miniButtonState = 1;
                radialProgress.setProgress(0, false);
                messageObject.putInDownloadsStore = true;
                FileLoader.getInstance(currentAccount).loadFile(document, messageObject, FileLoader.PRIORITY_NORMAL_UP, 0);
                messageObject.loadingCancelled = false;
                radialProgress.setMiniIcon(MediaActionDrawable.ICON_CANCEL, false, true);
                invalidate();
            } else if (miniButtonState == 1) {
                miniButtonState = 0;
                messageObject.loadingCancelled = true;
                FileLoader.getInstance(currentAccount).cancelLoadFile(document);
                radialProgress.setMiniIcon(MediaActionDrawable.ICON_DOWNLOAD, false, true);
                invalidate();
            }
        }

        /** true, если координата (x,y) попадает в область мини-бейджа загрузки —
         * формула центра cx/cy 1:1 с RadialProgress2.draw() для ветки
         * "progressRect.width()==dp(44)" (у нас всегда так, см. onMeasure ниже):
         * cx/cy = центр + dp(16), визуальный радиус ~dp(11). Хит-зона берётся с
         * запасом (dp(15) половина стороны — как dp(36)/dp(28) side/offset в
         * оригинале ChatMessageCell, тоже заметно больше видимого кружка ради
         * удобства пальца). */
        private boolean isInMiniButtonArea(float x, float y) {
            if (miniButtonState < 0) return false;
            RectF pr = radialProgress.getProgressRect();
            float cx = pr.centerX() + dp(16);
            float cy = pr.centerY() + dp(16);
            float half = dp(15);
            return x >= cx - half && x <= cx + half && y >= cy - half && y <= cy + half;
        }

        private boolean miniPressed;
        private boolean mainPressed;

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (document == null) return super.onTouchEvent(event);
            boolean inMini = isInMiniButtonArea(event.getX(), event.getY());
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (inMini) {
                        miniPressed = true;
                    } else {
                        mainPressed = true;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    return true;
                case MotionEvent.ACTION_UP:
                    if (miniPressed && inMini) {
                        didPressMiniButton();
                    } else if (mainPressed && !inMini) {
                        onClick();
                    }
                    miniPressed = false;
                    mainPressed = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    miniPressed = false;
                    mainPressed = false;
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(dp(44), dp(44));
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            radialProgress.setProgressRect(0, 0, w, h);
            // bind() вызывается из setPost() при биндинге ViewHolder'а — в этот
            // момент у View ещё могли быть нулевые границы, и RadialProgress2 мог
            // закрепить иконку под них. Пересчитываем состояние ЕЩЁ РАЗ уже после
            // реального измерения — похоже, именно это было причиной того, что
            // кнопка play/download визуально не появлялась вообще.
            if (messageObject != null) {
                updateState(false);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (buttonState == -1) return;
            radialProgress.draw(canvas);
        }

        @Override
        public void onFailedDownload(String name, boolean canceled) {
            updateState(true);
        }

        @Override
        public void onSuccessDownload(String name) {
            if (messageObject != null) messageObject.mediaExists = true;
            radialProgress.setProgress(1, true);
            updateState(true);
        }

        @Override
        public void onProgressDownload(String name, long downloadedSize, long totalSize) {
            radialProgress.setProgress(Math.min(1f, downloadedSize / (float) totalSize), true);
            if (miniButtonState != 1) {
                updateState(true);
            }
        }

        @Override
        public void onProgressUpload(String name, long uploadedSize, long totalSize, boolean isEncrypted) {
        }

        @Override
        public int getObserverTag() {
            return TAG;
        }
    }

    // ------------------------------------------------------------------ PlayTriangleView

    /**
     * Чистый треугольник play, нарисованный через Path — чёткий на любом dpi.
     * Растровый play_mini_video всего 24x30px даже в xxhdpi, поэтому при
     * увеличении (как было раньше, scale 1.8) выглядит размытым.
     */
    private static class PlayTriangleView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        PlayTriangleView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.FILL);
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            path.reset();
            // Равносторонний треугольник вершиной вправо, по центру view
            float r = Math.min(w, h) / 2f;
            float cx = w / 2f, cy = h / 2f;
            path.moveTo(cx - r * 0.55f, cy - r * 0.85f);
            path.lineTo(cx - r * 0.55f, cy + r * 0.85f);
            path.lineTo(cx + r * 0.85f, cy);
            path.close();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawPath(path, paint);
        }
    }

    // ------------------------------------------------------------------ PollView

    /**
     * Карточка опроса внутри поста ленты. Оригинальный Telegram рисует опрос
     * сложной canvas-отрисовкой прямо в ChatMessageCell (PollButton и десятки
     * полей вроде vibrateOnProgressUp, pollAnimatedVoteCounter и т.п.) — заводить
     * такую же машинерию под один тип контента ленты избыточно, поэтому здесь —
     * эквивалент из обычных Android-view (тот же подход, что раньше был выбран
     * для durationBadge), но по всем содержательным элементам максимально близко
     * к оригиналу: тип опроса, вопрос, варианты со шкалой процентов и подсветкой
     * выбранного/правильного варианта, число проголосовавших.
     */
    private static class PollView extends LinearLayout {
        private final Theme.ResourcesProvider resourcesProvider;
        private final TextView typeLabel;
        private final TextView questionView;
        private final LinearLayout answersContainer;
        private final TextView votersView;
        private final TextView voteButton;
        private final ArrayList<PollAnswerRow> rows = new ArrayList<>();
        private final ArrayList<TLRPC.PollAnswer> selectedAnswers = new ArrayList<>();
        private MessageObject messageObject;
        private TLRPC.TL_messageMediaPoll media;
        private boolean sendingVote = false;
        /**
         * Долгое нажатие по карточке поста должно открывать канал (как у обычных
         * постов) — но строки вариантов ответа (PollAnswerRow) кликабельны ДО
         * голосования (обрабатывают обычный тап на выбор варианта), и Android не
         * даёт долгому нажатию всплыть до родительской карточки, если дочерний
         * View сам кликабелен — touch-последовательность перехватывается там, где
         * начался палец. Поэтому пробрасываем длинное нажатие с каждой строки
         * (и с кнопки "Проголосовать") наружу вручную через этот callback.
         */
        private Runnable onLongPress;

        void setOnLongPressListener(Runnable listener) {
            onLongPress = listener;
        }

        PollView(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            setOrientation(VERTICAL);

            // ВАЖНО: вопрос — ПЕРВАЯ строка (жирным), тип опроса ("Анонимный опрос" и
            // т.п.) — строка ПОД ним, мельче и серым. Раньше порядок addView был
            // обратным (типа опроса сверху, вопрос снизу) — не совпадало со
            // скриншотом реального канала, где "Test" (вопрос) идёт первым.
            questionView = new TextView(context);
            questionView.setTextSize(16);
            questionView.setTypeface(AndroidUtilities.bold());
            questionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            addView(questionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            typeLabel = new TextView(context);
            typeLabel.setTextSize(13);
            typeLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            addView(typeLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

            answersContainer = new LinearLayout(context);
            answersContainer.setOrientation(VERTICAL);
            addView(answersContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

            // Кнопка "Проголосовать" — видна только для многовариантных опросов ДО
            // голосования (одиночный выбор голосует сразу по тапу на вариант, как в
            // оригинальном Telegram — отдельная кнопка ему не нужна).
            voteButton = new TextView(context);
            voteButton.setTextSize(14);
            voteButton.setTypeface(AndroidUtilities.bold());
            voteButton.setGravity(Gravity.START);
            voteButton.setText("Проголосовать");
            voteButton.setPadding(0, dp(10), 0, dp(4));
            voteButton.setVisibility(GONE);
            voteButton.setOnClickListener(v -> {
                if (!selectedAnswers.isEmpty()) {
                    submitVote(new ArrayList<>(selectedAnswers));
                }
            });
            voteButton.setOnLongClickListener(v -> {
                if (onLongPress != null) {
                    onLongPress.run();
                    return true;
                }
                return false;
            });
            addView(voteButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

            votersView = new TextView(context);
            votersView.setTextSize(13);
            votersView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            addView(votersView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        }

        void bind(TLRPC.TL_messageMediaPoll media, MessageObject messageObject) {
            if (media == null || media.poll == null || messageObject == null) {
                setVisibility(GONE);
                return;
            }
            this.media = media;
            this.messageObject = messageObject;
            selectedAnswers.clear();
            TLRPC.Poll poll = media.poll;
            TLRPC.PollResults results = media.results;

            // Тип опроса — та же формулировка, что использует оригинальный клиент.
            String type;
            if (poll.quiz) {
                type = "Викторина";
            } else if (poll.public_voters) {
                type = "Опрос";
            } else {
                type = "Анонимный опрос";
            }
            if (poll.closed) {
                type += " • завершён";
            } else if (poll.multiple_choice) {
                type += " • можно выбрать несколько";
            }
            typeLabel.setText(type);
            questionView.setText(poll.question != null ? poll.question.text : "");

            // Режим голосования (чекбоксы, без процентов) — пока пользователь не
            // проголосовал и опрос не завершён, один в один как в самом канале
            // (см. скрины пользователя: до голоса — пустые строки с чекбоксами и
            // кнопкой "Проголосовать", после — шкалы с процентами).
            boolean votingMode = !messageObject.isVoted() && !poll.closed;

            int totalVoters = results != null ? results.total_voters : 0;
            java.util.Map<String, TLRPC.PollAnswerVoters> votersByOption = new java.util.HashMap<>();
            boolean hasResults = results != null && results.results != null;
            if (hasResults) {
                for (TLRPC.PollAnswerVoters v : results.results) {
                    if (v != null && v.option != null) {
                        votersByOption.put(bytesToKey(v.option), v);
                    }
                }
            }

            answersContainer.removeAllViews();
            rows.clear();
            if (poll.answers != null) {
                for (TLRPC.PollAnswer answer : poll.answers) {
                    if (answer == null) continue;
                    PollAnswerRow row = new PollAnswerRow(getContext(), resourcesProvider);
                    TLRPC.PollAnswerVoters voters = answer.option != null ? votersByOption.get(bytesToKey(answer.option)) : null;
                    int optionVotes = voters != null ? voters.voters : 0;
                    int percent = (!votingMode && hasResults && totalVoters > 0) ? Math.round(100f * optionVotes / totalVoters) : -1;
                    boolean chosen = voters != null && voters.chosen;
                    boolean correct = voters != null && voters.correct;
                    boolean wrong = poll.quiz && chosen && !correct;
                    row.bind(answer.text != null ? answer.text.text : "", percent, chosen, correct && poll.quiz, wrong, votingMode, poll.multiple_choice);
                    if (votingMode) {
                        row.setOnClickListener(v -> onRowTapped(answer, poll, row));
                    } else {
                        row.setOnClickListener(null);
                        row.setClickable(false);
                    }
                    // См. комментарий у поля onLongPress выше: строка кликабельна в
                    // votingMode и перехватывает touch-последовательность, из-за чего
                    // долгое нажатие не всплывает до карточки поста — форвардим вручную.
                    row.setOnLongClickListener(v -> {
                        if (onLongPress != null) {
                            onLongPress.run();
                            return true;
                        }
                        return false;
                    });
                    answersContainer.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));
                    rows.add(row);
                }
            }

            voteButton.setVisibility(votingMode && poll.multiple_choice ? VISIBLE : GONE);
            updateVoteButtonState();

            if (totalVoters > 0) {
                votersView.setVisibility(VISIBLE);
                votersView.setText(LocaleController.formatPluralString("Vote", totalVoters));
            } else {
                votersView.setVisibility(GONE);
            }

            setVisibility(VISIBLE);
        }

        /**
         * Одиночный выбор — голос уходит сразу по тапу (как в оригинале, отдельной
         * кнопки не требуется). Множественный выбор — тап только переключает
         * чекбокс, реальная отправка — по кнопке "Проголосовать".
         */
        private void onRowTapped(TLRPC.PollAnswer answer, TLRPC.Poll poll, PollAnswerRow row) {
            if (sendingVote || messageObject == null) return;
            if (poll.multiple_choice) {
                boolean nowSelected = !selectedAnswers.contains(answer);
                if (nowSelected) {
                    selectedAnswers.add(answer);
                } else {
                    selectedAnswers.remove(answer);
                }
                row.setSelectedForVote(nowSelected);
                updateVoteButtonState();
            } else {
                // Одиночный выбор — голос уходит мгновенно по тапу, поэтому здесь же
                // (а не в кнопке "Проголосовать", которой для одиночного выбора нет)
                // включаем анимацию ожидания на КОНКРЕТНОЙ нажатой строке — сразу
                // видимый отклик на тап вместо ощущения "нажал в пустоту".
                row.setVoteInProgress(true);
                ArrayList<TLRPC.PollAnswer> answers = new ArrayList<>();
                answers.add(answer);
                submitVote(answers);
            }
        }

        private void updateVoteButtonState() {
            boolean enabled = !selectedAnswers.isEmpty() && !sendingVote;
            voteButton.setAlpha(enabled ? 1f : 0.5f);
            voteButton.setEnabled(enabled);
            voteButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider));
        }

        private void submitVote(ArrayList<TLRPC.PollAnswer> answers) {
            if (messageObject == null) return;
            sendingVote = true;
            updateVoteButtonState();
            // Реальный API-вызов — TL_messages_sendVote, тот же самый метод, которым
            // голосует сам оригинальный Telegram-клиент. Ответ сервера приходит через
            // MessagesController.processUpdates() -> NotificationCenter.didUpdatePollResults,
            // на который подписан PotokFeedFragment (см. PotokFeedPostCell.updatePollIfMatching) —
            // именно оттуда прилетит перерисовка с уже посчитанными процентами, а не
            // отсюда напрямую, чтобы результат совпадал с тем, что реально подтвердил сервер.
            org.telegram.messenger.SendMessagesHelper.getInstance(messageObject.currentAccount)
                .sendVote(messageObject, answers, () -> {
                    sendingVote = false;
                    updateVoteButtonState();
                });
        }

        /** byte[] нельзя использовать как ключ HashMap напрямую (сравнение по ссылке) — переводим в строку. */
        private static String bytesToKey(byte[] bytes) {
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) sb.append(Integer.toHexString(b & 0xFF));
            return sb.toString();
        }
    }

    /**
     * Один вариант ответа: скруглённая строка с рамкой, внутри — заливка-шкала
     * пропорционально проценту голосов (когда результаты видны) и текст варианта
     * слева / процент справа. Выбранный пользователем и (для квиза) правильный
     * вариант — акцентным цветом, как в оригинале.
     */
    private static class PollAnswerRow extends View {
        // Константы геометрии — сверены построчно с оригинальным PollButton-блоком
        // отрисовки в ChatMessageCell.java (метод drawContent, секция поллов):
        // высота линии результата 5dp (не 4, как было раньше), радиус скругления
        // линии = высота/2 (как canvas.drawRoundRect(..., dp(2), dp(2), ...) с
        // высотой линии 5dp в оригинале), альфа трека ровно 16/255 от цвета заливки
        // (Color.alpha(lineColor)*16/255 в оригинале, у нас lineColor непрозрачный,
        // поэтому множитель ровно 16/255), длительность анимации процента 300ms с
        // decelerate-интерполятором (AndroidUtilities.decelerateInterpolator,
        // pollAnimationProgressTime/300.0f в оригинале).
        private static final float CHECKBOX_CX = dp(9);
        private static final float CHECKBOX_R_CIRCLE = dp(8.5f);
        private static final float CHECKBOX_R_SQUARE = dp(8f);
        private static final float CHECKBOX_SQUARE_CORNER = dp(4f);
        private static final float TEXT_START_X = dp(26);
        private static final float LINE_TOP_GAP = dp(8);
        private static final float LINE_HEIGHT = dp(5);
        private static final float CHOSEN_ICON_R = dp(7);
        private static final float ROW_BOTTOM_PADDING = dp(6);
        private static final long PERCENT_ANIM_DURATION = 300L;

        private final Paint checkboxOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint checkboxFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint checkMarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint lineTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint lineFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint chosenCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Paint srcOutPaint;
        private final Paint voteArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.text.TextPaint textPaint = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final android.text.TextPaint percentPaint = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final int normalTextColor;
        private final int accentColor;
        private final int wrongColor;
        private final int neutralBorderColor;
        private final int cardBackgroundColor;
        private String optionText = "";
        private int percent = -1; // -1 = результаты ещё не видны — только текст+чекбокс, без линии
        private int prevPercent = -1;
        private long percentAnimStart = 0L;
        private boolean percentAnimating = false;
        private boolean chosen = false;
        private boolean correctQuizAnswer = false;
        private boolean wrongQuizAnswer = false;
        private boolean votingMode = false;
        private boolean multipleChoice = false;
        private boolean selectedForVote = false;
        private boolean voteInProgress = false;
        private float voteArcAngle = 0f;
        private android.text.StaticLayout textLayout;
        private int textLayoutWidth = -1;
        private final RectF rect = new RectF();
        private final Runnable voteArcTick = new Runnable() {
            @Override
            public void run() {
                if (!voteInProgress) return;
                voteArcAngle = (voteArcAngle + 12f) % 360f;
                invalidate();
                postDelayed(this, 16);
            }
        };
        private final Runnable percentAnimTick = new Runnable() {
            @Override
            public void run() {
                if (!percentAnimating) return;
                invalidate();
                if (getDisplayedPercentProgress() < 1f) {
                    postDelayed(this, 16);
                } else {
                    percentAnimating = false;
                }
            }
        };

        PollAnswerRow(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            normalTextColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
            accentColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider);
            wrongColor = Theme.getColor(Theme.key_text_RedRegular, resourcesProvider);
            neutralBorderColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
            cardBackgroundColor = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);

            checkboxOutlinePaint.setStyle(Paint.Style.STROKE);
            checkboxOutlinePaint.setStrokeWidth(dp(1.5f));
            checkboxOutlinePaint.setColor(neutralBorderColor);

            checkboxFillPaint.setStyle(Paint.Style.FILL);

            checkMarkPaint.setStyle(Paint.Style.STROKE);
            checkMarkPaint.setStrokeWidth(dp(1.833f));
            checkMarkPaint.setStrokeCap(Paint.Cap.ROUND);
            checkMarkPaint.setStrokeJoin(Paint.Join.ROUND);
            checkMarkPaint.setColor(0xFFFFFFFF);

            lineTrackPaint.setStyle(Paint.Style.FILL);
            lineFillPaint.setStyle(Paint.Style.FILL);
            chosenCirclePaint.setStyle(Paint.Style.FILL);

            voteArcPaint.setStyle(Paint.Style.STROKE);
            voteArcPaint.setStrokeWidth(dp(1.5f));
            voteArcPaint.setStrokeCap(Paint.Cap.ROUND);
            voteArcPaint.setColor(accentColor);

            textPaint.setTextSize(dp(14));
            textPaint.setColor(normalTextColor);
            percentPaint.setTextSize(dp(13));
            percentPaint.setTypeface(AndroidUtilities.bold());

            // Реальный ripple-selector вместо полного отсутствия тактильного отклика —
            // в оригинале у каждого PollButton есть Theme.selectorDrawable с подсветкой
            // по нажатию; здесь — стандартный Android RippleDrawable той же смысловой
            // роли. foreground (не background), чтобы подсветка была ПОВЕРХ нарисованных
            // текста/шкалы, а не перекрывалась ими — доступно с API 23, minSdk проекта 21,
            // поэтому с проверкой версии (на 21-22 будет просто без ripple-подсветки).
            android.graphics.drawable.RippleDrawable ripple = new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Theme.multAlpha(accentColor, 0.12f)), null, null);
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                setForeground(ripple);
            } else {
                setBackground(ripple);
            }
        }

        void bind(String text, int percentValue, boolean chosen, boolean correctQuizAnswer, boolean wrongQuizAnswer, boolean votingMode, boolean multipleChoice) {
            optionText = text != null ? text : "";
            // Запускаем плавную анимацию процента (300ms, decelerate) ТОЛЬКО когда это
            // реальное изменение уже показанного значения (голос/новые результаты), а
            // не первичная привязка строки при скролле — иначе при переиспользовании
            // ViewHolder'а в RecyclerView анимация будет ложно запускаться на каждый bind.
            if (this.percent != percentValue) {
                if (this.percent >= 0 && percentValue >= 0) {
                    prevPercent = this.percent;
                    percentAnimStart = android.os.SystemClock.elapsedRealtime();
                    percentAnimating = true;
                    removeCallbacks(percentAnimTick);
                    post(percentAnimTick);
                } else {
                    prevPercent = percentValue;
                    percentAnimating = false;
                }
            }
            percent = percentValue;
            this.chosen = chosen;
            this.correctQuizAnswer = correctQuizAnswer;
            this.wrongQuizAnswer = wrongQuizAnswer;
            this.votingMode = votingMode;
            this.multipleChoice = multipleChoice;
            this.selectedForVote = false;
            setVoteInProgress(false);
            textLayoutWidth = -1; // форсируем пересборку StaticLayout под текущий текст
            setClickable(votingMode);
            requestLayout();
            invalidate();
        }

        /** Вызывается из PollView при тапе на чекбокс в режиме множественного выбора. */
        void setSelectedForVote(boolean selected) {
            selectedForVote = selected;
            invalidate();
        }

        /**
         * Анимация ожидания ответа сервера конкретно на ЭТОТ вариант — аналог
         * pollVoteInProgress/voteCurrentCircleLength в оригинале (растущая дуга на
         * месте чекбокса тапнутого варианта). Даёт мгновенный визуальный отклик на
         * тап вместо "нажал и как будто ничего не произошло".
         */
        void setVoteInProgress(boolean inProgress) {
            if (voteInProgress == inProgress) return;
            voteInProgress = inProgress;
            removeCallbacks(voteArcTick);
            if (inProgress) {
                voteArcAngle = 0f;
                post(voteArcTick);
            }
            invalidate();
        }

        /** 0..1 — доля пройденного пути 300мс-анимации процента, БЕЗ интерполятора. */
        private float getDisplayedPercentProgress() {
            if (!percentAnimating) return 1f;
            long elapsed = android.os.SystemClock.elapsedRealtime() - percentAnimStart;
            return Math.min(1f, elapsed / (float) PERCENT_ANIM_DURATION);
        }

        /** Текущий отображаемый (интерполированный) процент — как button.prevPercent + (percent-prevPercent)*pollAnimationProgress в оригинале. */
        private int getDisplayedPercent() {
            if (!percentAnimating || percent < 0) return percent;
            float t = AndroidUtilities.decelerateInterpolator.getInterpolation(getDisplayedPercentProgress());
            return (int) Math.ceil(prevPercent + (percent - prevPercent) * t);
        }

        private void buildTextLayoutIfNeeded(int rowWidth) {
            if (textLayoutWidth == rowWidth && textLayout != null) return;
            textLayoutWidth = rowWidth;
            float rightReserve = (!votingMode && percent >= 0) ? dp(36) : dp(4);
            int availableWidth = Math.max(dp(10), (int) (rowWidth - TEXT_START_X - rightReserve));
            textLayout = new android.text.StaticLayout(
                optionText, textPaint, availableWidth,
                android.text.Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false
            );
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            buildTextLayoutIfNeeded(width);
            int textHeight = textLayout != null ? textLayout.getHeight() : dp(18);
            int height = (int) (textHeight + LINE_TOP_GAP + LINE_HEIGHT + dp(6) + ROW_BOTTOM_PADDING);
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            removeCallbacks(voteArcTick);
            removeCallbacks(percentAnimTick);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            buildTextLayoutIfNeeded(getWidth());
            if (textLayout == null) return;

            // --- текст варианта (многострочный, как StaticLayout title в оригинале) ---
            canvas.save();
            canvas.translate(TEXT_START_X, 0);
            textPaint.setColor((correctQuizAnswer || (!votingMode && chosen)) ? accentColor : wrongQuizAnswer ? wrongColor : normalTextColor);
            textLayout.draw(canvas);
            canvas.restore();

            float firstLineCenterY = (textLayout.getLineTop(0) + textLayout.getLineBottom(0)) / 2f;

            // --- чекбокс/индикатор варианта слева от текста ---
            if (votingMode) {
                if (voteInProgress) {
                    // Растущая дуга ожидания ответа сервера — вместо статичного чекбокса,
                    // пока голос за этот конкретный вариант не подтверждён.
                    rect.set(CHECKBOX_CX - CHECKBOX_R_CIRCLE, firstLineCenterY - CHECKBOX_R_CIRCLE,
                        CHECKBOX_CX + CHECKBOX_R_CIRCLE, firstLineCenterY + CHECKBOX_R_CIRCLE);
                    canvas.drawArc(rect, voteArcAngle, 100, false, voteArcPaint);
                } else if (multipleChoice) {
                    float r = CHECKBOX_R_SQUARE;
                    rect.set(CHECKBOX_CX - r, firstLineCenterY - r, CHECKBOX_CX + r, firstLineCenterY + r);
                    if (selectedForVote) {
                        checkboxFillPaint.setColor(accentColor);
                        canvas.drawRoundRect(rect, CHECKBOX_SQUARE_CORNER, CHECKBOX_SQUARE_CORNER, checkboxFillPaint);
                        drawCheckMark(canvas, CHECKBOX_CX, firstLineCenterY, dp(5));
                    } else {
                        canvas.drawRoundRect(rect, CHECKBOX_SQUARE_CORNER, CHECKBOX_SQUARE_CORNER, checkboxOutlinePaint);
                    }
                } else {
                    // Одиночный выбор — голос уходит сразу по тапу, поэтому это просто
                    // пустой индикатор варианта (кружок), не переключаемый чекбокс.
                    canvas.drawCircle(CHECKBOX_CX, firstLineCenterY, CHECKBOX_R_CIRCLE, checkboxOutlinePaint);
                }
                // Тонкая линия-разделитель ПОД текстом — в оригинале рисуется ВСЕГДА,
                // независимо от того, голосовал пользователь или нет (chat_replyLinePaint
                // под каждым вариантом ответа) — раньше в режиме голосования линии не
                // было вообще, только после появления процентов.
                {
                    float lineTop = textLayout.getHeight() + LINE_TOP_GAP;
                    float lineWidth = getWidth() - TEXT_START_X - dp(4);
                    lineTrackPaint.setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(normalTextColor, 16));
                    rect.set(TEXT_START_X, lineTop, TEXT_START_X + lineWidth, lineTop + LINE_HEIGHT);
                    canvas.drawRoundRect(rect, LINE_HEIGHT / 2f, LINE_HEIGHT / 2f, lineTrackPaint);
                }
            } else if (percent >= 0) {
                // --- результаты: линия-шкала под текстом (2 скруглённых прямоугольника,
                // ровно как в оригинале: трек с альфой 16/255 + заливка поверх, радиус
                // скругления = высота/2) + плавно анимированный процент + иконка ---
                int displayedPercent = getDisplayedPercent();
                float lineTop = textLayout.getHeight() + LINE_TOP_GAP;
                float lineWidth = getWidth() - TEXT_START_X - dp(4);
                int lineColor = (correctQuizAnswer || chosen) ? accentColor : (wrongQuizAnswer ? wrongColor : normalTextColor);
                float radius = LINE_HEIGHT / 2f;

                // Трек — на всю ширину, альфа ровно 16/255 (как Color.alpha(lineColor)*16/255 в оригинале для непрозрачного цвета).
                lineTrackPaint.setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(lineColor, 16));
                rect.set(TEXT_START_X, lineTop, TEXT_START_X + lineWidth, lineTop + LINE_HEIGHT);
                canvas.drawRoundRect(rect, radius, radius, lineTrackPaint);

                float filled = lineWidth * (displayedPercent / 100f);
                if (filled > 0) {
                    lineFillPaint.setColor(lineColor);
                    rect.set(TEXT_START_X, lineTop, TEXT_START_X + filled, lineTop + LINE_HEIGHT);
                    canvas.drawRoundRect(rect, radius, radius, lineFillPaint);
                }
                String percentStr = displayedPercent + "%";
                percentPaint.setColor(lineColor);
                float percentW = percentPaint.measureText(percentStr);
                canvas.drawText(percentStr, getWidth() - dp(4) - percentW, lineTop - dp(3), percentPaint);

                if (chosen || correctQuizAnswer || wrongQuizAnswer) {
                    float cx = TEXT_START_X - CHOSEN_ICON_R - dp(4);
                    float cy = lineTop + LINE_HEIGHT / 2f;
                    // Реальные ассеты Theme.chat_pollCheckDrawable/chat_pollCrossDrawable —
                    // те же самые, что рисует оригинальный ChatMessageCell. Это уже готовый
                    // цветной диск с вырезанной галочкой/крестиком (тонированный самой
                    // темой один раз при инициализации, тем же путём, что и в чате) —
                    // рисуем его как есть, затем, как в оригинале, "дозаливаем" вырез
                    // цветом реального фона карточки через SRC_OUT (иначе вырез был бы
                    // прозрачным окном в буфер saveLayer, а не в реальный фон карточки).
                    Drawable iconDrawable = wrongQuizAnswer ? Theme.chat_pollCrossDrawable[0] : Theme.chat_pollCheckDrawable[0];
                    if (iconDrawable != null) {
                        canvas.saveLayerAlpha(cx - CHOSEN_ICON_R, cy - CHOSEN_ICON_R, cx + CHOSEN_ICON_R, cy + CHOSEN_ICON_R, 255);
                        int iw = iconDrawable.getIntrinsicWidth() > 0 ? iconDrawable.getIntrinsicWidth() : (int) (CHOSEN_ICON_R * 2);
                        int ih = iconDrawable.getIntrinsicHeight() > 0 ? iconDrawable.getIntrinsicHeight() : (int) (CHOSEN_ICON_R * 2);
                        iconDrawable.setBounds((int) (cx - iw / 2f), (int) (cy - ih / 2f), (int) (cx + iw / 2f), (int) (cy + ih / 2f));
                        iconDrawable.draw(canvas);
                        if (srcOutPaint == null) {
                            srcOutPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                            srcOutPaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_OUT));
                        }
                        srcOutPaint.setColor(cardBackgroundColor);
                        rect.set(cx - CHOSEN_ICON_R, cy - CHOSEN_ICON_R, cx + CHOSEN_ICON_R, cy + CHOSEN_ICON_R);
                        canvas.drawRoundRect(rect, CHOSEN_ICON_R, CHOSEN_ICON_R, srcOutPaint);
                        canvas.restore();
                    } else {
                        // Ассет ещё не проинициализирован темой (не должно происходить
                        // в норме) — подстраховка тем же приёмом, что был раньше.
                        chosenCirclePaint.setColor(wrongQuizAnswer ? wrongColor : accentColor);
                        canvas.drawCircle(cx, cy, CHOSEN_ICON_R, chosenCirclePaint);
                        drawCheckMark(canvas, cx, cy, CHOSEN_ICON_R * 0.6f);
                    }
                }
            }
        }

        /** Галочка "V" в квадратике — используется только для чекбокса множественного выбора ДО голосования. */
        private void drawCheckMark(Canvas canvas, float cx, float cy, float r) {
            canvas.drawLine(cx - r, cy, cx - r * 0.2f, cy + r * 0.7f, checkMarkPaint);
            canvas.drawLine(cx - r * 0.2f, cy + r * 0.7f, cx + r, cy - r * 0.6f, checkMarkPaint);
        }
    }

    // ------------------------------------------------------------------ DotsIndicator

    private static class DotsIndicator extends View {
        private static final int DOT_SIZE_DP   = 6;
        private static final int DOT_GAP_DP    = 5;
        private final Paint activePaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint inactivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int pageCount   = 0;
        private int currentPage = 0;

        DotsIndicator(Context context, Theme.ResourcesProvider rp) {
            super(context);
            activePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, rp));
            inactivePaint.setColor(0x55808080);
        }

        void setPageCount(int count)   { pageCount = count;   invalidate(); }
        void setCurrentPage(int page)  { currentPage = page;  invalidate(); }

        @Override
        protected void onDraw(Canvas canvas) {
            if (pageCount <= 1) return;
            float dotR  = dp(DOT_SIZE_DP) / 2f;
            float gap   = dp(DOT_GAP_DP);
            float totalW = pageCount * dp(DOT_SIZE_DP) + (pageCount - 1) * gap;
            float startX = (getWidth() - totalW) / 2f + dotR;
            float cy = getHeight() / 2f;
            for (int i = 0; i < pageCount; i++) {
                float cx = startX + i * (dp(DOT_SIZE_DP) + gap);
                canvas.drawCircle(cx, cy, dotR, i == currentPage ? activePaint : inactivePaint);
            }
        }
    }

    // Фикс "реакция без смайлика": обычный TextView умеет показать только юникод-эмодзи
    // (TL_reactionEmoji). Если у канала кастомная эмодзи-реакция (TL_reactionCustomEmoji —
    // это стикер, а не текстовый символ), нужен полноценный AnimatedEmojiDrawable.
    // Эта вьюха — минимальный хост для него: сама ничего не знает про реакции,
    // просто рисует переданный document_id.
    private static class ReactionEmojiView extends View {
        private AnimatedEmojiDrawable drawable;

        ReactionEmojiView(Context context) {
            super(context);
        }

        void setDocumentId(long documentId) {
            if (drawable != null) {
                drawable.removeView(this);
                drawable = null;
            }
            if (documentId != 0) {
                drawable = AnimatedEmojiDrawable.make(UserConfig.selectedAccount, AnimatedEmojiDrawable.CACHE_TYPE_MESSAGES, documentId);
                drawable.addView(this);
            }
            invalidate();
        }

        void recycle() {
            if (drawable != null) {
                drawable.removeView(this);
                drawable = null;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (drawable != null) {
                drawable.setBounds(0, 0, getWidth(), getHeight());
                drawable.draw(canvas);
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            recycle();
        }
    }
}
