package com.codex.tabletlivewallpaper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Per-Engine, serial-call playback state. It never writes configuration. */
final class WallpaperPlaybackController {
    static final long[] BACKOFF_MILLIS = {60_000L, 300_000L, 1_800_000L};

    /**
     * A renderer must return this opaque attempt, rather than only an image ID.  An image can be
     * removed and re-added while an old decode is still completing, so an ID alone is not enough
     * to decide whether that completion may mutate playback state.
     */
    static final class Candidate {
        final ProfileOrientation orientation;
        final String imageId;
        private final long transactionId;

        private Candidate(ProfileOrientation orientation, String imageId, long transactionId) {
            this.orientation = orientation;
            this.imageId = imageId;
            this.transactionId = transactionId;
        }
    }
    private final Map<ProfileOrientation, State> states = new HashMap<>();
    private final Random random;

    WallpaperPlaybackController() { this(new Random(0)); }
    WallpaperPlaybackController(Random random) {
        this.random = random;
        states.put(ProfileOrientation.PORTRAIT, new State());
        states.put(ProfileOrientation.LANDSCAPE, new State());
    }

    synchronized void merge(WallpaperConfiguration configuration, long now) {
        for (ProfileOrientation orientation : ProfileOrientation.values()) {
            State state = states.get(orientation);
            WallpaperProfile profile = configuration.profile(orientation);
            /* A prepared candidate belongs to the old configuration generation.  It has not
               completed a transition, so it must never survive a merge even when its ID still
               exists in the new profile. */
            clearTransaction(state);
            HashSet<String> valid = ids(profile.images);
            boolean currentRemoved = state.currentImageId != null && !valid.contains(state.currentImageId);
            if (currentRemoved) {
                int formerPosition = state.lastOrder.indexOf(state.currentImageId);
                /* A replacement is only committed after its frame completes a transition.  Remember
                   the sequential successor, but never make an undecoded frame current. */
                state.pendingSequentialSuccessor = profile.rotation.orderMode == OrderMode.SEQUENTIAL
                        && !profile.images.isEmpty()
                        ? profile.images.get(Math.floorMod(formerPosition, profile.images.size())).id : null;
                state.currentImageId = null;
            }
            state.shuffleBag.retainAll(valid);
            if (state.inFlightImageId != null && !valid.contains(state.inFlightImageId)) {
                clearTransaction(state);
            }
            state.unavailableUntil.keySet().retainAll(valid);
            if (profile.rotation.orderMode != state.orderMode || !sameSet(state.knownIds, valid)) {
                state.shuffleBag.clear();
            }
            state.knownIds = valid;
            state.orderMode = profile.rotation.orderMode;
            boolean intervalChanged = state.intervalMillis != profile.rotation.intervalMillis;
            state.intervalMillis = profile.rotation.intervalMillis;
            if (intervalChanged) {
                if (state.active) state.deadline = now + state.intervalMillis;
                else state.remaining = state.intervalMillis;
            }
            state.lastOrder.clear();
            for (WallpaperImage image : profile.images) state.lastOrder.add(image.id);
        }
    }

    synchronized void activate(ProfileOrientation orientation, long now) {
        for (ProfileOrientation value : ProfileOrientation.values()) {
            State state = states.get(value);
            if (value != orientation && state.active) pause(state, now);
        }
        State state = states.get(orientation);
        if (state.active) return;
        state.active = true;
        state.deadline = now + Math.max(0L, state.remaining == Long.MIN_VALUE
                ? state.intervalMillis : state.remaining);
        state.remaining = Long.MIN_VALUE;
    }

    synchronized void deactivate(long now) {
        for (State state : states.values()) if (state.active) pause(state, now);
    }

    /** Drops every uncommitted candidate after a surface/configuration/lifecycle generation change. */
    synchronized void abortPendingTransactions() {
        for (State state : states.values()) clearTransaction(state);
    }

    synchronized Candidate nextCandidate(ProfileOrientation orientation, WallpaperProfile profile,
                                         long now) {
        State state = states.get(orientation);
        if (state.inFlightImageId != null) return candidate(orientation, state);
        List<WallpaperImage> available = available(profile.images, state, now);
        if (available.isEmpty()) return null;
        if (profile.rotation.orderMode == OrderMode.SEQUENTIAL) {
            if (state.pendingSequentialSuccessor != null) {
                String successor = state.pendingSequentialSuccessor;
                if (contains(available, successor)) return beginTransaction(orientation, state, successor);
                state.pendingSequentialSuccessor = null;
            }
            int current = indexOf(profile.images, state.currentImageId);
            for (int offset = 1; offset <= profile.images.size(); offset++) {
                WallpaperImage candidate = profile.images.get(Math.floorMod(current + offset,
                        profile.images.size()));
                if (isAvailable(state, candidate.id, now)) return beginTransaction(orientation, state, candidate.id);
            }
            return null;
        }
        refillBag(state, available);
        return state.shuffleBag.isEmpty() ? null : beginTransaction(orientation, state, state.shuffleBag.get(0));
    }

    /** Starts a generation-bound reprepare of the already committed frame. */
    synchronized Candidate prepareCurrent(ProfileOrientation orientation, String imageId) {
        State state = states.get(orientation);
        if (state.inFlightImageId != null || !imageId.equals(state.currentImageId)) return null;
        return beginTransaction(orientation, state, imageId);
    }

    synchronized void preparedAndDisplayed(Candidate candidate, long now) {
        if (!matches(candidate)) return;
        State state = states.get(candidate.orientation);
        state.currentImageId = candidate.imageId;
        state.pendingSequentialSuccessor = null;
        if (state.stagedShuffleBag != null) state.shuffleBag = state.stagedShuffleBag;
        clearTransaction(state);
        state.failures.remove(candidate.imageId);
        state.unavailableUntil.remove(candidate.imageId);
        state.deadline = now + state.intervalMillis;
    }

    synchronized void preparationFailed(Candidate candidate, long now) {
        if (!matches(candidate)) return;
        State state = states.get(candidate.orientation);
        markUnavailable(state, candidate.imageId, now);
        clearTransaction(state);
    }

    /** Records a failed availability probe; it is intentionally not a display transaction. */
    synchronized void availabilityProbeFailed(ProfileOrientation orientation, String imageId, long now) {
        markUnavailable(states.get(orientation), imageId, now);
    }

    private static void markUnavailable(State state, String imageId, long now) {
        int failures = state.failures.containsKey(imageId) ? state.failures.get(imageId) + 1 : 1;
        state.failures.put(imageId, failures);
        state.shuffleBag.remove(imageId);
        state.unavailableUntil.put(imageId, now + BACKOFF_MILLIS[Math.min(failures - 1,
                BACKOFF_MILLIS.length - 1)]);
    }

    /** A successful bounded availability probe makes this image eligible again without a catch-up. */
    synchronized void availabilityRestored(ProfileOrientation orientation, String imageId, long now) {
        State state = states.get(orientation);
        state.unavailableUntil.remove(imageId);
        state.failures.remove(imageId);
        if (state.orderMode == OrderMode.SHUFFLE && !imageId.equals(state.currentImageId)
                && !state.shuffleBag.contains(imageId)) {
            state.shuffleBag.add(random.nextInt(state.shuffleBag.size() + 1), imageId);
        }
        /* A recovered image becomes eligible for the next normal rotation.  Do not use a
           delayed availability callback to create a catch-up transition. */
        if (state.active) state.deadline = now + state.intervalMillis;
    }

    synchronized long nextRotationDeadline(ProfileOrientation orientation, WallpaperProfile profile, long now) {
        State state = states.get(orientation);
        return state.active && available(profile.images, state, now).size() >= 2
                ? state.deadline : Long.MAX_VALUE;
    }

    /** Availability probes remain independently scheduled when a profile has fewer than two usable images. */
    synchronized long nextAvailabilityRetryDeadline(ProfileOrientation orientation) {
        long retry = Long.MAX_VALUE;
        for (Long until : states.get(orientation).unavailableUntil.values()) retry = Math.min(retry, until);
        return retry;
    }

    /** Returns one expired backoff entry for a bounded, non-rotating availability probe. */
    synchronized String nextAvailabilityRetry(ProfileOrientation orientation,
                                               WallpaperProfile profile, long now) {
        State state = states.get(orientation);
        for (WallpaperImage image : profile.images) {
            Long until = state.unavailableUntil.get(image.id);
            if (until != null && until <= now) return image.id;
        }
        return null;
    }

    synchronized boolean hasRotationCandidate(ProfileOrientation orientation, WallpaperProfile profile,
                                              long now) {
        return available(profile.images, states.get(orientation), now).size() >= 2;
    }

    synchronized String current(ProfileOrientation orientation) { return states.get(orientation).currentImageId; }

    private void pause(State state, long now) {
        state.remaining = Math.max(0L, state.deadline - now);
        state.active = false;
    }
    private void refillBag(State state, List<WallpaperImage> available) {
        if (!state.shuffleBag.isEmpty()) return;
        for (WallpaperImage image : available) if (!image.id.equals(state.currentImageId)) state.shuffleBag.add(image.id);
        Collections.shuffle(state.shuffleBag, random);
    }
    /** Begins a candidate transaction without mutating the committed bag or current image. */
    private static Candidate beginTransaction(ProfileOrientation orientation, State state, String candidate) {
        state.inFlightImageId = candidate;
        state.inFlightTransactionId++;
        state.stagedShuffleBag = new ArrayList<>(state.shuffleBag);
        state.stagedShuffleBag.remove(candidate);
        return candidate(orientation, state);
    }
    private static Candidate candidate(ProfileOrientation orientation, State state) {
        return new Candidate(orientation, state.inFlightImageId, state.inFlightTransactionId);
    }
    private boolean matches(Candidate candidate) {
        if (candidate == null || states.get(candidate.orientation) == null) return false;
        State state = states.get(candidate.orientation);
        return candidate.transactionId == state.inFlightTransactionId
                && candidate.imageId.equals(state.inFlightImageId);
    }
    private static void clearTransaction(State state) {
        state.inFlightImageId = null;
        state.stagedShuffleBag = null;
    }
    private static List<WallpaperImage> available(List<WallpaperImage> images, State state, long now) {
        List<WallpaperImage> result = new ArrayList<>();
        for (WallpaperImage image : images) {
            Long until = state.unavailableUntil.get(image.id);
            /* Expired backoff entries remain unavailable until a bounded probe succeeds. */
            if (until == null) result.add(image);
        }
        return result;
    }
    private static boolean isAvailable(State state, String id, long now) {
        Long until = state.unavailableUntil.get(id);
        return until == null;
    }
    private static int indexOf(List<WallpaperImage> images, String id) {
        for (int i = 0; i < images.size(); i++) if (images.get(i).id.equals(id)) return i;
        return -1;
    }
    private static boolean contains(List<WallpaperImage> images, String id) {
        return indexOf(images, id) >= 0;
    }
    private static HashSet<String> ids(List<WallpaperImage> images) {
        HashSet<String> result = new HashSet<>(); for (WallpaperImage image : images) result.add(image.id); return result;
    }
    private static boolean sameSet(HashSet<String> a, HashSet<String> b) { return a.equals(b); }
    private static final class State {
        String currentImageId;
        String pendingSequentialSuccessor;
        String inFlightImageId;
        long inFlightTransactionId;
        List<String> shuffleBag = new ArrayList<>();
        List<String> stagedShuffleBag;
        List<String> lastOrder = new ArrayList<>();
        HashSet<String> knownIds = new HashSet<>();
        Map<String, Long> unavailableUntil = new HashMap<>();
        Map<String, Integer> failures = new HashMap<>();
        OrderMode orderMode = OrderMode.SEQUENTIAL;
        long intervalMillis = RotationSettings.DEFAULT_INTERVAL_MILLIS;
        long deadline = Long.MAX_VALUE;
        long remaining = Long.MIN_VALUE;
        boolean active;
    }
}
