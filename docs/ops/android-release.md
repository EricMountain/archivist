# Releasing the Android app

How to cut a release once the app is already listed on Play and the five release
secrets exist (`docs/ops/android-release-secrets.md` — do that one-time setup first if
you haven't).

## What you don't need to touch

`android/app/build.gradle.kts` hardcodes fallback `versionCode`/`versionName` values,
but the tag-driven release path below overrides both automatically:

* `versionCode` — `play { resolutionStrategy.set(ResolutionStrategy.AUTO) }` queries
  Play for the internal track's current `versionCode` and builds with that value + 1.
  This only runs when Gradle Play Publisher is enabled, which only happens in
  `android-release.yml` (gated on `ANDROID_PUBLISHER_CREDENTIALS` being set).
* `versionName` — the release workflow sets `ARCHIVIST_VERSION_NAME` from the git tag
  itself (`android-v0.2.0` → `0.2.0`) and `build.gradle.kts` reads that env var ahead of
  its literal fallback.

The literals in `build.gradle.kts` only matter for a local build with no Play
credentials (a manual `bundleRelease`/`assembleRelease`, e.g. the one-time first upload
described in `android-release-secrets.md`). Don't hand-edit them for an ordinary
release — they're dead weight on the tag-driven path and just another thing to
remember to revert.

## Cutting a release

```sh
rel=v0.2.0
git tag android-${rel} -m "Android app ${rel}"
git push origin android-${rel}
```

That's the whole trigger. Pushing an `android-v*.*.*` tag runs
`.github/workflows/android-release.yml`, which:

1. Builds the release bundle with `versionName` from the tag.
2. Signs it using the `ANDROID_KEYSTORE_*` secrets (from the `play` environment).
3. Runs `:app:publishBundle` (Gradle Play Publisher), which resolves `versionCode` via
   `AUTO` and uploads straight to the Play **internal** track.
4. In a separate job that never sees the Play secrets, creates a **GitHub Release** for
   the tag: the exact uploaded bundle as `archivist-<version>.aab`, its `.sha256`, and
   notes listing the commits under `android/` since the previous release tag. The
   release is published immutable: its assets can't be changed and its tag can't be
   moved or deleted.

No manual Play Console step for a normal release. Watch it with:

```sh
gh run list --workflow=android-release.yml
gh run view --log
```

You usually don't need to cut one by hand at all: `android-auto-release.yml` releases
the next patch version every Sunday if anything under `android/` changed since the last
tag (see `docs/ops/automated-updates.md`). A hand-pushed tag still releases at any time.

## A wrong tag is fixed forward

Release tags can't be moved or deleted: a tag ruleset protects `android-v*`, and the
immutable GitHub Release protects its own tag. So a mistaken tag, or a release with a
bug, is fixed by releasing the next patch version, never by retagging. If Play
publishing succeeded but the GitHub Release job failed, re-run just that job: it deletes
any draft an earlier attempt left and starts again.

## Notes

* The tag is prefixed `android-v` because this repo also holds the unrelated
  Terraform-deployed backend, which has its own release path.
* Only the internal track is published to automatically — promoting to a wider track
  is a manual Play Console step, not part of this workflow.
* If a step fails, `gh run view --log` on the failing run is the fastest way to see
  which one and why — see `docs/ops/android-release-secrets.md` for the secrets it
  depends on, and `android/AGENTS.md` ("CI and release (Gradle Play Publisher)") for two
  build-time gotchas already fixed in `build.gradle.kts` (GPP breaking
  `assembleRelease` for everyone without credentials, and
  `ANDROID_PUBLISHER_CREDENTIALS` being the credentials file's *contents*, not a path).
