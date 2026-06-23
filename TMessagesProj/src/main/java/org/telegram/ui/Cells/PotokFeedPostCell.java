package org.telegram.ui.Cells;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.PhotoViewer;

import java.util.ArrayList;

/**
 * Карточка поста в Ленте — этап 1.
 * Шапка -> текст (полный, без обрезки) -> медиа (карусель фото/видео ИЛИ аудио) -> футер.
 * Медиа всегда рендерится через карусель (mediaPager); для одного медиа это просто
 * нескроллящаяся "карусель из одного слайда" — единая логика без частных случаев.
 * Высота медиа считается по соотношению сторон ПЕРВОГО слайда (как в Instagram) — без обрезки
 * для одиночного медиа; для альбомов остальные слайды могут немного обрезаться под эту высоту,
 * если их соотношение сторон отличается (так же ведёт себя Instagram-карусель).
 * Полноэкранный просмотр — родной PhotoViewer Telegram, с поддержкой свайпа между медиа альбома.
 */
public class PotokFeedPostCell extends LinearLayout {

    private static final int MIN_MEDIA_HEIGHT_DP = 140;

    private final BackupImageView avatarView;
    private final TextView titleView;
    private final TextView timeView;
    private final TextView textView;
    private final FrameLayout mediaContainer;
    private final RecyclerView mediaPager;
    private final MediaPagerAdapter mediaPagerAdapter;
    private final LinearLayout dotsContainer;
    private final SharedAudioCell audioCell;
    private final TextView viewsView;
    private final TextView reactionView;
    private final ImageView viewsIcon;

    private ArrayList<MessageObject> currentMessages;
    private MessageObject currentAudioMessage;
    private TLRPC.Chat currentChannel;
    private android.app.Activity parentActivity;

    public void setParentActivity(android.app.Activity activity) {
        parentActivity = activity;
    }

    public PotokFeedPostCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        setOrientation(VERTICAL);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));

        // --- Шапка: аватар + (название/время) ---
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

        // --- Текст поста: показывается ПОЛНОСТЬЮ, без обрезки (раскрытие "ещё" — отдельный этап) ---
        textView = new TextView(context);
        textView.setTextSize(15);
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        textView.setLineSpacing(dp(2), 1f);
        addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 8, 12, 0));

        // --- Медиа: карусель во всю ширину экрана, без боковых отступов и без скругления ---
        mediaContainer = new FrameLayout(context);
        addView(mediaContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, MIN_MEDIA_HEIGHT_DP, 0, 10, 0, 0));

        mediaPager = new RecyclerView(context);
        mediaPager.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        mediaPagerAdapter = new MediaPagerAdapter();
        mediaPager.setAdapter(mediaPagerAdapter);
        new PagerSnapHelper().attachToRecyclerView(mediaPager);
        mediaPager.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    updateActiveDot();
                }
            }
        });
        mediaContainer.addView(mediaPager, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        dotsContainer = new LinearLayout(context);
        dotsContainer.setOrientation(HORIZONTAL);
        dotsContainer.setVisibility(GONE);
        mediaContainer.addView(dotsContainer, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 8));

        // --- Аудио: готовая ячейка Telegram (play/pause, длительность, прогресс) ---
        audioCell = new SharedAudioCell(context, resourcesProvider);
        audioCell.setVisibility(GONE);
        addView(audioCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, 10, 8, 0));
        audioCell.setOnClickListener(v -> {
            if (currentAudioMessage != null) {
                MediaController.getInstance().playMessage(currentAudioMessage);
            }
        });

        // --- Футер: просмотры + топ-реакция ---
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

        // тонкий разделитель между карточками
        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider, resourcesProvider));
        addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));
    }

    public void setPost(ArrayList<MessageObject> messages, TLRPC.Chat channel) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        currentMessages = messages;
        currentChannel = channel;
        MessageObject primary = messages.get(0);

        AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(channel);
        avatarView.setForUserOrChat(channel, avatarDrawable);

        titleView.setText(channel != null ? channel.title : "");
        timeView.setText(LocaleController.formatDate(primary.messageOwner.date));

        // подпись — первая ненулевая среди сообщений альбома, либо messageText, если пост чисто текстовый
        CharSequence caption = null;
        for (MessageObject mo : messages) {
            if (!TextUtils.isEmpty(mo.caption)) {
                caption = mo.caption;
                break;
            }
        }
        if (caption == null && primary.type == MessageObject.TYPE_TEXT) {
            caption = primary.messageText;
        }
        if (TextUtils.isEmpty(caption)) {
            textView.setVisibility(GONE);
        } else {
            textView.setVisibility(VISIBLE);
            if (caption instanceof android.text.Spannable) {
                AndroidUtilities.addLinksSafe((android.text.Spannable) caption, android.text.util.Linkify.WEB_URLS, false, true);
            } else {
                android.text.SpannableString spannable = new android.text.SpannableString(caption);
                AndroidUtilities.addLinksSafe(spannable, android.text.util.Linkify.WEB_URLS, false, true);
                caption = spannable;
            }
            textView.setText(caption);
            textView.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            textView.setLinksClickable(true);
        }

        boolean isVoiceOrMusic = primary.isVoice() || primary.isMusic();
        if (isVoiceOrMusic) {
            mediaContainer.setVisibility(GONE);
            dotsContainer.setVisibility(GONE);
            currentAudioMessage = primary;
            audioCell.setMessageObject(primary, false);
            audioCell.setVisibility(VISIBLE);
        } else {
            audioCell.setVisibility(GONE);

            ArrayList<MessageObject> mediaSlides = new ArrayList<>();
            for (MessageObject mo : messages) {
                if (mo.photoThumbs != null && !mo.photoThumbs.isEmpty()) {
                    mediaSlides.add(mo);
                }
            }

            if (mediaSlides.isEmpty()) {
                mediaContainer.setVisibility(GONE);
                dotsContainer.setVisibility(GONE);
            } else {
                mediaContainer.setVisibility(VISIBLE);

                // высота — по соотношению сторон ПЕРВОГО медиа, без потолка (как в самом канале)
                MessageObject firstSlide = mediaSlides.get(0);
                TLRPC.PhotoSize firstSize = FileLoader.getClosestPhotoSizeWithSize(firstSlide.photoThumbs, AndroidUtilities.getPhotoSize());
                int w = firstSize != null ? firstSize.w : 0;
                int h = firstSize != null ? firstSize.h : 0;
                int mediaHeight = MIN_MEDIA_HEIGHT_DP;
                if (w > 0 && h > 0) {
                    int screenWidthDp = (int) (AndroidUtilities.displaySize.x / AndroidUtilities.density);
                    mediaHeight = Math.max(MIN_MEDIA_HEIGHT_DP, Math.round(screenWidthDp * (h / (float) w)));
                }
                LayoutParams containerParams = (LayoutParams) mediaContainer.getLayoutParams();
                containerParams.height = dp(mediaHeight);
                mediaContainer.setLayoutParams(containerParams);

                mediaPagerAdapter.setSlides(mediaSlides);
                mediaPager.scrollToPosition(0);
                rebuildDots(mediaSlides.size());
            }
        }

        int views = primary.messageOwner != null ? primary.messageOwner.views : 0;
        viewsView.setText(views > 0 ? LocaleController.formatShortNumber(views, null) : "0");

        TLRPC.ReactionCount topReaction = getTopReaction(primary);
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

    private void rebuildDots(int count) {
        dotsContainer.removeAllViews();
        if (count <= 1) {
            dotsContainer.setVisibility(GONE);
            return;
        }
        dotsContainer.setVisibility(VISIBLE);
        for (int i = 0; i < count; i++) {
            View dot = new View(getContext());
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(0xFFFFFFFF);
            dot.setBackground(shape);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(6), dp(6));
            lp.leftMargin = dp(3);
            lp.rightMargin = dp(3);
            dot.setLayoutParams(lp);
            dot.setAlpha(i == 0 ? 1f : 0.4f);
            dotsContainer.addView(dot);
        }
    }

    private void updateActiveDot() {
        RecyclerView.LayoutManager lm = mediaPager.getLayoutManager();
        if (!(lm instanceof LinearLayoutManager)) {
            return;
        }
        int position = ((LinearLayoutManager) lm).findFirstVisibleItemPosition();
        for (int i = 0; i < dotsContainer.getChildCount(); i++) {
            dotsContainer.getChildAt(i).setAlpha(i == position ? 1f : 0.4f);
        }
    }

    private void openMediaViewer(int index) {
        if (currentMessages == null || currentMessages.isEmpty() || parentActivity == null || index < 0) {
            return;
        }
        PhotoViewer.getInstance().setParentActivity(parentActivity);
        long dialogId = currentChannel != null ? -currentChannel.id : 0;
        PhotoViewer.getInstance().openPhoto(currentMessages, index, dialogId, 0, 0, photoViewerProvider);
    }

    private final PhotoViewer.PhotoViewerProvider photoViewerProvider = new PhotoViewer.EmptyPhotoViewerProvider() {
        @Override
        public PhotoViewer.PlaceProviderObject getPlaceForPhoto(MessageObject messageObject, TLRPC.FileLocation fileLocation, int index, boolean needPreview, boolean closing) {
            if (currentMessages == null || index < 0 || index >= currentMessages.size() || messageObject != currentMessages.get(index)) {
                return null;
            }
            RecyclerView.LayoutManager lm = mediaPager.getLayoutManager();
            View child = lm != null ? lm.findViewByPosition(index) : null;
            BackupImageView slideImageView = findImageView(child);
            if (slideImageView == null) {
                return null;
            }
            int[] coords = new int[2];
            slideImageView.getLocationInWindow(coords);
            PhotoViewer.PlaceProviderObject object = new PhotoViewer.PlaceProviderObject();
            object.viewX = coords[0];
            object.viewY = coords[1];
            object.parentView = slideImageView;
            object.imageReceiver = slideImageView.getImageReceiver();
            object.dialogId = currentChannel != null ? -currentChannel.id : 0;
            return object;
        }
    };

    private static BackupImageView findImageView(View container) {
        if (!(container instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) container;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof BackupImageView) {
                return (BackupImageView) child;
            }
        }
        return null;
    }

    private class MediaPagerAdapter extends RecyclerView.Adapter<MediaSlideHolder> {
        private final ArrayList<MessageObject> slides = new ArrayList<>();

        void setSlides(ArrayList<MessageObject> newSlides) {
            slides.clear();
            slides.addAll(newSlides);
            notifyDataSetChanged();
        }

        @Override
        public MediaSlideHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            FrameLayout slideContainer = new FrameLayout(context);
            slideContainer.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            BackupImageView imageView = new BackupImageView(context);
            slideContainer.addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            ImageView playIcon = new ImageView(context);
            playIcon.setImageDrawable(buildPlayDrawable());
            slideContainer.addView(playIcon, LayoutHelper.createFrame(48, 48, Gravity.CENTER));

            TextView durationView = new TextView(context);
            durationView.setTextColor(0xFFFFFFFF);
            durationView.setTextSize(12);
            durationView.setBackgroundColor(0x66000000);
            durationView.setPadding(dp(6), dp(2), dp(6), dp(2));
            slideContainer.addView(durationView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 8, 8));

            MediaSlideHolder holder = new MediaSlideHolder(slideContainer, imageView, playIcon, durationView);
            slideContainer.setOnClickListener(v -> openMediaViewer(holder.getAdapterPosition()));
            return holder;
        }

        @Override
        public void onBindViewHolder(MediaSlideHolder holder, int position) {
            MessageObject mo = slides.get(position);
            ArrayList<TLRPC.PhotoSize> sizes = mo.photoThumbs;
            TLRPC.PhotoSize photoSize = FileLoader.getClosestPhotoSizeWithSize(sizes, AndroidUtilities.getPhotoSize());
            TLRPC.PhotoSize thumbSize = FileLoader.getClosestPhotoSizeWithSize(sizes, 50);
            holder.imageView.setImage(
                ImageLocation.getForObject(photoSize, mo.photoThumbsObject),
                "300_300",
                ImageLocation.getForObject(thumbSize, mo.photoThumbsObject),
                "50_50",
                null,
                mo
            );
            boolean slideIsVideo = mo.isVideo();
            holder.playIcon.setVisibility(slideIsVideo ? VISIBLE : GONE);
            if (slideIsVideo) {
                holder.duration.setText(formatDuration((int) mo.getDuration()));
                holder.duration.setVisibility(VISIBLE);
            } else {
                holder.duration.setVisibility(GONE);
            }
        }

        @Override
        public int getItemCount() {
            return slides.size();
        }
    }

    private static class MediaSlideHolder extends RecyclerView.ViewHolder {
        final BackupImageView imageView;
        final ImageView playIcon;
        final TextView duration;

        MediaSlideHolder(View itemView, BackupImageView imageView, ImageView playIcon, TextView duration) {
            super(itemView);
            this.imageView = imageView;
            this.playIcon = playIcon;
            this.duration = duration;
        }
    }

    private static String formatDuration(int seconds) {
        if (seconds < 0) {
            seconds = 0;
        }
        int m = seconds / 60;
        int s = seconds % 60;
        return m + ":" + (s < 10 ? "0" + s : String.valueOf(s));
    }

    private static Drawable buildPlayDrawable() {
        return new Drawable() {
            private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint trianglePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            {
                circlePaint.setColor(0x80000000);
                trianglePaint.setColor(0xFFFFFFFF);
            }

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                float cx = b.centerX();
                float cy = b.centerY();
                float r = Math.min(b.width(), b.height()) / 2f;
                canvas.drawCircle(cx, cy, r, circlePaint);
                float triR = r * 0.45f;
                Path path = new Path();
                path.moveTo(cx - triR * 0.6f, cy - triR);
                path.lineTo(cx - triR * 0.6f, cy + triR);
                path.lineTo(cx + triR, cy);
                path.close();
                canvas.drawPath(path, trianglePaint);
            }

            @Override
            public void setAlpha(int alpha) {
                circlePaint.setAlpha((int) (0x80 * (alpha / 255f)));
                trianglePaint.setAlpha(alpha);
            }

            @Override
            public void setColorFilter(ColorFilter colorFilter) {
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }

    private TLRPC.ReactionCount getTopReaction(MessageObject messageObject) {
        if (messageObject.messageOwner == null || messageObject.messageOwner.reactions == null) {
            return null;
        }
        ArrayList<TLRPC.ReactionCount> results = messageObject.messageOwner.reactions.results;
        if (results == null || results.isEmpty()) {
            return null;
        }
        TLRPC.ReactionCount top = results.get(0);
        for (int i = 1; i < results.size(); i++) {
            if (results.get(i).count > top.count) {
                top = results.get(i);
            }
        }
        return top;
    }
}
