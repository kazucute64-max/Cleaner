# StorageSweep

Native Android storage cleaner. Kotlin + Jetpack Compose + Coroutines, with optional Shizuku-powered Power Scan (non-root privileged IPC — Shizuku is never treated as root).

**→ See [HANDOFF.md](HANDOFF.md) for the full build history, what's done, what's not, and known bugs.** Read that first if you're picking this project up.

## Status

This is scaffolding for a production build, not a finished app. Built so far:

- `settings.gradle.kts`, root `build.gradle.kts`, `app/build.gradle.kts` — real dependency set (Shizuku API/provider, Compose, coroutines, WorkManager, DataStore)
- `AndroidManifest.xml` — version-gated storage permissions (legacy READ/WRITE for ≤29, granular READ_MEDIA_* for 33+, MANAGE_EXTERNAL_STORAGE requested explicitly not assumed), Shizuku provider declaration, foreground scan service
- `shizuku/ShizukuStateManager.kt` — live-only detection of the four required states (unavailable / installed-but-stopped / running-unauthorized / running-authorized), binder-death listener that immediately drops Power Scan eligibility, no cached state
- `scanner/ScannerEngine.kt` — coroutine-based, cancellable, streaming directory walker: symlink-cycle guard via canonical-path visited set, SecurityException → Protected, IOException → Skipped, disappearing-file handling, real byte accounting, progress as a SharedFlow

This "Status"/"Not yet built" pair is from an early point in the build and hasn't been kept
current — **HANDOFF.md §2/§5 is the accurate, up-to-date source** for what's actually built vs.
outstanding. As of the latest session: root discovery, Power Scan IPC, all detectors, the
cleanup engine, the full Compose UI flow, notifications, a JVM unit test suite
(`ScannerEngineTest`, `DuplicateDetectorTest`), and a real launcher icon all exist. What's
genuinely still open is listed in HANDOFF.md §5 (instrumentation tests, the notification
status-bar icon, two documented `PowerScanEngine` edge cases, and true whole-process-kill
survival for in-flight scans).

## Testing

```
gradle test          # JVM unit tests — ScannerEngineTest, DuplicateDetectorTest
```

No emulator/device needed for the current suite — both test classes operate on a real temp
filesystem and avoid Android framework classes entirely (see HANDOFF.md for why). Instrumentation
tests (`gradle connectedAndroidTest`, needs a device/emulator) aren't written yet.

## Build

Requires Android Studio (Ladybug+), or a system-installed Gradle + Android SDK 35 for CLI builds.

**No Gradle wrapper is checked into this repo** (`gradle/wrapper/gradle-wrapper.jar` and
`gradle-wrapper.properties` are absent) — the dev environment for this project has no network
access to Gradle's distribution servers to generate one. This means `./gradlew` will **not**
work here. Use one of:

```
# If Android Studio is available: Studio bundles its own Gradle, so opening the project
# and building from the IDE works with no extra setup.

# If building via CLI (e.g. from Termux) with system Gradle installed:
gradle assembleDebug
```

CI (`.github/workflows/build.yml`) builds the same way, via `gradle/actions/setup-gradle`
rather than `./gradlew`, for the same reason.

Debug builds work out of the box. Release builds (`gradle assembleRelease`) additionally need
`app/proguard-rules.pro` (included) since `isMinifyEnabled = true` for that build type.

The Shizuku dependency resolves from JitPack (declared in `settings.gradle.kts`). No API keys, no backend — everything is local, per the privacy requirements in the spec.
