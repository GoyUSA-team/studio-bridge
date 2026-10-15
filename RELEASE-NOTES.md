# Experimental prerelease — not a working-port claim

Unofficial fan-made Winlator-based experiment toward Roblox Studio on Android. Not affiliated with, endorsed by, or associated with Roblox Corporation. Use at your own risk.

This CI job builds an ARM64 debug APK from pinned upstream source plus the source patch. It does not run Android, Wine, Roblox Studio or GPU tests. Mali rendering, Studio login/editing, installation and UI are unverified. Both graphics presets are experimental, not measured optimizations.

**Package conflict:** uses `com.winlator`; not a side-by-side installation. Do not uninstall existing Winlator or delete its data without a verified backup. Debug signing differs between independent build environments.

The APK contains no Roblox Studio binaries. The in-app installer downloads them from Roblox only after confirmation. Existing upstream runtime/native components remain.

Target SDK 28 and other inherited warnings remain; this is not a production/Play Store release. Read the setup and validation documents before testing. APK and ZIP assets are convenience experimental builds, not assurances of compatibility or safety.
