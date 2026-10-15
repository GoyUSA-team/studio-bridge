/* Studio Bridge additions, 2026. LGPL-2.1-or-later. */
package com.winlator.studiobridge;

import androidx.appcompat.app.AppCompatActivity;
import android.view.MotionEvent;
import android.widget.*;
import android.view.View;
import com.winlator.core.AppUtils;
import com.winlator.xserver.*;
import java.util.*;

/** Large touch targets; key-up and mouse-up on cancellation, pause, or detach. */
public final class StudioTouchBar extends HorizontalScrollView {
    private final XServer server;
    private final Set<XKeycode> held=EnumSet.noneOf(XKeycode.class);
    private boolean rightMouse;
    private final LinearLayout row;
    public StudioTouchBar(AppCompatActivity activity,XServer server) {
        super(activity);this.server=server;setBackgroundColor(0xff202020);setHorizontalScrollBarEnabled(true);setFillViewport(false);
        row=new LinearLayout(activity);row.setPadding(dp(4),0,dp(4),0);addView(row);
        action("Unofficial",()->new androidx.appcompat.app.AlertDialog.Builder(activity).setTitle("Experimental fan-made project").setMessage(StudioHubActivity.DISCLAIMER).setPositiveButton("Close",null).show());
        action("Keyboard",()->AppUtils.showKeyboard(activity));
        chord("Undo",XKeycode.KEY_CTRL_L,XKeycode.KEY_Z);
        chord("Redo",XKeycode.KEY_CTRL_L,XKeycode.KEY_Y);
        chord("Save",XKeycode.KEY_CTRL_L,XKeycode.KEY_S);
        chord("Esc",XKeycode.KEY_ESC);chord("Play F5",XKeycode.KEY_F5);chord("Focus F",XKeycode.KEY_F);
        hold("W ↑",XKeycode.KEY_W);hold("A ←",XKeycode.KEY_A);hold("S ↓",XKeycode.KEY_S);hold("D →",XKeycode.KEY_D);
        Button look=button("Look · hold");
        look.setOnTouchListener((v,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){v.getParent().requestDisallowInterceptTouchEvent(true);rightMouse=true;server.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);v.setPressed(true);return true;}
            if(e.getActionMasked()==MotionEvent.ACTION_UP || e.getActionMasked()==MotionEvent.ACTION_CANCEL){if(rightMouse)server.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);rightMouse=false;v.setPressed(false);v.performClick();v.getParent().requestDisallowInterceptTouchEvent(false);return true;}
            return true;
        });
    }
    private void action(String label,Runnable action){button(label).setOnClickListener(v->action.run());}
    private void chord(String label,XKeycode... keys){action(label,()->{
        for(XKeycode k:keys)if(held.contains(k))return;
        for(XKeycode k:keys){held.add(k);server.injectKeyPress(k);}
        postDelayed(()->{for(int i=keys.length-1;i>=0;i--)if(held.remove(keys[i]))server.injectKeyRelease(keys[i]);},80);
    });}
    private void hold(String label,XKeycode key){Button b=button(label);b.setOnTouchListener((v,e)->{
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){v.getParent().requestDisallowInterceptTouchEvent(true);held.add(key);server.injectKeyPress(key);v.setPressed(true);return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_UP || e.getActionMasked()==MotionEvent.ACTION_CANCEL){if(held.remove(key))server.injectKeyRelease(key);v.setPressed(false);v.performClick();v.getParent().requestDisallowInterceptTouchEvent(false);return true;}
        return true;
    });}
    public void releaseAll(){for(XKeycode k:held)server.injectKeyRelease(k);held.clear();if(rightMouse){server.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);rightMouse=false;}}
    @Override protected void onDetachedFromWindow(){releaseAll();super.onDetachedFromWindow();}
    private Button button(String text){Button b=new Button(getContext());b.setText(text);b.setAllCaps(false);b.setTextSize(14);b.setContentDescription(text);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(52));lp.setMargins(dp(2),dp(4),dp(2),dp(4));row.addView(b,lp);return b;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
