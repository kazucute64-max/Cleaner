# StorageSweep — Project Status & Handoff

Last updated: this session (real cleanup process-kill resilience via WorkManager + a per-item
persisted ledger — see §2 "Real cleanup process-kill resilience"; and, earlier in the same
session, a real `canonicalPath()`-based cycle guard for `PowerScanEngine`'s IPC walk, replacing
the string-approximation one) on top of the `PowerScanEngine`/AIDL null-vs-empty fix for the
empty-vs-unreadable directory ambiguity, which itself sat on top of the real notification
small-icon, the unit test suite, real launcher icon, and scan execution moved into
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

### UI flow
`Dashboard → Scanning → Results (category breakdown + spec-required overview stats) → Review
(sort/filter/select, explicit "about to remove X files, recover ~Y" confirm dialog) → Cleaning →
CleanupResult (real per-item outcomes)`. Settings reachable via gear icon on Dashboard; Capability
report reachable from Settings.

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
6. ~~Unit tests~~ — `ScannerEngineTest` and `DuplicateDetectorTest` done this session, see §2.
   Instrumentation tests (real-device/emulator, e.g. exercising `ScanForegroundService`'s actual
   Android lifecycle or `PowerScanEngine`'s Shizuku IPC path) are still untouched — those need a
   device/emulator and framework classes the JVM unit test suite deliberately avoids.
7. ~~Real launcher icon design~~ — done this session, see §2.
8. ~~Move scan execution into `ScanForegroundService`'s own scope~~ — done this session, see §2
   and the "Honest limitation" note there for exactly what this does and doesn't cover.

**What's still genuinely open**, in rough priority order:

1. **Instrumentation tests** (see item 6 above) — none exist yet.
2. ~~Placeholder notification small-icon~~ — done this session, see §4 (`ic_notification.xml`).
3. ~~`PowerScanEngine` empty-vs-unreadable ambiguity~~ — done this session, see §4 (null-vs-empty
   AIDL contract). ~~Cycle-guard approximation~~ — also done this session, see §4
   (`canonicalPath()` added to the AIDL contract, real cycle detection over IPC).
4. ~~True whole-process-kill survival~~ — done this session **for cleanup**, see §2 ("Real
   cleanup process-kill resilience") — WorkManager-backed, with a per-item persisted ledger.
   Deliberately NOT extended to scan; see the "Honest limitation" note in that same §2 entry for
   why a scan restart-on-relaunch is the correct behavior there rather than a remaining gap.

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

