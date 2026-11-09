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
        public final String mimeType;
        public final String volumeName;
        public final String relativePath;
        public final String parentDocumentId;

        public Item(String name, Uri uri, boolean video) {
            this(name, uri, video, null, null, null, null);
        }

        public Item(String name, Uri uri, boolean video, String mimeType,
                    String volumeName, String relativePath, String parentDocumentId) {
            this.name = name;
            this.uri = uri;
            this.video = video;
            this.mimeType = mimeType;
            this.volumeName = volumeName;
            this.relativePath = relativePath;
            this.parentDocumentId = parentDocumentId;
        }

        @Override
        public int compareTo(Item other) {
            return name.compareToIgnoreCase(other.name);
        }
    }

    private static List<Item> items = Collections.emptyList();
    private static String directoryKey = "";
    private static String treeUri = "";
    private static String volumeName = "";
    private static String relativePath = "";
    private static String directoryPath = "";
    private static int startIndex;

    private PlaybackQueue() {
    }

    public static void set(List<Item> newItems, String key, int index) {
        items = new ArrayList<>(newItems);
        directoryKey = key == null ? "" : key;
        startIndex = index;
    }

    public static void setDirectoryContext(String tree, String volume,
                                           String relative, String directory) {
        treeUri = tree == null ? "" : tree;
        volumeName = volume == null ? "" : volume;
        relativePath = relative == null ? "" : relative;
        directoryPath = directory == null ? "" : directory;
    }

    public static List<Item> getItems() {
        return items;
    }

    public static String getDirectoryKey() {
        return directoryKey;
    }

    public static String getTreeUri() {
        return treeUri;
    }

    public static String getVolumeName() {
        return volumeName;
    }

    public static String getRelativePath() {
        return relativePath;
    }

    public static String getDirectoryPath() {
        return directoryPath;
    }

    public static int getStartIndex() {
        return startIndex;
    }

    public static void clear() {
        items = Collections.emptyList();
        directoryKey = "";
        treeUri = "";
        volumeName = "";
        relativePath = "";
        directoryPath = "";
        startIndex = 0;
    }
}
