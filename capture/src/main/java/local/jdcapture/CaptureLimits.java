package local.jdcapture;
import java.io.File;
import java.io.IOException;

/** Stop recording at the limit; never silently delete captured evidence. */
final class CaptureLimits {
    static final long MAX_BYTES = 128L * 1024 * 1024;
    static final long EVENT_BYTES = 1024L * 1024;
    static final int MAX_FILES = 2000;
    static void requireSpace(File directory, long incoming) throws IOException {
        requireSpace(directory, incoming, MAX_BYTES, MAX_FILES);
    }
    static void requireSpace(File directory, long incoming, long limit, int fileLimit) throws IOException {
        long bytes = 0; int count = 0;
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("CAPTURE_DIRECTORY_UNAVAILABLE");
        for (File file : files) if (file.isFile()) { bytes += file.length(); count++; }
        if (incoming < 0 || incoming > limit || bytes > limit - incoming || count >= fileLimit)
            throw new IOException("CAPTURE_QUOTA_REACHED");
    }
}
