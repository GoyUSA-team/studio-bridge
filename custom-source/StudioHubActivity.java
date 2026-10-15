/* Studio Bridge additions, 2026. LGPL-2.1-or-later. */
package com.winlator.studiobridge;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.winlator.XServerDisplayActivity;
import com.winlator.box64.Box64Preset;
import com.winlator.container.AudioDrivers;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.DXWrappers;
import com.winlator.container.GraphicsDrivers;
import com.winlator.widget.FrameRating;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.RootFSInstaller;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Self-contained Studio Bridge Control Center & Launcher.
 * Handles runtime installation, container creation, Roblox Studio deployment, and all graphics/Wine
 * settings directly without ever sending the user to Winlator's MainActivity.
 */
public class StudioHubActivity extends AppCompatActivity {
    public static final String DISCLAIMER =
            "Unofficial, fan-made experiment toward a Roblox Studio Android port. Not affiliated with, endorsed by, or associated with Roblox Corporation. Use at your own risk.";

    private static final int COLOR_TEXT_PRIMARY = 0xfff8fafc;
    private static final int COLOR_TEXT_SECONDARY = 0xff94a3b8;
    private static final int COLOR_ACCENT_CYAN = 0xff38bdf8;
    private static final int COLOR_ACCENT_BLUE = 0xff3b82f6;
    private static final int COLOR_ACCENT_EMERALD = 0xff10b981;

    private static final int BTN_PRIMARY = 0;
    private static final int BTN_SUCCESS = 1;
    private static final int BTN_SECONDARY = 2;
    private static final int BTN_DANGER = 3;

    private static final String[] GRAPHICS_PRESET_KEYS = new String[]{
            "DXVK_1103_VORTEK",
            "DXVK_241_VORTEK",
            "DXVK_241_TURNIP",
            "DXVK_1103_TURNIP",
            "WINED3D_VIRGL",
            "WINED3D_ZINK",
            "VULKAN_NATIVE"
    };
    private static final String[] GRAPHICS_PRESET_LABELS = new String[]{
            "DXVK 1.10.3 · Vortek Vulkan (Recommended Universal DXVK)",
            "DXVK 2.4.1 · Vortek Vulkan (Vulkan 1.3+ System Driver)",
            "DXVK 2.4.1 · Turnip Vulkan (Snapdragon / Adreno GPU)",
            "DXVK 1.10.3 · Turnip Vulkan (Older Adreno GPU)",
            "WineD3D · VirGL OpenGL (Compatibility Fallback)",
            "WineD3D · Zink (OpenGL over Vulkan)",
            "Native Vulkan · Direct (No DXVK Wrapper)"
    };

    private static final String[] RENDERER_KEYS = new String[]{"Auto", "D3D11", "D3D11FL10", "Vulkan", "OpenGL"};
    private static final String[] RENDERER_LABELS = new String[]{
            "Auto · D3D11 for DXVK/WineD3D, Vulkan for Native (Recommended)",
            "Force Direct3D 11 · FFlagDebugGraphicsPreferD3D11",
            "Force Direct3D 11 FL10 · FFlagDebugGraphicsPreferD3D11FL10 (Shader Model 4.0)",
            "Force Vulkan · FFlagDebugGraphicsPreferVulkan",
            "Force OpenGL · FFlagDebugGraphicsPreferOpenGL"
    };

    private static final String[] RESOLUTION_KEYS = new String[]{
            "1280x720", "1600x900", "1920x1080", "1440x900", "1024x768", "960x540"
    };
    private static final String[] RESOLUTION_LABELS = new String[]{
            "1280x720 · 16:9 HD (Recommended Workspace)",
            "1600x900 · 16:9 HD+ (Sharp UI)",
            "1920x1080 · 16:9 Full HD (Desktop Grade)",
            "1440x900 · 16:10 Widescreen",
            "1024x768 · 4:3 Compact",
            "960x540 · 16:9 qHD (Maximum Performance)"
    };

    private static final String[] BOX64_KEYS = new String[]{
            Box64Preset.STABILITY,
            Box64Preset.CONSERVATIVE,
            Box64Preset.INTERMEDIATE,
            Box64Preset.PERFORMANCE
    };
    private static final String[] BOX64_LABELS = new String[]{
            "Stability · Safe JIT (Recommended for Studio)",
            "Conservative · Strict Compatibility JIT",
            "Intermediate · Balanced JIT",
            "Performance · Fast JIT"
    };

    private static final String[] VRAM_KEYS = new String[]{"4096", "2048", "1024", "0"};
    private static final String[] VRAM_LABELS = new String[]{
            "4096 MB (4 GB · Recommended)",
            "2048 MB (2 GB)",
            "1024 MB (1 GB · Low Memory Devices)",
            "Unlimited (Host Reported)"
    };

    private static final String[] FPS_KEYS = new String[]{"0", "60", "45", "30"};
    private static final String[] FPS_LABELS = new String[]{
            "Unlimited (No Cap)",
            "60 FPS Cap",
            "45 FPS Cap",
            "30 FPS Battery Saver"
    };

    private static final String[] AUDIO_KEYS = new String[]{AudioDrivers.ALSA, AudioDrivers.PULSEAUDIO};
    private static final String[] AUDIO_LABELS = new String[]{
            "ALSA · Low-Latency Native Audio (Recommended)",
            "PulseAudio · Compatibility Server"
    };

    private static final int[] DPI_VALUES = new int[]{98, 120, 144};
    private static final String[] DPI_LABELS = new String[]{
            "98 DPI · Standard + Splash Hang Fix (Recommended)",
            "120 DPI · 125% Medium UI Scale",
            "144 DPI · 150% Large UI Scale"
    };

    private static final float[] CURSOR_SPEED_VALUES = new float[]{0.5f, 0.7f, 1.0f, 1.3f, 1.6f};
    private static final String[] CURSOR_SPEED_LABELS = new String[]{
            "0.5x · Precision Slow",
            "0.7x · Studio Default",
            "1.0x · Standard Speed",
            "1.3x · Fast",
            "1.6x · Ultra Fast"
    };

    private static final float[] CURSOR_SCALE_VALUES = new float[]{1.0f, 1.4f, 1.8f};
    private static final String[] CURSOR_SCALE_LABELS = new String[]{
            "1.0x · Standard Pointer",
            "1.4x · Medium Pointer (Recommended)",
            "1.8x · Large High-Visibility Pointer"
    };

    private final List<Container> containers = new ArrayList<>();
    private SharedPreferences studioPrefs;
    private SharedPreferences winlatorPrefs;

    private TextView runtimeBadgeText;
    private TextView containerBadgeText;
    private TextView studioVersionBadgeText;
    private TextView statusBanner;

    private Spinner containerPicker;
    private Spinner graphicsPresetSpinner;
    private Spinner rendererSpinner;
    private Spinner resolutionSpinner;
    private Spinner box64Spinner;
    private Spinner vramSpinner;
    private Spinner fpsSpinner;
    private Spinner audioSpinner;
    private Spinner dpiSpinner;
    private Spinner cursorSpeedSpinner;
    private Spinner cursorScaleSpinner;

    private CheckBox cbKernelbasePatch;
    private CheckBox cbMimallocFix;
    private CheckBox cbDisableDxdiagn;
    private CheckBox cbQtSoftwareUi;
    private CheckBox cbWebview2Fix;
    private CheckBox cbDxvkAsync;
    private CheckBox cbDarkTheme;
    private CheckBox cbTouchpointCursor;
    private CheckBox cbShowFpsHud;
    private CheckBox cbBackgroundKeepAlive;
    private CheckBox cbWineDebug;

    private boolean busy;
    private boolean suppressSpinnerEvents;
    private StudioDeployment deployment;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ProgressDialog progress;
    private int previousOrientation;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff06090f);
        getWindow().setNavigationBarColor(0xff06090f);

        studioPrefs = getSharedPreferences(StudioLoginBridge.PREFS_NAME, Context.MODE_PRIVATE);
        winlatorPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        ensureDefaultPreferences();

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
        }

        ScrollView scroll = new ScrollView(this);
        GradientDrawable pageBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xff06090f, 0xff0d1526, 0xff080c14}
        );
        scroll.setBackground(pageBg);
        scroll.setFillViewport(true);

        LinearLayout rootColumn = new LinearLayout(this);
        rootColumn.setOrientation(LinearLayout.VERTICAL);
        rootColumn.setPadding(dp(16), dp(20), dp(16), dp(36));
        scroll.addView(rootColumn);
        setContentView(scroll);

        buildHeaderSection(rootColumn);
        buildQuickLaunchSection(rootColumn);
        buildRuntimeAndContainerSection(rootColumn);
        buildSettingsSection(rootColumn);
        buildFooterSection(rootColumn);

        loadSavedSettingsIntoViews();
        refresh();

        // Proactively patch RootFS kernelbase.dll and migrate existing Studio containers in background
        executor.execute(() -> {
            try {
                RootFS fs = RootFS.find(this);
                if (fs.isValid()) {
                    StudioLoginBridge.patchRootFSKernelbase(fs.getRootDir());
                }
                if (!studioPrefs.getBoolean("containers_migrated_v3", false)) {
                    for (Container c : new ContainerManager(this).getContainers()) {
                        if ("1".equals(c.getExtra("studioBridge", "0"))) {
                            StudioLoginBridge.patchRootFSKernelbase(c.getRootDir());
                            StudioLoginBridge.configureWineRegistry(this, c);
                        }
                    }
                    studioPrefs.edit().putBoolean("containers_migrated_v3", true).apply();
                }
            } catch (Exception ignored) {}
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (containerPicker != null) {
            refresh();
        }
    }

    private void ensureDefaultPreferences() {
        if (!studioPrefs.getBoolean("prefs_initialized_v2", false)) {
            studioPrefs.edit()
                    .putBoolean("prefs_initialized_v2", true)
                    .putString("pref_graphics_preset", "DXVK_1103_VORTEK")
                    .putString("pref_studio_renderer", "Auto")
                    .putString("pref_resolution", "1280x720")
                    .putString("pref_box64_preset", Box64Preset.STABILITY)
                    .putString("pref_vram_mb", "4096")
                    .putString("pref_fps_cap", "0")
                    .putString("pref_audio_driver", AudioDrivers.ALSA)
                    .putInt("pref_wine_dpi", 98)
                    .putFloat("pref_cursor_speed", 0.7f)
                    .putFloat("pref_cursor_scale", 1.4f)
                    .putBoolean("pref_kernelbase_patch", true)
                    .putBoolean("pref_mimalloc_fix", true)
                    .putBoolean("pref_disable_dxdiagn", true)
                    .putBoolean("pref_qt_software_ui", true)
                    .putBoolean("pref_webview2_fix", true)
                    .putBoolean("pref_dxvk_async", true)
                    .putBoolean("pref_dark_theme", true)
                    .putBoolean("pref_touchpoint_cursor", false)
                    .putBoolean("pref_show_fps_hud", false)
                    .putBoolean("pref_bg_keepalive", true)
                    .putBoolean("pref_wine_debug", false)
                    .apply();
        }
        winlatorPrefs.edit()
                .putBoolean("enable_background_protection", studioPrefs.getBoolean("pref_bg_keepalive", true))
                .putBoolean("enable_background_wakelock", studioPrefs.getBoolean("pref_bg_keepalive", true))
                .putFloat("cursor_speed", studioPrefs.getFloat("pref_cursor_speed", 0.7f))
                .putFloat("cursor_scale", studioPrefs.getFloat("pref_cursor_scale", 1.4f))
                .putBoolean("move_cursor_to_touchpoint", studioPrefs.getBoolean("pref_touchpoint_cursor", false))
                .putBoolean("enable_wine_debug", studioPrefs.getBoolean("pref_wine_debug", false))
                .apply();
    }

    private void buildHeaderSection(LinearLayout parent) {
        LinearLayout card = createCard(parent, 0xff0d1629, 0xff090f1d, 0xff2563eb);

        TextView tag = createLabel("STUDIO BRIDGE // PRO CONTROL CENTER", 11, COLOR_ACCENT_CYAN, true);
        tag.setLetterSpacing(0.12f);
        card.addView(tag, marginParams(0, 0, 0, 4));

        TextView title = createLabel("ROBLOX STUDIO ENVIRONMENT", 24, COLOR_TEXT_PRIMARY, true);
        title.setLetterSpacing(0.03f);
        card.addView(title, marginParams(0, 0, 0, 6));

        TextView subtitle = createLabel(
                "Standalone Wine 10.10 + DXVK/Vortek Studio Engine with Kernelbase VirtualProtectFromApp Patch, Mimalloc Native UCRT Bridge & In-App OAuth Login.",
                13, COLOR_TEXT_SECONDARY, false
        );
        card.addView(subtitle, marginParams(0, 0, 0, 14));

        // Status Telemetry Grid
        LinearLayout metricsRow = new LinearLayout(this);
        metricsRow.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable metricsBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xff070b14, 0xff0b111e}
        );
        metricsBg.setCornerRadius(0f);
        metricsBg.setStroke(dp(1), 0xff1e293b);
        metricsRow.setBackground(metricsBg);
        metricsRow.setPadding(dp(12), dp(10), dp(12), dp(10));

        runtimeBadgeText = createLabel("RUNTIME ENGINE: CHECKING...", 12, COLOR_ACCENT_CYAN, true);
        containerBadgeText = createLabel("ACTIVE CONTAINER: NONE", 12, COLOR_TEXT_PRIMARY, true);
        studioVersionBadgeText = createLabel("STUDIO BUILD: NOT INSTALLED", 12, COLOR_ACCENT_EMERALD, true);

        metricsRow.addView(runtimeBadgeText, marginParams(0, 0, 0, 4));
        metricsRow.addView(containerBadgeText, marginParams(0, 0, 0, 4));
        metricsRow.addView(studioVersionBadgeText);
        card.addView(metricsRow, marginParams(0, 0, 0, 10));

        statusBanner = createLabel("Initializing Studio Bridge workspace…", 13, COLOR_ACCENT_CYAN, false);
        card.addView(statusBanner);
    }

    private void buildQuickLaunchSection(LinearLayout parent) {
        LinearLayout card = createCard(parent, 0xff0c1424, 0xff080d18, 0xff10b981);

        addSectionHeader(card, "01 // INSTANT LAUNCH & STUDIO DEPLOYMENT",
                "Launch Roblox Studio directly with your saved DXVK & Wine compatibility patches, or download the latest Windows Studio build.");

        addSharpButton(card, "LAUNCH ROBLOX STUDIO NOW", BTN_SUCCESS, this::launchStudio);
        addSharpButton(card, "DOWNLOAD / UPDATE ROBLOX STUDIO", BTN_PRIMARY, this::confirmInstall);
    }

    private void buildRuntimeAndContainerSection(LinearLayout parent) {
        LinearLayout card = createCard(parent, 0xff0b1220, 0xff080d18, 0xff3b82f6);

        addSectionHeader(card, "02 // RUNTIME ENGINE & CONTAINER MANAGEMENT",
                "Initialize or repair the embedded Wine rootfs and manage your dedicated Studio Bridge container directly here (no Winlator homepage required).");

        addSharpButton(card, "INSTALL / REPAIR RUNTIME ENGINE (ROOTFS + PATCHES)", BTN_PRIMARY, this::installOrRepairRuntime);

        addFieldLabel(card, "ACTIVE STUDIO BRIDGE CONTAINER");
        containerPicker = createSharpSpinner(new String[]{"No Studio container yet"});
        containerPicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View view, int position, long id) {
                if (!suppressSpinnerEvents) {
                    updateTelemetryBadges();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parentView) {}
        });
        card.addView(containerPicker, marginParams(0, 0, 0, 12));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button createBtn = createSharpButtonView("CREATE NEW CONTAINER", BTN_SECONDARY, this::createContainer);
        Button repairBtn = createSharpButtonView("APPLY FIXES & SYNC DLLS", BTN_SECONDARY, this::applyAllSettingsAndRepairContainer);
        LinearLayout.LayoutParams lpLeft = new LinearLayout.LayoutParams(0, dp(50), 1f);
        lpLeft.rightMargin = dp(6);
        LinearLayout.LayoutParams lpRight = new LinearLayout.LayoutParams(0, dp(50), 1f);
        lpRight.leftMargin = dp(6);
        btnRow.addView(createBtn, lpLeft);
        btnRow.addView(repairBtn, lpRight);
        card.addView(btnRow, marginParams(0, 0, 0, 4));
    }

    private void buildSettingsSection(LinearLayout parent) {
        LinearLayout card = createCard(parent, 0xff0b1220, 0xff080d18, 0xff38bdf8);

        addSectionHeader(card, "03 // PROFESSIONAL GRAPHICS, DXVK & ENGINE SETTINGS",
                "All settings below are automatically saved to Studio Bridge preferences and written into your container & ClientAppSettings.json.");

        addFieldLabel(card, "GRAPHICS DRIVER & DXVK WRAPPER PRESET");
        graphicsPresetSpinner = createSharpSpinner(GRAPHICS_PRESET_LABELS);
        card.addView(graphicsPresetSpinner, marginParams(0, 0, 0, 12));

        addFieldLabel(card, "ROBLOX STUDIO RENDERER OVERRIDE (ClientAppSettings.json)");
        rendererSpinner = createSharpSpinner(RENDERER_LABELS);
        card.addView(rendererSpinner, marginParams(0, 0, 0, 12));

        addFieldLabel(card, "DISPLAY RESOLUTION");
        resolutionSpinner = createSharpSpinner(RESOLUTION_LABELS);
        card.addView(resolutionSpinner, marginParams(0, 0, 0, 12));

        addFieldLabel(card, "BOX64 CPU TRANSLATION PROFILE");
        box64Spinner = createSharpSpinner(BOX64_LABELS);
        card.addView(box64Spinner, marginParams(0, 0, 0, 12));

        addFieldLabel(card, "DXVK VIDEO MEMORY (VRAM) LIMIT");
        vramSpinner = createSharpSpinner(VRAM_LABELS);
        card.addView(vramSpinner, marginParams(0, 0, 0, 12));

        addFieldLabel(card, "DXVK FRAME RATE CAP");
        fpsSpinner = createSharpSpinner(FPS_LABELS);
        card.addView(fpsSpinner, marginParams(0, 0, 0, 12));

        addFieldLabel(card, "AUDIO BACKEND");
        audioSpinner = createSharpSpinner(AUDIO_LABELS);
        card.addView(audioSpinner, marginParams(0, 0, 0, 12));

        addFieldLabel(card, "WINE DESKTOP DPI (SPLASH HANG WORKAROUND)");
        dpiSpinner = createSharpSpinner(DPI_LABELS);
        card.addView(dpiSpinner, marginParams(0, 0, 0, 12));

        addFieldLabel(card, "TOUCHPAD CURSOR SENSITIVITY");
        cursorSpeedSpinner = createSharpSpinner(CURSOR_SPEED_LABELS);
        card.addView(cursorSpeedSpinner, marginParams(0, 0, 0, 12));

        addFieldLabel(card, "CURSOR POINTER SCALE");
        cursorScaleSpinner = createSharpSpinner(CURSOR_SCALE_LABELS);
        card.addView(cursorScaleSpinner, marginParams(0, 0, 0, 16));

        addFieldLabel(card, "SPLASH-SCREEN & RUNTIME STABILITY PATCHES");
        cbKernelbasePatch = addStyledCheckbox(card,
                "Patch Wine kernelbase.dll with VirtualProtectFromApp (Fixes Splash Screen Hang)", true);
        cbMimallocFix = addStyledCheckbox(card,
                "Sync Native UCRT/MSVC DLLs + MIMALLOC_FORCE_REDIRECT=1 (Fixes Allocator Crash)", true);
        cbDisableDxdiagn = addStyledCheckbox(card,
                "Disable dxdiagn.dll, D3D12/VKD3D & Wine MenuBuilder (Prevents Startup DX Probe Freeze)", true);
        cbQtSoftwareUi = addStyledCheckbox(card,
                "Use Qt6 Software UI Backend (QSG_RHI_BACKEND=software · Fixes DXVK Splash/Login Freeze)", true);
        cbWebview2Fix = addStyledCheckbox(card,
                "Set msedgewebview2.exe to Windows 8 Mode (Prevents WebView2 Crash)", true);
        cbDxvkAsync = addStyledCheckbox(card,
                "Enable DXVK Async Pipeline Cache (Reduces Viewport Shader Stutter)", true);
        cbDarkTheme = addStyledCheckbox(card,
                "Force Windows Dark Theme Registry Keys for Studio UI", true);
        cbTouchpointCursor = addStyledCheckbox(card,
                "Move Cursor Directly to Touchpoint (Disable for Relative Trackpad Mode)", false);
        cbShowFpsHud = addStyledCheckbox(card,
                "Show On-Screen FPS & GPU Telemetry HUD Overlay", false);
        cbBackgroundKeepAlive = addStyledCheckbox(card,
                "Keep Studio Session Alive in Background + CPU WakeLock", true);
        cbWineDebug = addStyledCheckbox(card,
                "Enable Verbose Wine & Box64 Debug Logs (Session Menu -> Logs)", false);

        View spacer = new View(this);
        card.addView(spacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));

        addSharpButton(card, "SAVE & APPLY ALL SETTINGS TO CONTAINER", BTN_PRIMARY, this::saveAndApplySettingsInteractive);
        addSharpButton(card, "RESET SETTINGS TO RECOMMENDED DEFAULTS", BTN_DANGER, this::resetToRecommendedDefaults);
    }

    private void buildFooterSection(LinearLayout parent) {
        LinearLayout card = createCard(parent, 0xff090e1a, 0xff070b14, 0xff334155);

        addSectionHeader(card, "04 // DIAGNOSTICS & DOCUMENTATION",
                "Review controls, inspect your active configuration, or read the project notice.");

        addSharpButton(card, "TOUCH CONTROLS & TROUBLESHOOTING GUIDE", BTN_SECONDARY, () ->
                message("Studio Bridge Controls & Troubleshooting",
                        "• Bottom Command Bar: Provides instant access to LOGIN, KEYBOARD, PLAY F5, UNDO, REDO, SAVE, FOCUS F, ESC, held W/A/S/D movement, LOOK · HOLD (right mouse), WINDOWS, LOGS, and HUB / EXIT.\n\n"
                                + "• Splash Screen Hang Fix: Studio Bridge automatically patches Wine 10.10's kernelbase.dll to export VirtualProtectFromApp, copies Studio's native UCRT/MSVC runtime DLLs for mimalloc, sets QSG_RHI_BACKEND=software for Qt6 login panels, and configures ClientAppSettings.json so Studio does not hang on the splash screen.\n\n"
                                + "• Graphics Presets: If DXVK 2.4.1 fails on your GPU's Vulkan driver, select 'DXVK 1.10.3 · Vortek Vulkan' or 'WineD3D · VirGL OpenGL' and tap SAVE & APPLY ALL SETTINGS.\n\n"
                                + "• OAuth Sign-In: Tap LOGIN in the bottom bar during a session to sign in via the In-App OAuth Bridge or full-screen browser without Android split-screen.")
        );

        TextView credits = createLabel(
                DISCLAIMER + "\n\nBased on Winlator by brunodev85 and contributors, with compatibility research from VinegarHQ / Kombucha. Upstream LGPL-2.1 licenses preserved.",
                11, COLOR_TEXT_SECONDARY, false
        );
        card.addView(credits, marginParams(0, 6, 0, 0));
    }

    private void loadSavedSettingsIntoViews() {
        suppressSpinnerEvents = true;
        try {
            setSpinnerByKey(graphicsPresetSpinner, GRAPHICS_PRESET_KEYS,
                    studioPrefs.getString("pref_graphics_preset", "DXVK_1103_VORTEK"));
            setSpinnerByKey(rendererSpinner, RENDERER_KEYS,
                    studioPrefs.getString("pref_studio_renderer", "Auto"));
            setSpinnerByKey(resolutionSpinner, RESOLUTION_KEYS,
                    studioPrefs.getString("pref_resolution", "1280x720"));
            setSpinnerByKey(box64Spinner, BOX64_KEYS,
                    studioPrefs.getString("pref_box64_preset", Box64Preset.STABILITY));
            setSpinnerByKey(vramSpinner, VRAM_KEYS,
                    studioPrefs.getString("pref_vram_mb", "4096"));
            setSpinnerByKey(fpsSpinner, FPS_KEYS,
                    studioPrefs.getString("pref_fps_cap", "0"));
            setSpinnerByKey(audioSpinner, AUDIO_KEYS,
                    studioPrefs.getString("pref_audio_driver", AudioDrivers.ALSA));

            int dpi = studioPrefs.getInt("pref_wine_dpi", 98);
            int dpiIdx = 0;
            for (int i = 0; i < DPI_VALUES.length; i++) {
                if (DPI_VALUES[i] == dpi) {
                    dpiIdx = i;
                    break;
                }
            }
            dpiSpinner.setSelection(dpiIdx);

            float speed = studioPrefs.getFloat("pref_cursor_speed", 0.7f);
            int speedIdx = 1;
            for (int i = 0; i < CURSOR_SPEED_VALUES.length; i++) {
                if (Math.abs(CURSOR_SPEED_VALUES[i] - speed) < 0.05f) {
                    speedIdx = i;
                    break;
                }
            }
            cursorSpeedSpinner.setSelection(speedIdx);

            float scale = studioPrefs.getFloat("pref_cursor_scale", 1.4f);
            int scaleIdx = 1;
            for (int i = 0; i < CURSOR_SCALE_VALUES.length; i++) {
                if (Math.abs(CURSOR_SCALE_VALUES[i] - scale) < 0.05f) {
                    scaleIdx = i;
                    break;
                }
            }
            cursorScaleSpinner.setSelection(scaleIdx);

            cbKernelbasePatch.setChecked(studioPrefs.getBoolean("pref_kernelbase_patch", true));
            cbMimallocFix.setChecked(studioPrefs.getBoolean("pref_mimalloc_fix", true));
            cbDisableDxdiagn.setChecked(studioPrefs.getBoolean("pref_disable_dxdiagn", true));
            cbQtSoftwareUi.setChecked(studioPrefs.getBoolean("pref_qt_software_ui", true));
            cbWebview2Fix.setChecked(studioPrefs.getBoolean("pref_webview2_fix", true));
            cbDxvkAsync.setChecked(studioPrefs.getBoolean("pref_dxvk_async", true));
            cbDarkTheme.setChecked(studioPrefs.getBoolean("pref_dark_theme", true));
            cbTouchpointCursor.setChecked(studioPrefs.getBoolean("pref_touchpoint_cursor", false));
            cbShowFpsHud.setChecked(studioPrefs.getBoolean("pref_show_fps_hud", false));
            cbBackgroundKeepAlive.setChecked(studioPrefs.getBoolean("pref_bg_keepalive", true));
            cbWineDebug.setChecked(studioPrefs.getBoolean("pref_wine_debug", false));
        } finally {
            suppressSpinnerEvents = false;
        }
    }

    private void persistSettingsFromViews() {
        String graphicsPreset = pickKey(graphicsPresetSpinner, GRAPHICS_PRESET_KEYS, "DXVK_1103_VORTEK");
        String studioRenderer = pickKey(rendererSpinner, RENDERER_KEYS, "Auto");
        String resolution = pickKey(resolutionSpinner, RESOLUTION_KEYS, "1280x720");
        String box64Preset = pickKey(box64Spinner, BOX64_KEYS, Box64Preset.STABILITY);
        String vramMb = pickKey(vramSpinner, VRAM_KEYS, "4096");
        String fpsCap = pickKey(fpsSpinner, FPS_KEYS, "0");
        String audioDriver = pickKey(audioSpinner, AUDIO_KEYS, AudioDrivers.ALSA);

        int dpiIdx = Math.max(0, Math.min(DPI_VALUES.length - 1, dpiSpinner.getSelectedItemPosition()));
        int wineDpi = DPI_VALUES[dpiIdx];

        int speedIdx = Math.max(0, Math.min(CURSOR_SPEED_VALUES.length - 1, cursorSpeedSpinner.getSelectedItemPosition()));
        float cursorSpeed = CURSOR_SPEED_VALUES[speedIdx];

        int scaleIdx = Math.max(0, Math.min(CURSOR_SCALE_VALUES.length - 1, cursorScaleSpinner.getSelectedItemPosition()));
        float cursorScale = CURSOR_SCALE_VALUES[scaleIdx];

        studioPrefs.edit()
                .putBoolean("prefs_initialized_v2", true)
                .putString("pref_graphics_preset", graphicsPreset)
                .putString("pref_studio_renderer", studioRenderer)
                .putString("pref_resolution", resolution)
                .putString("pref_box64_preset", box64Preset)
                .putString("pref_vram_mb", vramMb)
                .putString("pref_fps_cap", fpsCap)
                .putString("pref_audio_driver", audioDriver)
                .putInt("pref_wine_dpi", wineDpi)
                .putFloat("pref_cursor_speed", cursorSpeed)
                .putFloat("pref_cursor_scale", cursorScale)
                .putBoolean("pref_kernelbase_patch", cbKernelbasePatch.isChecked())
                .putBoolean("pref_mimalloc_fix", cbMimallocFix.isChecked())
                .putBoolean("pref_disable_dxdiagn", cbDisableDxdiagn.isChecked())
                .putBoolean("pref_qt_software_ui", cbQtSoftwareUi.isChecked())
                .putBoolean("pref_webview2_fix", cbWebview2Fix.isChecked())
                .putBoolean("pref_dxvk_async", cbDxvkAsync.isChecked())
                .putBoolean("pref_dark_theme", cbDarkTheme.isChecked())
                .putBoolean("pref_touchpoint_cursor", cbTouchpointCursor.isChecked())
                .putBoolean("pref_show_fps_hud", cbShowFpsHud.isChecked())
                .putBoolean("pref_bg_keepalive", cbBackgroundKeepAlive.isChecked())
                .putBoolean("pref_wine_debug", cbWineDebug.isChecked())
                .apply();

        winlatorPrefs.edit()
                .putBoolean("enable_background_protection", cbBackgroundKeepAlive.isChecked())
                .putBoolean("enable_background_wakelock", cbBackgroundKeepAlive.isChecked())
                .putFloat("cursor_speed", cursorSpeed)
                .putFloat("cursor_scale", cursorScale)
                .putBoolean("move_cursor_to_touchpoint", cbTouchpointCursor.isChecked())
                .putBoolean("enable_wine_debug", cbWineDebug.isChecked())
                .apply();
    }

    private JSONObject buildContainerPresetJson() throws Exception {
        persistSettingsFromViews();

        String presetKey = studioPrefs.getString("pref_graphics_preset", "DXVK_1103_VORTEK");
        String resolution = studioPrefs.getString("pref_resolution", "1280x720");
        String box64 = studioPrefs.getString("pref_box64_preset", Box64Preset.STABILITY);
        String vram = studioPrefs.getString("pref_vram_mb", "4096");
        String fps = studioPrefs.getString("pref_fps_cap", "0");
        String audio = studioPrefs.getString("pref_audio_driver", AudioDrivers.ALSA);
        boolean showHud = studioPrefs.getBoolean("pref_show_fps_hud", false);

        String graphicsDriver;
        String dxWrapper;
        String dxvkVersion;

        switch (presetKey) {
            case "DXVK_241_VORTEK":
                graphicsDriver = GraphicsDrivers.VORTEK + "," + GraphicsDrivers.VIRGL;
                dxWrapper = DXWrappers.DXVK;
                dxvkVersion = "2.4.1";
                break;
            case "DXVK_241_TURNIP":
                graphicsDriver = GraphicsDrivers.TURNIP + "," + GraphicsDrivers.ZINK;
                dxWrapper = DXWrappers.DXVK;
                dxvkVersion = "2.4.1";
                break;
            case "DXVK_1103_TURNIP":
                graphicsDriver = GraphicsDrivers.TURNIP + "," + GraphicsDrivers.ZINK;
                dxWrapper = DXWrappers.DXVK;
                dxvkVersion = "1.10.3";
                break;
            case "WINED3D_VIRGL":
                graphicsDriver = GraphicsDrivers.VORTEK + "," + GraphicsDrivers.VIRGL;
                dxWrapper = DXWrappers.WINED3D;
                dxvkVersion = "";
                break;
            case "WINED3D_ZINK":
                graphicsDriver = GraphicsDrivers.VORTEK + "," + GraphicsDrivers.ZINK;
                dxWrapper = DXWrappers.WINED3D;
                dxvkVersion = "";
                break;
            case "VULKAN_NATIVE":
                graphicsDriver = GraphicsDrivers.VORTEK + "," + GraphicsDrivers.VIRGL;
                dxWrapper = DXWrappers.WINED3D;
                dxvkVersion = "";
                break;
            case "DXVK_1103_VORTEK":
            default:
                graphicsDriver = GraphicsDrivers.VORTEK + "," + GraphicsDrivers.VIRGL;
                dxWrapper = DXWrappers.DXVK;
                dxvkVersion = "1.10.3";
                break;
        }

        String dxWrapperConfig = "";
        if (DXWrappers.DXVK.equals(dxWrapper)) {
            dxWrapperConfig = "version=" + dxvkVersion + ",framerate=" + fps + ",maxDeviceMemory=" + vram + "|version=2.14.1";
        }

        String envVars = "ZINK_DESCRIPTORS=lazy MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB "
                + "WINEESYNC=1 WINEFSYNC=1 WINE_LARGE_ADDRESS_AWARE=1 WINEBOOT_HIDE_DIALOG=1 MIMALLOC_FORCE_REDIRECT=1";

        JSONObject data = new JSONObject();
        data.put("screenSize", resolution);
        data.put("graphicsDriver", graphicsDriver);
        data.put("graphicsDriverConfig", "|");
        data.put("dxwrapper", dxWrapper);
        data.put("dxwrapperConfig", dxWrapperConfig);
        data.put("audioDriver", audio);
        data.put("envVars", envVars);
        data.put("box64Preset", box64);
        data.put("startupSelection", Container.STARTUP_SELECTION_NORMAL);
        data.put("wincomponents", Container.DEFAULT_WINCOMPONENTS);
        data.put("hudMode", showHud ? FrameRating.Mode.FULL.ordinal() : FrameRating.Mode.DISABLED.ordinal());
        return data;
    }

    private void applySettingsToContainerObject(Container c) throws Exception {
        JSONObject p = buildContainerPresetJson();
        c.setScreenSize(p.getString("screenSize"));
        c.setGraphicsDriver(p.getString("graphicsDriver"));
        c.setGraphicsDriverConfig(p.getString("graphicsDriverConfig"));
        c.setDXWrapper(p.getString("dxwrapper"));
        c.setDXWrapperConfig(p.getString("dxwrapperConfig"));
        c.setAudioDriver(p.getString("audioDriver"));
        c.setEnvVars(p.getString("envVars"));
        c.setBox64Preset(p.getString("box64Preset"));
        c.setStartupSelection((byte) p.getInt("startupSelection"));
        c.setWinComponents(p.getString("wincomponents"));
        c.setHUDMode((byte) p.getInt("hudMode"));
        // Force Winlator to re-extract the selected DXVK version on next launch if changed
        c.putExtra("dxwrapper", "");
        c.saveData();

        RootFS fs = RootFS.find(this);
        if (cbKernelbasePatch.isChecked()) {
            StudioLoginBridge.patchRootFSKernelbase(fs.getRootDir());
            StudioLoginBridge.patchRootFSKernelbase(c.getRootDir());
        }
        StudioLoginBridge.configureWineRegistry(this, c);
    }

    private void saveAndApplySettingsInteractive() {
        persistSettingsFromViews();
        if (containers.isEmpty()) {
            statusBanner.setText("Settings saved! Create a Studio container or tap Launch to initialize one.");
            Toast.makeText(this, "All Studio Bridge settings saved.", Toast.LENGTH_SHORT).show();
            updateTelemetryBadges();
            return;
        }
        Container c = selected();
        if (c == null) return;
        try {
            applySettingsToContainerObject(c);
            updateTelemetryBadges();
            statusBanner.setText("All settings & compatibility patches saved and applied to " + c.getName() + ".");
            Toast.makeText(this, "Settings saved & applied to " + c.getName(), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            message("Failed to apply settings", e.toString());
        }
    }

    private void applyAllSettingsAndRepairContainer() {
        Container c = selected();
        if (c == null) return;
        try {
            applySettingsToContainerObject(c);
            updateTelemetryBadges();
            message("Container Patched & Synced",
                    "Applied to " + c.getName() + ":\n"
                            + "• Wine 10.10 kernelbase.dll VirtualProtectFromApp export patched\n"
                            + "• Studio native UCRT & Mimalloc redirect DLLs synced to system32\n"
                            + "• ClientAppSettings.json updated for selected renderer\n"
                            + "• DXVK / Graphics & Box64 configuration saved");
        } catch (Exception e) {
            message("Repair failed", e.toString());
        }
    }

    private void resetToRecommendedDefaults() {
        studioPrefs.edit().clear().apply();
        ensureDefaultPreferences();
        loadSavedSettingsIntoViews();
        if (!containers.isEmpty()) {
            try {
                Container c = containers.get(Math.max(0, containerPicker.getSelectedItemPosition()));
                applySettingsToContainerObject(c);
            } catch (Exception ignored) {}
        }
        updateTelemetryBadges();
        statusBanner.setText("Restored recommended Studio Bridge defaults.");
        Toast.makeText(this, "Restored recommended defaults.", Toast.LENGTH_SHORT).show();
    }

    private void installOrRepairRuntime() {
        if (busy) return;
        RootFSInstaller.install(this, () -> {
            executor.execute(() -> {
                RootFS fs = RootFS.find(this);
                if (fs.isValid()) {
                    StudioLoginBridge.patchRootFSKernelbase(fs.getRootDir());
                }
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    refresh();
                    if (fs.isValid() && containers.isEmpty()) {
                        createContainer();
                    }
                });
            });
        });
    }

    private void refresh() {
        int old = containerPicker != null ? containerPicker.getSelectedItemPosition() : 0;
        containers.clear();
        for (Container c : new ContainerManager(this).getContainers()) {
            if ("1".equals(c.getExtra("studioBridge", "0"))) {
                containers.add(c);
            }
        }
        List<String> names = new ArrayList<>();
        for (Container c : containers) {
            names.add(c.getName() + "  [" + c.getScreenSize() + "]");
        }
        if (names.isEmpty()) {
            names.add("No Studio container yet (Tap Create New Container)");
        }
        suppressSpinnerEvents = true;
        containerPicker.setAdapter(createSharpDropdownAdapter(names));
        if (old >= 0 && old < containers.size()) {
            containerPicker.setSelection(old);
        }
        suppressSpinnerEvents = false;
        updateTelemetryBadges();
    }

    private void updateTelemetryBadges() {
        RootFS fs = RootFS.find(this);
        boolean ready = fs.isValid() && fs.getVersion() >= RootFSInstaller.LATEST_VERSION;
        if (ready) {
            runtimeBadgeText.setText("RUNTIME ENGINE: READY (WINE 10.10 + KERNELBASE PATCHED)");
            runtimeBadgeText.setTextColor(COLOR_ACCENT_EMERALD);
        } else {
            runtimeBadgeText.setText("RUNTIME ENGINE: NOT INITIALIZED (TAP INSTALL / REPAIR RUNTIME)");
            runtimeBadgeText.setTextColor(0xfffbbf24);
        }

        String presetKey = studioPrefs.getString("pref_graphics_preset", "DXVK_1103_VORTEK");
        if (containers.isEmpty()) {
            containerBadgeText.setText("CONTAINER: NONE CREATED YET  |  PRESET: " + presetKey);
            studioVersionBadgeText.setText("STUDIO BUILD: AWAITING CONTAINER");
            studioVersionBadgeText.setTextColor(COLOR_TEXT_SECONDARY);
            statusBanner.setText(ready
                    ? "Runtime ready. Create a container below, then download Roblox Studio."
                    : "Step 1: Tap 'INSTALL / REPAIR RUNTIME ENGINE' to extract system files.");
            return;
        }

        int idx = Math.max(0, Math.min(containers.size() - 1, containerPicker.getSelectedItemPosition()));
        Container c = containers.get(idx);
        containerBadgeText.setText("CONTAINER: " + c.getName().toUpperCase() + "  |  PRESET: " + presetKey + "  |  " + c.getScreenSize());

        File exe = null;
        String ver = "";
        try {
            exe = StudioDeployment.currentExecutable(c.getRootDir());
            File marker = new File(StudioDeployment.base(c.getRootDir()), "current-version.txt");
            if (marker.isFile()) ver = DeploymentFiles.read(marker).trim();
        } catch (Exception ignored) {}

        if (exe != null && exe.isFile()) {
            studioVersionBadgeText.setText("STUDIO BUILD: INSTALLED (" + (ver.isEmpty() ? exe.getName() : ver) + ")");
            studioVersionBadgeText.setTextColor(COLOR_ACCENT_EMERALD);
            statusBanner.setText("Ready to launch! Tap 'LAUNCH ROBLOX STUDIO NOW' above.");
        } else {
            studioVersionBadgeText.setText("STUDIO BUILD: NOT DOWNLOADED YET");
            studioVersionBadgeText.setTextColor(0xfffbbf24);
            statusBanner.setText("Container ready. Tap 'DOWNLOAD / UPDATE ROBLOX STUDIO' to install Studio.");
        }
    }

    private Container selected() {
        if (busy) {
            message("Please wait", "A setup task is currently running.");
            return null;
        }
        int i = containerPicker.getSelectedItemPosition();
        if (i < 0 || i >= containers.size()) {
            message("No Container Selected", "Tap 'CREATE NEW CONTAINER' first.");
            return null;
        }
        return containers.get(i);
    }

    private void createContainer() {
        if (busy) return;
        RootFS fs = RootFS.find(this);
        if (!fs.isValid() || fs.getVersion() < RootFSInstaller.LATEST_VERSION) {
            new AlertDialog.Builder(this)
                    .setTitle("Initialize Runtime Engine First")
                    .setMessage("The Wine system runtime needs to be extracted once before creating a Studio container. Extract runtime now?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Install Runtime", (d, w) -> installOrRepairRuntime())
                    .show();
            return;
        }
        try {
            JSONObject data = buildContainerPresetJson();
            data.put("name", "Studio Bridge " + (containers.size() + 1));
            data.put("extraData", new JSONObject().put("studioBridge", "1"));
            begin("Creating dedicated Studio Bridge container…", false);
            new ContainerManager(this).createContainerAsync(data, c -> {
                if (isDestroyed()) return;
                end();
                if (c == null) {
                    refresh();
                    message("Container creation failed", "Could not create container. Check free storage space.");
                    return;
                }
                try {
                    applySettingsToContainerObject(c);
                } catch (Exception ignored) {}
                refresh();
                for (int i = 0; i < containers.size(); i++) {
                    if (containers.get(i).id == c.id) {
                        containerPicker.setSelection(i);
                        break;
                    }
                }
                updateTelemetryBadges();
                statusBanner.setText("Container '" + c.getName() + "' created and patched! Download Roblox Studio next.");
            });
        } catch (Exception e) {
            end();
            message("Cannot create container", e.toString());
        }
    }

    private void confirmInstall() {
        if (containers.isEmpty()) {
            createContainer();
            return;
        }
        Container c = selected();
        if (c == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Download Roblox Studio")
                .setMessage("This downloads and extracts the latest official Windows 64-bit Roblox Studio packages into " + c.getName() + " and applies the Wine/DXVK compatibility patches.\n\n" + DISCLAIMER)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Download & Patch", (d, w) -> install(c))
                .show();
    }

    private void install(Container c) {
        begin("Connecting to Roblox deployment service…", true);
        deployment = new StudioDeployment((msg, pct) -> runOnUiThread(() -> {
            if (progress != null && !isDestroyed()) {
                progress.setMessage(msg);
                progress.setProgress(pct);
            }
        }));
        final StudioDeployment task = deployment;
        executor.execute(() -> {
            String result;
            try {
                File exe = task.install(c.getRootDir(), getCacheDir());
                applySettingsToContainerObject(c);
                result = "Roblox Studio installed (" + exe.getParentFile().getName() + ") and patched for Wine 10.10 + DXVK!";
            } catch (Exception e) {
                result = "Installation did not complete: " + e.getMessage();
            }
            final String text = result;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                deployment = null;
                end();
                refresh();
                statusBanner.setText(text);
                message("Studio Deployment Complete", text);
            });
        });
    }

    private void launchStudio() {
        if (containers.isEmpty()) {
            createContainer();
            return;
        }
        Container c = selected();
        if (c == null) return;
        try {
            File exe = StudioDeployment.currentExecutable(c.getRootDir());
            if (exe == null || !exe.isFile()) {
                confirmInstall();
                return;
            }
            applySettingsToContainerObject(c);
            Intent intent = new Intent(this, XServerDisplayActivity.class);
            intent.putExtra("container_id", c.id);
            intent.putExtra("exec_path", exe.getAbsolutePath());
            intent.putExtra("studio_bridge_session", true);
            startActivity(intent);
        } catch (Exception e) {
            message("Cannot launch Studio", e.toString());
        }
    }

    private void begin(String msg, boolean cancel) {
        busy = true;
        previousOrientation = getRequestedOrientation();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        progress = new ProgressDialog(this);
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setMessage(msg);
        progress.setCancelable(false);
        progress.setCanceledOnTouchOutside(false);
        if (cancel) {
            progress.setButton(ProgressDialog.BUTTON_NEGATIVE, "Cancel", (d, w) -> {
                if (deployment != null) deployment.cancel();
            });
        }
        progress.show();
    }

    private void end() {
        busy = false;
        if (progress != null) {
            progress.dismiss();
            progress = null;
        }
        setRequestedOrientation(previousOrientation);
    }

    @Override
    protected void onDestroy() {
        if (deployment != null) deployment.cancel();
        executor.shutdownNow();
        if (progress != null) progress.dismiss();
        super.onDestroy();
    }

    private void message(String title, String body) {
        if (!isDestroyed()) {
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(body)
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    // -------------------------------------------------------------------------
    // Sharp-Edged Double-Stroke UI Component Builders
    // -------------------------------------------------------------------------

    private LinearLayout createCard(LinearLayout parent, int topColor, int bottomColor, int accentStrokeColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        card.setPadding(pad, pad, pad, pad);

        GradientDrawable outer = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{topColor, bottomColor}
        );
        outer.setShape(GradientDrawable.RECTANGLE);
        outer.setCornerRadius(0f);
        outer.setStroke(dp(1), accentStrokeColor);

        GradientDrawable inner = new GradientDrawable();
        inner.setShape(GradientDrawable.RECTANGLE);
        inner.setCornerRadius(0f);
        inner.setColor(Color.TRANSPARENT);
        inner.setStroke(dp(1), 0x28ffffff);

        LayerDrawable layered = new LayerDrawable(new Drawable[]{outer, inner});
        int inset = dp(3);
        layered.setLayerInset(1, inset, inset, inset, inset);
        card.setBackground(layered);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = dp(16);
        parent.addView(card, lp);
        return card;
    }

    private void addSectionHeader(LinearLayout card, String titleText, String descText) {
        TextView header = createLabel(titleText, 14, COLOR_ACCENT_CYAN, true);
        header.setLetterSpacing(0.08f);
        card.addView(header, marginParams(0, 0, 0, 4));

        TextView desc = createLabel(descText, 12, COLOR_TEXT_SECONDARY, false);
        card.addView(desc, marginParams(0, 0, 0, 14));
    }

    private void addFieldLabel(LinearLayout card, String labelText) {
        TextView label = createLabel(labelText, 11, 0xffcbd5e1, true);
        label.setLetterSpacing(0.07f);
        card.addView(label, marginParams(0, 2, 0, 6));
    }

    private TextView createLabel(String value, int sp, int color, boolean boldCondensed) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(dp(2), 1.12f);
        t.setTypeface(Typeface.create(
                boldCondensed ? "sans-serif-condensed" : "sans-serif",
                boldCondensed ? Typeface.BOLD : Typeface.NORMAL
        ));
        return t;
    }

    private Button createSharpButtonView(String label, int style, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(COLOR_TEXT_PRIMARY);
        b.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        b.setLetterSpacing(0.06f);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setBackground(createDoubleStrokeDrawable(style));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void addSharpButton(LinearLayout parent, String label, int style, Runnable action) {
        Button b = createSharpButtonView(label, style, action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
        );
        lp.bottomMargin = dp(10);
        parent.addView(b, lp);
    }

    private Drawable createDoubleStrokeDrawable(int style) {
        int topColor;
        int bottomColor;
        int outerStroke;
        int innerStroke;

        switch (style) {
            case BTN_SUCCESS:
                topColor = 0xff0d4736;
                bottomColor = 0xff07291f;
                outerStroke = 0xff10b981;
                innerStroke = 0x776ee7b7;
                break;
            case BTN_PRIMARY:
                topColor = 0xff16336b;
                bottomColor = 0xff0d1f45;
                outerStroke = 0xff3b82f6;
                innerStroke = 0x777dd3fc;
                break;
            case BTN_DANGER:
                topColor = 0xff3b1220;
                bottomColor = 0xff220912;
                outerStroke = 0xfff43f5e;
                innerStroke = 0x66fda4af;
                break;
            case BTN_SECONDARY:
            default:
                topColor = 0xff152033;
                bottomColor = 0xff0c1320;
                outerStroke = 0xff475569;
                innerStroke = 0x55cbd5e1;
                break;
        }

        GradientDrawable outer = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{topColor, bottomColor}
        );
        outer.setShape(GradientDrawable.RECTANGLE);
        outer.setCornerRadius(0f);
        outer.setStroke(dp(1), outerStroke);

        GradientDrawable inner = new GradientDrawable();
        inner.setShape(GradientDrawable.RECTANGLE);
        inner.setCornerRadius(0f);
        inner.setColor(Color.TRANSPARENT);
        inner.setStroke(dp(1), innerStroke);

        LayerDrawable layered = new LayerDrawable(new Drawable[]{outer, inner});
        int inset = dp(3);
        layered.setLayerInset(1, inset, inset, inset, inset);
        return layered;
    }

    private Spinner createSharpSpinner(String[] options) {
        List<String> list = new ArrayList<>();
        for (String s : options) list.add(s);
        Spinner s = new Spinner(this);
        s.setMinimumHeight(dp(48));
        s.setPopupBackgroundDrawable(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xff0f172a, 0xff090d16}
        ));

        GradientDrawable outer = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xff111a2c, 0xff0b111e}
        );
        outer.setCornerRadius(0f);
        outer.setStroke(dp(1), COLOR_ACCENT_BLUE);

        GradientDrawable inner = new GradientDrawable();
        inner.setCornerRadius(0f);
        inner.setColor(Color.TRANSPARENT);
        inner.setStroke(dp(1), 0x407dd3fc);

        LayerDrawable bg = new LayerDrawable(new Drawable[]{outer, inner});
        int inset = dp(3);
        bg.setLayerInset(1, inset, inset, inset, inset);
        s.setBackground(bg);

        s.setAdapter(createSharpDropdownAdapter(list));
        return s;
    }

    private ArrayAdapter<String> createSharpDropdownAdapter(List<String> items) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextColor(COLOR_TEXT_PRIMARY);
                tv.setTextSize(13);
                tv.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
                tv.setPadding(dp(12), dp(10), dp(12), dp(10));
                tv.setSingleLine(true);
                return tv;
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView tv = (TextView) super.getDropDownView(position, convertView, parent);
                tv.setTextColor(COLOR_TEXT_PRIMARY);
                tv.setBackgroundColor(position % 2 == 0 ? 0xff0f172a : 0xff131e33);
                tv.setTextSize(13);
                tv.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
                tv.setPadding(dp(14), dp(12), dp(14), dp(12));
                return tv;
            }
        };
    }

    private CheckBox addStyledCheckbox(LinearLayout parent, String label, boolean defaultChecked) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setChecked(defaultChecked);
        cb.setTextColor(COLOR_TEXT_PRIMARY);
        cb.setTextSize(12.5f);
        cb.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        cb.setPadding(dp(8), dp(8), dp(8), dp(8));

        GradientDrawable boxBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xff0e1626, 0xff090f1a}
        );
        boxBg.setCornerRadius(0f);
        boxBg.setStroke(dp(1), 0xff26354d);
        cb.setBackground(boxBg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = dp(6);
        parent.addView(cb, lp);
        return cb;
    }

    private void setSpinnerByKey(Spinner spinner, String[] keys, String targetKey) {
        if (spinner == null || keys == null) return;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equalsIgnoreCase(targetKey)) {
                spinner.setSelection(i);
                return;
            }
        }
        spinner.setSelection(0);
    }

    private String pickKey(Spinner spinner, String[] keys, String fallback) {
        if (spinner == null || keys == null || keys.length == 0) return fallback;
        int pos = spinner.getSelectedItemPosition();
        if (pos < 0 || pos >= keys.length) return fallback;
        return keys[pos];
    }

    private LinearLayout.LayoutParams marginParams(int leftDp, int topDp, int rightDp, int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(dp(leftDp), dp(topDp), dp(rightDp), dp(bottomDp));
        return lp;
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }
}
