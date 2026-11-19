# Signing identities and the 1.4.2 transition

**Before replacing a development-signed installation, create and check a portable backup outside Forge. Uninstalling removes the app's private data. Do not uninstall until that backup is available and verified.**

Version 1.4.2 introduced a dedicated production release identity. Version 1.4.3 keeps that identity, application ID `com.forge.workout`, Room schema 4 and portable backup format 3. Production-signed 1.4.2 installations can update in place. Previously published development-signed APKs, including 1.4.1, need the one-time migration below. See the [migration test results](release-1.4.2.md).

## Public certificate fingerprints

These SHA-256 values identify the public signing certificates. They are safe to publish; they are not passwords, private keys or APK file checksums.

| Identity | Certificate SHA-256 |
| --- | --- |
| Published 1.4.1 / legacy development certificate | `ebf2faa1b45b6a6eea4742e7c31f8144415f5748067af1b9e4159a988ce32adc` |
| Dedicated production release certificate | `7845655dd1614ae614f9cf0b1386f1c215fe3a8b96b0c16415116460cb425019` |

The fingerprints differ. A standard debug build on another machine can have a different debug fingerprint; the first row identifies Forge's actual legacy distribution certificate, not every Android debug key.

Debug uses Android's standard debug signing configuration exclusively for development and testing. Official new releases use the owner-controlled production PKCS12 keystore, containing a 3072-bit RSA key and a SHA256withRSA certificate. Release APKs use v2/v3 signatures; the legacy debug key is not a co-signer. Keep this production identity for future compatible releases.

## Why the one-time reinstall is necessary

Android 9/API 28 introduced signing proof-of-rotation, and later Android versions support additional rotation behavior. Android 8/API 26 cannot consume that lineage. A universal APK retaining an in-place API 26 upgrade would still need the old signer for that platform, contrary to Forge's production-only signing policy. This release instead uses only the new production identity, without a signing lineage. The same explicit manual transition applies to old development-signed installations on all supported Android versions. [AOSP v3 signing](https://source.android.com/docs/security/features/apksigning/v3), [apksigner rotation options](https://developer.android.com/tools/apksigner)

Modern signing rotation is technically possible in other configurations; it has not been implemented or verified for this Forge transition. Forge's APK verifier continues to require complete current-signer equality. It must not accept unrelated certificates or arbitrary overlaps of signing histories.

## Migrate from 1.4.1 or another development-signed build

1. Finish or save pending edits. In the existing app, choose **Settings → Backup & restore → Create backup**. Save the JSON file in a user-controlled Documents/Downloads folder or external storage that will remain after uninstall. Do not use Forge's private directory or `Android/data/com.forge.workout`.
2. Check that the backup exists and has nonzero size. Use **Restore backup** to select it and review its validation preview, date and expected record counts; then press **Cancel**. Do not choose Replace and restore merely to inspect it. Keep a separate safe copy. If validation fails or records are missing, stop and retain the installed app.
3. Download `Forge-1.4.3.apk` from [Forge v1.4.3](https://github.com/ritikverma098/Forge-Fitness-Tracker/releases/tag/v1.4.3) to storage outside the old app. Check its complete-file SHA-256 against that release's `SHA256SUMS.txt`.
4. Only after the backup has been checked, uninstall the development-signed Forge app through Android's normal settings. This is a manual, user-approved step. Forge never uninstalls itself, clears personal data or bypasses signature checks.
5. Install the official production-signed APK using Android's installer. Approve installation-source access if Android requests it for the browser/file manager used in this one-time step.
6. Open Forge and choose **Settings → Backup & restore → Restore backup**. Select the verified JSON, review its counts and confirm replacement on this fresh installation.
7. Check workouts, sets, history, routines, custom foods, recipes, diary entries and historical nutrient snapshots, body weights, goals and app/nutrition preferences. The restore replaces the personal records; it is not a merge or duplicate-import operation.
8. Optional reference-food packs are not stored in the portable personal backup. Download or import them again from **Nutrition → Food catalog** as needed. Their stable [1.4.0 assets and checksums](https://github.com/ritikverma098/Forge-Fitness-Tracker/releases/tag/v1.4.0) remain available. Historical diary/recipe nutrition snapshots are retained independently of catalog reinstallation. Android permissions may also need granting again after reinstall.

The published 1.4.1 app still opens its validated APK link in a browser; it cannot be retroactively given the new downloader or migrate its own certificate. Reading the release notes does not make the new certificate compatible with the installed one. If Android rejects an attempted in-place installation, keep the old installation and follow the backup procedure above rather than clearing data to bypass the error.

After migration, future official APKs signed with this same production identity can use Forge's **Download & Install** workflow: verify the download, choose Install Update and approve Android's confirmation. Normal compatible updates preserve the app's private data. [Android signing concepts](https://developer.android.com/studio/publish/app-signing)

## Private production build configuration

The production keystore and passwords are kept outside Git and outside the project checkout in durable owner-controlled private storage. The owner has confirmed independent secure backups of both. A backup of the keystore alone is insufficient if its passwords are lost. Do not upload signing files, place them in release assets, paste secrets into chat, or store them in this repository.

Use exactly one input method:

- Set `FORGE_RELEASE_SIGNING_PROPERTIES` to an external Java properties file containing `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Relative `storeFile` values resolve against that file's directory. Use forward slashes or escaped backslashes in Java properties.
- Alternatively set all four variables: `FORGE_RELEASE_STORE_FILE`, `FORGE_RELEASE_STORE_PASSWORD`, `FORGE_RELEASE_KEY_ALIAS` and `FORGE_RELEASE_KEY_PASSWORD`.

Do not combine the methods. Missing, partial or invalid release inputs fail the signing check. The keystore and properties must resolve outside the repository, and release signing must not fall back to the debug identity. Supply secrets through private configuration or the environment, never through command-line password arguments. Avoid verbose/debug logging, build scans and configuration caching while signing.

On Windows, the helper accepts only a private file path; it does not generate keys, create backups or publish:

```powershell
.\tools\release\build-production.ps1 -SigningProperties 'D:\Private\Forge\signing.properties'
```

On other hosts, set the external path in the environment and use:

```sh
./gradlew --no-configuration-cache :app:assembleRelease
```

The production APK is generated at `app/build/outputs/apk/release/app-release.apk`. Debug remains independent: `assembleDebug` uses the standard development key. CI contains no production signing inputs and deliberately requests `-Pforge.allowUnsignedRelease=true` for an optimized unsigned verification build. That unsigned output is not installable and must not be distributed as a release. [CI details](ci.md)

## Verify an APK before distribution

Use the Android SDK Build Tools signer to check APK signatures and public certificate identity:

```sh
apksigner verify --verbose --print-certs Forge-1.4.3.apk
```

The certificate SHA-256 must match the production row above. Check for application ID `com.forge.workout`, version name `1.4.3` and version code `12`. Verify the complete APK file against the independently generated release manifest:

```sh
sha256sum --check SHA256SUMS.txt
```

On Windows, `Get-FileHash -Algorithm SHA256 -LiteralPath .\Forge-1.4.3.apk` computes the full APK checksum. The certificate fingerprint identifies the signer. The APK file checksum identifies the exact downloaded bytes. Align before signing and keep the signed APK intact. [apksigner verification](https://developer.android.com/tools/apksigner)
