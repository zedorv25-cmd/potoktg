package org.telegram.ui;

import androidx.recyclerview.widget.RecyclerView;

/**
 * Универсальный helper: навешивается на RecyclerView любого таба (Чаты/Лента/Траф/Контакты)
 * и скрывает/показывает таббар через MainTabsActivityController при скролле списка.
 *
 * Использование:
 *   recyclerView.addOnScrollListener(new TabBarScrollHider(controller));
 *
 * Где controller — тот же MainTabsActivityController, что передаётся в DialogsActivity.
 */
public class TabBarScrollHider extends RecyclerView.OnScrollListener {

    private static final int SCROLL_THRESHOLD_DP = 4; // минимальный сдвиг, чтобы не дёргалось от мелкого дребезга

    private final MainTabsActivityController controller;
    private final float thresholdPx;
    private boolean tabsVisible = true;
    private int accumulatedDy = 0;

    public TabBarScrollHider(MainTabsActivityController controller) {
        this.controller = controller;
        this.thresholdPx = org.telegram.messenger.AndroidUtilities.dp(SCROLL_THRESHOLD_DP);
    }

    @Override
    public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
        if (controller == null || dy == 0) {
            return;
        }

        // если направление сменилось — сбрасываем накопленный сдвиг, чтобы не было "залипания"
        if ((dy > 0 && accumulatedDy < 0) || (dy < 0 && accumulatedDy > 0)) {
            accumulatedDy = 0;
        }
        accumulatedDy += dy;

        if (accumulatedDy > thresholdPx && tabsVisible) {
            tabsVisible = false;
            controller.setTabsVisible(false);
            accumulatedDy = 0;
        } else if (accumulatedDy < -thresholdPx && !tabsVisible) {
            tabsVisible = true;
            controller.setTabsVisible(true);
            accumulatedDy = 0;
        }
    }

    @Override
    public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
        // когда список долистали до самого верха — всегда показываем таббар,
        // даже если последний жест технически был "вниз" на маленьком отрезке
        if (newState == RecyclerView.SCROLL_STATE_IDLE) {
            androidx.recyclerview.widget.RecyclerView.LayoutManager lm = recyclerView.getLayoutManager();
            if (lm instanceof androidx.recyclerview.widget.LinearLayoutManager) {
                int firstVisible = ((androidx.recyclerview.widget.LinearLayoutManager) lm).findFirstCompletelyVisibleItemPosition();
                if (firstVisible == 0 && !tabsVisible) {
                    tabsVisible = true;
                    if (controller != null) {
                        controller.setTabsVisible(true);
                    }
                }
            }
        }
    }

    /** Принудительно показать таббар (например, при возврате на экран) */
    public void forceShow() {
        tabsVisible = true;
        accumulatedDy = 0;
        if (controller != null) {
            controller.setTabsVisible(true);
        }
    }
}
