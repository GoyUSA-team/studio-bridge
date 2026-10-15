# Studio Bridge 0.2 — DXVK Splash Screen Fix, OAuth Bridge & Pro Control Center

Unofficial fan-made Winlator-based experiment toward Roblox Studio on Android. Not affiliated with, endorsed by, or associated with Roblox Corporation. Use at your own risk.

### What's New in This Build
- **Fixed DXVK & Splash Screen Hang:**
  - **In-Place `kernelbase.dll` PE Export Patch:** Automatically patches Wine 10.10's 64-bit and 32-bit `kernelbase.dll` to export `VirtualProtectFromApp` (forwarding to `kernel32.VirtualProtect`), preventing `RobloxCrashHandler.exe` and `RobloxStudioBeta.exe` from hanging on the splash screen.
  - **Native UCRT & Mimalloc Bridge:** Syncs Studio's bundled native UCRT/MSVC runtime DLLs (`ucrtbase.dll`, `msvcp140*.dll`, `vcruntime140*.dll`, `concrt140.dll`, `vccorlib140.dll`) into `system32`, removes `ucrtbase` from `KnownDLLs`, and sets `MIMALLOC_FORCE_REDIRECT=1`.
  - **DXVK + Vortek Compatibility (`dxvk.conf` & `ClientAppSettings.json`):** Defaults to DXVK 1.10.3 on Vortek with `dxgi.deferSurfaceCreation = True`, `dxvk.enableGraphicsPipelineLibrary = False`, disables `d3d12`/`vkd3d` and `dxdiagn` probes, configures `ClientSettings/ClientAppSettings.json` (`FFlagDebugGraphicsPreferD3D11`), sets `QSG_RHI_BACKEND=software` for Qt6 login panels, and auto-raises Studio dialogs above the splash screen.
- **Self-Contained Pro Control Center UI (No Winlator Homepage):**
  - Redesigned dark gradient UI with sharp-edged (`0dp`) double-stroke buttons (`LayerDrawable` inner + outer borders), condensed bold typography, built-in runtime engine (`rootfs.tzst`) installer, and full persisted settings (`studio_bridge_prefs`).
  - Completely eliminates navigation to the stock Winlator homepage (`MainActivity`).
- **Enhanced In-App OAuth Login Bridge & Background Keep-Alive:**
  - Full-screen in-app WebView OAuth login with JavaScript & subresource `roblox-studio-auth://` callback interception, direct `CreateProcessW` batch delivery (without `start.exe` argument mangling), and persistent foreground service keep-alive.

