package com.codex.tabletlivewallpaper;

import java.util.HashMap;
import java.util.Map;

/** Process-local, URI-free diagnostics for settings feedback. */
final class WallpaperDiagnosticsStore {
    private static final long TRANSIENT_TTL_MILLIS = 30L * 60L * 1_000L;
    private static final WallpaperDiagnosticsStore INSTANCE = new WallpaperDiagnosticsStore();
    enum Source { ACTIVE_ENGINE, PREVIEW_ENGINE, SETTINGS_PROBE }
    enum Failure { PERMISSION, DECODE, TOO_LARGE, MEMORY, CANCELLED }
    enum State { CHECKING, SUCCESS, FAILURE }
    interface Clock { long now(); }
    static final class Entry {
        final Failure failure; final Source source; final long timestamp; final State state;
        Entry(Failure f, Source s, long t, State state) { failure = f; source = s; timestamp = t; this.state = state; }
    }
    private final Map<String, Entry> entries = new HashMap<>();
    private Clock clock = new Clock() { @Override public long now() { return android.os.SystemClock.elapsedRealtime(); } };
    static WallpaperDiagnosticsStore get() { return INSTANCE; }
    synchronized void report(long revision, String imageId, Failure failure, Source source) { entries.put(key(revision, imageId, source), new Entry(failure, source, clock.now(), State.FAILURE)); }
    synchronized void checking(long revision, String imageId, Source source) { entries.put(key(revision, imageId, source), new Entry(null, source, clock.now(), State.CHECKING)); }
    synchronized void success(long revision, String imageId, Source source) { entries.put(key(revision, imageId, source), new Entry(null, source, clock.now(), State.SUCCESS)); }
    synchronized void setClockForTests(Clock value) { clock = value == null ? new Clock() { @Override public long now() { return android.os.SystemClock.elapsedRealtime(); } } : value; }
    synchronized void clear(long revision, String imageId) { for (Source source : Source.values()) entries.remove(revision + ":" + imageId + ":" + source); }
    synchronized void clear(long revision, String imageId, Source source) { entries.remove(revision + ":" + imageId + ":" + source); }
    synchronized Entry get(long revision, String imageId, Source source) {
        String key = key(revision, imageId, source);
        Entry entry = entries.get(key);
        if (entry != null && entry.state == State.FAILURE && entry.failure != Failure.PERMISSION
                && entry.failure != Failure.TOO_LARGE && clock.now() - entry.timestamp > TRANSIENT_TTL_MILLIS) {
            entries.remove(key); return null;
        }
        return entry;
    }
    private static String key(long revision, String imageId, Source source) { return revision + ":" + imageId + ":" + source; }
}
