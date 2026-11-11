package com.raghav.karaoke;

import android.content.Context;
import android.os.SystemClock;

import com.raghav.karaoke.core.LocalLibrary;
import com.raghav.karaoke.core.Song;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Songs on USB sticks, found by walking the storage folders on a background thread. */
final class UsbLibrary implements LocalLibrary {
    private static final long RESCAN_MS = 30000;
    private static final int MAX_DEPTH = 4;
    private static final int MAX_FILES = 5000;
    private static final int MAX_RESULTS = 15;

    private final Context app;
    private final AtomicBoolean scanning = new AtomicBoolean();
    private volatile List<File> files = Collections.emptyList();
    private volatile long lastScanAt;

    UsbLibrary(Context context) {
        app = context.getApplicationContext();
        rescan();
    }

    /** Starts a scan unless one is running. Used when permission was just granted or the cache has aged out. */
    void rescan() {
        if (!scanning.compareAndSet(false, true)) {
            return;
        }
        lastScanAt = SystemClock.elapsedRealtime();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<File> found = new ArrayList<>();
                    for (File root : roots()) {
                        walk(root, 0, found);
                    }
                    files = found;
                } catch (Throwable ignored) {
                    // A strange mount must never take the app down; keep the previous list.
                } finally {
                    scanning.set(false);
                }
            }
        }, "usb-scan");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private Set<File> roots() {
        Set<File> roots = new LinkedHashSet<>();
        addChildren(new File("/storage"), roots);
        addChildren(new File("/mnt/usb"), roots);
        // /storage may not be listable; app dirs reveal each mounted volume: <volume>/Android/data/<pkg>/files.
        File[] appDirs = app.getExternalFilesDirs(null);
        if (appDirs != null) {
            for (File dir : appDirs) {
                if (dir == null) {
                    continue;
                }
                String path = dir.getAbsolutePath();
                int cut = path.indexOf("/Android/");
                if (cut > 0 && !path.contains("/emulated/")) {
                    roots.add(new File(path.substring(0, cut)));
                }
            }
        }
        return roots;
    }

    private static void addChildren(File parent, Set<File> out) {
        File[] children = parent.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String name = child.getName();
            if (!name.equals("emulated") && !name.equals("self")) {
                out.add(child);
            }
        }
    }

    private static void walk(File dir, int depth, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (out.size() >= MAX_FILES) {
                return;
            }
            String name = child.getName();
            if (name.startsWith(".")) {
                continue;
            }
            if (child.isDirectory()) {
                if (depth < MAX_DEPTH && !name.equals("Android")) {
                    walk(child, depth + 1, out);
                }
            } else if (isAudio(name)) {
                out.add(child);
            }
        }
    }

    private static boolean isAudio(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".aac") || lower.endsWith(".wav");
    }

    @Override
    public List<Song> search(String query) {
        if (SystemClock.elapsedRealtime() - lastScanAt >= RESCAN_MS) {
            rescan();
        }
        List<Song> results = new ArrayList<>();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return results;
        }
        String[] words = q.split("\\s+");
        for (File file : files) {
            String lower = file.getName().toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String word : words) {
                all &= lower.contains(word);
            }
            if (all) {
                results.add(toSong(file));
                if (results.size() >= MAX_RESULTS) {
                    break;
                }
            }
        }
        return results;
    }

    private static Song toSong(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        Song song = new Song();
        song.id = "usb:" + file.getAbsolutePath();
        song.title = dot > 0 ? name.substring(0, dot) : name;
        song.artist = "USB";
        song.source = "usb";
        song.streamUrl = file.getAbsolutePath();
        return song;
    }
}
