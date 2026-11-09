package com.kkorpse.tvpicview;

import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The player runs in the same process as MainActivity, so the current playlist
 * can be handed over without hitting the Binder transaction size limit.
 */
public final class PlaybackQueue {

    public static final class Item implements Comparable<Item> {
        public final String name;
        public final Uri uri;
        public final boolean video;

        public Item(String name, Uri uri, boolean video) {
            this.name = name;
            this.uri = uri;
            this.video = video;
        }

        @Override
        public int compareTo(Item other) {
            return name.compareToIgnoreCase(other.name);
        }
    }

    private static List<Item> items = Collections.emptyList();
    private static String directoryKey = "";
    private static int startIndex;

    private PlaybackQueue() {
    }

    public static void set(List<Item> newItems, String key, int index) {
        items = new ArrayList<>(newItems);
        directoryKey = key == null ? "" : key;
        startIndex = index;
    }

    public static List<Item> getItems() {
        return items;
    }

    public static String getDirectoryKey() {
        return directoryKey;
    }

    public static int getStartIndex() {
        return startIndex;
    }

    public static void clear() {
        items = Collections.emptyList();
        directoryKey = "";
        startIndex = 0;
    }
}
