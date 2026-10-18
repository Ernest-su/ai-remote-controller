package ernest.airc.bridge;

import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.*;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONObject;

/** Private, bounded, process-safe cache. PackageManager identities are always checked first. */
final class AppMetadataCache implements AutoCloseable {
    private final File directory;
    private final FileOutputStream lockStream;
    private final FileLock lock;
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    AppMetadataCache(String path) throws Exception {
        directory = new File(path);
        StructStat dir = Os.lstat(path);
        if (!OsConstants.S_ISDIR(dir.st_mode) || dir.st_uid != Process.myUid() || (dir.st_mode & 077) != 0)
            throw new IOException("application cache directory ownership or mode mismatch");
        FileDescriptor fd = Os.open(new File(directory,"cache.lock").getPath(), OsConstants.O_RDWR | OsConstants.O_CREAT | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK | OsConstants.O_CLOEXEC, 0600);
        try { regular(fd); } catch(Exception e) { Os.close(fd); throw e; }
        lockStream = new FileOutputStream(fd);
        try { lock = lockStream.getChannel().lock(); }
        catch(Exception e) { lockStream.close(); throw e; }
        File[] leftovers=directory.listFiles();
        if(leftovers!=null) for(File f:leftovers) if(f.getName().startsWith(".app-") && f.getName().endsWith(".tmp")) f.delete();
    }
    static String digest(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder value = new StringBuilder();
        for(byte b:hash) { value.append(Character.forDigit((b>>4)&15,16));value.append(Character.forDigit(b&15,16)); }
        return value.toString();
    }
    private static void regular(FileDescriptor fd) throws Exception {
        StructStat st = Os.fstat(fd);
        if(!OsConstants.S_ISREG(st.st_mode) || st.st_uid != Process.myUid() || (st.st_mode & 077) != 0)
            throw new IOException("application cache file ownership or mode mismatch");
    }
    private byte[] read(String name, int limit) {
        try {
            FileDescriptor fd = Os.open(new File(directory,name).getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK | OsConstants.O_CLOEXEC, 0);
            try(FileInputStream input = new FileInputStream(fd)) {
                regular(fd);
                if(Os.fstat(fd).st_size > limit) return null;
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192]; int count;
                while((count=input.read(buffer))!=-1) {
                    if(output.size()+count>limit) return null;
                    output.write(buffer,0,count);
                }
                return output.toByteArray();
            }
        } catch(Exception unavailable) { return null; }
    }
    JSONObject metadata(String id, String key) {
        try {
            byte[] bytes = read(id+".json", 16384);
            if(bytes==null) return null;
            JSONObject value = new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
            return value.getString("cacheKey").equals(key) ? value : null;
        } catch(Exception invalid) { return null; }
    }
    byte[] icon(String id, JSONObject metadata) throws Exception {
        if(metadata==null || !metadata.has("iconSha256")) return null;
        byte[] bytes = read(id+".png",128*1024);
        if(bytes==null || bytes.length<8 || bytes[0]!=(byte)137 || bytes[1]!=80 || bytes[2]!=78 || bytes[3]!=71 || !digest(bytes).equals(metadata.getString("iconSha256"))) return null;
        new File(directory,id+".png").setLastModified(System.currentTimeMillis());
        return bytes;
    }
    void put(String id, JSONObject metadata, byte[] icon) {
        try {
            if(icon!=null) {
                write(id+".png",icon);
                metadata.put("iconSha256",digest(icon));
            }
            write(id+".json",metadata.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch(Exception unavailable) {
            // Cache writes do not turn a successful PackageManager query into a failure.
        }
    }
    private void write(String name, byte[] bytes) throws Exception {
        File temporary = File.createTempFile(".app-", ".tmp", directory);
        try {
            Os.chmod(temporary.getPath(),0600);
            try(FileOutputStream output = new FileOutputStream(temporary)) { output.write(bytes); output.getFD().sync(); }
            Files.move(temporary.toPath(),new File(directory,name).toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { temporary.delete(); }
    }
    void remove(int user, String name) {
        new File(directory,user+"-"+name+".json").delete();
        new File(directory,user+"-"+name+".png").delete();
    }
    void retain(int user, Set<String> installed) {
        File[] files = directory.listFiles(); if(files==null) return;
        String prefix=user+"-";
        for(File f:files) {
            String name=f.getName();
            if(name.startsWith(prefix) && (name.endsWith(".json") || name.endsWith(".png"))) {
                String pkg=name.substring(prefix.length(),name.lastIndexOf('.'));
                if(!installed.contains(pkg)) f.delete();
            }
        }
    }
    private void trim() {
        File[] files=directory.listFiles(); if(files==null) return;
        ArrayList<File> entries=new ArrayList<>(); long bytes=0;
        for(File f:files) if(f.getName().matches("[0-9]+-[a-zA-Z0-9_.]+\\.(json|png)") && Files.isRegularFile(f.toPath(),LinkOption.NOFOLLOW_LINKS)) { entries.add(f);bytes+=f.length(); }
        entries.sort(Comparator.comparingLong(File::lastModified));
        int count=entries.size();
        for(File f:entries) {
            if(bytes<=MAX_BYTES && count<=8192) break;
            long size=f.length(); if(f.delete()) { bytes-=size;count--; }
        }
    }
    @Override public void close() throws Exception { try { trim(); } finally { try { lock.release(); } finally { lockStream.close(); } } }
}
