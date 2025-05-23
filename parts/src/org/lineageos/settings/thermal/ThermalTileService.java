/*
 * Copyright (C) 2025 The LineageOS Project
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

package org.lineageos.settings.thermal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.settings.R;
import org.lineageos.settings.utils.FileUtils;

public class ThermalTileService extends TileService {
    private static final String TAG = "ThermalTileService";
    private static final String THERMAL_SCONFIG = "/sys/class/thermal/thermal_message/sconfig";
    private static final String THERMAL_ENABLED_KEY = "thermal_enabled";
    private static final String SYS_PROP = "sys.perf_mode_active";
    private static final int NOTIFICATION_ID_PERFORMANCE = 1001;

    // Constants for thermal modes
    private static final int MODE_DEFAULT = 0;
    private static final int MODE_PERFORMANCE = 1;
    private static final int MODE_BATTERY_SAVER = 2;
    private static final int MODE_UNKNOWN = 3;

    private String[] modes;
    private int currentMode = MODE_DEFAULT; // Default mode index
    private SharedPreferences mSharedPrefs;
    private NotificationManager mNotificationManager;
    private Notification mNotification;
    private ContentObserver batterySaverObserver;

    @Override
    public void onCreate() {
        super.onCreate();
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        // Ensure a default value for the master switch
        if (!mSharedPrefs.contains(THERMAL_ENABLED_KEY)) {
            mSharedPrefs.edit().putBoolean(THERMAL_ENABLED_KEY, false).apply();
        }

        setupNotificationChannel();
        registerBatterySaverObserver();
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        modes = new String[]{
                getString(R.string.thermal_mode_default),
                getString(R.string.thermal_mode_performance),
                getString(R.string.thermal_mode_battery_saver),
                getString(R.string.thermal_mode_unknown)
        };

        boolean isMasterEnabled = mSharedPrefs.getBoolean(THERMAL_ENABLED_KEY, false);
        if (isMasterEnabled) {
            updateTileDisabled();
        } else {
            currentMode = getCurrentThermalMode();
            if (currentMode == MODE_UNKNOWN) {
                currentMode = MODE_DEFAULT;
                setThermalMode(currentMode);
            }
            updateTile();
        }
    }

    @Override
    public void onClick() {
        boolean isMasterEnabled = mSharedPrefs.getBoolean(THERMAL_ENABLED_KEY, false);
        if (isMasterEnabled) {
            return;
        }
        toggleThermalMode();
    }

    private void toggleThermalMode() {
        if (currentMode == MODE_UNKNOWN) {
            currentMode = MODE_DEFAULT;
        } else {
            currentMode = (currentMode + 1) % 3; // Cycle through 0, 1, 2
        }
        setThermalMode(currentMode);
        updateTile();
    }

    private int getCurrentThermalMode() {
        String line = FileUtils.readOneLine(THERMAL_SCONFIG);
        if (line == null) {
            Log.e(TAG, "Failed to read thermal mode from " + THERMAL_SCONFIG);
            return MODE_UNKNOWN;
        }
        try {
            int value = Integer.parseInt(line.trim());
            switch (value) {
                case 0: return MODE_DEFAULT;
                case 6: return MODE_PERFORMANCE;
                case 1: return MODE_BATTERY_SAVER;
                default: return MODE_UNKNOWN;
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "Error parsing thermal mode value: ", e);
            return MODE_UNKNOWN;
        }
    }

    private void setThermalMode(int mode) {
        int thermalValue;
        switch (mode) {
            case MODE_DEFAULT:
                thermalValue = 0;
                setPerformanceModeActive(1); // Default mode
                break;
            case MODE_PERFORMANCE:
                thermalValue = 6;
                setPerformanceModeActive(2); // Performance mode
                break;
            case MODE_BATTERY_SAVER:
                thermalValue = 1;
                setPerformanceModeActive(0); // Battery saver mode
                break;
            default:
                thermalValue = 0;
                setPerformanceModeActive(1); // Default mode
                break;
        }

        boolean success = FileUtils.writeLine(THERMAL_SCONFIG, String.valueOf(thermalValue));
        Log.d(TAG, "Thermal mode changed to " + modes[mode] + ": " + success);

        if (mode == MODE_BATTERY_SAVER) {
            enableBatterySaver(true);
            cancelPerformanceNotification();
        } else {
            enableBatterySaver(false);
            if (mode == MODE_PERFORMANCE) {
                showPerformanceNotification();
            } else {
                cancelPerformanceNotification();
            }
        }
    }

    private void enableBatterySaver(boolean enable) {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            boolean isBatterySaverEnabled = powerManager.isPowerSaveMode();
            if (enable && !isBatterySaverEnabled) {
                powerManager.setPowerSaveModeEnabled(true);
                Log.d(TAG, "Battery Saver mode enabled.");
            } else if (!enable && isBatterySaverEnabled) {
                powerManager.setPowerSaveModeEnabled(false);
                Log.d(TAG, "Battery Saver mode disabled.");
            }
        }
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile != null) {
            if (currentMode == MODE_PERFORMANCE) {
                tile.setState(Tile.STATE_ACTIVE);
                tile.setIcon(Icon.createWithResource(this, R.drawable.ic_thermal_performance));
            } else if (currentMode == MODE_BATTERY_SAVER) {
                tile.setState(Tile.STATE_INACTIVE);
                tile.setIcon(Icon.createWithResource(this, R.drawable.ic_thermal_battery_saver));
            } else {
                tile.setState(Tile.STATE_INACTIVE);
                tile.setIcon(Icon.createWithResource(this, R.drawable.ic_thermal_default));
            }
            tile.setLabel(getString(R.string.thermal_tile_label));
            tile.setSubtitle(modes[currentMode]);
            tile.updateTile();
        }
    }

    private void updateTileDisabled() {
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_UNAVAILABLE);
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_thermal_default)); // Default icon when disabled
            tile.setLabel(getString(R.string.thermal_tile_label));
            tile.setSubtitle(getString(R.string.thermal_tile_disabled_subtitle));
            tile.updateTile();
        }
    }

    private void setupNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                TAG,
                getString(R.string.perf_mode_title),
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setBlockable(true);
        mNotificationManager.createNotificationChannel(channel);
    }

    private void showPerformanceNotification() {
        Intent intent = new Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        mNotification = new Notification.Builder(this, TAG)
                .setContentTitle(getString(R.string.perf_mode_title))
                .setContentText(getString(R.string.perf_mode_notification))
                .setSmallIcon(R.drawable.ic_thermal_performance)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setFlag(Notification.FLAG_NO_CLEAR, true)
                .build();
        mNotificationManager.notify(NOTIFICATION_ID_PERFORMANCE, mNotification);
    }

    private void cancelPerformanceNotification() {
        mNotificationManager.cancel(NOTIFICATION_ID_PERFORMANCE);
    }

    private void setPerformanceModeActive(int mode) {
        SystemProperties.set(SYS_PROP, String.valueOf(mode));
        Log.d(TAG, "Performance mode active set to: " + mode);
    }

    private void registerBatterySaverObserver() {
        batterySaverObserver = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange) {
                boolean isBatterySaverOn = Settings.Global.getInt(
                        getContentResolver(),
                        Settings.Global.LOW_POWER_MODE, 0) == 1;

                if (isBatterySaverOn && (currentMode == MODE_DEFAULT || currentMode == MODE_PERFORMANCE)) {
                    Log.d(TAG, "Battery saver enabled, switching to battery saver thermal mode.");
                    currentMode = MODE_BATTERY_SAVER;
                    setThermalMode(currentMode);
                    updateTile();
                }
            }
        };

        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.LOW_POWER_MODE),
                false,
                batterySaverObserver
        );
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (batterySaverObserver != null) {
            getContentResolver().unregisterContentObserver(batterySaverObserver);
        }
    }
}