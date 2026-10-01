package run.endive.redline.experimental.api.internal;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Turns {@link Thread#interrupt()} on a thread running native code into a raised interrupt
 * flag, which the native code checks at function entry and on loop back-edges.
 *
 * <p>One daemon thread serves every call in the process. A call registers on entry and
 * deregisters on exit, which costs a set insert and remove instead of starting a thread. The
 * thread polls registered callers every millisecond and parks while nothing is registered.
 */
public final class InterruptWatchdog {
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    private static final Set<Watch> WATCHES = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static volatile Thread poller;

    private InterruptWatchdog() {}

    /**
     * Watches the current thread until the returned watch is closed. While it is open, an
     * interrupt of this thread runs {@code raise}, and keeps running it every poll: a nested
     * call clears the flag when it finishes, and the outer call still needs it.
     */
    public static Watch watch(Runnable raise) {
        var watch = new Watch(Thread.currentThread(), raise);
        WATCHES.add(watch);
        if (ACTIVE.getAndIncrement() == 0) {
            // A poller that saw nothing registered parks; this wakes it. If it has not
            // parked yet the permit makes its next park return at once.
            LockSupport.unpark(poller());
        }
        return watch;
    }

    private static Thread poller() {
        Thread t = poller;
        if (t == null) {
            synchronized (InterruptWatchdog.class) {
                t = poller;
                if (t == null) {
                    t = new Thread(InterruptWatchdog::poll, "redline-interrupt-watchdog");
                    t.setDaemon(true);
                    t.start();
                    poller = t;
                }
            }
        }
        return t;
    }

    private static void poll() {
        while (true) {
            if (ACTIVE.get() == 0) {
                LockSupport.park(InterruptWatchdog.class);
                continue;
            }
            for (Watch watch : WATCHES) {
                if (watch.caller.isInterrupted()) {
                    watch.raise.run();
                }
            }
            LockSupport.parkNanos(InterruptWatchdog.class, POLL_NANOS);
        }
    }

    /** One registered call. Closing it stops the watching. */
    public static final class Watch implements AutoCloseable {
        private final Thread caller;
        private final Runnable raise;

        private Watch(Thread caller, Runnable raise) {
            this.caller = caller;
            this.raise = raise;
        }

        @Override
        public void close() {
            if (WATCHES.remove(this)) {
                ACTIVE.decrementAndGet();
            }
        }
    }
}
