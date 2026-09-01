# Android build & test gotchas

Environment and toolchain quirks discovered while working in this module — things that
cost real time to diagnose and will cost it again for the next agent (or human) unless
they're written down here. Not project status (`docs/plans/STATUS.md`) and not design
decisions (`docs/design/android.md`, `docs/design/design.md`) — this file is purely "if
you hit this exact wall, here's why and how to get past it."

## Build toolchain

**A brand-new JDK's version string can break Gradle/Kotlin before anything compiles.**
If `./gradlew` fails immediately with something like `IllegalArgumentException:
26.0.2.1` out of `JavaVersion.parse` (Kotlin Gradle plugin), that's a version-string
parsing bug tripped by a very recent JDK release, not a project misconfiguration —
Gradle/Kotlin's parsing tends to lag brand-new JDK releases by a few months. Point
`JAVA_HOME` at a stable LTS JDK (21 worked) and retry before assuming anything else is
wrong. As of Gradle 9.5.0/AGP 9.3.2 this repo builds clean with no `JAVA_HOME` override
at all (system default JDK 26) — try a plain `./gradlew` first; only fall back to
pinning `JAVA_HOME` if you hit the parse error above. Android Studio is unaffected
either way — it uses its own bundled JDK for Gradle by default, independent of the
shell's `JAVA_HOME`.

**`:app` needs `junit-platform-launcher` declared explicitly.** Since the Gradle
8.13→9.5 bump, `:app:testDebugUnitTest` (JUnit5/Jupiter, `useJUnitPlatform()`) fails
with `Failed to load JUnit Platform... junit-platform-launcher` unless
`testRuntimeOnly(libs.junit.platform.launcher)` is declared alongside
`junit-jupiter` — Gradle stopped auto-resolving the launcher transitively somewhere
between 8.10 and 9.5. Already fixed in `app/build.gradle.kts`; if a future Gradle bump
reintroduces this error, check whether the launcher version needs bumping alongside
`junit-jupiter`'s, don't assume it's the JDK-parsing issue above. `:core:crypto` uses
JUnit4 and isn't affected.

**AGP 10 removes the pre-AGP-9 opt-out flags in `gradle.properties`.** This repo is
already pre-migrated (`android.builtInKotlin=true`, `android.newDsl=true`, etc. — see
`gradle.properties`), which required dropping the `org.jetbrains.kotlin.android` plugin
(built-in Kotlin compiles it directly; the plugin conflicts with the new DSL) and
bumping Hilt to 2.60.1 (2.54 fails under `android.newDsl=true` with `Android
BaseExtension not found` — Dagger/Hilt only added real AGP 9 support in 2.59). If a
future Gradle/AGP bump surfaces new `android.*` deprecation warnings mentioning "removed
in version 10.0" for properties not yet flipped, the fix pattern is the same: flip the
property to the value AGP 10 will hardcode and rebuild, don't just silence the warning.

## Testing

**`DataStore`'s default internal scope is real `Dispatchers.IO`, invisible to a test's
`StandardTestDispatcher`.** Code backed by `androidx.datastore` (see
`data/local/InstanceStore.kt`, `TokenStore.kt`) will flake or deadlock under
`runTest { }` unless the `DataStore` is built with its scope bound to the same test
dispatcher passed into `runTest`. The same failure mode showed up a second time from a
different source — an OkHttp real thread pool racing a `StandardTestDispatcher` in a
ViewModel test — so treat "test passes alone, flakes in the suite" involving any
real-thread-backed dependency as this class of bug first.

**Room DAO tests run as plain JVM unit tests — no Robolectric, no device needed** (this
build environment has neither an emulator nor Robolectric's Android SDK jars readily
available). The recipe, in `app/src/test/kotlin/.../data/local/db/TestDatabase.kt`:

```kotlin
Room.inMemoryDatabaseBuilder(mock<Context>(), AppDatabase::class.java)
    .setDriver(BundledSQLiteDriver())
    .build()
```

Two non-obvious points behind this:

- Room's Android-target `inMemoryDatabaseBuilder` still requires a `Context` argument
  even with `BundledSQLiteDriver` doing all the actual SQL work — the KMP-style
  no-context `Room.databaseBuilder<T>(name)` overload only exists for non-Android
  targets (JVM/native/wasm), not the Android one, so `Room.inMemoryDatabaseBuilder<T>()`
  will fail to resolve with "No value passed for parameter 'context'". A decompiled read
  of `RoomDatabase.Builder` (2.8.4) confirms the only things it does with `context` for
  an in-memory, driver-backed database are a null-check and (for the default `AUTOMATIC`
  journal mode) one `getSystemService` lookup that's safe to return null from — so an
  unstubbed `mockito-kotlin` mock is enough. Don't reach for Robolectric just to satisfy
  this parameter.
- **`androidx.sqlite:sqlite-bundled` is a multiplatform umbrella artifact** — on a plain
  Android module's `test` (JVM) classpath, Gradle resolves it to the *Android* variant
  (a `.aar` with a native `.so`, not loadable by the host JVM running the test), which
  fails at runtime with `UnsatisfiedLinkError`/`NoClassDefFoundError`, not at compile
  time. Pin `androidx.sqlite:sqlite-bundled-jvm` explicitly for `testImplementation`
  instead (see the comment on `androidx-sqlite-bundled` in `gradle/libs.versions.toml`).

**`@Upsert` is unreliable with `sqlite-bundled-jvm:2.7.0` — use `ON CONFLICT DO UPDATE`
instead.** Room's generated `@Upsert` code inserts, catches the resulting constraint
violation, and falls back to an update — but it decides "was that actually a uniqueness
violation" by pattern-matching the caught exception's *message* (looking for `"unique"`,
`"2067"`, or `"1555"`). `sqlite-bundled-jvm:2.7.0` throws that exception with a **null
message** on this platform, confirmed by reproducing it against the bare driver with no
Room involved at all (`INSERT` of an already-used primary key → `android.database.
SQLException`, `message == null`). Room's fallback never fires, so a second `@Upsert` of
an existing row crashes instead of updating it. Every DAO in `data/local/db/` that needs
real upsert semantics writes an explicit `INSERT INTO ... ON CONFLICT(...) DO UPDATE SET
...` `@Query` instead, which sidesteps the exception-parsing path entirely (see
`PhotoDao.upsertOne` for the pattern, and its doc comment for a fuller explanation).
Whether this is specific to the `-jvm` driver artifact or a broader Room 2.8.4 issue is
unconfirmed — if `@Upsert` gets reached for again anywhere in this codebase, verify it
against a real device/emulator before trusting it, not just the JVM test suite.

**A Room `Flow`-returning query (`observeXxx()`), collected from a coroutine context
where `Dispatchers.Main` is resolvable, needs `ArchTaskExecutor` initialized — which a
bare JVM test never does.** DAO tests calling `.observeAll().first()` from a plain
`runTest { }` (see `PhotoDaoTest`, `ScannerTest`) never hit this — only a `ViewModel`
test does, because `viewModelScope` is `Dispatchers.Main.immediate`, and once a test has
called `Dispatchers.setMain(...)`, Room's `TriggerBasedInvalidationTracker` routes its
invalidation callback through `ArchTaskExecutor`, whose default delegate posts to a
real `Handler(Looper.getMainLooper())` — nonexistent here. Symptom: `RuntimeException:
Method getMainLooper in android.os.Looper not mocked`, wrapped in
`MissingMainCoroutineDispatcher`/`CoroutinesInternalError`, thrown from deep inside
`TriggerBasedInvalidationTracker$createFlow`. Fixed once, centrally: `TestDatabase.kt`'s
`buildTestDatabase()` installs a synchronous `TaskExecutor` delegate via
`ArchTaskExecutor.getInstance().setDelegate(...)` before building the database — every
test using that helper is covered, whether or not it happens to need it.

**`androidx.room.withTransaction` doesn't work at all against a `buildTestDatabase()`
database — use `RoomDatabase.useWriterConnection { it.withTransaction(...) { ... } }`
instead, everywhere, not just in tests.** Found via `TimelineRemoteMediatorTest` (plan
step 2.11), the first code in this codebase needing an atomic transaction spanning more
than one DAO call (`@Transaction` only covers a single DAO's own methods). Two separate
failures stacked, in this order:
1. `RoomDatabase.beginTransaction()` calls `assertNotMainThread()`, which — since
   there's no real main thread in this process — calls `Looper.getMainLooper()`; with
   `isReturnDefaultValues = true` (needed anyway, see the `ExifInterface` entries below)
   that returns `null` rather than throwing "not mocked", so the very next call,
   `.getThread()`, NPEs.
2. Fixing *that* (e.g. via `.allowMainThreadQueries()` on the test builder) uncovers the
   real, unfixable-that-way problem underneath: `androidx.room.withTransaction` still
   routes through the legacy `SupportSQLiteOpenHelper`-based transaction API
   (`RoomDatabase.beginTransaction()`/`getOpenHelper()`), which throws
   `IllegalStateException: Cannot return a SupportSQLiteOpenHelper since no
   SupportSQLiteOpenHelper.Factory was configured with Room` — because
   `buildTestDatabase()` uses `.setDriver(BundledSQLiteDriver())` (there's no
   Android-framework SQLite on a bare JVM to build a `SupportSQLiteOpenHelper` from in
   the first place) and never configures a compat open-helper alongside it.

The actual fix has nothing to do with the test environment: `androidx.room.
useWriterConnection { transactor -> transactor.withTransaction(Transactor.
SQLiteTransactionType.IMMEDIATE) { ... } }` is the modern, driver-based transaction API
(already in `room-runtime`, no separate `room-ktx` dependency needed) that both this
test database *and* the app's real, framework-backed production database understand —
ordinary suspend DAO calls made from inside the block reuse the already-acquired
connection via coroutine-context confinement, same as `@Transaction` would. Switching
`TimelineRemoteMediator.kt` to this made `.allowMainThreadQueries()` unnecessary too
(confirmed by removing it and re-running the suite) — this transaction API never calls
`assertNotMainThread()` at all, so problem 1 above was really just a symptom of using
the wrong API for problem 2, not two independent bugs.

**A Room suspend DAO call can resume on a real thread, not the caller's test
dispatcher — `advanceUntilIdle()` alone doesn't wait for it.** Same failure shape as
the `DataStore`/OkHttp entry above (one more instance of "test passes alone, flakes
under a `ViewModel` + `StandardTestDispatcher`"), different source: Room's Bundled-driver
connection pool does its actual query execution on its own internal executor. A
`ViewModel` test that fires a `viewModelScope.launch` touching Room and then calls a
bare `dispatcher.scheduler.advanceUntilIdle()` can read `uiState.value` before that work
lands — not consistently; enough to pass in isolation and fail as flakiness. Fix: the
same bounded real-time poll as `EnrolmentRepositoryTest`'s `awaitState` helper (retry
`advanceUntilIdle()` + a short real `Thread.sleep` until the expected state appears or a
timeout elapses), not a single `advanceUntilIdle()` call. See `FoldersViewModelTest`.

**`androidx.exifinterface`'s `ExifInterface` needs `testOptions.unitTests.isReturnDefaultValues
= true` to even construct in a plain JVM test** (not Robolectric — an AGP flag). Its
static initializer calls `android.util.Log.isLoggable`, which the bare `android.jar`
stub throws `RuntimeException: ... not mocked` for by default; the flag makes unmocked
stub methods return their default value (`false` here) instead, letting the class load.
Already set in `app/build.gradle.kts`.

**That same flag corrupts `android.util.Pair` used *internally* by `ExifInterface`'s
*write* path (`setAttribute`/`saveAttributes`), silently.** `isReturnDefaultValues` stubs
every unmocked `android.*` method — including, it turns out, `android.util.Pair`'s
constructor, which the stub treats like any other method and replaces with a no-op that
never assigns `first`/`second`. `ExifInterface.setAttribute()`'s own format-guessing
logic builds one of these internally and immediately dereferences `.first`, so instead
of a "field never assigned" symptom this surfaces several frames away as
`NullPointerException: Cannot invoke "java.lang.Integer.intValue()" because
"guess.first" is null` — confirmed by reflectively calling the library's private
`guessDataFormat` directly and watching it return a `Pair` with both fields null. The
*read* path (`getAttribute`, `getGpsDateTime`, etc.) never touches this and is
unaffected. Don't generate EXIF test fixtures by writing tags through `ExifInterface` in
this test environment — write them with an external tool instead (`ExifExtractorTest`'s
`canon-with-gps.jpg`, a real image with real EXIF/GPS tags, was generated once with
Python's Pillow — `Image.Exif`/`get_ifd()` — and committed under
`app/src/test/resources/exif-fixtures/`) and only ever *read* it back through
`ExifInterface` in the test itself.

**`ExifInterface.getGpsDateTime()` is genuinely nullable (`Long?`), not a sentinel
value.** Easy to assume otherwise — some library versions' docs describe a negative
sentinel for "not present" — but 1.4.2's Kotlin-visible signature is `@Nullable Long`,
and the Kotlin compiler will catch a `Long.takeIf { it >= 0 }` treatment of it
immediately (unboxing a null throws "Operator call is prohibited on a nullable
receiver"). Trust the compiler over a half-remembered API shape here.

**`TAG_IMAGE_WIDTH`/`TAG_IMAGE_LENGTH` do get populated for a plain JPEG with no EXIF
segment at all**, confirmed empirically against a real (if synthetic) `javax.imageio`-
generated JPEG: `ExifInterface` derives them from the JPEG's own SOF marker when no
EXIF tag supplies them, so `getAttributeInt(TAG_IMAGE_WIDTH, 0)` alone is a reasonable
fallback even before trying the more commonly-populated `TAG_PIXEL_X_DIMENSION`/
`TAG_PIXEL_Y_DIMENSION` (Exif SubIFD tags most real cameras actually write).

## Instrumented tests (real device/emulator)

Plan step 2.10 added the first ones (`app/src/androidTest/`) — `ScannerInstrumentedTest`,
`ThumbnailerInstrumentedTest`, `UploadWorkerInstrumentedTest` — run via
`./gradlew :app:connectedDebugAndroidTest` against a booted emulator or device (`adb
devices` must show one; `~/Library/Android/sdk/{platform-tools/adb,emulator/emulator}`
exist in this environment even though neither is on `PATH` by default — `export
ANDROID_HOME=~/Library/Android/sdk; export PATH="$ANDROID_HOME/platform-tools:$PATH"`).
Filter to one class with `-Pandroid.testInstrumentationRunnerArguments.class=<FQCN>`.

**`connectedDebugAndroidTest` uninstalls the app-under-test when it finishes — including
its data.** Confirmed the hard way: ran the full suite against an emulator that had a
real, previously-enrolled session on it (from earlier manual 2.4/2.5/2.9 live-testing,
per `STATUS.md`), and the run silently wiped it — `pm list packages` showed
`fr.enry.archivist.debug` gone entirely afterward, taking its `DataStore`/
`SharedPreferences` and (since Keystore keys are tied to the app's UID) its device-wrap
Keystore key with it. This is AGP's own default task behavior, not a bug in any test's
own code — it happens regardless of what the tests themselves do, and no amount of
save-and-restore *inside* a test (see `UploadWorkerInstrumentedTest`'s "Safety" doc, which
protects against a *different* risk — clobbering live in-app state *while a test runs*,
not the package being removed *after*) prevents it. **Before running
`connectedDebugAndroidTest` (or `connectedCheck`) against any device/emulator that isn't
known-disposable, check `adb shell pm list packages | grep archivist` for a real
install and ask first** — same bar as any other destructive action. `am instrument`
invoked directly (bypassing the Gradle task) does not have this behavior; that's the
fallback if a session's app state needs to survive the test run.

**`Configuration.Provider` alone does not make WorkManager use a custom
`WorkerFactory`.** `androidx.startup`'s default `WorkManagerInitializer` runs during
`Application.attachBaseContext()` — before `onCreate()`, and therefore before Hilt's
field injection into the `Application` instance (`@Inject lateinit var workerFactory:
HiltWorkerFactory`) has happened — and calls `WorkManager.initialize(context,
Configuration.Builder().build())` unconditionally, locking in the reflective default
`WorkerFactory` before the on-demand `Configuration.Provider` path ever gets consulted.
Symptom, confirmed live: `WM-WorkerFactory: Could not instantiate
fr.enry.archivist.sync.UploadWorker` / `NoSuchMethodException:
UploadWorker.<init>[Context, WorkerParameters]` (a `@HiltWorker`'s real constructor is
`@AssistedInject`, not that reflective shape). The fix — also from WorkManager's own
"On-demand initialization" docs, not just this project — is to remove the default
initializer's manifest entry so the *first* `WorkManager.getInstance()` call is the one
that actually reads `workManagerConfiguration`:
```xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    android:exported="false"
    tools:node="merge">
    <meta-data
        android:name="androidx.work.WorkManagerInitializer"
        android:value="androidx.startup"
        tools:node="remove" />
</provider>
```
See `AndroidManifest.xml`. No JVM test can catch this — `UploadRepositoryTest` calls
`UploadRepository.uploadOne()` directly, bypassing `UploadWorker`/WorkManager entirely.

**WorkManager's own manifest doesn't declare a `foregroundServiceType` for
`SystemForegroundService` — a `ForegroundInfo(..., FOREGROUND_SERVICE_TYPE_DATA_SYNC)`
call crashes on API 34+ without an explicit override.** `IllegalArgumentException:
foregroundServiceType 0x1 is not a subset of foregroundServiceType attribute 0x0 in
service element of manifest file` — confirmed live, not assumed from the docs (an
earlier STATUS.md note guessed this was already declared; it wasn't). Fix, in the same
manifest:
```xml
<service
    android:name="androidx.work.impl.foreground.SystemForegroundService"
    android:foregroundServiceType="dataSync"
    tools:node="merge" />
```

**A new `@EntryPoint` interface must live in `app/src/main`, not `app/src/androidTest`,
even if only instrumented tests ever call it.** Hilt aggregates every
`@InstallIn(SingletonComponent::class)` declaration at the compilation that generates
the *real* `SingletonComponent` implementation — `:app`'s main variant — not the
separate `androidTest` APK's own (different) Hilt component. Declaring it in
`androidTest` compiles fine and throws `ClassCastException: Cannot cast
DaggerArchivistApplication_HiltComponents_SingletonC$SingletonCImpl to
TestEntryPoint` at runtime; confirmed by trying it first. See `TestEntryPoint.kt`'s own
doc for the full reasoning — it's real production-tree code (an interface with getters,
no behavior) specifically because of this constraint, not by choice.

**`AndroidMediaStoreSource` builds URIs off `MediaStore.Files` (the collection every
media type shares), not a type-specific collection like `MediaStore.Images.Media`.**
Both name the same underlying row as different URI strings
(`content://media/external/file/<id>` vs. `content://media/external/images/media/<id>`),
and `UploadQueueDao`/`Scanner` match rows by the exact string. A test fixture that
inserts via `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` but hands back that same
images-collection URI as the "content URI" will silently never match what `Scanner`
itself records — recompute the `MediaStore.Files` form (`ContentUris.withAppendedId
(MediaStore.Files.getContentUri("external"), id)`) instead; see
`testutil/MediaStoreFixtures.kt`.

**A JUnit4 `@Before`/`@After` method's inferred return type must be exactly `Unit`, not
`Unit?`.** `fun tearDown() = runBlocking { ... }` where the block's last expression is a
nullable `?.let { ... }` infers `Unit?` and fails to load with `InvalidTestClassError:
Method tearDown() should be void` — not a compile error, only visible at test-collection
time. Use a block body (`fun tearDown() { runBlocking { ... } }`) instead of an
expression body whenever the last line inside could be nullable.

**Robolectric has no native JUnit5 support**, as of this writing (checked directly,
not from memory) — only a third-party bridge (`tech.apter.junit5.jupiter:
robolectric-extension`, still pre-1.0 at last check). If a future step (Compose UI
tests, a `MediaStore`-driven scanner test) seems to need Robolectric, the two realistic
options are: (a) that third-party bridge, accepting its own-version-churn risk, or (b)
add `junit:junit` + `org.junit.vintage:junit-vintage-engine` so classic
`@RunWith(RobolectricTestRunner::class)` JUnit4 tests run via the Vintage engine
alongside the existing Jupiter tests under the same `useJUnitPlatform()` task — no
`useJUnitPlatform()`/JUnit5 migration needed for Robolectric specifically, since Vintage
lets both coexist. Prefer (b) unless there's a specific reason not to.
