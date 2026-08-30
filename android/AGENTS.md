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
