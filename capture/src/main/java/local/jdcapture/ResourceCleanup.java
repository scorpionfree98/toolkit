package local.jdcapture;

/** Attempt every resource release; preserve all failures for a retryable recovery state. */
final class ResourceCleanup {
    interface Step { void run() throws Exception; }
    static void run(Step... steps) throws Exception {
        Exception failure = null;
        for (Step step : steps) {
            try { step.run(); }
            catch (Exception error) {
                if (failure == null) failure = error;
                else if (error != failure) failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}
