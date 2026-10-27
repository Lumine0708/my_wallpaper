package com.codex.tabletlivewallpaper;

import android.graphics.Bitmap;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.Choreographer;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.WindowManager;
import android.util.DisplayMetrics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Lifecycle adapter; each Engine owns one controller and no state is shared between Engines. */
public final class FitWallpaperService extends WallpaperService {
    private static final int MAX_BLANK_REDRAW_ATTEMPTS = 30;
    private WallpaperConfigRepository repository;
    private UriGrantRegistry grants;
    private final WallpaperDiagnosticsStore diagnostics = WallpaperDiagnosticsStore.get();
    private final List<FitEngine> engines = new ArrayList<>();
    @Override public void onCreate() {
        super.onCreate();
        repository = new WallpaperConfigRepository(this);
        /* A wallpaper-only process must recover the v1-owned grant ledger too; the settings
           Activity is not guaranteed to have been started in this process. */
        grants = new UriGrantRegistry(this);
    }
    @Override public Engine onCreateEngine() { FitEngine engine = new FitEngine(); synchronized (engines) { engines.add(engine); } return engine; }
    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        synchronized (engines) { for (FitEngine engine : new ArrayList<>(engines)) engine.resampleOrientation(); }
    }

    private final class FitEngine extends Engine implements WallpaperConfigRepository.Listener {
        private final WallpaperPlaybackController controller = new WallpaperPlaybackController();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private WallpaperConfiguration configuration;
        private ProfileOrientation orientation;
        private Bitmap stableFrame;
        private String stableId;
        private Bitmap incomingFrame;
        private WallpaperPlaybackController.Candidate incomingCandidate;
        private boolean visible, surface, destroyed, needsPrepare, needsBlankFrame;
        private int blankRedrawAttempts;
        private int width, height;
        private long transitionStart;
        private final Runnable tick = new Runnable() { @Override public void run() { advance(); } };
        private final DecodeDeliveryDispatcher decodeDeliveries = new DecodeDeliveryDispatcher(handler,
                tick, new BitmapReleaser() { @Override public void release(Bitmap bitmap) {
                    recycle(bitmap);
                }}, new DeliveryEligibility() { @Override public boolean isCurrent() {
                    return !destroyed && surface;
                }});
        private final Choreographer.FrameCallback animator = new Choreographer.FrameCallback() { @Override public void doFrame(long ignored) { draw(); if (incomingFrame != null) Choreographer.getInstance().postFrameCallback(this); else requestBlankRedraw(); } };

        @Override public void onCreate(SurfaceHolder holder) { super.onCreate(holder); repository.addListener(this); reload(); }
        @Override public void onDestroy() { destroyed = true; surface = false; decodeDeliveries.destroy(); clearInvalidatedState(true); Choreographer.getInstance().removeFrameCallback(animator); repository.removeListener(this); synchronized (engines) { engines.remove(this); } recycle(stableFrame); stableFrame = null; stableId = null; super.onDestroy(); }
        @Override public void onVisibilityChanged(boolean value) { visible = value; if (visible) { reload(); activate(); } else { controller.deactivate(SystemClock.elapsedRealtime()); invalidateGeneration(false); } }
        @Override public void onSurfaceCreated(SurfaceHolder holder) { surface = true; invalidateGeneration(false); activate(); }
        @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) { width = w; height = h; invalidateGeneration(false); activate(); }
        @Override public void onSurfaceDestroyed(SurfaceHolder holder) { surface = false; invalidateGeneration(true); recycle(stableFrame); stableFrame = null; stableId = null; super.onSurfaceDestroyed(holder); }
        @Override public void onConfigurationChanged(WallpaperConfiguration value) { configuration = value; controller.merge(value, SystemClock.elapsedRealtime()); invalidateGeneration(false); activate(); }

        private void reload() {
            WallpaperConfigRepository.Snapshot snapshot = repository.snapshot();
            if (snapshot.result instanceof WallpaperConfigRepository.LoadResult.ValidV1) {
                configuration = ((WallpaperConfigRepository.LoadResult.ValidV1) snapshot.result).configuration;
                grants.setCommittedConfiguration(configuration);
            } else if (snapshot.result instanceof WallpaperConfigRepository.LoadResult.LegacyFallback) {
                /* Bundled images remain a read-only migration fallback only while no v1 file
                   exists.  They do not enter the persisted schema or URI grant ledger. */
                configuration = legacyConfiguration();
                grants.setLegacyAbsentConfiguration();
            } else {
                configuration = null;
                grants.setCommittedConfiguration(null);
            }
            if (configuration != null) controller.merge(configuration, SystemClock.elapsedRealtime());
        }
        private WallpaperConfiguration legacyConfiguration() {
            List<WallpaperImage> portrait = new ArrayList<>();
            for (String name : WallpaperConfig.BUNDLED_PORTRAIT_ROTATION_IMAGE_NAMES) {
                portrait.add(new WallpaperImage("legacy-" + name, "asset://" + name, name));
            }
            WallpaperProfile portraitProfile = new WallpaperProfile(portrait,
                    new RotationSettings(WallpaperConfig.PORTRAIT_ROTATION_INTERVAL_MS,
                            OrderMode.SEQUENTIAL));
            WallpaperProfile landscapeProfile = new WallpaperProfile(java.util.Collections.singletonList(
                    new WallpaperImage("legacy-" + WallpaperConfig.BUNDLED_LANDSCAPE_IMAGE_NAME,
                            "asset://" + WallpaperConfig.BUNDLED_LANDSCAPE_IMAGE_NAME,
                            WallpaperConfig.BUNDLED_LANDSCAPE_IMAGE_NAME)),
                    new RotationSettings(RotationSettings.DEFAULT_INTERVAL_MILLIS,
                            OrderMode.SEQUENTIAL));
            return new WallpaperConfiguration(WallpaperConfiguration.SCHEMA_VERSION, 0L,
                    portraitProfile, landscapeProfile);
        }
        private void activate() {
            if (!visible || !surface || configuration == null || width <= 0 || height <= 0) return;
            ProfileOrientation next = sampledOrientation();
            /* Near-square fallback bounds have no reliable direction.  Keep the last stable
               frame rather than choosing a profile from the wallpaper surface dimensions. */
            if (next == null) return;
            boolean changed = orientation != next;
            if (changed) { controller.deactivate(SystemClock.elapsedRealtime()); orientation = next; invalidateGeneration(false); }
            controller.activate(orientation, SystemClock.elapsedRealtime());
            if (changed || needsPrepare || stableFrame == null || !equals(stableId, controller.current(orientation))) {
                needsPrepare = false;
                /* An empty active profile is an explicit state, not a request to keep the
                   previous orientation/configuration frame alive. */
                if (configuration.profile(orientation).images.isEmpty()) {
                    recycle(stableFrame);
                    stableFrame = null;
                    stableId = null;
                    needsBlankFrame = true;
                    blankRedrawAttempts = 0;
                    requestBlankRedraw();
                    draw();
                    schedule();
                    return;
                }
                prepareCurrentOrNext(false);
            }
            schedule();
        }
        private void resampleOrientation() { invalidateGeneration(false); needsPrepare = true; activate(); }
        private ProfileOrientation sampledOrientation() {
            int signal = getResources().getConfiguration().orientation;
            if (signal == Configuration.ORIENTATION_LANDSCAPE) return ProfileOrientation.LANDSCAPE;
            if (signal == Configuration.ORIENTATION_PORTRAIT) return ProfileOrientation.PORTRAIT;
            if (orientation != null) return orientation;
            int boundsWidth = width, boundsHeight = height;
            WindowManager manager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (manager != null) {
                Display display = manager.getDefaultDisplay();
                /* v1 only considers the default display.  A wallpaper surface can be wider than
                   the visible display for launcher scrolling, so it is not an orientation signal. */
                if (display != null && display.getDisplayId() == Display.DEFAULT_DISPLAY) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        android.graphics.Rect bounds = manager.getCurrentWindowMetrics().getBounds();
                        boundsWidth = bounds.width();
                        boundsHeight = bounds.height();
                    } else {
                        DisplayMetrics metrics = new DisplayMetrics();
                        display.getRealMetrics(metrics);
                        boundsWidth = metrics.widthPixels;
                        boundsHeight = metrics.heightPixels;
                    }
                }
            }
            if (boundsWidth >= boundsHeight * 1.10f) return ProfileOrientation.LANDSCAPE;
            if (boundsHeight >= boundsWidth * 1.10f) return ProfileOrientation.PORTRAIT;
            return null;
        }

        /** Invalidates all delayed work for an old config/direction/surface generation. */
        private void invalidateGeneration(boolean surfaceGone) {
            decodeDeliveries.invalidateGeneration();
            clearInvalidatedState(surfaceGone);
        }
        private void clearInvalidatedState(boolean surfaceGone) {
            Choreographer.getInstance().removeFrameCallback(animator);
            /* The controller's candidate is staged until its transition completes.  Cancelling
               only the renderer would leave that ID in-flight forever and block the next turn. */
            controller.abortPendingTransactions();
            incomingCandidate = null;
            recycle(incomingFrame);
            incomingFrame = null;
            needsBlankFrame = false;
            blankRedrawAttempts = 0;
            if (!surfaceGone) needsPrepare = true;
            if (surfaceGone) controller.deactivate(SystemClock.elapsedRealtime());
        }
        /**
         * Starts one bounded candidate preparation, returning false when every currently eligible
         * item has already been exhausted.  A decode failure marks its item unavailable before
         * this method is entered again, so a single rotation can attempt each configured item at
         * most once rather than creating a retry loop.
         */
        private boolean prepareCurrentOrNext(boolean advance) {
            WallpaperProfile profile = configuration.profile(orientation); String id = advance ? null : controller.current(orientation);
            final WallpaperPlaybackController.Candidate candidate = id == null
                    ? controller.nextCandidate(orientation, profile, SystemClock.elapsedRealtime())
                    : controller.prepareCurrent(orientation, id);
            if (candidate == null) { draw(); return false; }
            final WallpaperImage image = image(profile, candidate.imageId); if (image == null) { draw(); return false; }
            final long generation = decodeDeliveries.generation(); final String target = image.id;
            DecodeCoordinator.Callback callback = new DecodeCoordinator.Callback() { @Override public void complete(final Bitmap bitmap, final WallpaperDiagnosticsStore.Failure failure) { decodeDeliveries.post(generation, bitmap, new BitmapDelivery() { @Override public boolean deliver(Bitmap frame) { if (failure != null) { diagnostics.report(configuration.revision, target, failure, diagnosticSource()); /* Try another currently eligible image immediately.  Backoff removes the failed
                   item, which bounds this chain to the profile's image count. */ controller.preparationFailed(candidate, SystemClock.elapsedRealtime()); if (!prepareCurrentOrNext(true)) schedule(); return true; } incomingCandidate = candidate; recycle(incomingFrame); incomingFrame = frame; transitionStart = SystemClock.elapsedRealtime(); Choreographer.getInstance().postFrameCallback(animator); draw(); return true; }}); }};
            if (image.sourceUri.startsWith("asset://")) {
                DecodeCoordinator.get().decodeAsset(getAssets(), image.sourceUri.substring("asset://".length()),
                        width, height, callback);
            } else {
                DecodeCoordinator.get().decode(FitWallpaperService.this, Uri.parse(image.sourceUri), width, height, callback);
            }
            return true;
        }
        private void advance() {
            if (!visible || configuration == null || orientation == null) return;
            long now = SystemClock.elapsedRealtime();
            WallpaperProfile profile = configuration.profile(orientation);
            if (controller.nextRotationDeadline(orientation, profile, now) <= now) {
                prepareCurrentOrNext(true);
                return;
            }
            String retry = controller.nextAvailabilityRetry(orientation, profile, now);
            if (retry != null) probeAvailability(profile, retry);
            else schedule();
        }
        private void probeAvailability(WallpaperProfile profile, String id) {
            WallpaperImage image = image(profile, id);
            if (image == null) { schedule(); return; }
            final long generation = decodeDeliveries.generation();
            final String target = image.id;
            DecodeCoordinator.get().probe(FitWallpaperService.this, Uri.parse(image.sourceUri), new DecodeCoordinator.ProbeCallback() {
                @Override public void complete(final WallpaperDiagnosticsStore.Failure failure) {
                    handler.post(new Runnable() { @Override public void run() {
                        if (generation != decodeDeliveries.generation() || destroyed || !surface) return;
                        long now = SystemClock.elapsedRealtime();
                        if (failure == null) {
                            controller.availabilityRestored(orientation, target, now);
                        } else {
                            diagnostics.report(configuration.revision, target, failure,
                                    diagnosticSource());
                            controller.availabilityProbeFailed(orientation, target, now);
                        }
                        schedule();
                    }});
                }
            });
        }
        private void schedule() { handler.removeCallbacks(tick); if (configuration == null || orientation == null) return; long now = SystemClock.elapsedRealtime(); WallpaperProfile profile = configuration.profile(orientation); long deadline = controller.nextRotationDeadline(orientation, profile, now); long retry = controller.nextAvailabilityRetryDeadline(orientation); deadline = Math.min(deadline, retry); if (deadline != Long.MAX_VALUE) handler.postDelayed(tick, Math.max(0L, deadline - now)); }
        private void draw() {
            long elapsed = SystemClock.elapsedRealtime() - transitionStart;
            boolean fullIncomingFrame = incomingFrame != null
                    && (stableFrame == null || elapsed >= 680L);
            Canvas canvas = null;
            boolean rendered = false;
            boolean posted = false;
            try {
                canvas = getSurfaceHolder().lockCanvas();
                if (canvas == null) return;
                canvas.drawColor(Color.rgb(244,238,233));
                if (stableFrame != null) drawBitmap(canvas, stableFrame, 255);
                if (incomingFrame != null) {
                    float progress = stableFrame == null ? 1f : Math.min(1f, elapsed / 680f);
                    drawBitmap(canvas, incomingFrame, Math.round(255 * progress));
                }
                rendered = true;
            } catch (RuntimeException ignored) { }
            finally {
                if (canvas != null) {
                    try {
                        getSurfaceHolder().unlockCanvasAndPost(canvas);
                        posted = true;
                    } catch (RuntimeException ignored) { }
                }
            }
            /* Promote the candidate only after its full-alpha canvas was actually posted.  On a
               surface race the animator keeps the incoming transaction alive for a later frame. */
            if (rendered && posted) {
                needsBlankFrame = false;
                if (fullIncomingFrame) finishTransition();
            }
        }
        private void requestBlankRedraw() {
            if (needsBlankFrame && blankRedrawAttempts < MAX_BLANK_REDRAW_ATTEMPTS) {
                blankRedrawAttempts++;
                Choreographer.getInstance().postFrameCallback(animator);
            }
        }
        private WallpaperDiagnosticsStore.Source diagnosticSource() {
            return isPreview() ? WallpaperDiagnosticsStore.Source.PREVIEW_ENGINE
                    : WallpaperDiagnosticsStore.Source.ACTIVE_ENGINE;
        }
        private void finishTransition() { if (incomingFrame == null || incomingCandidate == null) return; recycle(stableFrame); stableFrame = incomingFrame; stableId = incomingCandidate.imageId; incomingFrame = null; controller.preparedAndDisplayed(incomingCandidate, SystemClock.elapsedRealtime()); incomingCandidate = null; /* A renderer result is healthy only after this complete frame was posted. */ diagnostics.clear(configuration.revision, stableId, diagnosticSource()); schedule(); }
        private void drawBitmap(Canvas canvas, Bitmap bitmap, int alpha) { float scale = Math.max(canvas.getWidth() / (float) bitmap.getWidth(), canvas.getHeight() / (float) bitmap.getHeight()); float w = bitmap.getWidth() * scale, h = bitmap.getHeight() * scale; paint.setAlpha(alpha); canvas.drawBitmap(bitmap, null, new RectF((canvas.getWidth()-w)/2f,(canvas.getHeight()-h)/2f,(canvas.getWidth()+w)/2f,(canvas.getHeight()+h)/2f), paint); paint.setAlpha(255); }
        private WallpaperImage image(WallpaperProfile profile, String id) { if (id != null) for (WallpaperImage image : profile.images) if (id.equals(image.id)) return image; return null; }
        private boolean equals(String first, String second) { return first == null ? second == null : first.equals(second); }
        private void recycle(Bitmap bitmap) {
            releaseDecodedFrame(bitmap);
        }
    }

    interface BitmapReleaser { void release(Bitmap bitmap); }
    interface DeliveryEligibility { boolean isCurrent(); }
    interface BitmapDelivery { boolean deliver(Bitmap bitmap); }

    /** Keeps queued decode-result ownership alive until it can be adopted or released. */
    static final class DecodeDeliveryDispatcher {
        private final Handler handler;
        private final Runnable scheduler;
        private final BitmapReleaser releaser;
        private final DeliveryEligibility eligibility;
        private final AtomicLong generation = new AtomicLong();
        private final AtomicBoolean terminal = new AtomicBoolean();

        DecodeDeliveryDispatcher(Handler handler, Runnable scheduler, BitmapReleaser releaser,
                                 DeliveryEligibility eligibility) {
            this.handler = handler;
            this.scheduler = scheduler;
            this.releaser = releaser;
            this.eligibility = eligibility;
        }

        long generation() { return generation.get(); }

        void invalidateGeneration() {
            generation.incrementAndGet();
            /* Delayed rotation/retry work belongs to the old generation; decode deliveries own
               retained bitmaps and must drain to their stale-release branch instead. */
            handler.removeCallbacks(scheduler);
        }

        void destroy() {
            terminal.set(true);
            invalidateGeneration();
        }

        void post(final long expectedGeneration, final Bitmap bitmap, final BitmapDelivery delivery) {
            final FrameDelivery ownership = new FrameDelivery(bitmap);
            /* A completion which observes terminal state on its decoder thread does not need to
               enter the Handler queue.  Work already accepted before terminal state still drains
               through guardedDelivery so it can release its own retained frame. */
            if (terminal.get()) {
                ownership.releaseOnce();
                return;
            }
            Runnable guardedDelivery = new Runnable() { @Override public void run() {
                if (terminal.get() || expectedGeneration != generation.get() || !eligibility.isCurrent()) {
                    ownership.releaseOnce();
                    return;
                }
                boolean adopted = false;
                try {
                    adopted = delivery.deliver(bitmap);
                } finally {
                    if (!adopted) ownership.releaseOnce();
                }
            }};
            if (!handler.post(guardedDelivery)) ownership.releaseOnce();
        }

        private final class FrameDelivery {
            private final Bitmap bitmap;
            private final AtomicBoolean owned = new AtomicBoolean(true);

            FrameDelivery(Bitmap bitmap) { this.bitmap = bitmap; }

            void releaseOnce() {
                if (owned.compareAndSet(true, false)) releaser.release(bitmap);
            }
        }
    }

    static void releaseDecodedFrame(Bitmap bitmap) {
            if (bitmap == null) return;
            DecodeCoordinator.get().release(bitmap);
            if (!bitmap.isRecycled()) bitmap.recycle();
    }
}
