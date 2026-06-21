package org.telegram.ui.Cells;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * Карточка поста в Ленте — этап 1.
 * Структура — вертикальный LinearLayout, блоки идут друг под другом естественно:
 * шапка (аватар + название + время) -> текст (до 7 строк) -> медиа (если есть) -> футер (просмотры + топ-реакция).
 * Фон карточки совпадает с фоном остального UI (не серый).
 */
public class PotokFeedPostCell extends LinearLayout {

    private static final int MAX_TEXT_LINES = 7;

    private final BackupImageView avatarView;
    private final TextView titleView;
    private final TextView timeView;
    private final TextView textView;
    private final BackupImageView mediaView;
    private final TextView viewsView;
    private final TextView reactionView;
    private final ImageView viewsIcon;

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

        // --- Текст поста ---
        textView = new TextView(context);
        textView.setTextSize(15);
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        textView.setMaxLines(MAX_TEXT_LINES);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textView.setLineSpacing(dp(2), 1f);
        addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 8, 12, 0));

        // --- Медиа ---
        mediaView = new BackupImageView(context);
        mediaView.setRoundRadius(dp(8));
        addView(mediaView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 220, 12, 10, 12, 0));

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
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(channel);
        avatarView.setForUserOrChat(channel, avatarDrawable);

        titleView.setText(channel != null ? channel.title : "");
        timeView.setText(LocaleController.formatDate(messageObject.messageOwner.date));

        CharSequence messageText = messageObject.messageText;
        if (messageText == null) {
            messageText = "";
        }
        if (TextUtils.isEmpty(messageText)) {
            textView.setVisibility(GONE);
        } else {
            textView.setVisibility(VISIBLE);
            textView.setText(messageText);
        }

        ArrayList<TLRPC.PhotoSize> sizes = messageObject.photoThumbs;
        boolean hasPhoto = sizes != null && !sizes.isEmpty();
        if (hasPhoto) {
            TLRPC.PhotoSize photoSize = FileLoader.getClosestPhotoSizeWithSize(sizes, AndroidUtilities.getPhotoSize());
            TLRPC.PhotoSize thumbSize = FileLoader.getClosestPhotoSizeWithSize(sizes, 50);
            mediaView.setVisibility(VISIBLE);
            mediaView.setImage(
                ImageLocation.getForObject(photoSize, messageObject.photoThumbsObject),
                "300_220",
                ImageLocation.getForObject(thumbSize, messageObject.photoThumbsObject),
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
