/* Studio Bridge additions, 2026. LGPL-2.1-or-later. */
package com.winlator.studiobridge;

import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;
import com.winlator.MainActivity;
import com.winlator.XServerDisplayActivity;
import com.winlator.container.*;
import com.winlator.box64.Box64Preset;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.RootFSInstaller;
import org.json.JSONObject;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/** A purpose-built experimental setup screen. It does not claim Studio compatibility. */
public class StudioHubActivity extends AppCompatActivity {
    public static final String DISCLAIMER="Unofficial, fan-made experiment toward a Roblox Studio Android port. Not affiliated with, endorsed by, or associated with Roblox Corporation. Use at your own risk. This is a Winlator-based compatibility experiment, not a native Studio port; successful launch is unverified.";
    private final List<Container> containers=new ArrayList<>();
    private Spinner containerPicker,profilePicker,resolutionPicker;
    private TextView status;
    private LinearLayout column;
    private boolean busy;
    private StudioDeployment deployment;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private ProgressDialog progress;
    private int previousOrientation;
    private static final int TEXT=0xffeeeeed, SECONDARY=0xffbebebc;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);getWindow().setStatusBarColor(0xff191919);getWindow().setNavigationBarColor(0xff191919);
        android.content.SharedPreferences initPrefs=PreferenceManager.getDefaultSharedPreferences(this);
        initPrefs.edit().putBoolean("enable_background_protection",true).putBoolean("enable_background_wakelock",true).apply();
        if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},1001);
        }
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(0xff191919);
        column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setPadding(dp(20),dp(24),dp(20),dp(32));scroll.addView(column);setContentView(scroll);
        text("STUDIO BRIDGE · EXPERIMENTAL",14,0xff87b8f0,12);
        text("A workspace, not a promise.",28,TEXT,16);
        text(DISCLAIMER,16,SECONDARY,24);
        text("1  Prepare the runtime",20,TEXT,8);
        text("Open Winlator once, allow its normal setup, then return here. No Roblox files are included in this app.",16,SECONDARY,8);
        button("Open Winlator setup / settings",()->startActivity(new Intent(this,MainActivity.class)));
        text("2  Create a dedicated Studio container",20,TEXT,8);
        text("These are test presets, not measured Mali optimizations. Compatibility uses VirGL + WineD3D; Vulkan uses Vortek + DXVK with the phone's system driver. Neither preset selects Turnip or fakes GPU capabilities.",16,SECONDARY,12);
        profilePicker=spinner(new String[]{"Compatibility · VirGL / WineD3D","Experimental Vulkan · Vortek / DXVK"});
        resolutionPicker=spinner(new String[]{"1280x720 · balanced workspace","960x540 · lower rendering load"});
        button("Create a new Studio container",this::createContainer);
        containerPicker=spinner(new String[]{"No Studio container yet"});
        button("Apply selected preset to this Studio container",this::confirmPreset);
        text("3  Download, then test",20,TEXT,8);
        text("Downloads come from Roblox's HTTPS deployment service. Allow several GB of free storage for the runtime, Studio, cache and projects. Installation success does not mean Studio will launch.",16,SECONDARY,12);
        button("Download / update Studio from Roblox",this::confirmInstall);
        button("Launch Studio · experimental",this::launchStudio);
        status=text("Checking local setup…",16,0xff87b8f0,16);
        button("Touch controls and troubleshooting",()->new AlertDialog.Builder(this).setTitle("Using the experiment").setMessage("Studio launches with a large-button toolbar: keyboard, undo, redo, save, Esc, F5 and held W/A/S/D/right mouse. Slide the toolbar horizontally. The desktop above remains a relative touchpad. Four-finger tap opens Winlator's session menu.\n\nIf the viewport is black or crashes, close the session and try the other graphics preset. No driver can guarantee support on every Mali model. Use Settings to enable Wine/Box64 logs, then use Logs in the session menu.\n\nStudio handles its own Roblox sign-in. This app does not request passwords, cookies, or tokens. Save local test files first; do not trust an untested session with important work.\n\nThis build currently retains Winlator's original Android package ID because its bundled native runtime has hardcoded paths. It is not a side-by-side install. Do not remove an existing Winlator installation or its data without a backup.").setPositiveButton("Close",null).show());
        text("Based on Winlator by brunodev85 and its contributors. Upstream licenses and credits are preserved. No association with Roblox Corporation.",14,SECONDARY,0);
    }
    @Override public void onResume(){super.onResume();if(containerPicker!=null)refresh();}
    private void refresh() {
        int old=containerPicker.getSelectedItemPosition();containers.clear();
        for(Container c:new ContainerManager(this).getContainers())if(c.getExtra("studioBridge","0").equals("1"))containers.add(c);
        List<String> names=new ArrayList<>();for(Container c:containers)names.add(c.getName());if(names.isEmpty())names.add("No Studio container yet");
        containerPicker.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));
        if(old>=0 && old<containers.size())containerPicker.setSelection(old);
        boolean ready=RootFS.find(this).isValid() && RootFS.find(this).getVersion()>=RootFSInstaller.LATEST_VERSION;
        status.setText(ready?"Runtime present. Studio launch and Mali rendering still require device testing.":"Runtime not ready. Complete step 1 before creating a container.");
    }
    private Container selected() {
        if(busy){message("Please wait","A setup task is still running.");return null;}
        int i=containerPicker.getSelectedItemPosition();
        if(i<0 || i>=containers.size()){message("Create a container first","Use step 2 to create a dedicated Studio container.");return null;}
        return containers.get(i);
    }
    private JSONObject preset() throws Exception {
        boolean vulkan=profilePicker.getSelectedItemPosition()==1;JSONObject data=new JSONObject();
        data.put("screenSize",resolutionPicker.getSelectedItemPosition()==1?"960x540":"1280x720");
        data.put("graphicsDriver","vortek,virgl");data.put("graphicsDriverConfig", "|");
        data.put("dxwrapper",vulkan?DXWrappers.DXVK:DXWrappers.WINED3D);data.put("dxwrapperConfig","");
        data.put("envVars","MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=256MB WINEESYNC=1");
        data.put("box64Preset",Box64Preset.STABILITY);data.put("startupSelection",Container.STARTUP_SELECTION_NORMAL);
        data.put("wincomponents",Container.DEFAULT_WINCOMPONENTS);
        return data;
    }
    private void createContainer() {
        if(busy)return;
        RootFS fs=RootFS.find(this);
        if(!fs.isValid() || fs.getVersion()<RootFSInstaller.LATEST_VERSION){message("Runtime required","Open Winlator setup, let system files finish installing, then return here.");return;}
        try {
            JSONObject data=preset();data.put("name","Studio Bridge "+(containers.size()+1));data.put("extraData",new JSONObject().put("studioBridge","1"));
            begin("Creating a dedicated container…",false);
            new ContainerManager(this).createContainerAsync(data,c->{
                if(isDestroyed())return;
                end();refresh();if(c==null){message("Container creation failed","No success was recorded. Check runtime installation and free space.");return;}
                for(int i=0;i<containers.size();i++)if(containers.get(i).id==c.id)containerPicker.setSelection(i);
                android.content.SharedPreferences prefs=PreferenceManager.getDefaultSharedPreferences(this);
                android.content.SharedPreferences.Editor edit=prefs.edit();
                if(!prefs.contains("cursor_scale"))edit.putFloat("cursor_scale",1.4f);
                if(!prefs.contains("cursor_speed"))edit.putFloat("cursor_speed",0.7f);
                if(!prefs.contains("move_cursor_to_touchpoint"))edit.putBoolean("move_cursor_to_touchpoint",false);
                edit.putBoolean("enable_background_protection",true);
                edit.putBoolean("enable_background_wakelock",true);
                edit.apply();status.setText("Dedicated container created. Download Studio next; compatibility is unverified.");
            });
        }catch(Exception e){end();message("Cannot create container",e.toString());}
    }
    private void confirmPreset() {
        Container c=selected();if(c==null)return;
        new AlertDialog.Builder(this).setTitle("Apply experimental preset?").setMessage("This changes graphics, resolution, startup, environment and Box64 settings on "+c.getName()+". Existing non-Studio containers are never listed here. Close any running session first.").setNegativeButton("Cancel",null).setPositiveButton("Apply",(d,w)->{
            try{JSONObject p=preset();c.setScreenSize(p.getString("screenSize"));c.setGraphicsDriver(p.getString("graphicsDriver"));c.setGraphicsDriverConfig(p.getString("graphicsDriverConfig"));c.setDXWrapper(p.getString("dxwrapper"));c.setDXWrapperConfig("");c.setEnvVars(p.getString("envVars"));c.setBox64Preset(Box64Preset.STABILITY);c.setStartupSelection(Container.STARTUP_SELECTION_NORMAL);c.saveData();status.setText("Preset saved. Restart the Studio session to test it.");}
            catch(Exception e){message("Preset failed",e.toString());}
        }).show();
    }
    private void confirmInstall() {
        Container c=selected();if(c==null)return;
        new AlertDialog.Builder(this).setTitle("Download Studio from Roblox?").setMessage("This downloads and extracts Roblox's Windows Studio packages into the dedicated container. Package sizes vary; several GB of free storage are recommended. No Roblox files are bundled or redistributed by this APK. Close any running Studio session first.\n\n"+DISCLAIMER).setNegativeButton("Cancel",null).setPositiveButton("Download",(d,w)->install(c)).show();
    }
    private void install(Container c) {
        begin("Checking release…",true);
        deployment=new StudioDeployment((msg,pct)->runOnUiThread(()->{if(progress!=null && !isDestroyed()){progress.setMessage(msg);progress.setProgress(pct);}}));
        final StudioDeployment task=deployment;
        executor.execute(()->{
            String result;
            try{File exe=task.install(c.getRootDir(),getCacheDir());result="Studio packages installed at "+exe.getName()+". No successful launch has been verified. Use Launch Studio to test.";}
            catch(Exception e){result="Installation did not complete: "+e.getMessage();}
            final String text=result;
            runOnUiThread(()->{if(isDestroyed())return;deployment=null;end();status.setText(text);message("Installation result",text);});
        });
    }
    private void launchStudio() {
        Container c=selected();if(c==null)return;
        try {
            File exe=StudioDeployment.currentExecutable(c.getRootDir());
            if(exe==null){message("Studio not installed","Complete the download step first.");return;}
            StudioLoginBridge.configureWineRegistry(c);
            Intent intent=new Intent(this,XServerDisplayActivity.class);intent.putExtra("container_id",c.id);intent.putExtra("exec_path",exe.getAbsolutePath());intent.putExtra("studio_bridge_session",true);startActivity(intent);
        }catch(Exception e){message("Cannot launch",e.toString());}
    }
    private void begin(String msg,boolean cancel) {
        busy=true;previousOrientation=getRequestedOrientation();setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        progress=new ProgressDialog(this);progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);progress.setMax(100);progress.setMessage(msg);progress.setCancelable(false);progress.setCanceledOnTouchOutside(false);
        if(cancel)progress.setButton(ProgressDialog.BUTTON_NEGATIVE,"Cancel",(d,w)->{if(deployment!=null)deployment.cancel();});progress.show();
    }
    private void end(){busy=false;if(progress!=null){progress.dismiss();progress=null;}setRequestedOrientation(previousOrientation);}
    @Override protected void onDestroy(){if(deployment!=null)deployment.cancel();executor.shutdownNow();if(progress!=null)progress.dismiss();super.onDestroy();}
    private void message(String title,String body){if(!isDestroyed())new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("Close",null).show();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int sp,int color,int bottom){TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(color);t.setLineSpacing(dp(3),1.1f);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(bottom);column.addView(t,lp);return t;}
    private void button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(16);b.setMinHeight(dp(52));b.setOnClickListener(v->action.run());LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(16);column.addView(b,lp);}
    private Spinner spinner(String[] options){Spinner s=new Spinner(this);s.setMinimumHeight(dp(52));s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,options));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(56));lp.bottomMargin=dp(12);column.addView(s,lp);return s;}
}
