# Studio Bridge 0.1 — validation record

Unofficial fan-made compatibility experiment, not associated with Roblox Corporation. Use at your own risk. No functioning Android Studio port is claimed.

## Established by the build and test tools

- APK assembled successfully from the included source, including native ARM64 libraries.
- APK signature verification passed (APK Signature Scheme v2, local Android Debug certificate). This verifies package signature integrity; it does not prove safety, compatibility, or Roblox endorsement.
- APK bytes: 161,456,392; SHA-256: `4963d7bc6dadd3ab859f22eb1b004c98a468bf2bfa73d1f7e361d39c4c98ed4c`.
- Package ID: `com.winlator` (not a side-by-side installation); version `11.2-studiobridge-0.1`.
- Launcher: `com.winlator.studiobridge.StudioHubActivity`.
- Minimum SDK 26, inherited target SDK 28, compile SDK 35, native ABI arm64-v8a.
- 29 platform-independent deployment-file validation tests passed.
- 9 official-host allowlist, current-version marker and install-lock tests passed without network requests.
- A clean host-side end-to-end deployment test passed against Roblox's live release `version-4f087c32742644ab`, reported as `0.743.0.7431133` at test time. All package downloads used the new installer, with compressed size/MD5 checks, contained extraction, and final executable PE checks. The resolved Studio executable was 225,310,672 bytes. The program was never executed.
- A second call on an existing complete installation resolved the current executable without redownloading the application packages.
- End-to-end testing exposed Windows-style empty/rooted ZIP directory markers; directory normalization was corrected and explicit validation tests were added before the clean successful run.

## Checks that did not pass cleanly

Android lint reported **1 error and 489 warnings**. The error is the inherited `targetSdkVersion 28` being too old for Google Play. It was not suppressed, the target was not raised without migrating upstream storage/runtime behavior, and this APK is not represented as Play Store-ready. Warnings include upstream legacy/API/security/UI findings and six findings in the new code: conservative usable-space API choice, a programmatic-only view constructor, two touch accessibility warnings, and two English text localization warnings. Native compilation also produced upstream C/C++ warnings. Full lint and build records are in `verification/`.

## Not verified — do not confuse compilation with a working port

- Android APK installation or launch; no Android device/emulator was available.
- Native Android visual QA, screen layouts, font scaling, touch interaction, multitouch or accessibility operation.
- Winlator runtime extraction/initialization on a real device using this APK.
- Studio launch through Wine/Box64, Roblox login, 3D viewport rendering, opening/saving projects, editing, playtesting, publishing, reliability or security.
- Any Mali device/driver model, Vulkan feature coverage, performance, thermal behavior, 16 KB Android page-size support, or modern Android permission restrictions.
- Side-by-side installation/data migration; explicitly unsupported in this prototype.
- Independent Authenticode verification of Roblox packages or a legal/security audit of all upstream bundled binaries.

This is an experimental debug build for further investigation, not a finished or production-safe port. Read `README-STUDIO-BRIDGE.md` before installation. In particular, **do not uninstall an existing Winlator app or delete its data without a verified backup**.
