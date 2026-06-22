package org.telegram.ui;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

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
 * Лента — этап 1 (тестовый режим).
 * ВРЕМЕННО: показывает последние посты ОДНОГО тестового канала (TEST_CHANNEL_USERNAME),
 * чтобы отладить все аспекты карточки поста до подключения полного фида по всем подпискам.
 * Когда карточка будет полностью готова — заменить loadFeed() на сборку по всем dialogsChannelsOnly.
 */
public class PotokFeedFragment extends BaseFragment implements MainTabsActivity.TabFragmentDelegate, NotificationCenter.NotificationCenterDelegate {

    private static final String TEST_CHANNEL_USERNAME = "komissariatforsvoix";
    private static final int POSTS_TO_LOAD = 20;

    private RecyclerListView listView;
    private MainTabsActivityController mainTabsActivityController;
    private final ArrayList<FeedItem> items = new ArrayList<>();
    private TLRPC.Chat testChannel;
    private boolean loadRequested;

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
        listView.setPadding(0, org.telegram.messenger.AndroidUtilities.statusBarHeight, 0, 0);
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
        if (loadRequested) {
            return;
        }

        MessagesController messagesController = getMessagesController();

        // ищем тестовый канал среди подписок пользователя по username
        testChannel = null;
        for (TLRPC.Dialog dialog : messagesController.dialogsChannelsOnly) {
            TLRPC.Chat chat = messagesController.getChat(-dialog.id);
            if (chat != null && chat.username != null && chat.username.equalsIgnoreCase(TEST_CHANNEL_USERNAME)) {
                testChannel = chat;
                break;
            }
        }

        if (testChannel == null) {
            // канал не найден среди подписок — нечего грузить
            return;
        }

        loadRequested = true;
        long dialogId = -testChannel.id;
        messagesController.loadMessages(dialogId, 0, false, POSTS_TO_LOAD, 0, 0, true, 0, getClassGuid(), 0, 0, 0, 0, 0, 0, false);
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
        loadRequested = false;
        loadFeed();
    }
}
