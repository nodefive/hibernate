package com.unl0cker.hibernate;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** All settings, in one SharedPreferences file. */
final class Prefs {

    static final String KEY_USER_APPS = "user_apps";
    static final String KEY_SYSTEM_APPS = "system_apps";
    static final String KEY_ONLY_LIST = "only_list";
    static final String KEY_LIST = "list";
    static final String KEY_EXCEPTIONS = "exceptions";
    static final String KEY_SCREEN_OFF = "screen_off";
    static final String KEY_SCREEN_OFF_DELAY = "screen_off_delay";
    static final String KEY_FAST = "fast_mode";
    static final String KEY_WIFI_OFF_AFTER = "wifi_off_after";
    static final String KEY_OVERLAY = "overlay";
    static final String KEY_ACTION_DELAY = "action_delay";

    // Device state, not settings: left out of backups.
    static final String KEY_FAILED = "failed";
    static final String KEY_ADB_AUTHORIZED = "adb_authorized";
    /** A wireless debugging connection has worked since the last authorization. */
    static final String KEY_FAST_VERIFIED = "fast_verified";

    static final String[] BOOLEAN_KEYS = {
            KEY_USER_APPS, KEY_SYSTEM_APPS, KEY_ONLY_LIST, KEY_SCREEN_OFF, KEY_FAST,
            KEY_WIFI_OFF_AFTER, KEY_OVERLAY
    };
    static final String[] INT_KEYS = {KEY_SCREEN_OFF_DELAY, KEY_ACTION_DELAY};
    static final String[] SET_KEYS = {KEY_LIST, KEY_EXCEPTIONS};

    /** Seconds to wait after the screen turns off. */
    static final int SCREEN_OFF_DELAY_MIN = 0;
    static final int SCREEN_OFF_DELAY_MAX = 120;
    static final int SCREEN_OFF_DELAY_STEP = 5;
    static final int SCREEN_OFF_DELAY_DEFAULT = 10;

    /** Milliseconds between apps in accessibility mode. */
    static final int ACTION_DELAY_MIN = 0;
    static final int ACTION_DELAY_MAX = 1000;
    static final int ACTION_DELAY_STEP = 50;
    static final int ACTION_DELAY_DEFAULT = 150;

    private Prefs() {
    }

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static boolean defaultBoolean(String key) {
        switch (key) {
            case KEY_USER_APPS:
            case KEY_FAST:
            case KEY_WIFI_OFF_AFTER:
            case KEY_OVERLAY:
                return true;
            default:
                return false;
        }
    }

    static int defaultInt(String key) {
        return KEY_SCREEN_OFF_DELAY.equals(key) ? SCREEN_OFF_DELAY_DEFAULT : ACTION_DELAY_DEFAULT;
    }

    static boolean bool(SharedPreferences p, String key) {
        return p.getBoolean(key, defaultBoolean(key));
    }

    static int integer(SharedPreferences p, String key) {
        return p.getInt(key, defaultInt(key));
    }

    static boolean userApps(SharedPreferences p) {
        return bool(p, KEY_USER_APPS);
    }

    static boolean systemApps(SharedPreferences p) {
        return bool(p, KEY_SYSTEM_APPS);
    }

    static boolean onlyList(SharedPreferences p) {
        return bool(p, KEY_ONLY_LIST);
    }

    static boolean screenOff(SharedPreferences p) {
        return bool(p, KEY_SCREEN_OFF);
    }

    static int screenOffDelay(SharedPreferences p) {
        return integer(p, KEY_SCREEN_OFF_DELAY);
    }

    static boolean fastEnabled(SharedPreferences p) {
        return bool(p, KEY_FAST);
    }

    static boolean wifiOffAfter(SharedPreferences p) {
        return bool(p, KEY_WIFI_OFF_AFTER);
    }

    static boolean overlay(SharedPreferences p) {
        return bool(p, KEY_OVERLAY);
    }

    static int actionDelay(SharedPreferences p) {
        return integer(p, KEY_ACTION_DELAY);
    }

    static boolean adbAuthorized(SharedPreferences p) {
        return p.getBoolean(KEY_ADB_AUTHORIZED, false);
    }

    static boolean fastVerified(SharedPreferences p) {
        return p.getBoolean(KEY_FAST_VERIFIED, false);
    }

    static Set<String> set(SharedPreferences p, String key) {
        return new HashSet<>(p.getStringSet(key, Collections.emptySet()));
    }

    static void putSet(SharedPreferences p, String key, Set<String> value) {
        p.edit().putStringSet(key, new HashSet<>(value)).apply();
    }
}
