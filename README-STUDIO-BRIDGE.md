# Studio Bridge — experimental Winlator customization

**Unofficial, fan-made experiment toward a Roblox Studio Android port. Not affiliated with, endorsed by, or associated with Roblox Corporation. Use at your own risk.** This is a Winlator-based compatibility experiment, not a native Studio port. Successful Studio launch, Mali rendering, login, and editing have **not** been verified on an Android device.

## Important installation limitations

- This debug build **retains `com.winlator`**, Winlator's original Android application ID. Its bundled native runtime contains hardcoded package paths. It is **not a side-by-side installation** and will normally conflict with an existing differently signed Winlator app.
- **Do not uninstall an existing Winlator app or delete its data without a verified backup.** A spare device with no existing Winlator data is preferable. No migration, backup, or data-preservation claim is made.
- ARM64 Android, Android 8/API 26 minimum, compile API 35, inherited target API 28. No guarantee for newer Android restrictions, 16 KB page-size devices, or any particular Mali model.
- This is **debug-signed**, not a production or Play Store release. Android UI/installation/launch testing and visual QA could not be performed in the build sandbox. Do not disable device security protections to force installation.
- The upstream icon and runtime remain; custom branding does not imply Roblox or Winlator developer endorsement.

## Implemented in this prototype

1. A dedicated setup activity with persistent unofficial/non-affiliation/risk messaging.
2. Dedicated Studio containers; existing non-Studio containers are not modified by the setup screen.
3. Two **unbenchmarked experimental presets**, both avoiding Turnip:
   - Compatibility: VirGL OpenGL path + WineD3D.
   - Vulkan: Vortek + DXVK, default/system Vulkan driver.
   Both use Box64 Stability, normal startup, and a 1280×720 or optional 960×540 desktop. This is configuration work, not a new Mali driver or a proven performance optimization.
4. Larger cursor and slower relative pointer defaults on fresh preferences, without overwriting existing preference choices.
5. An independent Studio deployment installer: live WindowsStudio64 release lookup, official HTTPS host allowlist, package size/MD5 checks against Roblox's manifest, canonical ZIP-path containment, Windows directory-marker normalization, extraction-size caps, cancellation, install locking, staging, preserved previous releases, and a current-version marker.
6. A launch action wired to Winlator's existing `exec_path` mechanism.
7. A Studio-session touch toolbar: unofficial notice, keyboard, Undo, Redo, Save, Esc, F5, Focus, held W/A/S/D and right mouse. Held inputs release on cancel, activity pause, and view detach. Touch operation is untested.

No Roblox executables, assets, authentication tokens, cookies, passwords, or decompiled Roblox code are bundled in this source distribution or APK. The existing Winlator runtime assets remain. Studio packages are fetched from Roblox only when the user explicitly starts the download. The app does not implement its own Roblox login; any login occurs in Studio itself.

## On-device test flow (not a claim of compatibility)

1. Read the installation limitations above. Do not replace an existing Winlator installation that contains data you need.
2. Open Studio Bridge. Use **Open Winlator setup / settings**, complete upstream runtime setup, then return to the Bridge screen.
3. Choose **Compatibility** and a workspace resolution. Create a dedicated Studio container.
4. Choose **Download / update Studio from Roblox** and read the confirmation. Allow several GB free storage for the runtime, downloads, installation and test projects. Package sizes change over time.
5. Choose **Launch Studio · experimental**. A launch request does not establish a working session.
6. If it fails, close the session before applying the Vulkan preset and retrying. Do not spoof GPU capabilities, install Adreno-only drivers on Mali, or bypass Roblox protections.
7. Enable upstream Wine/Box64 logs in Settings and inspect Logs in the session menu. Share only non-sensitive error details; do not share authentication cookies, tokens or passwords.
8. Four-finger tap opens Winlator's session menu. The bottom toolbar scrolls horizontally; use Keyboard for text entry. Save a disposable local project before testing important work or publishing.

## Source provenance and licensing

- Main Winlator repository: https://github.com/brunodev85/winlator
  pinned commit `b6b2259158cf38d06067c34430d840d56b46d220`.
- Android app source: https://github.com/brunodev85/winlator-app
  pinned commit `a030f552f452158a2db64fdb32b490fa19c0b48d`.
- Upstream `LICENSE` (LGPL 2.1) and `README.md` credits remain unchanged. New Studio Bridge Java files are LGPL-2.1-or-later. Third-party components retain their own licenses/notices in the source tree and upstream projects. This is not a legal audit of upstream bundled artifacts.
- The original upstream README is preserved separately from this customization guide.
- The source archive includes the modified standalone Android app tree, original assets/native inputs, custom source and tests. It excludes Gradle caches, SDK/NDK tools, local paths, signing keys, downloaded Roblox packages, `.git`, and generated build outputs. Build scripts download their own standard dependencies.

## Rebuilding

Install a JDK supported by Gradle (the tested build used JDK 21), Android SDK platform 35, build-tools 34.0.0, NDK 24.0.8215888 and CMake 3.22.1. Set `ANDROID_HOME` or create your own `local.properties` with `sdk.dir`. Do not copy another machine's SDK path.

```sh
chmod +x gradlew
./gradlew --no-daemon --max-workers=2 assembleDebug
```

The debug APK is generated under `app/build/outputs/apk/debug/`. Its debug signing key is local to the build machine; the delivered private key is deliberately not included. A rebuild will not necessarily update an already installed prototype signed by another machine.

Run platform-independent installer validation (requires a normal `org.json` implementation for the second test; Android's SDK stubs cannot execute on a JVM):

```sh
mkdir -p /tmp/studio-tests
javac -cp /path/to/json.jar -d /tmp/studio-tests \
  app/src/main/java/com/winlator/studiobridge/DeploymentFiles.java \
  app/src/main/java/com/winlator/studiobridge/StudioDeployment.java \
  tests/studiobridge/*.java
java -cp /tmp/studio-tests:/path/to/json.jar com.winlator.studiobridge.DeploymentFilesTest
java -cp /tmp/studio-tests:/path/to/json.jar com.winlator.studiobridge.StudioDeploymentTest
```

## Validation boundaries

Read `VALIDATION-STUDIO-BRIDGE.md` and the included verification logs. An APK compilation/signature check or host-side package extraction test does not establish Android installation, UI correctness, graphics support, or Studio functionality. Android lint has a known error for the inherited legacy target SDK and many warnings; those findings are not hidden or represented as a clean pass.
