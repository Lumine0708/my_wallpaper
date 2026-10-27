package com.codex.tabletlivewallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Random;

public final class WallpaperConfigTest {
    private static WallpaperImage image(String id) { return new WallpaperImage(java.util.UUID.nameUUIDFromBytes(id.getBytes()).toString(), "content://test/" + id, id + ".jpg"); }
    private static WallpaperProfile profile(OrderMode mode, WallpaperImage... images) { return new WallpaperProfile(Arrays.asList(images), new RotationSettings(10_000L, mode)); }
    private static String id(WallpaperPlaybackController.Candidate candidate) { return candidate == null ? null : candidate.imageId; }
    @Test public void validProfilesAreIndependentAndPreserveOrder() {
        WallpaperConfiguration configuration = new WallpaperConfiguration(1, 1, profile(OrderMode.SEQUENTIAL, image("p1"), image("p2")), profile(OrderMode.SHUFFLE, image("l1")));
        ConfigValidator.validate(configuration);
        assertEquals("p1.jpg", configuration.portraitProfile.images.get(0).displayName);
        assertEquals("l1.jpg", configuration.landscapeProfile.images.get(0).displayName);
    }
    @Test public void duplicateIdsAndUrisAreRejected() {
        WallpaperImage duplicate = image("same");
        WallpaperConfiguration configuration = new WallpaperConfiguration(1, 1, profile(OrderMode.SEQUENTIAL, duplicate), profile(OrderMode.SEQUENTIAL, duplicate));
        assertThrows(IllegalArgumentException.class, () -> ConfigValidator.validate(configuration));
    }
    @Test public void shuffleBagDoesNotRepeatAcrossBoundary() {
        WallpaperProfile profile = profile(OrderMode.SHUFFLE, image("a"), image("b"), image("c"));
        WallpaperConfiguration configuration = new WallpaperConfiguration(1, 1, profile, WallpaperProfile.empty());
        WallpaperPlaybackController controller = new WallpaperPlaybackController(new Random(4)); controller.merge(configuration, 0); controller.activate(ProfileOrientation.PORTRAIT, 0);
        String last = null; for (int i=0;i<9;i++) { WallpaperPlaybackController.Candidate next=controller.nextCandidate(ProfileOrientation.PORTRAIT,profile,0); assertNotEquals(last,id(next)); controller.preparedAndDisplayed(next,0); last=id(next); }
    }
    @Test public void failedImageDoesNotBecomeCurrentAndBacksOff() {
        WallpaperProfile profile = profile(OrderMode.SEQUENTIAL, image("a"), image("b")); WallpaperConfiguration configuration = new WallpaperConfiguration(1,1,profile,WallpaperProfile.empty()); WallpaperPlaybackController controller = new WallpaperPlaybackController(); controller.merge(configuration,0); controller.activate(ProfileOrientation.PORTRAIT,0); WallpaperPlaybackController.Candidate candidate=controller.nextCandidate(ProfileOrientation.PORTRAIT,profile,0); controller.preparationFailed(candidate,0); assertEquals(null,controller.current(ProfileOrientation.PORTRAIT)); assertNotEquals(id(candidate),id(controller.nextCandidate(ProfileOrientation.PORTRAIT,profile,0)));
    }
    @Test public void failedFirstCandidateLeavesAnImmediateBoundedReplacement() {
        WallpaperImage first = image("first");
        WallpaperImage second = image("second");
        WallpaperProfile profile = profile(OrderMode.SEQUENTIAL, first, second);
        WallpaperPlaybackController controller = new WallpaperPlaybackController();
        controller.merge(new WallpaperConfiguration(1, 1, profile, WallpaperProfile.empty()), 0);
        controller.activate(ProfileOrientation.PORTRAIT, 0);

        WallpaperPlaybackController.Candidate firstCandidate = controller.nextCandidate(ProfileOrientation.PORTRAIT, profile, 0);
        assertEquals(first.id, id(firstCandidate));
        controller.preparationFailed(firstCandidate, 0);

        /* The service must start this candidate immediately instead of waiting for the failed
           image's one-minute availability retry. */
        assertEquals(second.id, id(controller.nextCandidate(ProfileOrientation.PORTRAIT, profile, 1)));
        assertEquals(60_000L, controller.nextAvailabilityRetryDeadline(ProfileOrientation.PORTRAIT));
    }
    @Test public void removingCurrentImageDoesNotCommitAnUnrenderedReplacement() {
        WallpaperImage first = image("first");
        WallpaperImage second = image("second");
        WallpaperProfile original = profile(OrderMode.SEQUENTIAL, first, second);
        WallpaperPlaybackController controller = new WallpaperPlaybackController();
        controller.merge(new WallpaperConfiguration(1, 1, original, WallpaperProfile.empty()), 0);
        controller.preparedAndDisplayed(controller.nextCandidate(ProfileOrientation.PORTRAIT, original, 0), 0);
        WallpaperProfile removed = profile(OrderMode.SEQUENTIAL, second);
        controller.merge(new WallpaperConfiguration(1, 2, removed, WallpaperProfile.empty()), 1);
        assertEquals(null, controller.current(ProfileOrientation.PORTRAIT));
        assertEquals(second.id, id(controller.nextCandidate(ProfileOrientation.PORTRAIT, removed, 1)));
    }
    @Test public void removingCurrentImageUsesItsFormerSequentialPosition() {
        WallpaperImage first = image("first");
        WallpaperImage middle = image("middle");
        WallpaperImage last = image("last");
        WallpaperPlaybackController controller = new WallpaperPlaybackController();
        controller.merge(new WallpaperConfiguration(1, 1, profile(OrderMode.SEQUENTIAL, first, middle, last), WallpaperProfile.empty()), 0);
        controller.preparationFailed(controller.nextCandidate(ProfileOrientation.PORTRAIT,
                profile(OrderMode.SEQUENTIAL, first, middle, last), 0), 0);
        controller.preparedAndDisplayed(controller.nextCandidate(ProfileOrientation.PORTRAIT,
                profile(OrderMode.SEQUENTIAL, first, middle, last), 1), 1);
        WallpaperProfile removed = profile(OrderMode.SEQUENTIAL, first, last);
        controller.merge(new WallpaperConfiguration(1, 2, removed, WallpaperProfile.empty()), 1);
        assertEquals(last.id, id(controller.nextCandidate(ProfileOrientation.PORTRAIT, removed, 1)));
    }
    @Test public void oneUsableImageHasNoRotationDeadlineButKeepsAvailabilityDeadline() {
        WallpaperImage first = image("first");
        WallpaperImage second = image("second");
        WallpaperProfile profile = profile(OrderMode.SEQUENTIAL, first, second);
        WallpaperPlaybackController controller = new WallpaperPlaybackController();
        controller.merge(new WallpaperConfiguration(1, 1, profile, WallpaperProfile.empty()), 0);
        controller.activate(ProfileOrientation.PORTRAIT, 0);
        controller.preparationFailed(controller.nextCandidate(ProfileOrientation.PORTRAIT, profile, 0), 0);
        assertEquals(false, controller.hasRotationCandidate(ProfileOrientation.PORTRAIT, profile, 1));
        assertEquals(Long.MAX_VALUE, controller.nextRotationDeadline(ProfileOrientation.PORTRAIT, profile, 1));
        assertEquals(60_000L, controller.nextAvailabilityRetryDeadline(ProfileOrientation.PORTRAIT));
    }
    @Test public void sequentialBackoffExpiresWithoutLeavingTheImagePermanentlySkipped() {
        WallpaperImage first = image("first");
        WallpaperImage second = image("second");
        WallpaperProfile profile = profile(OrderMode.SEQUENTIAL, first, second);
        WallpaperPlaybackController controller = new WallpaperPlaybackController();
        controller.merge(new WallpaperConfiguration(1, 1, profile, WallpaperProfile.empty()), 0);
        controller.activate(ProfileOrientation.PORTRAIT, 0);
        controller.preparationFailed(controller.nextCandidate(ProfileOrientation.PORTRAIT, profile, 0), 0);
        assertEquals(second.id, id(controller.nextCandidate(ProfileOrientation.PORTRAIT, profile, 1)));
        controller.preparedAndDisplayed(controller.nextCandidate(ProfileOrientation.PORTRAIT, profile, 1), 1);
        assertEquals(first.id, controller.nextAvailabilityRetry(ProfileOrientation.PORTRAIT, profile, 60_000L));
        assertEquals(false, controller.hasRotationCandidate(ProfileOrientation.PORTRAIT, profile, 60_000L));
        controller.availabilityRestored(ProfileOrientation.PORTRAIT, first.id, 60_000L);
        assertEquals(first.id, id(controller.nextCandidate(ProfileOrientation.PORTRAIT, profile, 60_000L)));
    }
    @Test public void duplicateActivationDoesNotRestartAnActiveDeadline() {
        WallpaperProfile profile = profile(OrderMode.SEQUENTIAL, image("first"), image("second"));
        WallpaperPlaybackController controller = new WallpaperPlaybackController();
        controller.merge(new WallpaperConfiguration(1, 1, profile, WallpaperProfile.empty()), 0);
        controller.activate(ProfileOrientation.PORTRAIT, 0);
        controller.activate(ProfileOrientation.PORTRAIT, 5_000L);
        assertEquals(10_000L, controller.nextRotationDeadline(ProfileOrientation.PORTRAIT, profile, 5_000L));
    }
    @Test public void cancelledShufflePreparationDoesNotConsumeTheBag() {
        WallpaperProfile profile = profile(OrderMode.SHUFFLE, image("a"), image("b"), image("c"));
        WallpaperPlaybackController controller = new WallpaperPlaybackController(new Random(7));
        controller.merge(new WallpaperConfiguration(1, 1, profile, WallpaperProfile.empty()), 0);
        WallpaperPlaybackController.Candidate candidate = controller.nextCandidate(ProfileOrientation.PORTRAIT, profile, 0);
        assertEquals(id(candidate), id(controller.nextCandidate(ProfileOrientation.PORTRAIT, profile, 0)));
        controller.preparationFailed(candidate, 0);
        assertNotEquals(id(candidate), id(controller.nextCandidate(ProfileOrientation.PORTRAIT, profile, 1)));
    }
    @Test public void generationAbortDropsAnUncommittedCandidate() {
        WallpaperProfile profile = profile(OrderMode.SHUFFLE, image("a"), image("b"), image("c"));
        WallpaperPlaybackController controller = new WallpaperPlaybackController(new Random(7));
        controller.merge(new WallpaperConfiguration(1, 1, profile, WallpaperProfile.empty()), 0);

        WallpaperPlaybackController.Candidate candidate = controller.nextCandidate(ProfileOrientation.PORTRAIT, profile, 0);
        controller.abortPendingTransactions();

        /* The same candidate is available again; the old staged bag was never committed. */
        assertEquals(id(candidate), id(controller.nextCandidate(ProfileOrientation.PORTRAIT, profile, 1)));
    }
    @Test public void staleCandidateCannotCommitAfterGenerationAbort() {
        WallpaperProfile profile = profile(OrderMode.SEQUENTIAL, image("a"), image("b"));
        WallpaperPlaybackController controller = new WallpaperPlaybackController();
        controller.merge(new WallpaperConfiguration(1, 1, profile, WallpaperProfile.empty()), 0);

        WallpaperPlaybackController.Candidate stale = controller.nextCandidate(ProfileOrientation.PORTRAIT, profile, 0);
        controller.abortPendingTransactions();
        WallpaperPlaybackController.Candidate current = controller.nextCandidate(ProfileOrientation.PORTRAIT, profile, 1);

        controller.preparedAndDisplayed(stale, 2);
        assertEquals(null, controller.current(ProfileOrientation.PORTRAIT));
        controller.preparedAndDisplayed(current, 3);
        assertEquals(current.imageId, controller.current(ProfileOrientation.PORTRAIT));
    }
    @Test public void restoredShuffleImageRejoinsTheCurrentBag() {
        WallpaperProfile profile = profile(OrderMode.SHUFFLE, image("a"), image("b"), image("c"));
        WallpaperPlaybackController controller = new WallpaperPlaybackController(new Random(2));
        controller.merge(new WallpaperConfiguration(1, 1, profile, WallpaperProfile.empty()), 0);
        controller.activate(ProfileOrientation.PORTRAIT, 0);

        WallpaperPlaybackController.Candidate failed = controller.nextCandidate(ProfileOrientation.PORTRAIT, profile, 0);
        controller.preparationFailed(failed, 0);
        WallpaperPlaybackController.Candidate displayed = controller.nextCandidate(ProfileOrientation.PORTRAIT, profile, 1);
        controller.preparedAndDisplayed(displayed, 1);
        controller.availabilityRestored(ProfileOrientation.PORTRAIT, failed.imageId, 60_000L);

        WallpaperPlaybackController.Candidate first = controller.nextCandidate(ProfileOrientation.PORTRAIT, profile, 60_000L);
        controller.preparedAndDisplayed(first, 60_000L);
        WallpaperPlaybackController.Candidate second = controller.nextCandidate(ProfileOrientation.PORTRAIT, profile, 60_001L);
        assertEquals(failed.imageId, first.imageId.equals(failed.imageId) ? first.imageId : second.imageId);
    }
    @Test public void configurationMergeAbortsAnUncommittedCandidate() {
        WallpaperProfile profile = profile(OrderMode.SEQUENTIAL, image("a"), image("b"));
        WallpaperPlaybackController controller = new WallpaperPlaybackController();
        controller.merge(new WallpaperConfiguration(1, 1, profile, WallpaperProfile.empty()), 0);
        WallpaperPlaybackController.Candidate candidate = controller.nextCandidate(ProfileOrientation.PORTRAIT, profile, 0);

        controller.merge(new WallpaperConfiguration(1, 2, profile, WallpaperProfile.empty()), 1);

        assertEquals(id(candidate), id(controller.nextCandidate(ProfileOrientation.PORTRAIT, profile, 1)));
    }
    @Test public void emptyProfileClearsCommittedPlaybackState() {
        WallpaperProfile profile = profile(OrderMode.SEQUENTIAL, image("a"));
        WallpaperPlaybackController controller = new WallpaperPlaybackController();
        controller.merge(new WallpaperConfiguration(1, 1, profile, WallpaperProfile.empty()), 0);
        controller.preparedAndDisplayed(controller.nextCandidate(ProfileOrientation.PORTRAIT, profile, 0), 0);

        controller.merge(WallpaperConfiguration.empty(2), 1);

        assertEquals(null, controller.current(ProfileOrientation.PORTRAIT));
        assertEquals(null, id(controller.nextCandidate(ProfileOrientation.PORTRAIT,
                WallpaperProfile.empty(), 1)));
    }
    @Test public void intervalAndNameBoundariesAreValidated() {
        WallpaperProfile invalid=new WallpaperProfile(Collections.singletonList(image("a")),new RotationSettings(4_999L,OrderMode.SEQUENTIAL)); assertThrows(IllegalArgumentException.class,()->ConfigValidator.validate(new WallpaperConfiguration(1,1,invalid,WallpaperProfile.empty()))); assertEquals("未命名图片",ConfigValidator.cleanDisplayName("\u0000\t",null));
    }
    @Test public void diagnosticsKeepActivePreviewAndSettingsResultsSeparate() {
        WallpaperDiagnosticsStore diagnostics = WallpaperDiagnosticsStore.get();
        final long[] now = {100L};
        diagnostics.setClockForTests(() -> now[0]);
        diagnostics.clear(97L, "image");
        diagnostics.report(97L, "image", WallpaperDiagnosticsStore.Failure.DECODE,
                WallpaperDiagnosticsStore.Source.PREVIEW_ENGINE);
        diagnostics.report(97L, "image", WallpaperDiagnosticsStore.Failure.PERMISSION,
                WallpaperDiagnosticsStore.Source.ACTIVE_ENGINE);
        assertEquals(WallpaperDiagnosticsStore.Failure.DECODE,
                diagnostics.get(97L, "image", WallpaperDiagnosticsStore.Source.PREVIEW_ENGINE).failure);
        assertEquals(WallpaperDiagnosticsStore.Failure.PERMISSION,
                diagnostics.get(97L, "image", WallpaperDiagnosticsStore.Source.ACTIVE_ENGINE).failure);
        assertEquals(null, diagnostics.get(97L, "image", WallpaperDiagnosticsStore.Source.SETTINGS_PROBE));
        diagnostics.clear(97L, "image");
    }
    @Test public void settingsDiagnosticsExposeCheckingSuccessAndStableFailures() {
        WallpaperDiagnosticsStore diagnostics = WallpaperDiagnosticsStore.get();
        final long[] now = {100L};
        diagnostics.setClockForTests(() -> now[0]);
        diagnostics.clear(98L, "image");
        diagnostics.checking(98L, "image", WallpaperDiagnosticsStore.Source.SETTINGS_PROBE);
        assertEquals(WallpaperDiagnosticsStore.State.CHECKING,
                diagnostics.get(98L, "image", WallpaperDiagnosticsStore.Source.SETTINGS_PROBE).state);
        diagnostics.success(98L, "image", WallpaperDiagnosticsStore.Source.SETTINGS_PROBE);
        assertEquals(WallpaperDiagnosticsStore.State.SUCCESS,
                diagnostics.get(98L, "image", WallpaperDiagnosticsStore.Source.SETTINGS_PROBE).state);
        diagnostics.report(98L, "image", WallpaperDiagnosticsStore.Failure.TOO_LARGE,
                WallpaperDiagnosticsStore.Source.SETTINGS_PROBE);
        now[0] += 31L * 60L * 1_000L;
        assertEquals(WallpaperDiagnosticsStore.Failure.TOO_LARGE,
                diagnostics.get(98L, "image", WallpaperDiagnosticsStore.Source.SETTINGS_PROBE).failure);
        diagnostics.clear(98L, "image");
    }
    @Test public void xiaomiDevicesUseTheCompatibleWallpaperList() {
        assertEquals(true, ImageSourceActivity.usesSystemWallpaperList("Xiaomi"));
        assertEquals(true, ImageSourceActivity.usesSystemWallpaperList(" xIAoMi "));
        assertEquals(false, ImageSourceActivity.usesSystemWallpaperList("Google"));
        assertEquals(false, ImageSourceActivity.usesSystemWallpaperList(null));
    }
}
