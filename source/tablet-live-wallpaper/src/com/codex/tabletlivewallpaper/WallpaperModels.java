package com.codex.tabletlivewallpaper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Immutable schema-v1 values. Runtime playback state deliberately lives elsewhere. */
enum ProfileOrientation { PORTRAIT, LANDSCAPE }

enum OrderMode { SEQUENTIAL, SHUFFLE }

final class RotationSettings {
    static final long MIN_INTERVAL_MILLIS = 5_000L;
    static final long MAX_INTERVAL_MILLIS = 24L * 60L * 60L * 1_000L;
    static final long DEFAULT_INTERVAL_MILLIS = 10_000L;
    final long intervalMillis;
    final OrderMode orderMode;

    RotationSettings(long intervalMillis, OrderMode orderMode) {
        this.intervalMillis = intervalMillis;
        this.orderMode = orderMode;
    }
}

final class WallpaperImage {
    final String id;
    final String sourceUri;
    final String displayName;

    WallpaperImage(String id, String sourceUri, String displayName) {
        this.id = id;
        this.sourceUri = sourceUri;
        this.displayName = ConfigValidator.cleanDisplayName(displayName, sourceUri);
    }
}

final class WallpaperProfile {
    final List<WallpaperImage> images;
    final RotationSettings rotation;

    WallpaperProfile(List<WallpaperImage> images, RotationSettings rotation) {
        this.images = Collections.unmodifiableList(new ArrayList<>(images));
        this.rotation = rotation;
    }

    static WallpaperProfile empty() {
        return new WallpaperProfile(Collections.<WallpaperImage>emptyList(),
                new RotationSettings(RotationSettings.DEFAULT_INTERVAL_MILLIS, OrderMode.SEQUENTIAL));
    }
}

final class WallpaperConfiguration {
    static final int SCHEMA_VERSION = 1;
    final int schemaVersion;
    final long revision;
    final WallpaperProfile portraitProfile;
    final WallpaperProfile landscapeProfile;

    WallpaperConfiguration(int schemaVersion, long revision, WallpaperProfile portraitProfile,
                           WallpaperProfile landscapeProfile) {
        this.schemaVersion = schemaVersion;
        this.revision = revision;
        this.portraitProfile = portraitProfile;
        this.landscapeProfile = landscapeProfile;
    }

    static WallpaperConfiguration empty(long revision) {
        return new WallpaperConfiguration(SCHEMA_VERSION, revision, WallpaperProfile.empty(),
                WallpaperProfile.empty());
    }

    WallpaperProfile profile(ProfileOrientation orientation) {
        return orientation == ProfileOrientation.PORTRAIT ? portraitProfile : landscapeProfile;
    }

    WallpaperConfiguration withProfile(ProfileOrientation orientation, WallpaperProfile profile,
                                       long nextRevision) {
        return orientation == ProfileOrientation.PORTRAIT
                ? new WallpaperConfiguration(SCHEMA_VERSION, nextRevision, profile, landscapeProfile)
                : new WallpaperConfiguration(SCHEMA_VERSION, nextRevision, portraitProfile, profile);
    }
}

final class ConfigValidator {
    static final int MAX_IMAGES_PER_PROFILE = 64;
    static final int MAX_DISTINCT_URIS = 64;
    static final int MAX_URI_LENGTH = 4096;
    static final int MAX_DISPLAY_NAME_CODE_POINTS = 120;
    static final int MAX_JSON_BYTES = 256 * 1024;

    private ConfigValidator() { }

    static void validate(WallpaperConfiguration configuration) {
        if (configuration == null || configuration.schemaVersion != WallpaperConfiguration.SCHEMA_VERSION
                || configuration.revision < 1 || configuration.portraitProfile == null
                || configuration.landscapeProfile == null) {
            throw new IllegalArgumentException("invalid schema v1 configuration");
        }
        Set<String> ids = new HashSet<>();
        Set<String> uris = new HashSet<>();
        validateProfile(configuration.portraitProfile, ids, uris);
        validateProfile(configuration.landscapeProfile, ids, uris);
        if (uris.size() > MAX_DISTINCT_URIS) throw new IllegalArgumentException("too many image URIs");
    }

    private static void validateProfile(WallpaperProfile profile, Set<String> ids, Set<String> allUris) {
        if (profile == null || profile.rotation == null || profile.rotation.orderMode == null
                || profile.rotation.intervalMillis < RotationSettings.MIN_INTERVAL_MILLIS
                || profile.rotation.intervalMillis > RotationSettings.MAX_INTERVAL_MILLIS
                || profile.images == null || profile.images.size() > MAX_IMAGES_PER_PROFILE) {
            throw new IllegalArgumentException("invalid wallpaper profile");
        }
        Set<String> profileUris = new HashSet<>();
        for (WallpaperImage image : profile.images) {
            if (image == null || empty(image.id) || empty(image.sourceUri)
                    || !isUuid(image.id) || !isPersistableContentUri(image.sourceUri)
                    || image.sourceUri.length() > MAX_URI_LENGTH || !ids.add(image.id)
                    || !profileUris.add(image.sourceUri)) {
                throw new IllegalArgumentException("duplicate or invalid wallpaper image");
            }
            allUris.add(image.sourceUri);
        }
    }

    static String cleanDisplayName(String name, String uri) {
        String source = name == null ? "" : name.replaceAll("[\\p{Cntrl}]", "").trim()
                .replaceAll("\\s+", " ");
        if (source.isEmpty() && uri != null) {
            int slash = uri.lastIndexOf('/');
            source = slash >= 0 && slash + 1 < uri.length() ? uri.substring(slash + 1) : "";
        }
        if (source.isEmpty()) source = "未命名图片";
        int count = source.codePointCount(0, source.length());
        return count <= MAX_DISPLAY_NAME_CODE_POINTS ? source
                : source.substring(0, source.offsetByCodePoints(0, MAX_DISPLAY_NAME_CODE_POINTS));
    }

    /** A v1 image is always a stable SAF-style content URI, never a filesystem path. */
    private static boolean isPersistableContentUri(String value) {
        return value.startsWith("content://") && value.length() > "content://".length();
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean empty(String value) { return value == null || value.trim().isEmpty(); }
}
