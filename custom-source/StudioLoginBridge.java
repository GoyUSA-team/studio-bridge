/* Studio Bridge additions, 2026. Distributed under LGPL-2.1-or-later. */
package com.winlator.studiobridge;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
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
import com.winlator.core.WineRegistryEditor;
import com.winlator.core.WineUtils;
import com.winlator.winhandler.WinHandler;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/**
 * Bridges Roblox Studio's OAuth browser login inside Winlator:
 * 1. Intercepts Wine browser launch requests (RequestCodes.OPEN_URL) so Android does not
 *    force split-screen Chrome (FLAG_ACTIVITY_LAUNCH_ADJACENT).
 * 2. Provides an in-app WebView sign-in modal that intercepts roblox-studio-auth: and
 *    roblox-studio: redirects directly inside the running Studio session.
 * 3. Supports full-screen external browser fallback while keeping Wine guest processes
 *    unpaused so Studio's PKCE OAuth state stays alive until the deep-link callback returns.
 * 4. Delivers long OAuth callback URIs into the active Wine session via a short batch helper
 *    (C:\StudioBridge\studio-auth.bat) to avoid WinHandler's 256-byte UDP packet limit.
 */
public final class StudioLoginBridge {
    public static final String DEFAULT_AUTH_URL = "https://authorize.roblox.com/";
    public static final String STUDIO_AUTH_BAT_DOS = "C:\\StudioBridge\\studio-auth.bat";
    public static final String STUDIO_PROTOCOL_REG_DOS = "C:\\StudioBridge\\studio-protocol.reg";
    private static final long EXTERNAL_AUTH_GRACE_MS = 10 * 60 * 1000L;

    private static volatile long externalAuthStartedAt = 0L;
    private static Dialog activeLoginDialog;
    private static WebView activeWebView;
    private static String lastAuthUrl = DEFAULT_AUTH_URL;
    private static String lastCallbackUri;

    private StudioLoginBridge() {}

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
                + "start \"\" \"" + dosExePath + "\" \"" + escapedUri + "\"\r\n";
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

    public static void configureWineRegistry(Container container) {
        if (container == null) return;
        try {
            File rootDir = container.getRootDir();
            File exe = StudioDeployment.currentExecutable(rootDir);
            if (exe == null) return;
            File marker = new File(StudioDeployment.base(rootDir), "current-version.txt");
            if (!marker.isFile()) return;
            String version = DeploymentFiles.read(marker).trim();
            String dosExePath = WineUtils.unixToDOSPath(exe.getAbsolutePath(), container);
            File base = StudioDeployment.base(rootDir);
            DeploymentFiles.mkdir(base);
            DeploymentFiles.write(new File(base, "studio-protocol.reg"), buildProtocolRegContent(dosExePath, version));

            String command = "\"" + dosExePath + "\" \"%1\"";
            String icon = dosExePath + ",0";
            for (String regName : new String[]{".wine/user.reg", ".wine/system.reg"}) {
                File regFile = new File(rootDir, regName);
                try (WineRegistryEditor editor = new WineRegistryEditor(regFile)) {
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
                    editor.setStringValue("Software\\Wine\\WineBrowser", "Browsers", "C:\\windows\\winhandler.exe /url");
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

    @SuppressLint("SetJavaScriptEnabled")
    public static void showInAppLoginDialog(XServerDisplayActivity activity, String startUrl) {
        if (activity == null || activity.isDestroyed()) return;
        dismissActiveLoginDialog();

        final Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff18181b);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(0xff242429);
        int pad = dp(activity, 8);
        header.setPadding(pad, dp(activity, 4), pad, dp(activity, 4));

        TextView title = new TextView(activity);
        title.setText("Studio Sign-In (In-App Bridge)");
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(title, titleLp);

        final ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);

        final WebView webView = new WebView(activity);
        activeWebView = webView;

        Button browserBtn = headerButton(activity, "External Browser", () -> {
            String current = webView.getUrl();
            openExternalBrowser(activity, (current != null && current.startsWith("http")) ? current : startUrl);
        });
        header.addView(browserBtn);

        Button pasteBtn = headerButton(activity, "Paste URI", () -> completeFromClipboard(activity));
        header.addView(pasteBtn);

        Button closeBtn = headerButton(activity, "Close", StudioLoginBridge::dismissActiveLoginDialog);
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

    private static Button headerButton(Context context, String label, Runnable action) {
        Button b = new Button(context);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 40));
        lp.leftMargin = dp(context, 6);
        b.setLayoutParams(lp);
        return b;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
