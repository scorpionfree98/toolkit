package local.jdcapture.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

public class ResponseBodyTest {
    private List<String[]> headers(String value) {return Collections.singletonList(new String[]{"Content-Encoding",value});}
    private byte[] gzip(byte[] input) throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();try(GZIPOutputStream zip=new GZIPOutputStream(out)){zip.write(input);}return out.toByteArray();}
    @Test public void brotliResponseBecomesReadableWithoutChangingOriginalBytes() throws Exception {
        byte[] raw=Base64.getDecoder().decode("GyoA+EVveL37HZ/vZUkpigtM5MApkATb+a1JN/q4BA5Oa8sd9zSjj8+BYIxaCQ==");
        byte[] saved=raw.clone();
        assertEquals("capture decoding test: 中文 gzip + brotli",new String(ResponseBody.decode(raw,headers("br")),StandardCharsets.UTF_8));
        assertArrayEquals(saved,raw);
    }
    @Test public void stackedContentEncodingsAreDecodedInReverseOrder() throws Exception {
        byte[] raw=Base64.getDecoder().decode("GyoA+EVveL37HZ/vZUkpigtM5MApkATb+a1JN/q4BA5Oa8sd9zSjj8+BYIxaCQ==");
        assertEquals("capture decoding test: 中文 gzip + brotli",new String(ResponseBody.decode(gzip(raw),headers("br, gzip")),StandardCharsets.UTF_8));
    }
    @Test public void gzipAndBothDeflateVariantsDecode() throws Exception {
        byte[] raw="local capture".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(raw,ResponseBody.decode(gzip(raw),headers("gzip")));
        for(boolean nowrap:new boolean[]{false,true}) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();Deflater d=new Deflater(6,nowrap);
            try(DeflaterOutputStream zip=new DeflaterOutputStream(out,d)){zip.write(raw);}finally{d.end();}
            assertArrayEquals(raw,ResponseBody.decode(out.toByteArray(),headers("deflate")));
        }
    }
    @Test public void unknownAndCorruptEncodingsFailExplicitly() {
        assertThrows(IOException.class,()->ResponseBody.decode(new byte[]{1,2},headers("zstd")));
        assertThrows(IOException.class,()->ResponseBody.decode(new byte[]{1,2},headers("gzip")));
    }
    @Test public void expandedBodyLimitIsEnforced() throws Exception {
        byte[] compressed=gzip(new byte[HttpWire.MAX_BODY+1]);
        assertThrows(IOException.class,()->ResponseBody.decode(compressed,headers("gzip")));
    }
}
