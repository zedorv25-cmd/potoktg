package org.telegram.ui;

import android.content.Context;
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
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
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
public class PotokFeedFragment extends BaseFragment implements MainTabsActivity.TabFragmentDelegate, NotificationCenter.NotificationCenterDelegate {

    private static final int MESSAGES_TO_LOAD_PER_CHANNEL = 30;
    private static final int MAX_POSTS_PER_CHANNEL = 10;

    private RecyclerListView listView;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefreshLayout;
    private boolean refreshingFeed = false;
    private LinearLayoutManager listViewLayoutManager;
    private org.telegram.ui.Components.RecyclerAnimationScrollHelper scrollHelper;
    private FrameLayout scrollToTopButton;
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
        PotokDebugLog.log("PotokFeedLogo", "createView: НАЧАЛО");
        try {
            View result = createViewInternal(context);
            PotokDebugLog.log("PotokFeedLogo", "createView: УСПЕШНО завершён, вернул " + (result != null ? result.getClass().getSimpleName() : "null"));
            return result;
        } catch (Throwable t) {
            PotokDebugLog.log("PotokFeedLogo", "createView: ИСКЛЮЧЕНИЕ " + t.getClass().getName() + ": " + t.getMessage()
                + "\n" + android.util.Log.getStackTraceString(t));
            throw t;
        }
    }

    private View createViewInternal(Context context) {
        org.telegram.ui.Components.SizeNotifierFrameLayout frameLayout = new org.telegram.ui.Components.SizeNotifierFrameLayout(context);
        fragmentView = frameLayout;
        // Фикс "карнавал полосок": лента раньше рисовала сплошной фон темы под
        // карточками, из-за чего фон не совпадал с обоями, которые пользователь
        // выставил в самих чатах. Теперь используем тот же механизм, что и ChatActivity —
        // SizeNotifierFrameLayout.setBackgroundImage с текущими обоями темы.
        frameLayout.setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion());

        listView = new RecyclerListView(context);
        listViewLayoutManager = new LinearLayoutManager(context);
        listView.setLayoutManager(listViewLayoutManager);
        scrollHelper = new org.telegram.ui.Components.RecyclerAnimationScrollHelper(listView, listViewLayoutManager);

        // Отступ сверху = статусбар + высота таббара снизу MainTabsActivity (не тулбар — его нет)
        int topPadding = AndroidUtilities.statusBarHeight + AndroidUtilities.dp(56);
        listView.setPadding(0, topPadding, 0, AndroidUtilities.dp(56));
        listView.setClipToPadding(false);

        listView.setAdapter(new RecyclerView.Adapter<RecyclerListView.Holder>() {
            @Override
            public RecyclerListView.Holder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
                PotokFeedPostCell cell = new PotokFeedPostCell(context, null);
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT);
                // Фикс "карточки впритык": раньше пост занимал всю ширину без отступов
                // и соседние посты стыковались друг с другом. Теперь — заметные, но не
                // большие отступы со всех 4 сторон, как отдельные карточки в канале.
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

        // Фикс: свайп сверху вниз на самом верху ленты — обновляет посты (как в
        // большинстве соцсетей), со стандартным материальным спиннером-индикатором.
        swipeRefreshLayout = new androidx.swiperefreshlayout.widget.SwipeRefreshLayout(context);
        swipeRefreshLayout.setProgressViewOffset(false, AndroidUtilities.statusBarHeight + AndroidUtilities.dp(20), AndroidUtilities.statusBarHeight + AndroidUtilities.dp(76));
        swipeRefreshLayout.setColorSchemeColors(Theme.getColor(Theme.key_featuredStickers_addButton));
        swipeRefreshLayout.setOnRefreshListener(() -> {
            refreshingFeed = true;
            loadFeed();
            // Страховка: если запросы по какой-то причине зависнут (обрыв сети),
            // спиннер всё равно скроется через 8 секунд, а не будет висеть вечно.
            AndroidUtilities.runOnUIThread(() -> {
                if (refreshingFeed) {
                    refreshingFeed = false;
                    if (swipeRefreshLayout != null) {
                        swipeRefreshLayout.setRefreshing(false);
                    }
                }
            }, 8000);
        });
        swipeRefreshLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        frameLayout.addView(swipeRefreshLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // --- Надпись "ПОТОК" в верхней полосе (там же, где раньше было пусто) ---
        TextView logoView = new TextView(context);
        logoView.setText("ПОТОК");
        logoView.setTextSize(22);
        logoView.setTypeface(AndroidUtilities.bold());
        // Фикс: раньше цвет брался из темозависимого ключа и, судя по всему, где-то
        // сливался с фоном/обоями. Логотип — фирменный элемент, красим его всегда
        // белым (как на примере), плюс небольшая тень для контраста на светлых обоях.
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
        // На случай сомнений в порядке отрисовки — явно поднимаем поверх всего
        // остального содержимого фрагмента (обои/список/спиннер обновления).
        logoView.bringToFront();

        // Диагностика: раз логотип по неизвестной причине не появлялся визуально —
        // пишем в файл лога реальные размеры/позицию/видимость уже ПОСЛЕ layout-прохода,
        // чтобы понять — logoView вообще не создаётся/не в иерархии, или создаётся,
        // но с нулевым размером/вне экрана/перекрыт чем-то.
        logoView.post(() -> {
            int[] loc = new int[2];
            logoView.getLocationOnScreen(loc);
            PotokDebugLog.log("PotokFeedLogo",
                "logoView: width=" + logoView.getWidth() + " height=" + logoView.getHeight()
                + " visibility=" + logoView.getVisibility() + " alpha=" + logoView.getAlpha()
                + " screenX=" + loc[0] + " screenY=" + loc[1]
                + " parent=" + (logoView.getParent() != null)
                + " statusBarHeight=" + AndroidUtilities.statusBarHeight
                + " frameLayout.w=" + frameLayout.getWidth() + " frameLayout.h=" + frameLayout.getHeight()
                + " frameLayout.childCount=" + frameLayout.getChildCount()
                + " text='" + logoView.getText() + "'");
        });

        // --- Кнопка "три точки" (настройки ленты) — справа в той же верхней полосе ---
        // Пока без функционала — открывает пустое меню, сама механика (открытие/закрытие)
        // готова, содержимое добавим отдельно позже.
        ImageView feedMenuButton = new ImageView(context);
        feedMenuButton.setImageResource(org.telegram.messenger.R.drawable.ic_ab_other);
        feedMenuButton.setColorFilter(0xFFFFFFFF);
        feedMenuButton.setScaleType(ImageView.ScaleType.CENTER);
        feedMenuButton.setBackground(Theme.createSelectorDrawable(0x33ffffff, Theme.RIPPLE_MASK_CIRCLE_20DP));
        FrameLayout.LayoutParams feedMenuParams = new FrameLayout.LayoutParams(AndroidUtilities.dp(40), AndroidUtilities.dp(40));
        feedMenuParams.gravity = Gravity.RIGHT | Gravity.TOP;
        feedMenuParams.rightMargin = AndroidUtilities.dp(8);
        feedMenuParams.topMargin = AndroidUtilities.statusBarHeight + AndroidUtilities.dp(8);
        feedMenuButton.setOnClickListener(v -> {
            PotokDebugLog.log("PotokFeedLogo", "Клик: три точки (настройки ленты)");
            android.widget.PopupMenu popupMenu = new android.widget.PopupMenu(context, feedMenuButton);
            // Временно пусто — механика открытия готова, пункты добавим позже.
            popupMenu.show();
        });
        frameLayout.addView(feedMenuButton, feedMenuParams);
        feedMenuButton.bringToFront();

        // --- Кнопка "наверх" ---
        scrollToTopButton = new FrameLayout(context);
        GradientDrawable circleBg = new GradientDrawable();
        circleBg.setShape(GradientDrawable.OVAL);
        circleBg.setColor(Theme.getColor(Theme.key_dialogFloatingButton));
        scrollToTopButton.setBackground(circleBg);
        scrollToTopButton.setElevation(AndroidUtilities.dp(4));
        scrollToTopButton.setVisibility(View.GONE);
        scrollToTopButton.setAlpha(0f);

        // Векторная стрелка-шеврон + стержень — рисуется через Path, поэтому одинаково
        // чёткая на любом dpi (системный android.R.drawable.arrow_up_float — низкого
        // разрешения и визуально выглядит как треугольник, а не как стрелка).
        ArrowUpView arrowUp = new ArrowUpView(context);
        arrowUp.setColor(Theme.getColor(Theme.key_dialogFloatingIcon));
        scrollToTopButton.addView(arrowUp, LayoutHelper.createFrame(24, 24, Gravity.CENTER));

        scrollToTopButton.setOnClickListener(v -> {
            if (listView == null || scrollHelper == null) {
                return;
            }
            // Раньше здесь был smoothScrollToPosition(0) — он прокручивает
            // последовательно через ВСЕ посты между текущей позицией и началом,
            // что на длинной ленте выглядит как долгое пролистывание. scrollHelper
            // (тот же паттерн, что у кнопки "к началу" в DialogsActivity) мгновенно
            // переносит layout к позиции 0 и красиво доезжают только элементы,
            // уже видимые на экране — без полного перебора истории.
            scrollHelper.setScrollDirection(org.telegram.ui.Components.RecyclerAnimationScrollHelper.SCROLL_DIRECTION_UP);
            scrollHelper.scrollToPosition(0, 0, false, true);
        });

        // Отступ снизу должен учитывать реальную высоту плавающего таббара
        // (DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS = 56 + 8*2 = 72dp, не 56dp)
        // и системный navigationBarHeight (жестовая панель/кнопки) — без него
        // кнопка уходит под таббар на части устройств, см. AndroidUtilities.navigationBarHeight.
        int scrollButtonBottomMarginPx = AndroidUtilities.navigationBarHeight
            + AndroidUtilities.dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS)
            + AndroidUtilities.dp(16);
        int scrollButtonBottomMarginDp = (int) (scrollButtonBottomMarginPx / AndroidUtilities.density);
        frameLayout.addView(scrollToTopButton, LayoutHelper.createFrame(48, 48, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 16, scrollButtonBottomMarginDp));

        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm == null) return;
                boolean shouldShow = lm.findFirstVisibleItemPosition() > 1;
                if (shouldShow && scrollToTopButton.getVisibility() != View.VISIBLE) {
                    scrollToTopButton.setVisibility(View.VISIBLE);
                    scrollToTopButton.animate().alpha(1f).setDuration(150).start();
                } else if (!shouldShow && scrollToTopButton.getVisibility() == View.VISIBLE) {
                    scrollToTopButton.animate().alpha(0f).setDuration(150)
                        .withEndAction(() -> scrollToTopButton.setVisibility(View.GONE)).start();
                }
            }
        });

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
        StringBuilder channelNames = new StringBuilder();
        for (TLRPC.Chat channel : channels) {
            channelNames.append(channel.title).append(" (id=").append(channel.id).append("); ");
        }
        PotokDebugLog.log("PotokFeedLogo", "loadFeed: найдено каналов = " + channels.size() + ": " + channelNames);
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
            if (refreshingFeed && historyInFlight.isEmpty()) {
                refreshingFeed = false;
                if (swipeRefreshLayout != null) {
                    swipeRefreshLayout.setRefreshing(false);
                }
            }
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

    /**
     * Простой шеврон "^" (две сходящиеся вверх линии, без стержня) — по просьбе
     * убрать "стрелу", оставить только галочку.
     */
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
