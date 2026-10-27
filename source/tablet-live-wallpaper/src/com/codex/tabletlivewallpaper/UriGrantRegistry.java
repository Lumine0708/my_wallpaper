package com.codex.tabletlivewallpaper;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.AtomicFile;

import org.json.JSONArray;
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

/** Private, recoverable ledger for grants acquired by this v1 implementation only. */
final class UriGrantRegistry {
    private static final int LEDGER_VERSION = 1;
    private static final long DRAFT_TTL_MILLIS = 24L * 60L * 60L * 1_000L;
    /** Shared with configuration CAS so durable JSON is written before lease reconciliation. */
    static final Object TRANSACTION_LOCK = new Object();
    private static final Object STORES_LOCK = new Object();
    private static final Map<String, Store> STORES = new HashMap<>();
    private static volatile String persistFailurePhaseForTests;
    private static volatile int persistFailureOccurrenceForTests = 1;
    private static int persistFailureHitsForTests;

    private static final class Store {
        final ContentResolver resolver;
        final AtomicFile ledger;
        final File backup;
        final Map<String, Entry> entries = new HashMap<>();
        Set<String> committedUris = Collections.emptySet();
        boolean reconciliationSafe;
        Store(Context context, File file) {
            resolver = context.getContentResolver();
            ledger = new AtomicFile(file);
            backup = new File(file.getPath() + ".bak");
        }
    }

    private final Store store;

    UriGrantRegistry(Context context) {
        File file = new File(context.getFilesDir(), "wallpaper-uri-grants-v1.json");
        synchronized (STORES_LOCK) {
            Store existing = STORES.get(file.getAbsolutePath());
            if (existing == null) {
                existing = new Store(context, file);
                restore(existing);
                recoverPendingAcquisitions(existing);
                STORES.put(file.getAbsolutePath(), existing);
            }
            store = existing;
        }
        synchronized (store) { recoverPendingAcquisitions(store); }
        pruneExpired();
    }

    /** An import attempt owns only this token; stale callbacks cannot release another attempt. */
    static final class Lease {
        final String draftId;
        final String uri;
        final String token;
        Lease(String draftId, String uri, String token) {
            this.draftId = draftId; this.uri = uri; this.token = token;
        }
    }

    Lease acquireLease(String draftId, Uri uri) {
        synchronized (TRANSACTION_LOCK) { synchronized (store) {
            String value = uri.toString();
            Entry entry = store.entries.get(value);
            boolean created = entry == null;
            boolean alreadyPersisted = hasPersistedReadPermission(value);
            if (entry == null) { entry = new Entry(); store.entries.put(value, entry); }
            if (entry.pending) return null;
            boolean previouslyOwned = entry.owned;
            /* The ownership fact is part of the pending transaction.  If this process dies after
               takePersistableUriPermission(), recovery can distinguish a v1 acquisition from a
               historical grant which it must never release. */
            entry.pending = true;
            entry.pendingWasPersisted = alreadyPersisted;
            if (!persist(store)) {
                entry.pending = false; entry.pendingWasPersisted = false;
                if (created && entry.leases.isEmpty() && !entry.committed) store.entries.remove(value);
                return null;
            }
            try {
                store.resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException failure) {
                entry.pending = false;
                entry.pendingWasPersisted = false;
                if (entry.leases.isEmpty() && !entry.committed) store.entries.remove(value);
                persist(store);
                return null;
            }
            entry.pending = false;
            entry.owned = entry.owned || !entry.pendingWasPersisted;
            entry.pendingWasPersisted = false;
            String token = java.util.UUID.randomUUID().toString();
            entry.leases.put(token, new LeaseRecord(draftId, System.currentTimeMillis()));
            if (!persist(store)) {
                /* The durable file still contains the pre-acquisition pending record.  Preserve
                   that exact recovery state in memory and never erase ownership that belonged to
                   an older committed reference or draft lease. */
                entry.leases.remove(token);
                entry.owned = previouslyOwned;
                entry.pending = true;
                entry.pendingWasPersisted = alreadyPersisted;
                return null;
            }
            return new Lease(draftId, value, token);
        }}
    }

    /** Removes a lease obtained for an item that was rejected before entering the draft. */
    void releaseLease(Lease lease) {
        if (lease == null) return;
        synchronized (TRANSACTION_LOCK) { synchronized (store) {
            Entry entry = store.entries.get(lease.uri);
            if (entry != null) {
                LeaseRecord record = entry.leases.get(lease.token);
                if (record != null && lease.draftId.equals(record.draftId)) entry.leases.remove(lease.token);
            }
            reconcileIfSafe();
        }}
    }

    void touchDraft(String draftId) {
        synchronized (TRANSACTION_LOCK) { synchronized (store) {
            long now = System.currentTimeMillis(); boolean changed = false;
            for (Entry entry : store.entries.values()) for (Map.Entry<String, LeaseRecord> lease : entry.leases.entrySet()) {
                if (draftId.equals(lease.getValue().draftId)) {
                    lease.setValue(new LeaseRecord(draftId, now)); changed = true;
                }
            }
            if (changed) persist(store);
        }}
    }

    void cancelDraft(String draftId) {
        synchronized (TRANSACTION_LOCK) { synchronized (store) {
            for (Entry entry : store.entries.values()) removeDraftLeases(entry, draftId);
            reconcileIfSafe();
        }}
    }

    boolean commit(String draftId, WallpaperConfiguration configuration) {
        synchronized (TRANSACTION_LOCK) { synchronized (store) {
            store.committedUris = references(configuration);
            store.reconciliationSafe = true;
            for (Entry entry : store.entries.values()) removeDraftLeases(entry, draftId);
            return reconcile(store.committedUris);
        }}
    }

    /** Trusted v1 snapshot: it is safe to release an unreferenced v1-owned grant. */
    void setCommittedConfiguration(WallpaperConfiguration configuration) {
        synchronized (TRANSACTION_LOCK) { synchronized (store) {
            /* Corrupt and future documents are not a trustworthy empty reference set. */
            if (configuration == null) {
                store.committedUris = Collections.emptySet();
                store.reconciliationSafe = false;
                persist(store);
                return;
            }
            store.committedUris = references(configuration);
            store.reconciliationSafe = true;
            reconcile(store.committedUris);
        }}
    }

    /** Legacy absence is also trusted: no v1 configuration references any URI yet. */
    void setLegacyAbsentConfiguration() {
        synchronized (TRANSACTION_LOCK) { synchronized (store) {
            store.committedUris = Collections.emptySet();
            store.reconciliationSafe = true;
            reconcile(store.committedUris);
        }}
    }

    void pruneExpired() {
        synchronized (TRANSACTION_LOCK) { synchronized (store) {
            long oldest = System.currentTimeMillis() - DRAFT_TTL_MILLIS;
            for (Entry entry : store.entries.values()) {
                List<String> expired = new ArrayList<>();
                for (Map.Entry<String, LeaseRecord> lease : entry.leases.entrySet()) if (lease.getValue().touchedAt < oldest) expired.add(lease.getKey());
                for (String key : expired) entry.leases.remove(key);
            }
            reconcileIfSafe();
        }}
    }

    private void reconcileIfSafe() { if (store.reconciliationSafe) reconcile(store.committedUris); else persist(store); }
    /** Persist ownership/reference changes before releasing an OS grant.  If the final cleanup
        write is interrupted, the retained row is safe to reconcile idempotently on next launch. */
    private boolean reconcile(Set<String> committedUris) {
        List<String> release = new ArrayList<>();
        for (Map.Entry<String, Entry> item : store.entries.entrySet()) {
            Entry entry = item.getValue(); entry.committed = committedUris.contains(item.getKey());
            if (entry.owned && !entry.committed && entry.leases.isEmpty() && !entry.pending) release.add(item.getKey());
        }
        if (!persist(store)) return false;
        for (String value : release) {
            boolean released = false;
            try {
                store.resolver.releasePersistableUriPermission(Uri.parse(value), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                released = true;
            } catch (SecurityException ignored) {
                released = !hasPersistedReadPermission(value);
            }
            if (released) store.entries.remove(value);
        }
        return persist(store);
    }

    private static void restore(Store store) {
        /* AtomicFile.openRead() restores a complete backup when a replacement was interrupted.
           Do not classify a backup-only ledger as absent and overwrite its pending/lease rows. */
        if (!store.ledger.getBaseFile().exists() && !store.backup.exists()) return;
        try (FileInputStream input = store.ledger.openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            JSONObject root = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            /* Unversioned ledgers predate ownership provenance.  Retain their rows solely so
               draft cleanup remains harmless, but never release a historical grant from them. */
            boolean trustedVersion = root.optInt("version", -1) == LEDGER_VERSION;
            JSONArray items = root.getJSONArray("entries");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i); Entry entry = new Entry();
                entry.owned = trustedVersion && item.optBoolean("owned"); entry.pending = trustedVersion && item.optBoolean("pending");
                entry.pendingWasPersisted = trustedVersion && item.optBoolean("pendingWasPersisted"); entry.committed = item.optBoolean("committed");
                JSONObject leases = item.optJSONObject("leases");
                if (leases != null) for (java.util.Iterator<String> it = leases.keys(); it.hasNext();) {
                    String id = it.next(); Object raw = leases.get(id);
                    if (raw instanceof JSONObject) {
                        JSONObject record = (JSONObject) raw;
                        entry.leases.put(id, new LeaseRecord(record.getString("draftId"), record.getLong("touchedAt")));
                    }
                }
                store.entries.put(item.getString("uri"), entry);
            }
        } catch (Exception ignored) { store.entries.clear(); }
    }

    private static boolean recoverPendingAcquisitions(Store store) {
        Set<String> persisted = new HashSet<>();
        for (android.content.UriPermission permission : store.resolver.getPersistedUriPermissions()) {
            if (permission.isReadPermission()) persisted.add(permission.getUri().toString());
        }
        Map<Entry, boolean[]> before = new HashMap<>();
        for (Map.Entry<String, Entry> item : store.entries.entrySet()) {
            Entry entry = item.getValue();
            if (entry.pending) {
                before.put(entry, new boolean[] { entry.owned, entry.pending, entry.pendingWasPersisted });
                /* Claim the permission only when the pending record proves it did not predate
                   this acquisition.  A missing permission simply leaves an unowned ledger row. */
                entry.owned = entry.owned || (!entry.pendingWasPersisted && persisted.contains(item.getKey()));
                entry.pending = false; entry.pendingWasPersisted = false;
            }
        }
        if (before.isEmpty()) return true;
        if (persist(store)) return true;
        for (Map.Entry<Entry, boolean[]> item : before.entrySet()) {
            boolean[] state = item.getValue();
            item.getKey().owned = state[0];
            item.getKey().pending = state[1];
            item.getKey().pendingWasPersisted = state[2];
        }
        return false;
    }

    private boolean hasPersistedReadPermission(String value) {
        for (android.content.UriPermission permission : store.resolver.getPersistedUriPermissions()) {
            if (permission.isReadPermission() && value.equals(permission.getUri().toString())) return true;
        }
        return false;
    }

    private static Set<String> references(WallpaperConfiguration configuration) {
        Set<String> committed = new HashSet<>();
        for (ProfileOrientation orientation : ProfileOrientation.values())
            for (WallpaperImage image : configuration.profile(orientation).images) committed.add(image.sourceUri);
        return committed;
    }

    private static boolean persist(Store store) {
        FileOutputStream output = null;
        try {
            JSONObject root = new JSONObject(); JSONArray items = new JSONArray();
            for (Map.Entry<String, Entry> item : store.entries.entrySet()) {
                Entry entry = item.getValue(); JSONObject record = new JSONObject();
                record.put("uri", item.getKey()); record.put("owned", entry.owned); record.put("pending", entry.pending);
                record.put("pendingWasPersisted", entry.pendingWasPersisted); record.put("committed", entry.committed);
                JSONObject leases = new JSONObject();
                for (Map.Entry<String, LeaseRecord> lease : entry.leases.entrySet()) {
                    JSONObject leaseRecord = new JSONObject(); leaseRecord.put("draftId", lease.getValue().draftId);
                    leaseRecord.put("touchedAt", lease.getValue().touchedAt); leases.put(lease.getKey(), leaseRecord);
                }
                record.put("leases", leases); items.put(record);
            }
            root.put("version", LEDGER_VERSION); root.put("entries", items);
            failPersistForTests("START_WRITE");
            output = store.ledger.startWrite();
            failPersistForTests("WRITE");
            output.write(root.toString().getBytes(StandardCharsets.UTF_8)); output.flush();
            failPersistForTests("SYNC");
            output.getFD().sync();
            failPersistForTests("FINISH_WRITE");
            store.ledger.finishWrite(output); output = null;
            return true;
        } catch (Exception ignored) { if (output != null) store.ledger.failWrite(output); return false; }
    }

    static void setPersistFailurePhaseForTests(String phase) {
        setPersistFailureForTests(phase, 1);
    }
    static void setPersistFailureForTests(String phase, int occurrence) {
        persistFailurePhaseForTests = phase;
        persistFailureOccurrenceForTests = Math.max(1, occurrence);
        persistFailureHitsForTests = 0;
    }
    static void resetProcessStateForTests() {
        synchronized (STORES_LOCK) { STORES.clear(); }
        persistFailurePhaseForTests = null;
        persistFailureOccurrenceForTests = 1;
        persistFailureHitsForTests = 0;
    }
    private static void failPersistForTests(String phase) throws IOException {
        if (phase.equals(persistFailurePhaseForTests)
                && ++persistFailureHitsForTests == persistFailureOccurrenceForTests) {
            throw new IOException("injected " + phase);
        }
    }

    private static void removeDraftLeases(Entry entry, String draftId) {
        List<String> remove = new ArrayList<>();
        for (Map.Entry<String, LeaseRecord> lease : entry.leases.entrySet()) if (draftId.equals(lease.getValue().draftId)) remove.add(lease.getKey());
        for (String token : remove) entry.leases.remove(token);
    }

    private static final class Entry {
        boolean owned; boolean pending; boolean pendingWasPersisted; boolean committed;
        final Map<String, LeaseRecord> leases = new HashMap<>();
    }
    private static final class LeaseRecord {
        final String draftId; final long touchedAt;
        LeaseRecord(String draftId, long touchedAt) { this.draftId = draftId; this.touchedAt = touchedAt; }
    }
}
