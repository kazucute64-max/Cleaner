# StorageSweep

Native Android storage cleaner. Kotlin + Jetpack Compose + Coroutines, with optional Shizuku-powered Power Scan (non-root privileged IPC — Shizuku is never treated as root).

## Status

This is scaffolding for a production build, not a finished app. Built so far:

- `settings.gradle.kts`, root `build.gradle.kts`, `app/build.gradle.kts` — real dependency set (Shizuku API/provider, Compose, coroutines, WorkManager, DataStore)
- `AndroidManifest.xml` — version-gated storage permissions (legacy READ/WRITE for ≤29, granular READ_MEDIA_* for 33+, MANAGE_EXTERNAL_STORAGE requested explicitly not assumed), Shizuku provider declaration, foreground scan service
- `shizuku/ShizukuStateManager.kt` — live-only detection of the four required states (unavailable / installed-but-stopped / running-unauthorized / running-authorized), binder-death listener that immediately drops Power Scan eligibility, no cached state
- `scanner/ScannerEngine.kt` — coroutine-based, cancellable, streaming directory walker: symlink-cycle guard via canonical-path visited set, SecurityException → Protected, IOException → Skipped, disappearing-file handling, real byte accounting, progress as a SharedFlow

## Not yet built

- Standard Scan root discovery (MediaStore / StorageStatsManager / SAF wiring)
- Power Scan Shizuku IPC calls (`IShizukuService`-backed file operations)
- LeftoverDetector, CacheDetector, DuplicateDetector (staged: size → filename → partial hash → full hash), old-APK/thumbnail detectors
- Cleanup engine (re-check-before-delete, per-item success/failure reporting)
- Compose UI: dashboard, Shizuku status chip, scan animation, results/review screens, settings, capability report
- Notifications (scan progress / complete / cleanup complete)
- Unit + instrumentation tests

## Build

Requires Android Studio (Ladybug+) or CLI Gradle with Android SDK 35 installed.

```
./gradlew assembleDebug
```

The Shizuku dependency resolves from JitPack (declared in `settings.gradle.kts`). No API keys, no backend — everything is local, per the privacy requirements in the spec.
