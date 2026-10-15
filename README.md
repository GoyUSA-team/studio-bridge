# Studio Bridge — experimental Winlator customization

**Unofficial, fan-made project. Not affiliated with, endorsed by, or associated with Roblox Corporation. Use at your own risk. Android operation is unverified.** This is not a verified working Roblox Studio port.

## Downloads

The build workflow is intended to create a prerelease under this repository's **Releases** page. **Until the workflow finishes successfully, no downloadable APK release is established.** The repository is private; sign in with the owning account to access it.

Release assets: Android APK, APK-in-ZIP, reconstructed source ZIP, and SHA-256 checksums. GitHub Actions rebuilds from source, so the APK will have a new debug signature and checksum rather than being byte-for-byte identical to the local sandbox build. Do not try to update a differently signed installed prototype.

**Warning:** the application retains `com.winlator` and cannot install alongside standard Winlator. Do not uninstall an existing Winlator app or delete its data without a verified backup. Android installation, native visual QA, Studio launch, login, Mali rendering, and editing are untested.

## Source layout

- `studio-bridge-changes.patch`: all modifications against the pinned upstream app source, including new source, setup guide, tests, validation notes, and local test records.
- `custom-source/`: browsable copies of the four new Java classes.
- `scripts/build-studio-bridge.sh`: obtains upstream commit `a030f552f452158a2db64fdb32b490fa19c0b48d`, checks/applies the patch, and assembles the APK and source distribution.
- `.github/workflows/build.yml`: bounded build and experimental prerelease publishing.
- `LICENSE`: preserved upstream LGPL 2.1 license; third-party components retain their own licenses and credits.

The source patch, not the convenience `custom-source` copies, is authoritative for builds. Original Winlator project: https://github.com/brunodev85/winlator and Android source: https://github.com/brunodev85/winlator-app .

## Features implemented, not device-verified

Dedicated Studio setup and containers; official-CDN deployment installer; unbenchmarked VirGL/WineD3D and Vortek/DXVK test presets avoiding Turnip; workspace resolution options; larger touch toolbar. No new Mali driver or guaranteed performance improvement is claimed.

No Roblox binaries, decompiled Roblox source, login tokens, cookies, passwords, or signing keys are included in this repository. Studio is downloaded directly from Roblox only when a user requests it in the app.

Local compilation, APK signature/structure checks, 38 installer validation checks, and a clean host-side full Studio deployment download/extraction passed. Android lint remains non-clean: the inherited target API 28 causes one Play target-policy error and there are additional warnings. See `VALIDATION-STUDIO-BRIDGE.md` for details and testing boundaries.

The repository does not preserve or expose the access token pasted into the chat. Revoke exposed credentials separately.
