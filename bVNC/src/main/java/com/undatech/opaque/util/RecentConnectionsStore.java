package com.undatech.opaque.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists the ids of the most recently launched connections (newest first).
 * The database only keeps a single most-recent row, so the history lives here.
 */
public final class RecentConnectionsStore {
    private static final String PREFS_NAME = "recentConnections";
    private static final String KEY_IDS = "ids";
    private static final String SEPARATOR = "\n";
    // Keep a few extra so a deleted connection does not leave the list short.
    private static final int MAX_STORED = 5;
    public static final int MAX_SHOWN = 3;

    private RecentConnectionsStore() {
    }

    public static List<String> getRecentIds(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_IDS, "");
        List<String> ids = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return ids;
        }
        for (String id : raw.split(SEPARATOR)) {
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    public static void recordAccess(Context context, String id) {
        if (id == null || id.isEmpty() || "$NC".equals(id)) {
            return;
        }
        List<String> ids = getRecentIds(context);
        ids.remove(id);
        ids.add(0, id);
        while (ids.size() > MAX_STORED) {
            ids.remove(ids.size() - 1);
        }
        save(context, ids);
    }

    public static void remove(Context context, String id) {
        List<String> ids = getRecentIds(context);
        if (ids.remove(id)) {
            save(context, ids);
        }
    }

    private static void save(Context context, List<String> ids) {
        StringBuilder raw = new StringBuilder();
        for (String recentId : ids) {
            if (raw.length() > 0) {
                raw.append(SEPARATOR);
            }
            raw.append(recentId);
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_IDS, raw.toString()).apply();
    }
}
