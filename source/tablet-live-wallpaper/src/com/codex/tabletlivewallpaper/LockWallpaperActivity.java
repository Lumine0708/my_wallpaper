package com.codex.tabletlivewallpaper;

/*
 * Historical note: this file used to host a one-shot Activity for setting a
 * bundled lock-screen image. It is deliberately left as a no-op placeholder and
 * is not registered in AndroidManifest.xml. The app must not request
 * SET_WALLPAPER or write FLAG_LOCK, so MIUI keeps lock-screen ownership in its
 * own wallpaper/template stack.
 */
final class LockWallpaperActivity {
    private LockWallpaperActivity() {
    }
}
