package org.telegram.ui;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.PotokFeedPostCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Лента — этап 1.
 * Показывает последние посты одного канала (TEST_CHANNEL_USERNAME).
 * Канал резолвится через contacts.resolveUsername — НЕ зависит от того, подписан ли
 * текущий аккаунт на канал (раньше искалось только среди dialogsChannelsOnly, из-за
 * чего лента была пустой, если аккаунт не подписан).
 * Когда карточка будет полностью готова — заменить TEST_CHANNEL_USERNAME на сборку
 * по всем подпискам пользователя.
 */
public class PotokFeedFragment extends BaseFragment implements MainTabsActivity.TabFragmentDelegate, NotificationCenter.NotificationCenterDelegate {

    private static final String TEST_CHANNEL_USERNAME = "komissariatforsvoix";
    private static final int POSTS_TO_LOAD = 20;

    private RecyclerListView listView;
    private MainTabsActivityController mainTabsActivityController;
    private final ArrayList<FeedItem> items = new ArrayList<>();
    private TLRPC.Chat testChannel;
    private boolean resolveRequested;

    private static class FeedItem {
        TLRPC.Chat channel;
        MessageObject message;
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
        listView.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);
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
                ((PotokFeedPostCell) holder.itemView).setMessage(item.message, item.channel);
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

        getNotificationCenter().addObserver(this, NotificationCenter.messagesDidLoad);

        loadFeed();

        return frameLayout;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        getNotificationCenter().removeObserver(this, NotificationCenter.messagesDidLoad);
    }

    private void loadFeed() {
        if (testChannel != null) {
            loadHistory();
            return;
        }
        if (resolveRequested) {
            return;
        }
        resolveRequested = true;

        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = TEST_CHANNEL_USERNAME;
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            resolveRequested = false;
            if (error != null || !(response instanceof TLRPC.TL_contacts_resolvedPeer)) {
                return;
            }
            TLRPC.TL_contacts_resolvedPeer resolvedPeer = (TLRPC.TL_contacts_resolvedPeer) response;
            if (resolvedPeer.chats.isEmpty()) {
                return;
            }
            testChannel = resolvedPeer.chats.get(0);
            getMessagesController().putChat(testChannel, false);
            loadHistory();
        }));
    }

    private void loadHistory() {
        long dialogId = -testChannel.id;
        getMessagesController().loadMessages(dialogId, 0, false, POSTS_TO_LOAD, 0, 0, true, 0, getClassGuid(), 0, 0, 0, 0, 0, 0, false);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagesDidLoad) {
            int guid = (Integer) args[10];
            if (guid != getClassGuid()) {
                return;
            }
            if (testChannel == null) {
                return;
            }

            @SuppressWarnings("unchecked")
            ArrayList<MessageObject> messageObjects = (ArrayList<MessageObject>) args[2];

            items.clear();
            for (MessageObject messageObject : messageObjects) {
                if (messageObject == null || messageObject.messageOwner == null) {
                    continue;
                }
                FeedItem item = new FeedItem();
                item.channel = testChannel;
                item.message = messageObject;
                items.add(item);
            }

            notifyWhenReady();
        }
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
