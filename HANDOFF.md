# StorageSweep — Project Status & Handoff

Last updated: this session — cleared out the rest of the prior session's roadmap in one pass:
(1) fixed silent exception swallowing in `MainActivity`'s APK share/open/extract helpers (real
`Toast` feedback now), (2) reformatted `ApkModels.kt` out of its dense uncommented style and
into the rest of the codebase's convention, (3) added real package-identity parsing for
`.apks`/`.apkm`/`.xapk` installer containers (previously only `.apk` got parsed — see §4 for why
each format needed a different strategy), and (4) added the project's first instrumentation
tests (`StandardRootDiscoveryInstrumentedTest`, `PermissionManagerInstrumentedTest`,
`ApkFileManagerInstrumentedTest`) — real-device/`Context`-dependent coverage the JVM unit test
suite structurally can't provide. One coverage gap remains open (`ScanForegroundService`/
`PowerScanEngine`'s Shizuku IPC path) — see §5.

Before that, same session: added the App Manager / Cache Manager / Orphan Scanner / APK
Manager / Storage Tools feature set ("Piece 1-4" — see §2), fixed three real functional bugs
found on re-analysis of that new code (Android/data|obb leftover/orphan detection had no
Shizuku-privileged fallback, `storageStatsAvailable()` could never detect a missing Usage Access
grant, and `StorageModels.kt`'s "Android" category total had the identical undercounting issue —
all §4), and **restored this document itself** after a prior session/commit ("Piece 1-4", commits
`42fadc3`..`ee75189`) replaced ~500 lines of maintained history here with a 29-line stub,
directly violating §7 below — nothing from that stub was lost, its legitimate content is now
properly in §2 (see the §4 entry documenting the incident).

Before that: real cleanup process-kill resilience via WorkManager + a per-item persisted ledger
(§2 "Real cleanup process-kill resilience"); a real `canonicalPath()`-based cycle guard for
`PowerScanEngine`'s IPC walk, replacing the string-approximation one; the `PowerScanEngine`/AIDL
null-vs-empty fix for the empty-vs-unreadable directory ambiguity; the real notification
small-icon; the unit test suite; real launcher icon; and scan execution moved into
ScanForegroundService's own scope — which sat on top of earlier build-config fixes
(proguard-rules.pro, README/gradlew mismatch) and v0.8 + notification wiring before that.

This document exists so anyone (human or AI) picking up this project can see exactly what's
real, what's stubbed, and what's actively broken — without re-reading the whole codebase or
guessing from commit messages.

> **If you change this project, you must update this document in the same commit — see §7 at
> the bottom for exactly what's expected. This isn't a one-time write-up; it's a living log
> everyone maintains.**

---

## 1. What this app is

Native Android storage cleaner (Kotlin, Jetpack Compose, coroutines). Two scan modes:

- **Standard Scan** — legitimate Android APIs only (no special integration required).
- **Power Scan** — requires [Shizuku](https://github.com/RikkaApps/Shizuku) running and
  authorized. Non-root: gets privileged reach via a UID Shizuku launches a helper process under
  (shell UID or root, depending on how the user set up Shizuku), not via any exploit.

Hard requirement carried through the whole build: **no fake data, ever**. Every number shown
(storage sizes, scan progress, Shizuku status, cleanup results) must come from a real API call
made against the real device at that moment. Several design choices below exist specifically to
honor this (e.g. Power Scan falling back to standard-only results rather than pretending a
privileged scan completed if the binder dies).

---

## 2. What's actually built and working

### Core scanning
- **`ScannerEngine`** — coroutine-based, cancellable, streaming directory walker. Symlink-cycle
  guard via canonical-path tracking. `SecurityException` → Protected, `IOException`/disappeared
  files → Skipped. Real byte accounting. Progress emitted as a `SharedFlow`.
- **`StandardRootDiscovery`** — real roots only: app-internal/external files+cache dirs and OBB
  always; full shared-storage volumes *only* when `Environment.isExternalStorageManager()` is
  live-checked and true.
- **`PowerScanEngine`** — walks via IPC round trips through the Shizuku-hosted service (not
  local `File` calls — that's the actual mechanism that gives it reach beyond the app sandbox).
  Probes `Android/data` and `Android/obb` via a real `exists()` call and only scans what's
  confirmed reachable on that specific device.

### Shizuku integration
- **`ShizukuStateManager`** — computes one of 4 states live every time (`UNAVAILABLE`,
  `INSTALLED_SERVICE_STOPPED`, `RUNNING_UNAUTHORIZED`, `RUNNING_AUTHORIZED`), never cached.
  Binder-death listener drops state to `UNAVAILABLE` immediately.
- **`IPrivilegedFileService.aidl` + `PrivilegedFileService` + `ShizukuIpcClient`** — the current,
  non-deprecated Shizuku pattern. `Shizuku.newProcess` (arbitrary shell exec) was removed from
  the Shizuku API for security reasons a while back; the supported approach now is a bound AIDL
  `UserService` that Shizuku launches in a separate process under its own privileged UID. That's
  what's implemented here.

### Detectors (all real, no name-string-matching shortcuts)
- **`CacheDetector`** — only flags files under directories the app actually owns.
- **`LeftoverDetector`** — live `PackageManager` comparison against package-id-shaped directory
  names under `Android/data`/`Android/obb`. Degrades to "no data" (doesn't flag anything) if the
  package query is denied/filtered, rather than guessing.
- **`DuplicateDetector`** — 4-stage: size → filename → SHA-256 partial sample → full SHA-256.
  Only hashes survivors of each cheaper stage. Never auto-deletes the "original," only
  recommends one (shortest path, kept unselected by default).
- **`LargeFileDetector`** — configurable threshold, always classified `REVIEW_RECOMMENDED` (not
  `SAFE`) — size alone is never grounds for a deletion suggestion.
- **`OldApkDetector` / `ThumbnailDetector` / `EmptyDirectoryDetector` / `UnusedDownloadDetector`**
  — small, conservative, single-purpose rules.
- **`DetectorPipeline`** — composes the per-file ones and backs `ScannerEngine.classify()`.

### Cleanup
- **`CleanupEngine`** — re-verifies every item **at delete time**, not scan time: existence
  re-checked, directory contents re-checked for drift since the scan ran. `PROTECTED`/`UNKNOWN`
  classifications are hard-blocked from deletion no matter what got selected. Routes to local
  `File.delete()` or the Shizuku IPC client depending on which scan produced the candidate.
  Reports real per-item outcomes (`Deleted`/`Failed`/`Protected`/`AlreadyGone`) — never a single
  boolean success flag.

### Permissions
- **`PermissionManager`** — live state, never cached: `READ_MEDIA_IMAGES/VIDEO/AUDIO` on API 33+,
  legacy `READ/WRITE_EXTERNAL_STORAGE` on ≤29, separate live check of
  `Environment.isExternalStorageManager()` for `MANAGE_EXTERNAL_STORAGE` on 30+.
- Dashboard hard-gates Standard Scan behind a real permission request if media permission is
  missing; soft "optional upgrade" card for All Files Access.

### Settings
- **`SettingsRepository`** — DataStore-backed. Large-file threshold and duplicate-detection
  toggle are **wired into actual scan behavior** (`MainViewModel.withDuplicatesAndLargeFiles`
  reads live settings values on every scan completion) — not decorative switches.

### Capability report screen
- **`CapabilityReport` / `CapabilityReportGenerator`** — computes every field live: Android
  version + SDK int, manufacturer/model (`Build.*`), storage permission state (reuses
  `PermissionManager`), Shizuku state (reuses `ShizukuStateManager`'s state), accessible roots
  (reuses `StandardRootDiscovery`), and a derived "unsupported operations on this device" list
  built from that same live state — never a hardcoded list.
  - **Protected paths are intentionally `null`, not an empty list, until a scan has actually
    run.** Protected paths aren't knowable ahead of walking a location, so an empty list would
    wrongly imply "confirmed zero protected paths" when the truth is "we haven't checked yet."
    `MainViewModel` tracks `_lastScanSummary` separately from `ScanUiState` specifically so this
    section stays accurate even after the user navigates away from the Results screen.
- **`CapabilityReportScreen`** — snapshots the report once when opened (a diagnostic report
  should read as one consistent point-in-time view, not shift mid-read as unrelated state
  changes elsewhere in the app), with an explicit Refresh action for re-checking. Reachable via
  "View full capability report" on the Settings screen.

### Bounded-concurrency scanning
- **`ScannerEngine` rewritten** — was purely sequential recursion; now launches subdirectory
  walks as concurrent children gated by a `Semaphore` (default 4 permits) so in-flight directory
  reads across the whole walk never exceed that bound. The permit is held only for the
  `listFiles()` I/O call itself, not the per-entry processing after — no reason to serialize
  CPU-bound classification work.
  - **This required moving every piece of shared mutable state from plain collections to
    concurrency-safe ones**: `protectedPaths`/`skippedPaths` are now
    `Collections.synchronizedList`, `visitedCanonicalPaths` is `ConcurrentHashMap.newKeySet()`
    instead of a plain `HashSet`. The old sequential version's plain `mutableListOf()` would have
    been a real data race the moment multiple coroutines wrote to it — this wasn't optional once
    concurrency was introduced.
  - `maxConcurrentDirectories` deliberately modest (4) by default: directory listing is I/O-bound
    on flash storage, and too much parallelism can make things *slower* on lower-end devices, not
    faster — this isn't a "bigger number is always better" knob.

### Scan history persistence
- **`ScanHistoryRepository`** — DataStore-backed (same pattern as `SettingsRepository`), stores
  up to 50 entries (metadata only — timestamp, mode, byte/file/dir counts, potential-cleanup
  total; deliberately no file paths or candidate lists, kept minimal on principle even though
  this never leaves the device either way). Real `clearHistory()` that actually removes stored
  data, not a UI-only reset.
- Wired into `MainViewModel`: every completed scan (Standard or Power) calls `recordScan()`.
  Settings screen's "Clear scan history" button (previously non-functional/absent) now shows a
  real count and does a real clear behind a confirm dialog.

### Real cleanup process-kill resilience
- **`CleanupWorker`** (`cleanup/work/`) — a `CoroutineWorker` that runs the real deletion pass,
  one item at a time, persisting each item's outcome the instant it happens via
  **`CleanupStateRepository`** (DataStore-backed, keyed by a per-run UUID). Scoped deliberately
  to cleanup only, not scan: a scan is read-only and idempotent, so restarting it from scratch
  after a process kill is the honest, correct behavior (see the note under "Honest limitation"
  below) — cleanup is destructive, so losing track of what already happened to 40 of 100 deleted
  files is a real problem a scan restart isn't.
- Resilience is two independent mechanisms working together: (1) WorkManager persists the
  enqueued work request to its own on-disk DB, so the OS reruns it — a fresh Worker instance,
  quite possibly in a fresh process — even after total process death; (2) the per-item ledger
  means that fresh instance knows exactly which paths are already accounted for and only
  processes the remainder, rather than re-running the whole original selection.
- **`CleanupEngine.deleteOne()`** made public (was private, only reachable via the batch
  `cleanup()`) so the Worker can persist each outcome immediately rather than only after an
  entire batch finishes.
- **`CleanupCandidateCodec`** — compact delimited-string serialization of the selected
  `ScanCandidate` list for WorkManager's `Data` payload (same style as the AIDL layer's
  `"field|field"` lines, no JSON dependency pulled in for one small payload). `encode()` returns
  null — not a silent truncation — if the result would exceed a conservative ~9KB budget under
  `Data`'s real ~10KB platform limit, so **`MainViewModel.confirmCleanup()`** can fall back to
  running the cleanup directly in its own coroutine (old behavior, no process-kill resilience)
  for the rare oversized-selection case instead of dropping items.
- **`MainViewModel`** — `confirmCleanup()` now enqueues unique WorkManager work
  (`ExistingWorkPolicy.KEEP`, so a confirm tap can't clobber a run still being resumed) instead of
  launching in `viewModelScope`. `resumeInFlightCleanupIfAny()` runs once from `init {}`: if
  `CleanupStateRepository`'s persisted active-run-id is non-null (set right before enqueueing,
  cleared only once the UI has actually shown that run's result), it reconnects to the unique
  work by name and reports whatever WorkManager says its real current state is — this is the
  actual demonstration that survival works: a brand-new ViewModel instance, in a brand-new
  process, correctly reports a cleanup it never itself ran any part of.
- **Honest limitation, unchanged in spirit from the scan-lifecycle fix earlier:** this does not
  attempt to make *scan* survive a process kill with resumable mid-walk state. `ScannerEngine`'s
  streaming walk holds file-list state that's explicitly documented (see the performance/scale
  caveats below) as not built to be held in memory at unlimited scale, let alone serialized and
  resumed — persisting *that* would be a much larger, more fragile change for comparatively
  little benefit, since a scan is safe to just re-run in full after a kill. If mid-walk scan
  resumption is ever wanted, it would need a fundamentally different (chunked/checkpointed)
  walker design, not an extension of this cleanup mechanism.

### Real ScanForegroundService
- **`ScanForegroundService`** — actually implemented now (previously declared in the manifest
  with no class behind it — see the "FIXED" entry below for that history). Binds from
  `MainViewModel`, observes the *same* `ScannerEngine.progress` `SharedFlow` the in-app
  `ScanScreen` reads (not a separate estimate), and keeps a live notification updated with real
  file/byte counts. Uses `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` on API 29+ per Android's
  foreground-service-type requirements.
  - Binding failure (e.g. OS background-start restrictions) degrades gracefully — the scan still
    runs and updates in-app UI regardless, it just won't have a system notification for that run.

### Scan execution moved into ScanForegroundService's own scope
- **`ScanForegroundService.executeInServiceScope`** — new. Runs the actual scan `suspend` work
  inside the service's `serviceScope` (a `SupervisorJob`-backed `CoroutineScope` alive from
  `onCreate` to `onDestroy`) and returns a `Deferred` the caller awaits. Replaces the previous
  arrangement where the scan coroutine ran in `MainViewModel.viewModelScope`.
- **`MainViewModel.runScanInServiceScopeOrHere`** — waits up to 2s for the foreground service to
  finish binding (`CompletableDeferred<ScanForegroundService>`, reset per scan), then routes the
  scan work through `executeInServiceScope`. If binding doesn't complete in time (e.g. OS
  background-start restrictions rejected the service — the same failure mode already handled
  elsewhere), falls back to running the work directly in `viewModelScope`, preserving the
  existing "binding failure degrades gracefully" guarantee rather than blocking the scan on it.
- Both `startStandardScan()` and `startPowerScan()` now route their scan work (including, for
  Power Scan, the whole standard→discover-privileged-roots→privileged-scan→merge sequence as one
  unit) through this helper. Progress collection (`scannerEngine.progress` /
  `powerScanEngine.progress`) deliberately stays in `viewModelScope` regardless — those are hot
  `SharedFlow`s on the engine instances themselves, independent of whichever scope calls `.scan()`,
  so only the *execution* needed to move, not the UI observation.
- `cancelScan()` now cancels both `scanJob` (viewModelScope, drives UI state) **and**
  `serviceScanDeferred` (the actual work, tracked separately since it lives in a different
  `CoroutineScope` that `scanJob.cancel()` alone would not reach).
- **Honest limitation, unchanged from before:** this fixes the scan being tied to the
  ViewModel/Activity lifecycle (config changes, `onCleared()`, backgrounding while the process
  survives). It does **not** give true whole-*process*-kill survival — if the OS kills the entire
  process, this in-memory coroutine dies with it regardless of which scope owned it. Real
  process-death survival needs externally persisted, resumable scan state (e.g. WorkManager),
  which is materially more work than moving execution into an already-alive service's scope; see
  §5.

### Unit test suite
- **`ScannerEngineTest`** (`app/src/test/.../scanner/`) — exercises real files on a JVM temp
  directory rather than mocking `java.io.File`, since traversal is pure filesystem logic. Covers:
  file/directory/byte accounting, `collectFiles` toggling, symlink-cycle guarding (skips itself
  gracefully in sandboxes without symlink support), unreadable-directory → `PROTECTED`
  classification (verifies the restriction actually took rather than trusting
  `setReadable()`'s return value alone — important since JVMs running as root in some CI/sandbox
  environments bypass Unix permission bits entirely), cooperative cancellation, and — the
  regression test that actually matters most given the concurrency rewrite — a 40-directory
  concurrent walk verifying zero lost files/bytes and zero duplicate entries under the
  semaphore-bounded concurrent walker, which is exactly the scenario a plain (non-synchronized)
  collection would have been likely to corrupt.
- **`DuplicateDetectorTest`** (`app/src/test/.../detector/`) — covers each stage of the
  size→filename→partial-hash→full-hash pipeline in isolation: different sizes never grouped,
  same-size-different-content excluded, content identical for the first 4096 bytes (the partial
  sample size) but differing after it correctly excluded (the actual reason the detector doesn't
  trust the partial sample alone), empty files never flagged, shortest-path keep recommendation,
  3+ file groups, filenames-differ-but-content-matches (small-group filename-stage bypass), mixed
  groups not cross-contaminating unrelated same-size files, and a file deleted mid-detection
  being excluded rather than throwing.
- Neither suite touches `Context`, `PackageManager`, or any other Android framework class —
  that's why `ScannerEngine` takes a nullable `DetectorPipeline` and `DuplicateDetector` is a
  pure `object` operating on `java.io.File`. Instrumentation tests (real device/emulator) are
  still not written; see §5.

### Real launcher icon
- Replaced the placeholder ring glyph with an actual designed adaptive icon: a storage-drive
  shape (accent blue, matching `SweepAccent` in `ui/theme/Color.kt`) with a data-row detail,
  crossed by a green "sweep" stroke (reusing `StatusReady`'s green rather than inventing a new
  hue, so the icon's "cleaned" color ties back to the same success color used for the Shizuku
  status chip elsewhere in the app) with small debris dots trailing off the swept corner.
  Background changed from a flat fill to a subtle diagonal gradient for the same reason.
- Added `ic_launcher_monochrome.xml` and wired it into both `mipmap-anydpi-v26` adaptive-icon
  XMLs via `<monochrome>`, so Android 13+ themed ("Material You") icons get a real single-color
  silhouette instead of falling back to whatever the OS derives from the full-color version.

### App Manager, Cache Manager, Orphan Scanner, APK Manager, Storage Tools ("Piece 1-4")
Added in a separate session (commits `42fadc3`..`ee75189`, HANDOFF.md not properly maintained
during it — see the §4 entry on that). Verified by reading the actual code, not just trusting
the commit summary; two real bugs found there are documented and fixed in §4.

- **App Manager** (`AppManagerScreen.kt`, `appmanager/AppModels.kt`) — lists installed apps via
  `PackageManager`, real per-app data/cache bytes via `StorageStatsManager` (gated on actual
  Usage Access grant — see the §4 fix, since the original gating check was broken). Uninstall
  goes through the real system `ACTION_DELETE` intent (`MainActivity`'s `uninstallLauncher`), and
  — this is the "Revo-style" part — only triggers the leftover-cleanup flow after verifying via
  `PackageManager` that the package is actually gone, not just that the uninstall dialog closed.
- **Leftover scan** (`AppRepository.findLeftovers`) — checks `Android/data/<pkg>`,
  `Android/obb/<pkg>`, and a conservative name-fuzzy-match across common user folders
  (Documents/Download/Pictures/Movies/Music — never a whole-storage fuzzy walk). As of the §4
  fix, the two Android/data|obb checks now fall back to Shizuku's privileged IPC
  (`ShizukuIpcClient.exists`/`recursiveSize`) when plain `File` access can't confirm something,
  and honestly report `Confidence.UNVERIFIABLE` (not silent omission) when neither channel can
  determine the truth.
- **App Cache Manager** (`CacheRepository`, `CacheManagerScreen`) — real cache bytes via
  `StorageStatsManager` (no Shizuku needed for *reading* — that's a normal privileged system
  API). *Clearing* cache for another app does need Shizuku: routes through
  `IPrivilegedFileService.clearPackageCache` → `pm clear --cache-only <package>` run in the
  Shizuku-spawned process (package name validated against a package-id regex before being passed
  as a separate `ProcessBuilder` argument — not shell-string-interpolated, so no injection risk
  even without the regex, but the regex is kept as defense-in-depth matching project convention).
- **Orphan Scanner** (`OrphanRepository`, `OrphanManagerScreen`) — finds package-shaped directory
  names under `Android/data`/`Android/obb` whose package isn't currently installed. As of the §4
  fix: discovery falls back to privileged listing when plain top-level listing fails, and sizing
  falls back to `ShizukuIpcClient.recursiveSize` when a plain-File walk suspiciously reports
  exactly 0 bytes for a real directory (ambiguous zero — see §4 for why that mattered). Deletion
  (`OrphanDeletion`) routes through `ipcClient.deletePath` for entries only reachable that way;
  `PrivilegedFileService.deletePath` was also fixed to actually recurse into non-empty
  directories (`File.delete()` alone only succeeds on empty ones — the only real caller of this
  for a directory is deleting a non-empty orphaned data tree, so without recursion it could never
  actually succeed at its one real job).
- **APK Manager** (`apk/ApkModels.kt`, `ApkManagerScreen.kt`) — scans Downloads/Documents/shared
  storage root (bounded depth 5) for `.apk`/`.apks`/`.apkm`/`.xapk` files, reads real package
  metadata from all four formats (`.apk` via `PackageManager.getPackageArchiveInfo`;
  `.apkm`/`.xapk` via their embedded JSON manifest; `.apks` via extracting and reading its base
  APK entry — see §4 for the parsing-added fix and why each format needed a different strategy).
  Share/open/extract use a real `FileProvider` (`res/xml/file_paths.xml`, manifest provider
  entry) rather than a raw `file://` URI, which would fail on modern Android's
  `FileUriExposedException`, and show a real `Toast` on failure (see §4) rather than failing
  silently. Delete is scoped-and-validated (must be under external storage root, must have a
  known installer extension, must be a file) before removal.
- **Storage Tools** (`storage/StorageModels.kt`, `StorageToolsScreen.kt`) — Storage Explorer
  (root-scoped directory navigation), Storage Breakdown (category totals: DCIM/Download/
  Pictures/Movies/Music/Documents/Android/Other), Large File Manager (100MB/500MB/1GB/2GB
  thresholds, review-only — no bulk delete from this screen, matching the spec's "large personal
  files are review items, not junk" principle). Operates within the normal shared-storage tree
  (legitimate territory for plain `File` API with All Files Access), *except* the "Android"
  category total in `categorySizes()`, which walks into `Android/data`/`Android/obb` the same
  way `OrphanRepository` used to — same silent-undercount risk, smaller stakes (one line item in
  a breakdown rather than a primary leftover-cleanup feature), not yet fixed — see §5.
- Manifest gained `PACKAGE_USAGE_STATS` (for per-app storage stats — see the §4 fix for why the
  original availability check for this was broken) and a `FileProvider` declaration.

### UI flow
Core scan flow: `Dashboard → Scanning → Results (category breakdown + spec-required overview
stats) → Review (sort/filter/select, explicit "about to remove X files, recover ~Y" confirm
dialog) → Cleaning → CleanupResult (real per-item outcomes)`. Settings reachable via gear icon on
Dashboard; Capability report reachable from Settings.

Piece 1-4 additions, all reachable from Dashboard: App Manager (→ Leftovers screen after a
verified uninstall), Cache Manager, Orphan Scanner, APK Manager, Storage Tools.

### CI
- **`.github/workflows/build.yml`** — builds a debug APK on every push via
  `gradle/actions/setup-gradle` (deliberately not using `./gradlew`, since the
  `gradle-wrapper.jar` binary can't be generated without downloading it — no internet access to
  Gradle's distribution servers during development).
- Repo: `kazucute64-max/Cleaner`. User builds/pushes from **Termux on Android**, not a desktop —
  worth remembering when giving instructions (exact copy-paste commands, no assumption of a
  desktop shell).

---

## 3. CI failures already hit and fixed (don't reintroduce these)

1. **`org.jetbrains.kotlin.plugin.compose` plugin not found** — that plugin only exists for
   Kotlin 2.0+. Project is on Kotlin 1.9.24. Fix: removed the plugin, use the classic
   `composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }` block in `app/build.gradle.kts`
   instead.
2. **`compileDebugAidl` failed — AndroidX dependencies detected but `android.useAndroidX` not
   enabled`** — there was no `gradle.properties` at all. Added one with
   `android.useAndroidX=true`, `android.nonTransitiveRClass=true`,
   `android.suppressUnsupportedCompileSdk=35`, and JVM heap args.
3. **`processDebugResources` failed — `mipmap/ic_launcher` not found`** — manifest referenced a
   launcher icon that was never created. Added a real adaptive icon (`mipmap-anydpi-v26` XML +
   vector background/foreground drawables — no PNG needed since `minSdk=26` already meets the
   adaptive-icon minimum).

As of the last confirmed build, the project got past resource linking (further than the first
three failures). Notification-permission-wiring and the dead-service-declaration fixes above were
made in this session, after that confirmed build — **not yet pushed/tested in CI.**

---

## 4. Known bugs / incomplete work (be careful here)

### ✅ Silent exception swallowing in APK share/open/extract — FIXED this session
- `MainActivity`'s `shareApk`/`extractAndShareApk`/`openApk` previously had bare
  `catch (_: Exception) {}` — a real failure (no app installed to view a package archive,
  `FileProvider` misconfiguration, the source APK having disappeared) produced no feedback at
  all; the tap just silently did nothing. Not a correctness bug (nothing false was claimed) but
  a real UX gap flagged in the previous session's re-analysis.
- Fixed: each now shows a `Toast` with the actual failure reason (or a specific
  `ActivityNotFoundException`-aware message for `openApk`, since "no app can open this" is a
  distinct, common case worth naming specifically rather than lumping into a generic error).

### ✅ `ApkModels.kt` code-style inconsistency — FIXED this session
- Previously written in a dense, single-line, uncommented style — a real departure from the
  rest of this codebase's convention of explaining *why*, flagged in the previous session's
  re-analysis. Reformatted to match: multi-line, named parameters, doc comments on every
  non-trivial function. No behavior change from this alone — see the next entry for the actual
  functional addition made alongside the reformat.

### ✅ APKS/APKM/XAPK containers never got package-identity parsing — FIXED this session
- Previously: only `.apk` files got real package metadata (via
  `PackageManager.getPackageArchiveInfo`); `.apks`/`.apkm`/`.xapk` were discovered and sized but
  `packageName`/`versionName`/`versionCode` were always null for them, so `isForUninstalledApp`
  could never be true and "old version installed" detection never applied to these formats —
  a real, previously-undocumented gap (now it's documented, and fixed).
- Fixed with format-appropriate real parsing, not a generic fallback:
  - **`.apkm`** (APKMirror's bundle format) and **`.xapk`** (APKPure's format) both embed a
    plain JSON manifest inside the zip (`info.json` and `manifest.json` respectively) with the
    package identity at the top level — read directly via `ZipFile` + `org.json.JSONObject`
    (already used elsewhere in this project, part of the Android platform SDK, no new
    dependency). Field names read defensively (`package_name` or `pname`, etc.) since neither
    format has one single official spec version across the tools that produce them.
  - **`.apks`** (Android App Bundle split installer, produced by `bundletool`) has no simple
    manifest — its table of contents is a protobuf (`toc.pb`), not worth a new dependency just
    for a package name. Instead: locate the base APK entry inside the zip (by name pattern —
    `base`-containing, falling back to the first non-`config.`-split `.apk` entry), extract it
    to a throwaway file in `context.cacheDir`, and read it exactly like a normal standalone
    `.apk` — deleted immediately after, success or failure.
  - All three parsing paths are wrapped so a malformed/unexpected container never crashes the
    scan — a parse failure just means no metadata for that one entry, same as before.

### ✅ Instrumentation tests — ADDED this session (were previously entirely absent)
- Only JVM unit tests (`ScannerEngineTest`, `DuplicateDetectorTest`, `StorageScannerTest`)
  existed before this session — none of them can exercise real `Context`/`PackageManager`/
  filesystem behavior, which several parts of this codebase genuinely depend on. Added:
  - **`StandardRootDiscoveryInstrumentedTest`** — verifies `discover()` against a real device's
    real app-owned directories (always accessible, no permission needed) and that every
    returned root is genuinely readable, not just non-null.
  - **`PermissionManagerInstrumentedTest`** — verifies `checkState()`'s `sdkInt` matches the
    real device, that `manageAllFilesGranted` is correctly `false` (not a crash, not a
    fabricated `true`) below API 30 where the underlying API doesn't exist, and that
    `runtimePermissionsToRequest()` is never empty on any supported API level.
  - **`ApkFileManagerInstrumentedTest`** — exercises `ApkFileManager.delete()`'s real safety
    gating (extension check, root-containment check) against real files written to the app's
    own external files dir (writable with no runtime permission, and a genuine subpath of
    external storage root, so the containment check runs its real logic): confirms an in-scope
    `.apk` file is actually deleted, a wrong-extension file is refused and left untouched, and a
    never-created path correctly reports failure rather than a false success.
  - Added `androidx.test:rules:1.6.1` as an `androidTestImplementation` dependency (wasn't
    present before — needed for real-device test infrastructure beyond plain JUnit4/Espresso).
  - **Not covered yet**: `ScanForegroundService`'s actual Android service lifecycle (binding,
    foreground promotion) and `PowerScanEngine`'s Shizuku IPC path — both need either a running
    Shizuku instance or a more involved test harness than this session had scope for. Still a
    real gap; see §5.

### ✅ `HANDOFF.md` was gutted by a prior session — RESTORED this session
- Commits `42fadc3`..`ee75189` (the "Piece 1-4" work — see §2) replaced this entire document,
  ~500 lines of maintained architecture rationale and bug/CI history, with a 29-line stub
  ("Completed through Piece 4" + a build-status note). This directly violates §7 below, which
  this same project established specifically to prevent exactly this.
- Fixed: restored the full prior content from git history (commit `a36ace2`, the last version
  before the gutting), folded the stub's legitimate content (the Piece 1-4 feature list) into
  §2 properly instead of just reverting and losing that work's description, and added this
  entry plus the two functional-bug entries below. Nothing from the stub was discarded — it
  only ever described what's now written up properly in §2.
- **If you're an AI continuing this project: do not replace this file's content with a shorter
  summary, ever, even if it feels like the "current" one is more relevant. Append/extend. §7
  is not a suggestion.**

### ✅ Leftover/orphan detection for `Android/data`/`Android/obb` had no Shizuku fallback — FIXED this session
- Found while re-analyzing the "Piece 1-4" App Manager work: `AppRepository.findLeftovers()` and
  `OrphanRepository.scan()`/`sizeOf()` used only plain `File` I/O against `Android/data` and
  `Android/obb` — the exact two paths `PowerScanEngine` already needed a whole privileged-IPC
  layer for, because plain `File` access to *other apps'* subdirectories there is broadly
  restricted on API 30+ even with All Files Access granted. `CleanupRepositories.kt` importing
  `ShizukuIpcClient` and never calling it was the telltale sign this was left unfinished.
- Practical effect before the fix: real leftover/orphan data would silently read as "doesn't
  exist" (dropped from leftover results with zero indication) or "0 bytes" (orphan directory
  sizes undercounted via a caught-and-swallowed `SecurityException`) on most modern devices —
  exactly the kind of fabricated-looking value this project's core principle exists to prevent,
  and specifically for the single most valuable case this feature set exists for (actual
  post-uninstall `Android/data` cleanup).
- Fixed: added `ShizukuIpcClient.recursiveSize(path)` (sums real sizes via privileged IPC
  listing, reusing the same mechanism `PowerScanEngine` already established, with a real
  `canonicalPath()`-based cycle guard). `findLeftovers`/`OrphanRepository.scan` are now suspend
  functions taking an optional `ShizukuIpcClient` (only passed when `RUNNING_AUTHORIZED`, per
  that class's own documented contract): plain `File` check first, privileged fallback when
  plain can't confirm and Shizuku is available, and an honest `Confidence.UNVERIFIABLE` /
  `sizeBytes = -1` result (never silently omitted, never a fake zero) when neither channel can
  determine the truth. `LeftoverItem`/`OrphanedDirectory` gained a `requiresShizuku` flag so
  deletion routes through the same privileged channel that found the entry — `OrphanDeletion`
  gained `deletePrivileged()`, and `MainViewModel.deleteLeftover`/`deleteOrphan` branch on it.
- This also surfaced and fixed a second, smaller bug it depended on:
  **`PrivilegedFileService.deletePath()` only ever succeeded on empty directories** (plain
  `File.delete()` semantics) — the only real caller needing directory deletion is a non-empty
  orphaned data tree, so without recursion this could never actually succeed at its one real
  job. Now uses `deleteRecursively()` for directories.
- And a third: **`Long.toHumanBytes()` rendered any negative value as `"0 B"`** — which is
  exactly the fabricated-zero problem this whole fix is about, applied to the *display* layer.
  Now negative renders as `"Unknown"`, consistent with the `-1`-means-unknown convention
  `ShizukuIpcClient.statSize()` already established elsewhere. This affects all ~40 existing
  call sites of `toHumanBytes()` — none of them previously passed negative values (verified),
  so this is a pure bug fix with no behavior change for any existing caller.

### ✅ `AppRepository.storageStatsAvailable()` could never detect a missing Usage Access grant — FIXED this session
- Found in the same re-analysis pass. The old implementation self-tested by querying
  `StorageStatsManager` for the app's *own* package — which always succeeds regardless of
  Usage Access grant status, because querying your own stats never requires that permission.
  The actual operation this was meant to gate — `queryStatsForPackage` for *other* installed
  apps, used throughout the App Manager screen — genuinely does require `PACKAGE_USAGE_STATS`
  (Usage Access), so the check could never correctly detect it was missing.
- Practical effect before the fix: App Manager would silently show blank data/cache figures for
  every other installed app when Usage Access wasn't granted, with no prompt to fix it — even
  though a working "Open Usage Access settings" button (`openUsageAccessSettings()` in
  `MainActivity`) already existed and was wired, it would essentially never need to be shown
  since the broken check always reported "available."
- Fixed: replaced the self-query test with a direct `AppOpsManager` check of the actual granted
  app-op (`OPSTR_GET_USAGE_STATS` via `unsafeCheckOpNoThrow` on API 29+, `checkOpNoThrow` below
  that) — the same pattern Android's own `UsageStatsManager` documentation describes for
  checking this exact permission.

### ✅ `StorageModels.kt`'s "Android" category undercounting — FIXED this session
- Same root cause as the leftover/orphan fix above, flagged at the time as a smaller-stakes
  follow-up (§5 item 6) and addressed now. `categorySizes()`'s "Android" line summed a plain
  `File` walk over the whole `Android/` tree — `Android/data`/`Android/obb` subdirectories
  belonging to other apps silently contributed 0 via a caught `SecurityException`, same as the
  leftover/orphan case, just for one breakdown-screen total rather than a primary cleanup
  feature.
- Fixed with the same pattern: `categorySizes()` is now suspend and takes an optional
  `ShizukuIpcClient` (passed only when `RUNNING_AUTHORIZED`). Only `Android/data` and
  `Android/obb` get the privileged-fallback treatment — `Android/media` and anything else at
  that level is ordinary readable shared-storage territory and was never the problem, so it's
  summed the normal way, untouched. `StorageCategorySize` gained a `partial: Boolean` field:
  true when part of the category genuinely couldn't be measured (not "measured as zero"), so
  `sizeBytes` in that case is a floor, not a claimed total. `StorageToolsScreen`'s breakdown
  list now shows a `≥` prefix and an explanatory line for any partial category, rather than
  presenting an undercounted number as if it were final.

### ✅ Missing `proguard-rules.pro` — FIXED this session
- `app/build.gradle.kts`'s `release` build type referenced `proguard-rules.pro` via
  `proguardFiles(...)`, but the file didn't exist anywhere in the repo. `assembleDebug` was
  unaffected (`isMinifyEnabled = false` for debug), but `assembleRelease` would fail immediately
  on a missing-file error.
- Fixed: added `app/proguard-rules.pro` with keep rules for Shizuku's AIDL/binder classes
  (reflection + marshalling need exact names preserved), Parcelable `CREATOR` fields, and
  DataStore's internal protobuf-lite classes. Minimal on purpose — expand as real R8 stripping
  issues turn up once release builds are actually exercised.

### ✅ README build instructions didn't match reality — FIXED this session
- README said to run `./gradlew assembleDebug`, but there is no Gradle wrapper checked into this
  repo (`gradle/wrapper/` has no jar or properties file) — per §6, the dev environment has no
  network access to Gradle's distribution servers to generate one. `./gradlew` would fail for
  anyone following the README literally. CI already worked around this correctly via
  `gradle/actions/setup-gradle`, but the README never explained the discrepancy.
- Fixed: README now states the wrapper is intentionally absent and why, and gives two real paths
  — open in Android Studio (bundles its own Gradle), or run system-installed `gradle
  assembleDebug` directly (matches how CI and Termux builds actually work). Also noted the
  `proguard-rules.pro` dependency for `assembleRelease` alongside this.

### ✅ Notification permission request — FIXED this session
- Previously: `NotificationHelper` correctly checked for `POST_NOTIFICATIONS` before posting, but
  nothing in the UI ever requested that permission, so notifications would silently never fire
  on Android 13+.
- Fixed: turning the Settings notifications toggle **on** now calls
  `notificationPermissionLauncher.launch(POST_NOTIFICATIONS)` (API 33+ only) via a callback
  threaded through `SettingsScreen` → `MainActivity`. Requested at the moment of intent (toggle
  on), not preemptively at app launch.

### ✅ `ScanForegroundService` dead manifest entry — FIXED (two sessions: removed, then built for real)
- Session A: manifest declared `.scanner.ScanForegroundService` but the class never existed —
  removed the declaration rather than leave the manifest claiming something untrue.
- Session B (this session): built the real service (see §2 "Real ScanForegroundService" above)
  and restored the manifest entry now that it's backed by an actual class. Current limitation
  (scan coroutine doesn't yet run inside the service's own scope) is documented in §2, not
  hidden.

### ✅ Placeholder notification icon — FIXED this session
- Previously: `NotificationHelper.post()` and `ScanForegroundService`'s live notification both
  used `android.R.drawable.stat_sys_download` — a system stock icon — as a stopgap small icon.
- Fixed: added `res/drawable/ic_notification.xml`, a real 24dp vector asset (drive outline +
  diagonal sweep stroke, same motif and direction as the launcher icon's drive+sweep glyph, but
  with the status-slot/data-row/debris-dot detail dropped since it wouldn't survive legibly at
  notification-icon size). Drawn with `android:tint="#FFFFFF"` and plain black `strokeColor`
  paths — the actual color is irrelevant since Android renders status-bar icons as a flat
  alpha-mask silhouette and re-tints them itself regardless of what the drawable specifies; the
  white tint is there only so the asset previews correctly, not because it affects the rendered
  result. Both `NotificationHelper.post()` and `ScanForegroundService`'s builder now reference
  `R.drawable.ic_notification` instead of the stock system glyph.

### ✅ `PowerScanEngine` empty-vs-unreadable ambiguity — FIXED this session
- Previously: `IPrivilegedFileService.listDirectory()` returned an empty array for both a
  genuinely empty directory and an unreadable one (SecurityException), so `PowerScanEngine`
  disambiguated with a follow-up `exists()` IPC round trip whenever it saw zero entries.
- Fixed: changed the AIDL contract so `listDirectory()` returns **null** when the path can't be
  read at shell UID, and an empty (zero-length, non-null) array when it's genuinely empty.
  `PrivilegedFileService` now returns `null` directly on `SecurityException` (or when
  `File.listFiles()` itself returns null) instead of `emptyArray()`. `ShizukuIpcClient` and
  `PowerScanEngine` propagate/consume that null explicitly. This removes the extra
  IPC round trip entirely for this case — better than the "add a dedicated `readable: Boolean`
  field" idea floated below, since it needs no new field or second call, just a real signal on
  the return type that was already there.

### ✅ `PowerScanEngine` cycle guard is string-based, not canonical-path-based — FIXED this session
- Previously: `ScannerEngine` (local walk) used real `File.canonicalPath` for cycle detection,
  but `PowerScanEngine` (privileged IPC walk) couldn't call `canonicalPath()` on a remote path,
  so it fell back to a normalized string set — a real approximation, not equivalent protection
  (e.g. two different-looking paths that are actually the same directory via a symlink would
  not have been caught).
- Fixed: added `canonicalPath(String path)` to `IPrivilegedFileService` — resolves the real
  canonical path at shell UID via `File(path).canonicalPath`, returning null only if resolution
  itself fails (SecurityException/IOException/vanished path), which is *not* the same as "no
  cycle risk" — `PowerScanEngine.walk()` falls back to the normalized string for just that one
  path when resolution fails, rather than skipping the guard. `ShizukuIpcClient.canonicalPath()`
  exposes it as a suspend function. **Honest cost:** this adds one IPC round trip per directory
  visited (on top of the `listDirectory` call already made for that directory) — a real,
  deliberate trade of Power Scan throughput for correctness, consistent with the project's
  no-fake-data principle over raw speed. Bumped `UserServiceArgs.version()` from 1 to 2 since the
  AIDL contract changed (Shizuku uses this to know a previously-bound service definition is
  stale and needs relaunching).

### 🟢 Performance/scale caveats (documented, not bugs, but worth knowing)
- `ScannerEngine.scan(collectFiles = true)` (used for duplicate/large-file detection) holds a
  `File` reference per scanned file in memory. Fine for moderate trees, not built for millions of
  files — a streaming duplicate-detection redesign is still on the roadmap (see below).
- `PowerScanEngine` pays two real cross-process IPC calls per directory now (`canonicalPath` for
  the cycle guard, then `listDirectory`) — much more expensive than
  local `File` I/O. It's scoped to the two known privileged root candidates
  (`Android/data`, `Android/obb`), not meant to walk millions of files the way Standard Scan can.

---

## 5. What's NOT built yet (in rough priority order)

1. ~~Fix the notification-permission wiring gap~~ — done, see §4.
2. ~~Capability report screen~~ — done, see §2. Note: it only knows protected paths after a scan
   has run this session (see the `null`-vs-empty-list design note in §2) — that's intentional,
   not a gap.
3. ~~Real `ScanForegroundService`~~ — done, see §2.
4. ~~Scan history persistence~~ — done, see §2 (`ScanHistoryRepository`).
5. ~~Bounded-concurrency scanning~~ — done, see §2 (`ScannerEngine` semaphore-bounded rewrite).
6. ~~Unit tests~~ — `ScannerEngineTest` and `DuplicateDetectorTest` done earlier, `StorageScannerTest`
   done alongside it. **Instrumentation tests** — done this session, see §4: `StandardRootDiscoveryInstrumentedTest`,
   `PermissionManagerInstrumentedTest`, `ApkFileManagerInstrumentedTest`. Not everything is
   covered yet — see the "Not covered yet" note in that §4 entry (`ScanForegroundService`
   lifecycle, `PowerScanEngine`'s Shizuku IPC path).
7. ~~Real launcher icon design~~ — done this session, see §2.
8. ~~Move scan execution into `ScanForegroundService`'s own scope~~ — done this session, see §2
   and the "Honest limitation" note there for exactly what this does and doesn't cover.

**What's still genuinely open**, in rough priority order:

1. **`ScanForegroundService`/`PowerScanEngine` instrumentation coverage** — the one piece of
   item 6 above still missing. Needs either a running Shizuku instance in the test environment
   or a more involved test harness (a fake `IPrivilegedFileService` implementation bound in
   place of the real one) than this session had scope for.
2. ~~Placeholder notification small-icon~~ — done this session, see §4 (`ic_notification.xml`).
3. ~~`PowerScanEngine` empty-vs-unreadable ambiguity~~ — done this session, see §4 (null-vs-empty
   AIDL contract). ~~Cycle-guard approximation~~ — also done this session, see §4
   (`canonicalPath()` added to the AIDL contract, real cycle detection over IPC).
4. ~~True whole-process-kill survival~~ — done this session **for cleanup**, see §2 ("Real
   cleanup process-kill resilience") — WorkManager-backed, with a per-item persisted ledger.
   Deliberately NOT extended to scan; see the "Honest limitation" note in that same §2 entry for
   why a scan restart-on-relaunch is the correct behavior there rather than a remaining gap.
5. ~~APKS/APKM/XAPK package-identity parsing~~ — done this session, see §4.
6. ~~`StorageModels.kt`'s "Android" category undercounting~~ — done this session, see §4.
7. ~~`ApkModels.kt`'s code style~~ — done this session (reformatted alongside the parsing fix,
   see §4).
8. ~~Silent exception swallowing in `MainActivity`'s APK share/open/extract helpers~~ — done
   this session, see §4.

**Everything from the last several sessions' roadmaps is now checked off.** The only genuinely
open item is instrumentation coverage for `ScanForegroundService`/`PowerScanEngine` (item 1
above). Whoever picks this up next: confirm a green CI build is actually recorded (§3's stale
note about this still hasn't been resolved by anyone pushing and checking), then it may be worth
a fresh, broader pass over the whole app rather than working from a stale checklist — the
easy/known items are done.

---

## 6. Environment/workflow notes for whoever continues this

- User works from **Termux on an Android phone**, not a desktop. No Android Studio, no local
  Gradle wrapper jar (can't be generated without network access to Gradle's distribution
  servers — CI uses `gradle/actions/setup-gradle` specifically to avoid needing `gradlew`).
- GitHub repo: `kazucute64-max/Cleaner`. Auth is via Personal Access Token (already walked
  through once — GitHub blocks plain-password HTTPS auth now).
- When giving instructions, exact copy-pasteable shell commands work best — this has been the
  effective pattern throughout the build so far.

## 7. Updating this document (required, not optional)

Whoever touches this project next — human or AI — **must update this file in the same commit**
as their change, with the same level of detail as the entries above. This doc is only useful if
it stays current. Specifically:

- **If you build something new:** add it to section 2 ("What's actually built and working"),
  under the right subsection (or a new one). State what it does, how it works, and why any
  non-obvious design choice was made — not just "added X screen." Follow the existing style:
  short bolded component name, then the substance.
- **If you fix a bug:** move it from section 4 into a "✅ FIXED this session" entry (see the
  pattern already used for the notification-permission and dead-service fixes) — don't just
  delete the old entry, show what was wrong and what changed, so the fix is traceable.
- **If you find a new bug or gap you didn't fix:** add it to section 4 with the same rigor as the
  existing entries — what's broken, what the practical effect is, and (if you know it) what the
  fix would look like. A vague "notifications might not work right" is not acceptable; be as
  specific as "POST_NOTIFICATIONS is checked before posting but never requested anywhere in the
  UI, so it silently never fires on API 33+."
- **If you change an architectural decision** (e.g. swap how Power Scan gets privileged access,
  change the detector pipeline, alter the cleanup safety model) — explain what it replaces and
  why, not just what it is now. Future readers need to know a decision was deliberate and
  reconsidered, not accidental.
- **Update section 5** ("What's NOT built yet") to remove what you finished and add anything new
  you discovered is still missing.
- **If a CI build fails and you fix it,** add it to section 3 in the same format as the existing
  three entries — the exact error, the root cause, and the fix — so the same mistake never gets
  reintroduced by someone unaware it already happened once.
- **Don't summarize away detail to keep this file short.** A long, precise HANDOFF.md that lets
  someone start cold is worth more than a tidy one that makes them re-derive context from the
  code. If a section is getting unwieldy, split it into a dedicated doc and link it — don't
  compress it into vagueness.

The test for any entry you add: could someone with zero prior context on this project read it
and know exactly what changed, why, and what to do next — without opening the diff?

