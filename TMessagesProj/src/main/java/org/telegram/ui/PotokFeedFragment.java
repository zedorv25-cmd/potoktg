package org.telegram.ui;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.PotokFeedPostCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Лента — этап 1: список постов (последнее сообщение) из подписанных каналов.
 * Каждая карточка — PotokFeedPostCell.
 */
public class PotokFeedFragment extends BaseFragment implements MainTabsActivity.TabFragmentDelegate {

    private RecyclerListView listView;
    private MainTabsActivityController mainTabsActivityController;
    private final ArrayList<FeedItem> items = new ArrayList<>();

    public void setMainTabsActivityController(MainTabsActivityController controller) {
        mainTabsActivityController = controller;
    }

    private static class FeedItem {
        TLRPC.Chat channel;
        MessageObject message;
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

        loadFeed();

        return frameLayout;
    }

    private void loadFeed() {
        items.clear();
        MessagesController messagesController = getMessagesController();

        for (TLRPC.Dialog dialog : messagesController.dialogsChannelsOnly) {
            TLRPC.Chat chat = messagesController.getChat(-dialog.id);
            if (chat == null || !chat.broadcast) {
                continue; // только каналы, не супергруппы
            }

            ArrayList<MessageObject> messages = messagesController.dialogMessage.get(dialog.id);
            if (messages == null || messages.isEmpty()) {
                continue;
            }

            MessageObject lastMessage = messages.get(0);
            if (lastMessage == null || lastMessage.messageOwner == null) {
                continue;
            }

            FeedItem item = new FeedItem();
            item.channel = chat;
            item.message = lastMessage;
            items.add(item);
        }

        // сортировка по дате поста, свежие сверху
        Collections.sort(items, new Comparator<FeedItem>() {
            @Override
            public int compare(FeedItem a, FeedItem b) {
                int dateA = a.message.messageOwner != null ? a.message.messageOwner.date : 0;
                int dateB = b.message.messageOwner != null ? b.message.messageOwner.date : 0;
                return dateB - dateA;
            }
        });

        if (listView != null && listView.getAdapter() != null) {
            // notifyDataSetChanged может вызваться из onResume() в момент анимации переключения таба —
            // в этот момент RecyclerView выполняет layout/scroll и запрещает изменения синхронно.
            // post() откладывает вызов на следующий кадр, когда RecyclerView точно свободен.
            listView.post(() -> {
                if (listView != null && listView.getAdapter() != null) {
                    listView.getAdapter().notifyDataSetChanged();
                }
            });
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
