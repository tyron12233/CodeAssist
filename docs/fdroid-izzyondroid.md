# Distributing CodeAssist via IzzyOnDroid (F-Droid)

This documents how CodeAssist is published to the **IzzyOnDroid** F-Droid repository, why the
**official F-Droid main repo is not used**, and the exact steps to cut a release IzzyOnDroid can pick up.

## Why IzzyOnDroid and not the official F-Droid repo

The official F-Droid repo builds every app **from source on its own buildserver**, with no network
access for arbitrary downloads and no prebuilt binaries. CodeAssist's on-device Android pipeline
cannot meet that today:

- **Prebuilt `aapt2`** (`ide-android/src/main/jniLibs/<abi>/libaapt2.so`, fetched from the ReVanced
  GitHub releases by the `fetchAndroidBuildTools` Gradle task). aapt2 is itself free software (AOSP,
  Apache-2.0) but building it from source on the F-Droid buildserver is infeasible, and F-Droid will
  not ship a binary it did not build.
- **Build-time network download** (`fetchAndroidBuildTools`) — forbidden on the buildserver.
- **Bundled `android.jar`** (`ide-android/src/main/assets/android.jar`) — the Android SDK platform
  stubs, shipped inside the APK as the on-device compiler boot classpath. The Android SDK is under
  Google's non-free SDK licence, so shipping it is a non-free asset.

IzzyOnDroid instead distributes the **developer's own prebuilt, signed APK** (pulled from GitHub
Releases). It requires the app to be FOSS-licensed and free of proprietary trackers/libraries — which
CodeAssist is — and discloses any binary blobs as *Anti-Features* rather than rejecting them.

## One-time prerequisites (done in this repo)

- [x] **FOSS licence** — `LICENSE` is GPL-3.0-or-later; declared in `README.md`.
- [x] **Fastlane metadata** — `fastlane/metadata/android/en-US/` holds `title.txt`,
      `short_description.txt`, `full_description.txt`, and `changelogs/<versionCode>.txt`. IzzyOnDroid
      reads these for the listing.
- [ ] **Promo images (optional)** — add to `fastlane/metadata/android/en-US/images/`:
      `icon.png` (512×512, optional — IzzyOnDroid otherwise extracts it from the APK),
      `featureGraphic.png` (1024×500), and `phoneScreenshots/1.png …` (real device screenshots).
- [x] **Public source** — https://github.com/tyron12233/CodeAssist (branch `main`).

## Signing — important

The APK published to IzzyOnDroid is signed with the **upload key**
(`keystore/upload-keystore.jks`, alias `upload-key`) — the same key used for the Play *upload*.

Because Google Play uses **Play App Signing** (Google re-signs with a managed key), the Play-distributed
build and the IzzyOnDroid build have **different signatures**. That is expected and unavoidable: a user
cannot update across the two stores; they are independent installs. Keep using the **same** key for every
IzzyOnDroid release so in-store updates work — if the signature ever changes, IzzyOnDroid users must
uninstall/reinstall.

## Cutting a release IzzyOnDroid can ingest

IzzyOnDroid's updater watches the GitHub **Releases** of the repo and picks up the APK asset whose
`versionCode` is newest.

1. **Bump the version** in `ide-android/build.gradle.kts` (`versionCode` must strictly increase;
   `versionName` is the human label, e.g. `3.0.0`).
2. **Add a changelog** `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
3. **Build a signed release APK** (not an AAB — IzzyOnDroid distributes APKs):

   ```sh
   export JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
   ./gradlew :ide-android:assembleRelease
   # → ide-android/build/outputs/apk/release/ide-android-release.apk
   ```

4. **Tag and publish a GitHub release**, attaching the APK:

   ```sh
   git tag v3.0.0 && git push origin v3.0.0
   gh release create v3.0.0 \
     ide-android/build/outputs/apk/release/ide-android-release.apk \
     --title "CodeAssist 3.0" \
     --notes-file fastlane/metadata/android/en-US/changelogs/31.txt
   ```

## Requesting addition to IzzyOnDroid (first time only)

Open a **Request For Packaging** issue at the IzzyOnDroid repo:
https://gitlab.com/IzzyOnDroid/repo/-/issues (use the "Request packaging of an app" template), with:

- **App name:** CodeAssist
- **Source / repo:** https://github.com/tyron12233/CodeAssist
- **Releases (APK):** https://github.com/tyron12233/CodeAssist/releases
- **Licence:** GPL-3.0-or-later
- **applicationId:** `com.tyron.code`
- **Update mechanism:** GitHub Releases (APK attached per release)
- **Anti-Features (disclose up front):** see below.

After the app is accepted once, every later GitHub release with a higher `versionCode` is picked up
automatically — no further requests needed.

## Anti-Features to disclose

IzzyOnDroid runs the F-Droid scanner over the APK. Declare these proactively so review is smooth:

- **NonFreeAssets** — the bundled `android.jar` (Android SDK platform stubs, non-free SDK licence)
  shipped as the on-device compiler boot classpath.
- **Prebuilt component** — the bundled `libaapt2.so` binaries (Apache-2.0 source, but shipped prebuilt).

Neither is a tracker and the app contains **no analytics, ads, or proprietary libraries**. The only
network use is the user-initiated Maven dependency download on the Dependencies screen (declared via the
`INTERNET` permission), and the optional install of the user's own built APK via `PackageInstaller`
(`REQUEST_INSTALL_PACKAGES`).
