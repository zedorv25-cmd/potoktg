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
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

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
 * Структура — вертикальный LinearLayout: шапка -> текст (полный, без обрезки) -> медиа (фото/видео/аудио) -> футер.
 * Просмотр фото/видео и проигрывание аудио — через готовые компоненты Telegram (PhotoViewer, SharedAudioCell).
 */
public class PotokFeedPostCell extends LinearLayout {

    private static final int MAX_MEDIA_HEIGHT_DP = 360;
    private static final int MIN_MEDIA_HEIGHT_DP = 140;

    private final BackupImageView avatarView;
    private final TextView titleView;
    private final TextView timeView;
    private final TextView textView;
    private final FrameLayout mediaContainer;
    private final BackupImageView mediaView;
    private final ImageView playIconView;
    private final TextView durationView;
    private final SharedAudioCell audioCell;
    private final TextView viewsView;
    private final TextView reactionView;
    private final ImageView viewsIcon;

    private MessageObject currentMessage;
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

        // --- Медиа: фото/видео в контейнере (play-иконка и длительность — для видео) ---
        mediaContainer = new FrameLayout(context);
        addView(mediaContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, MIN_MEDIA_HEIGHT_DP, 12, 10, 12, 0));
        mediaContainer.setOnClickListener(v -> openMediaViewer());

        mediaView = new BackupImageView(context);
        mediaView.setRoundRadius(dp(8));
        mediaContainer.addView(mediaView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        playIconView = new ImageView(context);
        playIconView.setImageDrawable(buildPlayDrawable());
        playIconView.setVisibility(GONE);
        mediaContainer.addView(playIconView, LayoutHelper.createFrame(48, 48, Gravity.CENTER));

        durationView = new TextView(context);
        durationView.setTextColor(0xFFFFFFFF);
        durationView.setTextSize(12);
        durationView.setBackgroundColor(0x66000000);
        durationView.setPadding(dp(6), dp(2), dp(6), dp(2));
        durationView.setVisibility(GONE);
        mediaContainer.addView(durationView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 8, 8));

        // --- Аудио: готовая ячейка Telegram (play/pause, длительность, прогресс) ---
        audioCell = new SharedAudioCell(context, resourcesProvider);
        audioCell.setVisibility(GONE);
        addView(audioCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, 10, 8, 0));
        audioCell.setOnClickListener(v -> {
            if (currentMessage != null) {
                MediaController.getInstance().playMessage(currentMessage);
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

    public void setMessage(MessageObject messageObject, TLRPC.Chat channel) {
        currentMessage = messageObject;
        currentChannel = channel;

        AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(channel);
        avatarView.setForUserOrChat(channel, avatarDrawable);

        titleView.setText(channel != null ? channel.title : "");
        timeView.setText(LocaleController.formatDate(messageObject.messageOwner.date));

        CharSequence caption = messageObject.caption;
        if (TextUtils.isEmpty(caption) && messageObject.type == MessageObject.TYPE_TEXT) {
            caption = messageObject.messageText;
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

        boolean isVoiceOrMusic = messageObject.isVoice() || messageObject.isMusic();
        boolean isVideo = messageObject.isVideo();
        ArrayList<TLRPC.PhotoSize> sizes = messageObject.photoThumbs;
        boolean hasPhotoOrVideoThumb = !isVoiceOrMusic && sizes != null && !sizes.isEmpty();

        if (isVoiceOrMusic) {
            mediaContainer.setVisibility(GONE);
            mediaView.setImageDrawable(null);
            playIconView.setVisibility(GONE);
            durationView.setVisibility(GONE);
            audioCell.setMessageObject(messageObject, false);
            audioCell.setVisibility(VISIBLE);
        } else if (hasPhotoOrVideoThumb) {
            audioCell.setVisibility(GONE);

            TLRPC.PhotoSize photoSize = FileLoader.getClosestPhotoSizeWithSize(sizes, AndroidUtilities.getPhotoSize());
            TLRPC.PhotoSize thumbSize = FileLoader.getClosestPhotoSizeWithSize(sizes, 50);

            int w = photoSize != null ? photoSize.w : 0;
            int h = photoSize != null ? photoSize.h : 0;
            int mediaHeight = MIN_MEDIA_HEIGHT_DP;
            if (w > 0 && h > 0) {
                int screenWidthDp = (int) (AndroidUtilities.displaySize.x / AndroidUtilities.density) - 24;
                mediaHeight = Math.round(screenWidthDp * (h / (float) w));
                mediaHeight = Math.max(MIN_MEDIA_HEIGHT_DP, Math.min(MAX_MEDIA_HEIGHT_DP, mediaHeight));
            }
            LayoutParams containerParams = (LayoutParams) mediaContainer.getLayoutParams();
            containerParams.height = dp(mediaHeight);
            mediaContainer.setLayoutParams(containerParams);

            mediaContainer.setVisibility(VISIBLE);
            mediaView.setImage(
                ImageLocation.getForObject(photoSize, messageObject.photoThumbsObject),
                "300_" + mediaHeight,
                ImageLocation.getForObject(thumbSize, messageObject.photoThumbsObject),
                "50_50",
                null,
                messageObject
            );

            if (isVideo) {
                playIconView.setVisibility(VISIBLE);
                durationView.setText(formatDuration((int) messageObject.getDuration()));
                durationView.setVisibility(VISIBLE);
            } else {
                playIconView.setVisibility(GONE);
                durationView.setVisibility(GONE);
            }
        } else {
            mediaContainer.setVisibility(GONE);
            mediaView.setImageDrawable(null);
            playIconView.setVisibility(GONE);
            durationView.setVisibility(GONE);
            audioCell.setVisibility(GONE);
        }

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

    private void openMediaViewer() {
        if (currentMessage == null || parentActivity == null) {
            return;
        }
        // родной полноэкранный просмотрщик Telegram — та же механика, что и в чатах:
        // зум, свайп, видео со звуком по умолчанию. Никакой своей реализации.
        PhotoViewer.getInstance().setParentActivity(parentActivity);
        long dialogId = currentChannel != null ? -currentChannel.id : 0;
        PhotoViewer.getInstance().openPhoto(currentMessage, dialogId, 0, 0, photoViewerProvider, true);
    }

    private final PhotoViewer.PhotoViewerProvider photoViewerProvider = new PhotoViewer.EmptyPhotoViewerProvider() {
        @Override
        public PhotoViewer.PlaceProviderObject getPlaceForPhoto(MessageObject messageObject, TLRPC.FileLocation fileLocation, int index, boolean needPreview, boolean closing) {
            if (messageObject != currentMessage || mediaView.getVisibility() != VISIBLE) {
                return null;
            }
            int[] coords = new int[2];
            mediaView.getLocationInWindow(coords);
            PhotoViewer.PlaceProviderObject object = new PhotoViewer.PlaceProviderObject();
            object.viewX = coords[0];
            object.viewY = coords[1];
            object.parentView = mediaView;
            object.imageReceiver = mediaView.getImageReceiver();
            object.dialogId = currentChannel != null ? -currentChannel.id : 0;
            return object;
        }
    };

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
