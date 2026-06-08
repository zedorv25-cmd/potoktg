package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

public class PotokDrawerView extends FrameLayout {

    private BaseFragment parentFragment;
    private OnDrawerItemClickListener listener;

    public interface OnDrawerItemClickListener {
        void onItemClick(int id);
    }

    public static final int ID_CONTACTS = 1;
    public static final int ID_CALLS = 2;
    public static final int ID_SAVED = 3;
    public static final int ID_SETTINGS = 4;
    public static final int ID_NEW_GROUP = 5;
    public static final int ID_NEW_CHANNEL = 6;
    public static final int ID_FOLDERS = 7;
    public static final int ID_WALLET = 8;

    public PotokDrawerView(Context context, BaseFragment fragment) {
        super(context);
        this.parentFragment = fragment;

        setBackgroundColor(Theme.getColor(Theme.key_chats_menuBackground));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        // Header
        FrameLayout header = new FrameLayout(context);
        header.setBackgroundColor(Theme.getColor(Theme.key_chats_menuTopBackground));
        header.setMinimumHeight(AndroidUtilities.dp(148));

        BackupImageView avatarView = new BackupImageView(context);
        avatarView.getImageReceiver().setRoundRadius(AndroidUtilities.dp(32));
        TLRPC.User user = MessagesController.getInstance(UserConfig.selectedAccount)
                .getUser(UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId());
        if (user != null) {
            AvatarDrawable avatarDrawable = new AvatarDrawable(user);
            avatarView.setForUserOrChat(user, avatarDrawable);
        }
        header.addView(avatarView, LayoutHelper.createFrame(64, 64, Gravity.LEFT | Gravity.BOTTOM, 16, 0, 0, 67));

        TextView nameView = new TextView(context);
        nameView.setTextSize(1, 15);
        nameView.setTypeface(AndroidUtilities.bold());
        nameView.setTextColor(Theme.getColor(Theme.key_chats_menuName));
        if (user != null) {
            nameView.setText(user.first_name + (user.last_name != null ? " " + user.last_name : ""));
        }
        header.addView(nameView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.BOTTOM, 16, 0, 52, 28));

        TextView phoneView = new TextView(context);
        phoneView.setTextSize(1, 13);
        phoneView.setTextColor(Theme.getColor(Theme.key_chats_menuPhone));
        if (user != null && user.phone != null) {
            phoneView.setText("+" + user.phone);
        }
        header.addView(phoneView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.BOTTOM, 16, 0, 52, 9));

        content.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Пункты меню
        content.addView(createMenuItem(context, ID_NEW_GROUP, R.drawable.msg_groups, LocaleController.getString("NewGroup", R.string.NewGroup)));
        content.addView(createMenuItem(context, ID_NEW_CHANNEL, R.drawable.msg_channel, LocaleController.getString("NewChannel", R.string.NewChannel)));
        content.addView(createDivider(context));
        content.addView(createMenuItem(context, ID_CONTACTS, R.drawable.msg_contacts, LocaleController.getString("Contacts", R.string.Contacts)));
        content.addView(createMenuItem(context, ID_FOLDERS, R.drawable.msg_folders, LocaleController.getString("Filters", R.string.Filters)));
        content.addView(createMenuItem(context, ID_SAVED, R.drawable.msg_saved, LocaleController.getString("SavedMessages", R.string.SavedMessages)));
        content.addView(createMenuItem(context, ID_CALLS, R.drawable.msg_calls, LocaleController.getString("Calls", R.string.Calls)));
        content.addView(createDivider(context));
        content.addView(createMenuItem(context, ID_WALLET, R.drawable.settings_wallet, LocaleController.getString("PotokWallet", R.string.PotokWallet)));
        content.addView(createMenuItem(context, ID_SETTINGS, R.drawable.msg_settings_old, LocaleController.getString("Settings", R.string.Settings)));

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(content);
        addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    private View createMenuItem(Context context, int id, int iconRes, String title) {
        FrameLayout item = new FrameLayout(context);
        item.setMinimumHeight(AndroidUtilities.dp(48));
        item.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));

        ImageView icon = new ImageView(context);
        icon.setImageResource(iconRes);
        icon.setColorFilter(Theme.getColor(Theme.key_chats_menuItemIcon), android.graphics.PorterDuff.Mode.MULTIPLY);
        item.addView(icon, LayoutHelper.createFrame(24, 24, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 0, 0, 0));

        TextView text = new TextView(context);
        text.setTextSize(1, 15);
        text.setText(title);
        text.setTextColor(Theme.getColor(Theme.key_chats_menuItemText));
        item.addView(text, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 72, 0, 16, 0));

        item.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(id);
            }
        });

        return item;
    }

    private View createDivider(Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        return divider;
    }

    public void setOnDrawerItemClickListener(OnDrawerItemClickListener listener) {
        this.listener = listener;
    }
}
