package com.codex.tabletlivewallpaper;

final class WallpaperConfig {
    static final String PREFS = "wallpaper";
    static final String KEY_IMAGE_URI = "image_uri";
    static final String KEY_ROTATION_OFFSET = "rotation_offset";
    static final String EXTERNAL_IMAGE_NAME = "wallpaper.jpg";
    static final String BUNDLED_IMAGE_NAME = "earth.jpg";
    static final String BUNDLED_LANDSCAPE_IMAGE_NAME = "earthrise.jpg";
    static final String[] BUNDLED_PORTRAIT_ROTATION_IMAGE_NAMES = new String[]{
            "earth.jpg",
            "earthrise.jpg"
    };
    static final int MAX_DECODED_EDGE = 4096;
    static final long PORTRAIT_ROTATION_INTERVAL_MS = 10L * 1000L;
    static final long PORTRAIT_TRANSITION_DURATION_MS = 680L;

    private WallpaperConfig() {
    }
}
