package com.codex.tabletlivewallpaper;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/** Private cache for an unsaved editor instance. */
final class ProfileDraftStore {
    static final class Draft {
        final long baseRevision;
        final EnumMap<ProfileOrientation, String> ids;
        final EnumMap<ProfileOrientation, WallpaperProfile> profiles;
        final EnumMap<ProfileOrientation, Boolean> dirty;
        Draft(long baseRevision, EnumMap<ProfileOrientation, String> ids,
              EnumMap<ProfileOrientation, WallpaperProfile> profiles,
              EnumMap<ProfileOrientation, Boolean> dirty) {
            this.baseRevision = baseRevision;
            this.ids = ids;
            this.profiles = profiles;
            this.dirty = dirty;
        }
    }

    private final AtomicFile file;
    ProfileDraftStore(Context context, String activityDraftId) {
        file = new AtomicFile(new File(context.getCacheDir(), "wallpaper-draft-" + activityDraftId + ".json"));
    }
    void save(Draft draft) {
        FileOutputStream output = null;
        try {
            JSONObject root = new JSONObject();
            root.put("baseRevision", draft.baseRevision);
            for (ProfileOrientation orientation : ProfileOrientation.values()) {
                WallpaperProfile profile = draft.profiles.get(orientation);
                JSONObject saved = new JSONObject();
                saved.put("id", draft.ids.get(orientation));
                saved.put("dirty", Boolean.TRUE.equals(draft.dirty.get(orientation)));
                saved.put("interval", profile.rotation.intervalMillis);
                saved.put("order", profile.rotation.orderMode.name());
                JSONArray images = new JSONArray();
                for (WallpaperImage image : profile.images) {
                    JSONObject record = new JSONObject(); record.put("id", image.id);
                    record.put("uri", image.sourceUri); record.put("name", image.displayName); images.put(record);
                }
                saved.put("images", images); root.put(orientation.name(), saved);
            }
            output = file.startWrite(); output.write(root.toString().getBytes(StandardCharsets.UTF_8));
            output.flush(); output.getFD().sync(); file.finishWrite(output);
        } catch (Exception failure) {
            if (output != null) file.failWrite(output);
        }
    }
    Draft load() {
        if (!file.getBaseFile().exists()) return null;
        try (FileInputStream input = file.openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            JSONObject root = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            EnumMap<ProfileOrientation, String> ids = new EnumMap<>(ProfileOrientation.class);
            EnumMap<ProfileOrientation, WallpaperProfile> profiles = new EnumMap<>(ProfileOrientation.class);
            EnumMap<ProfileOrientation, Boolean> dirty = new EnumMap<>(ProfileOrientation.class);
            for (ProfileOrientation orientation : ProfileOrientation.values()) {
                JSONObject saved = root.getJSONObject(orientation.name()); JSONArray images = saved.getJSONArray("images");
                List<WallpaperImage> values = new ArrayList<>();
                for (int i = 0; i < images.length(); i++) { JSONObject image = images.getJSONObject(i);
                    values.add(new WallpaperImage(image.getString("id"), image.getString("uri"), image.getString("name"))); }
                ids.put(orientation, saved.getString("id"));
                /* Drafts written before dirty-state persistence represented unsaved state. */
                dirty.put(orientation, saved.has("dirty") ? saved.getBoolean("dirty") : true);
                profiles.put(orientation, new WallpaperProfile(values, new RotationSettings(saved.getLong("interval"),
                        OrderMode.valueOf(saved.getString("order")))));
            }
            return new Draft(root.getLong("baseRevision"), ids, profiles, dirty);
        } catch (Exception failure) { return null; }
    }
    void clear() { file.delete(); }
}
