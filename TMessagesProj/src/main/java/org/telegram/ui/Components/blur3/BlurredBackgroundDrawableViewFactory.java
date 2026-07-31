package org.telegram.ui.Components.blur3;

import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawableRenderNode;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSource;
import org.telegram.ui.Components.chat.ViewPositionWatcher;

import me.vkryl.core.reference.ReferenceList;

public class BlurredBackgroundDrawableViewFactory {

    private final BlurredBackgroundSource source;

    public BlurredBackgroundDrawableViewFactory(BlurredBackgroundSource source) {
        this.source = source;
    }

    public BlurredBackgroundDrawableViewFactory(ViewPositionWatcher watcher, ViewGroup parent, BlurredBackgroundSource source) {
        this(source);
        setSourceRootView(watcher, parent);
    }

    public void setSourceRootView(ViewPositionWatcher watcher, ViewGroup parent) {
        this.viewPositionWatcher = watcher;
        this.parent = parent;
    }

    private @Nullable ReferenceList<View> linkedViews;
    private @Nullable ViewPositionWatcher viewPositionWatcher;
    private @Nullable ViewGroup parent;

    public void setLinkedViewsRef(@Nullable ReferenceList<View> linkedViews) {
        this.linkedViews = linkedViews;
    }

    public void invalidateAllLinkedViews() {
        if (linkedViews != null) {
            for (View v : linkedViews) {
                v.invalidate();
            }
        }
    }


    private boolean isLiquidGlassEffectAllowed;

    public void setLiquidGlassEffectAllowed(boolean liquidGlassEffectAllowed) {
        isLiquidGlassEffectAllowed = liquidGlassEffectAllowed;
    }

    public BlurredBackgroundDrawable create() {
        return create(null);
    }

    public BlurredBackgroundDrawable create(View view) {
        return create(view, null);
    }

    public BlurredBackgroundDrawable create(View view, boolean multiwindow) {
        return create(view, null, multiwindow);
    }

    public BlurredBackgroundDrawable create(View view, BlurredBackgroundColorProvider provider) {
        return create(view, provider, false);
    }

    public BlurredBackgroundDrawable create(View view, BlurredBackgroundColorProvider provider, boolean multiwindow) {
        final BlurredBackgroundDrawable drawable = source.createDrawable();
        if (isLiquidGlassEffectAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (drawable instanceof BlurredBackgroundDrawableRenderNode) {
                ((BlurredBackgroundDrawableRenderNode) drawable).setLiquidGlassEffectAllowed();
            }
        }

        drawable.setColorProvider(provider);

        if (linkedViews != null && view != null) {
            linkedViews.add(view);
        }

        if (viewPositionWatcher != null && parent != null && view != null) {
            final int[] potokLogCounter = {0};
            viewPositionWatcher.subscribe(view, parent, (v, pos) -> {
                drawable.setSourceOffset(pos.left, pos.top);
                view.invalidate();
                // ДИАГНОСТИКА зависания при скролле (см. подробный комментарий в
                // MainTabsActivity.blur3_invalidateBlur). Логируем не каждый раз (иначе сам лог
                // станет узким местом при фликах в десятки кадров/сек) — только первый вызов и
                // затем раз в 60 вызовов, этого достаточно, чтобы увидеть в хвосте лога, шёл ли
                // этот callback непрерывно (много строк подряд перед зависанием) или прекратился.
                potokLogCounter[0]++;
                if (potokLogCounter[0] == 1 || potokLogCounter[0] % 60 == 0) {
                    org.telegram.ui.PotokDebugLog.d("BLUR3", "onPositionChanged view=" + v.getClass().getSimpleName()
                            + " offset=" + pos.left + "," + pos.top + " count=" + potokLogCounter[0]);
                }
            }, multiwindow);
        }

        return drawable;
    }
}
