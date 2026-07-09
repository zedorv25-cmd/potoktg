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
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
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

/**
 * Лента — показывает посты из всех каналов на которые подписан пользователь,
 * смешанные и отсортированные по дате (свежие сверху).
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
    // username -> уже резолвленный канал (или null, если ещё не резолвлен)
    private final java.util.Map<String, TLRPC.Chat> resolvedChannels = new java.util.HashMap<>();
    // username -> посты этого канала, уже собранные в FeedItem (альбомы объединены)
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
        PotokDebugLog.log("PotokFeedLogo", "createView: НАЧАЛО");
        try {
            // Раньше здесь стоял actionBar.setAddToContainer(false) — полностью прятали
            // ActionBar, т.к. он же был источником "пустой полосы" (см. историю выше).
            // Теперь используем его штатно: заголовок "POTOK ЛЕНТА" (второе слово другим
            // цветом) + кнопка трёх точек. Настройка вынесена в setupActionBar(context).
            setupActionBar(context);
            View result = createViewInternal(context);
            PotokDebugLog.log("PotokFeedLogo", "createView: УСПЕШНО завершён, вернул " + (result != null ? result.getClass().getSimpleName() : "null"));
            return result;
        } catch (Throwable t) {
            PotokDebugLog.log("PotokFeedLogo", "createView: ИСКЛЮЧЕНИЕ " + t.getClass().getName() + ": " + t.getMessage()
                + "\n" + android.util.Log.getStackTraceString(t));
            throw t;
        }
    }

    private static final int MENU_ITEM_FILTER = 1;

    private void setupActionBar(Context context) {
        if (actionBar == null) {
            return;
        }
        // "POTOK " обычным цветом заголовка + "ЛЕНТА" акцентным цветом (тот же акцент,
        // что используется у спиннера pull-to-refresh — key_featuredStickers_addButton).
        android.text.SpannableStringBuilder title = new android.text.SpannableStringBuilder();
        title.append("POTOK ");
        int accentStart = title.length();
        title.append("ЛЕНТА");
        title.setSpan(new android.text.style.ForegroundColorSpan(Theme.getColor(Theme.key_featuredStickers_addButton)),
                accentStart, title.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        actionBar.setTitle(title);

        ActionBarMenu menu = actionBar.createMenu();
        org.telegram.ui.ActionBar.ActionBarMenuItem menuItem = menu.addItem(MENU_ITEM_FILTER, org.telegram.messenger.R.drawable.ic_ab_other);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == MENU_ITEM_FILTER) {
                    PotokDebugLog.log("PotokFeedLogo", "Клик: три точки — открываем меню ленты");
                    showThreeDotsMenu(context, menuItem);
                }
            }
        });
    }

    private View createViewInternal(Context context) {
        // Загружаем сохранённые скрытые каналы из SharedPreferences
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        hiddenChannelIds = new HashSet<>(prefs.getStringSet(PREFS_KEY_HIDDEN, new HashSet<>()));

        // AndroidUtilities.statusBarHeight в этом проекте всегда 0 (fillStatusBarHeight
        // здесь не вызывается), поэтому реальную высоту статусбара получаем синхронно
        // через системный ресурс "status_bar_height" — без WindowInsets/post{}, которые
        // раньше давали верное значение слишком поздно и нестабильно. Считаем один раз
        // и переиспользуем во всех местах, где раньше подставлялся ненадёжный 0.
        final int statusBarH = AndroidUtilities.getStatusBarHeight(context);

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

        // Теперь у нас есть настоящий ActionBar (заголовок "POTOK ЛЕНТА" + три точки,
        // см. setupActionBar) — он добавляется отдельным view поверх контента (общий
        // механизм ViewPagerActivity), и резервирует статусбар + свою высоту (56dp,
        // ActionBar.getCurrentActionBarHeight() — т.к. occupyStatusBar по умолчанию
        // true). Чтобы пост не заезжал под него НИ НА ПИКСЕЛЬ, отступ ленты считаем
        // ТОЧНО той же формулой: statusBarInset + getCurrentActionBarHeight().
        // statusBar берём из настоящих WindowInsets (см. предыдущую диагностику — это
        // совпало с getStatusBarHeight на тесте, но insets надёжнее в общем случае).
        listView.setPadding(0, statusBarH + ActionBar.getCurrentActionBarHeight(), 0, AndroidUtilities.dp(56));
        ViewCompat.setOnApplyWindowInsetsListener(frameLayout, (v, insets) -> {
            int topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            int totalTop = topInset + ActionBar.getCurrentActionBarHeight();
            listView.setPadding(0, totalTop, 0, AndroidUtilities.dp(56));
            PotokDebugLog.log("PotokFeedLogo", "WindowInsets: statusBars().top=" + topInset
                    + " + actionBarHeight=" + ActionBar.getCurrentActionBarHeight() + " = " + totalTop);
            return insets;
        });
        frameLayout.requestApplyInsets();
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
        int actionBarTotalHeight = statusBarH + ActionBar.getCurrentActionBarHeight();
        swipeRefreshLayout.setProgressViewOffset(false, actionBarTotalHeight + AndroidUtilities.dp(20), actionBarTotalHeight + AndroidUtilities.dp(76));
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
                // Пост, докрутившийся до видимой области экрана, считается
                // просмотренным — засчитываем это как прочтение в чате канала.
                checkVisibleFeedItemsRead();
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
            addChannelToFeed(channel);
        }
        // Загружаем посты из последних 10 каналов из истории поиска
        loadRecentSearchChannels();
    }

    private void addChannelToFeed(TLRPC.Chat channel) {
        String key = String.valueOf(channel.id);
        // Баг был здесь: loadHistory() раньше вызывался ТОЛЬКО внутри этого if,
        // то есть ровно один раз за всё время жизни фрагмента на канал — второй и
        // любой последующий вызов addChannelToFeed() для уже известного канала
        // (обновление свайпом вниз, повторный loadFeed() на onResume) ничего не
        // делал вообще. Поэтому новые посты не подтягивались никогда, кроме
        // самого первого раза — а он происходит только при пересоздании
        // фрагмента с нуля, то есть при полном рестарте приложения. Теперь
        // "канал уже известен" (resolvedChannels/allChannels — нужно только
        // чтобы не дублировать канал в списке фильтра) и "нужно перезапросить
        // историю" — две независимые вещи: второе происходит при каждом вызове.
        if (!resolvedChannels.containsKey(key)) {
            resolvedChannels.put(key, channel);
            boolean alreadyInList = false;
            for (TLRPC.Chat ch : allChannels) {
                if (ch.id == channel.id) { alreadyInList = true; break; }
            }
            if (!alreadyInList) allChannels.add(channel);
        }
        loadHistory(key, channel);
    }

    private void loadRecentSearchChannels() {
        DialogsSearchAdapter.loadRecentSearch(currentAccount, 0, (arrayList, hashMap) -> {
            ArrayList<TLRPC.Chat> recentChannels = new ArrayList<>();
            for (int i = 0; i < arrayList.size() && recentChannels.size() < MAX_RECENT_SEARCH_CHANNELS; i++) {
                DialogsSearchAdapter.RecentSearchObject obj = arrayList.get(i);
                if (obj.object instanceof TLRPC.Chat) {
                    TLRPC.Chat chat = (TLRPC.Chat) obj.object;
                    // chat.left = "вы не состоите в этом чате" — для каналов из истории
                    // поиска, на которые пользователь НЕ подписан (именно такие и нужны
                    // в ленте), этот флаг всегда true, поэтому раньше их отфильтровывало.
                    if (chat.broadcast && !chat.megagroup && !chat.deactivated && !chat.kicked) {
                        recentChannels.add(chat);
                    }
                }
            }
            PotokDebugLog.log("PotokFeedLogo", "История поиска: сырых записей=" + arrayList.size() + " каналов после фильтра=" + recentChannels.size());
            AndroidUtilities.runOnUIThread(() -> {
                for (TLRPC.Chat channel : recentChannels) {
                    PotokDebugLog.log("PotokFeedLogo", "История поиска: добавляю в ленту канал id=" + channel.id + " (" + channel.title + ")");
                    addChannelToFeed(channel);
                }
            });
        });
    }

    /**
     * Скрывает канал из ленты через тот же механизм, что и общий фильтр каналов
     * по кнопке в шапке (hiddenChannelIds/SharedPreferences). Вызывается из
     * пункта "Не показывать посты из этого канала" в меню поста (PotokFeedPostCell).
     */
    public void hideChannel(TLRPC.Chat channel) {
        if (channel == null) {
            return;
        }
        String id = String.valueOf(channel.id);
        hiddenChannelIds.add(id);
        Context context = getParentActivity();
        if (context != null) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putStringSet(PREFS_KEY_HIDDEN, hiddenChannelIds).apply();
        }
        PotokDebugLog.log("PotokFeedLogo", "Канал скрыт из поста: id=" + id + " скрыто всего=" + hiddenChannelIds.size());
        rebuildAndShowAllItems();
    }

    private ActionBarPopupWindow threeDotsMenuWindow;

    /**
     * Меню по кнопке "три точки" в шапке ленты — тот же тёмный стиль попапа,
     * что и у меню поста (ActionBarPopupWindowLayout + popup_fixed_alert4), а
     * не системный белый AlertDialog, как было раньше. Список каналов с
     * чекбоксами открывается ВНУТРИ этого же попапа через встроенный в
     * Telegram механизм swipe-back (FLAG_USE_SWIPEBACK/openForeground) — то
     * же самое, чем в оригинальном приложении открывается, например, подменю
     * "добавить в папку". Пункты "Отметить всё просмотренным", "Настройки
     * медиа", "Очистка кэша" и "Обратная связь" пока не реализованы —
     * заглушки, как и договаривались.
     */
    private void showThreeDotsMenu(Context context, View anchor) {
        int flags = ActionBarPopupWindow.ActionBarPopupWindowLayout.FLAG_USE_SWIPEBACK;
        ActionBarPopupWindow.ActionBarPopupWindowLayout layout =
            new ActionBarPopupWindow.ActionBarPopupWindowLayout(context, org.telegram.messenger.R.drawable.popup_fixed_alert4, null, flags);

        // ------------------------------------------------------------ подменю "Фильтр каналов"
        LinearLayout filterMenuView = new LinearLayout(context);
        filterMenuView.setOrientation(LinearLayout.VERTICAL);

        ActionBarMenuSubItem backItem = new ActionBarMenuSubItem(context, true, false, null);
        backItem.setTextAndIcon("Назад", org.telegram.messenger.R.drawable.ic_ab_back);
        backItem.setMinimumWidth(AndroidUtilities.dp(220));
        backItem.setOnClickListener(v -> layout.getSwipeBack().closeForeground());
        filterMenuView.addView(backItem);
        filterMenuView.addView(new ActionBarPopupWindow.GapView(context, null), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));

        // Скроллится, если каналов много — максимум 40% высоты экрана / 360dp,
        // тот же приём, что и у оригинального подменю папок в DialogsActivity.
        android.widget.ScrollView scrollView = new android.widget.ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, android.view.View.MeasureSpec.makeMeasureSpec(
                    (int) Math.min(
                        android.view.View.MeasureSpec.getSize(heightMeasureSpec),
                        Math.min(AndroidUtilities.displaySize.y * 0.4f, AndroidUtilities.dp(360))
                    ),
                    android.view.View.MeasureSpec.getMode(heightMeasureSpec)
                ));
            }
        };
        scrollView.setVerticalScrollBarEnabled(false);
        LinearLayout channelsList = new LinearLayout(context);
        channelsList.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(channelsList);

        // Изменения копятся в pendingHidden и применяются только по кнопке
        // "Применить" — так же, как раньше вело себя "Отмена" в AlertDialog.
        final Set<String> pendingHidden = new HashSet<>(hiddenChannelIds);
        for (TLRPC.Chat ch : allChannels) {
            final String chId = String.valueOf(ch.id);
            org.telegram.ui.Cells.CheckBoxCell cell = new org.telegram.ui.Cells.CheckBoxCell(context, 1);
            cell.setText(ch.title, "", !pendingHidden.contains(chId), false);
            cell.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
            cell.setOnClickListener(v -> {
                boolean nowChecked = pendingHidden.contains(chId);
                if (nowChecked) {
                    pendingHidden.remove(chId);
                } else {
                    pendingHidden.add(chId);
                }
                ((org.telegram.ui.Cells.CheckBoxCell) v).setChecked(nowChecked, true);
            });
            channelsList.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        }
        filterMenuView.addView(scrollView);
        filterMenuView.addView(new ActionBarPopupWindow.GapView(context, null), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));

        ActionBarMenuSubItem applyItem = new ActionBarMenuSubItem(context, false, true, null);
        applyItem.setTextAndIcon("Применить", org.telegram.messenger.R.drawable.msg_check_s);
        applyItem.setMinimumWidth(AndroidUtilities.dp(220));
        applyItem.setOnClickListener(v -> {
            hiddenChannelIds = new HashSet<>(pendingHidden);
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putStringSet(PREFS_KEY_HIDDEN, hiddenChannelIds).apply();
            PotokDebugLog.log("PotokFeedLogo", "Фильтр применён: скрыто=" + hiddenChannelIds.size());
            rebuildAndShowAllItems();
            if (threeDotsMenuWindow != null) threeDotsMenuWindow.dismiss();
        });
        filterMenuView.addView(applyItem);

        int filterMenuIndex = layout.addViewToSwipeBack(filterMenuView);

        // ------------------------------------------------------------ главный список пунктов
        ActionBarMenuSubItem filterItem = new ActionBarMenuSubItem(context, true, false, null);
        filterItem.setTextAndIcon("Фильтр каналов", org.telegram.messenger.R.drawable.menu_tag_filter);
        filterItem.setMinimumWidth(AndroidUtilities.dp(220));
        filterItem.setOnClickListener(v -> {
            if (allChannels.isEmpty()) {
                PotokDebugLog.log("PotokFeedLogo", "Фильтр: каналов пока нет");
                return;
            }
            layout.getSwipeBack().openForeground(filterMenuIndex);
        });
        layout.addView(filterItem);

        // Пока заглушки без действия — как и договаривались, реализован только фильтр.
        ActionBarMenuSubItem markReadItem = new ActionBarMenuSubItem(context, false, false, null);
        markReadItem.setTextAndIcon("Отметить всё просмотренным", org.telegram.messenger.R.drawable.msg_markread);
        markReadItem.setMinimumWidth(AndroidUtilities.dp(220));
        layout.addView(markReadItem);

        ActionBarMenuSubItem mediaSettingsItem = new ActionBarMenuSubItem(context, false, false, null);
        mediaSettingsItem.setTextAndIcon("Настройки медиа", org.telegram.messenger.R.drawable.msg_photo_settings);
        mediaSettingsItem.setMinimumWidth(AndroidUtilities.dp(220));
        layout.addView(mediaSettingsItem);

        ActionBarMenuSubItem clearCacheItem = new ActionBarMenuSubItem(context, false, false, null);
        clearCacheItem.setTextAndIcon("Очистка кэша", org.telegram.messenger.R.drawable.msg_clearcache);
        clearCacheItem.setMinimumWidth(AndroidUtilities.dp(220));
        layout.addView(clearCacheItem);

        ActionBarMenuSubItem feedbackItem = new ActionBarMenuSubItem(context, false, true, null);
        feedbackItem.setTextAndIcon("Обратная связь", org.telegram.messenger.R.drawable.msg_help);
        feedbackItem.setMinimumWidth(AndroidUtilities.dp(220));
        layout.addView(feedbackItem);

        threeDotsMenuWindow = new ActionBarPopupWindow(layout, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
        threeDotsMenuWindow.setFocusable(true);
        threeDotsMenuWindow.setOutsideTouchable(true);
        threeDotsMenuWindow.setClippingEnabled(true);
        threeDotsMenuWindow.setAnimationStyle(org.telegram.messenger.R.style.PopupAnimation);
        threeDotsMenuWindow.setOnDismissListener(() -> threeDotsMenuWindow = null);

        layout.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        );

        int[] location = new int[2];
        anchor.getLocationInWindow(location);
        int x = location[0] + anchor.getWidth() - layout.getMeasuredWidth();
        int y = location[1] + anchor.getHeight();
        threeDotsMenuWindow.showAtLocation(anchor, android.view.Gravity.LEFT | android.view.Gravity.TOP, x, y);
        ActionBarPopupWindow.startAnimation(layout);
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
                PotokDebugLog.log("PotokFeedLogo", "loadHistory: ОШИБКА для канала id=" + channel.id + " (" + channel.title + ") error="
                        + (error != null ? (error.code + " " + error.text) : "response не messages_Messages: " + response));
                return;
            }
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            getMessagesController().putUsers(res.users, false);
            getMessagesController().putChats(res.chats, false);

            ArrayList<MessageObject> messageObjects = new ArrayList<>();
            for (TLRPC.Message message : res.messages) {
                // Служебные сообщения (TL_messageService) — это не посты канала, а
                // системные строки вида "X закрепил(а) фотографию", "канал создан",
                // смена фото канала и т.п. Раньше они не отфильтровывались нигде и
                // попадали в ленту как пустой/странный пост. Пропускаем их целиком —
                // в ленте должны быть только настоящие посты (TL_message).
                if (message instanceof TLRPC.TL_messageService) {
                    continue;
                }
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
        for (java.util.Map.Entry<String, ArrayList<FeedItem>> entry : channelItems.entrySet()) {
            if (hiddenChannelIds.contains(entry.getKey())) continue;
            items.addAll(entry.getValue());
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
            // После обновления списка нужно дождаться прохода layout (позиции ещё не
            // известны сразу после notifyDataSetChanged), иначе findFirstVisibleItemPosition
            // ниже вернёт NO_POSITION.
            listView.post(this::checkVisibleFeedItemsRead);
        }
    }

    /**
     * Просмотр поста В ЛЕНТЕ засчитывается как просмотр этого же сообщения в самом
     * чате канала — иначе цифра непрочитанных в списке чатов продолжает показывать
     * то, что пользователь уже увидел здесь. Логика зеркалит то, как ChatActivity
     * помечает видимые сообщения прочитанными при скролле (см. markDialogAsRead
     * с maxPositiveId/maxDate, вычисленными по видимым сообщениям) — тут вместо
     * позиций сообщений в чате берём позиции постов, реально видимых в RecyclerView
     * ленты прямо сейчас.
     */
    private void checkVisibleFeedItemsRead() {
        if (listView == null || listViewLayoutManager == null || items.isEmpty()) {
            return;
        }
        int first = listViewLayoutManager.findFirstVisibleItemPosition();
        int last = listViewLayoutManager.findLastVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) {
            return;
        }
        for (int i = Math.max(0, first); i <= last && i < items.size(); i++) {
            markFeedItemAsRead(items.get(i));
        }
    }

    /**
     * Помечает прочитанным (локально + серверу через отложенную read-задачу внутри
     * markDialogAsRead) сообщение(я) одного поста в чате его канала. Пост в нашей
     * ленте может быть альбомом из нескольких MessageObject — берём максимальный id
     * и максимальную дату среди них, ровно как это делает сам Telegram при чтении
     * альбома целиком. Если в посте нет ни одного реально непрочитанного сообщения
     * (уже было прочитано раньше — в т.ч. нами же на предыдущем скролле), ничего не
     * делаем, чтобы не слать лишние сетевые запросы при каждом скролле ленты.
     */
    private void markFeedItemAsRead(FeedItem item) {
        if (item == null || item.messages.isEmpty()) {
            return;
        }
        int maxId = 0;
        int maxDate = 0;
        long dialogId = 0;
        for (MessageObject mo : item.messages) {
            if (mo == null || mo.messageOwner == null) continue;
            dialogId = mo.getDialogId();
            if (mo.getId() > maxId) maxId = mo.getId();
            if (mo.messageOwner.date > maxDate) maxDate = mo.messageOwner.date;
        }
        if (maxId <= 0 || dialogId == 0) {
            return;
        }
        // Баг был здесь: mo.isUnread() читает messageOwner.unread — а это поле
        // выставляется MessagesController/MessagesStorage ТОЛЬКО когда сообщение
        // проходит через обычный локальный пайплайн загрузки диалога. Наши посты
        // ленты строятся напрямую из ответа messages.getHistory (см. loadHistory())
        // в обход этого пайплайна, поэтому messageOwner.unread у них всегда false
        // по умолчанию — hasUnread был всегда false, и markDialogAsRead ниже
        // молча никогда не вызывался. Реальное "прочитано ли" сравниваем вручную
        // с read_inbox_max_id диалога канала: пост непрочитан, если его id больше.
        TLRPC.Dialog dialog = getMessagesController().getDialog(dialogId);
        if (dialog == null || maxId <= dialog.read_inbox_max_id) {
            return;
        }
        for (MessageObject mo : item.messages) {
            if (mo != null) mo.setIsRead();
        }
        getMessagesController().markDialogAsRead(dialogId, maxId, maxId, maxDate, false, 0, 0, true, 0);
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
    }

    // ВАЖНО: раньше loadFeed() вызывался из onResume(). Проблема в том, что
    // onResume() здесь дёргается НЕ только при реальном переключении на вкладку
    // "Лента" — в архитектуре ViewPagerActivity (см. setVisibility()) он также
    // срабатывает при любом возврате фокуса ко всей Activity целиком, например
    // при закрытии PhotoViewer/системных диалогов/смене приложений, даже если
    // пользователь всё это время сидел на вкладке "Лента" и никуда с неё не
    // уходил. Из-за этого лента лишний раз дёргала messages.getHistory по всем
    // каналам — та самая "лишняя трата ресурсов". onBecomeFullyVisible(), в
    // отличие от onResume(), вызывается строго тогда, когда эта вкладка реально
    // становится полностью видимой страницей пейджера (см. ViewPagerActivity.
    // PageAdapter.setVisibility(), fragment.onBecomeFullyVisible() при
    // newVisibility >= 1) — это и есть корректный третий триггер обновления
    // ("вернулся в ленту с другого раздела"), не срабатывающий на посторонние
    // события. Итого обновление ленты теперь строго по 3 триггерам: первый показ
    // (createView() -> loadFeed(), см. выше), свайп-обновление (swipeRefreshLayout)
    // и вот этот — реальный возврат на вкладку.
    @Override
    public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
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
