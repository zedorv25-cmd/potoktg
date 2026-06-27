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
 * Лента — этап 1 + 2 (тестовый режим, несколько каналов).
 * Показывает посты из списка TEST_CHANNEL_USERNAMES, смешанные и отсортированные по дате.
 * Каждый канал резолвится и грузится независимо (параллельно), результат объединяется
 * по готовности всех каналов.
 */
public class PotokFeedFragment extends BaseFragment implements MainTabsActivity.TabFragmentDelegate {

    private static final String[] TEST_CHANNEL_USERNAMES = {
        "komissariatforsvoix",
        "news_unitedm",
        "daysmad"
    };
    private static final int MESSAGES_TO_LOAD_PER_CHANNEL = 60;
    private static final int MAX_POSTS_PER_CHANNEL = 15;

    private RecyclerListView listView;
    private MainTabsActivityController mainTabsActivityController;
    private final ArrayList<FeedItem> items = new ArrayList<>();
    // username -> уже резолвленный канал (или null, если ещё не резолвлен)
    private final java.util.Map<String, TLRPC.Chat> resolvedChannels = new java.util.HashMap<>();
    // username -> посты этого канала, уже собранные в FeedItem (альбомы объединены)
    private final java.util.Map<String, ArrayList<FeedItem>> channelItems = new java.util.HashMap<>();
    private final java.util.Set<String> resolveInFlight = new java.util.HashSet<>();
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
        for (String username : TEST_CHANNEL_USERNAMES) {
            TLRPC.Chat channel = resolvedChannels.get(username);
            if (channel != null) {
                loadHistory(username, channel);
            } else {
                resolveChannel(username);
            }
        }
    }

    private void resolveChannel(String username) {
        if (resolveInFlight.contains(username)) {
            return;
        }
        resolveInFlight.add(username);

        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = username;
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            resolveInFlight.remove(username);
            if (error != null || !(response instanceof TLRPC.TL_contacts_resolvedPeer)) {
                return;
            }
            TLRPC.TL_contacts_resolvedPeer resolvedPeer = (TLRPC.TL_contacts_resolvedPeer) response;
            if (resolvedPeer.chats.isEmpty()) {
                return;
            }
            TLRPC.Chat channel = resolvedPeer.chats.get(0);
            resolvedChannels.put(username, channel);
            getMessagesController().putChat(channel, false);
            loadHistory(username, channel);
        }));
    }

    private void loadHistory(String username, TLRPC.Chat channel) {
        if (historyInFlight.contains(username)) {
            return;
        }
        historyInFlight.add(username);

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
            historyInFlight.remove(username);
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
            channelItems.put(username, buildChannelItems(messageObjects, channel));
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
