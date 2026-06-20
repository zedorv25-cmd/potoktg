package org.telegram.ui.Cells;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * Карточка поста в Ленте — этап 1.
 * Шапка (аватар канала + название + время) — текст до 6-7 строк без раскрытия — одно фото — футер (просмотры + топ-реакция).
 * Фон карточки совпадает с фоном остального UI (не серый).
 */
public class PotokFeedPostCell extends FrameLayout {

    private static final int MAX_TEXT_LINES = 7;

    private final BackupImageView avatarView;
    private final TextView titleView;
    private final TextView timeView;
    private final TextView textView;
    private final BackupImageView mediaView;
    private final TextView viewsView;
    private final TextView reactionView;
    private final ImageView viewsIcon;

    private final Theme.ResourcesProvider resourcesProvider;

    private MessageObject currentMessage;

    public PotokFeedPostCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;

        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(dp(18));
        addView(avatarView, LayoutHelper.createFrame(36, 36, Gravity.TOP | Gravity.LEFT, 12, 12, 0, 0));

        titleView = new TextView(context);
        titleView.setTextSize(15);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 58, 12, 16, 0));

        timeView = new TextView(context);
        timeView.setTextSize(13);
        timeView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        addView(timeView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 58, 32, 16, 0));

        textView = new TextView(context);
        textView.setTextSize(15);
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        textView.setMaxLines(MAX_TEXT_LINES);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textView.setLineSpacing(dp(2), 1f);
        addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 12, 58, 12, 0));

        mediaView = new BackupImageView(context);
        mediaView.setRoundRadius(dp(8));
        addView(mediaView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 220, Gravity.TOP | Gravity.LEFT, 12, 0, 12, 0));

        LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        addView(footer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 32, Gravity.TOP | Gravity.LEFT, 12, 0, 12, 8));

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

        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
    }

    public void setMessage(MessageObject messageObject, TLRPC.Chat channel) {
        currentMessage = messageObject;

        AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(channel);
        avatarView.setForUserOrChat(channel, avatarDrawable);

        titleView.setText(channel != null ? channel.title : "");
        timeView.setText(LocaleController.formatDate(messageObject.messageOwner.date));

        CharSequence messageText = messageObject.messageText;
        if (messageText == null) {
            messageText = "";
        }
        textView.setText(messageText);

        ArrayList<TLRPC.PhotoSize> sizes = messageObject.photoThumbs;
        boolean hasPhoto = sizes != null && !sizes.isEmpty();
        if (hasPhoto) {
            TLRPC.PhotoSize photoSize = FileLoader.getClosestPhotoSizeWithSize(sizes, AndroidUtilities.getPhotoSize());
            TLRPC.PhotoSize thumbSize = FileLoader.getClosestPhotoSizeWithSize(sizes, 50);
            mediaView.setVisibility(VISIBLE);
            mediaView.setImage(
                org.telegram.messenger.ImageLocation.getForObject(photoSize, messageObject.photoThumbsObject),
                "300_220",
                org.telegram.messenger.ImageLocation.getForObject(thumbSize, messageObject.photoThumbsObject),
                "50_50",
                null,
                messageObject
            );
        } else {
            mediaView.setVisibility(GONE);
            mediaView.setImageDrawable(null);
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

        requestLayout();
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

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        boolean hasPhoto = mediaView.getVisibility() == VISIBLE;
        // примерная высота: шапка(58) + текст(до 7 строк ~ 24*7) + медиа(220, если есть) + футер(40) + отступы
        int textHeight = textView.getLineCount() > 0 ? textView.getMeasuredHeight() : dp(24) * Math.min(MAX_TEXT_LINES, 3);
        int height = dp(58) + dp(20) + textHeight + (hasPhoto ? dp(220) + dp(8) : dp(4)) + dp(40) + dp(8);
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
