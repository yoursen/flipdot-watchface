package com.seniuk.yuriy.flipdotwatchface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.SurfaceHolder;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

public class FlipdotCanvasWatchFaceService extends CanvasWatchFaceService {
    @Override
    public Engine onCreateEngine() {
        /* provide your watch face implementation */
        return new FlipdotEngine();
    }


    /* implement service callback methods */
    private class FlipdotEngine extends CanvasWatchFaceService.Engine {

        static final int MSG_UPDATE_TIME = 0;
        static final int MSG_ON_INTERACTIVE_MSG = 1;

        private final long START_INTERCTIVE_MODE_OFFSET_MS = 200;
        private final long ONE_ANIMATION_STEP_RATE_MS = 50;

        private final int FLIPDOT_COLOR = Color.GREEN;
        private final int FLIPDOTS_COUNT = 8;
        private final float BURNIN_FLIPDOT_PAINT_STROKE_WIDTH = 3f;

        final class FlipdotState {
            static final int OFF = 0;
            static final int ON = 7;
        }

        private Rect mPeekRect;
        private int mFlipdotBlockSize;
        private float mFlipdotCircleRadius;
        private float mFlipdotCircleCutDeltaPos;
        private float mFlipdotCircleCutRadius;
        private float mFlipdotXYOffset;

        final class Direction {
            static final int FLIPDOT_DIRECTION_FORWARD = 1;
            static final int FLIPDOT_DIRECTION_REVERSE = -1;
        }

        private int mCurrentAnimationIndex = FlipdotState.OFF;
        private int mCurrentFlipdotDirection = Direction.FLIPDOT_DIRECTION_FORWARD;

        private java.util.Calendar mCalendar;
        private java.util.Date mDate;
        private boolean mRegisteredTimeZoneReceiver = false;

        private Bitmap[] mFlipdotBitmaps;
        private Bitmap mBackgroundScaledBitmap;

        private int[][][] mSymbolsMatrix;

        private Paint mAmbientFlipdotPaint;
        private Paint mAmbientFlipdotCutPaint;
        private Paint mAmbientPeekRect;

        private boolean mAmbient;
        private boolean mLowBitAmbient;
        private boolean mBurnInProtection;

        final class ScreenDigits {
            static final int DIGIT_0 = 0;
            static final int DIGIT_1 = 1;
            static final int DIGIT_2 = 2;
            static final int DIGIT_3 = 3;
            static final int DIGITS_COUNT = 4;
        }

        @SuppressWarnings("SpellCheckingInspection")
        final class Symbols {
            static final int SYMB_0 = 0;
            static final int SYMB_1 = 1;
            static final int SYMB_2 = 2;
            static final int SYMB_3 = 3;
            static final int SYMB_4 = 4;
            static final int SYMB_5 = 5;
            static final int SYMB_6 = 6;
            static final int SYMB_7 = 7;
            static final int SYMB_8 = 8;
            static final int SYMB_9 = 9;
            static final int SYMB_EXCLAMATION = 10;
        }

        private int mHours;
        private int mMinutes;

        private boolean mInteractiveRedraw = true;

        private float mCenterX;
        private float mCenterY;
        private float mWidth;
        private float mHeight;

        private int[] mInteractiveDigitsOnDisplay = new int[]{-1, -1, -1, -1};
        private int[] mDigitsToDisplay = new int[4];

        private final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_ON_INTERACTIVE_MSG:
                        mInteractiveRedraw = true;
                        updateInteractiveTimer();
                        break;

                    case MSG_UPDATE_TIME:
                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs = ONE_ANIMATION_STEP_RATE_MS - (timeMs % ONE_ANIMATION_STEP_RATE_MS);
                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                }
            }
        };

        private void updateInteractiveTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        private boolean shouldTimerBeRunning() {
            return isVisible() && !mAmbient && mInteractiveRedraw;
        }

        private final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_LOCALE_CHANGED);
            FlipdotCanvasWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            FlipdotCanvasWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        private void createDigitsMatrix() {
            mSymbolsMatrix = new int[11][][];
            mSymbolsMatrix[Symbols.SYMB_0] = new int[][]{
                    {1, 1, 1},
                    {1, 0, 1},
                    {1, 0, 1},
                    {1, 0, 1},
                    {1, 1, 1}};

            mSymbolsMatrix[Symbols.SYMB_1] = new int[][]{
                    {1, 1, 0},
                    {0, 1, 0},
                    {0, 1, 0},
                    {0, 1, 0},
                    {1, 1, 1}};

            mSymbolsMatrix[Symbols.SYMB_2] = new int[][]{
                    {1, 1, 1},
                    {0, 0, 1},
                    {1, 1, 1},
                    {1, 0, 0},
                    {1, 1, 1}};

            mSymbolsMatrix[Symbols.SYMB_3] = new int[][]{
                    {1, 1, 1},
                    {0, 0, 1},
                    {1, 1, 1},
                    {0, 0, 1},
                    {1, 1, 1}};

            mSymbolsMatrix[Symbols.SYMB_4] = new int[][]{
                    {1, 0, 1},
                    {1, 0, 1},
                    {1, 1, 1},
                    {0, 0, 1},
                    {0, 0, 1}};

            mSymbolsMatrix[Symbols.SYMB_5] = new int[][]{
                    {1, 1, 1},
                    {1, 0, 0},
                    {1, 1, 1},
                    {0, 0, 1},
                    {1, 1, 1}};

            mSymbolsMatrix[Symbols.SYMB_6] = new int[][]{
                    {1, 1, 1},
                    {1, 0, 0},
                    {1, 1, 1},
                    {1, 0, 1},
                    {1, 1, 1}};

            mSymbolsMatrix[Symbols.SYMB_7] = new int[][]{
                    {1, 1, 1},
                    {0, 0, 1},
                    {0, 0, 1},
                    {0, 0, 1},
                    {0, 0, 1}};

            mSymbolsMatrix[Symbols.SYMB_8] = new int[][]{
                    {1, 1, 1},
                    {1, 0, 1},
                    {1, 1, 1},
                    {1, 0, 1},
                    {1, 1, 1}};

            mSymbolsMatrix[Symbols.SYMB_9] = new int[][]{
                    {1, 1, 1},
                    {1, 0, 1},
                    {1, 1, 1},
                    {0, 0, 1},
                    {1, 1, 1}};

            mSymbolsMatrix[Symbols.SYMB_EXCLAMATION] = new int[][]{
                    {0, 1, 0},
                    {0, 1, 0},
                    {0, 1, 0},
                    {0, 0, 0},
                    {0, 1, 0}};
        }

        private void updateConstants(int flipdotWidth) {
            mFlipdotBlockSize = flipdotWidth;
            mFlipdotCircleRadius = (mFlipdotBlockSize - 2) / 2;
            mFlipdotCircleCutRadius = (mFlipdotCircleRadius) / 3;
            mFlipdotCircleCutDeltaPos = (float) (Math.sin(Math.toRadians(45)) * mFlipdotCircleRadius);
            mFlipdotXYOffset = mFlipdotBlockSize / 2;
        }

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            //noinspection deprecation
            setWatchFaceStyle(new WatchFaceStyle.Builder(FlipdotCanvasWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setPeekOpacityMode(WatchFaceStyle.PEEK_OPACITY_MODE_TRANSLUCENT)
                    .setAmbientPeekMode(WatchFaceStyle.AMBIENT_PEEK_MODE_VISIBLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setStatusBarGravity(Gravity.CENTER | Gravity.TOP)
                    .setHotwordIndicatorGravity(Gravity.CENTER | Gravity.TOP)
                    .build());

            mAmbientFlipdotPaint = new Paint();
            mAmbientFlipdotPaint.setColor(Color.WHITE);
            mAmbientFlipdotPaint.setAntiAlias(false);

            mAmbientFlipdotCutPaint = new Paint();
            mAmbientFlipdotCutPaint.setColor(Color.BLACK);
            mAmbientFlipdotCutPaint.setAntiAlias(false);

            mAmbientPeekRect = new Paint();
            mAmbientPeekRect.setColor(Color.BLACK);
            mAmbientPeekRect.setAntiAlias(false);
            mAmbientPeekRect.setAlpha(200);

            mCalendar = Calendar.getInstance();
            mDate = new Date();

            createDigitsMatrix();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            mUpdateTimeHandler.removeMessages(MSG_ON_INTERACTIVE_MSG);
            super.onDestroy();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);

            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);

            mAmbientFlipdotPaint.setAntiAlias(!mLowBitAmbient);
            mAmbientFlipdotCutPaint.setAntiAlias(!mLowBitAmbient);

            if (mBurnInProtection) {
                mAmbientFlipdotPaint.setStrokeWidth(BURNIN_FLIPDOT_PAINT_STROKE_WIDTH);
                mAmbientFlipdotPaint.setStyle(Paint.Style.STROKE);
            } else {
                mAmbientFlipdotPaint.setStyle(Paint.Style.FILL);
            }
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();

            /* the time changed */
            if (updateActualTime()) {
                mInteractiveRedraw = true;
                updateInteractiveTimer();
            }

            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            /* the wearable switched between modes */
            mAmbient = inAmbientMode;

            //start animation after ambient mode from closed flipdots
            if (!mAmbient) {
                mCurrentAnimationIndex = FlipdotState.OFF;
                mCurrentFlipdotDirection = Direction.FLIPDOT_DIRECTION_FORWARD;

                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_ON_INTERACTIVE_MSG, START_INTERCTIVE_MODE_OFFSET_MS);
            } else {
                for (int i = 0; i < ScreenDigits.DIGITS_COUNT; i++) {
                    mInteractiveDigitsOnDisplay[i] = -1;
                }
            }

            invalidate();
            updateInteractiveTimer();
        }

        private void drawInteractiveDigit(Canvas canvas, int animationIndex, int symbol, int dotOffsetX, int dotOffsetY) {
            int digit;
            if (mCurrentFlipdotDirection == Direction.FLIPDOT_DIRECTION_REVERSE && mInteractiveDigitsOnDisplay[symbol] >= 0) {
                digit = mInteractiveDigitsOnDisplay[symbol];
            } else {
                digit = mDigitsToDisplay[symbol];
            }

            int[][] digitMatrix = mSymbolsMatrix[digit];
            drawInteractiveSymbol(canvas, digitMatrix, animationIndex, dotOffsetX, dotOffsetY);
        }

        private void drawInteractiveSymbol(Canvas canvas, int[][] symbolMatrix, int animationIndex, int dotOffsetX, int dotOffsetY) {
            for (int y = 0; y < symbolMatrix.length; y++) {
                for (int x = 0; x < symbolMatrix[y].length; x++) {
                    if (symbolMatrix[y][x] == 1) {
                        drawFlipdot(canvas, animationIndex, dotOffsetX + x, dotOffsetY + y);
                    }
                }
            }
        }

        private void drawAmbientDigit(Canvas canvas, int symbol, int dotOffsetX, int dotOffsetY) {
            int digit = mDigitsToDisplay[symbol];
            int[][] digitMatrix = mSymbolsMatrix[digit];
            drawAmbientSymbol(canvas, digitMatrix, dotOffsetX, dotOffsetY);
        }

        private void drawAmbientSymbol(Canvas canvas, int[][] symbolMatrix, int dotOffsetX, int dotOffsetY) {
            for (int y = 0; y < symbolMatrix.length; y++) {
                for (int x = 0; x < symbolMatrix[y].length; x++) {
                    if (symbolMatrix[y][x] == 1) {
                        drawAmbientFlipdot(canvas, dotOffsetX + x, dotOffsetY + y);
                    }
                }
            }
        }

        private void drawAmbientFlipdot(Canvas canvas, int dotOffsetX, int dotOffsetY) {
            if (mCurrentAnimationIndex != FlipdotState.OFF) {
                int xPos = (int) (mCenterX + dotOffsetX * mFlipdotBlockSize);
                int yPos = (int) (mCenterY + dotOffsetY * mFlipdotBlockSize);

                if (mBurnInProtection) {
                    canvas.drawCircle(xPos, yPos, mFlipdotCircleRadius / 2f, mAmbientFlipdotPaint);
                } else {
                    canvas.drawCircle(xPos, yPos, mFlipdotCircleRadius, mAmbientFlipdotPaint);
                    canvas.drawCircle(xPos - mFlipdotCircleCutDeltaPos, yPos - mFlipdotCircleCutDeltaPos, mFlipdotCircleCutRadius, mAmbientFlipdotCutPaint);
                }
            }
        }

        private void drawFlipdot(Canvas canvas, int flipdotIndex, int dotOffsetX, int dotOffsetY) {
            if (mCurrentAnimationIndex != FlipdotState.OFF) {
                int xPos = (int) (mCenterX + dotOffsetX * mFlipdotBlockSize - mFlipdotXYOffset);
                int yPos = (int) (mCenterY + dotOffsetY * mFlipdotBlockSize - mFlipdotXYOffset);
                canvas.drawBitmap(mFlipdotBitmaps[flipdotIndex], xPos, yPos, null);
            }
        }

        private boolean updateActualTime() {
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);

            boolean is24Hour = DateFormat.is24HourFormat(FlipdotCanvasWatchFaceService.this);
            int hours;
            if (is24Hour) {
                hours = mCalendar.get(Calendar.HOUR_OF_DAY);
            } else {
                hours = mCalendar.get(Calendar.HOUR);
                if (hours == 0) {
                    hours = 12;
                }
            }
            int minutes = mCalendar.get(Calendar.MINUTE);

            boolean isChanged = mMinutes != minutes;

            mHours = hours;
            mMinutes = minutes;

            mDigitsToDisplay[ScreenDigits.DIGIT_0] = mHours / 10;
            mDigitsToDisplay[ScreenDigits.DIGIT_1] = mHours % 10;
            mDigitsToDisplay[ScreenDigits.DIGIT_2] = mMinutes / 10;
            mDigitsToDisplay[ScreenDigits.DIGIT_3] = mMinutes % 10;

            return isChanged;
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            if (!mAmbient) {
                canvas.drawBitmap(mBackgroundScaledBitmap, 0, 0, null);

                if (mInteractiveRedraw) {
                    mCurrentAnimationIndex += mCurrentFlipdotDirection;
                }

                drawInteractiveDigit(canvas, mCurrentAnimationIndex, ScreenDigits.DIGIT_0, -3, -5);
                drawInteractiveDigit(canvas, mCurrentAnimationIndex, ScreenDigits.DIGIT_1, 1, -5);
                drawInteractiveDigit(canvas, mCurrentAnimationIndex, ScreenDigits.DIGIT_2, -3, 1);
                drawInteractiveDigit(canvas, mCurrentAnimationIndex, ScreenDigits.DIGIT_3, 1, 1);

                if (mCurrentAnimationIndex == FlipdotState.ON) {
                    mCurrentFlipdotDirection = Direction.FLIPDOT_DIRECTION_REVERSE;
                    //copy display values, to be used on reverse animation
                    System.arraycopy(mDigitsToDisplay, 0, mInteractiveDigitsOnDisplay, 0, ScreenDigits.DIGITS_COUNT);
                    //we're ON, turn off interactive animation
                    mInteractiveRedraw = false;
                } else if (mCurrentAnimationIndex == FlipdotState.OFF) {
                    mCurrentFlipdotDirection = Direction.FLIPDOT_DIRECTION_FORWARD;
                }

            } else {
                canvas.drawColor(Color.BLACK);

                drawAmbientDigit(canvas, ScreenDigits.DIGIT_0, -3, -5);
                drawAmbientDigit(canvas, ScreenDigits.DIGIT_1, 1, -5);
                drawAmbientDigit(canvas, ScreenDigits.DIGIT_2, -3, 1);
                drawAmbientDigit(canvas, ScreenDigits.DIGIT_3, 1, 1);

                //draw black rectangle if.
                Rect rect = mPeekRect;
                if(rect != null && rect.height() > 0) {
                    canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, mAmbientPeekRect);
                }
            }
        }

        @Override
        public void onPeekCardPositionUpdate(Rect rect) {
            super.onPeekCardPositionUpdate(rect);
            mPeekRect = rect;

            //invalidate only in ambient mode.
            if(mAmbient)
            {
                invalidate();
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone and date formats, in case they changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateInteractiveTimer();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (mBackgroundScaledBitmap == null
                    || mBackgroundScaledBitmap.getWidth() != width
                    || mBackgroundScaledBitmap.getHeight() != height) {

                Resources resources = FlipdotCanvasWatchFaceService.this.getResources();
                Bitmap bitmap = ((BitmapDrawable) resources.getDrawable(R.drawable.watch_face_bg, null)).getBitmap();
                boolean isScaled = false;
                if(bitmap.getHeight() != height || bitmap.getWidth() != width) {
                    mBackgroundScaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true /* filter */);
                    isScaled = true;
                }else{
                    mBackgroundScaledBitmap = bitmap;
                }

                float scaleHeigth = (mBackgroundScaledBitmap.getHeight() * 1f) / bitmap.getHeight();
                float scaleWidth = (mBackgroundScaledBitmap.getWidth() * 1f) / bitmap.getWidth();

                mFlipdotBitmaps = new Bitmap[FLIPDOTS_COUNT];
                for (int i = 0; i < FLIPDOTS_COUNT; i++) {
                    int id = resources.getIdentifier("flipdot_svg_tr" + i, "drawable", getPackageName());
                    mFlipdotBitmaps[i] = ((BitmapDrawable) resources.getDrawable(id, null)).getBitmap();

                    if(isScaled){
                        mFlipdotBitmaps[i] = Bitmap.createScaledBitmap(mFlipdotBitmaps[i],
                                (int)(mFlipdotBitmaps[i].getWidth() * scaleWidth),
                                (int)(mFlipdotBitmaps[i].getHeight() * scaleHeigth),
                                true /* filter */);
                    }
                }

                updateConstants(mFlipdotBitmaps[0].getWidth());
            }

            mWidth = width;
            mHeight = height;

            mCenterX = width / 2f;
            mCenterY = height / 2f;

            super.onSurfaceChanged(holder, format, width, height);
        }
    }

}
