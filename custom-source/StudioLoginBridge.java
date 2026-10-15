/* Studio Bridge additions, 2026. Distributed under LGPL-2.1-or-later. */
package com.winlator.studiobridge;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.winlator.XServerDisplayActivity;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.EnvVars;
import com.winlator.core.WineRegistryEditor;
import com.winlator.core.WineUtils;
import com.winlator.winhandler.WinHandler;
import com.winlator.xenvironment.RootFS;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Bridges Roblox Studio's OAuth browser login and Wine 10.10 runtime compatibility inside Winlator:
 * 1. Patches Wine 10.10's kernelbase.dll (64-bit & 32-bit) in-place to export VirtualProtectFromApp
 *    (forwarding to VirtualProtect), preventing RobloxCrashHandler.exe from crashing on startup
 *    and hanging RobloxStudioBeta.exe indefinitely on the splash screen (Vinegar Issue #591/#592).
 * 2. Syncs Studio's bundled native UCRT/MSVC runtime DLLs (ucrtbase.dll, msvcp140*.dll,
 *    vcruntime140*.dll, concrt140.dll, vccorlib140.dll) into system32, removes ucrtbase from
 *    Wine's KnownDLLs, and sets MIMALLOC_FORCE_REDIRECT=1 so Studio's mimalloc allocator hooks
 *    succeed without crashing (Kombucha patches 0002/0004/0012).
 * 3. Writes ClientSettings/ClientAppSettings.json to configure Studio's rendering backend
 *    (D3D11 for DXVK/WineD3D or Vulkan) and disables dxdiagn.dll so Studio never hangs probing
 *    unsupported Vulkan 1.3 extensions or DirectX diagnostics during the splash screen.
 * 4. Intercepts Wine browser launch requests (RequestCodes.OPEN_URL) with a full-screen in-app
 *    OAuth bridge modal and delivers callback URIs into the running Wine session.
 */
public final class StudioLoginBridge {
    public static final String DEFAULT_AUTH_URL = "https://authorize.roblox.com/";
    public static final String STUDIO_AUTH_BAT_DOS = "C:\\StudioBridge\\studio-auth.bat";
    public static final String STUDIO_PROTOCOL_REG_DOS = "C:\\StudioBridge\\studio-protocol.reg";
    public static final String PREFS_NAME = "studio_bridge_prefs";
    private static final long EXTERNAL_AUTH_GRACE_MS = 10 * 60 * 1000L;

    private static final String[] STUDIO_NATIVE_RUNTIME_DLLS = new String[]{
            "ucrtbase.dll",
            "concrt140.dll",
            "msvcp140.dll",
            "msvcp140_1.dll",
            "msvcp140_2.dll",
            "msvcp140_codecvt_ids.dll",
            "vccorlib140.dll",
            "vcruntime140.dll",
            "vcruntime140_1.dll"
    };

    private static volatile long externalAuthStartedAt = 0L;
    private static Dialog activeLoginDialog;
    private static WebView activeWebView;
    private static String lastAuthUrl = DEFAULT_AUTH_URL;
    private static String lastCallbackUri;

    private StudioLoginBridge() {}

    /**
     * Patches a Wine PE kernelbase.dll byte array in-place to export "VirtualProtectFromApp"
     * pointing to the existing "VirtualProtect" RVA.
     * Returns true if the byte array was modified, false if already patched or not applicable.
     */
    public static boolean patchKernelbaseBytes(byte[] pe) {
        if (pe == null || pe.length < 0x200) return false;
        ByteBuffer buf = ByteBuffer.wrap(pe).order(ByteOrder.LITTLE_ENDIAN);
        if ((pe[0] & 0xFF) != 'M' || (pe[1] & 0xFF) != 'Z') return false;

        int peOffset = buf.getInt(0x3C);
        if (peOffset <= 0 || peOffset + 0x90 > pe.length) return false;
        if (buf.getInt(peOffset) != 0x00004550) return false; // "PE\0\0"

        int numSections = buf.getShort(peOffset + 6) & 0xFFFF;
        int optHeaderSize = buf.getShort(peOffset + 20) & 0xFFFF;
        int optOffset = peOffset + 24;
        int magic = buf.getShort(optOffset) & 0xFFFF;
        if (magic != 0x20B && magic != 0x10B) return false;

        int secTableOffset = optOffset + optHeaderSize;
        int edataSecOffset = -1;
        int edataVa = 0;
        int edataVSize = 0;
        int edataRawPtr = 0;
        int edataRawSize = 0;

        for (int i = 0; i < numSections; i++) {
            int sOff = secTableOffset + i * 40;
            if (sOff + 40 > pe.length) return false;
            String name = readAsciiZeroTerminated(pe, sOff, 8);
            if (".edata".equals(name)) {
                edataSecOffset = sOff;
                edataVSize = buf.getInt(sOff + 8);
                edataVa = buf.getInt(sOff + 12);
                edataRawSize = buf.getInt(sOff + 16);
                edataRawPtr = buf.getInt(sOff + 20);
                break;
            }
        }
        if (edataSecOffset < 0 || edataRawPtr <= 0 || edataRawPtr + edataRawSize > pe.length) {
            return false;
        }

        int numFunctions = buf.getInt(edataRawPtr + 20);
        int numNames = buf.getInt(edataRawPtr + 24);
        int addrFunctionsRva = buf.getInt(edataRawPtr + 28);
        int addrNamesRva = buf.getInt(edataRawPtr + 32);
        int addrOrdinalsRva = buf.getInt(edataRawPtr + 36);

        if (numFunctions <= 0 || numNames <= 0 || numFunctions > 8192 || numNames > 8192) {
            return false;
        }

        int funcsFileOff = edataRawPtr + (addrFunctionsRva - edataVa);
        int namesFileOff = edataRawPtr + (addrNamesRva - edataVa);
        int ordsFileOff = edataRawPtr + (addrOrdinalsRva - edataVa);

        // Expect standard Wine .edata layout: Functions -> Names -> Ordinals -> Strings
        if (namesFileOff != funcsFileOff + numFunctions * 4) return false;
        if (ordsFileOff != namesFileOff + numNames * 4) return false;

        String targetName = "VirtualProtectFromApp";
        byte[] targetBytes = (targetName + "\0").getBytes(StandardCharsets.US_ASCII);
        int shift = 16; // 4 + 4 + 2 = 10 bytes table growth, aligned to 16 bytes
        int extraNeeded = shift + targetBytes.length + 4;
        if (edataVSize + extraNeeded > edataRawSize) {
            return false;
        }

        // Verify padding space at the end of .edata is clean zeros
        int checkStart = edataRawPtr + edataVSize;
        int checkEnd = Math.min(edataRawPtr + edataRawSize, checkStart + extraNeeded);
        for (int i = checkStart; i < checkEnd; i++) {
            if (pe[i] != 0) return false;
        }

        int vpRva = -1;
        int insertIdx = numNames;
        int maxStrEndRva = 0;

        for (int i = 0; i < numNames; i++) {
            int nameRva = buf.getInt(namesFileOff + i * 4);
            int nameFileOff = edataRawPtr + (nameRva - edataVa);
            if (nameFileOff < edataRawPtr || nameFileOff >= edataRawPtr + edataRawSize) {
                return false;
            }
            String exportName = readAsciiZeroTerminated(pe, nameFileOff, 256);
            int strEndRva = nameRva + exportName.length() + 1;
            if (strEndRva > maxStrEndRva) maxStrEndRva = strEndRva;

            if (targetName.equals(exportName)) {
                return false; // Already patched!
            }
            if ("VirtualProtect".equals(exportName)) {
                int ord = buf.getShort(ordsFileOff + i * 2) & 0xFFFF;
                if (ord < numFunctions) {
                    vpRva = buf.getInt(funcsFileOff + ord * 4);
                }
            }
            if (insertIdx == numNames && exportName.compareTo(targetName) > 0) {
                insertIdx = i;
            }
        }

        if (vpRva <= 0) return false;

        int stringsStartRva = addrOrdinalsRva + numNames * 2;
        int stringsStartFileOff = edataRawPtr + (stringsStartRva - edataVa);
        int stringsEndRva = Math.max(maxStrEndRva, edataVa + edataVSize);
        int stringsLen = stringsEndRva - stringsStartRva;
        if (stringsLen <= 0 || stringsStartFileOff + stringsLen + shift + targetBytes.length > edataRawPtr + edataRawSize) {
            return false;
        }

        byte[] funcsBlock = new byte[numFunctions * 4];
        System.arraycopy(pe, funcsFileOff, funcsBlock, 0, funcsBlock.length);

        int[] nameRvas = new int[numNames];
        int[] ordinals = new int[numNames];
        for (int i = 0; i < numNames; i++) {
            nameRvas[i] = buf.getInt(namesFileOff + i * 4);
            ordinals[i] = buf.getShort(ordsFileOff + i * 2) & 0xFFFF;
        }

        byte[] stringsBlock = new byte[stringsLen];
        System.arraycopy(pe, stringsStartFileOff, stringsBlock, 0, stringsLen);

        // Shift forwarder RVAs inside AddressOfFunctions if they point into the strings block
        ByteBuffer funcsBuf = ByteBuffer.wrap(funcsBlock).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < numFunctions; i++) {
            int fRva = funcsBuf.getInt(i * 4);
            if (fRva >= stringsStartRva && fRva < edataVa + edataVSize) {
                funcsBuf.putInt(i * 4, fRva + shift);
            }
        }

        int newNumFunctions = numFunctions + 1;
        int newNumNames = numNames + 1;
        int newOrd = numFunctions; // Append at end of AddressOfFunctions so existing ordinals never change

        int newStringsStartRva = stringsStartRva + shift;
        int newStringsStartFileOff = edataRawPtr + (newStringsStartRva - edataVa);

        // Write shifted string block and append "VirtualProtectFromApp\0"
        System.arraycopy(stringsBlock, 0, pe, newStringsStartFileOff, stringsLen);
        int newTargetStrRva = newStringsStartRva + stringsLen;
        int newTargetStrFileOff = edataRawPtr + (newTargetStrRva - edataVa);
        System.arraycopy(targetBytes, 0, pe, newTargetStrFileOff, targetBytes.length);

        // Write updated AddressOfFunctions (with vpRva appended at index numFunctions)
        System.arraycopy(funcsBlock, 0, pe, funcsFileOff, funcsBlock.length);
        buf.putInt(funcsFileOff + numFunctions * 4, vpRva);

        // Write updated AddressOfNames and AddressOfNameOrdinals
        int newNamesRva = addrFunctionsRva + newNumFunctions * 4;
        int newOrdsRva = newNamesRva + newNumNames * 4;
        int newNamesFileOff = edataRawPtr + (newNamesRva - edataVa);
        int newOrdsFileOff = edataRawPtr + (newOrdsRva - edataVa);

        for (int i = 0; i < newNumNames; i++) {
            int rvaVal;
            int ordVal;
            if (i < insertIdx) {
                rvaVal = nameRvas[i] + shift;
                ordVal = ordinals[i];
            } else if (i == insertIdx) {
                rvaVal = newTargetStrRva;
                ordVal = newOrd;
            } else {
                rvaVal = nameRvas[i - 1] + shift;
                ordVal = ordinals[i - 1];
            }
            buf.putInt(newNamesFileOff + i * 4, rvaVal);
            buf.putShort(newOrdsFileOff + i * 2, (short) (ordVal & 0xFFFF));
        }

        // Zero any padding gap between end of new ordinals table and start of shifted strings
        int newOrdsEndFileOff = newOrdsFileOff + newNumNames * 2;
        for (int i = newOrdsEndFileOff; i < newStringsStartFileOff; i++) {
            pe[i] = 0;
        }

        // Update export directory header
        int exportDirNameRva = buf.getInt(edataRawPtr + 12);
        if (exportDirNameRva >= stringsStartRva && exportDirNameRva < edataVa + edataVSize) {
            buf.putInt(edataRawPtr + 12, exportDirNameRva + shift);
        }
        buf.putInt(edataRawPtr + 20, newNumFunctions);
        buf.putInt(edataRawPtr + 24, newNumNames);
        buf.putInt(edataRawPtr + 32, newNamesRva);
        buf.putInt(edataRawPtr + 36, newOrdsRva);

        // Update .edata VirtualSize and OptionalHeader Export DataDirectory Size
        int newEdataVSize = (newTargetStrRva + targetBytes.length) - edataVa;
        buf.putInt(edataSecOffset + 8, newEdataVSize);
        int exportDataDirOffset = optOffset + (magic == 0x20B ? 112 : 96);
        if (exportDataDirOffset + 8 <= pe.length) {
            buf.putInt(exportDataDirOffset + 4, newEdataVSize);
        }
        return true;
    }

    private static String readAsciiZeroTerminated(byte[] data, int offset, int maxLen) {
        int end = offset;
        int limit = Math.min(data.length, offset + maxLen);
        while (end < limit && data[end] != 0) {
            end++;
        }
        return new String(data, offset, end - offset, StandardCharsets.US_ASCII);
    }

    public static boolean patchKernelbaseDll(File dllFile) {
        if (dllFile == null || !dllFile.isFile()) return false;
        try {
            byte[] bytes = readAllBytes(dllFile);
            if (!patchKernelbaseBytes(bytes)) return false;
            File tmp = new File(dllFile.getParentFile(), dllFile.getName() + ".studiotmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(bytes);
                fos.getFD().sync();
            }
            if (!tmp.renameTo(dllFile)) {
                try (FileOutputStream fos = new FileOutputStream(dllFile)) {
                    fos.write(bytes);
                    fos.getFD().sync();
                }
                tmp.delete();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void patchRootFSKernelbase(File rootDir) {
        if (rootDir == null || !rootDir.isDirectory()) return;
        String[] relativePaths = new String[]{
                "opt/wine/lib/wine/x86_64-windows/kernelbase.dll",
                "opt/wine/lib/wine/i386-windows/kernelbase.dll",
                ".wine/drive_c/windows/system32/kernelbase.dll",
                ".wine/drive_c/windows/syswow64/kernelbase.dll"
        };
        for (String rel : relativePaths) {
            File target = new File(rootDir, rel);
            if (target.isFile()) {
                patchKernelbaseDll(target);
            }
        }
    }

    public static String buildClientAppSettingsJson(String rendererMode) {
        String mode = rendererMode != null ? rendererMode.trim() : "D3D11";
        String activeRenderer;
        if ("Vulkan".equalsIgnoreCase(mode)) {
            activeRenderer = "Vulkan";
        } else if ("OpenGL".equalsIgnoreCase(mode)) {
            activeRenderer = "OpenGL";
        } else if ("D3D11FL10".equalsIgnoreCase(mode)) {
            activeRenderer = "D3D11FL10";
        } else {
            activeRenderer = "D3D11";
        }

        String[] allRenderers = new String[]{"D3D11", "D3D11FL10", "Vulkan", "OpenGL"};
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        for (String avail : allRenderers) {
            boolean isCurrent = avail.equals(activeRenderer);
            sb.append("  \"FFlagDebugGraphicsPrefer").append(avail).append("\": ").append(isCurrent).append(",\n");
            sb.append("  \"FFlagDebugGraphicsDisable").append(avail).append("\": ").append(!isCurrent).append(",\n");
        }
        sb.append("  \"FLogStudioLuauPlugin\": 0\n");
        sb.append("}\n");
        return sb.toString();
    }

    public static void writeClientAppSettings(File studioExe, String rendererMode) {
        if (studioExe == null || !studioExe.isFile()) return;
        File versionDir = studioExe.getParentFile();
        if (versionDir == null) return;
        try {
            File clientSettingsDir = new File(versionDir, "ClientSettings");
            DeploymentFiles.mkdir(clientSettingsDir);
            File settingsFile = new File(clientSettingsDir, "ClientAppSettings.json");
            DeploymentFiles.write(settingsFile, buildClientAppSettingsJson(rendererMode));
        } catch (Exception ignored) {}
    }

    public static void syncStudioRuntimeDlls(File studioExe, File containerRoot, boolean useDxvk) {
        if (studioExe == null || !studioExe.isFile() || containerRoot == null) return;
        File versionDir = studioExe.getParentFile();
        File system32Dir = new File(containerRoot, ".wine/drive_c/windows/system32");
        if (versionDir == null || !system32Dir.isDirectory()) return;

        // Copy Studio's native UCRT & MSVC runtime DLLs into system32 so mimalloc-redirect.dll
        // hooks the native ucrtbase.dll in system32 without page-faulting or asserting.
        for (String dllName : STUDIO_NATIVE_RUNTIME_DLLS) {
            File src = new File(versionDir, dllName);
            if (src.isFile()) {
                File dst = new File(system32Dir, dllName);
                try {
                    if (!dst.isFile() || dst.length() != src.length()) {
                        copyFile(src, dst);
                    }
                } catch (Exception ignored) {}
            }
        }

        // Remove any local d3d11.dll/dxgi.dll from versionDir so Wine always loads the active
        // DXVK or WineD3D DLLs managed by Winlator in C:\windows\system32.
        for (String dxDll : new String[]{"d3d11.dll", "dxgi.dll", "d3d10core.dll", "d3d9.dll"}) {
            File localDxDll = new File(versionDir, dxDll);
            if (localDxDll.isFile()) {
                localDxDll.delete();
            }
        }
    }

    public static void prepareStudioEnvironment(Context context, RootFS rootFS, Container container) {
        if (rootFS != null) {
            patchRootFSKernelbase(rootFS.getRootDir());
        }
        if (container != null) {
            patchRootFSKernelbase(container.getRootDir());
        }
        configureWineRegistry(context, container);
    }

    public static void configureWineRegistry(Container container) {
        configureWineRegistry(null, container);
    }

    public static void configureWineRegistry(Context context, Container container) {
        if (container == null) return;
        try {
            SharedPreferences prefs = context != null
                    ? context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    : null;
            boolean mimallocFix = prefs == null || prefs.getBoolean("pref_mimalloc_fix", true);
            boolean disableDxdiagn = prefs == null || prefs.getBoolean("pref_disable_dxdiagn", true);
            boolean webview2Fix = prefs == null || prefs.getBoolean("pref_webview2_fix", true);
            boolean darkTheme = prefs == null || prefs.getBoolean("pref_dark_theme", true);
            int wineDpi = prefs != null ? prefs.getInt("pref_wine_dpi", 98) : 98;
            String rendererPref = prefs != null ? prefs.getString("pref_studio_renderer", "Auto") : "Auto";

            String dxWrapper = container.getDXWrapper();
            boolean useDxvk = dxWrapper != null && dxWrapper.contains("dxvk");

            String resolvedRenderer;
            if ("Vulkan".equalsIgnoreCase(rendererPref)) {
                resolvedRenderer = "Vulkan";
            } else if ("OpenGL".equalsIgnoreCase(rendererPref)) {
                resolvedRenderer = "OpenGL";
            } else if ("D3D11FL10".equalsIgnoreCase(rendererPref)) {
                resolvedRenderer = "D3D11FL10";
            } else if ("D3D11".equalsIgnoreCase(rendererPref)) {
                resolvedRenderer = "D3D11";
            } else {
                // Auto: D3D11 for DXVK and WineD3D, Vulkan only if Native Vulkan preset is selected
                String presetMode = prefs != null ? prefs.getString("pref_graphics_preset", "DXVK_1103_VORTEK") : "DXVK_1103_VORTEK";
                resolvedRenderer = "VULKAN_NATIVE".equals(presetMode) ? "Vulkan" : "D3D11";
            }

            File rootDir = container.getRootDir();
            patchRootFSKernelbase(rootDir);

            File exe = StudioDeployment.currentExecutable(rootDir);
            if (exe != null) {
                writeClientAppSettings(exe, resolvedRenderer);
                if (mimallocFix) {
                    syncStudioRuntimeDlls(exe, rootDir, useDxvk);
                }
            }

            File marker = new File(StudioDeployment.base(rootDir), "current-version.txt");
            String version = marker.isFile() ? DeploymentFiles.read(marker).trim() : "";
            String dosExePath = exe != null ? WineUtils.unixToDOSPath(exe.getAbsolutePath(), container) : null;
            if (dosExePath != null && !version.isEmpty()) {
                File base = StudioDeployment.base(rootDir);
                DeploymentFiles.mkdir(base);
                DeploymentFiles.write(new File(base, "studio-protocol.reg"), buildProtocolRegContent(dosExePath, version));
            }

            String command = dosExePath != null ? "\"" + dosExePath + "\" \"%1\"" : null;
            String icon = dosExePath != null ? dosExePath + ",0" : null;

            for (String regName : new String[]{".wine/user.reg", ".wine/system.reg"}) {
                File regFile = new File(rootDir, regName);
                if (!regFile.isFile()) continue;
                try (WineRegistryEditor editor = new WineRegistryEditor(regFile)) {
                    if (command != null && icon != null) {
                        editor.setStringValue("Software\\Classes\\roblox-studio", null, "URL: Roblox Protocol");
                        editor.setStringValue("Software\\Classes\\roblox-studio", "URL Protocol", "");
                        editor.setStringValue("Software\\Classes\\roblox-studio\\DefaultIcon", null, icon);
                        editor.setStringValue("Software\\Classes\\roblox-studio\\shell\\open\\command", null, command);

                        editor.setStringValue("Software\\Classes\\roblox-studio-auth", null, "URL: Roblox Studio Auth Protocol");
                        editor.setStringValue("Software\\Classes\\roblox-studio-auth", "URL Protocol", "");
                        editor.setStringValue("Software\\Classes\\roblox-studio-auth\\DefaultIcon", null, icon);
                        editor.setStringValue("Software\\Classes\\roblox-studio-auth\\shell\\open\\command", null, command);

                        editor.setStringValue("Software\\ROBLOX Corporation\\Environments\\RobloxStudio", "version", version);
                        editor.setStringValue("Software\\ROBLOX Corporation\\Environments\\RobloxStudio", "clientExePath", dosExePath);
                        editor.setStringValue("Software\\ROBLOX Corporation\\Environments\\RobloxStudio\\Channel", "www.roblox.com", "LIVE");
                    }

                    editor.setStringValue("Software\\Wine\\WineBrowser", "Browsers", "C:\\windows\\winhandler.exe /url");

                    // DLL overrides for Studio runtime stability
                    if (mimallocFix) {
                        for (String dll : STUDIO_NATIVE_RUNTIME_DLLS) {
                            String dllBase = dll.substring(0, dll.length() - 4);
                            editor.setStringValue("Software\\Wine\\DllOverrides", dllBase, "native,builtin");
                        }
                    }
                    if (useDxvk) {
                        editor.setStringValue("Software\\Wine\\DllOverrides", "d3d11", "native,builtin");
                        editor.setStringValue("Software\\Wine\\DllOverrides", "dxgi", "native,builtin");
                        editor.setStringValue("Software\\Wine\\DllOverrides", "d3d10core", "native,builtin");
                        editor.setStringValue("Software\\Wine\\DllOverrides", "d3d9", "native,builtin");
                    } else {
                        editor.setStringValue("Software\\Wine\\DllOverrides", "d3d11", "builtin");
                        editor.setStringValue("Software\\Wine\\DllOverrides", "dxgi", "builtin");
                        editor.setStringValue("Software\\Wine\\DllOverrides", "d3d10core", "builtin");
                    }
                    if (disableDxdiagn) {
                        editor.setStringValue("Software\\Wine\\DllOverrides", "dxdiagn", "");
                        editor.setStringValue("Software\\Wine\\DllOverrides", "d3d12", "");
                        editor.setStringValue("Software\\Wine\\DllOverrides", "d3d12core", "");
                        editor.setStringValue("Software\\Wine\\DllOverrides", "winemenubuilder.exe", "");
                        editor.setStringValue("Software\\Wine\\DllOverrides", "mscoree", "");
                        editor.setStringValue("Software\\Wine\\DllOverrides", "mshtml", "");
                    }
                    if (webview2Fix) {
                        editor.setStringValue("Software\\Wine\\AppDefaults\\msedgewebview2.exe", "Version", "win8");
                    }
                    if (wineDpi >= 96 && wineDpi <= 240) {
                        editor.setDwordValue("Control Panel\\Desktop", "LogPixels", wineDpi);
                        editor.setDwordValue("Software\\Fonts", "LogPixels", wineDpi);
                    }
                    if (darkTheme) {
                        editor.setDwordValue("Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "AppsUseLightTheme", 0);
                        editor.setDwordValue("Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "SystemUsesLightTheme", 0);
                    }
                    if (mimallocFix && ".wine/system.reg".equals(regName)) {
                        // Remove ucrtbase from KnownDLLs so Wine allows native ucrtbase.dll override
                        editor.removeValue("System\\CurrentControlSet\\Control\\Session Manager\\KnownDLLs", "ucrtbase");
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public static void applyStudioEnvVars(Context context, Container container, EnvVars envVars) {
        if (envVars == null) return;
        SharedPreferences prefs = context != null
                ? context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                : null;
        boolean mimallocFix = prefs == null || prefs.getBoolean("pref_mimalloc_fix", true);
        boolean disableDxdiagn = prefs == null || prefs.getBoolean("pref_disable_dxdiagn", true);
        boolean dxvkAsync = prefs == null || prefs.getBoolean("pref_dxvk_async", true);
        boolean qtSoftwareUi = prefs == null || prefs.getBoolean("pref_qt_software_ui", true);
        String fpsCap = prefs != null ? prefs.getString("pref_fps_cap", "0") : "0";

        String dxWrapper = container != null ? container.getDXWrapper() : "dxvk";
        boolean useDxvk = dxWrapper != null && dxWrapper.contains("dxvk");

        envVars.put("WINEBOOT_HIDE_DIALOG", "1");
        envVars.put("WINE_LARGE_ADDRESS_AWARE", "1");
        envVars.put("WINEESYNC", "1");
        envVars.put("WINEFSYNC", "1");
        envVars.put("XR_LOADER_DEBUG", "none");
        envVars.put("WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS", "--in-process-gpu --no-sandbox --disable-gpu");
        envVars.put("WINE_D3D_CONFIG", useDxvk ? "renderer=vulkan" : "renderer=gl");
        if (qtSoftwareUi) {
            // Render Qt6 2D Login/Ribbon QML UI via CPU rasterizer so it never deadlocks
            // creating a competing Vulkan swapchain on Vortek while the 3D Viewport uses DXVK.
            envVars.put("QSG_RHI_BACKEND", "software");
            envVars.put("QT_QUICK_BACKEND", "software");
        }
        if (mimallocFix) {
            envVars.put("MIMALLOC_FORCE_REDIRECT", "1");
        }
        if (useDxvk) {
            envVars.put("DXVK_STATE_CACHE", "1");
            envVars.put("DXVK_LOG_LEVEL", "none");
            envVars.put("DXVK_LOG_PATH", "none");
            if (dxvkAsync) {
                envVars.put("DXVK_ASYNC", "1");
                envVars.put("DXVK_GPLASYNCCACHE", "1");
            }
            if (fpsCap != null && !fpsCap.isEmpty() && !"0".equals(fpsCap)) {
                envVars.put("DXVK_FRAME_RATE", fpsCap);
            }
        }

        StringBuilder dllOverrides = new StringBuilder();
        if (disableDxdiagn) {
            dllOverrides.append("dxdiagn=;d3d12=;d3d12core=;winemenubuilder.exe=;mscoree=;mshtml=;");
        }
        if (mimallocFix) {
            dllOverrides.append("ucrtbase=n,b;concrt140=n,b;msvcp140=n,b;msvcp140_1=n,b;msvcp140_2=n,b;msvcp140_codecvt_ids=n,b;vccorlib140=n,b;vcruntime140=n,b;vcruntime140_1=n,b;");
        }
        if (useDxvk) {
            dllOverrides.append("d3d11=n,b;dxgi=n,b;d3d10core=n,b;d3d9=n,b");
        } else {
            dllOverrides.append("d3d11=b;dxgi=b;d3d10core=b");
        }
        envVars.put("WINEDLLOVERRIDES", dllOverrides.toString());
    }

    private static byte[] readAllBytes(File file) throws IOException {
        long len = file.length();
        if (len <= 0 || len > 64 * 1024 * 1024L) {
            throw new IOException("Invalid file length: " + len);
        }
        byte[] data = new byte[(int) len];
        try (FileInputStream fis = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int read = fis.read(data, offset, data.length - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset != data.length) {
                throw new IOException("Short read on " + file.getName());
            }
        }
        return data;
    }

    private static void copyFile(File src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.getFD().sync();
        }
    }

    public static boolean isStudioCallbackUri(String raw) {
        if (raw == null) return false;
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.startsWith("roblox-studio-auth:") || trimmed.startsWith("roblox-studio:")) {
            return true;
        }
        if (trimmed.startsWith("intent:")) {
            return trimmed.contains("scheme=roblox-studio-auth") || trimmed.contains("scheme=roblox-studio");
        }
        return false;
    }

    public static String normalizeCallbackUri(String raw) throws IOException {
        if (raw == null) throw new IOException("Empty callback URI");
        String s = raw.trim();
        if (s.isEmpty() || s.length() > 4096) throw new IOException("Invalid callback URI length");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '"' || c == '`') {
                throw new IOException("Unsafe character in callback URI");
            }
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("intent:")) {
            int marker = s.indexOf("#Intent;");
            if (marker < 0) throw new IOException("Malformed intent callback URI");
            String body = s.substring("intent:".length(), marker);
            String tail = s.substring(marker + "#Intent;".length());
            String scheme = null;
            for (String part : tail.split(";")) {
                if (part.startsWith("scheme=")) {
                    scheme = part.substring("scheme=".length()).toLowerCase(Locale.ROOT);
                    break;
                }
            }
            if (!"roblox-studio-auth".equals(scheme) && !"roblox-studio".equals(scheme)) {
                throw new IOException("Unsupported intent callback scheme");
            }
            while (body.startsWith("/")) body = body.substring(1);
            s = scheme + ":/" + body;
            lower = s.toLowerCase(Locale.ROOT);
        }
        if (lower.startsWith("roblox-studio-auth:")) {
            return "roblox-studio-auth:" + s.substring("roblox-studio-auth:".length());
        }
        if (lower.startsWith("roblox-studio:")) {
            return "roblox-studio:" + s.substring("roblox-studio:".length());
        }
        throw new IOException("Unsupported Studio callback scheme");
    }

    public static boolean isRobloxAuthUrl(String raw) {
        if (raw == null) return false;
        try {
            URI u = new URI(raw.trim());
            String scheme = u.getScheme();
            String host = u.getHost();
            if (scheme == null || host == null) return false;
            String s = scheme.toLowerCase(Locale.ROOT);
            String h = host.toLowerCase(Locale.ROOT);
            if (!s.equals("https") && !s.equals("http")) return false;
            return h.equals("roblox.com") || h.endsWith(".roblox.com");
        } catch (Exception e) {
            return false;
        }
    }

    public static String escapeBatchArg(String callbackUri) throws IOException {
        String normalized = normalizeCallbackUri(callbackUri);
        return normalized.replace("%", "%%");
    }

    public static String extractDosDir(String dosExePath) throws IOException {
        if (dosExePath == null || !dosExePath.matches("[A-Za-z]:\\\\[^\"\\r\\n]+\\\\RobloxStudioBeta\\.exe")) {
            throw new IOException("Invalid Studio DOS executable path");
        }
        return dosExePath.substring(0, dosExePath.lastIndexOf('\\'));
    }

    public static String buildProtocolRegContent(String dosExePath, String version) throws IOException {
        extractDosDir(dosExePath);
        if (version == null || !version.matches("version-[a-fA-F0-9]{16}")) {
            throw new IOException("Invalid Studio version string");
        }
        String escapedExe = dosExePath.replace("\\", "\\\\");
        String openCommand = "\\\"" + escapedExe + "\\\" \\\"%1\\\"";
        String iconValue = escapedExe + ",0";
        return "Windows Registry Editor Version 5.00\r\n\r\n"
                + "[HKEY_CURRENT_USER\\Software\\Classes\\roblox-studio]\r\n"
                + "@=\"URL: Roblox Protocol\"\r\n"
                + "\"URL Protocol\"=\"\"\r\n\r\n"
                + "[HKEY_CURRENT_USER\\Software\\Classes\\roblox-studio\\DefaultIcon]\r\n"
                + "@=\"" + iconValue + "\"\r\n\r\n"
                + "[HKEY_CURRENT_USER\\Software\\Classes\\roblox-studio\\shell\\open\\command]\r\n"
                + "@=\"" + openCommand + "\"\r\n\r\n"
                + "[HKEY_CURRENT_USER\\Software\\Classes\\roblox-studio-auth]\r\n"
                + "@=\"URL: Roblox Studio Auth Protocol\"\r\n"
                + "\"URL Protocol\"=\"\"\r\n\r\n"
                + "[HKEY_CURRENT_USER\\Software\\Classes\\roblox-studio-auth\\DefaultIcon]\r\n"
                + "@=\"" + iconValue + "\"\r\n\r\n"
                + "[HKEY_CURRENT_USER\\Software\\Classes\\roblox-studio-auth\\shell\\open\\command]\r\n"
                + "@=\"" + openCommand + "\"\r\n\r\n"
                + "[HKEY_CURRENT_USER\\Software\\ROBLOX Corporation\\Environments\\RobloxStudio]\r\n"
                + "\"version\"=\"" + version + "\"\r\n"
                + "\"clientExePath\"=\"" + escapedExe + "\"\r\n\r\n"
                + "[HKEY_CURRENT_USER\\Software\\ROBLOX Corporation\\Environments\\RobloxStudio\\Channel]\r\n"
                + "\"www.roblox.com\"=\"LIVE\"\r\n\r\n"
                + "[HKEY_CURRENT_USER\\Software\\Wine\\WineBrowser]\r\n"
                + "\"Browsers\"=\"C:\\\\windows\\\\winhandler.exe /url\"\r\n";
    }

    public static String buildAuthBatchScript(String dosExePath, String callbackUri) throws IOException {
        String dosDir = extractDosDir(dosExePath);
        String escapedUri = escapeBatchArg(callbackUri);
        return "@echo off\r\n"
                + "C:\\windows\\regedit.exe /s " + STUDIO_PROTOCOL_REG_DOS + "\r\n"
                + "cd /d \"" + dosDir + "\"\r\n"
                + "\"" + dosExePath + "\" \"" + escapedUri + "\"\r\n";
    }

    public static File writeAuthArtifacts(File containerRoot, String dosExePath, String version, String callbackUri) throws IOException {
        File base = StudioDeployment.base(containerRoot);
        DeploymentFiles.mkdir(base);
        File regFile = new File(base, "studio-protocol.reg");
        DeploymentFiles.write(regFile, buildProtocolRegContent(dosExePath, version));
        File batFile = new File(base, "studio-auth.bat");
        DeploymentFiles.write(batFile, buildAuthBatchScript(dosExePath, callbackUri));
        return batFile;
    }

    public static void onWindowMapped(XServerDisplayActivity activity, com.winlator.xserver.Window window) {
        if (activity == null || window == null) return;
        boolean isStudio = activity.getIntent() != null
                && activity.getIntent().getBooleanExtra("studio_bridge_session", false);
        if (!isStudio) return;
        try {
            if (window.isRenderable() && !window.isDesktopWindow() && !window.hasNoDecorations()) {
                com.winlator.xserver.Window parent = window.getParent();
                if (parent != null) {
                    parent.moveChildAbove(window, null);
                }
                long handle = window.getHandle();
                WinHandler winHandler = activity.getWinHandler();
                if (winHandler != null && handle > 0) {
                    winHandler.bringToFront(window.getClassName(), handle);
                }
            }
        } catch (Exception ignored) {}
    }

    public static void markAwaitingExternalAuth() {
        externalAuthStartedAt = SystemClock.elapsedRealtime();
    }

    public static void onSessionResumed(XServerDisplayActivity activity) {
        externalAuthStartedAt = 0L;
    }

    public static boolean shouldKeepAliveOnPause(XServerDisplayActivity activity) {
        if (activity == null) return true;
        boolean isStudio = activity.getIntent() != null
                && activity.getIntent().getBooleanExtra("studio_bridge_session", false);
        if (isStudio) return true;
        long started = externalAuthStartedAt;
        if (started > 0L && (SystemClock.elapsedRealtime() - started) < EXTERNAL_AUTH_GRACE_MS) {
            return true;
        }
        android.content.SharedPreferences prefs = activity.getPreferences();
        return prefs == null || prefs.getBoolean("enable_background_protection", true);
    }

    public static void prepareLaunchIntent(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String data = intent.getDataString();
        if (!isStudioCallbackUri(data)) return;
        intent.putExtra("studio_auth_uri", data);
        intent.putExtra("studio_bridge_session", true);
        if (!intent.hasExtra("container_id")) {
            ContainerManager manager = new ContainerManager(context);
            for (Container c : manager.getContainers()) {
                if ("1".equals(c.getExtra("studioBridge", "0"))) {
                    intent.putExtra("container_id", c.id);
                    try {
                        File exe = StudioDeployment.currentExecutable(c.getRootDir());
                        if (exe != null) intent.putExtra("exec_path", exe.getAbsolutePath());
                    } catch (IOException ignored) {}
                    break;
                }
            }
        }
    }

    public static void handleIncomingIntent(XServerDisplayActivity activity, Intent intent) {
        if (activity == null || intent == null) return;
        String rawUri = intent.getStringExtra("studio_auth_uri");
        if (rawUri == null || rawUri.isEmpty()) {
            rawUri = intent.getDataString();
        }
        if (isStudioCallbackUri(rawUri)) {
            intent.removeExtra("studio_auth_uri");
            intent.setData(null);
            deliverAuthCallback(activity, rawUri);
        }
    }

    public static boolean handleOpenUrl(XServerDisplayActivity activity, String rawUrl) {
        if (activity == null || rawUrl == null) return false;
        final String url = rawUrl.trim();
        if (url.isEmpty()) return false;

        if (isStudioCallbackUri(url)) {
            activity.runOnUiThread(() -> deliverAuthCallback(activity, url));
            return true;
        }

        boolean isStudioSession = activity.getIntent() != null
                && activity.getIntent().getBooleanExtra("studio_bridge_session", false);
        if (!isStudioSession && !isRobloxAuthUrl(url)) {
            return false;
        }

        lastAuthUrl = url;
        activity.runOnUiThread(() -> showInAppLoginDialog(activity, url));
        return true;
    }

    public static void deliverAuthCallback(XServerDisplayActivity activity, String rawUri) {
        if (activity == null || activity.isDestroyed()) return;
        try {
            String normalized = normalizeCallbackUri(rawUri);
            lastCallbackUri = normalized;
            externalAuthStartedAt = 0L;
            dismissActiveLoginDialog();

            Container container = activity.getContainer();
            if (container == null) {
                Toast.makeText(activity, "Studio container not active yet.", Toast.LENGTH_LONG).show();
                return;
            }
            File rootDir = container.getRootDir();
            File exe = StudioDeployment.currentExecutable(rootDir);
            if (exe == null) {
                Toast.makeText(activity, "Studio executable not found in container.", Toast.LENGTH_LONG).show();
                return;
            }
            File marker = new File(StudioDeployment.base(rootDir), "current-version.txt");
            String version = marker.isFile() ? DeploymentFiles.read(marker).trim() : "";
            String dosExePath = WineUtils.unixToDOSPath(exe.getAbsolutePath(), container);

            writeAuthArtifacts(rootDir, dosExePath, version, normalized);

            WinHandler winHandler = activity.getWinHandler();
            if (winHandler != null) {
                winHandler.exec("C:\\windows\\system32\\cmd.exe", "/c " + STUDIO_AUTH_BAT_DOS);
                winHandler.bringToFront("RobloxStudioBeta.exe");
                Toast.makeText(activity, "Sending login callback to Roblox Studio…", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(activity, "Login callback error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public static void openExternalBrowser(XServerDisplayActivity activity, String url) {
        if (activity == null || url == null || url.isEmpty()) return;
        try {
            markAwaitingExternalAuth();
            dismissActiveLoginDialog();
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            Toast.makeText(activity, "Complete login in browser; you will be returned to Studio.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(activity, "Cannot open browser: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public static void showLoginBridgeMenu(XServerDisplayActivity activity) {
        if (activity == null || activity.isDestroyed()) return;
        String[] items = new String[]{
                "Open in-app Roblox login (" + (lastAuthUrl != null ? "current URL" : "authorize.roblox.com") + ")",
                "Open login in external browser (full screen, no split)",
                "Complete login from clipboard (roblox-studio-auth://)",
                "Paste roblox-studio-auth:// callback manually",
                "Re-send last captured login callback to Studio"
        };
        new AlertDialog.Builder(activity)
                .setTitle("Studio Login Bridge")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        showInAppLoginDialog(activity, lastAuthUrl != null ? lastAuthUrl : DEFAULT_AUTH_URL);
                    } else if (which == 1) {
                        openExternalBrowser(activity, lastAuthUrl != null ? lastAuthUrl : DEFAULT_AUTH_URL);
                    } else if (which == 2) {
                        completeFromClipboard(activity);
                    } else if (which == 3) {
                        promptManualCallback(activity);
                    } else if (which == 4) {
                        if (lastCallbackUri != null) {
                            deliverAuthCallback(activity, lastCallbackUri);
                        } else {
                            Toast.makeText(activity, "No login callback captured yet.", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private static void completeFromClipboard(XServerDisplayActivity activity) {
        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) {
                String text = clip.getItemAt(0).getText().toString().trim();
                if (isStudioCallbackUri(text)) {
                    deliverAuthCallback(activity, text);
                    return;
                }
            }
        }
        Toast.makeText(activity, "Clipboard does not contain a roblox-studio-auth: link.", Toast.LENGTH_LONG).show();
    }

    private static void promptManualCallback(XServerDisplayActivity activity) {
        final EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint("roblox-studio-auth:/?code=...");
        new AlertDialog.Builder(activity)
                .setTitle("Paste Studio Auth Callback URI")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Complete Login", (d, w) -> {
                    String value = input.getText() != null ? input.getText().toString().trim() : "";
                    deliverAuthCallback(activity, value);
                })
                .show();
    }

    private static final String JS_OAUTH_INTERCEPTOR =
            "(function(){"
            + "if(window.__studioBridgeHooked)return;window.__studioBridgeHooked=true;"
            + "function checkUrl(u){"
            + "  if(typeof u==='string'&&(u.toLowerCase().indexOf('roblox-studio-auth:')===0||u.toLowerCase().indexOf('roblox-studio:')===0||u.toLowerCase().indexOf('scheme=roblox-studio')!==-1)){"
            + "    if(window.StudioAuthBridge&&window.StudioAuthBridge.onCallbackUri){window.StudioAuthBridge.onCallbackUri(u);return true;}"
            + "  }"
            + "  return false;"
            + "}"
            + "var origOpen=window.open;"
            + "window.open=function(u){if(checkUrl(u))return null;return origOpen?origOpen.apply(this,arguments):null;};"
            + "var origAssign=window.location.assign;"
            + "if(origAssign){window.location.assign=function(u){if(checkUrl(u))return;return origAssign.apply(this,arguments);};}"
            + "var origReplace=window.location.replace;"
            + "if(origReplace){window.location.replace=function(u){if(checkUrl(u))return;return origReplace.apply(this,arguments);};}"
            + "document.addEventListener('click',function(e){"
            + "  var a=e.target&&e.target.closest?e.target.closest('a'):null;"
            + "  if(a&&a.href&&checkUrl(a.href)){e.preventDefault();}"
            + "},true);"
            + "setInterval(function(){"
            + "  var iframes=document.querySelectorAll('iframe');"
            + "  for(var i=0;i<iframes.length;i++){if(iframes[i].src)checkUrl(iframes[i].src);}"
            + "  var links=document.querySelectorAll('a[href^=\"roblox-studio\"]');"
            + "  for(var j=0;j<links.length;j++){if(links[j].href)checkUrl(links[j].href);}"
            + "},250);"
            + "})();";

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public static void showInAppLoginDialog(XServerDisplayActivity activity, String startUrl) {
        if (activity == null || activity.isDestroyed()) return;
        dismissActiveLoginDialog();

        final Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff080b11);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable headerBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xff0d131f, 0xff131c2e}
        );
        headerBg.setStroke(dp(activity, 1), 0xff26354d);
        header.setBackground(headerBg);
        int pad = dp(activity, 10);
        header.setPadding(pad, dp(activity, 6), pad, dp(activity, 6));

        TextView title = new TextView(activity);
        title.setText("ROBLOX STUDIO // OAUTH BRIDGE");
        title.setTextColor(0xfff1f5f9);
        title.setTextSize(13);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        title.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(title, titleLp);

        final ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);

        final WebView webView = new WebView(activity);
        activeWebView = webView;

        Button browserBtn = headerButton(activity, "EXTERNAL BROWSER", 0xff162238, 0xff3b82f6, () -> {
            String current = webView.getUrl();
            openExternalBrowser(activity, (current != null && current.startsWith("http")) ? current : startUrl);
        });
        header.addView(browserBtn);

        Button pasteBtn = headerButton(activity, "PASTE URI", 0xff122032, 0xff38bdf8, () -> completeFromClipboard(activity));
        header.addView(pasteBtn);

        Button closeBtn = headerButton(activity, "CLOSE", 0xff28131a, 0xfff43f5e, StudioLoginBridge::dismissActiveLoginDialog);
        header.addView(closeBtn);

        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 3)));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        String defaultUa = settings.getUserAgentString();
        if (defaultUa != null) {
            settings.setUserAgentString(defaultUa.replace("; wv", "").replace("Version/4.0 ", ""));
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void onCallbackUri(final String url) {
                if (isStudioCallbackUri(url)) {
                    activity.runOnUiThread(() -> {
                        CookieManager.getInstance().flush();
                        deliverAuthCallback(activity, url);
                    });
                }
            }
        }, "StudioAuthBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? android.view.View.GONE : android.view.View.VISIBLE);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            private boolean intercept(String url) {
                if (url == null) return false;
                if (isStudioCallbackUri(url)) {
                    CookieManager.getInstance().flush();
                    deliverAuthCallback(activity, url);
                    return true;
                }
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    lastAuthUrl = url;
                    return false;
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (isStudioCallbackUri(url)) {
                    CookieManager.getInstance().flush();
                    deliverAuthCallback(activity, url);
                    return;
                }
                super.onPageStarted(view, url, favicon);
                view.evaluateJavascript(JS_OAUTH_INTERCEPTOR, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                view.evaluateJavascript(JS_OAUTH_INTERCEPTOR, null);
            }

            @Override
            public void onLoadResource(WebView view, String url) {
                if (isStudioCallbackUri(url)) {
                    CookieManager.getInstance().flush();
                    deliverAuthCallback(activity, url);
                    return;
                }
                super.onLoadResource(view, url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request != null ? request.getUrl() : null;
                return u != null && intercept(u.toString());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return intercept(url);
            }
        });

        root.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
        dialog.setOnDismissListener(d -> {
            if (activeWebView == webView) {
                activeWebView.stopLoading();
                activeWebView.destroy();
                activeWebView = null;
            }
            if (activeLoginDialog == dialog) {
                activeLoginDialog = null;
            }
        });

        activeLoginDialog = dialog;
        dialog.show();
        webView.loadUrl(startUrl);
    }

    public static void dismissActiveLoginDialog() {
        Dialog d = activeLoginDialog;
        activeLoginDialog = null;
        if (d != null && d.isShowing()) {
            try {
                d.dismiss();
            } catch (Exception ignored) {}
        }
    }

    private static Button headerButton(Context context, String label, int bgColor, int strokeColor, Runnable action) {
        Button b = new Button(context);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(11);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        b.setLetterSpacing(0.04f);
        b.setPadding(dp(context, 12), 0, dp(context, 12), 0);

        GradientDrawable outer = new GradientDrawable();
        outer.setShape(GradientDrawable.RECTANGLE);
        outer.setCornerRadius(0f);
        outer.setColor(bgColor);
        outer.setStroke(dp(context, 1), strokeColor);

        GradientDrawable inner = new GradientDrawable();
        inner.setShape(GradientDrawable.RECTANGLE);
        inner.setCornerRadius(0f);
        inner.setColor(Color.TRANSPARENT);
        inner.setStroke(dp(context, 1), 0x44ffffff);

        LayerDrawable layered = new LayerDrawable(new android.graphics.drawable.Drawable[]{outer, inner});
        int inset = dp(context, 3);
        layered.setLayerInset(1, inset, inset, inset, inset);
        b.setBackground(layered);

        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 36));
        lp.leftMargin = dp(context, 6);
        b.setLayoutParams(lp);
        return b;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
