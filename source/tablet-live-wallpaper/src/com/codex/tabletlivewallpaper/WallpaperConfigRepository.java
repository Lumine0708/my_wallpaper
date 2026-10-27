package com.codex.tabletlivewallpaper;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The only persisted configuration entry point. All public methods are serialized. */
final class WallpaperConfigRepository {
    interface Listener { void onConfigurationChanged(WallpaperConfiguration configuration); }
    interface ProfileTransform { WallpaperProfile apply(WallpaperProfile current); }
    static abstract class LoadResult {
        private LoadResult() { }
        static final class LegacyFallback extends LoadResult { }
        static final class ValidV1 extends LoadResult { final WallpaperConfiguration configuration; ValidV1(WallpaperConfiguration c) { configuration = c; } }
        static final class UnsupportedVersion extends LoadResult { final int version; UnsupportedVersion(int v) { version = v; } }
        static final class Corrupt extends LoadResult { final String error; Corrupt(String e) { error = e; } }
    }
    static abstract class ConfigBaseToken {
        private ConfigBaseToken() { }
        static final class LegacyAbsentToken extends ConfigBaseToken { }
        static final class RevisionToken extends ConfigBaseToken { final long revision; RevisionToken(long r) { revision = r; } }
    }
    static final class Snapshot {
        final LoadResult result; final ConfigBaseToken token;
        Snapshot(LoadResult result, ConfigBaseToken token) { this.result = result; this.token = token; }
    }
    static final class ConflictException extends Exception { ConflictException() { super("configuration changed"); } }

    private static final String FILE_NAME = "wallpaper-configuration-v1.json";
    /* All repository facades for one private configuration file share one mutex and listener set.
       Activities and WallpaperService are created independently, so instance synchronization alone
       cannot provide the revision CAS or process-local update notification promised by v1. */
    private static final Object STORES_LOCK = new Object();
    private static final Map<String, Store> STORES = new HashMap<>();
    private final Store store;

    private static final class Store {
        final File baseFile;
        final File backupFile;
        final AtomicFile file;
        final List<Listener> listeners = new ArrayList<>();
        Store(File baseFile) {
            this.baseFile = baseFile;
            /* AtomicFile uses this sibling for an interrupted replacement.  Keep the
               presence test explicit: openRead() is responsible for restoring it. */
            backupFile = new File(baseFile.getPath() + ".bak");
            file = new AtomicFile(baseFile);
        }
    }

    WallpaperConfigRepository(Context context) {
        File baseFile = new File(context.getFilesDir(), FILE_NAME);
        String key = baseFile.getAbsolutePath();
        synchronized (STORES_LOCK) {
            Store existing = STORES.get(key);
            if (existing == null) {
                existing = new Store(baseFile);
                STORES.put(key, existing);
            }
            store = existing;
        }
    }
    Snapshot snapshot() {
        synchronized (store) {
        LoadResult result = loadLocked();
        ConfigBaseToken token = result instanceof LoadResult.ValidV1
                ? new ConfigBaseToken.RevisionToken(((LoadResult.ValidV1) result).configuration.revision)
                : result instanceof LoadResult.LegacyFallback ? new ConfigBaseToken.LegacyAbsentToken() : null;
        return new Snapshot(result, token);
        }
    }
    LoadResult load() {
        synchronized (store) { return loadLocked(); }
    }
    private LoadResult loadLocked() {
        /* A missing base file is only legacy when there is no AtomicFile backup.  Otherwise
           openRead() can restore the last complete revision and must get that opportunity. */
        if (!store.baseFile.exists() && !store.backupFile.exists()) {
            return new LoadResult.LegacyFallback();
        }
        try {
            byte[] bytes = readFully();
            if (bytes.length > ConfigValidator.MAX_JSON_BYTES) return new LoadResult.Corrupt("configuration exceeds size limit");
            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            int version = json.optInt("schemaVersion", -1);
            if (version > WallpaperConfiguration.SCHEMA_VERSION) return new LoadResult.UnsupportedVersion(version);
            if (version != WallpaperConfiguration.SCHEMA_VERSION) return new LoadResult.Corrupt("unsupported schema");
            WallpaperConfiguration configuration = parse(json);
            ConfigValidator.validate(configuration);
            return new LoadResult.ValidV1(configuration);
        } catch (Exception exception) {
            return new LoadResult.Corrupt("configuration cannot be read");
        }
    }
    WallpaperConfiguration updateProfile(ProfileOrientation orientation, ConfigBaseToken base,
                                         ProfileTransform transform)
            throws ConflictException, IOException {
        synchronized (UriGrantRegistry.TRANSACTION_LOCK) {
            return updateProfileLocked(orientation, base, transform);
        }
    }
    /**
     * Commits a profile and its draft lease as one serialized operation.  The JSON document is
     * durable before the ledger is reconciled, so an interrupted save leaves the draft lease in
     * place for retry rather than releasing a URI that the next committed snapshot references.
     */
    WallpaperConfiguration updateProfileAndCommitLease(ProfileOrientation orientation,
                                                       ConfigBaseToken base,
                                                       ProfileTransform transform,
                                                       UriGrantRegistry grants,
                                                       String draftId)
            throws ConflictException, IOException {
        synchronized (UriGrantRegistry.TRANSACTION_LOCK) {
            WallpaperConfiguration updated = updateProfileLocked(orientation, base, transform);
            /* The configuration is already durable.  A failed ledger cleanup leaves the old
               draft lease on disk and is therefore safe to reconcile on the next load; reporting
               the save as failed here would strand the editor on a stale revision token. */
            grants.commit(draftId, updated);
            return updated;
        }
    }
    private WallpaperConfiguration updateProfileLocked(ProfileOrientation orientation,
                                                       ConfigBaseToken base,
                                                       ProfileTransform transform)
            throws ConflictException, IOException {
        synchronized (store) {
        LoadResult current = loadLocked();
        WallpaperConfiguration baseConfiguration;
        long nextRevision;
        if (base instanceof ConfigBaseToken.LegacyAbsentToken && current instanceof LoadResult.LegacyFallback) {
            baseConfiguration = WallpaperConfiguration.empty(0); nextRevision = 1;
        } else if (base instanceof ConfigBaseToken.RevisionToken && current instanceof LoadResult.ValidV1
                && ((ConfigBaseToken.RevisionToken) base).revision
                == ((LoadResult.ValidV1) current).configuration.revision) {
            baseConfiguration = ((LoadResult.ValidV1) current).configuration;
            nextRevision = baseConfiguration.revision + 1;
        } else {
            throw new ConflictException();
        }
        WallpaperConfiguration updated = baseConfiguration.withProfile(orientation,
                transform.apply(baseConfiguration.profile(orientation)), nextRevision);
        ConfigValidator.validate(updated);
        byte[] document;
        try { document = toJson(updated).toString().getBytes(StandardCharsets.UTF_8); }
        catch (JSONException exception) { throw new IOException("configuration cannot be encoded", exception); }
        if (document.length > ConfigValidator.MAX_JSON_BYTES) throw new IOException("configuration exceeds size limit");
        write(document);
        publishLocked(updated);
        return updated;
        }
    }
    WallpaperConfiguration resetCorrupt() throws IOException {
        synchronized (store) {
        LoadResult existing = loadLocked();
        if (!(existing instanceof LoadResult.Corrupt)) throw new IOException("reset only allowed for corrupt configuration");
        WallpaperConfiguration clean = WallpaperConfiguration.empty(1);
        try { write(toJson(clean).toString().getBytes(StandardCharsets.UTF_8)); }
        catch (JSONException exception) { throw new IOException("configuration cannot be encoded", exception); }
        publishLocked(clean);
        return clean;
        }
    }
    /** Validates the exact document that an editor intends to save before it takes a URI lease. */
    static void preflightCandidate(WallpaperConfiguration candidate) throws IOException {
        ConfigValidator.validate(candidate);
        try {
            if (toJson(candidate).toString().getBytes(StandardCharsets.UTF_8).length > ConfigValidator.MAX_JSON_BYTES) {
                throw new IOException("configuration exceeds size limit");
            }
        } catch (JSONException failure) {
            throw new IOException("configuration cannot be encoded", failure);
        }
    }
    void addListener(Listener listener) { synchronized (store) { store.listeners.add(listener); } }
    void removeListener(Listener listener) { synchronized (store) { store.listeners.remove(listener); } }

    private void publishLocked(WallpaperConfiguration configuration) {
        for (Listener listener : new ArrayList<>(store.listeners)) listener.onConfigurationChanged(configuration);
    }

    private byte[] readFully() throws IOException {
        try (FileInputStream input = store.file.openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > ConfigValidator.MAX_JSON_BYTES) throw new IOException("too large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
    private void write(byte[] bytes) throws IOException {
        FileOutputStream output = null;
        try {
            output = store.file.startWrite(); output.write(bytes); output.flush(); output.getFD().sync(); store.file.finishWrite(output);
        } catch (IOException failure) {
            if (output != null) store.file.failWrite(output); throw failure;
        }
    }
    private static WallpaperConfiguration parse(JSONObject json) throws JSONException {
        return new WallpaperConfiguration(json.getInt("schemaVersion"), json.getLong("revision"),
                parseProfile(json.getJSONObject("portraitProfile")), parseProfile(json.getJSONObject("landscapeProfile")));
    }
    private static WallpaperProfile parseProfile(JSONObject json) throws JSONException {
        JSONArray images = json.getJSONArray("images"); List<WallpaperImage> result = new ArrayList<>();
        for (int i = 0; i < images.length(); i++) { JSONObject image = images.getJSONObject(i); result.add(new WallpaperImage(image.getString("id"), image.getString("sourceUri"), image.getString("displayName"))); }
        JSONObject rotation = json.getJSONObject("rotation");
        return new WallpaperProfile(result, new RotationSettings(rotation.getLong("intervalMillis"), OrderMode.valueOf(rotation.getString("orderMode"))));
    }
    private static JSONObject toJson(WallpaperConfiguration c) throws JSONException {
        JSONObject result = new JSONObject(); result.put("schemaVersion", c.schemaVersion); result.put("revision", c.revision);
        result.put("portraitProfile", profileJson(c.portraitProfile)); result.put("landscapeProfile", profileJson(c.landscapeProfile)); return result;
    }
    private static JSONObject profileJson(WallpaperProfile profile) throws JSONException {
        JSONObject result = new JSONObject(); JSONArray images = new JSONArray();
        for (WallpaperImage image : profile.images) { JSONObject item = new JSONObject(); item.put("id", image.id); item.put("sourceUri", image.sourceUri); item.put("displayName", image.displayName); images.put(item); }
        JSONObject rotation = new JSONObject(); rotation.put("intervalMillis", profile.rotation.intervalMillis); rotation.put("orderMode", profile.rotation.orderMode.name()); result.put("images", images); result.put("rotation", rotation); return result;
    }
}
