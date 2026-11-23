package local.jdcapture;

/** Admission precedes body allocation; every accepted request must release in finally. */
final class InFlightBudget {
    static final int MAX_REQUEST = 9 * 1024 * 1024;
    private int count;
    private long bytes;
    synchronized boolean acquire(int size) {
        if(size < 0 || size > MAX_REQUEST || count >= 2 || bytes + size > 12L*1024*1024) return false;
        count++; bytes += size; return true;
    }
    synchronized void release(int size) { count--; bytes -= size; }
}
