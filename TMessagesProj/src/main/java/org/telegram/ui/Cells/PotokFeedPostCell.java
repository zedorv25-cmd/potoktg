package org.telegram.ui.Cells;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.Paint;
import android.graphics.Path;
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
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MediaActionDrawable;
import org.telegram.ui.Components.RadialProgress2;
import org.telegram.ui.Components.SeekBar;
import org.telegram.ui.PhotoViewer;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
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
    private final SharedAudioCell audioCell;
    private final FrameLayout audioContainer;
    private final AudioSeekBarView audioSeekBarView;
    private final TextView audioTimeView;
    private final LinearLayout audioSeekRow;

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
        audioContainer = new FrameLayout(context);
        addView(audioContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, 10, 8, 0));
        audioCell = new SharedAudioCell(context, resourcesProvider);
        // Без этого listener needPlayMessage() возвращает false и воспроизведение не запускается
        audioCell.setCheckForButtonPress(true);
        audioCell.setNeedPlayMessageListener(messageObject -> {
            boolean started = MediaController.getInstance().playMessage(messageObject);
            // Показываем ползунок сразу в момент запуска, не дожидаясь первого тика
            // messagePlayingProgressDidChanged — иначе на долю секунды видно
            // play-кнопку без сикбара под ней.
            if (started && currentMessage == messageObject) {
                updateAudioSeekVisibility(messageObject);
            }
            return started;
        });

        // Ползунок прогресса воспроизведения — SharedAudioCell сам по себе его не рисует
        // (это просто строка play/pause + длительность), поэтому добавляем отдельным View
        // под ним, по тому же паттерну, что и ChatMessageCell (SeekBar + NotificationCenter
        // messagePlayingProgressDidChanged для обновления, см. setAudioMessageObjectForSeek).
        audioSeekBarView = new AudioSeekBarView(context, resourcesProvider);
        audioTimeView = new TextView(context);
        audioTimeView.setTextSize(12);
        audioTimeView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));

        LinearLayout audioSeekRow = new LinearLayout(context);
        audioSeekRow.setOrientation(HORIZONTAL);
        audioSeekRow.setGravity(Gravity.CENTER_VERTICAL);
        audioSeekRow.setVisibility(GONE);
        addView(audioSeekRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 8, 0));
        audioSeekRow.addView(audioSeekBarView, LayoutHelper.createLinear(0, 24, 1f));
        audioSeekRow.addView(audioTimeView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
        this.audioSeekRow = audioSeekRow;

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

        // Собираем медиа-сообщения из группы (только с фото/видео)
        ArrayList<MessageObject> mediaMessages = new ArrayList<>();
        for (MessageObject mo : messages) {
            if (!mo.isVoice() && !mo.isMusic()
                    && mo.photoThumbs != null && !mo.photoThumbs.isEmpty()) {
                mediaMessages.add(mo);
            }
        }

        if (isVoiceOrMusic) {
            hideCarousel();
            if (audioCell.getParent() == null) {
                audioContainer.addView(audioCell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }
            audioContainer.setVisibility(VISIBLE);
            audioCell.setMessageObject(messageObject, false);
            // Раньше audioSeekRow показывался безусловно при каждом setPost — то есть
            // ползунок был виден даже для аудио, которое никто не запускал. Теперь
            // видимость зависит от того, реально ли это сообщение сейчас в плеере
            // (играет или на паузе) — см. updateAudioSeekVisibility().
            audioSeekBarView.setMessageObject(messageObject);
            updateAudioSeekVisibility(messageObject);
        } else if (!mediaMessages.isEmpty()) {
            if (audioCell.getParent() != null) audioContainer.removeView(audioCell);
            audioContainer.setVisibility(GONE);
            audioSeekRow.setVisibility(GONE);
            audioSeekBarView.setMessageObject(null);

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
            if (audioCell.getParent() != null) audioContainer.removeView(audioCell);
            audioContainer.setVisibility(GONE);
            audioSeekRow.setVisibility(GONE);
            audioSeekBarView.setMessageObject(null);
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
     * Ползунок прогресса должен быть виден только когда ЭТО аудио реально
     * выбрано текущим плеером (играет или стоит на паузе) — isPlayingMessage
     * остаётся true и на паузе, что нам и нужно: пользователь поставил
     * на паузу, но прогресс должен остаться виден, а не исчезнуть.
     * Если плеер не трогали или играет другой трек — сикбара не должно быть,
     * только строка play/pause из SharedAudioCell.
     */
    private void updateAudioSeekVisibility(MessageObject mo) {
        boolean shouldShow = mo != null && MediaController.getInstance().isPlayingMessage(mo);
        audioSeekRow.setVisibility(shouldShow ? VISIBLE : GONE);
        if (shouldShow) {
            updateAudioTimeText(mo);
        }
    }

    private void updateAudioTimeText(MessageObject mo) {
        int durationSec = (int) mo.getDuration();
        int playedSec = MediaController.getInstance().isPlayingMessage(mo)
            ? mo.audioProgressSec : (int) (mo.audioProgress * durationSec);
        audioTimeView.setText(AndroidUtilities.formatShortDuration(playedSec, durationSec));
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
            updateAudioSeekVisibility(currentMessage);
        }
        audioSeekBarView.updateProgress();
        updateAudioTimeText(currentMessage);
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
        if (all != null && all.size() > 1) {
            // Группа из нескольких медиа (альбом) — открываем со списком и индексом,
            // независимо от того видео это или фото, чтобы PhotoViewer мог свайпать
            // между элементами и правильно инициализировать видеоплеер в контексте группы.
            PhotoViewer.getInstance().openPhoto(all, index, dialogId, 0L, 0L, new PhotoViewer.EmptyPhotoViewerProvider());
        } else {
            // Одиночное медиа — старая логика подходит, отдельный путь для видео не нужен
            PhotoViewer.getInstance().openPhoto(mo, dialogId, 0, 0, new PhotoViewer.EmptyPhotoViewerProvider(), true);
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

        // Скачать медиа (фото/видео/аудио поста)
        ArrayList<MessageObject> mediaToSave = new ArrayList<>();
        for (MessageObject mo : groupMessages) {
            boolean hasMedia = mo.isVoice() || mo.isMusic() || mo.isVideo()
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

            // 2) Маленькая кнопка загрузки в кэш — левый верхний угол, БЕЗ фонового
            //    круга, отступ 8dp от края (videoRadialProgress в ChatMessageCell).
            //    Стрелка "скачать" -> тап -> FileLoader.loadFile(), во время загрузки
            //    кольцо прогресса + крестик отмены, после успешной загрузки иконка
            //    пропадает совсем.
            VideoDownloadOverlay downloadOverlay = new VideoDownloadOverlay(parent.getContext());
            downloadOverlay.setVisibility(GONE);
            wrapper.addView(downloadOverlay, LayoutHelper.createFrame(30, 30, Gravity.LEFT | Gravity.TOP, 4, 28, 0, 0));

            // Бейдж длительности в левом верхнем углу — как в оригинальном Telegram
            // и в скриншоте из Plus Messenger, который прислал пользователь: тёмная
            // полупрозрачная плашка с текстом "м:сс".
            TextView durationBadge = new TextView(parent.getContext());
            durationBadge.setTextColor(0xFFFFFFFF);
            durationBadge.setTextSize(12);
            durationBadge.setTypeface(AndroidUtilities.bold());
            durationBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
            android.graphics.drawable.GradientDrawable badgeBg = new android.graphics.drawable.GradientDrawable();
            badgeBg.setColor(0x99000000);
            badgeBg.setCornerRadius(dp(4));
            durationBadge.setBackground(badgeBg);
            durationBadge.setVisibility(GONE);
            wrapper.addView(durationBadge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 6, 6, 0, 0));

            return new MediaHolder(wrapper, img, playIndicator, downloadOverlay, durationBadge);
        }

        @Override
        public void onViewRecycled(MediaHolder holder) {
            super.onViewRecycled(holder);
            // Обязательно отписываемся от DownloadController — иначе при переиспользовании
            // ViewHolder'а (RecyclerView) колбэки о загрузке будут прилетать в "чужую",
            // уже переиспользованную под другое видео ячейку.
            holder.downloadOverlay.unbind();
        }

        @Override
        public void onBindViewHolder(MediaHolder holder, int position) {
            MessageObject mo = items.get(position);
            BackupImageView img = holder.img;

            TLRPC.MessageMedia media = mo.messageOwner != null ? mo.messageOwner.media : null;
            boolean isVideo = mo.isVideo();

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
                String currentPhotoFilterThumb = currentPhotoObjectThumb != null
                    ? currentPhotoObjectThumb.w + "_" + currentPhotoObjectThumb.h + "_b" : "b1";

                // ГЛАВНОЕ ИЗМЕНЕНИЕ: видео больше НЕ подгружается/не стримится само по
                // себе при показе поста. canDecodeFromVideo (декодирование реального
                // кадра через стриминг) теперь разрешено ТОЛЬКО если файл уже реально
                // лежит в кэше (пользователь явно нажал кнопку загрузки раньше) — иначе
                // просто статичный превью-thumbnail с сервера, без единого байта самого
                // видео.
                java.io.File cacheFile = FileLoader.getInstance(mo.currentAccount).getPathToAttach(document, true);
                boolean fileExists = cacheFile != null && cacheFile.exists();
                boolean canDecodeFromVideo = !mo.isRepostPreview && fileExists && mo.canStreamVideo();
                if (canDecodeFromVideo) {
                    img.getImageReceiver().setAllowDecodeSingleFrame(true);
                    img.getImageReceiver().setAllowStartAnimation(false);
                    img.getImageReceiver().setImage(
                        ImageLocation.getForDocument(document), org.telegram.messenger.ImageLoader.AUTOPLAY_FILTER_NONLOOP,
                        ImageLocation.getForObject(currentPhotoObject, document), currentPhotoFilter,
                        ImageLocation.getForObject(currentPhotoObjectThumb, document), currentPhotoFilterThumb,
                        strippedThumb, document.size, (String) null, mo, 0
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

                // Бейдж длительности "м:сс" в левом верхнем углу (как в оригинальном
                // Telegram и в присланном скрине из Plus Messenger).
                long durationSec = 0;
                for (TLRPC.DocumentAttribute attr : document.attributes) {
                    if (attr instanceof TLRPC.TL_documentAttributeVideo) {
                        durationSec = (long) attr.duration;
                        break;
                    }
                }
                if (durationSec > 0) {
                    holder.durationBadge.setText(AndroidUtilities.formatShortDuration((int) durationSec));
                    holder.durationBadge.setVisibility(VISIBLE);
                } else {
                    holder.durationBadge.setVisibility(GONE);
                }

                holder.playIndicator.setVisibility(VISIBLE);
                holder.downloadOverlay.setVisibility(VISIBLE);
                final int bindPosition = position;
                holder.downloadOverlay.bind(document, mo.currentAccount, fileExists, () -> {
                    // Файл докачался — перепривязываем ячейку: canDecodeFromVideo теперь
                    // увидит fileExists=true и покажет уже настоящий декодированный кадр.
                    notifyItemChanged(bindPosition);
                });
            } else {
                holder.playIndicator.setVisibility(GONE);
                holder.downloadOverlay.setVisibility(GONE);
                holder.downloadOverlay.unbind();
                holder.durationBadge.setVisibility(GONE);
                // Фото — стандартный путь
                ArrayList<TLRPC.PhotoSize> sizes = mo.photoThumbs;
                TLRPC.PhotoSize photoSize = FileLoader.getClosestPhotoSizeWithSize(sizes, 1280, false, null, true);
                if (photoSize == null) photoSize = FileLoader.getClosestPhotoSizeWithSize(sizes, 1280);
                TLRPC.PhotoSize thumbSize = FileLoader.getClosestPhotoSizeWithSize(sizes, 50, false, null, true);
                img.setImage(
                    ImageLocation.getForObject(photoSize, mo.photoThumbsObject), (String) null,
                    thumbSize != null ? ImageLocation.getForObject(thumbSize, mo.photoThumbsObject) : null, "50_50",
                    (Drawable) null, (String) null, 0, 0, mo
                );
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
            final VideoDownloadOverlay downloadOverlay;
            final TextView durationBadge;
            MediaHolder(View wrapper, BackupImageView img, PlayIndicatorView playIndicator, VideoDownloadOverlay downloadOverlay, TextView durationBadge) {
                super(wrapper);
                this.img = img;
                this.playIndicator = playIndicator;
                this.downloadOverlay = downloadOverlay;
                this.durationBadge = durationBadge;
            }
        }
    }

    // ------------------------------------------------------------------ VideoDownloadOverlay
    /**
     * Кнопка загрузки видео в кэш — та же механика, что в оригинальном Telegram
     * (см. ContextLinkCell/ChatMessageCell): RadialProgress2 рисует круг с иконкой,
     * которая меняется по факту наличия файла и по колбэкам DownloadController
     * (загрузка / прогресс / готово). Пока файла нет — иконка "скачать", тап
     * запускает FileLoader.loadFile(...). Во время загрузки — кольцо прогресса
     * и крестик отмены. Когда файл скачан — колбэк onReady сообщает наверх
     * (PotokFeedPostCell перепривязывает ячейку, чтобы показать декодированный кадр).
     */
    private static class VideoDownloadOverlay extends View implements DownloadController.FileDownloadProgressListener {
        private final RadialProgress2 radialProgress;
        private final int TAG;
        private TLRPC.Document document;
        private int currentAccount;
        private String fileName;
        private Runnable onReady;
        private int buttonState; // -1 = скачан/ничего не показываем, 1 = грузится, 2 = скачать

        VideoDownloadOverlay(Context context) {
            super(context);
            radialProgress = new RadialProgress2(this);
            radialProgress.setColorKeys(Theme.key_chat_mediaLoaderPhoto, Theme.key_chat_mediaLoaderPhotoSelected,
                    Theme.key_chat_mediaLoaderPhotoIcon, Theme.key_chat_mediaLoaderPhotoIconSelected);
            // Без фонового круга и маленького радиуса — точно как videoRadialProgress
            // в оригинальном ChatMessageCell (там setDrawBackground(false) +
            // setCircleRadius(dp(15))), это отдельная маленькая кнопка в углу, а не
            // большая кнопка по центру.
            radialProgress.setDrawBackground(false);
            radialProgress.setCircleRadius(dp(15));
            TAG = DownloadController.getInstance(UserConfig.selectedAccount).generateObserverTag();
            setOnClickListener(v -> onClick());
        }

        void bind(TLRPC.Document doc, int account, boolean fileExists, Runnable onReadyCallback) {
            unbind();
            document = doc;
            currentAccount = account;
            onReady = onReadyCallback;
            fileName = FileLoader.getAttachFileName(doc);
            updateState(false);
        }

        void unbind() {
            if (fileName != null) {
                DownloadController.getInstance(currentAccount).removeLoadingFileObserver(this);
            }
            document = null;
            fileName = null;
            onReady = null;
        }

        private void updateState(boolean animated) {
            if (document == null || fileName == null) {
                return;
            }
            java.io.File cacheFile = FileLoader.getInstance(currentAccount).getPathToAttach(document, true);
            boolean fileExists = cacheFile != null && cacheFile.exists();
            boolean isLoading = FileLoader.getInstance(currentAccount).isLoadingFile(fileName);
            if (fileExists) {
                DownloadController.getInstance(currentAccount).removeLoadingFileObserver(this);
                buttonState = -1;
                setVisibility(GONE);
                if (onReady != null) {
                    onReady.run();
                }
            } else {
                setVisibility(VISIBLE);
                DownloadController.getInstance(currentAccount).addLoadingFileObserver(fileName, this);
                if (isLoading) {
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
            if (document == null) return;
            if (buttonState == 2) {
                // Тап по стрелке — запускаем реальную загрузку в кэш, ровно как жмут
                // кнопку загрузки в самом Telegram/Plus Messenger.
                FileLoader.getInstance(currentAccount).loadFile(document, null, FileLoader.PRIORITY_NORMAL, 0);
                updateState(true);
            } else if (buttonState == 1) {
                // Тап по крестику во время загрузки — отмена.
                FileLoader.getInstance(currentAccount).cancelLoadFile(document);
                updateState(true);
            }
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
            boolean result = seekBar.onTouch(event.getAction(), event.getX(), event.getY());
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
