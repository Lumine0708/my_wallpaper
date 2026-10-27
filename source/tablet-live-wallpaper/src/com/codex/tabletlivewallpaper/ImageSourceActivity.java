package com.codex.tabletlivewallpaper;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

/** Framework-only settings UI with independent, explicit profile drafts. */
public final class ImageSourceActivity extends Activity {
    private static final int REQUEST_IMAGES = 42;
    private WallpaperConfigRepository repository;
    private UriGrantRegistry grants;
    private WallpaperConfigRepository.ConfigBaseToken token;
    private WallpaperConfiguration base;
    private WallpaperProfile draft;
    private final EnumMap<ProfileOrientation, WallpaperProfile> drafts = new EnumMap<>(ProfileOrientation.class);
    private final EnumMap<ProfileOrientation, String> profileDraftIds = new EnumMap<>(ProfileOrientation.class);
    private ProfileOrientation orientation = ProfileOrientation.PORTRAIT;
    private String draftId;
    private ProfileDraftStore draftStore;
    private LinearLayout list;
    private TextView status;
    private Button importErrorsButton;
    private Button resetButton;
    private Button addButton;
    private Button orderButton;
    private Button intervalButton;
    private Button saveButton;
    private Button portraitTab;
    private Button landscapeTab;
    private final List<String> importErrors = new ArrayList<>();
    private long editorGeneration;
    private final EnumMap<ProfileOrientation, Boolean> dirtyProfiles = new EnumMap<>(ProfileOrientation.class);
    private boolean corruptConfiguration;
    private boolean readOnlyConfiguration;
    private boolean importInFlight;
    private final WallpaperDiagnosticsStore diagnostics = WallpaperDiagnosticsStore.get();
    private final Set<String> settingsChecks = new HashSet<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); repository = new WallpaperConfigRepository(this); grants = new UriGrantRegistry(this);
        draftId = state == null ? UUID.randomUUID().toString() : state.getString("draft", UUID.randomUUID().toString());
        draftStore = new ProfileDraftStore(this, draftId); reload(); restoreDraft(); build();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        persistDraft(); state.putString("draft", draftId); super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        editorGeneration++;
        /* Rotation keeps the private cache and leases; an actual Activity destruction has the
           same cleanup semantics as Cancel/Back so delayed callbacks cannot outlive a draft. */
        if (isFinishing() && !isChangingConfigurations()) discardDraftsWithoutFinishing();
        super.onDestroy();
    }

    private void reload() {
        editorGeneration++;
        settingsChecks.clear();
        WallpaperConfigRepository.Snapshot snapshot = repository.snapshot(); token = snapshot.token;
        corruptConfiguration = snapshot.result instanceof WallpaperConfigRepository.LoadResult.Corrupt;
        readOnlyConfiguration = snapshot.result instanceof WallpaperConfigRepository.LoadResult.UnsupportedVersion;
        if (snapshot.result instanceof WallpaperConfigRepository.LoadResult.ValidV1) base = ((WallpaperConfigRepository.LoadResult.ValidV1) snapshot.result).configuration;
        else if (snapshot.result instanceof WallpaperConfigRepository.LoadResult.LegacyFallback) base = WallpaperConfiguration.empty(0);
        else base = null;
        drafts.clear();
        for (ProfileOrientation value : ProfileOrientation.values()) {
            drafts.put(value, base == null ? WallpaperProfile.empty() : base.profile(value));
            if (!profileDraftIds.containsKey(value)) profileDraftIds.put(value, UUID.randomUUID().toString());
            dirtyProfiles.put(value, false);
        }
        draft = drafts.get(orientation);
        if (snapshot.result instanceof WallpaperConfigRepository.LoadResult.LegacyFallback) grants.setLegacyAbsentConfiguration();
        else grants.setCommittedConfiguration(base);
    }
    private void restoreDraft() {
        ProfileDraftStore.Draft cached = draftStore.load();
        if (cached == null) return;
        try {
            WallpaperConfiguration candidate = new WallpaperConfiguration(WallpaperConfiguration.SCHEMA_VERSION,
                    Math.max(1L, cached.baseRevision), cached.profiles.get(ProfileOrientation.PORTRAIT),
                    cached.profiles.get(ProfileOrientation.LANDSCAPE));
            ConfigValidator.validate(candidate);
            drafts.clear(); drafts.putAll(cached.profiles); profileDraftIds.clear(); profileDraftIds.putAll(cached.ids);
            dirtyProfiles.clear(); dirtyProfiles.putAll(cached.dirty);
            token = cached.baseRevision == 0L ? new WallpaperConfigRepository.ConfigBaseToken.LegacyAbsentToken()
                    : new WallpaperConfigRepository.ConfigBaseToken.RevisionToken(cached.baseRevision);
            for (String id : profileDraftIds.values()) grants.touchDraft(id);
            draft = drafts.get(orientation);
        } catch (RuntimeException invalid) { draftStore.clear(); }
    }
    private void persistDraft() {
        if (draftStore == null || drafts.size() != ProfileOrientation.values().length) return;
        long revision = token instanceof WallpaperConfigRepository.ConfigBaseToken.RevisionToken
                ? ((WallpaperConfigRepository.ConfigBaseToken.RevisionToken) token).revision : 0L;
        draftStore.save(new ProfileDraftStore.Draft(revision, new EnumMap<>(profileDraftIds),
                new EnumMap<>(drafts), new EnumMap<>(dirtyProfiles)));
        for (String id : profileDraftIds.values()) grants.touchDraft(id);
    }
    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(color(R.color.app_background));

        FrameLayout page = new FrameLayout(this);
        page.setPadding(dp(16), dp(20), dp(16), dp(32));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int availableWidth = getResources().getDisplayMetrics().widthPixels - dp(32);
        int contentWidth = Math.min(availableWidth, dp(760));
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                contentWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        page.addView(content, contentParams);

        LinearLayout hero = section(R.drawable.bg_hero, 24);
        LinearLayout heroTop = new LinearLayout(this);
        heroTop.setOrientation(LinearLayout.HORIZONTAL);
        heroTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView eyebrow = text(getString(R.string.hero_eyebrow), 13, R.color.on_primary_muted);
        eyebrow.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        eyebrow.setLetterSpacing(0.08f);
        heroTop.addView(eyebrow, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView brandMark = new ImageView(this);
        brandMark.setImageResource(R.drawable.ic_launcher_foreground);
        brandMark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        brandMark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heroTop.addView(brandMark, new LinearLayout.LayoutParams(dp(48), dp(48)));
        hero.addView(heroTop, full());
        TextView title = text(getString(R.string.hero_title), 28, R.color.on_primary);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        hero.addView(title, topMargin(4));
        TextView subtitle = text(getString(R.string.hero_subtitle), 15, R.color.on_primary_muted);
        subtitle.setLineSpacing(dp(2), 1f);
        hero.addView(subtitle, topMargin(8));
        content.addView(hero, full());

        TextView directionTitle = sectionTitle(
                getString(R.string.direction_section_title), R.drawable.ic_portrait);
        content.addView(directionTitle, topMargin(28));
        TextView directionHint = helperText(getString(R.string.direction_section_hint));
        content.addView(directionHint, topMargin(4));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        portraitTab = tabButton(getString(R.string.portrait));
        portraitTab.setOnClickListener(v -> switchProfile(ProfileOrientation.PORTRAIT));
        landscapeTab = tabButton(getString(R.string.landscape));
        landscapeTab.setOnClickListener(v -> switchProfile(ProfileOrientation.LANDSCAPE));
        tabs.addView(portraitTab, weightedMarginEnd(4));
        tabs.addView(landscapeTab, weightedMarginStart(4));
        content.addView(tabs, topMargin(12));

        LinearLayout summaryCard = section(R.drawable.bg_status_card, 20);
        status = text("", 18, R.color.on_primary_container);
        status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        status.setCompoundDrawablePadding(dp(12));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        summaryCard.addView(status, full());
        content.addView(summaryCard, topMargin(12));

        TextView setupTitle = sectionTitle(
                getString(R.string.playback_section_title), R.drawable.ic_timer);
        content.addView(setupTitle, topMargin(28));
        TextView setupHint = helperText(getString(R.string.playback_section_hint));
        content.addView(setupHint, topMargin(4));

        LinearLayout controls = section(R.drawable.bg_card, 16);
        addButton = primaryButton(getString(R.string.add_images));
        setTextIcon(addButton, R.drawable.ic_add, R.color.button_primary_text, 20);
        addButton.setOnClickListener(v -> chooseImages());
        controls.addView(addButton, full());
        orderButton = secondaryButton(getString(R.string.order_sequential));
        setTextIcon(orderButton, R.drawable.ic_order, R.color.button_secondary_text, 20);
        orderButton.setOnClickListener(v -> {
            setDraft(new WallpaperProfile(draft.images, new RotationSettings(
                    draft.rotation.intervalMillis,
                    draft.rotation.orderMode == OrderMode.SEQUENTIAL
                            ? OrderMode.SHUFFLE : OrderMode.SEQUENTIAL)));
            render();
        });
        controls.addView(orderButton, topMargin(10));
        intervalButton = secondaryButton(getString(R.string.set_interval));
        setTextIcon(intervalButton, R.drawable.ic_timer, R.color.button_secondary_text, 20);
        intervalButton.setOnClickListener(v -> chooseInterval());
        controls.addView(intervalButton, topMargin(10));
        content.addView(controls, topMargin(12));

        importErrorsButton = dangerButton(getString(R.string.view_import_errors));
        setTextIcon(importErrorsButton, R.drawable.ic_warning, R.color.button_danger_text, 20);
        importErrorsButton.setOnClickListener(v -> showImportErrors());
        content.addView(importErrorsButton, topMargin(12));

        TextView imagesTitle = sectionTitle(
                getString(R.string.images_section_title), R.drawable.ic_queue);
        content.addView(imagesTitle, topMargin(28));
        content.addView(helperText(getString(R.string.images_section_hint)), topMargin(4));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list, topMargin(12));

        LinearLayout actions = section(R.drawable.bg_card, 16);
        saveButton = primaryButton(getString(R.string.save));
        setTextIcon(saveButton, R.drawable.ic_save, R.color.button_primary_text, 20);
        saveButton.setOnClickListener(v -> save());
        actions.addView(saveButton, full());
        Button cancel = textButton(getString(R.string.cancel));
        cancel.setOnClickListener(v -> confirmCancel());
        actions.addView(cancel, topMargin(8));
        resetButton = dangerButton(getString(R.string.reset_corrupt));
        setTextIcon(resetButton, R.drawable.ic_warning, R.color.button_danger_text, 20);
        resetButton.setOnClickListener(v -> resetCorrupt());
        actions.addView(resetButton, topMargin(8));
        content.addView(actions, topMargin(28));

        LinearLayout applyCard = section(R.drawable.bg_apply_card, 20);
        TextView applyTitle = text(getString(R.string.apply_section_title), 20, R.color.on_surface);
        applyTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setTextIcon(applyTitle, R.drawable.ic_apply, R.color.primary, 24);
        applyCard.addView(applyTitle, full());
        TextView applyHint = helperText(getString(R.string.apply_section_hint));
        applyCard.addView(applyHint, topMargin(6));
        Button enable = primaryButton(getString(R.string.open_wallpaper_picker));
        setTextIcon(enable, R.drawable.ic_apply, R.color.button_primary_text, 20);
        enable.setOnClickListener(v -> openPicker());
        applyCard.addView(enable, topMargin(16));
        content.addView(applyCard, topMargin(16));

        setContentView(scroll);
        render();
    }
    private void render() {
        /* A future schema is deliberately view-only.  Do not leave row actions enabled merely
           because its last successfully decoded snapshot happened to populate a draft. */
        boolean editable = base != null && !corruptConfiguration && !readOnlyConfiguration && !importInFlight;
        if (addButton != null) addButton.setEnabled(editable);
        if (orderButton != null) orderButton.setEnabled(editable);
        if (intervalButton != null) intervalButton.setEnabled(editable);
        if (saveButton != null) saveButton.setEnabled(editable);
        importErrorsButton.setVisibility(importErrors.isEmpty() ? View.GONE : View.VISIBLE);
        resetButton.setVisibility(corruptConfiguration ? View.VISIBLE : View.GONE);
        updateTabs();
        list.removeAllViews();
        if (base == null) {
            status.setText(readOnlyConfiguration
                    ? "配置只读\n由更高版本的应用创建"
                    : "配置需要修复\n重置后即可重新设置");
            list.addView(infoState(
                    getString(readOnlyConfiguration
                            ? R.string.config_readonly_title : R.string.config_corrupt_title),
                    getString(readOnlyConfiguration
                            ? R.string.config_readonly_hint : R.string.config_corrupt_hint)), full());
            return;
        }
        String orientationText = orientation == ProfileOrientation.PORTRAIT ? "竖屏设置" : "横屏设置";
        String dirtyText = Boolean.TRUE.equals(dirtyProfiles.get(orientation)) ? " · 未保存" : "";
        status.setText(orientationText + dirtyText + "\n" + draft.images.size() + " 张图片 · "
                + orderModeText(draft.rotation.orderMode) + " · "
                + draft.rotation.intervalMillis / 1000 + " 秒");
        setTextIcon(status,
                orientation == ProfileOrientation.PORTRAIT
                        ? R.drawable.ic_portrait : R.drawable.ic_landscape,
                R.color.on_primary_container, 24);
        boolean shuffle = draft.rotation.orderMode == OrderMode.SHUFFLE;
        orderButton.setText(getString(shuffle
                ? R.string.order_shuffle : R.string.order_sequential));
        setTextIcon(orderButton, shuffle ? R.drawable.ic_shuffle : R.drawable.ic_order,
                R.color.button_secondary_text, 20);
        intervalButton.setText(getString(
                R.string.interval_button, draft.rotation.intervalMillis / 1000));
        orderButton.setContentDescription("当前为" + orderModeText(draft.rotation.orderMode)
                + "，点击切换播放方式");
        intervalButton.setContentDescription("当前每 " + draft.rotation.intervalMillis / 1000
                + " 秒切换，点击设置切换间隔");
        if (draft.images.isEmpty()) {
            list.addView(emptyState(getString(R.string.images_empty_hint)), full());
            return;
        }
        for (int i = 0; i < draft.images.size(); i++) {
            final int index = i;
            WallpaperImage image = draft.images.get(i);
            LinearLayout card = section(R.drawable.bg_card, 16);

            TextView name = text((i + 1) + ". " + image.displayName, 16, R.color.on_surface);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            setTextIcon(name, R.drawable.ic_apply, R.color.primary, 22);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setContentDescription("第 " + (i + 1) + " 张，共 " + draft.images.size()
                    + " 张：" + image.displayName);
            card.addView(name, full());

            String diagnostic = diagnosticSuffix(image);
            if (!diagnostic.isEmpty()) {
                TextView diagnosticText = helperText(diagnostic.replaceFirst("^ — ", ""));
                card.addView(diagnosticText, topMargin(6));
            }

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            Button up = compactButton(getString(R.string.move_up));
            setTextIcon(up, R.drawable.ic_up, R.color.button_secondary_text, 18);
            up.setContentDescription("将 " + image.displayName + " 从第 " + (i + 1) + " 位上移");
            up.setEnabled(editable && i > 0);
            up.setOnClickListener(v -> move(index, index - 1));
            Button down = compactButton(getString(R.string.move_down));
            setTextIcon(down, R.drawable.ic_down, R.color.button_secondary_text, 18);
            down.setContentDescription("将 " + image.displayName + " 从第 " + (i + 1) + " 位下移");
            down.setEnabled(editable && i + 1 < draft.images.size());
            down.setOnClickListener(v -> move(index, index + 1));
            Button remove = compactDangerButton(getString(R.string.remove_image));
            setTextIcon(remove, R.drawable.ic_remove, R.color.button_danger_text, 18);
            remove.setContentDescription("移除第 " + (i + 1) + " 位的 " + image.displayName);
            remove.setEnabled(editable);
            remove.setOnClickListener(v -> remove(index));
            row.addView(up, weightedMarginEnd(4));
            row.addView(down, weightedHorizontalMargin(4));
            row.addView(remove, weightedMarginStart(4));
            card.addView(row, topMargin(12));
            list.addView(card, index == 0 ? full() : topMargin(10));
            probeForSettings(image);
        }
    }
    private void switchProfile(ProfileOrientation next) { drafts.put(orientation, draft); orientation=next; draft=drafts.get(next); render(); }
    private void chooseImages() { if (base == null || corruptConfiguration || readOnlyConfiguration || importInFlight) return; importInFlight=true; render(); Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("image/*"); intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); startActivityForResult(intent,REQUEST_IMAGES); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_IMAGES || result != RESULT_OK || data == null) { importInFlight=false; render(); return; }
        List<Uri> selected = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) {
            Uri uri = data.getClipData().getItemAt(i).getUri();
            if (uri != null && seen.add(uri.toString())) selected.add(uri);
        }
        if (data.getData() != null && seen.add(data.getData().toString())) selected.add(data.getData());
        new ImportSession(orientation, profileDraftIds.get(orientation), selected, editorGeneration).start();
    }
    private LinkedHashSet<String> allDraftUris(){LinkedHashSet<String> values=new LinkedHashSet<>();for(WallpaperProfile profile:drafts.values())for(WallpaperImage image:profile.images)values.add(image.sourceUri);return values;}
    private void move(int from,int to){List<WallpaperImage> images=new ArrayList<>(draft.images); WallpaperImage image=images.remove(from);images.add(to,image);setDraft(new WallpaperProfile(images,draft.rotation));render();}
    private void remove(int index){List<WallpaperImage> images=new ArrayList<>(draft.images);images.remove(index);setDraft(new WallpaperProfile(images,draft.rotation));render();}
    private void chooseInterval(){final String[] choices={"5 秒","10 秒","30 秒","60 秒"}; final long[] values={5000,10000,30000,60000};new AlertDialog.Builder(this).setTitle(R.string.interval_title).setItems(choices,(d,w)->{setDraft(new WallpaperProfile(draft.images,new RotationSettings(values[w],draft.rotation.orderMode)));render();}).show();}
    private void save(){ if (base == null || corruptConfiguration || readOnlyConfiguration) return; editorGeneration++; try{WallpaperConfiguration saved=repository.updateProfileAndCommitLease(orientation,token,current->draft,grants,profileDraftIds.get(orientation));base=saved; drafts.put(orientation,saved.profile(orientation)); draft=drafts.get(orientation); token=new WallpaperConfigRepository.ConfigBaseToken.RevisionToken(saved.revision); dirtyProfiles.put(orientation,false); if (!hasDirtyDrafts()) draftStore.clear(); else persistDraft(); Toast.makeText(this,"已保存",Toast.LENGTH_SHORT).show();}catch(WallpaperConfigRepository.ConflictException conflict){new AlertDialog.Builder(this).setMessage("配置已在其他页面中修改，请重新加载后再试。").setPositiveButton("重新加载",(d,w)->{discardDraftsWithoutFinishing();reload();render();}).show();}catch(Exception failure){Toast.makeText(this,"无法保存设置",Toast.LENGTH_LONG).show();}}
    private void setDraft(WallpaperProfile value) { if (base == null || corruptConfiguration || readOnlyConfiguration) return; dirtyProfiles.put(orientation, true); draft = value; drafts.put(orientation, value); }
    private void setProfileDraft(ProfileOrientation target, WallpaperProfile value) {
        drafts.put(target, value);
        dirtyProfiles.put(target, true);
        if (orientation == target) draft = value;
    }
    private void showImportErrors() {
        new AlertDialog.Builder(this).setTitle("部分图片未能添加")
                .setMessage(android.text.TextUtils.join("\n", importErrors))
                .setPositiveButton("确定", null).show();
    }
    /** Imports one URI at a time so selection order is retained and each result gets a reason. */
    private final class ImportSession {
        private final ProfileOrientation target;
        private final String targetDraftId;
        private final List<Uri> selected;
        private final long generation;
        private int index, added;
        private final List<String> failures = new ArrayList<>();
        ImportSession(ProfileOrientation target, String targetDraftId, List<Uri> selected, long generation) {
            this.target = target; this.targetDraftId = targetDraftId; this.selected = selected; this.generation = generation;
        }
        void start() { next(); }
        private void next() {
            if (index >= selected.size()) { finish(); return; }
            final Uri uri = selected.get(index++);
            String value = uri.toString();
            if (generation != editorGeneration || isFinishing() || isDestroyed()) return;
            WallpaperProfile targetProfile = drafts.get(target);
            boolean newConfigurationUri = !allDraftUris().contains(value);
            if (value.length() > ConfigValidator.MAX_URI_LENGTH || !value.startsWith("content://")) {
                fail(uri, "不支持的文档地址"); return;
            }
            if (containsUri(targetProfile, value)) { fail(uri, "已存在于当前配置中"); return; }
            if (targetProfile.images.size() >= ConfigValidator.MAX_IMAGES_PER_PROFILE) { fail(uri, "已达到当前配置的图片数量上限"); return; }
            if (newConfigurationUri && allDraftUris().size() >= ConfigValidator.MAX_DISTINCT_URIS) { fail(uri, "已达到配置的图片数量上限"); return; }
            WallpaperImage candidateImage = new WallpaperImage(UUID.randomUUID().toString(), value, uri.getLastPathSegment());
            try {
                preflightImport(target, targetProfile, candidateImage);
            } catch (Exception oversized) { fail(uri, "已达到配置大小上限"); return; }
            final UriGrantRegistry.Lease lease = grants.acquireLease(targetDraftId, uri);
            if (lease == null) { fail(uri, "未获得持久读取权限"); return; }
            DecodeCoordinator.get().probe(ImageSourceActivity.this, uri, failure -> runOnUiThread(() -> {
                if (generation != editorGeneration || isFinishing() || isDestroyed()) { grants.releaseLease(lease); return; }
                if (failure != null) { grants.releaseLease(lease); fail(uri, importFailure(failure)); return; }
                WallpaperProfile current = drafts.get(target);
                if (containsUri(current, value)) { grants.releaseLease(lease); fail(uri, "已存在于当前配置中"); return; }
                try {
                    if (current.images.size() >= ConfigValidator.MAX_IMAGES_PER_PROFILE) throw new IllegalArgumentException();
                    if (!allDraftUris().contains(value) && allDraftUris().size() >= ConfigValidator.MAX_DISTINCT_URIS) throw new IllegalArgumentException();
                    preflightImport(target, current, candidateImage);
                } catch (Exception limit) { grants.releaseLease(lease); fail(uri, "已达到配置大小上限"); return; }
                List<WallpaperImage> images = new ArrayList<>(current.images);
                images.add(candidateImage);
                setProfileDraft(target, new WallpaperProfile(images, current.rotation));
                added++;
                next();
            }));
        }
        private void fail(Uri uri, String reason) { failures.add(shortName(uri) + "：" + reason); next(); }
        private void finish() {
            importInFlight=false; importErrors.clear(); importErrors.addAll(failures); persistDraft(); render();
            Toast.makeText(ImageSourceActivity.this, "已添加 " + added + " 张，失败 " + failures.size() + " 张", Toast.LENGTH_LONG).show();
        }
    }
    private void preflightImport(ProfileOrientation target, WallpaperProfile targetProfile,
                                 WallpaperImage candidateImage) throws java.io.IOException {
        WallpaperConfiguration candidate = new WallpaperConfiguration(WallpaperConfiguration.SCHEMA_VERSION,
                Math.max(1L, base == null ? 1L : base.revision + 1L),
                target == ProfileOrientation.PORTRAIT ? new WallpaperProfile(append(targetProfile, candidateImage), targetProfile.rotation) : drafts.get(ProfileOrientation.PORTRAIT),
                target == ProfileOrientation.LANDSCAPE ? new WallpaperProfile(append(targetProfile, candidateImage), targetProfile.rotation) : drafts.get(ProfileOrientation.LANDSCAPE));
        WallpaperConfigRepository.preflightCandidate(candidate);
    }
    private static List<WallpaperImage> append(WallpaperProfile profile, WallpaperImage image) { List<WallpaperImage> images = new ArrayList<>(profile.images); images.add(image); return images; }
    private static boolean containsUri(WallpaperProfile profile, String value) { for (WallpaperImage image : profile.images) if (value.equals(image.sourceUri)) return true; return false; }
    private static String shortName(Uri uri) { String value = uri.getLastPathSegment(); return value == null || value.isEmpty() ? "所选图片" : value; }
    private static String importFailure(WallpaperDiagnosticsStore.Failure failure) {
        if (failure == WallpaperDiagnosticsStore.Failure.PERMISSION) return "无法读取图片";
        if (failure == WallpaperDiagnosticsStore.Failure.TOO_LARGE) return "图片超过安全大小限制";
        if (failure == WallpaperDiagnosticsStore.Failure.MEMORY) return "内存不足，无法安全检查图片";
        return "无法解码为图片";
    }
    private String diagnosticSuffix(WallpaperImage image) {
        if (base == null || base.revision < 1) return "";
        WallpaperDiagnosticsStore.Entry active = diagnostics.get(base.revision, image.id, WallpaperDiagnosticsStore.Source.ACTIVE_ENGINE);
        WallpaperDiagnosticsStore.Entry preview = diagnostics.get(base.revision, image.id, WallpaperDiagnosticsStore.Source.PREVIEW_ENGINE);
        WallpaperDiagnosticsStore.Entry probe = diagnostics.get(base.revision, image.id, WallpaperDiagnosticsStore.Source.SETTINGS_PROBE);
        if (active != null && active.state == WallpaperDiagnosticsStore.State.FAILURE) return " — 当前壁纸：" + importFailure(active.failure);
        if (preview != null && preview.state == WallpaperDiagnosticsStore.State.FAILURE) return " — 预览：" + importFailure(preview.failure);
        if (probe == null) return " — 尚未检查";
        if (probe.state == WallpaperDiagnosticsStore.State.CHECKING) return " — 正在检查";
        if (probe.state == WallpaperDiagnosticsStore.State.SUCCESS) return " — 已检查";
        return " — 当前页面：" + importFailure(probe.failure);
    }
    private void probeForSettings(WallpaperImage image) {
        if (base == null || base.revision < 1 || !image.sourceUri.startsWith("content://")) return;
        final String key = base.revision + ":" + image.id;
        if (!settingsChecks.add(key)) return;
        final long generation = editorGeneration; final long revision = base.revision; final String id = image.id;
        diagnostics.checking(revision, id, WallpaperDiagnosticsStore.Source.SETTINGS_PROBE);
        DecodeCoordinator.get().probe(this, Uri.parse(image.sourceUri), failure -> {
            /* A probe completion belongs to the editor generation that submitted it.  Checking
               this before touching the process-wide store prevents a cancelled/rotated editor
               from publishing stale SETTINGS_PROBE state for a later session. */
            if (generation != editorGeneration || isFinishing() || isDestroyed()) return;
            if (failure == null) diagnostics.success(revision, id, WallpaperDiagnosticsStore.Source.SETTINGS_PROBE);
            else diagnostics.report(revision, id, failure, WallpaperDiagnosticsStore.Source.SETTINGS_PROBE);
            runOnUiThread(() -> { if (generation == editorGeneration && !isFinishing() && !isDestroyed()) render(); });
        });
    }
    private boolean hasDirtyDrafts() { for (Boolean dirty : dirtyProfiles.values()) if (Boolean.TRUE.equals(dirty)) return true; return false; }
    private void cancelAllDrafts() { for (String id : profileDraftIds.values()) grants.cancelDraft(id); }
    private void confirmCancel() { if (!hasDirtyDrafts()) { discardAndFinish(); return; } new AlertDialog.Builder(this).setMessage("放弃未保存的更改？").setNegativeButton("继续编辑", null).setPositiveButton("放弃", (d, w) -> discardAndFinish()).show(); }
    private void discardDraftsWithoutFinishing() { cancelAllDrafts(); if (draftStore != null) draftStore.clear(); for (ProfileOrientation value : ProfileOrientation.values()) dirtyProfiles.put(value, false); }
    private void discardAndFinish() { editorGeneration++; discardDraftsWithoutFinishing(); finish(); }
    @Override public void onBackPressed() { confirmCancel(); }
    private void resetCorrupt() { if (!corruptConfiguration) return; new AlertDialog.Builder(this).setMessage("重置损坏的配置？").setNegativeButton("取消", null).setPositiveButton("重置", (d,w) -> performResetCorrupt()).show(); }
    private void performResetCorrupt() { if (!corruptConfiguration) return; try { base=repository.resetCorrupt(); token=new WallpaperConfigRepository.ConfigBaseToken.RevisionToken(base.revision); corruptConfiguration=false; readOnlyConfiguration=false; /* A confirmed reset is also an explicit rejection of any cached pre-reset draft. */ discardDraftsWithoutFinishing(); grants.setCommittedConfiguration(base); reload(); render(); } catch (Exception ignored) { Toast.makeText(this,"无法重置配置",Toast.LENGTH_LONG).show(); } }
    private void openPicker(){
        /* HyperOS 3 accepts ACTION_CHANGE_LIVE_WALLPAPER, briefly starts its picker, then
           finishes without showing the requested component.  Its wallpaper list and preview
           flow work normally, so use that supported path on Xiaomi devices. */
        if (usesSystemWallpaperList(Build.MANUFACTURER)) { openWallpaperList(); return; }
        Intent intent=new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,new ComponentName(this,FitWallpaperService.class));
        try{startActivity(intent);}catch(RuntimeException e){openWallpaperList();}
    }
    static boolean usesSystemWallpaperList(String manufacturer){return manufacturer!=null&&"xiaomi".equalsIgnoreCase(manufacturer.trim());}
    private void openWallpaperList(){startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));}
    private static String orderModeText(OrderMode mode){return mode==OrderMode.SHUFFLE?"随机播放":"顺序播放";}

    private void updateTabs() {
        if (portraitTab == null || landscapeTab == null) return;
        updateTab(portraitTab, ProfileOrientation.PORTRAIT, getString(R.string.portrait));
        updateTab(landscapeTab, ProfileOrientation.LANDSCAPE, getString(R.string.landscape));
    }

    private void updateTab(Button tab, ProfileOrientation value, String label) {
        boolean selected = orientation == value;
        boolean dirty = Boolean.TRUE.equals(dirtyProfiles.get(value));
        tab.setText(label + (dirty ? "  •" : ""));
        tab.setBackgroundResource(selected ? R.drawable.bg_tab_selected : R.drawable.bg_tab_unselected);
        tab.setTextColor(color(selected ? R.color.on_primary : R.color.on_surface_variant));
        setTextIcon(tab,
                value == ProfileOrientation.PORTRAIT
                        ? R.drawable.ic_portrait : R.drawable.ic_landscape,
                selected ? R.color.on_primary : R.color.button_secondary_text, 20);
        tab.setContentDescription(label + (selected ? "，已选择" : "")
                + (dirty ? "，有未保存的更改" : ""));
        tab.setSelected(selected);
    }

    private LinearLayout emptyState(String message) {
        return infoState(getString(R.string.images_empty_title), message);
    }

    private LinearLayout infoState(String titleValue, String message) {
        LinearLayout empty = section(R.drawable.bg_empty_state, 22);
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView illustration = new ImageView(this);
        illustration.setImageResource(R.drawable.ic_queue);
        illustration.setColorFilter(color(R.color.primary));
        illustration.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams illustrationParams =
                new LinearLayout.LayoutParams(dp(42), dp(42));
        illustrationParams.gravity = Gravity.CENTER_HORIZONTAL;
        empty.addView(illustration, illustrationParams);
        TextView title = text(titleValue, 18, R.color.on_surface);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        empty.addView(title, topMargin(10));
        TextView hint = helperText(message);
        hint.setGravity(Gravity.CENTER);
        empty.addView(hint, topMargin(6));
        return empty;
    }

    private LinearLayout section(int background, int paddingDp) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp));
        section.setBackgroundResource(background);
        return section;
    }

    private TextView sectionTitle(String value, int iconResource) {
        TextView view = text(value, 20, R.color.on_background);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setTextIcon(view, iconResource, R.color.primary, 24);
        return view;
    }

    private TextView helperText(String value) {
        TextView view = text(value, 14, R.color.on_surface_variant);
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    private TextView text(String value, float sizeSp, int colorResource) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color(colorResource));
        view.setGravity(Gravity.START);
        return view;
    }

    private Button primaryButton(String value) {
        return styledButton(value, R.drawable.bg_primary_button,
                R.color.button_primary_text, 52, 15);
    }

    private Button secondaryButton(String value) {
        return styledButton(value, R.drawable.bg_secondary_button,
                R.color.button_secondary_text, 52, 15);
    }

    private Button dangerButton(String value) {
        return styledButton(value, R.drawable.bg_danger_button,
                R.color.button_danger_text, 52, 15);
    }

    private Button textButton(String value) {
        return styledButton(value, R.drawable.bg_text_button,
                R.color.button_secondary_text, 48, 15);
    }

    private Button compactButton(String value) {
        return styledButton(value, R.drawable.bg_secondary_button,
                R.color.button_secondary_text, 48, 14);
    }

    private Button compactDangerButton(String value) {
        return styledButton(value, R.drawable.bg_danger_button,
                R.color.button_danger_text, 48, 14);
    }

    private Button tabButton(String value) {
        Button button = styledButton(value, R.drawable.bg_tab_unselected,
                R.color.button_secondary_text, 52, 15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return button;
    }

    private Button styledButton(String value, int backgroundResource, int textColors,
                                int minHeightDp, float textSizeSp) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(textSizeSp);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinHeight(dp(minHeightDp));
        button.setMinimumHeight(dp(minHeightDp));
        button.setMinimumWidth(0);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setBackgroundResource(backgroundResource);
        button.setTextColor(getColorStateList(textColors));
        button.setStateListAnimator(null);
        button.setElevation(0f);
        return button;
    }

    private void setTextIcon(TextView view, int drawableResource, int tintResource, int sizeDp) {
        Drawable icon = getDrawable(drawableResource).mutate();
        icon.setTintList(getColorStateList(tintResource));
        int size = dp(sizeDp);
        icon.setBounds(0, 0, size, size);
        view.setCompoundDrawablePadding(dp(8));
        view.setCompoundDrawablesRelative(icon, null, null, null);
    }

    private int color(int resource) { return getColor(resource); }
    private LinearLayout.LayoutParams full(){return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);}
    private LinearLayout.LayoutParams topMargin(int value){LinearLayout.LayoutParams p=full();p.topMargin=dp(value);return p;}
    private LinearLayout.LayoutParams weightedMarginEnd(int value){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1);p.setMarginEnd(dp(value));return p;}
    private LinearLayout.LayoutParams weightedMarginStart(int value){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1);p.setMarginStart(dp(value));return p;}
    private LinearLayout.LayoutParams weightedHorizontalMargin(int value){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1);p.setMarginStart(dp(value));p.setMarginEnd(dp(value));return p;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
