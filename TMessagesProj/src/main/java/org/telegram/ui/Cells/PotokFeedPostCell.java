package org.telegram.ui.Cells;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Paint;
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
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.PhotoViewer;

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

    // --- Футер ---
    private final ImageView viewsIcon;
    private final TextView viewsView;
    private final TextView reactionView;

    private MessageObject currentMessage;
    private ArrayList<MessageObject> currentMessages;
    private android.app.Activity parentActivity;

    public void setParentActivity(android.app.Activity activity) {
        parentActivity = activity;
    }

    public PotokFeedPostCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        setOrientation(VERTICAL);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));

        // --- Шапка ---
        LinearLayout headerRow = new LinearLayout(context);
        headerRow.setOrientation(HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        addView(headerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 12, 12, 0));

        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(dp(18));
        headerRow.addView(avatarView, LayoutHelper.createLinear(36, 36));

        LinearLayout titleColumn = new LinearLayout(context);
        titleColumn.setOrientation(VERTICAL);
        headerRow.addView(titleColumn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 10, 0, 0, 0));

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

        // --- Текст ---
        textView = new TextView(context);
        textView.setTextSize(15);
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        textView.setMaxLines(MAX_TEXT_LINES);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textView.setLineSpacing(dp(2), 1f);
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
        addView(carouselView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, MIN_MEDIA_HEIGHT_DP, 0, 10, 0, 0));

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
        audioCell.setNeedPlayMessageListener(messageObject -> {
            MediaController.getInstance().setPlaylist(null, messageObject, 0);
            return MediaController.getInstance().playMessage(messageObject);
        });

        // --- Футер ---
        LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        addView(footer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 10, 12, 12));

        viewsIcon = new ImageView(context);
        viewsIcon.setImageResource(org.telegram.messenger.R.drawable.msg_views);
        viewsIcon.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        footer.addView(viewsIcon, LayoutHelper.createLinear(16, 16, 0, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

        viewsView = new TextView(context);
        viewsView.setTextSize(13);
        viewsView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        footer.addView(viewsView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, Gravity.CENTER_VERTICAL, 0, 0, 16, 0));

        reactionView = new TextView(context);
        reactionView.setTextSize(13);
        reactionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        footer.addView(reactionView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, Gravity.CENTER_VERTICAL));

        // --- Разделитель ---
        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_graySection, resourcesProvider));
        addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));
    }

    // ------------------------------------------------------------------ setPost

    public void setPost(ArrayList<MessageObject> messages, TLRPC.Chat channel) {
        if (messages == null || messages.isEmpty()) return;
        currentMessages = messages;
        MessageObject messageObject = messages.get(0);
        currentMessage = messageObject;

        // Сброс состояния при переиспользовании
        isExpanded = false;
        textView.setMaxLines(MAX_TEXT_LINES);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        expandButton.setText("ещё");
        expandButton.setVisibility(GONE);

        // Шапка
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(channel);
        avatarView.setForUserOrChat(channel, avatarDrawable);
        titleView.setText(channel != null ? channel.title : "");
        timeView.setText(LocaleController.formatDate(messageObject.messageOwner.date));

        // Текст / caption
        CharSequence caption = null;
        for (MessageObject mo : messages) {
            if (!TextUtils.isEmpty(mo.caption)) { caption = mo.caption; break; }
        }
        if (TextUtils.isEmpty(caption) && messages.size() == 1 && messageObject.type == MessageObject.TYPE_TEXT) {
            caption = messageObject.messageText;
        }
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
            audioCell.setMessageObject(messageObject, false);
            if (audioCell.getParent() == null) {
                audioContainer.addView(audioCell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }
            audioContainer.setVisibility(VISIBLE);
        } else if (!mediaMessages.isEmpty()) {
            if (audioCell.getParent() != null) audioContainer.removeView(audioCell);
            audioContainer.setVisibility(GONE);

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
        }

        // Футер
        int views = messageObject.messageOwner != null ? messageObject.messageOwner.views : 0;
        viewsView.setText(views > 0 ? LocaleController.formatShortNumber(views, null) : "0");

        TLRPC.ReactionCount topReaction = getTopReaction(messageObject);
        if (topReaction != null) {
            String emoji = "";
            if (topReaction.reaction instanceof TLRPC.TL_reactionEmoji) {
                emoji = ((TLRPC.TL_reactionEmoji) topReaction.reaction).emoticon;
            }
            reactionView.setText(emoji + " " + topReaction.count);
            reactionView.setVisibility(VISIBLE);
        } else {
            reactionView.setVisibility(GONE);
        }
    }

    private void hideCarousel() {
        carouselView.setVisibility(GONE);
        dotsIndicator.setVisibility(GONE);
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
            BackupImageView img = new BackupImageView(parent.getContext());
            img.setRoundRadius(dp(8));
            // Каждый слайд занимает полную ширину карусели
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.MATCH_PARENT
            );
            img.setLayoutParams(lp);
            return new MediaHolder(img);
        }

        @Override
        public void onBindViewHolder(MediaHolder holder, int position) {
            MessageObject mo = items.get(position);
            BackupImageView img = (BackupImageView) holder.itemView;

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

                // Точный паттерн ChatMessageCell (DOCUMENT_ATTACH_TYPE_VIDEO, автоплей-ветка):
                // mediaLocation = сам видеодокумент → ImageReceiver декодирует реальный кадр
                // через стриминг (canStreamVideo), а не довольствуется маленьким серверным
                // thumbnail — отсюда чёткость, как в самом чате. currentPhotoObject остаётся
                // как thumb на время, пока кадр из видео не декодирован.
                boolean canDecodeFromVideo = !mo.isRepostPreview && mo.canStreamVideo();
                if (canDecodeFromVideo) {
                    img.getImageReceiver().setAllowDecodeSingleFrame(true);
                    img.getImageReceiver().setAllowStartAnimation(false);
                    img.setImage(
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
                        (ImageLocation) null, null,
                        null, 0, 0, mo
                    );
                } else {
                    // 9-param: imageLocation, imageFilter, thumbLocation, thumbFilter, thumb(Drawable), ext, size, cacheType, parentObject
                    img.setImage(
                        ImageLocation.getForObject(currentPhotoObject, document), currentPhotoFilter,
                        (ImageLocation) null, null,
                        strippedThumb, null, 0, 0, mo
                    );
                }
            } else {
                // Фото — стандартный путь
                ArrayList<TLRPC.PhotoSize> sizes = mo.photoThumbs;
                TLRPC.PhotoSize photoSize = FileLoader.getClosestPhotoSizeWithSize(sizes, 1280, false, null, true);
                if (photoSize == null) photoSize = FileLoader.getClosestPhotoSizeWithSize(sizes, 1280);
                TLRPC.PhotoSize thumbSize = FileLoader.getClosestPhotoSizeWithSize(sizes, 50, false, null, true);
                img.setImage(
                    ImageLocation.getForObject(photoSize, mo.photoThumbsObject), null,
                    thumbSize != null ? ImageLocation.getForObject(thumbSize, mo.photoThumbsObject) : null, "50_50",
                    null, mo
                );
            }

            final int idx = position;
            img.setOnClickListener(v -> openMediaViewer(mo, idx, items));
        }

        @Override public int getItemCount() { return items.size(); }

        class MediaHolder extends RecyclerView.ViewHolder {
            MediaHolder(View v) { super(v); }
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
}
