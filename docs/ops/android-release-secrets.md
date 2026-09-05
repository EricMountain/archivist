# Setting up Android release secrets

Five GitHub Actions repository secrets, all read by `.github/workflows/android-release.yml`
(plan step 2.16; see "CI and release, as built" in `docs/design/android.md`). Nothing here
is needed for `.github/workflows/android-ci.yml` — PRs never touch signing or Play.

| Secret | What it is |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | The upload keystore, base64-encoded |
| `ANDROID_KEYSTORE_PASSWORD` | Its store password |
| `ANDROID_KEY_ALIAS` | The key alias inside it |
| `ANDROID_KEY_PASSWORD` | That key's own password |
| `PLAY_SERVICE_ACCOUNT_JSON` | A Google Cloud service account key, raw JSON |

As elsewhere in this repo, every value below is a placeholder — substitute your own and
never commit the real ones (see "Nothing personal in the committed tree" in `CLAUDE.md`).

## Before any of this: one manual upload

**The Play Developer API cannot create an app's first release — only the Play Console
can.** `publishBundle` (Gradle Play Publisher, which the release workflow calls) will
fail on a listing that has never had a build uploaded through the Console, no matter how
correctly the secrets below are set up. So, once (not per-release):

1. Build a release bundle by hand. `app/build.gradle.kts` never hardcodes a keystore
   path or password — it reads four environment variables at configuration time
   (`ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
   `ANDROID_KEY_PASSWORD`) and only wires up a `signingConfig` for the release build
   type if `ANDROID_KEYSTORE_PATH` is set. `ANDROID_KEYSTORE_PATH` itself isn't one of
   the five secrets above — in CI the release workflow creates it on the fly by
   decoding `ANDROID_KEYSTORE_BASE64` to a temp file; for a manual local build you point
   it straight at wherever you put `release.keystore` from step 1 below (do that first
   if you haven't made a keystore yet):
   ```sh
   export ANDROID_KEYSTORE_PATH=/absolute/path/to/release.keystore
   export ANDROID_KEYSTORE_PASSWORD='choose-a-strong-password'
   export ANDROID_KEY_ALIAS='archivist-upload'
   export ANDROID_KEY_PASSWORD="${ANDROID_KEYSTORE_PASSWORD}"
   cd android && ./gradlew :app:bundleRelease
   ```
   Verified locally with a throwaway test keystore: with all four set, `bundleRelease`
   runs `signReleaseBundle` (not skipped) and the resulting `app/build/outputs/bundle/release/app-release.aab`
   passes `jarsigner -verify -certs`, showing "jar verified" and a signer matching the
   keystore's own certificate. Leave any one of the four unset and the release build
   type gets no `signingConfig` at all — the build still succeeds, silently, but
   produces an *unsigned* bundle, which Play Console will reject on upload rather than
   erroring at build time.
2. Play Console → your app → Testing → Internal testing → Create a release → upload that
   `.aab` → save and roll out.

Only after that exists does `android-v*.*.*` tagging have a release to increment against.

## 1. The upload keystore

If you don't already have one:

```sh
keytool -genkeypair -v \
  -keystore release.keystore \
  -alias archivist-upload \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Your Name, OU=, O=, L=, ST=, C=US"
```

keytool will prompt for the store password to set.

Verified locally (`keytool` from a JDK 17+ install): produces a PKCS12 keystore (the
default keystore type since Java 9) containing one self-signed key under the alias you
named. `storepass`/`keypass` cannot be distinct - PKCS12 doesn't support that. `ANDROID_KEYSTORE_PASSWORD`
and `ANDROID_KEY_PASSWORD` are separate secrets all the same.

**Back up `release.keystore` somewhere durable and outside this repo before doing
anything else with it.** Play App Signing means Google holds the *app* signing key, but
losing this *upload* key is still real — recoverable via Play Console support, but
slowly (see "Play App Signing" in `docs/design/android.md`).

Base64-encode it for the secret:

```sh
base64 -i release.keystore -o release.keystore.b64   # macOS
# or: base64 -w0 release.keystore > release.keystore.b64   # GNU coreutils
```

Then set the four keystore-related secrets (`gh` reads `--body` from the given string;
`< file` reads it from stdin — either works):

```sh
gh secret set ANDROID_KEYSTORE_BASE64 < release.keystore.b64
gh secret set ANDROID_KEYSTORE_PASSWORD # prompt's for password
gh secret set ANDROID_KEY_ALIAS --body 'archivist-upload'
gh secret set ANDROID_KEY_PASSWORD # prompt's for password
```

Verified: `gh secret set` (this repo's `gh` is authenticated already) encrypts the value
client-side before it ever reaches GitHub; `base64 -i`/`-D` round-trips byte-identical
locally. Delete `release.keystore.b64` afterward — it's a plaintext copy of a secret you
just set. `release.keystore` itself you keep, per the backup note above; `android/.gitignore`
already excludes `*.keystore` so it can live in the working tree without being committed
by accident.

### Register the key on Play Console

* Login to Google Play Console
* Select "Android developer verification"
* Register the package name
* Add the key generated above
   * `keytool -list -keystore ...` will list the required sha256 fingerprint

## 2. The Play service account

**Not command-verified this session** — no `gcloud` install or GCP project was available
here to run these against; the sequence itself is gcloud's ordinary, long-stable
service-account flow, but confirm each step's actual output against your own project.

1. Pick or create a Google Cloud project (a fresh one that only exists for this is
   fine — the API, the service account and the project itself are all free):
   ```sh
   gcloud projects create archivist-release --name="Archivist release"
   gcloud config set project archivist-release
   ```
2. Enable the Play Developer API on it:
   ```sh
   gcloud services enable androidpublisher.googleapis.com
   ```
3. Create the service account and a JSON key for it:
   ```sh
   gcloud iam service-accounts create archivist-publisher \
     --display-name="Archivist Play publisher"
   gcloud iam service-accounts keys create play-service-account.json \
     --iam-account=archivist-publisher@archivist-release.iam.gserviceaccount.com
   ```
4. **In the Play Console** (this half has no CLI — it's a separate permission system
   from GCP IAM): Users and permissions → Invite new users → the service account's own
   email (`archivist-publisher@archivist-release.iam.gserviceaccount.com`) → grant it
   access to this app only, with just the release permissions the internal track
   needs (create/edit releases, no financial or account-management scopes) — the
   narrowest role that works, not account-wide admin, per `android.md`.

Then set the secret from the key file's raw contents:

```sh
gh secret set PLAY_SERVICE_ACCOUNT_JSON < play-service-account.json
```

**This one is JSON content, not a path** — confirmed against Gradle Play Publisher's own
parse-failure message while building the release workflow (see `android/AGENTS.md`'s "CI
and release" section); the workflow passes this secret straight to GPP as the
`ANDROID_PUBLISHER_CREDENTIALS` environment variable, unchanged. Delete the local
`play-service-account.json` afterward — same reasoning as the keystore's `.b64` copy.

## After all five exist

`git tag android-v0.1.0 && git push origin android-v0.1.0` (adjust the version) triggers
`android-release.yml`. Nothing in this session has run that workflow for real — the
first actual tag push is what confirms this end to end, not this document. If it fails,
`gh run list --workflow=android-release.yml` / `gh run view --log` is the fastest way to
see which step and why.
