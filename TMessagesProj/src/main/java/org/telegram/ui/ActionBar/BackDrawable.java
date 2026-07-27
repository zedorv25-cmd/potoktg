/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.ActionBar;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.animation.DecelerateInterpolator;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;

public class BackDrawable extends Drawable {

    private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint prevPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean reverseAngle;
    private long lastFrameTime;
    private boolean animationInProgress;
    private float finalRotation;
    private float currentRotation;
    private int currentAnimationTime;
    private boolean alwaysClose;
    private DecelerateInterpolator interpolator = new DecelerateInterpolator();
    private int color = 0xffffffff;
    private int rotatedColor = 0xff757575;
    private float animationTime = 300.0f;
    private boolean rotated = true;
    private int arrowRotation;

    public float getRotation() {
        return finalRotation;
    }

    public BackDrawable(boolean close) {
        super();
        paint.setStrokeWidth(AndroidUtilities.dp(2));
        paint.setStrokeCap(Paint.Cap.ROUND);
        prevPaint.setStrokeWidth(AndroidUtilities.dp(2));
        prevPaint.setColor(Color.RED);
        alwaysClose = close;
    }

    public void setColor(int value) {
        color = value;
        invalidateSelf();
    }

    public void setRotatedColor(int value) {
        rotatedColor = value;
        invalidateSelf();
    }

    public void setArrowRotation(int angle) {
        arrowRotation = angle;
        invalidateSelf();
    }

    public void setRotation(float rotation, boolean animated) {
        lastFrameTime = 0;
        if (currentRotation == 1) {
            reverseAngle = true;
        } else if (currentRotation == 0) {
            reverseAngle = false;
        }
        lastFrameTime = 0;
        if (animated) {
            if (currentRotation < rotation) {
                currentAnimationTime = (int) (currentRotation * animationTime);
            } else {
                currentAnimationTime = (int) ((1.0f - currentRotation) * animationTime);
            }
            lastFrameTime = System.currentTimeMillis();
            finalRotation = rotation;
        } else {
            finalRotation = currentRotation = rotation;
        }
        invalidateSelf();
    }

    public void setAnimationTime(float value) {
        animationTime = value;
    }

    public void setRotated(boolean value) {
        rotated = value;
    }

    private float translationX;

    public BackDrawable setTranslationX(float translationX) {
        this.translationX = translationX;
        return this;
    }

    @Override
    public void draw(Canvas canvas) {
        if (currentRotation != finalRotation) {
            if (lastFrameTime != 0) {
                long dt = System.currentTimeMillis() - lastFrameTime;

                currentAnimationTime += dt;
                if (currentAnimationTime >= animationTime) {
                    currentRotation = finalRotation;
                } else {
                    if (currentRotation < finalRotation) {
                        currentRotation = interpolator.getInterpolation(currentAnimationTime / animationTime) * finalRotation;
                    } else {
                        currentRotation = 1.0f - interpolator.getInterpolation(currentAnimationTime / animationTime);
                    }
                }
            }
            lastFrameTime = System.currentTimeMillis();
            invalidateSelf();
        }

        paint.setColor(ColorUtils.blendARGB(color, rotatedColor, currentRotation));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.MITER);
        paint.setStrokeCap(Paint.Cap.ROUND);

        canvas.save();
        canvas.translate(getIntrinsicWidth() / 2 + translationX, getIntrinsicHeight() / 2);
        if (arrowRotation != 0) {
            canvas.rotate(arrowRotation);
        }
        float rotation = currentRotation;
        if (!alwaysClose) {
            canvas.rotate(currentRotation * (reverseAngle ? -225 : 135));
        } else {
            canvas.rotate(135 + currentRotation * (reverseAngle ? -180 : 180));
            rotation = 1.0f;
        }
        // ВАЖНО: горизонтальная линия (третья) нужна не только для хвоста стрелки —
        // она же вместе с двумя диагоналями образует "+", который после
        // canvas.rotate(135°/315° в alwaysClose-ветке выше) превращается в крестик
        // "X". В состоянии "стрелка" (rotation≈0) линия должна быть полностью не
        // видна. Раньше она рисовалась как canvas.drawLine(0,0,0,0,paint) — линия
        // нулевой длины с Cap.ROUND рисуется не пустотой, а закрашенной точкой
        // (баг "точка/палочка по центру"). Фикс: просто не рисуем эту линию вовсе,
        // если rotation пренебрежимо мал — тогда в состоянии "крестик" (rotation=1,
        // включая forced rotation=1 в alwaysClose) она рисуется как и раньше, в
        // полную длину.
        if (rotation > 0.001f) {
            float tailHalfLen = AndroidUtilities.dp(8) - (paint.getStrokeWidth() / 2f) * (1f - rotation);
            canvas.drawLine(-tailHalfLen * rotation, 0, tailHalfLen * rotation, 0, paint);
        }
        // Два плеча шеврона теперь рисуются ОДНИМ путём (moveTo -> вершина -> lineTo)
        // с Paint.Join.MITER вместо двух независимых canvas.drawLine(). Раньше
        // вершина была не одной точкой, а двумя точками с крошечным вертикальным
        // разносом (±0.25dp), и оба плеча сходились там со скруглённым концом
        // (Cap.ROUND) — из-за этого в месте стыка получалось заметное утолщение/
        // кружок вместо острого угла (особенно заметно на растровой иконке).
        // Теперь вершина — одна общая точка, плечи расходятся ровно под 45°
        // (endYDiff и startXDiff равны по модулю при rotation=0), Cap.ROUND
        // остаётся только на открытых внешних концах (кончики стрелки), а сам
        // стык — честный митрованный (острый) угол, без скругления.
        float endYDiff = AndroidUtilities.dp(AndroidUtilities.lerp(7f, 8f, rotation));
        float startXDiff = AndroidUtilities.dp(AndroidUtilities.lerp(-7f, 0f, rotation));
        float endXDiff = 0;
        Path chevronPath = new Path();
        chevronPath.moveTo(endXDiff, -endYDiff);
        chevronPath.lineTo(startXDiff, 0);
        chevronPath.lineTo(endXDiff, endYDiff);
        canvas.drawPath(chevronPath, paint);
        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSPARENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return AndroidUtilities.dp(24);
    }

    @Override
    public int getIntrinsicHeight() {
        return AndroidUtilities.dp(24);
    }
}
