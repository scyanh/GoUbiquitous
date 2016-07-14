/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.caydeem.wear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final String TAG = "MyWatchFace";

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;


    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {

        private static final String WEATHER_PATH = "/weather";
        private static final String HIGH_TEMPERATURE = "high_temperature";
        private static final String LOW_TEMPERATURE = "low_temperature";
        private static final String WEATHER_CONDITION = "weather_condition";

        private static final int SPACE_BETWEEN_TEMPERATURES = 10;
        private static final int MARGIN_TOP_DATE = 30;
        private static final int MARGIN_TOP_LINE = 55;

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        boolean mAmbient;
        Time mTime;
        Calendar calendar;

        Paint mLinePaint;
        Paint mDateTextPaint;
        Paint mHighTemperatureTextPaint;
        Paint mLowTemperatureTextPaint;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
                calendar.setTimeZone(TimeZone.getDefault());
            }
        };
        int mTapCount;

        float mXOffset;
        float mYOffset;
        float mDateYOffset;
        float mWeatherYOffset;

        Bitmap mConditionIcon;
        String mHighTemperature;
        String mLowTemperature;

        int mDigitalTextColor = -1;
        int mDigitalTextLightColor = -1;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        private GoogleApiClient mGoogleApiClient;
        private Resources resources;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            mGoogleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            resources = MyWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mDateYOffset = resources.getDimension(R.dimen.date_y_offset);
            mWeatherYOffset = resources.getDimension(R.dimen.weather_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mTime = new Time();

            mDigitalTextColor = ContextCompat.getColor(MyWatchFace.this, R.color.digital_text);
            mDigitalTextLightColor = ContextCompat.getColor(MyWatchFace.this, R.color.digital_text_light);


            mLinePaint = new Paint();
            mLinePaint.setColor(mDigitalTextLightColor);

            mDateTextPaint = createTextPaint(mDigitalTextLightColor);
            mHighTemperatureTextPaint = createTextPaint(mDigitalTextColor);
            mLowTemperatureTextPaint = createTextPaint(mDigitalTextLightColor);

            // allocate a Calendar to calculate local time using the UTC time and time zone
            calendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
                calendar.setTimeZone(TimeZone.getDefault());
            } else {
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }

                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTextPaint.setTextSize(textSize);

            float dateTextSize = resources.getDimension(R.dimen.date_text_size);
            mDateTextPaint.setTextSize(dateTextSize);

            float temperatureTextSize = resources.getDimension(R.dimen.temperature_text_size);
            mHighTemperatureTextPaint.setTextSize(temperatureTextSize);
            mLowTemperatureTextPaint.setTextSize(temperatureTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {

                mLinePaint.setColor(inAmbientMode ? mDigitalTextColor : mDigitalTextLightColor);
                mDateTextPaint.setColor(inAmbientMode ? mDigitalTextColor : mDigitalTextLightColor);
                mLowTemperatureTextPaint.setColor(inAmbientMode ? mDigitalTextColor : mDigitalTextLightColor);

                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                    mDateTextPaint.setAntiAlias(!inAmbientMode);
                    mHighTemperatureTextPaint.setAntiAlias(!inAmbientMode);
                    mLowTemperatureTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = MyWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();

            String text = mAmbient
                    ? String.format("%d:%02d", mTime.hour, mTime.minute)
                    : String.format("%d:%02d:%02d", mTime.hour, mTime.minute, mTime.second);

            float timeTextWidth = mTextPaint.measureText(text);
            float halfTimeTextWidth = timeTextWidth / 2;
            float xOffsetTime = bounds.centerX() - halfTimeTextWidth;
            //canvas.drawText(time, xOffsetTime, timeYOffset, timeTextPaint);

            canvas.drawText(text, xOffsetTime, mYOffset, mTextPaint);

            calendar.setTimeInMillis(System.currentTimeMillis());

            // Draw date text in x-center of screen
            SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, MMM dd yyyy", Locale.US);
            String date = dateFormat.format(calendar.getTime()).toUpperCase(Locale.US);
            float dateTextWidth = mDateTextPaint.measureText(date);
            float halfDateTextWidth = dateTextWidth / 2;
            float xOffsetDate = bounds.centerX() - halfDateTextWidth;
            canvas.drawText(date, xOffsetDate, mYOffset+MARGIN_TOP_DATE, mDateTextPaint);

            if (mConditionIcon != null && mHighTemperature != null && mLowTemperature != null) {
                float highTemperatureTextWidth = mHighTemperatureTextPaint.measureText(mHighTemperature);
                float lowTemperatureTextWidth = mLowTemperatureTextPaint.measureText(mLowTemperature);

                Rect temperatureBounds = new Rect();
                mHighTemperatureTextPaint.getTextBounds(mHighTemperature, 0, mHighTemperature.length(), temperatureBounds);

                float lineYOffset = mYOffset+MARGIN_TOP_LINE;
                canvas.drawLine(bounds.centerX() - 4 * SPACE_BETWEEN_TEMPERATURES, lineYOffset,
                        bounds.centerX() + 4 * SPACE_BETWEEN_TEMPERATURES, lineYOffset, mLinePaint);

                float xOffsetHighTemperature;
                if (mAmbient) {
                    xOffsetHighTemperature = bounds.centerX() - ((highTemperatureTextWidth + lowTemperatureTextWidth + SPACE_BETWEEN_TEMPERATURES) / 2);
                } else {
                    xOffsetHighTemperature = bounds.centerX() - (highTemperatureTextWidth / 2);

                    canvas.drawBitmap(mConditionIcon, xOffsetHighTemperature - mConditionIcon.getWidth() - 2 * SPACE_BETWEEN_TEMPERATURES,
                            mWeatherYOffset - (temperatureBounds.height() / 2) - (mConditionIcon.getHeight() / 2), null);
                }

                float xOffsetLowTemperature = xOffsetHighTemperature + highTemperatureTextWidth + SPACE_BETWEEN_TEMPERATURES;

                canvas.drawText(mHighTemperature, xOffsetHighTemperature, mWeatherYOffset, mHighTemperatureTextPaint);
                canvas.drawText(mLowTemperature, xOffsetLowTemperature, mWeatherYOffset, mLowTemperatureTextPaint);
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            Log.i(TAG, "zz onConnected ");
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            Log.i(TAG, "zz onConnectionFailed ");
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            Log.i(TAG, "zz onDataChanged");

            for (DataEvent dataEvent : dataEvents) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem dataItem = dataEvent.getDataItem();
                    if (dataItem.getUri().getPath().compareTo(WEATHER_PATH) == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
                        setWeatherData(dataMap.getString(HIGH_TEMPERATURE),
                                dataMap.getString(LOW_TEMPERATURE), dataMap.getInt(WEATHER_CONDITION));
                        invalidate();
                    }
                }
            }

        }

        private void setWeatherData(String highTemperature, String lowTemperature, int weatherCondition) {
            Log.i(TAG, "zz highTemperature="+highTemperature+" lowTemperature="+lowTemperature+" weatherCondition"+weatherCondition);

            this.mHighTemperature = highTemperature;
            this.mLowTemperature = lowTemperature;
            this.mConditionIcon = BitmapFactory.decodeResource(resources, Utility.getIconResourceForWeatherCondition(weatherCondition));

        }
    }
}
