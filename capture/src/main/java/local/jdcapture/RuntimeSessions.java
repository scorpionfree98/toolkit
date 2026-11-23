package local.jdcapture;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;

/** Only completed session trees inside this application's shell directory are removable. */
final class RuntimeSessions {
    static final long MAX_BYTES=64L*1024*1024, MAX_LOG_BYTES=2L*1024*1024;
    static final int MAX_SESSIONS=32;
    final File root;
    RuntimeSessions(File root) { this.root=root; }
    private void safe(File file) throws IOException {
        if(Files.isSymbolicLink(file.toPath()) || !file.getCanonicalPath().equals(file.getAbsolutePath()))
            throw new IOException("RUNTIME_SYMLINK_REJECTED");
        if(!file.equals(root) && !file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))
            throw new IOException("RUNTIME_PATH_REJECTED");
    }
    private List<File> sessions() throws IOException {
        safe(root); List<File> out=new ArrayList<>();
        if(!root.exists()) return out;
        File[] files=root.listFiles();if(files==null)throw new IOException("RUNTIME_DIRECTORY_UNAVAILABLE");
        for(File file:files) if(file.getName().matches("session-[0-9a-fA-F-]{36}")) {
            safe(file);if(!file.isDirectory())throw new IOException("INVALID_SESSION_DIRECTORY");out.add(file);
        }
        return out;
    }
    private List<File> tree(File file, int depth) throws IOException {
        safe(file);if(depth>3)throw new IOException("RUNTIME_TREE_TOO_DEEP");
        List<File> out=new ArrayList<>();
        if(file.isDirectory()) {
            File[] children=file.listFiles();if(children==null)throw new IOException("RUNTIME_DIRECTORY_UNAVAILABLE");
            if(children.length>256)throw new IOException("RUNTIME_TREE_TOO_LARGE");
            for(File child:children) out.addAll(tree(child,depth+1));
        }
        out.add(file);return out;
    }
    private String owned() throws Exception {
        File owner=new File(root,"owner.json");safe(owner);
        if(!owner.exists())return "";
        if(owner.length()>8192)throw new IOException("INVALID_OWNER_RECORD");
        String name=new JSONObject(new String(Files.readAllBytes(owner.toPath()),java.nio.charset.StandardCharsets.UTF_8)).getString("session");
        if(!name.matches("session-[0-9a-fA-F-]{36}"))throw new IOException("INVALID_OWNER_RECORD");
        return name;
    }
    JSONObject inventory(String active) throws Exception {
        List<File> files=sessions();String owner=owned();long bytes=0;int removable=0;
        for(File file:files) {
            for(File item:tree(file,0))if(item.isFile())bytes+=item.length();
            if(!file.getName().equals(active)&&!file.getName().equals(owner)&&new File(file,"stopped").isFile())removable++;
        }
        return new JSONObject().put("sessions",files.size()).put("bytes",bytes).put("removable",removable)
                .put("protected",files.size()-removable).put("limitBytes",MAX_BYTES);
    }
    void requireCapacity() throws Exception {
        JSONObject report=inventory("");
        if(report.getInt("sessions")>=MAX_SESSIONS || report.getLong("bytes")>MAX_BYTES-MAX_LOG_BYTES-1024*1024)
            throw new IOException("RUNTIME_STORAGE_LIMIT_EXPORT_AND_CLEAN");
    }
    int clearFinished(String active) throws Exception {
        String owner=owned();List<File> eligible=new ArrayList<>();
        // Validate all paths before deleting any evidence.
        for(File file:sessions()) {
            tree(file,0);
            if(!file.getName().equals(active)&&!file.getName().equals(owner)&&new File(file,"stopped").isFile())eligible.add(file);
        }
        for(File file:eligible)for(File item:tree(file,0))Files.delete(item.toPath());
        return eligible.size();
    }
    void cleanSecrets(File session) throws Exception {
        safe(session); List<File> targets=new ArrayList<>();File ca=new File(session,"ca");safe(ca);
        if(ca.exists()) { File[] files=ca.listFiles();if(files==null)throw new IOException("CA_CLEANUP_FAILED");targets.addAll(Arrays.asList(files)); }
        for(String name:new String[]{"wireguard.json","vpn-client.json","config.json","fixture.key","vpn-fixture.key"})targets.add(new File(session,name));
        for(File file:targets)safe(file);
        IOException failure=null;
        for(File file:targets)try{Files.deleteIfExists(file.toPath());}catch(IOException e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        if(failure!=null)throw failure;
    }
    static void copyLog(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer=new byte[8192];long written=0;int n;
        while((n=input.read(buffer))!=-1) {
            int keep=(int)Math.min(n,Math.max(0,limit-written));
            if(keep>0){output.write(buffer,0,keep);written+=keep;output.flush();}
            // Continue draining after the cap so the child cannot deadlock on stdout.
        }
    }
}
