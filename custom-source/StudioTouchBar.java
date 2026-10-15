/* Studio Bridge additions, 2026. LGPL-2.1-or-later. */
package com.winlator.studiobridge;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.winlator.XServerDisplayActivity;
import com.winlator.core.AppUtils;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.XKeycode;
import com.winlator.xserver.XServer;

import java.util.EnumSet;
import java.util.Set;

/**
 * Sharp-edged, double-stroke professional Studio command bar with key-up and mouse-up safety on pause/detach.
 */
public final class StudioTouchBar extends HorizontalScrollView {
    private static final int STYLE_ACCENT = 0;
    private static final int STYLE_STANDARD = 1;
    private static final int STYLE_PLAY = 2;
    private static final int STYLE_HOLD = 3;
    private static final int STYLE_DANGER = 4;

    private final XServer server;
    private final Set<XKeycode> held = EnumSet.noneOf(XKeycode.class);
    private boolean rightMouse;
    private final LinearLayout row;

    public StudioTouchBar(AppCompatActivity activity, XServer server) {
        super(activity);
        this.server = server;
        setHorizontalScrollBarEnabled(false);
        setFillViewport(false);

        GradientDrawable barBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xff070a10, 0xff0f1726, 0xff090d16}
        );
        barBg.setStroke(dp(1), 0xff223049);
        setBackground(barBg);

        row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(4), dp(6), dp(4));
        addView(row);

        action("LOGIN", STYLE_ACCENT, () -> {
            if (activity instanceof XServerDisplayActivity) {
                StudioLoginBridge.showLoginBridgeMenu((XServerDisplayActivity) activity);
            }
        });
        action("KEYBOARD", STYLE_STANDARD, () -> AppUtils.showKeyboard(activity));
        chord("PLAY F5", STYLE_PLAY, XKeycode.KEY_F5);
        chord("UNDO", STYLE_STANDARD, XKeycode.KEY_CTRL_L, XKeycode.KEY_Z);
        chord("REDO", STYLE_STANDARD, XKeycode.KEY_CTRL_L, XKeycode.KEY_Y);
        chord("SAVE", STYLE_STANDARD, XKeycode.KEY_CTRL_L, XKeycode.KEY_S);
        chord("FOCUS F", STYLE_STANDARD, XKeycode.KEY_F);
        chord("ESC", STYLE_STANDARD, XKeycode.KEY_ESC);

        hold("W ↑", XKeycode.KEY_W);
        hold("A ←", XKeycode.KEY_A);
        hold("S ↓", XKeycode.KEY_S);
        hold("D →", XKeycode.KEY_D);

        Button look = button("LOOK · HOLD", STYLE_HOLD);
        look.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                rightMouse = true;
                server.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                v.setPressed(true);
                applyButtonFill(look, STYLE_HOLD, true);
                return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                if (rightMouse) server.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                rightMouse = false;
                v.setPressed(false);
                applyButtonFill(look, STYLE_HOLD, false);
                v.performClick();
                v.getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
            return true;
        });

        action("WINDOWS", STYLE_STANDARD, () -> {
            if (activity instanceof XServerDisplayActivity) {
                ((XServerDisplayActivity) activity).showActiveWindows();
            }
        });
        action("LOGS", STYLE_STANDARD, () -> {
            if (activity instanceof XServerDisplayActivity) {
                ((XServerDisplayActivity) activity).showDebugLogs();
            }
        });
        action("HUB / EXIT", STYLE_DANGER, () -> {
            if (activity instanceof XServerDisplayActivity) {
                ((XServerDisplayActivity) activity).exit();
            } else {
                activity.finish();
            }
        });
    }

    private void action(String label, int style, Runnable action) {
        Button b = button(label, style);
        b.setOnClickListener(v -> action.run());
    }

    private void chord(String label, int style, XKeycode... keys) {
        action(label, style, () -> {
            for (XKeycode k : keys) if (held.contains(k)) return;
            for (XKeycode k : keys) {
                held.add(k);
                server.injectKeyPress(k);
            }
            postDelayed(() -> {
                for (int i = keys.length - 1; i >= 0; i--) {
                    if (held.remove(keys[i])) server.injectKeyRelease(keys[i]);
                }
            }, 80);
        });
    }

    private void hold(String label, XKeycode key) {
        Button b = button(label, STYLE_HOLD);
        b.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                held.add(key);
                server.injectKeyPress(key);
                v.setPressed(true);
                applyButtonFill(b, STYLE_HOLD, true);
                return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                if (held.remove(key)) server.injectKeyRelease(key);
                v.setPressed(false);
                applyButtonFill(b, STYLE_HOLD, false);
                v.performClick();
                v.getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
            return true;
        });
    }

    public void releaseAll() {
        for (XKeycode k : held) server.injectKeyRelease(k);
        held.clear();
        if (rightMouse) {
            server.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
            rightMouse = false;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseAll();
        super.onDetachedFromWindow();
    }

    private Button button(String text, int style) {
        Button b = new Button(getContext());
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(11.5f);
        b.setTextColor(0xfff1f5f9);
        b.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        b.setLetterSpacing(0.05f);
        b.setContentDescription(text);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setMinimumWidth(dp(64));
        b.setMinWidth(dp(64));
        applyButtonFill(b, style, false);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(48));
        lp.setMargins(dp(3), dp(2), dp(3), dp(2));
        row.addView(b, lp);
        return b;
    }

    private void applyButtonFill(Button b, int style, boolean active) {
        int topColor;
        int bottomColor;
        int outerStroke;
        int innerStroke;

        switch (style) {
            case STYLE_ACCENT:
                topColor = active ? 0xff1d4ed8 : 0xff152c5b;
                bottomColor = active ? 0xff1e3a8a : 0xff0d1b3a;
                outerStroke = 0xff3b82f6;
                innerStroke = 0x667dd3fc;
                break;
            case STYLE_PLAY:
                topColor = active ? 0xff059669 : 0xff0f392b;
                bottomColor = active ? 0xff065f46 : 0xff09221a;
                outerStroke = 0xff10b981;
                innerStroke = 0x666ee7b7;
                break;
            case STYLE_HOLD:
                topColor = active ? 0xff1e3a8a : 0xff131c2e;
                bottomColor = active ? 0xff172554 : 0xff0b111d;
                outerStroke = active ? 0xff38bdf8 : 0xff334155;
                innerStroke = active ? 0x88bae6fd : 0x3894a3b8;
                break;
            case STYLE_DANGER:
                topColor = active ? 0xff9f1239 : 0xff31101d;
                bottomColor = active ? 0xff881337 : 0xff1c0911;
                outerStroke = 0xfff43f5e;
                innerStroke = 0x55fda4af;
                break;
            case STYLE_STANDARD:
            default:
                topColor = active ? 0xff1e293b : 0xff141d2b;
                bottomColor = active ? 0xff0f172a : 0xff0c121c;
                outerStroke = 0xff3b4c66;
                innerStroke = 0x40cbd5e1;
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
        b.setBackground(layered);
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }
}
