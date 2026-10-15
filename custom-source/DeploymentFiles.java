/* Studio Bridge additions, 2026. Distributed under LGPL-2.1-or-later. */
package com.winlator.studiobridge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Platform-independent deployment validation. Never executes downloaded code. */
public final class DeploymentFiles {
    public interface CancelCheck { void check() throws IOException; }
    public static final class PackageInfo {
        public final String name, md5;
        public final long packed, unpacked;
        PackageInfo(String n, String m, long p, long u) { name=n; md5=m; packed=p; unpacked=u; }
    }
    public static List<PackageInfo> parseManifest(String text) throws IOException {
        String[] lines=text.trim().replace("\r", "").split("\n");
        if(lines.length<5 || !lines[0].equals("v0") || (lines.length-1)%4!=0)
            throw new IOException("Unsupported deployment manifest");
        List<PackageInfo> out=new ArrayList<>(); Set<String> names=new HashSet<>();
        long total=0;
        for(int i=1;i<lines.length;i+=4) {
            if(!lines[i].matches("[A-Za-z0-9_.-]+") || !names.add(lines[i]) ||
                    !lines[i+1].matches("[a-fA-F0-9]{32}")) throw new IOException("Invalid package identity");
            try {
                long packed=Long.parseLong(lines[i+2]),unpacked=Long.parseLong(lines[i+3]);
                if(packed<=0 || unpacked<0 || packed>2_000_000_000L || unpacked>4_000_000_000L)
                    throw new IOException("Invalid package size");
                total+=unpacked;
                if(total>8_000_000_000L) throw new IOException("Deployment exceeds safety limit");
                out.add(new PackageInfo(lines[i],lines[i+1].toLowerCase(Locale.ROOT),packed,unpacked));
            } catch(NumberFormatException e) { throw new IOException("Invalid manifest number",e); }
        }
        return out;
    }
    public static File safeChild(File root,String relative) throws IOException {
        String path=relative.replace('\\','/');
        if(path.startsWith("/") || path.contains(":")) throw new IOException("Absolute path rejected");
        for(String part:path.split("/")) if(part.equals("..")) throw new IOException("Parent path rejected");
        File base=root.getCanonicalFile(),child=new File(base,path).getCanonicalFile();
        if(!child.equals(base) && !child.getPath().startsWith(base.getPath()+File.separator))
            throw new IOException("Path escapes installation directory");
        return child;
    }
    public static boolean verify(File file,PackageInfo info) throws IOException {
        if(!file.isFile() || file.length()!=info.packed) return false;
        try {
            MessageDigest digest=MessageDigest.getInstance("MD5");
            try(InputStream in=new BufferedInputStream(new FileInputStream(file))) {
                byte[] b=new byte[131072]; int n;
                while((n=in.read(b))!=-1) digest.update(b,0,n);
            }
            StringBuilder hex=new StringBuilder();
            for(byte b:digest.digest()) hex.append(String.format(Locale.ROOT,"%02x",b&255));
            return hex.toString().equals(info.md5);
        } catch(java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }
    public static void extract(File archive,File root,long maxBytes,CancelCheck cancel) throws IOException {
        mkdir(root); long written=0; int entries=0;
        try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry; byte[] b=new byte[131072];
            while((entry=zip.getNextEntry())!=null) {
                cancel.check(); if(++entries>200000) throw new IOException("Too many archive entries");
                String entryName=entry.getName().replace('\\','/');
                // Windows ZIP directory markers may begin with a slash. Normalize
                // directories only, reject payloads, then enforce canonical containment.
                if(entry.isDirectory() || entryName.endsWith("/")) {
                    if(zip.read()!=-1) throw new IOException("Directory entry contains file data");
                    while(entryName.startsWith("/"))entryName=entryName.substring(1);
                    mkdir(safeChild(root,entryName));zip.closeEntry();continue;
                }
                File dst=safeChild(root,entryName);
                if(dst.isDirectory()) throw new IOException("File/directory conflict");
                {
                    mkdir(dst.getParentFile());
                    if(dst.isDirectory()) throw new IOException("File/directory conflict");
                    try(OutputStream out=new BufferedOutputStream(new FileOutputStream(dst))) {
                        int n;
                        while((n=zip.read(b))!=-1) {
                            cancel.check(); written+=n;
                            if(written>maxBytes) throw new IOException("Archive exceeds declared extraction size");
                            out.write(b,0,n);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
        if(entries==0) throw new IOException("Empty or invalid ZIP package");
    }
    public static void mkdir(File dir) throws IOException {
        if(dir==null || (!dir.isDirectory() && !dir.mkdirs())) throw new IOException("Cannot create directory: "+dir);
    }
    public static void write(File file,String text) throws IOException {
        mkdir(file.getParentFile());
        try(OutputStream out=new FileOutputStream(file)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
    }
    public static String read(File f) throws IOException {
        try(InputStream in=new FileInputStream(f)) {return new String(readLimited(in,1048576),StandardCharsets.UTF_8);}
    }
    public static byte[] readLimited(InputStream in,int max) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[8192];int n;
        while((n=in.read(b))!=-1) { if(out.size()+n>max) throw new IOException("Response too large");out.write(b,0,n); }
        return out.toByteArray();
    }
    public static boolean isPe(File f) throws IOException {
        if(!f.isFile() || f.length()<64) return false;
        try(RandomAccessFile in=new RandomAccessFile(f,"r")) {
            if(in.readUnsignedByte()!='M' || in.readUnsignedByte()!='Z') return false;
            in.seek(0x3c);long offset=Integer.toUnsignedLong(Integer.reverseBytes(in.readInt()));
            if(offset>f.length()-4) return false;
            in.seek(offset);return in.readInt()==0x50450000;
        }
    }
    public static void deleteTree(File file) {
        if(file.isDirectory()) {File[] files=file.listFiles();if(files!=null) for(File f:files) deleteTree(f);}
        file.delete();
    }
    private DeploymentFiles() {}
}
