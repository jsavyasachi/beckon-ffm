package com.hypirion.beckon;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import clojure.lang.ISeq;
import clojure.lang.PersistentHashSet;
import clojure.lang.Seqable;

/**
 * EXPERIMENTAL {@link SignalBackend} for macOS/BSD, built on the Foreign
 * Function and Memory API (JDK 22+) using {@code kqueue(2)} with
 * {@code EVFILT_SIGNAL} instead of {@code sun.misc.Signal}. Opt-in only
 * ({@code -Dbeckon.signal.backend=ffm}); selected automatically on macOS.
 *
 * <p>Each managed signal is set to {@code SIG_IGN} (so its default action does
 * not fire) and registered on a kqueue; a dispatcher thread blocks in
 * {@code kevent(2)} and runs the handlers when a signal is delivered. Because
 * {@code SIG_IGN} is a process-wide disposition (not a per-thread block), this
 * backend - unlike the Linux signalfd one - also observes signals sent from
 * outside the process (e.g. {@code kill -HUP}). It remains experimental and is
 * not bundled in the released jar.
 *
 * <p>macOS/BSD only. Constructing it elsewhere throws
 * {@link UnsupportedOperationException}.
 */
public final class FfmKqueueBackend implements SignalBackend {

    // macOS/BSD signal numbers (these DIFFER from Linux: USR1/USR2 especially).
    private static final Map<String, Integer> SIGNOS = new LinkedHashMap<>();
    static {
        SIGNOS.put("HUP", 1);   SIGNOS.put("INT", 2);   SIGNOS.put("QUIT", 3);
        SIGNOS.put("USR1", 30); SIGNOS.put("USR2", 31); SIGNOS.put("TERM", 15);
        SIGNOS.put("CHLD", 20); SIGNOS.put("CONT", 19); SIGNOS.put("TSTP", 18);
        SIGNOS.put("WINCH", 28);
    }

    // kqueue / struct kevent constants (macOS, 64-bit).
    private static final short EVFILT_SIGNAL = -6;
    private static final short EV_ADD    = 0x0001;
    private static final short EV_DELETE = 0x0002;
    private static final long  SIG_DFL = 0L;
    private static final long  SIG_IGN = 1L;
    private static final int   KEVENT_SIZE = 32; // ident8 filter2 flags2 fflags4 data8 udata8

    private final MethodHandle kqueueFn; // int kqueue(void)
    private final MethodHandle kevent;   // int kevent(int, kevent*, int, kevent*, int, timespec*)
    private final MethodHandle signalFn; // sig_t signal(int, sig_t)
    private final MethodHandle kill;     // int kill(pid_t, int)
    private final MethodHandle getpid;   // pid_t getpid(void)

    private final Arena arena = Arena.ofShared();
    private final Map<Integer, Seqable> registry = new ConcurrentHashMap<>();
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile int kq = -1;
    private volatile boolean running = true;

    private final Object raiseLock = new Object();
    private volatile CountDownLatch raiseDone;

    public FfmKqueueBackend() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!(os.contains("mac") || os.contains("darwin") || os.contains("bsd"))) {
            throw new UnsupportedOperationException(
                "beckon FFM kqueue backend requires macOS/BSD; this is "
                + System.getProperty("os.name"));
        }
        Linker linker = Linker.nativeLinker();
        SymbolLookup libc = linker.defaultLookup();
        kqueueFn = linker.downcallHandle(libc.find("kqueue").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT));
        kevent = linker.downcallHandle(libc.find("kevent").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        signalFn = linker.downcallHandle(libc.find("signal").orElseThrow(),
            FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS));
        kill = linker.downcallHandle(libc.find("kill").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
        getpid = linker.downcallHandle(libc.find("getpid").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT));

        Thread t = new Thread(this::dispatch, "beckon-ffm-kqueue");
        t.setDaemon(true);
        t.start();
        try {
            ready.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void dispatch() {
        try {
            kq = (int) kqueueFn.invokeExact();
            if (kq < 0) throw new IllegalStateException("kqueue() failed");
        } catch (Throwable e) {
            running = false;
            ready.countDown();
            throw new RuntimeException("FFM kqueue backend init failed", e);
        }
        ready.countDown();

        MemorySegment evlist = arena.allocate((long) KEVENT_SIZE * 16);
        while (running) {
            int n;
            try {
                n = (int) kevent.invokeExact(kq, MemorySegment.NULL, 0,
                                             evlist, 16, MemorySegment.NULL);
            } catch (Throwable e) {
                if (!running) break;
                continue;
            }
            if (n <= 0) {
                if (!running) break;
                continue;
            }
            for (int i = 0; i < n; i++) {
                long ident = evlist.get(JAVA_LONG, (long) i * KEVENT_SIZE);
                fold(registry.get((int) ident));
            }
            CountDownLatch done = raiseDone;
            if (done != null) done.countDown();
        }
    }

    /** Add or remove an EVFILT_SIGNAL registration for signo. */
    private void changeKevent(int signo, short flags) {
        try {
            MemorySegment kev = arena.allocate(KEVENT_SIZE);
            kev.set(JAVA_LONG, 0, signo);          // ident
            kev.set(JAVA_SHORT, 8, EVFILT_SIGNAL); // filter
            kev.set(JAVA_SHORT, 10, flags);        // flags
            kev.set(JAVA_INT, 12, 0);              // fflags
            kev.set(JAVA_LONG, 16, 0L);            // data
            kev.set(JAVA_LONG, 24, 0L);            // udata
            int r = (int) kevent.invokeExact(kq, kev, 1,
                                             MemorySegment.NULL, 0, MemorySegment.NULL);
        } catch (Throwable e) {
            throw new RuntimeException("kevent() change failed", e);
        }
    }

    /** Set the process-wide disposition of signo (SIG_IGN suppresses default). */
    private void setDisposition(int signo, long handler) {
        try {
            MemorySegment old = (MemorySegment)
                signalFn.invokeExact(signo, MemorySegment.ofAddress(handler));
        } catch (Throwable e) {
            throw new RuntimeException("signal() failed", e);
        }
    }

    private static int signo(String signame) {
        Integer n = SIGNOS.get(signame);
        if (n == null) {
            throw new IllegalArgumentException(
                "Unsupported signal for FFM backend: " + signame);
        }
        return n;
    }

    @Override
    public synchronized void register(String signame, Seqable runnables) {
        int signo = signo(signame);
        registry.put(signo, runnables);
        setDisposition(signo, SIG_IGN); // suppress default action; kqueue still sees it
        changeKevent(signo, EV_ADD);
    }

    @Override
    public synchronized void reset(String signame) {
        int signo = signo(signame);
        registry.remove(signo);
        changeKevent(signo, EV_DELETE);
        setDisposition(signo, SIG_DFL);
    }

    @Override
    public synchronized void resetAll() {
        for (Integer signo : new ArrayList<>(registry.keySet())) {
            registry.remove(signo);
            changeKevent(signo, EV_DELETE);
            setDisposition(signo, SIG_DFL);
        }
    }

    @Override
    public Seqable currentRunnables(String signame) {
        Seqable s = registry.get(signo(signame));
        return s != null ? s : PersistentHashSet.EMPTY;
    }

    @Override
    public void raise(String signame) {
        int signo = signo(signame);
        // Serialize and wait for the dispatcher to fold, so raises don't bleed
        // across handler sets (same contract as the signalfd backend).
        synchronized (raiseLock) {
            CountDownLatch done = new CountDownLatch(1);
            raiseDone = done;
            try {
                int pid = (int) getpid.invokeExact();
                int r = (int) kill.invokeExact(pid, signo); // process-directed
            } catch (Throwable e) {
                raiseDone = null;
                throw new RuntimeException("kill failed for " + signame, e);
            }
            try {
                done.await(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            raiseDone = null;
        }
    }

    private static void fold(Seqable fns) {
        if (fns == null) return;
        for (ISeq s = fns.seq(); s != null; s = s.next()) {
            try {
                ((Runnable) s.first()).run();
            } catch (Exception e) {
                break;
            }
        }
    }
}
