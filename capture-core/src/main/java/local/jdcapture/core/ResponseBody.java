package local.jdcapture.core;

import org.brotli.dec.BrotliInputStream;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Decode a bounded copy for inspection. The original network bytes remain untouched. */
public final class ResponseBody {
    public static byte[] decode(byte[] raw, List<String[]> headers) throws IOException {
        List<String> encodings=new ArrayList<>();
        for(String[] h:headers) if(h[0].equalsIgnoreCase("Content-Encoding"))
            for(String part:h[1].split(",")) {
                String encoding=part.trim().toLowerCase(Locale.ROOT);
                if(!encoding.isEmpty() && !encoding.equals("identity")) encodings.add(encoding);
            }
        if(encodings.size()>4) throw new IOException("TOO_MANY_CONTENT_ENCODINGS");
        byte[] decoded=raw;
        for(int i=encodings.size()-1;i>=0;i--) {
            String encoding=encodings.get(i);
            InputStream source=new ByteArrayInputStream(decoded);
            if(encoding.equals("gzip") || encoding.equals("x-gzip")) {
                try(InputStream in=new GZIPInputStream(source)){decoded=HttpWire.bounded(in);}
            } else if(encoding.equals("br")) {
                try(InputStream in=new BrotliInputStream(source)){decoded=HttpWire.bounded(in);}
            } else if(encoding.equals("deflate")) {
                boolean zlib=decoded.length>=2 && (decoded[0]&15)==8 && (((decoded[0]&255)<<8)+(decoded[1]&255))%31==0;
                Inflater inflater=new Inflater(!zlib);
                try(InputStream in=new InflaterInputStream(source,inflater)){decoded=HttpWire.bounded(in);}
                finally{inflater.end();}
            } else throw new IOException("UNSUPPORTED_CONTENT_ENCODING:"+encoding.replaceAll("[^a-z0-9_-]","?"));
        }
        return decoded;
    }
}
