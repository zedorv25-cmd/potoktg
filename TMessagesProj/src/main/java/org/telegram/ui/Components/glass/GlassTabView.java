package org.telegram.ui.Components.glass;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.RawRes;
import androidx.annotation.StringRes;
import androidx.annotation.DrawableRes;
import androidx.core.graphics.ColorUtils;
import androidx.core.math.MathUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DocumentObject;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.PremiumGradient;
import org.telegram.ui.Components.RLottieDrawable;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.MainTabsLayout;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;

public class GlassTabView extends FrameLayout implements MainTabsLayout.Tab, FactorAnimator.Target {
    private final TextView textView;
    private final RLottieImageView imageView;
    private BackupImageView backupImageView;
    public androidx.appcompat.widget.AppCompatImageView staticIconView;
    private Theme.ResourcesProvider resourcesProvider;
    private final Paint paintCounterBackground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final AnimatedTextView.AnimatedTextDrawable counter;

    private static final int ANIMATOR_ID_IS_SELECTED = 0;
    private static final int ANIMATOR_ID_COUNTER_VISIBLE = 1;
    private static final int ANIMATOR_ID_COUNTER_ERROR = 2;

    private final BoolAnimator isSelectedAnimator = new BoolAnimator(ANIMATOR_ID_IS_SELECTED, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 320);
    private final BoolAnimator isHasCounterAnimator = new BoolAnimator(ANIMATOR_ID_COUNTER_VISIBLE, this, CubicBezierInterpolator.EASE_OUT_QUINT, 380);
    private final BoolAnimator isHasCounterErrorAnimator = new BoolAnimator(ANIMATOR_ID_COUNTER_ERROR, this, CubicBezierInterpolator.EASE_OUT_QUINT, 380);
    private int colorSelected;
    private int colorSelectedText;
    private int colorDefault;
    private boolean usePremiumCounter;

    private TabAnimation tabAnimation;
    private TLRPC.TL_attachMenuBot tabAnimationBot;

    private final TextPaint defaultTextPaint;

    public GlassTabView(@NonNull Context context) {
        super(context);
        imageView = new RLottieImageView(context);
        addView(imageView, LayoutHelper.createFrame(44, 44, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, -6, 0, 0));

        imageView.setColorFilter(new PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN));

        textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f);
        textView.setSingleLine();
        textView.setLines(1);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textView.setTypeface(AndroidUtilities.bold());
        textView.setGravity(Gravity.CENTER);

        defaultTextPaint = new TextPaint(textView.getPaint());
        addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 28.33f, 0, 0));

        counter = new AnimatedTextView.AnimatedTextDrawable();
        counter.setTypeface(AndroidUtilities.bold());
        counter.setCallback(this);
        counter.setGravity(Gravity.CENTER);
        counter.setTextColor(Color.WHITE);
        counter.setTextSize(dp(10));
    }

    private boolean hasVisualWidth;
    private float visualWidth;
    public void setVisualWidth(float width) {
        hasVisualWidth = true;
        if (visualWidth != width) {
            visualWidth = width;
            checkVisualWidth();
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        checkVisualWidth();
    }

    private void checkVisualWidth() {
        if (hasVisualWidth) {
            final float offset = (visualWidth - getMeasuredWidth()) / 2f;
            imageView.setTranslationX(offset);
            textView.setTranslationX(offset);
        }
    }

    private static final RectF tmpRectF = new RectF();

    private boolean hasGestureSelectedOverride;
    private float gestureSelectedOverride;
    private boolean skipDrawSelector;

    public void setGestureSelectedOverride(float gestureSelectedOverride, boolean allow) {
        this.gestureSelectedOverride = gestureSelectedOverride;
        this.hasGestureSelectedOverride = allow;
        invalidate();
    }

    public void setSkipDrawSelector(boolean skipDrawSelector) {
        if (this.skipDrawSelector != skipDrawSelector) {
            this.skipDrawSelector = skipDrawSelector;
            invalidate();
        }
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        final float viewWidth = hasVisualWidth ? visualWidth : getWidth();
        final float selectedFactor = hasGestureSelectedOverride ? gestureSelectedOverride : isSelectedAnimator.getFloatValue();
        if (selectedFactor > 0 && !skipDrawSelector) {
            final float alpha = AnimatorUtils.DECELERATE_INTERPOLATOR.getInterpolation(selectedFactor);

            paintCounterBackground.setColor(Theme.multAlpha(colorSelected, 0.09f * alpha));
            tmpRectF.set(0, 0, viewWidth, getHeight());
            final float r = Math.min(tmpRectF.width(), tmpRectF.height()) / 2f;
            final float s = lerp(0.6f, 1, selectedFactor) * MathUtils.clamp(attachScale, 0, 1);
            canvas.save();
            canvas.scale(s, s, tmpRectF.centerX(), tmpRectF.centerY());
            canvas.drawRoundRect(tmpRectF, r, r, paintCounterBackground);
            canvas.restore();
        }

        final float hasCounter = (usePremiumCounter ? 1f : isHasCounterAnimator.getFloatValue()) * attachScale;
        final boolean saveLayer = hasCounter > 0;
        if (saveLayer) {
            canvas.saveLayer(0, 0, viewWidth, getHeight(), null);
        }

        super.dispatchDraw(canvas);

        if (hasCounter > 0) {
            canvas.save();

            final float gap = dpf2(1.33f);
            final float cx = viewWidth / 2f + dpf2(11);
            final float cy = dpf2(10);
            final float height = dpf2(16);
            final float width = Math.max(height, counter.getCurrentWidth() + dp(8));
            final float rOuter = dpf2(9.333f);
            final float rInner = dpf2(8f);
            tmpRectF.set(
                    cx - width / 2f - gap,
                    cy - height / 2f - gap,
                    cx + width / 2f + gap,
                    cy + height / 2f + gap
            );

            canvas.scale(hasCounter, hasCounter, cx, cy);
            canvas.drawRoundRect(tmpRectF, rOuter, rOuter, Theme.PAINT_CLEAR);
            tmpRectF.inset(gap, gap);

            if (usePremiumCounter) {
                if (premiumStarDrawable == null) {
                    premiumStarDrawable = getContext().getResources().getDrawable(R.drawable.star).mutate();
                }

                PremiumGradient.getInstance().updateMainGradientMatrix(0, 0, dp(96), dp(16), 0, 0);
                canvas.drawRoundRect(tmpRectF, rInner, rInner, PremiumGradient.getInstance().getMainGradientPaint());
                int x = (int)(cx - dpf2(7f));
                int y = (int)(cy - dpf2(7f));
                premiumStarDrawable.setBounds(x, y, x + dp(14), y + dp(14));
                premiumStarDrawable.draw(canvas);
            } else {
                paintCounterBackground.setColor(ColorUtils.blendARGB(Theme.getColor(Theme.key_telegram_color), Theme.getColor(Theme.key_fill_RedNormal), isHasCounterErrorAnimator.getFloatValue()));
                canvas.drawRoundRect(tmpRectF, rInner, rInner, paintCounterBackground);
                counter.setBounds(tmpRectF);
                counter.draw(canvas);
            }
            canvas.restore();
        }

        if (saveLayer) {
            canvas.restore();
        }
    }

    private Drawable premiumStarDrawable;

    public void setCounter(String text, boolean isError, boolean animated) {
        counter.setText(text, animated);
        isHasCounterAnimator.setValue(!TextUtils.isEmpty(text), animated);
        isHasCounterErrorAnimator.setValue(isError, animated);
    }

    public void setPremiumBadge(boolean usePremiumBadge) {
        usePremiumCounter = usePremiumBadge;
    }

    public void setSelected(boolean selected, boolean animated) {
        isSelectedAnimator.setValue(selected, animated);
        checkPlayAnimation(animated);

        textView.setTypeface(selected ? AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_EXTRA_BOLD) : AndroidUtilities.bold());
    }

    public boolean isTabSelected() {
        return isSelectedAnimator.getValue();
    }

    @Override
    public void onFactorChanged(int id, float factor, float fraction, FactorAnimator callee) {
        if (id == ANIMATOR_ID_IS_SELECTED) {
            updateColors();
        }
        invalidate();
    }

    private boolean needUpdateBackupViewColor;

    private void updateColors() {
        final int color = ColorUtils.blendARGB(colorDefault, colorSelected, isSelectedAnimator.getFloatValue());
        final int colorText = ColorUtils.blendARGB(colorDefault, colorSelectedText, isSelectedAnimator.getFloatValue());

        final PorterDuffColorFilter filter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
        if (backupImageView != null && needUpdateBackupViewColor) {
            backupImageView.setColorFilter(filter);
            backupImageView.invalidate();
        }
        imageView.setColorFilter(filter);
        if (staticIconView != null) {
            staticIconView.setColorFilter(filter);
        }
        textView.setTextColor(colorText);
    }

    public void updateColorsLottie() {
        colorDefault = Theme.getColor(Theme.key_glass_tabUnselected, resourcesProvider);
        colorSelected = Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider);
        colorSelectedText = Theme.getColor(Theme.key_glass_tabSelectedText, resourcesProvider);
        updateColors();
        invalidate();
    }


    private boolean lastIsSelected;
    private int lastIconAnimationRaw;
    private long lastBotIconId;

    private void checkPlayAnimation(boolean animated) {
        final boolean isSelected = isSelectedAnimator.getValue();

        if (tabAnimationBot !=  null) {
            boolean animatedIcon = true;
            TLRPC.TL_attachMenuBotIcon icon = MediaDataController.getAnimatedAttachMenuBotIcon(tabAnimationBot, isSelected);
            if (icon == null) {
                icon = MediaDataController.getStaticAttachMenuBotIcon(tabAnimationBot);
                animatedIcon = false;
            }
            if (icon != null && icon.icon != null) {
                TLRPC.Document iconDoc = icon.icon;
                if (lastBotIconId != icon.icon.id) {
                    String filter = "24_24" + (animatedIcon && !animated || true ? "_lastframe" : "");
                    backupImageView.setImage(
                        ImageLocation.getForDocument(iconDoc), filter,
                        ImageLocation.getForDocument(iconDoc), filter,
                        animatedIcon ? null : DocumentObject.getSvgThumb(iconDoc, Theme.key_windowBackgroundGray, 1f),
                        tabAnimationBot
                    );
                    lastBotIconId = iconDoc.id;
                }
            } else {
                backupImageView.clearImage();
            }
            updateColors();
            return;
        }

        if (tabAnimation == null) {
            return;
        }

        final int animationToSet = isSelected ?
            tabAnimation.iconToFilled : tabAnimation.iconToOutline;

        if (tabAnimation.endFrameMid != -1) {
            boolean update = lastIsSelected != isSelected;
            if (lastIconAnimationRaw != animationToSet) {
                lastIconAnimationRaw = animationToSet;
                imageView.setAnimation(animationToSet, 24, 24);
                update = true;
            }

            if (update) {
                final RLottieDrawable drawable = imageView.getAnimatedDrawable();
                if (drawable == null) {
                    return;
                }

                if (isSelected) {
                    drawable.setCustomEndFrame(tabAnimation.endFrameMid);
                    if (drawable.getCurrentFrame() >= tabAnimation.endFrameEnd - 2) {
                        drawable.setCurrentFrame(0, false);
                    }
                    if (drawable.getCurrentFrame() <= tabAnimation.endFrameMid) {
                        drawable.start();
                    } else {
                        drawable.setCurrentFrame(tabAnimation.endFrameMid);
                    }
                } else {
                    if (drawable.getCurrentFrame() >= tabAnimation.endFrameMid - 1) {
                        drawable.setCustomEndFrame(tabAnimation.endFrameEnd - 1);
                        drawable.start();
                    } else {
                        drawable.setCustomEndFrame(0);
                        drawable.setCurrentFrame(0);
                    }
                }
            }
            lastIsSelected = isSelected;
            return;
        }

        if (tabAnimation.iconToFilled != tabAnimation.iconToOutline) {
            if (lastIconAnimationRaw != animationToSet) {
                lastIconAnimationRaw = animationToSet;

                imageView.setAnimation(animationToSet, 24, 24);
                imageView.getAnimatedDrawable().setPlayInDirectionOfCustomEndFrame(false);
                if (animated) {
                    imageView.getAnimatedDrawable().setCurrentFrame(0);
                    imageView.playAnimation();
                } else {
                    imageView.getAnimatedDrawable().setProgress(0.99f);
                }
            }
            return;
        }

        if (imageView.getAnimatedDrawable() == null) {
            imageView.setAnimation(tabAnimation.iconToFilled, 24, 24);
        }

        final RLottieDrawable drawable = imageView.getAnimatedDrawable();
        if (drawable == null) {
            return;
        }

        if (lastIsSelected != isSelected) {
            lastIsSelected = isSelected;
            if (isSelected) {
                drawable.setPlayInDirectionOfCustomEndFrame(false);
                drawable.setCurrentFrame(0);
                drawable.setCustomEndFrame(drawable.getFramesCount());
            } else {
                drawable.setPlayInDirectionOfCustomEndFrame(true);
                drawable.setCurrentFrame(drawable.getFramesCount());
                drawable.setCustomEndFrame(0);
            }
            imageView.playAnimation();
        }
    }

    public static GlassTabView createMainTab(Context context, Theme.ResourcesProvider resourcesProvider, TabAnimation tabAnimation, @StringRes int stringRes) {
        GlassTabView tab = new GlassTabView(context);
        tab.resourcesProvider = resourcesProvider;
        tab.tabAnimation = tabAnimation;
        tab.textView.setText(LocaleController.getString(stringRes));
        tab.checkPlayAnimation(false);
        tab.imageView.setLayoutParams(LayoutHelper.createFrame(24, 24, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 4, 0, 0));
        tab.colorDefault = Theme.getColor(Theme.key_glass_tabUnselected, resourcesProvider);
        tab.colorSelected = Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider);
        tab.colorSelectedText = Theme.getColor(Theme.key_glass_tabSelectedText, resourcesProvider);
        tab.updateColors();
        return tab;
    }
  // Рисует иконку через прямой Path, без обращения к drawable-ресурсам.
  // Это устраняет зависимость от компиляции vector drawable в aapt/gradle.
  private static android.graphics.drawable.Drawable buildVectorIcon(@DrawableRes int iconRes) {
        final String pathData;
        if (iconRes == R.drawable.potok_tab_chats) {
            pathData = "M240-400h320v-80H240v80Zm0-120h480v-80H240v80Zm0-120h480v-80H240v80ZM80-80v-720q0-33 23.5-56.5T160-880h640q33 0 56.5 23.5T880-800v480q0 33-23.5 56.5T800-240H240L80-80Zm126-240h594v-480H160v525l46-45Zm-46 0v-480 480Z";
        } else if (iconRes == R.drawable.potok_tab_feed) {
            pathData = "M200-280q-33 0-56.5-23.5T120-360v-240q0-33 23.5-56.5T200-680h560q33 0 56.5 23.5T840-600v240q0 33-23.5 56.5T760-280H200Zm0-80h560v-240H200v240Zm-80-400v-80h720v80H120Zm0 640v-80h720v80H120Zm80-480v240-240Z";
        } else if (iconRes == R.drawable.potok_tab_traf) {
            pathData = "M120-120q-33 0-56.5-23.5T40-200v-520h80v520h680v80H120Zm160-160q-33 0-56.5-23.5T200-360v-440q0-33 23.5-56.5T280-880h200l80 80h280q33 0 56.5 23.5T920-720v360q0 33-23.5 56.5T840-280H280Zm0-80h560v-360H527l-80-80H280v440Zm0 0v-440 440Z";
        } else {
            pathData = "M160-40v-80h640v80H160Zm0-800v-80h640v80H160Zm320 400q50 0 85-35t35-85q0-50-35-85t-85-35q-50 0-85 35t-35 85q0 50 35 85t85 35ZM160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h640q33 0 56.5 23.5T880-720v480q0 33-23.5 56.5T800-160H160Zm70-80q45-56 109-88t141-32q77 0 141 32t109 88h70v-480H160v480h70Zm118 0h264q-29-20-62.5-30T480-280q-36 0-69.5 10T348-240Zm103.5-291.5Q440-543 440-560t11.5-28.5Q463-600 480-600t28.5 11.5Q520-577 520-560t-11.5 28.5Q497-520 480-520t-28.5-11.5ZM480-480Z";
        }
        final android.graphics.Path rawPath = androidx.core.graphics.PathParser.createPathFromPathData(pathData);
        final android.graphics.RectF pathBoundsRect = new android.graphics.RectF();
        rawPath.computeBounds(pathBoundsRect, true);
        android.util.Log.d("POTOK_ICON", "buildVectorIcon: created path for iconRes=" + iconRes + " pathBounds=" + pathBoundsRect);
        return new android.graphics.drawable.Drawable() {
            private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            { paint.setStyle(android.graphics.Paint.Style.FILL); paint.setColor(0xFFFFFFFF); }

            @Override
            protected void onBoundsChange(android.graphics.Rect bounds) {
                super.onBoundsChange(bounds);
                android.util.Log.d("POTOK_ICON", "onBoundsChange: bounds=" + bounds + " width=" + bounds.width() + " height=" + bounds.height());
                invalidateSelf();
            }

            @Override
            public void draw(android.graphics.Canvas canvas) {
                final android.graphics.Rect bounds = getBounds();
                final int size = Math.min(bounds.width(), bounds.height());
                android.util.Log.d("POTOK_ICON", "draw() called: bounds=" + bounds + " size=" + size + " paintColor=" + Integer.toHexString(paint.getColor()) + " paintAlpha=" + paint.getAlpha());
                if (size <= 0) {
                    android.util.Log.d("POTOK_ICON", "draw() ABORTED: size<=0");
                    return;
                }
                final float pathW = pathBoundsRect.width();
                final float pathH = pathBoundsRect.height();
                if (pathW <= 0 || pathH <= 0) {
                    android.util.Log.d("POTOK_ICON", "draw() ABORTED: invalid pathBounds " + pathBoundsRect);
                    return;
                }
                final float scale = size / Math.max(pathW, pathH);
                canvas.save();
                final float dx = bounds.left + (bounds.width() - size) / 2f;
                final float dy = bounds.top + (bounds.height() - size) / 2f;
                canvas.translate(dx, dy);
                canvas.scale(scale, scale);
                // компенсируем реальное смещение path (Material-иконки используют отрицательные Y координаты)
                canvas.translate(-pathBoundsRect.left, -pathBoundsRect.top);
                canvas.drawPath(rawPath, paint);
                canvas.restore();
                android.util.Log.d("POTOK_ICON", "draw() FINISHED: scale=" + scale + " dx=" + dx + " dy=" + dy + " compensateX=" + (-pathBoundsRect.left) + " compensateY=" + (-pathBoundsRect.top));
            }

            @Override
            public void setAlpha(int alpha) { paint.setAlpha(alpha); }

            @Override
            public void setColorFilter(android.graphics.ColorFilter colorFilter) { paint.setColorFilter(colorFilter); }

            @Override
            public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }

            @Override
            public int getIntrinsicWidth() { return dp(24); }

            @Override
            public int getIntrinsicHeight() { return dp(24); }
        };
    }

  public static GlassTabView createStaticTab(Context context, Theme.ResourcesProvider resourcesProvider, @DrawableRes int iconRes, @StringRes int stringRes) {
        GlassTabView tab = new GlassTabView(context);
        tab.resourcesProvider = resourcesProvider;
        tab.tabAnimation = null;
        tab.textView.setText(LocaleController.getString(stringRes));
        tab.imageView.setVisibility(GONE);

        tab.staticIconView = new androidx.appcompat.widget.AppCompatImageView(context);
        android.graphics.drawable.Drawable iconDrawable = buildVectorIcon(iconRes);
        android.util.Log.d("POTOK_ICON", "createStaticTab: iconRes=" + iconRes + " iconDrawable=" + iconDrawable + " isNull=" + (iconDrawable == null));
        tab.staticIconView.setImageDrawable(iconDrawable);
        tab.staticIconView.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        tab.addView(tab.staticIconView, LayoutHelper.createFrame(24, 24, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 8, 0, 0));
        android.util.Log.d("POTOK_ICON", "createStaticTab: addView done, staticIconView.getDrawable()=" + tab.staticIconView.getDrawable());

        tab.colorDefault = Theme.getColor(Theme.key_glass_tabUnselected, resourcesProvider);
        tab.colorSelected = Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider);
        tab.colorSelectedText = Theme.getColor(Theme.key_glass_tabSelectedText, resourcesProvider);
        tab.updateColors();
        return tab;
    }

    public static GlassTabView createAvatar(Context context, Theme.ResourcesProvider resourcesProvider, int currentAccount, @StringRes int stringRes) {
        GlassTabView tab = new GlassTabView(context);
        tab.textView.setText(LocaleController.getString(stringRes));
        tab.imageView.setVisibility(GONE);

        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(UserConfig.getInstance(currentAccount).getClientUserId());
        AvatarDrawable avatarDrawable = new AvatarDrawable(user);

        BackupImageView backupImageView = new BackupImageView(context);
        backupImageView.setForUserOrChat(user, avatarDrawable);
        backupImageView.setRoundRadius(dp(11));
        tab.backupImageView = backupImageView;

        tab.addView(backupImageView, LayoutHelper.createFrame(22, 22, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 5, 0, 0));
        tab.colorDefault = Theme.getColor(Theme.key_glass_tabUnselected, resourcesProvider);
        tab.colorSelected = Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider);
        tab.colorSelectedText = Theme.getColor(Theme.key_glass_tabSelectedText, resourcesProvider);
        tab.updateColors();
        return tab;
    }

    public void updateUserAvatar(int currentAccount) {
        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(UserConfig.getInstance(currentAccount).getClientUserId());
        AvatarDrawable avatarDrawable = new AvatarDrawable(user);
        backupImageView.setForUserOrChat(user, avatarDrawable);
    }

    public static GlassTabView createAttachTab(Context context, Theme.ResourcesProvider resourcesProvider) {
        GlassTabView tab = new GlassTabView(context);
        tab.resourcesProvider = resourcesProvider;
        tab.selfMeasure = true;
        tab.textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        tab.textView.setPadding(dp(8), 0, dp(8), 0);
        tab.checkPlayAnimation(false);
        tab.imageView.setLayoutParams(LayoutHelper.createFrame(24, 24, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 4, 0, 0));
        tab.colorDefault = Theme.getColor(Theme.key_glass_tabUnselected, resourcesProvider);
        tab.colorSelected = Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider);
        tab.colorSelectedText = Theme.getColor(Theme.key_glass_tabSelectedText, resourcesProvider);
        tab.updateColors();
        return tab;
    }

    public static GlassTabView createAttachBotTab(Context context, Theme.ResourcesProvider resourcesProvider) {
        GlassTabView tab = new GlassTabView(context);
        tab.resourcesProvider = resourcesProvider;
        tab.selfMeasure = true;
        tab.textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        tab.textView.setPadding(dp(8), 0, dp(8), 0);
        tab.imageView.setVisibility(GONE);
        tab.checkPlayAnimation(false);
        tab.backupImageView = new BackupImageView(context);
        tab.addView(tab.backupImageView, LayoutHelper.createFrame(24, 24, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 4, 0, 0));
        tab.colorDefault = Theme.getColor(Theme.key_glass_tabUnselected, resourcesProvider);
        tab.colorSelected = Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider);
        tab.colorSelectedText = Theme.getColor(Theme.key_glass_tabSelectedText, resourcesProvider);
        tab.updateColors();
        return tab;
    }

    public BackupImageView getBackupImageView() {
        return backupImageView;
    }

    private boolean selfMeasure;
    private int additionalWidth;

    public void setAdditionalWidth(int additionalWidth) {
        this.additionalWidth = additionalWidth;
        this.selfMeasure = true;
    }

    public float measureAttachTabWidth() {
        final float textWidth = measureTextWidth();
        final float padding = lerp(dpf2(16), dp(8), MathUtils.clamp((textWidth - dp(40)) / dp(16), 0, 1));
        return Math.min(dp(84), (int) (textWidth + padding * 2));
    }

    public float attachScale = 1;
    public void setAttachScale(float scale) {
        textView.setScaleX(scale);
        textView.setScaleY(scale);
        imageView.setScaleX(scale);
        imageView.setScaleY(scale);
        if (backupImageView != null) {
            backupImageView.setScaleX(scale);
            backupImageView.setScaleY(scale);
        }
        attachScale = scale;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (selfMeasure) {
            final int width = (int) (measureAttachTabWidth()) + additionalWidth;
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec);
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    @Override
    public float measureTextWidth() {
        return defaultTextPaint.measureText(textView.getText().toString());
    }


    public enum TabAnimation {
        CONTACTS(R.raw.tab_contacts),
        CALLS(R.raw.tab_calls),
        CHATS(R.raw.tab_chats),
        SETTINGS(R.raw.tab_settings),

        CHECKLIST(R.raw.tab_checklist, R.raw.tab_checklist_reverse),
        COLORS(R.raw.tab_colors, R.raw.tab_colors_reverse),
        FILES(R.raw.tab_files, R.raw.tab_files_reverse),
        GALLERY(R.raw.tab_gallery, R.raw.tab_gallery_reverse),
        GIFT(R.raw.tab_gift, R.raw.tab_gift_reverse),
        LOCATION(R.raw.tab_location, R.raw.tab_location_reverse),
        STICKER(R.raw.tab_sticker, R.raw.tab_sticker_reverse),
        EMOJI(R.raw.tab_emoji, R.raw.tab_emoji_reverse),
        MODELS(R.raw.tab_models, R.raw.tab_models_reverse),
        MUSIC(R.raw.tab_music, R.raw.tab_music_reverse),
        POLL(R.raw.tab_poll, R.raw.tab_poll_reverse),
        SYMBOLS(R.raw.tab_symbols, R.raw.tab_symbols_reverse),
        REPLIES(R.raw.tab_reply, R.raw.tab_reply_reverse),
        WALLET(R.raw.tab_wallet, R.raw.tab_wallet_reverse),

        BOOSTS(R.raw.boosts, 25, 49),
        MONETIZATION(R.raw.monetize, 19, 45);

        public final @RawRes int iconToFilled;
        public final @RawRes int iconToOutline;
        public final int endFrameMid, endFrameEnd;

        TabAnimation(int iconRes, int endFrameMid, int endFrameEnd) {
            this.iconToFilled = iconRes;
            this.iconToOutline = iconRes;
            this.endFrameMid = endFrameMid;
            this.endFrameEnd = endFrameEnd;
        }

        TabAnimation(int iconRes) {
            this.iconToFilled = iconRes;
            this.iconToOutline = iconRes;
            this.endFrameMid = -1;
            this.endFrameEnd = -1;
        }

        TabAnimation(int iconToFilled, int iconToOutline) {
            this.iconToFilled = iconToFilled;
            this.iconToOutline = iconToOutline;
            this.endFrameMid = -1;
            this.endFrameEnd = -1;
        }
    }

    public void setTabAnimation(TabAnimation animation) {
        tabAnimation = animation;
        tabAnimationBot = null;
        lastIconAnimationRaw = 0;
        lastBotIconId = 0;
        imageView.clearAnimationDrawable();
        checkPlayAnimation(false);
    }

    public void setText(CharSequence text) {
        textView.setText(text);
    }


    private AvatarDrawable avatarDrawable;

    public void setAttachBot(TLRPC.User user, TLRPC.TL_attachMenuBot bot, int currentAccount) {
        if (user == null || bot == null) {
            return;
        }
        tabAnimation = null;
        tabAnimationBot = bot;
        lastIconAnimationRaw = 0;
        lastBotIconId = 0;
        textView.setText(bot.short_name);

        backupImageView.setRoundRadius(0);
        backupImageView.setSize(dp(24), dp(24));
        backupImageView.setLayoutParams(LayoutHelper.createFrame(24, 24, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 4, 0, 0));
        needUpdateBackupViewColor = true;
        checkPlayAnimation(false);
        updateColors();
        invalidate();
    }

    public void setAttachBotUser(TLRPC.User user, int currentAccount) {
        if (user == null) {
            return;
        }
        tabAnimation = null;
        tabAnimationBot = null;
        lastIconAnimationRaw = 0;
        lastBotIconId = 0;

        textView.setText(ContactsController.formatName(user.first_name, user.last_name));
        if (avatarDrawable == null) {
            avatarDrawable = new AvatarDrawable();
        }
        avatarDrawable.setInfo(currentAccount, user);
        backupImageView.setForUserOrChat(user, avatarDrawable);
        backupImageView.setSize(-1, -1);
        backupImageView.setRoundRadius(dp(11.33f));
        backupImageView.setLayoutParams(LayoutHelper.createFrame(22, 22, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 5, 0, 0));
        backupImageView.setColorFilter(null);
        needUpdateBackupViewColor = false;
        invalidate();
    }

    public void onPreBind() {

    }
}
