package com.codex.tabletlivewallpaper;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;

import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.IdentityHashMap;
import java.util.Map;

/** Process-wide two-worker decoder with conservative reservations. */
final class DecodeCoordinator {
    private static final long MAX_ENCODED_BYTES = 128L * 1024L * 1024L;
    interface Callback { void complete(Bitmap bitmap, WallpaperDiagnosticsStore.Failure failure); }
    interface ProbeCallback { void complete(WallpaperDiagnosticsStore.Failure failure); }
    private static final DecodeCoordinator INSTANCE = new DecodeCoordinator();
    private final ExecutorService workers = new ThreadPoolExecutor(2, 2, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(8), new ThreadPoolExecutor.AbortPolicy());
    private final long budget = Math.min(64L * 1024L * 1024L, Runtime.getRuntime().maxMemory() / 4L);
    private long reserved;
    private boolean staleSnapshotsPruned;
    /* A decoded frame remains part of the process budget until its owner releases it. */
    private final Map<Bitmap, Long> retained = new IdentityHashMap<>();
    static DecodeCoordinator get() { return INSTANCE; }
    void decode(final Context context, final Uri uri, final int targetWidth, final int targetHeight,
                final Callback callback) {
        decodeDescriptor(context, uri, targetWidth, targetHeight, callback);
    }
    /** A content provider may change a document between opens.  Render bounds and pixels must
        therefore be decoded from one descriptor, just like the import probe. */
    private void decodeDescriptor(final Context context, final Uri uri, final int targetWidth,
                                  final int targetHeight, final Callback callback) {
        try { workers.execute(new Runnable() { @Override public void run() {
            Bitmap bitmap = null; WallpaperDiagnosticsStore.Failure failure = null; long estimate = 0L;
            File snapshot = null;
            try {
                snapshot = snapshot(context, uri);
                try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                        snapshot, ParcelFileDescriptor.MODE_READ_ONLY)) {
                /* The open descriptor keeps the inode alive.  Removing the directory entry now
                   prevents a process death during decode from accumulating a 128 MiB orphan. */
                if (snapshot.delete()) snapshot = null;
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFileDescriptor(descriptor.getFileDescriptor(), null, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) failure = WallpaperDiagnosticsStore.Failure.DECODE;
                else if (bounds.outWidth > 32768 || bounds.outHeight > 32768 || (long) bounds.outWidth * bounds.outHeight > 100_000_000L) failure = WallpaperDiagnosticsStore.Failure.TOO_LARGE;
                else {
                    int sample = 1; int desired = Math.max(1, Math.max(targetWidth, targetHeight));
                    while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > desired * 2L) sample *= 2;
                    while (true) { estimate = estimateBytes(bounds, sample); if (reserve(estimate)) break; if (sample >= largestSafeSample(bounds)) { failure = WallpaperDiagnosticsStore.Failure.MEMORY; break; } sample *= 2; }
                    if (failure == null) {
                        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = sample; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                        Os.lseek(descriptor.getFileDescriptor(), 0L, OsConstants.SEEK_SET);
                        bitmap = BitmapFactory.decodeFileDescriptor(descriptor.getFileDescriptor(), null, options);
                        if (bitmap == null) failure = WallpaperDiagnosticsStore.Failure.DECODE;
                    }
                }
                }
            } catch (SecurityException exception) { failure = WallpaperDiagnosticsStore.Failure.PERMISSION; }
            catch (EncodedTooLargeException exception) { failure = WallpaperDiagnosticsStore.Failure.TOO_LARGE; }
            catch (OutOfMemoryError error) { failure = WallpaperDiagnosticsStore.Failure.MEMORY; }
            catch (Exception exception) { failure = WallpaperDiagnosticsStore.Failure.DECODE; }
            finally {
                if (snapshot != null) snapshot.delete();
                if (bitmap != null && failure == null) retain(bitmap, estimate);
                else if (estimate > 0L) release(estimate);
            }
            callback.complete(bitmap, failure);
        }}); } catch (RejectedExecutionException full) { callback.complete(null, WallpaperDiagnosticsStore.Failure.MEMORY); }
    }
    /* Asset streams are immutable app resources; their legacy fallback can retain the stream path. */
    /** Legacy migration fallback only: bundled files are never represented as user URIs. */
    void decodeAsset(final AssetManager assets, final String assetName, final int targetWidth,
                     final int targetHeight, final Callback callback) {
        decode(new InputOpener() { @Override public InputStream open() throws Exception {
            return assets.open(assetName);
        }}, targetWidth, targetHeight, callback);
    }
    private void decode(final InputOpener opener, final int targetWidth, final int targetHeight,
                        final Callback callback) {
        try { workers.execute(new Runnable() { @Override public void run() {
            Bitmap bitmap = null; WallpaperDiagnosticsStore.Failure failure = null;
            long estimate = 0L;
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                try (InputStream input = opener.open()) { if (input == null) throw new SecurityException(); BitmapFactory.decodeStream(input, null, bounds); }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) failure = WallpaperDiagnosticsStore.Failure.DECODE;
                else if (bounds.outWidth > 32768 || bounds.outHeight > 32768 || (long) bounds.outWidth * bounds.outHeight > 100_000_000L) failure = WallpaperDiagnosticsStore.Failure.TOO_LARGE;
                else {
                    int sample = 1; int desired = Math.max(1, Math.max(targetWidth, targetHeight));
                    while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > desired * 2L) sample *= 2;
                    /* A reservation failure is not an invitation to oversubscribe the process.
                       Keep increasing the sample until the candidate fits the remaining shared
                       budget, or until even a 1x1-equivalent frame cannot be reserved. */
                    while (true) {
                        estimate = estimateBytes(bounds, sample);
                        if (reserve(estimate)) break;
                        if (sample >= largestSafeSample(bounds)) {
                            failure = WallpaperDiagnosticsStore.Failure.MEMORY;
                            break;
                        }
                        sample *= 2;
                    }
                    if (failure == null) {
                        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = sample; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                        try (InputStream input = opener.open()) { bitmap = input == null ? null : BitmapFactory.decodeStream(input, null, options); }
                        if (bitmap == null) failure = WallpaperDiagnosticsStore.Failure.DECODE;
                    }
                }
            } catch (SecurityException exception) { failure = WallpaperDiagnosticsStore.Failure.PERMISSION; }
            catch (OutOfMemoryError error) { failure = WallpaperDiagnosticsStore.Failure.MEMORY; }
            catch (Exception exception) { failure = WallpaperDiagnosticsStore.Failure.DECODE; }
            finally {
                if (bitmap != null && failure == null) retain(bitmap, estimate);
                else if (estimate > 0L) release(estimate);
            }
            callback.complete(bitmap, failure);
        }}); } catch (RejectedExecutionException full) { callback.complete(null, WallpaperDiagnosticsStore.Failure.MEMORY); }
    }
    private interface InputOpener { InputStream open() throws Exception; }
    /** Bounded pixel safety/availability check used by retry and settings import. */
    void probe(final Context context, final Uri uri, final ProbeCallback callback) {
        try { workers.execute(new Runnable() { @Override public void run() {
            WallpaperDiagnosticsStore.Failure failure = null;
            Bitmap decoded = null;
            long estimate = 0L;
            File snapshot = null;
            try {
                /* One bounded provider read becomes an immutable local snapshot.  This preserves
                   bounds/pixel identity and also supports providers backed by non-seekable pipes. */
                snapshot = snapshot(context, uri);
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                        snapshot, ParcelFileDescriptor.MODE_READ_ONLY)) {
                    if (snapshot.delete()) snapshot = null;
                    BitmapFactory.decodeFileDescriptor(descriptor.getFileDescriptor(), null, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) failure = WallpaperDiagnosticsStore.Failure.DECODE;
                else if (bounds.outWidth > 32768 || bounds.outHeight > 32768
                        || (long) bounds.outWidth * bounds.outHeight > 100_000_000L) failure = WallpaperDiagnosticsStore.Failure.TOO_LARGE;
                else {
                    /* A valid header is not sufficient: decode a small sampled frame so a
                       truncated payload cannot be accepted into a persistent draft. */
                    int sample = 1;
                    while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > 512L) sample *= 2;
                    while (true) {
                        estimate = estimateBytes(bounds, sample);
                        if (reserve(estimate)) break;
                        if (sample >= largestSafeSample(bounds)) { failure = WallpaperDiagnosticsStore.Failure.MEMORY; break; }
                        sample *= 2;
                    }
                    if (failure == null) {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inSampleSize = sample; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                        Os.lseek(descriptor.getFileDescriptor(), 0L, OsConstants.SEEK_SET);
                        decoded = BitmapFactory.decodeFileDescriptor(descriptor.getFileDescriptor(), null, options);
                        if (decoded == null) failure = WallpaperDiagnosticsStore.Failure.DECODE;
                    }
                }
                }
            } catch (SecurityException exception) { failure = WallpaperDiagnosticsStore.Failure.PERMISSION; }
            catch (EncodedTooLargeException exception) { failure = WallpaperDiagnosticsStore.Failure.TOO_LARGE; }
            catch (OutOfMemoryError error) { failure = WallpaperDiagnosticsStore.Failure.MEMORY; }
            catch (Exception exception) { failure = WallpaperDiagnosticsStore.Failure.DECODE; }
            finally {
                if (snapshot != null) snapshot.delete();
                if (decoded != null && !decoded.isRecycled()) decoded.recycle();
                if (estimate > 0L) release(estimate);
            }
            callback.complete(failure);
        }}); } catch (RejectedExecutionException full) { callback.complete(WallpaperDiagnosticsStore.Failure.MEMORY); }
    }
    private File snapshot(Context context, Uri uri) throws Exception {
        pruneStaleSnapshotsOnce(context.getCacheDir());
        File file = File.createTempFile("wallpaper-decode-", ".image", context.getCacheDir());
        ParcelFileDescriptor source = null;
        boolean complete = false;
        try {
            source = context.getContentResolver().openFileDescriptor(uri, "r");
            if (source == null) throw new SecurityException();
            if (source.getStatSize() > MAX_ENCODED_BYTES) throw new EncodedTooLargeException();
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(source);
                 FileOutputStream output = new FileOutputStream(file)) {
                source = null; // AutoCloseInputStream owns the descriptor from here.
                byte[] buffer = new byte[32 * 1024];
                long total = 0L;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_ENCODED_BYTES) throw new EncodedTooLargeException();
                    output.write(buffer, 0, count);
                }
                output.flush();
            }
            complete = true;
            return file;
        } finally {
            if (source != null) try { source.close(); } catch (IOException ignored) { }
            if (!complete) file.delete();
        }
    }
    private synchronized void pruneStaleSnapshotsOnce(File cacheDirectory) {
        if (staleSnapshotsPruned) return;
        staleSnapshotsPruned = true;
        File[] files = cacheDirectory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.getName().startsWith("wallpaper-decode-")
                    && file.getName().endsWith(".image")) file.delete();
        }
    }
    synchronized void resetSnapshotPruningForTests() { staleSnapshotsPruned = false; }
    synchronized boolean retainsForTests(Bitmap bitmap) { return retained.containsKey(bitmap); }
    private static final class EncodedTooLargeException extends IOException { }
    private synchronized boolean reserve(long estimate) {
        if (estimate <= 0L || reserved + estimate > budget) return false;
        reserved += estimate;
        return true;
    }
    private static long estimateBytes(BitmapFactory.Options bounds, int sample) {
        long width = Math.max(1L, (bounds.outWidth + (long) sample - 1L) / sample);
        long height = Math.max(1L, (bounds.outHeight + (long) sample - 1L) / sample);
        return 4L * width * height;
    }
    private static int largestSafeSample(BitmapFactory.Options bounds) {
        int sample = 1;
        while (bounds.outWidth / (long) sample > 1L || bounds.outHeight / (long) sample > 1L) {
            if (sample > (1 << 29)) return sample;
            sample *= 2;
        }
        return sample;
    }
    private synchronized void release(long estimate) { reserved = Math.max(0L, reserved - estimate); }
    synchronized void release(Bitmap bitmap) {
        Long estimate = retained.remove(bitmap);
        if (estimate != null) release(estimate);
    }
    private synchronized void retain(Bitmap bitmap, long estimate) {
        retained.put(bitmap, estimate);
    }
}
