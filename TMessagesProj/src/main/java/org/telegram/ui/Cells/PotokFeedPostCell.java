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
import android.graphics.RectF;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
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
        audioTopRow.addView(audioTitleColumn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 10, 0, 0, 0));

        // Строка 1: только название — исполнитель больше НЕ живёт в этой колонке
        // (раньше был здесь, вторым TextView'ом под названием, отсюда и жалоба
        // "во время игры остаётся только название и сразу полоса" — исполнитель просто
        // схлопывался в GONE без анимации и без своего места). Теперь исполнитель —
        // часть строки 2 (audioSecondRow) ниже, наравне с полосой перемотки.
        audioTitleView = new TextView(context);
        audioTitleView.setTextSize(15);
        audioTitleView.setTypeface(AndroidUtilities.bold());
        audioTitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        audioTitleView.setMaxLines(1);
        audioTitleView.setEllipsize(TextUtils.TruncateAt.END);
        audioTitleColumn.addView(audioTitleView);

        // Строка 2 (переключаемая, с анимацией): исполнитель ИЛИ полоса перемотки —
        // 1:1 с ChatMessageCell, где performerLayout и seekBar рисуются на ОДНОЙ и той
        // же Y-координате и кроссфейдятся (alpha + лёгкий scale) друг в друга, вместо
        // того чтобы одно резко исчезало, а другое резко появлялось ниже отдельной
        // строкой. Оба ребёнка лежат в одном FrameLayout один поверх другого;
        // видимость/прозрачность переключается в updateAudioSeekRowVisibility().
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
        // добавляется сбоку — оно теперь отдельная константная строка 3 (см. ниже).
        audioSeekBarView = new AudioSeekBarView(context, resourcesProvider);
        audioWaveformView = new AudioWaveformView(context, resourcesProvider);

        LinearLayout audioSeekRow = new LinearLayout(context);
        audioSeekRow.setOrientation(HORIZONTAL);
        audioSeekRow.setGravity(Gravity.CENTER_VERTICAL);
        audioSeekRow.addView(audioSeekBarView, LayoutHelper.createLinear(0, 24, 1f));
        audioSeekRow.addView(audioWaveformView, LayoutHelper.createLinear(0, 24, 1f));
        this.audioSeekRow = audioSeekRow;

        audioSecondRow = new FrameLayout(context);
        audioColumn.addView(audioSecondRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 24, 54, 4, 8, 0));
        audioSecondRow.addView(audioPerformerView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        audioSecondRow.addView(audioSeekRow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // Строка 3 (константа): "0:00 / 3:45" — 1:1 с ChatMessageCell.durationLayout,
        // которая рисуется на СВОЕЙ отдельной Y-координате (dp(57) в оригинале, ниже
        // строки 2) и видна ВСЕГДА — и до первого тапа play, и во время игры, и на
        // паузе, независимо от activelyPlaying. В оригинале для типа "музыка" (не
        // войс-АУДИО-документ, а именно DOCUMENT_ATTACH_TYPE_MUSIC) в этой строке нет
        // размера файла — только время, поэтому размер файла сюда сознательно не
        // добавлен (раньше в audioStaticInfoView был "0:00 / 2:56  5,9 MB" — это не
        // совпадало с оригиналом, который для музыки размер вообще не показывает).
        audioTimeView = new TextView(context);
        audioTimeView.setTextSize(13);
        audioTimeView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        audioColumn.addView(audioTimeView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 54, 2, 8, 0));


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

    public void setPost(ArrayList<MessageObject> messages, TLRPC.Chat channel) {
        if (messages == null || messages.isEmpty()) return;
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
        // может состоять из сообщения с опросом + отдельных сообщений с фото/видео
        // (у одного TL-сообщения media — это ЛИБО опрос, ЛИБО фото, никогда оба
        // сразу; поэтому медиа поста с опросом физически лежит в соседних
        // сообщениях той же группы, а не в самом опросе).
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

        // Собираем медиа-сообщения из группы (только с фото/видео)
        ArrayList<MessageObject> mediaMessages = new ArrayList<>();
        for (MessageObject mo : messages) {
            if (!mo.isVoice() && !mo.isMusic()
                    && mo.photoThumbs != null && !mo.photoThumbs.isEmpty()) {
                mediaMessages.add(mo);
            }
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

    // ------------------------------------------------------------------ CarouselAdapter

    private class CarouselAdapter extends RecyclerView.Adapter<CarouselAdapter.MediaHolder> {
        private final ArrayList<MessageObject> items = new ArrayList<>();
        private int heightDp = MIN_MEDIA_HEIGHT_DP;

        void setMessages(ArrayList<MessageObject> msgs, int hDp) {
            items.clear();
            items.addAll(msgs);
            heightDp = hDp;
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
            wrapper.addView(downloadPlate, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 6, 6, 0, 0));

            // Оверлей притемнения + кнопки загрузки для фото (аналог downloadPlate,
            // но для фото, а не видео) — во весь размер карточки, поверх фото.
            PhotoDownloadOverlay photoOverlay = new PhotoDownloadOverlay(parent.getContext());
            photoOverlay.setVisibility(GONE);
            wrapper.addView(photoOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            return new MediaHolder(wrapper, img, playIndicator, downloadPlate, photoOverlay);
        }

        @Override
        public void onViewRecycled(MediaHolder holder) {
            super.onViewRecycled(holder);
            // Обязательно отписываемся от DownloadController — иначе при переиспользовании
            // ViewHolder'а (RecyclerView) колбэки о загрузке будут прилетать в "чужую",
            // уже переиспользованную под другое видео ячейку.
            holder.downloadPlate.unbind();
            holder.photoOverlay.unbind();
        }

        @Override
        public void onBindViewHolder(MediaHolder holder, int position) {
            MessageObject mo = items.get(position);
            BackupImageView img = holder.img;

            TLRPC.MessageMedia media = mo.messageOwner != null ? mo.messageOwner.media : null;
            // GIF в Telegram технически хранится как тот же немой зацикленный
            // video/mp4-документ (TL_documentAttributeAnimated) — тот же контейнер,
            // что и обычное видео, поэтому дальше по пайплайну (инлайн-превью,
            // докачка, автовоспроизведение из кэша) обрабатывается идентично.
            // Раньше mo.isGif() нигде не проверялся, и такие посты не считались
            // медиа вообще (см. hasMedia-проверки выше) — GIF просто не отображался.
            boolean isVideo = mo.isVideo() || mo.isGif();

            if (isVideo && media instanceof TLRPC.TL_messageMediaDocument
                    && media.document != null) {
                TLRPC.Document document = media.document;

                TLRPC.PhotoSize currentPhotoObject = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, AndroidUtilities.getPhotoSize());
                TLRPC.PhotoSize currentPhotoObjectThumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 40);
                if (currentPhotoObject == currentPhotoObjectThumb) currentPhotoObjectThumb = null;

                BitmapDrawable strippedThumb = mo.strippedThumb;
                if (strippedThumb != null) currentPhotoObjectThumb = null;

                if (currentPhotoObject != null && (currentPhotoObject.w == 0 || currentPhotoObject.h == 0
                        || currentPhotoObject instanceof TLRPC.TL_photoStrippedSize)) {
                    for (TLRPC.DocumentAttribute attr : document.attributes) {
                        if (attr instanceof TLRPC.TL_documentAttributeVideo) {
                            if (currentPhotoObject instanceof TLRPC.TL_photoStrippedSize) {
                                float scale = Math.max(attr.w, attr.h) / 50.0f;
                                currentPhotoObject.w = (int) (attr.w / scale);
                                currentPhotoObject.h = (int) (attr.h / scale);
                            } else {
                                currentPhotoObject.w = attr.w;
                                currentPhotoObject.h = attr.h;
                            }
                            break;
                        }
                    }
                }

                int pw = currentPhotoObject != null ? currentPhotoObject.w : AndroidUtilities.displaySize.x;
                int ph = currentPhotoObject != null ? currentPhotoObject.h : AndroidUtilities.displaySize.x;
                String currentPhotoFilter = pw + "_" + ph;
                // Фикс "разный блюр на разных постах": здесь раньше был суффикс "_b"
                // (слабый блюр, 1 проход) — тот же баг, что уже чинили для фото (см.
                // "50_50_b2" в фото-ветке ниже). У видео/GIF (эта ветка) суффикс остался
                // "_b" по недосмотру — отсюда и "некоторые посты правильно заблюрены,
                // некоторые слегка": фото уже показывали сильный блюр "_b2", а видео/GIF-
                // превью — слабый "_b". Приведено к тому же сильному "_b2", что и у фото.
                String currentPhotoFilterThumb = currentPhotoObjectThumb != null
                    ? currentPhotoObjectThumb.w + "_" + currentPhotoObjectThumb.h + "_b2" : "b2";

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
                boolean canDecodeFromVideo = !mo.isRepostPreview && fileExists && mo.canStreamVideo();
                // Настройка "Скачивать видео" из меню трёх точек (по умолчанию включена).
                // Если выключена и сам видеофайл ещё не докачан пользователем вручную —
                // НЕ подгружаем даже полноразмерный статичный превью-кадр с сервера сам
                // по себе (это отдельный сетевой запрос за картинкой) — только маленький
                // стрип-thumb, который и так приходит вместе с самим сообщением бесплатно.
                boolean videoAutoload = PotokFeedFragment.isAutoloadVideoEnabled(getContext())
                    && PotokFeedFragment.isSizeOkForVideoAutoload(document.size);
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
                    img.getImageReceiver().setImage(
                        ImageLocation.getForDocument(document), org.telegram.messenger.ImageLoader.AUTOPLAY_FILTER,
                        ImageLocation.getForObject(currentPhotoObject, document), currentPhotoFilter,
                        ImageLocation.getForObject(currentPhotoObjectThumb, document), currentPhotoFilterThumb,
                        strippedThumb, document.size, (String) null, mo, 0
                    );
                    img.getImageReceiver().startAnimation();
                } else if (!videoAutoload && !fileExists) {
                    // Автозагрузка выключена — только стрип-thumb/маленькая миниатюра,
                    // без полноразмерного превью. Тап по кадру (openMediaViewer, см. ниже)
                    // всё равно скачает и покажет видео целиком независимо от этой настройки.
                    img.setImage(
                        currentPhotoObjectThumb != null ? ImageLocation.getForObject(currentPhotoObjectThumb, document) : null, currentPhotoFilterThumb,
                        (ImageLocation) null, (String) null,
                        strippedThumb, (String) null, 0, 0, mo
                    );
                } else if (currentPhotoObjectThumb != null || strippedThumb != null) {
                    // 10-param: mediaLocation, mediaFilter, imageLocation, imageFilter, thumbLocation, thumbFilter, ext, size, cacheType, parentObject
                    img.setImage(
                        ImageLocation.getForObject(currentPhotoObject, document), currentPhotoFilter,
                        ImageLocation.getForObject(currentPhotoObjectThumb, document), currentPhotoFilterThumb,
                        (ImageLocation) null, (String) null,
                        (String) null, 0, 0, mo
                    );
                } else {
                    // 9-param: imageLocation, imageFilter, thumbLocation, thumbFilter, thumb(Drawable), ext, size, cacheType, parentObject
                    img.setImage(
                        ImageLocation.getForObject(currentPhotoObject, document), currentPhotoFilter,
                        (ImageLocation) null, (String) null,
                        strippedThumb, (String) null, 0, 0, mo
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
                holder.downloadPlate.bind(document, mo.currentAccount, () -> {
                    // Файл докачался — перепривязываем ячейку: canDecodeFromVideo теперь
                    // увидит fileExists=true и покажет уже настоящий декодированный кадр.
                    // КРИТИЧНО: этот колбэк прилетает из DownloadController в произвольный
                    // момент — в том числе прямо во время скролла/layout-прохода самой
                    // карусели (пользователь свайпает, пока видео докачивается). Прямой
                    // notifyItemChanged() в такой момент — это классический
                    // IllegalStateException ("Cannot call this method while RecyclerView is
                    // computing a layout or scrolling"), который и был причиной краша:
                    // кадр "подвисал" затемнённым на последнем отрисованном состоянии,
                    // скролл переставал отвечать, дальше приложение вылетало. Откладываем
                    // через post() на следующий цикл отрисовки — к этому моменту текущий
                    // layout-проход уже завершён, вызывать notifyItemChanged() безопасно.
                    carouselView.post(() -> {
                        if (carouselAdapter != null) {
                            notifyItemChanged(bindPosition);
                        }
                    });
                });
            } else {
                holder.playIndicator.setVisibility(GONE);
                holder.downloadPlate.setVisibility(GONE);
                holder.downloadPlate.unbind();
                // Фото — стандартный путь
                ArrayList<TLRPC.PhotoSize> sizes = mo.photoThumbs;
                TLRPC.PhotoSize photoSize = FileLoader.getClosestPhotoSizeWithSize(sizes, 1280, false, null, true);
                if (photoSize == null) photoSize = FileLoader.getClosestPhotoSizeWithSize(sizes, 1280);
                TLRPC.PhotoSize thumbSize = FileLoader.getClosestPhotoSizeWithSize(sizes, 50, false, null, true);
                boolean photoAutoload = PotokFeedFragment.isAutoloadPhotoEnabled(getContext());
                mo.checkMediaExistance(false);
                if (photoAutoload || mo.mediaExists) {
                    // Автозагрузка включена (или файл и так уже реально в кэше) —
                    // грузим/показываем штатным путём ImageReceiver: сразу выставляем
                    // mo.strippedThumb пятым параметром (тот самый клиентский блюр-
                    // плейсхолдер, приезжающий прямо с сообщением, без сети) — пока
                    // грузится thumbSize/photoSize, видно ЕГО, а не пустоту. Отдельная
                    // кнопка загрузки тут не нужна и не должна показываться — фото и
                    // так грузится само, поэтому оверлей принудительно скрываем/
                    // отвязываем (раньше он показывался ВСЕГДА, даже когда автозагрузка
                    // уже сама тащит фото — отсюда и жалоба "фото чёткое, а кнопка есть").
                    img.setImage(
                        ImageLocation.getForObject(photoSize, mo.photoThumbsObject), (String) null,
                        thumbSize != null ? ImageLocation.getForObject(thumbSize, mo.photoThumbsObject) : null, "50_50_b2",
                        mo.strippedThumb, (String) null, 0, 0, mo
                    );
                    holder.photoOverlay.setVisibility(GONE);
                    holder.photoOverlay.unbind();
                } else {
                    // Автозагрузка выключена и файла ещё нет — грузим ТОЛЬКО маленький
                    // thumbSize ("50_50", копеечный по размеру, качается независимо от
                    // настройки автозагрузки полного размера — так же, как в самом
                    // Telegram: превью всегда бесплатное, ограничивается только full-size).
                    // mo.strippedThumb — мгновенный fallback ДРАВЕБЛ, показывается, пока
                    // даже этот маленький thumbSize не успел загрузиться. Раньше здесь обе
                    // ImageLocation были null — ImageReceiver в таком случае, похоже, не
                    // рисует вообще ничего (даже переданный thumb Drawable), отсюда и была
                    // пустота вместо блюра.
                    img.setImage(
                        (ImageLocation) null, (String) null,
                        thumbSize != null ? ImageLocation.getForObject(thumbSize, mo.photoThumbsObject) : null, "50_50_b2",
                        mo.strippedThumb, (String) null, 0, 0, mo
                    );
                    final int photoBindPosition = position;
                    holder.photoOverlay.bind(photoSize, mo.photoThumbsObject, mo.currentAccount, () -> {
                        carouselView.post(() -> {
                            if (carouselAdapter != null) {
                                notifyItemChanged(photoBindPosition);
                            }
                        });
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
        
