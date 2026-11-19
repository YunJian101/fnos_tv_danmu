package com.fntv.app.ui;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * 主题模式：0 = 跟随系统，1 = 夜间，2 = 白天。
 * 配色本身由 values / values-night 两套颜色令牌提供。
 */
public final class Theme {

    public static final int AUTO = 0;
    public static final int NIGHT = 1;
    public static final int DAY = 2;

    private static final String PREF = "fntv_prefs";
    private static final String KEY = "theme_mode";

    private Theme() {}

    public static int get(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return p.getInt(KEY, AUTO);
    }

    public static void set(Context c, int mode) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        p.edit().putInt(KEY, mode).apply();
        apply(mode);
    }

    public static void apply(int mode) {
        switch (mode) {
            case NIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case DAY:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    public static void applySaved(Context c) {
        apply(get(c));
    }

    public static String label(int mode) {
        switch (mode) {
            case NIGHT: return "夜间";
            case DAY: return "白天";
            default: return "跟随系统";
        }
    }
}
