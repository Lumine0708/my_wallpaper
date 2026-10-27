package com.codex.tabletlivewallpaper;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.UriPermission;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Android-boundary regression coverage for editor, grants, decoding, diagnostics and service. */
@RunWith(AndroidJUnit4.class)
public final class WallpaperInstrumentationTest {
    private static final String PROVIDER_AUTHORITY = "com.codex.tabletlivewallpaper.testimages";
    private static final String CONFIG_FILE = "wallpaper-configuration-v1.json";
    private static final String LEDGER_FILE = "wallpaper-uri-grants-v1.json";

    private Instrumentation instrumentation;
    private Context targetContext;
    private Context testContext;

    @Before public void setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        targetContext = instrumentation.getTargetContext();
        testContext = instrumentation.getContext();
        clearPrivateState();
    }

    @After public void tearDown() {
        WallpaperDiagnosticsStore.get().setClockForTests(null);
        clearPrivateState();
    }

    @Test public void emptyV1ConfigurationIsValidOnDevice() {
        WallpaperConfiguration configuration = WallpaperConfiguration.empty(1);
        ConfigValidator.validate(configuration);
        assertEquals(1, configuration.schemaVersion);
    }

    @Test public void portraitAndLandscapeControllersRemainIndependent() {
        WallpaperPlaybackController controller = new WallpaperPlaybackController();
        WallpaperConfiguration configuration = WallpaperConfiguration.empty(1);
        controller.merge(configuration, 10L);
        controller.activate(ProfileOrientation.PORTRAIT, 10L);
        controller.activate(ProfileOrientation.LANDSCAPE, 20L);
        assertNull(controller.current(ProfileOrientation.PORTRAIT));
        assertNull(controller.current(ProfileOrientation.LANDSCAPE));
    }

    @Test public void corruptAndFutureConfigurationDisableEditorControls() throws Exception {
        writePrivateFile(CONFIG_FILE, "not-json");
        ImageSourceActivity corrupt = launchSettings();
        assertFalse(buttonEnabled(corrupt, "添加图片"));
        assertFalse(buttonEnabled(corrupt, "顺序播放"));
        assertFalse(buttonEnabled(corrupt, "设置切换间隔"));
        assertFalse(buttonEnabled(corrupt, "保存"));
        assertEquals(View.VISIBLE, buttonVisibility(corrupt, "重置损坏的配置"));
        finish(corrupt);

        deletePrivateFile(CONFIG_FILE);
        writePrivateFile(CONFIG_FILE, "{\"schemaVersion\":2}");
        ImageSourceActivity future = launchSettings();
        assertFalse(buttonEnabled(future, "添加图片"));
        assertFalse(buttonEnabled(future, "顺序播放"));
        assertFalse(buttonEnabled(future, "设置切换间隔"));
        assertFalse(buttonEnabled(future, "保存"));
        assertEquals(View.GONE, buttonVisibility(future, "重置损坏的配置"));
        finish(future);
    }

    @Test public void reloadToCorruptConfigurationRemovesPreviouslyEditableRows() throws Exception {
        String imageId = "00000000-0000-0000-0000-000000000112";
        writePrivateFile(CONFIG_FILE, configurationWithPortraitImage(
                1L, imageId, providerUri("valid-reload")));
        ImageSourceActivity activity = launchSettings();
        assertNotNull(findButton(activity.getWindow().getDecorView(), "移除"));

        writePrivateFile(CONFIG_FILE, "not-json");
        runOnMain(() -> {
            invoke(activity, "reload", new Class<?>[0]);
            invoke(activity, "render", new Class<?>[0]);
        });

        assertNull(findButton(activity.getWindow().getDecorView(), "移除"));
        assertFalse(buttonEnabled(activity, "添加图片"));
        finish(activity);
    }

    @Test public void corruptResetDiscardsRestoredDraftCacheAndLease() throws Exception {
        writePrivateFile(CONFIG_FILE, "not-json");
        ImageSourceActivity activity = launchSettings();
        Uri uri = providerUri("valid-reset-draft");
        String imageId = "00000000-0000-0000-0000-000000000113";

        @SuppressWarnings("unchecked")
        EnumMap<ProfileOrientation, WallpaperProfile> drafts =
                (EnumMap<ProfileOrientation, WallpaperProfile>) field(activity, "drafts");
        @SuppressWarnings("unchecked")
        EnumMap<ProfileOrientation, Boolean> dirty =
                (EnumMap<ProfileOrientation, Boolean>) field(activity, "dirtyProfiles");
        @SuppressWarnings("unchecked")
        EnumMap<ProfileOrientation, String> draftIds =
                (EnumMap<ProfileOrientation, String>) field(activity, "profileDraftIds");
        UriGrantRegistry grants = (UriGrantRegistry) field(activity, "grants");
        grantPersistableRead(uri);
        assertNotNull(grants.acquireLease(draftIds.get(ProfileOrientation.PORTRAIT), uri));
        drafts.put(ProfileOrientation.PORTRAIT, new WallpaperProfile(
                Collections.singletonList(new WallpaperImage(imageId, uri.toString(), "reset.png")),
                new RotationSettings(10_000L, OrderMode.SEQUENTIAL)));
        dirty.put(ProfileOrientation.PORTRAIT, true);

        ImageSourceActivity restored = recreate(activity);
        assertEquals(Boolean.TRUE, ((EnumMap<?, ?>) field(restored, "dirtyProfiles"))
                .get(ProfileOrientation.PORTRAIT));
        runOnMain(() -> invoke(restored, "performResetCorrupt", new Class<?>[0]));

        String restoredDraftId = (String) field(restored, "draftId");
        assertNull(new ProfileDraftStore(targetContext, restoredDraftId).load());
        assertFalse(hasPersistedRead(uri));
        ImageSourceActivity clean = recreate(restored);
        assertEquals(Boolean.FALSE, ((EnumMap<?, ?>) field(clean, "dirtyProfiles"))
                .get(ProfileOrientation.PORTRAIT));
        @SuppressWarnings("unchecked")
        EnumMap<ProfileOrientation, WallpaperProfile> cleanDrafts =
                (EnumMap<ProfileOrientation, WallpaperProfile>) field(clean, "drafts");
        assertTrue(cleanDrafts.get(ProfileOrientation.PORTRAIT).images.isEmpty());
        finish(clean);
    }

    @Test public void savingPortraitDoesNotClearLandscapeDirtyDraft() throws Exception {
        writePrivateFile(CONFIG_FILE, emptyConfigurationJson(1L));
        ImageSourceActivity activity = launchSettings();
        WallpaperProfile portrait = new WallpaperProfile(Collections.emptyList(),
                new RotationSettings(5_000L, OrderMode.SHUFFLE));
        WallpaperProfile landscape = new WallpaperProfile(Collections.emptyList(),
                new RotationSettings(30_000L, OrderMode.SHUFFLE));

        runOnMain(() -> {
            invoke(activity, "setProfileDraft",
                    new Class<?>[] { ProfileOrientation.class, WallpaperProfile.class },
                    ProfileOrientation.PORTRAIT, portrait);
            invoke(activity, "setProfileDraft",
                    new Class<?>[] { ProfileOrientation.class, WallpaperProfile.class },
                    ProfileOrientation.LANDSCAPE, landscape);
            invoke(activity, "save", new Class<?>[0]);
        });

        @SuppressWarnings("unchecked")
        EnumMap<ProfileOrientation, Boolean> dirty =
                (EnumMap<ProfileOrientation, Boolean>) field(activity, "dirtyProfiles");
        assertEquals(Boolean.FALSE, dirty.get(ProfileOrientation.PORTRAIT));
        assertEquals(Boolean.TRUE, dirty.get(ProfileOrientation.LANDSCAPE));

        ImageSourceActivity recreated = recreate(activity);
        @SuppressWarnings("unchecked")
        EnumMap<ProfileOrientation, Boolean> restored =
                (EnumMap<ProfileOrientation, Boolean>) field(recreated, "dirtyProfiles");
        assertEquals(Boolean.FALSE, restored.get(ProfileOrientation.PORTRAIT));
        assertEquals(Boolean.TRUE, restored.get(ProfileOrientation.LANDSCAPE));
        runOnMain(() -> invoke(recreated, "discardAndFinish", new Class<?>[0]));
    }

    @Test public void backOnDirtyDraftRequiresConfirmationAndKeepsActivityAlive() throws Exception {
        writePrivateFile(CONFIG_FILE, emptyConfigurationJson(1L));
        ImageSourceActivity activity = launchSettings();
        runOnMain(() -> findButton(activity, "顺序播放").performClick());
        assertEquals(Boolean.TRUE, invoke(activity, "hasDirtyDrafts", new Class<?>[0]));
        runOnMain(activity::onBackPressed);
        instrumentation.waitForIdleSync();
        assertFalse(activity.isFinishing());
        runOnMain(() -> invoke(activity, "discardAndFinish", new Class<?>[0]));
    }

    @Test public void destroyedEditorCannotPublishDelayedProbeCompletion() throws Exception {
        String imageId = "00000000-0000-0000-0000-000000000111";
        Uri uri = providerUri("slow-corrupt");
        writePrivateFile(CONFIG_FILE, configurationWithPortraitImage(1L, imageId, uri));
        WallpaperDiagnosticsStore diagnostics = WallpaperDiagnosticsStore.get();
        diagnostics.clear(1L, imageId);
        ImageSourceActivity activity = launchSettings();
        assertEquals(WallpaperDiagnosticsStore.State.CHECKING,
                diagnostics.get(1L, imageId, WallpaperDiagnosticsStore.Source.SETTINGS_PROBE).state);
        finish(activity);
        Thread.sleep(1_600L);
        WallpaperDiagnosticsStore.Entry entry = diagnostics.get(1L, imageId,
                WallpaperDiagnosticsStore.Source.SETTINGS_PROBE);
        assertNotNull(entry);
        assertEquals(WallpaperDiagnosticsStore.State.CHECKING, entry.state);
        diagnostics.clear(1L, imageId);
    }

    @Test public void corruptPixelPayloadIsRejectedByBoundedProbe() throws Exception {
        Uri uri = providerUri("corrupt");
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (android.os.ParcelFileDescriptor descriptor =
                     targetContext.getContentResolver().openFileDescriptor(uri, "r")) {
            assertNotNull(descriptor);
            BitmapFactory.decodeFileDescriptor(descriptor.getFileDescriptor(), null, bounds);
        }
        assertTrue(bounds.outWidth > 0 && bounds.outHeight > 0);

        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<WallpaperDiagnosticsStore.Failure> result = new AtomicReference<>();
        DecodeCoordinator.get().probe(targetContext, uri, failure -> {
            result.set(failure);
            complete.countDown();
        });
        assertTrue(complete.await(5L, TimeUnit.SECONDS));
        assertEquals(WallpaperDiagnosticsStore.Failure.DECODE, result.get());
    }

    @Test public void nonSeekableProviderCanBeProbedAndDecodedFromStableSnapshots() throws Exception {
        File orphan = new File(targetContext.getCacheDir(), "wallpaper-decode-orphan.image");
        try (FileOutputStream output = new FileOutputStream(orphan)) {
            output.write(1);
        }
        DecodeCoordinator.get().resetSnapshotPruningForTests();
        Uri uri = providerUri("pipe-valid");
        CountDownLatch probeComplete = new CountDownLatch(1);
        AtomicReference<WallpaperDiagnosticsStore.Failure> probeFailure = new AtomicReference<>();
        DecodeCoordinator.get().probe(targetContext, uri, failure -> {
            probeFailure.set(failure);
            probeComplete.countDown();
        });
        assertTrue(probeComplete.await(5L, TimeUnit.SECONDS));
        assertNull(probeFailure.get());

        CountDownLatch decodeComplete = new CountDownLatch(1);
        AtomicReference<WallpaperDiagnosticsStore.Failure> decodeFailure = new AtomicReference<>();
        AtomicReference<Bitmap> decoded = new AtomicReference<>();
        DecodeCoordinator.get().decode(targetContext, uri, 64, 64, (bitmap, failure) -> {
            decoded.set(bitmap);
            decodeFailure.set(failure);
            decodeComplete.countDown();
        });
        assertTrue(decodeComplete.await(5L, TimeUnit.SECONDS));
        assertNull(decodeFailure.get());
        assertNotNull(decoded.get());
        DecodeCoordinator.get().release(decoded.get());
        decoded.get().recycle();
        assertFalse(orphan.exists());
        File[] cached = targetContext.getCacheDir().listFiles();
        if (cached != null) for (File file : cached) {
            assertFalse(file.getName(), file.getName().startsWith("wallpaper-decode-"));
        }
    }

    @Test public void invalidatedQueuedDecodeDeliveryReleasesRetainedFrame() throws Exception {
        DecodeCoordinator coordinator = DecodeCoordinator.get();
        Handler mainHandler = new Handler(Looper.getMainLooper());
        CountDownLatch mainLooperBlocked = new CountDownLatch(1);
        CountDownLatch unblockMainLooper = new CountDownLatch(1);
        CountDownLatch decodeQueued = new CountDownLatch(1);
        CountDownLatch staleCleanup = new CountDownLatch(1);
        CountDownLatch deliveryCommitted = new CountDownLatch(1);
        CountDownLatch schedulerRan = new CountDownLatch(1);
        AtomicReference<Bitmap> ownedBitmap = new AtomicReference<>();
        AtomicReference<WallpaperDiagnosticsStore.Failure> decodeFailure = new AtomicReference<>();
        AtomicReference<Throwable> callbackError = new AtomicReference<>();
        AtomicReference<Throwable> cleanupError = new AtomicReference<>();
        AtomicBoolean closing = new AtomicBoolean();
        AtomicBoolean decodedFrame = new AtomicBoolean();
        AtomicBoolean retainedBeforeDelivery = new AtomicBoolean();
        Runnable scheduler = schedulerRan::countDown;
        FitWallpaperService.DecodeDeliveryDispatcher dispatcher = dispatcher(mainHandler, scheduler,
                () -> true, cleanupError, staleCleanup);
        try {
            assertTrue(mainHandler.post(() -> {
                mainLooperBlocked.countDown();
                try {
                    unblockMainLooper.await(5L, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                } finally {
                    unblockMainLooper.countDown();
                }
            }));
            assertTrue(mainLooperBlocked.await(5L, TimeUnit.SECONDS));
            assertTrue(mainHandler.post(scheduler));
            queueSuccessfulDecode(dispatcher, coordinator, ownedBitmap, decodeFailure, callbackError,
                    cleanupError, closing, decodedFrame, retainedBeforeDelivery, decodeQueued,
                    staleCleanup, deliveryCommitted);
            assertTrue(decodeQueued.await(5L, TimeUnit.SECONDS));
            assertNull(callbackError.get());
            assertNull(cleanupError.get());
            assertNull(decodeFailure.get());
            assertTrue(decodedFrame.get());
            assertNotNull(ownedBitmap.get());
            assertTrue(retainedBeforeDelivery.get());
            assertTrue(coordinator.retainsForTests(ownedBitmap.get()));
            dispatcher.invalidateGeneration();
            unblockMainLooper.countDown();
            assertTrue(staleCleanup.await(5L, TimeUnit.SECONDS));
            instrumentation.waitForIdleSync();
            assertNull(cleanupError.get());
            assertEquals(1L, deliveryCommitted.getCount());
            assertEquals(1L, schedulerRan.getCount());
            assertReleased(coordinator, ownedBitmap.get());
        } finally {
            closing.set(true);
            dispatcher.invalidateGeneration();
            unblockMainLooper.countDown();
            boolean decodeComplete = awaitConvergence(decodeQueued);
            boolean cleanupComplete = awaitConvergence(staleCleanup);
            instrumentation.waitForIdleSync();
            releaseOwnedBitmap(ownedBitmap);
            assertTrue(decodeComplete);
            assertTrue(cleanupComplete);
        }
    }

    @Test public void rejectedDecodeDeliveryPostReleasesRetainedFrame() throws Exception {
        DecodeCoordinator coordinator = DecodeCoordinator.get();
        HandlerThread rejectedLooper = new HandlerThread("rejected-decode-delivery");
        CountDownLatch staleCleanup = new CountDownLatch(1);
        CountDownLatch decodeQueued = new CountDownLatch(1);
        CountDownLatch deliveryCommitted = new CountDownLatch(1);
        AtomicReference<Bitmap> ownedBitmap = new AtomicReference<>();
        AtomicReference<WallpaperDiagnosticsStore.Failure> decodeFailure = new AtomicReference<>();
        AtomicReference<Throwable> callbackError = new AtomicReference<>();
        AtomicReference<Throwable> cleanupError = new AtomicReference<>();
        AtomicBoolean closing = new AtomicBoolean();
        AtomicBoolean decodedFrame = new AtomicBoolean();
        AtomicBoolean retainedBeforeDelivery = new AtomicBoolean();
        FitWallpaperService.DecodeDeliveryDispatcher dispatcher;
        try {
            rejectedLooper.start();
            Handler rejectedHandler = new Handler(rejectedLooper.getLooper());
            dispatcher = dispatcher(rejectedHandler, () -> { }, () -> true, cleanupError, staleCleanup);
            assertTrue(rejectedLooper.quit());
            queueSuccessfulDecode(dispatcher, coordinator, ownedBitmap, decodeFailure, callbackError,
                    cleanupError, closing, decodedFrame, retainedBeforeDelivery, decodeQueued,
                    staleCleanup, deliveryCommitted);
            assertTrue(decodeQueued.await(5L, TimeUnit.SECONDS));
            assertNull(callbackError.get());
            assertNull(cleanupError.get());
            assertNull(decodeFailure.get());
            assertTrue(decodedFrame.get());
            assertTrue(staleCleanup.await(5L, TimeUnit.SECONDS));
            assertNull(cleanupError.get());
            assertEquals(1L, deliveryCommitted.getCount());
            assertTrue(retainedBeforeDelivery.get());
            assertNotNull(ownedBitmap.get());
            assertReleased(coordinator, ownedBitmap.get());
        } finally {
            closing.set(true);
            boolean decodeComplete = awaitConvergence(decodeQueued);
            boolean cleanupComplete = awaitConvergence(staleCleanup);
            rejectedLooper.quit();
            rejectedLooper.join(5_000L);
            releaseOwnedBitmap(ownedBitmap);
            assertTrue(decodeComplete);
            assertTrue(cleanupComplete);
        }
    }

    @Test public void terminalQueuedDecodeDeliveryReleasesRetainedFrame() throws Exception {
        DecodeCoordinator coordinator = DecodeCoordinator.get();
        Handler mainHandler = new Handler(Looper.getMainLooper());
        CountDownLatch mainLooperBlocked = new CountDownLatch(1);
        CountDownLatch unblockMainLooper = new CountDownLatch(1);
        CountDownLatch decodeQueued = new CountDownLatch(1);
        CountDownLatch staleCleanup = new CountDownLatch(1);
        CountDownLatch deliveryCommitted = new CountDownLatch(1);
        AtomicReference<Bitmap> ownedBitmap = new AtomicReference<>();
        AtomicReference<WallpaperDiagnosticsStore.Failure> decodeFailure = new AtomicReference<>();
        AtomicReference<Throwable> callbackError = new AtomicReference<>();
        AtomicReference<Throwable> cleanupError = new AtomicReference<>();
        AtomicBoolean closing = new AtomicBoolean();
        AtomicBoolean decodedFrame = new AtomicBoolean();
        AtomicBoolean retainedBeforeDelivery = new AtomicBoolean();
        FitWallpaperService.DecodeDeliveryDispatcher dispatcher = dispatcher(mainHandler, () -> { },
                () -> true, cleanupError, staleCleanup);
        try {
            assertTrue(mainHandler.post(() -> {
                mainLooperBlocked.countDown();
                try {
                    unblockMainLooper.await(5L, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                } finally {
                    unblockMainLooper.countDown();
                }
            }));
            assertTrue(mainLooperBlocked.await(5L, TimeUnit.SECONDS));
            queueSuccessfulDecode(dispatcher, coordinator, ownedBitmap, decodeFailure, callbackError,
                    cleanupError, closing, decodedFrame, retainedBeforeDelivery, decodeQueued,
                    staleCleanup, deliveryCommitted);
            assertTrue(decodeQueued.await(5L, TimeUnit.SECONDS));
            assertNull(callbackError.get());
            assertNull(cleanupError.get());
            assertNull(decodeFailure.get());
            assertTrue(decodedFrame.get());
            assertNotNull(ownedBitmap.get());
            assertTrue(retainedBeforeDelivery.get());
            dispatcher.destroy();
            unblockMainLooper.countDown();
            assertTrue(staleCleanup.await(5L, TimeUnit.SECONDS));
            instrumentation.waitForIdleSync();
            assertNull(cleanupError.get());
            assertEquals(1L, deliveryCommitted.getCount());
            assertReleased(coordinator, ownedBitmap.get());
        } finally {
            closing.set(true);
            dispatcher.destroy();
            unblockMainLooper.countDown();
            boolean decodeComplete = awaitConvergence(decodeQueued);
            boolean cleanupComplete = awaitConvergence(staleCleanup);
            instrumentation.waitForIdleSync();
            releaseOwnedBitmap(ownedBitmap);
            assertTrue(decodeComplete);
            assertTrue(cleanupComplete);
        }
    }

    @Test public void decodeCompletionAfterDestroyReleasesRetainedFrame() throws Exception {
        DecodeCoordinator coordinator = DecodeCoordinator.get();
        HandlerThread looper = new HandlerThread("terminal-decode-delivery");
        CountDownLatch staleCleanup = new CountDownLatch(1);
        CountDownLatch decodeQueued = new CountDownLatch(1);
        CountDownLatch deliveryCommitted = new CountDownLatch(1);
        AtomicReference<Bitmap> ownedBitmap = new AtomicReference<>();
        AtomicReference<WallpaperDiagnosticsStore.Failure> decodeFailure = new AtomicReference<>();
        AtomicReference<Throwable> callbackError = new AtomicReference<>();
        AtomicReference<Throwable> cleanupError = new AtomicReference<>();
        AtomicBoolean closing = new AtomicBoolean();
        AtomicBoolean decodedFrame = new AtomicBoolean();
        AtomicBoolean retainedBeforeDelivery = new AtomicBoolean();
        try {
            looper.start();
            FitWallpaperService.DecodeDeliveryDispatcher dispatcher = dispatcher(
                    new Handler(looper.getLooper()), () -> { }, () -> true, cleanupError, staleCleanup);
            dispatcher.destroy();
            queueSuccessfulDecode(dispatcher, coordinator, ownedBitmap, decodeFailure, callbackError,
                    cleanupError, closing, decodedFrame, retainedBeforeDelivery, decodeQueued,
                    staleCleanup, deliveryCommitted);
            assertTrue(decodeQueued.await(5L, TimeUnit.SECONDS));
            assertTrue(staleCleanup.await(5L, TimeUnit.SECONDS));
            assertNull(callbackError.get());
            assertNull(cleanupError.get());
            assertNull(decodeFailure.get());
            assertTrue(decodedFrame.get());
            assertTrue(retainedBeforeDelivery.get());
            assertNotNull(ownedBitmap.get());
            assertEquals(1L, deliveryCommitted.getCount());
            assertReleased(coordinator, ownedBitmap.get());
        } finally {
            closing.set(true);
            boolean decodeComplete = awaitConvergence(decodeQueued);
            boolean cleanupComplete = awaitConvergence(staleCleanup);
            looper.quit();
            looper.join(5_000L);
            releaseOwnedBitmap(ownedBitmap);
            assertTrue(decodeComplete);
            assertTrue(cleanupComplete);
        }
    }

    private FitWallpaperService.DecodeDeliveryDispatcher dispatcher(Handler handler, Runnable scheduler,
            FitWallpaperService.DeliveryEligibility eligibility, AtomicReference<Throwable> cleanupError,
            CountDownLatch staleCleanup) {
        return new FitWallpaperService.DecodeDeliveryDispatcher(handler, scheduler,
                bitmap -> releaseForCleanup(bitmap, cleanupError, staleCleanup), eligibility);
    }

    private void queueSuccessfulDecode(FitWallpaperService.DecodeDeliveryDispatcher dispatcher,
            DecodeCoordinator coordinator, AtomicReference<Bitmap> ownedBitmap,
            AtomicReference<WallpaperDiagnosticsStore.Failure> decodeFailure,
            AtomicReference<Throwable> callbackError, AtomicReference<Throwable> cleanupError,
            AtomicBoolean closing, AtomicBoolean decodedFrame, AtomicBoolean retainedBeforeDelivery,
            CountDownLatch decodeQueued, CountDownLatch staleCleanup,
            CountDownLatch deliveryCommitted) {
        final long generation = dispatcher.generation();
        coordinator.decode(targetContext, providerUri("valid-delivery"), 64, 64, (bitmap, failure) -> {
            ownedBitmap.set(bitmap);
            decodeFailure.set(failure);
            try {
                if (failure == null && bitmap != null) {
                    decodedFrame.set(true);
                    retainedBeforeDelivery.set(coordinator.retainsForTests(bitmap));
                    if (closing.get()) releaseForCleanup(bitmap, cleanupError, staleCleanup);
                    else dispatcher.post(generation, bitmap, frame -> {
                            deliveryCommitted.countDown();
                            return true;
                        });
                }
            } catch (Throwable error) {
                callbackError.set(error);
            } finally {
                decodeQueued.countDown();
            }
        });
    }

    private static void releaseOwnedBitmap(AtomicReference<Bitmap> ownedBitmap) {
        FitWallpaperService.releaseDecodedFrame(ownedBitmap.getAndSet(null));
    }

    private static void releaseForCleanup(Bitmap bitmap, AtomicReference<Throwable> cleanupError,
            CountDownLatch staleCleanup) {
        try {
            FitWallpaperService.releaseDecodedFrame(bitmap);
        } catch (Throwable error) {
            cleanupError.compareAndSet(null, error);
        } finally {
            staleCleanup.countDown();
        }
    }

    private static void assertReleased(DecodeCoordinator coordinator, Bitmap bitmap) {
        assertFalse(coordinator.retainsForTests(bitmap));
        assertTrue(bitmap.isRecycled());
    }

    private static boolean awaitConvergence(CountDownLatch latch) {
        try {
            return latch.await(5L, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Test public void staleLeaseTokenCannotReleaseNewerContentGrant() throws Exception {
        Uri uri = providerUri("valid");
        grantPersistableRead(uri);
        UriGrantRegistry registry = new UriGrantRegistry(targetContext);
        registry.setLegacyAbsentConfiguration();
        UriGrantRegistry.Lease older = registry.acquireLease("draft-old", uri);
        UriGrantRegistry.Lease newer = registry.acquireLease("draft-new", uri);
        assertNotNull(older);
        assertNotNull(newer);
        registry.releaseLease(older);

        JSONObject ledger = readPrivateJson(LEDGER_FILE);
        JSONObject leases = ledger.getJSONArray("entries").getJSONObject(0).getJSONObject("leases");
        assertFalse(leases.has(older.token));
        assertTrue(leases.has(newer.token));
        assertTrue(hasPersistedRead(uri));

        registry.cancelDraft("draft-new");
        assertFalse(hasPersistedRead(uri));
    }

    @Test public void unversionedLedgerOwnershipMigratesAsNonReleasable() throws Exception {
        Uri uri = providerUri("valid");
        grantPersistableRead(uri);
        targetContext.getContentResolver().takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertTrue(hasPersistedRead(uri));
        JSONObject legacyEntry = new JSONObject();
        legacyEntry.put("uri", uri.toString());
        legacyEntry.put("owned", true);
        legacyEntry.put("pending", false);
        legacyEntry.put("committed", false);
        legacyEntry.put("leases", new JSONObject());
        JSONObject legacy = new JSONObject();
        legacy.put("entries", new JSONArray().put(legacyEntry));
        writePrivateFile(LEDGER_FILE, legacy.toString());
        UriGrantRegistry.resetProcessStateForTests();

        UriGrantRegistry registry = new UriGrantRegistry(targetContext);
        registry.setLegacyAbsentConfiguration();
        JSONObject migrated = readPrivateJson(LEDGER_FILE);
        assertEquals(1, migrated.getInt("version"));
        JSONArray migratedEntries = migrated.getJSONArray("entries");
        for (int i = 0; i < migratedEntries.length(); i++) {
            JSONObject migratedEntry = migratedEntries.getJSONObject(i);
            if (uri.toString().equals(migratedEntry.getString("uri"))) {
                assertFalse(migratedEntry.getBoolean("owned"));
            }
        }
        /* Dropping an unowned migration row is harmless; claiming/releasing the historical OS
           grant is not.  This is the externally observable provenance boundary. */
        assertTrue(hasPersistedRead(uri));
    }

    @Test public void backupOnlyGrantLedgerIsRecovered() throws Exception {
        Uri uri = providerUri("valid");
        JSONObject entry = new JSONObject();
        entry.put("uri", uri.toString());
        entry.put("owned", false);
        entry.put("pending", false);
        entry.put("pendingWasPersisted", false);
        entry.put("committed", false);
        entry.put("leases", new JSONObject());
        JSONObject document = new JSONObject();
        document.put("version", 1);
        document.put("entries", new JSONArray().put(entry));
        writePrivateFile(LEDGER_FILE + ".bak", document.toString());
        deletePrivateFile(LEDGER_FILE);
        UriGrantRegistry.resetProcessStateForTests();

        new UriGrantRegistry(targetContext);
        assertTrue(new File(targetContext.getFilesDir(), LEDGER_FILE).exists());
        assertEquals(uri.toString(), readPrivateJson(LEDGER_FILE)
                .getJSONArray("entries").getJSONObject(0).getString("uri"));
    }

    @Test public void everyAtomicLedgerPhaseFailsBeforeGrantAcquisition() throws Exception {
        for (String phase : Arrays.asList("START_WRITE", "WRITE", "SYNC", "FINISH_WRITE")) {
            clearLedgerOnly();
            Uri uri = providerUri("valid-" + phase.toLowerCase());
            grantPersistableRead(uri);
            UriGrantRegistry registry = new UriGrantRegistry(targetContext);
            UriGrantRegistry.setPersistFailurePhaseForTests(phase);
            assertNull(registry.acquireLease("draft", uri));
            UriGrantRegistry.setPersistFailureForTests(null, 1);
            assertFalse(hasPersistedRead(uri));
            revokeRead(uri);
        }
    }

    @Test public void interruptedFinalLedgerWriteRecoversOwnedPendingGrant() throws Exception {
        Uri uri = providerUri("valid-final-write");
        grantPersistableRead(uri);
        UriGrantRegistry registry = new UriGrantRegistry(targetContext);
        registry.setLegacyAbsentConfiguration();
        UriGrantRegistry.setPersistFailureForTests("FINISH_WRITE", 2);
        assertNull(registry.acquireLease("draft", uri));
        assertTrue(hasPersistedRead(uri));
        JSONObject pending = readPrivateJson(LEDGER_FILE).getJSONArray("entries").getJSONObject(0);
        assertTrue(pending.getBoolean("pending"));

        UriGrantRegistry.setPersistFailureForTests(null, 1);
        new UriGrantRegistry(targetContext).setLegacyAbsentConfiguration();
        assertFalse(hasPersistedRead(uri));
    }

    @Test public void activePreviewAndSettingsDiagnosticsRemainIndependentOnDevice() {
        WallpaperDiagnosticsStore diagnostics = WallpaperDiagnosticsStore.get();
        final long[] now = {100L};
        diagnostics.setClockForTests(() -> now[0]);
        diagnostics.clear(41L, "image");
        diagnostics.report(41L, "image", WallpaperDiagnosticsStore.Failure.PERMISSION,
                WallpaperDiagnosticsStore.Source.ACTIVE_ENGINE);
        diagnostics.report(41L, "image", WallpaperDiagnosticsStore.Failure.DECODE,
                WallpaperDiagnosticsStore.Source.PREVIEW_ENGINE);
        diagnostics.success(41L, "image", WallpaperDiagnosticsStore.Source.SETTINGS_PROBE);
        diagnostics.clear(41L, "image", WallpaperDiagnosticsStore.Source.PREVIEW_ENGINE);
        assertNotNull(diagnostics.get(41L, "image", WallpaperDiagnosticsStore.Source.ACTIVE_ENGINE));
        assertNull(diagnostics.get(41L, "image", WallpaperDiagnosticsStore.Source.PREVIEW_ENGINE));
        assertEquals(WallpaperDiagnosticsStore.State.SUCCESS,
                diagnostics.get(41L, "image", WallpaperDiagnosticsStore.Source.SETTINGS_PROBE).state);
        diagnostics.clear(41L, "image");
    }

    @Test public void wallpaperServiceBoundaryRemainsHomeOnlyAndConstructible() throws Exception {
        ServiceInfo info = targetContext.getPackageManager().getServiceInfo(
                new ComponentName(targetContext, FitWallpaperService.class), 0);
        assertEquals("android.permission.BIND_WALLPAPER", info.permission);
        Service service = new FitWallpaperService();
        assertNotNull(service);
    }

    private ImageSourceActivity launchSettings() {
        Intent intent = new Intent(targetContext, ImageSourceActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Activity activity = instrumentation.startActivitySync(intent);
        instrumentation.waitForIdleSync();
        return (ImageSourceActivity) activity;
    }

    private void finish(Activity activity) {
        if (activity == null || activity.isDestroyed()) return;
        runOnMain(activity::finish);
        instrumentation.waitForIdleSync();
    }

    private ImageSourceActivity recreate(ImageSourceActivity activity) {
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                ImageSourceActivity.class.getName(), null, false);
        runOnMain(activity::recreate);
        Activity replacement = monitor.waitForActivityWithTimeout(5_000L);
        instrumentation.removeMonitor(monitor);
        assertNotNull("Activity recreation timed out", replacement);
        instrumentation.waitForIdleSync();
        return (ImageSourceActivity) replacement;
    }

    private void clearPrivateState() {
        for (UriPermission permission : targetContext.getContentResolver().getPersistedUriPermissions()) {
            if (PROVIDER_AUTHORITY.equals(permission.getUri().getAuthority())) {
                try {
                    targetContext.getContentResolver().releasePersistableUriPermission(
                            permission.getUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) { }
            }
        }
        deletePrivateFile(CONFIG_FILE);
        deletePrivateFile(CONFIG_FILE + ".bak");
        clearLedgerOnly();
        File[] cached = targetContext.getCacheDir().listFiles();
        if (cached != null) for (File file : cached) {
            if (file.getName().startsWith("wallpaper-draft-")
                    || file.getName().startsWith("wallpaper-decode-")) file.delete();
        }
    }

    private void clearLedgerOnly() {
        UriGrantRegistry.resetProcessStateForTests();
        deletePrivateFile(LEDGER_FILE);
        deletePrivateFile(LEDGER_FILE + ".bak");
    }

    private void grantPersistableRead(Uri uri) {
        testContext.grantUriPermission(targetContext.getPackageName(), uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    }

    private void revokeRead(Uri uri) {
        testContext.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    private boolean hasPersistedRead(Uri uri) {
        for (UriPermission permission : targetContext.getContentResolver().getPersistedUriPermissions()) {
            if (permission.isReadPermission() && uri.equals(permission.getUri())) return true;
        }
        return false;
    }

    private static Uri providerUri(String path) {
        return Uri.parse("content://" + PROVIDER_AUTHORITY + "/" + path);
    }

    private static WallpaperProfile profile(long interval, OrderMode mode) {
        return new WallpaperProfile(Collections.emptyList(), new RotationSettings(interval, mode));
    }

    private void writePrivateFile(String name, String content) throws Exception {
        try (FileOutputStream output = new FileOutputStream(new File(targetContext.getFilesDir(), name))) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private void deletePrivateFile(String name) {
        new File(targetContext.getFilesDir(), name).delete();
    }

    private JSONObject readPrivateJson(String name) throws Exception {
        File file = new File(targetContext.getFilesDir(), name);
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private static String emptyConfigurationJson(long revision) {
        return "{\"schemaVersion\":1,\"revision\":" + revision
                + ",\"portraitProfile\":{" + profileJson()
                + "},\"landscapeProfile\":{" + profileJson() + "}}";
    }

    private static String configurationWithPortraitImage(long revision, String id, Uri uri) {
        String portrait = "\"images\":[{\"id\":\"" + id + "\",\"sourceUri\":\""
                + uri + "\",\"displayName\":\"delayed.png\"}],"
                + "\"rotation\":{\"intervalMillis\":10000,\"orderMode\":\"SEQUENTIAL\"}";
        return "{\"schemaVersion\":1,\"revision\":" + revision
                + ",\"portraitProfile\":{" + portrait
                + "},\"landscapeProfile\":{" + profileJson() + "}}";
    }

    private static String profileJson() {
        return "\"images\":[],\"rotation\":{\"intervalMillis\":10000,"
                + "\"orderMode\":\"SEQUENTIAL\"}";
    }

    private Button findButton(Activity activity, String text) {
        Button result = findButton(activity.getWindow().getDecorView(), text);
        assertNotNull("Missing button: " + text, result);
        return result;
    }

    private Button findButton(View view, String text) {
        if (view instanceof Button && text.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button child = findButton(group.getChildAt(i), text);
                if (child != null) return child;
            }
        }
        return null;
    }

    private boolean buttonEnabled(Activity activity, String text) {
        final boolean[] value = new boolean[1];
        runOnMain(() -> value[0] = findButton(activity, text).isEnabled());
        return value[0];
    }

    private int buttonVisibility(Activity activity, String text) {
        final int[] value = new int[1];
        runOnMain(() -> value[0] = findButton(activity, text).getVisibility());
        return value[0];
    }

    private void runOnMain(Runnable action) {
        instrumentation.runOnMainSync(action);
        instrumentation.waitForIdleSync();
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target, arguments);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
