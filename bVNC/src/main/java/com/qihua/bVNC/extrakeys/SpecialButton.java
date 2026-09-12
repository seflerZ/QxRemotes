package com.qihua.bVNC.extrakeys;

import androidx.annotation.NonNull;

import java.util.HashMap;

/** The {@link Class} that implements special buttons for {@link ExtraKeysView}. */
public class SpecialButton {

    private static final HashMap<String, SpecialButton> map = new HashMap<>();

    public static final SpecialButton CTRL = new SpecialButton("CTRL");
    public static final SpecialButton ALT = new SpecialButton("ALT");
    public static final SpecialButton SHIFT = new SpecialButton("SHIFT");
    public static final SpecialButton META = new SpecialButton("META");
    public static final SpecialButton FN = new SpecialButton("FN");

    /** The special button key. */
    private final String key;

    /**
     * Initialize a {@link SpecialButton}.
     *
     * @param key The unique key name for the special button. The key is registered in {@link #map}
     *            with which the {@link SpecialButton} can be retrieved via a call to
     *            {@link #valueOf(String)}.
     */
    public SpecialButton(@NonNull final String key) {
        this.key = key;
        map.put(key, this);
    }

    /** Get {@link #key} for this {@link SpecialButton}. */
    public String getKey() {
        return key;
    }

    /**
     * Get the {@link SpecialButton} for {@code key}.
     *
     * @param key The unique key name for the special button.
     */
    public static SpecialButton valueOf(String key) {
        return map.get(key);
    }

    /**
     * Get the {@link SpecialButton} for {@code key}, creating a new one if it
     * doesn't exist. Used by {@link ExtraKeysView} to register extra-key
     * toggles (e.g. "F3") at view-build time.
     */
    public static SpecialButton registerOrGet(String key) {
        SpecialButton existing = map.get(key);
        if (existing != null) return existing;
        return new SpecialButton(key);
    }

    @NonNull
    @Override
    public String toString() {
        return key;
    }

}
