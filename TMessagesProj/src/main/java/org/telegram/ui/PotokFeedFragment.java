package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.TLObject;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Adapters.DialogsSearchAdapter;
import org.telegram.ui.Cells.PotokFeedPostCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.collection.LongSparseArray;

/**
 * Лента — показывает посты из каналов на которые подписан пользователь,
 * плюс из последних 10 каналов из истории поиска.
 * Можно фильтровать каналы через кнопку "три точки".
 */
public class PotokFeedFragment extends BaseFragment implements MainTabsActivity.TabFragmentDelegate, NotificationCenter.NotificationCenterDelegate {

    private static final int MESSAGES_TO_LOAD_PER_CHANNEL = 30;
    private static final int MAX_POSTS_PER_CHANNEL = 10;
    private static final int MAX_RECENT_SEARCH_CHANNELS = 10;
    private static final String PREFS_NAME = "potok_feed_filter";
    private static final String PREFS_KEY_HIDDEN = "hidden_channels";

    private RecyclerListView listView;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefreshLayout;
    private boolean refreshingFeed = false;
    private LinearLayoutManager listViewLayoutManager;
    private org.telegram.ui.Components.RecyclerAnimationScrollHelper scrollHelper;
    private FrameLayout scrollToTopButton;
    private MainTabsActivityController mainTabsActivityController;
    private final ArrayList<FeedItem> items = new ArrayList<>();
    private final java.util.Map<String, TLRPC.Chat> resolvedChannels = new java.util.HashMap<>();
    private final java.util.Map<String, ArrayList<FeedItem>> channelItems = new java.util.HashMap<>();
    private final java.util.Set<String> historyInFlight = new java.util.HashSet<>();

    // Все каналы в ленте (подписки + поиск) — для фильтра
    private final ArrayList<TLRPC.Chat> allChannels = new ArrayList<>();
    // ID каналов скрытых фильтром
    private Set<String> hiddenChannelIds = new HashSet<>();

    private static class FeedItem {
        TLRPC.Chat channel;
        ArrayList<MessageObject> messages = new ArrayList<>();
    }

    public void setMainTabsActivityController(MainTabsActivityController controller) {
        mainTabsActivityController = controller;
    }

    @Override
    public View createView(Context context) {
        PotokDebugLog.log("FEED", "createView: начало");
        try {
            View result = createViewInternal(context);
            PotokDebugLog.log("FEED", "createView: успешно");
            return result;
        } catch (Throwable t) {
            PotokDebugLog.log("FEED", "createView: ИСКЛЮЧЕНИЕ " + t.getClass().getName() + ": " + t.getMessage()
                + "\n" + android.util.Log.getStackTraceString(t));
            throw t;
        }
    }

    private View createViewInternal(Context context) {
        // Загружаем сохранённые скрытые каналы из SharedPreferences
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        hiddenChannelIds = new HashSet<>(prefs.getStringSet(PREFS_KEY_HIDDEN, new HashSet<>()));

        org.telegram.ui.Components.SizeNotifierFrameLayout frameLayout = new org.telegram.ui.Components.SizeNotifierFrameLayout(context);
        fragmentView = frameLayout;
        frameLayout.setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion());

        listView = new RecyclerListView(context);
        listViewLayoutManager = new LinearLayoutManager(context);
        listView.setLayoutManager(listViewLayoutManager);
        scrollHelper = new org.telegram.ui.Components.RecyclerAnimationScrollHelper(listView, listViewLayoutManager);

        int topPadding = AndroidUtilities.statusBarHeight + AndroidUtilities.dp(56);
        listView.setPadding(0, topPadding, 0, AndroidUtilities.dp(56));
        listView.setClipToPadding(false);

        listView.setAdapter(new RecyclerView.Adapter<RecyclerListView.Holder>() {
            @Override
            public RecyclerListView.Holder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
                PotokFeedPostCell cell = new PotokFeedPostCell(context, null);
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT);
                lp.leftMargin = AndroidUtilities.dp(8);
                lp.rightMargin = AndroidUtilities.dp(8);
                lp.topMargin = AndroidUtilities.dp(6);
                lp.bottomMargin = AndroidUtilities.dp(6);
                cell.setLayoutParams(lp);
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

        swipeRefreshLayout = new androidx.swiperefreshlayout.widget.SwipeRefreshLayout(context);
        swipeRefreshLayout.setProgressViewOffset(false, AndroidUtilities.statusBarHeight + AndroidUtilities.dp(20), AndroidUtilities.statusBarHeight + AndroidUtilities.dp(76));
        swipeRefreshLayout.setColorSchemeColors(Theme.getColor(Theme.key_featuredStickers_addButton));
        swipeRefreshLayout.setOnRefreshListener(() -> {
            refreshingFeed = true;
            loadFeed();
            AndroidUtilities.runOnUIThread(() -> {
                if (refreshingFeed) {
                    refreshingFeed = false;
                    if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
                }
            }, 8000);
        });
        swipeRefreshLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        frameLayout.addView(swipeRefreshLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // Надпись ПОТОК — курсив жирный, слева
        TextView logoView = new TextView(context);
        logoView.setText("ПОТОК");
        logoView.setTextSize(22);
        logoView.setTypeface(android.graphics.Typeface.create(AndroidUtilities.bold(), android.graphics.Typeface.BOLD_ITALIC));
        logoView.setTextColor(0xFFFFFFFF);
        logoView.setShadowLayer(AndroidUtilities.dp(3), 0, 0, 0x80000000);
        logoView.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams logoParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, AndroidUtilities.dp(56)
        );
        logoParams.gravity = Gravity.LEFT | Gravity.TOP;
        logoParams.leftMargin = AndroidUtilities.dp(16);
        logoParams.topMargin = AndroidUtilities.statusBarHeight;
        frameLayout.addView(logoView, logoParams);
        logoView.bringToFront();

        // Кнопка "три точки" — справа, открывает фильтр каналов
        ImageView feedMenuButton = new ImageView(context);
        feedMenuButton.setImageResource(org.telegram.messenger.R.drawable.ic_ab_other);
        feedMenuButton.setColorFilter(0xFFFFFFFF);
        feedMenuButton.setScaleType(ImageView.ScaleType.CENTER);
        feedMenuButton.setBackground(Theme.createSelectorDrawable(0x33ffffff, Theme.RIPPLE_MASK_CIRCLE_20DP));
        FrameLayout.LayoutParams feedMenuParams = new FrameLayout.LayoutParams(AndroidUtilities.dp(40), AndroidUtilities.dp(40));
        feedMenuParams.gravity = Gravity.RIGHT | Gravity.TOP;
        feedMenuParams.rightMargin = AndroidUtilities.dp(8);
        feedMenuParams.topMargin = AndroidUtilities.statusBarHeight + AndroidUtilities.dp(8);
        feedMenuButton.setOnClickListener(v -> showChannelFilter(context));
        frameLayout.addView(feedMenuButton, feedMenuParams);
        feedMenuButton.bringToFront();

        // Кнопка "наверх"
        scrollToTopButton = new FrameLayout(context);
        GradientDrawable circleBg = new GradientDrawable();
        circleBg.setShape(GradientDrawable.OVAL);
        circleBg.setColor(Theme.getColor(Theme.key_dialogFloatingButton));
        scrollToTopButton.setBackground(circleBg);
        scrollToTopButton.setElevation(AndroidUtilities.dp(4));
        scrollToTopButton.setVisibility(View.GONE);
        scrollToTopButton.setAlpha(0f);

        ArrowUpView arrowUp = new ArrowUpView(context);
        arrowUp.setColor(Theme.getColor(Theme.key_dialogFloatingIcon));
        scrollToTopButton.addView(arrowUp, LayoutHelper.createFrame(24, 24, Gravity.CENTER));

        scrollToTopButton.setOnClickListener(v -> {
            if (listView == null || scrollHelper == null) return;
            // Фикс компиляции: scrollToPosition требует минимум 2 аргумента, здесь —
            // тот же вызов с мгновенным переносом к позиции 0 (см. предыдущие версии).
            scrollHelper.setScrollDirection(org.telegram.ui.Components.RecyclerAnimationScrollHelper.SCROLL_DIRECTION_UP);
            scrollHelper.scrollToPosition(0, 0, false, true);
        });

        FrameLayout.LayoutParams scrollBtnParams = new FrameLayout.LayoutParams(AndroidUtilities.dp(52), AndroidUtilities.dp(52));
        scrollBtnParams.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        scrollBtnParams.bottomMargin = AndroidUtilities.dp(56 + 16);
        scrollBtnParams.rightMargin = AndroidUtilities.dp(16);
        frameLayout.addView(scrollToTopButton, scrollBtnParams);

        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            private int totalDy = 0;
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                totalDy += dy;
                boolean shouldShow = totalDy > AndroidUtilities.dp(300);
                if (shouldShow && scrollToTopButton.getVisibility() != View.VISIBLE) {
                    scrollToTopButton.setVisibility(View.VISIBLE);
                    scrollToTopButton.animate().alpha(1f).setDuration(180).start();
                } else if (!shouldShow && scrollToTopButton.getVisibility() == View.VISIBLE) {
                    scrollToTopButton.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                        if (scrollToTopButton != null) scrollToTopButton.setVisibility(View.GONE);
                    }).start();
                }
            }
        });

        loadFeed();
        return frameLayout;
    }

    /**
     * Показывает диалог-фильтр каналов: список всех каналов в ленте с чекбоксами.
     * Состояние сохраняется в SharedPreferences — при перезапуске фильтр сохраняется.
     */
    private void showChannelFilter(Context context) {
        if (allChannels.isEmpty()) {
            PotokDebugLog.log("FEED", "Фильтр: каналов пока нет (лента ещё не загружена)");
            return;
        }

        PotokDebugLog.log("FEED", "Фильтр: открыт, каналов=" + allChannels.size());

        String[] channelNames = new String[allChannels.size()];
        boolean[] checked = new boolean[allChannels.size()];
        for (int i = 0; i < allChannels.size(); i++) {
            TLRPC.Chat ch = allChannels.get(i);
            channelNames[i] = ch.title;
            // checked = показывается (не скрыт)
            checked[i] = !hiddenChannelIds.contains(String.valueOf(ch.id));
        }

        new android.app.AlertDialog.Builder(context)
            .setTitle("Фильтр каналов")
            .setMultiChoiceItems(channelNames, checked, (dialog, which, isChecked) -> {
                String id = String.valueOf(allChannels.get(which).id);
                if (isChecked) {
                    hiddenChannelIds.remove(id);
                } else {
                    hiddenChannelIds.add(id);
                }
            })
            .setPositiveButton("Применить", (dialog, which) -> {
                // Сохраняем в SharedPreferences
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putStringSet(PREFS_KEY_HIDDEN, hiddenChannelIds).apply();
                PotokDebugLog.log("FEED", "Фильтр применён: скрыто=" + hiddenChannelIds.size() + " каналов");
                // Перестраиваем ленту с учётом фильтра
                rebuildAndShowAllItems();
            })
            .setNegativeButton("Отмена", null)
            .show();
    }

    private void loadFeed() {
        // Шаг 1: каналы из подписок
        ArrayList<TLRPC.Dialog> dialogs = getMessagesController().getAllDialogs();
        ArrayList<TLRPC.Chat> subscribed = new ArrayList<>();
        for (TLRPC.Dialog dialog : dialogs) {
            if (!(dialog instanceof TLRPC.TL_dialog)) continue;
            long did = dialog.id;
            if (did >= 0) continue;
            TLRPC.Chat chat = getMessagesController().getChat(-did);
            if (chat == null) continue;
            if (chat.broadcast && !chat.megagroup && !chat.deactivated && !chat.left && !chat.kicked) {
                subscribed.add(chat);
            }
        }

        if (subscribed.isEmpty()) {
            AndroidUtilities.runOnUIThread(this::loadFeed, 1500);
            return;
        }

        PotokDebugLog.log("FEED", "loadFeed: подписок=" + subscribed.size());

        // Обновляем список всех каналов и грузим историю
        for (TLRPC.Chat channel : subscribed) {
            addChannelToFeed(channel);
        }

        // Шаг 2: каналы из истории поиска (последние 10)
        loadRecentSearchChannels();
    }

    /**
     * Загружает до MAX_RECENT_SEARCH_CHANNELS каналов из истории поиска
     * через стандартный механизм DialogsSearchAdapter.loadRecentSearch().
     */
    private void loadRecentSearchChannels() {
        DialogsSearchAdapter.loadRecentSearch(currentAccount, 0, (arrayList, hashMap) -> {
            ArrayList<TLRPC.Chat> recentChannels = new ArrayList<>();
            for (int i = 0; i < arrayList.size() && recentChannels.size() < MAX_RECENT_SEARCH_CHANNELS; i++) {
                DialogsSearchAdapter.RecentSearchObject obj = arrayList.get(i);
                if (obj.object instanceof TLRPC.Chat) {
                    TLRPC.Chat chat = (TLRPC.Chat) obj.object;
                    if (chat.broadcast && !chat.megagroup && !chat.deactivated && !chat.left && !chat.kicked) {
                        recentChannels.add(chat);
                    }
                }
            }
            PotokDebugLog.log("FEED", "История поиска: найдено каналов=" + recentChannels.size());
            AndroidUtilities.runOnUIThread(() -> {
                for (TLRPC.Chat channel : recentChannels) {
                    addChannelToFeed(channel);
                }
            });
        });
    }

    /**
     * Добавляет канал в общий список и запускает загрузку его истории,
     * если его ещё нет в ленте.
     */
    private void addChannelToFeed(TLRPC.Chat channel) {
        String key = String.valueOf(channel.id);
        if (!resolvedChannels.containsKey(key)) {
            resolvedChannels.put(key, channel);
            // Добавляем в список всех каналов для фильтра (без дублей)
            boolean alreadyInList = false;
            for (TLRPC.Chat ch : allChannels) {
                if (ch.id == channel.id) { alreadyInList = true; break; }
            }
            if (!alreadyInList) allChannels.add(channel);
            loadHistory(key, channel);
        }
    }

    private void loadHistory(String key, TLRPC.Chat channel) {
        if (historyInFlight.contains(key)) return;
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
            if (refreshingFeed && historyInFlight.isEmpty()) {
                refreshingFeed = false;
                if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
            }
            if (error != null || !(response instanceof TLRPC.messages_Messages)) return;
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
            if (mo == null || mo.messageOwner == null) continue;
            long groupId = mo.messageOwner.grouped_id;
            boolean continuesCurrentGroup = groupId != 0 && currentItem != null && currentGroupId == groupId;

            if (!continuesCurrentGroup && result.size() >= MAX_POSTS_PER_CHANNEL) break;

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

    private int postDate(FeedItem item) {
        if (item.messages.isEmpty() || item.messages.get(0).messageOwner == null) return 0;
        return item.messages.get(0).messageOwner.date;
    }

    private void rebuildAndShowAllItems() {
        items.clear();
        int hiddenPostsCount = 0;
        for (java.util.Map.Entry<String, ArrayList<FeedItem>> entry : channelItems.entrySet()) {
            // Применяем фильтр — скрытые каналы не попадают в ленту
            if (hiddenChannelIds.contains(entry.getKey())) {
                hiddenPostsCount += entry.getValue().size();
                continue;
            }
            items.addAll(entry.getValue());
        }
        if (hiddenPostsCount > 0) {
            PotokDebugLog.log("FEED", "rebuildAndShowAllItems: скрыто " + hiddenPostsCount + " постов из фильтра");
        }
        Collections.sort(items, (a, b) -> Integer.compare(postDate(b), postDate(a)));
        notifyWhenReady();
    }

    private void notifyWhenReady() {
        if (listView == null || listView.getAdapter() == null) return;
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
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        getNotificationCenter().addObserver(this, NotificationCenter.didSetNewTheme);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        getNotificationCenter().removeObserver(this, NotificationCenter.didSetNewTheme);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagePlayingProgressDidChanged) {
            if (listView == null) return;
            int mid = (Integer) args[0];
            int count = listView.getChildCount();
            for (int a = 0; a < count; a++) {
                View child = listView.getChildAt(a);
                if (child instanceof PotokFeedPostCell) {
                    ((PotokFeedPostCell) child).updateAudioProgressIfPlaying(mid);
                }
            }
        } else if (id == NotificationCenter.didSetNewTheme) {
            updateWallpaper();
        }
    }

    private void updateWallpaper() {
        if (fragmentView instanceof org.telegram.ui.Components.SizeNotifierFrameLayout) {
            ((org.telegram.ui.Components.SizeNotifierFrameLayout) fragmentView)
                .setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateWallpaper();
        loadFeed();
    }

    private static class ArrowUpView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        ArrowUpView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(AndroidUtilities.dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            path.reset();
            float cx = w / 2f;
            float cy = h / 2f;
            float halfWidth = w * 0.34f;
            float halfHeight = h * 0.15f;
            path.moveTo(cx - halfWidth, cy + halfHeight);
            path.lineTo(cx, cy - halfHeight);
            path.lineTo(cx + halfWidth, cy + halfHeight);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawPath(path, paint);
        }
    }
}
