package local.jdcapture;

/** Serialized operation identity, shared with heartbeat/recovery callbacks. */
final class CaptureLifecycle {
    enum State { STARTING, RUNNING, STOPPING, RECOVERY_REQUIRED, STOPPED }
    private long generation;
    private int retries;
    private State state=State.STOPPED;
    private final java.util.function.LongSupplier clock;
    private long recoveryDeadline;
    CaptureLifecycle(){this(System::nanoTime);}
    CaptureLifecycle(java.util.function.LongSupplier clock){this.clock=clock;}
    synchronized long begin(State value) { generation++; retries=0; recoveryDeadline=0; state=value; return generation; }
    synchronized long generation() { return generation; }
    synchronized State state() { return state; }
    synchronized boolean matches(long value) { return generation==value; }
    synchronized void set(State value) { state=value; }
    synchronized int nextRecoveryDelay(long value) {
        if(!matches(value)||retries>=3)return -1;
        state=State.RECOVERY_REQUIRED;
        long now=clock.getAsLong();
        if(recoveryDeadline==0)recoveryDeadline=now+java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        int delay=new int[]{2,5,10}[retries];
        if(now+java.util.concurrent.TimeUnit.SECONDS.toNanos(delay)>recoveryDeadline)return -1;
        retries++;return delay;
    }
}
