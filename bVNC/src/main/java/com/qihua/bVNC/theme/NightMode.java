package com.qihua.bVNC.theme;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;

/** The modes used by to decide night mode for themes. */
public enum NightMode {

    /** Night theme should be enabled. */
    NIGHT("night", AppCompatDelegate.MODE_NIGHT_YES),

    /** Day theme should be enabled. */
    LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),

    /**
     * Use night or dark theme depending on system night mode.
     * https://developer.android.com/guide/topics/resources#NightQualifier
     */
    AUTO("auto", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

    /** The current app wide night mode used by various libraries. Defaults to {@link #AUTO}. */
    private static NightMode APP_NIGHT_MODE;

    private final String name;
    private final @AppCompatDelegate.NightMode int mode;

    NightMode(final String name, int mode) {
        this.name = name;
        this.mode = mode;
    }

    public String getName() {
        return name;
    }

    public int getMode() {
        return mode;
    }


    /** Get {@link NightMode} for {@code name} if found, otherwise {@code null}. */
    @Nullable
    public static NightMode modeOf(@Nullable String name) {
        if (name == null) {
            return null;
        }
        for (NightMode v : NightMode.values()) {
            if (v.name.equals(name)) {
                return v;
            }
        }
        // Legacy names kept for preferences written by older builds.
        switch (name) {
            case "true":
                return NIGHT;
            case "false":
                return LIGHT;
            case "system":
                return AUTO;
            default:
                return null;
        }
    }

    /** Get {@link NightMode} for {@code name} if found, otherwise {@code def}. */
    @NonNull
    public static NightMode modeOf(@Nullable String name, @NonNull NightMode def) {
        NightMode nightMode = modeOf(name);
        return nightMode != null ? nightMode : def;
    }


    /** Set {@link #APP_NIGHT_MODE}. */
    public static void setAppNightMode(@Nullable String name) {
        if (name == null || name.isEmpty()) {
            APP_NIGHT_MODE = AUTO;
            return;
        }
        NightMode nightMode = modeOf(name);
        if (nightMode == null) {
            return;
        }
        APP_NIGHT_MODE = nightMode;
    }

    /** Get {@link #APP_NIGHT_MODE}. */
    @NonNull
    public static NightMode getAppNightMode() {
        if (APP_NIGHT_MODE == null)
            APP_NIGHT_MODE = AUTO;

        return APP_NIGHT_MODE;
    }

}
