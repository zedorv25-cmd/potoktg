package org.telegram.ui;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.PotokFeedPostCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Лента — показывает посты из всех каналов на которые подписан пользователь,
 * смешанные и отсортированные по дате (свежие сверху).
 */
public class PotokFeedFragment extends BaseFragment implements MainTabsActivity.TabFragmentDelegate {

    private static final int MESSAGES_TO_LOAD_PER_CHANNEL = 30;
    private static final int MAX_POSTS_PER_CHANNEL = 10;

    private RecyclerListView listView;
    private MainTabsActivityController mainTabsActivityController;
    private final ArrayList<FeedItem> items = new ArrayList<>();
    // username -> уже резолвленный канал (или null, если ещё не резолвлен)
    private final java.util.Map<String, TLRPC.Chat> resolvedChannels = new java.util.HashMap<>();
    // username -> посты этого канала, уже собранные в FeedItem (альбомы объединены)
    private final java.util.Map<String, ArrayList<FeedItem>> channelItems = new java.util.HashMap<>();
    private final java.util.Set<String> historyInFlight = new java.util.HashSet<>();

    private static class FeedItem {
        TLRPC.Chat channel;
        ArrayList<MessageObject> messages = new ArrayList<>();
    }

    public void setMainTabsActivityController(MainTabsActivityController controller) {
        mainTabsActivityController = controller;
    }

    @Override
    public View createView(Context context) {
        FrameLayout frameLayout = new FrameLayout(context);
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));

        // Отступ сверху = статусбар + высота таббара снизу MainTabsActivity (не тулбар — его нет)
        int topPadding = AndroidUtilities.statusBarHeight + AndroidUtilities.dp(56);
        listView.setPadding(0, topPadding, 0, AndroidUtilities.dp(56));
        listView.setClipToPadding(false);

        listView.setAdapter(new RecyclerView.Adapter<RecyclerListView.Holder>() {
            @Override
            public RecyclerListView.Holder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
                PotokFeedPostCell cell = new PotokFeedPostCell(context, null);
                cell.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
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
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        if (mainTabsActivityController != null) {
            listView.addOnScrollListener(new TabBarScrollHider(mainTabsActivityController));
        }

        loadFeed();

        return frameLayout;
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
        for (TLRPC.Chat channel : channels) {
            String key = String.valueOf(channel.id);
            resolvedChannels.put(key, channel);
            loadHistory(key, channel);
        }
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
            if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                return;
            }
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            getMessagesController().putUsers(res.users, false);
            getMessagesController().putChats(res.chats, false);

            ArrayList<MessageObject> messageObjects = new ArrayList<>();
            for (TLRPC.Message message : res.messages) {
                messageObjects.add(new MessageObject(currentAccount, message, true, true));
            }
            channelItems.put(key, buildChannelItems(messageObjects, channel));
            rebuildAndShowAllItems();
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
        return result;
    }

    /** Дата поста для сортировки общей ленты — берём дату первого сообщения в группе. */
    private int postDate(FeedItem item) {
        if (item.messages.isEmpty() || item.messages.get(0).messageOwner == null) {
            return 0;
        }
        return item.messages.get(0).messageOwner.date;
    }

    private void rebuildAndShowAllItems() {
        items.clear();
        for (ArrayList<FeedItem> channelList : channelItems.values()) {
            items.addAll(channelList);
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
        }
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        return true;
    }

    @Override
    public void onResume() {
        super.onResume();
        loadFeed();
    }
}
