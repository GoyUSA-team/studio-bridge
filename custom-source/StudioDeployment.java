/* Studio Bridge additions, 2026. LGPL-2.1-or-later. No Roblox binaries are bundled. */
package com.winlator.studiobridge;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Official-CDN deployment installer, independent of Wine execution or authentication. */
public final class StudioDeployment {
    public static final String VERSION_URL="https://clientsettingscdn.roblox.com/v2/client-version/WindowsStudio64/channel/LIVE";
    public interface Progress { void update(String message,int percent); }
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private final Progress progress;
    private volatile HttpURLConnection active;
    public StudioDeployment(Progress progress) {this.progress=progress;}
    public void cancel() {cancelled.set(true);HttpURLConnection c=active;if(c!=null)c.disconnect();}
    private void check() throws IOException {if(cancelled.get() || Thread.currentThread().isInterrupted())throw new InterruptedIOException("Installation cancelled");}
    public static File base(File containerRoot) {return new File(containerRoot,".wine/drive_c/StudioBridge");}
    public static File currentExecutable(File containerRoot) throws IOException {
        File base=base(containerRoot),marker=new File(base,"current-version.txt");
        if(!marker.isFile())return null;
        String version=DeploymentFiles.read(marker).trim();
        if(!version.matches("version-[a-fA-F0-9]{16}"))throw new IOException("Invalid installed version marker");
        File exe=DeploymentFiles.safeChild(base,"versions/"+version+"/RobloxStudioBeta.exe");
        return DeploymentFiles.isPe(exe)?exe:null;
    }
    public File install(File containerRoot,File cacheRoot) throws Exception {
        File base=base(containerRoot);DeploymentFiles.mkdir(base);
        // The lock protects against duplicate installs across activity instances.
        try(RandomAccessFile lockFile=new RandomAccessFile(new File(base,"install.lock"),"rw");
            java.nio.channels.FileChannel channel=lockFile.getChannel()) {
            java.nio.channels.FileLock lock;
            try {lock=channel.tryLock();} catch(java.nio.channels.OverlappingFileLockException e) {lock=null;}
            if(lock==null)throw new IOException("Another Studio installation is already running");
            try {return deploy(base,cacheRoot);} finally {lock.release();}
        }
    }
    private File deploy(File base,File cacheRoot) throws Exception {
        check();progress.update("Checking Roblox's live Studio release…",0);
        String version=new JSONObject(text(VERSION_URL)).getString("clientVersionUpload");
        if(!version.matches("version-[a-fA-F0-9]{16}"))throw new IOException("Invalid release identifier");
        String cdn="https://setup.rbxcdn.com/channel/common/"+version+"-";
        List<DeploymentFiles.PackageInfo> packages=DeploymentFiles.parseManifest(text(cdn+"rbxPkgManifest.txt"));
        File versions=new File(base,"versions"),target=new File(versions,version);
        File complete=new File(target,".bridge-complete");
        if(complete.isFile() && DeploymentFiles.isPe(new File(target,"RobloxStudioBeta.exe"))) {
            markCurrent(base,version);progress.update("This release is already installed",100);return new File(target,"RobloxStudioBeta.exe");
        }
        if(target.exists())throw new IOException("Incomplete release directory exists. It was preserved; use a new dedicated container or inspect it before retrying.");
        long packed=0,unpacked=0;
        for(DeploymentFiles.PackageInfo p:packages) if(p.name.endsWith(".zip")){packed+=p.packed;unpacked+=p.unpacked;}
        if(base.getUsableSpace()<packed+unpacked+268435456L)throw new IOException("Not enough free space. Allow at least "+((packed+unpacked+268435456L)/1048576)+" MiB for Studio, in addition to the Winlator runtime.");
        File cache=new File(cacheRoot,"studio-deployment-"+UUID.randomUUID());
        File stage=new File(versions,version+".installing-"+UUID.randomUUID());
        DeploymentFiles.mkdir(cache);DeploymentFiles.mkdir(stage);
        try {
            progress.update("Reading official installation layout…",1);
            File installer=new File(cache,"layout-installer.exe");
            download(cdn+"RobloxStudioInstaller.exe",installer,67108864L,0,1,0);
            if(!DeploymentFiles.isPe(installer))throw new IOException("Invalid official installer response");
            byte[] bytes;
            try(InputStream in=new FileInputStream(installer)){bytes=DeploymentFiles.readLimited(in,67108864);}
            String ascii=new String(bytes,StandardCharsets.ISO_8859_1);
            int start=ascii.indexOf("{\"ApplicationConfig.zip\"");
            if(start<0)throw new IOException("Official install layout changed; installer must be updated");
            int stop=ascii.indexOf('\0',start);
            if(stop<0 || stop-start>131072)throw new IOException("Invalid install layout");
            JSONObject layout=new JSONObject(ascii.substring(start,stop));
            long done=0;
            for(DeploymentFiles.PackageInfo p:packages) {
                check();if(p.name.equals("RobloxStudioInstaller.exe"))continue;
                if(!p.name.endsWith(".zip"))throw new IOException("Unsupported deployment package: "+p.name);
                if(!layout.has(p.name))throw new IOException("Missing destination for "+p.name);
                File destination=DeploymentFiles.safeChild(stage,layout.getString(p.name));
                File archive=new File(cache,p.name);
                download(cdn+p.name,archive,p.packed,done,packed,96);
                if(!DeploymentFiles.verify(archive,p))throw new IOException("Size/checksum mismatch: "+p.name);
                progress.update("Extracting "+p.name,2+(int)((done+p.packed)*96/Math.max(1,packed)));
                DeploymentFiles.extract(archive,destination,p.unpacked,this::check);
                done+=p.packed;
                if(!archive.delete())throw new IOException("Cannot clean download cache");
            }
            DeploymentFiles.write(new File(stage,"AppSettings.xml"),"<?xml version=\"1.0\"?><Settings><ContentFolder>content</ContentFolder><BaseUrl>https://www.roblox.com</BaseUrl></Settings>");
            if(!DeploymentFiles.isPe(new File(stage,"RobloxStudioBeta.exe")))throw new IOException("Application executable missing or invalid");
            DeploymentFiles.write(new File(stage,".bridge-complete"),version+"\n");
            check();if(!stage.renameTo(target))throw new IOException("Cannot finalize Studio installation");
            markCurrent(base,version);progress.update("Downloaded and installed; launch is still experimental",100);
            return new File(target,"RobloxStudioBeta.exe");
        } finally {DeploymentFiles.deleteTree(cache);DeploymentFiles.deleteTree(stage);}
    }
    private void markCurrent(File base,String version) throws IOException {
        File temp=new File(base,"current-version.new");DeploymentFiles.write(temp,version+"\n");
        java.nio.file.Files.move(temp.toPath(),new File(base,"current-version.txt").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    private HttpURLConnection open(String url) throws IOException {
        for(int redirects=0;redirects<6;redirects++) {
            check();URL u=new URL(url);String host=u.getHost().toLowerCase(Locale.ROOT);
            if(!u.getProtocol().equals("https") || !(host.equals("clientsettingscdn.roblox.com") || host.equals("rbxcdn.com") || host.endsWith(".rbxcdn.com")) || u.getUserInfo()!=null || (u.getPort()!=-1 && u.getPort()!=443))throw new IOException("Non-official download host rejected");
            HttpURLConnection c=(HttpURLConnection)u.openConnection();active=c;
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(20000);c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent","StudioBridge-Experimental/0.1");
            int status=c.getResponseCode();
            if(status==200)return c;
            String location=c.getHeaderField("Location");c.disconnect();
            if(status>=300 && status<400 && location!=null){url=new URL(u,location).toString();continue;}
            throw new IOException("Roblox download returned HTTP "+status);
        }
        throw new IOException("Too many download redirects");
    }
    private String text(String url) throws IOException {
        HttpURLConnection c=open(url);
        try(InputStream in=c.getInputStream()){return new String(DeploymentFiles.readLimited(in,1048576),StandardCharsets.UTF_8);}
        finally{c.disconnect();active=null;}
    }
    private void download(String url,File dst,long max,long done,long total,int weight) throws IOException {
        HttpURLConnection c=open(url);long copied=0,last=0;
        try(InputStream in=new BufferedInputStream(c.getInputStream());OutputStream out=new BufferedOutputStream(new FileOutputStream(dst))) {
            byte[] b=new byte[131072];int n;
            while((n=in.read(b))!=-1) {
                check();copied+=n;if(copied>max)throw new IOException("Download exceeds expected size");out.write(b,0,n);
                long now=System.currentTimeMillis();if(now-last>200){last=now;progress.update("Downloading "+dst.getName(),Math.min(98,2+(int)((done+copied)*weight/Math.max(1,total))));}
            }
        } finally {c.disconnect();active=null;}
    }
}
