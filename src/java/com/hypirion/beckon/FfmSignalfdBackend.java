package com.hypirion.beckon;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import clojure.lang.ISeq;
import clojure.lang.PersistentHashSet;
import clojure.lang.Seqable;

/**
 * EXPERIMENTAL {@link SignalBackend} built entirely on the Foreign Function and
 * Memory API (JDK 22+), using Linux {@code signalfd(2)} instead of
 * {@code sun.misc.Signal}. It is <strong>opt-in only</strong>
 * ({@code -Dbeckon.signal.backend=ffm}) and never the default.
 *
 * <p><strong>Known limitation.</strong> {@code signalfd} only receives a signal
 * if that signal is blocked in the thread it would otherwise be delivered to.
 * The JVM starts many threads before beckon loads, and we cannot retroactively
 * change their signal masks, so a process-directed signal from <em>outside</em>
 * (e.g. {@code kill -HUP}) will usually be taken by some other JVM thread and
 * run its default action rather than reaching this backend. This backend
 * therefore reliably handles only beckon's own {@link #raise(String)} (directed
 * at the dispatcher thread via {@code pthread_kill}); it is a demonstration of
 * the modern interop, not a production replacement for the sun.misc backend.
 * That limitation is the reason sun.misc remains beckon's default.
 *
 * <p>Linux only. Constructing it elsewhere throws
 * {@link UnsupportedOperationException}.
 */
public final class FfmSignalfdBackend implements SignalBackend {

    // --- Linux constants (x86_64 / aarch64) ---------------------------------
    private static final int SIG_BLOCK   = 0;
    private static final int SFD_CLOEXEC = 0x80000;
    private static final int SIGSET_SIZE = 128;          // glibc sigset_t
    private static final int SIGINFO_SIZE = 128;         // struct signalfd_siginfo

    /** Catchable signals beckon supports, by POSIX short name. */
    private static final Map<String, Integer> SIGNOS = new LinkedHashMap<>();
    static {
        SIGNOS.put("HUP", 1);   SIGNOS.put("INT", 2);   SIGNOS.put("QUIT", 3);
        SIGNOS.put("USR1", 10); SIGNOS.put("USR2", 12); SIGNOS.put("TERM", 15);
        SIGNOS.put("CHLD", 17); SIGNOS.put("CONT", 18); SIGNOS.put("TSTP", 20);
        SIGNOS.put("WINCH", 28);
    }

    // --- native handles ------------------------------------------------------
    private final MethodHandle signalfd;     // int signalfd(int, sigset_t*, int)
    private final MethodHandle read;         // ssize_t read(int, void*, size_t)
    private final MethodHandle sigemptyset;  // int sigemptyset(sigset_t*)
    private final MethodHandle sigaddset;    // int sigaddset(sigset_t*, int)
    private final MethodHandle pthreadSigmask; // int(int, sigset_t*, sigset_t*)
    private final MethodHandle pthreadSelf;  // pthread_t pthread_self(void)
    private final MethodHandle pthreadKill;  // int pthread_kill(pthread_t, int)

    private final Arena arena = Arena.ofShared();
    private final Map<Integer, Seqable> registry = new ConcurrentHashMap<>();
    private final CountDownLatch ready = new CountDownLatch(1);

    private volatile int fd = -1;
    private volatile long dispatcherPthread = 0;
    private volatile boolean running = true;

    // Serializes raise() and lets it wait for the dispatcher to fold the read it
    // triggered, so signals never bleed from one raise! into a later one.
    private final Object raiseLock = new Object();
    private volatile CountDownLatch raiseDone;

    public FfmSignalfdBackend() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            throw new UnsupportedOperationException(
                "beckon FFM signal backend requires Linux (signalfd); this is "
                + System.getProperty("os.name"));
        }
        Linker linker = Linker.nativeLinker();
        SymbolLookup libc = linker.defaultLookup();
        signalfd = linker.downcallHandle(libc.find("signalfd").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
        read = linker.downcallHandle(libc.find("read").orElseThrow(),
            FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
        sigemptyset = linker.downcallHandle(libc.find("sigemptyset").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
        sigaddset = linker.downcallHandle(libc.find("sigaddset").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        pthreadSigmask = linker.downcallHandle(libc.find("pthread_sigmask").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        pthreadSelf = linker.downcallHandle(libc.find("pthread_self").orElseThrow(),
            FunctionDescriptor.of(JAVA_LONG));
        pthreadKill = linker.downcallHandle(libc.find("pthread_kill").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT));

        Thread t = new Thread(this::dispatch, "beckon-ffm-dispatch");
        t.setDaemon(true);
        t.start();
        try {
            ready.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Build a native sigset_t containing the given signal numbers. */
    private MemorySegment sigset(Iterable<Integer> signos) throws Throwable {
        MemorySegment set = arena.allocate(SIGSET_SIZE);
        int r = (int) sigemptyset.invokeExact(set);
        for (int signo : signos) {
            r = (int) sigaddset.invokeExact(set, signo);
        }
        return set;
    }

    /** Dispatcher thread: block all supported signals here, create the fd, loop. */
    private void dispatch() {
        try {
            dispatcherPthread = (long) pthreadSelf.invokeExact();
            // Block every supported signal in THIS thread, so a pthread_kill
            // directed here stays pending and is readable via the signalfd.
            MemorySegment blockAll = sigset(SIGNOS.values());
            int r = (int) pthreadSigmask.invokeExact(SIG_BLOCK, blockAll, MemorySegment.NULL);
            // Start with an empty signalfd mask; register() adds to it.
            MemorySegment empty = sigset(java.util.Collections.emptyList());
            fd = (int) signalfd.invokeExact(-1, empty, SFD_CLOEXEC);
            if (fd < 0) {
                throw new IllegalStateException("signalfd() failed");
            }
        } catch (Throwable e) {
            running = false;
            ready.countDown();
            throw new RuntimeException("FFM signal backend init failed", e);
        }
        ready.countDown();

        MemorySegment buf = arena.allocate(SIGINFO_SIZE);
        while (running) {
            long n;
            try {
                n = (long) read.invokeExact(fd, buf, (long) SIGINFO_SIZE);
            } catch (Throwable e) {
                if (!running) break;
                continue;
            }
            if (n < SIGINFO_SIZE) {
                if (!running) break;
                continue; // EINTR or short read; retry
            }
            int signo = buf.get(JAVA_INT, 0); // ssi_signo is the first field
            fold(registry.get(signo));
            CountDownLatch done = raiseDone;
            if (done != null) done.countDown();
        }
    }

    /** Run each Runnable in order, stopping on the first Exception (Errors propagate). */
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

    /** Update the signalfd mask to exactly the currently-registered signals. */
    private synchronized void rebuildMask() {
        try {
            MemorySegment set = sigset(registry.keySet());
            int r = (int) signalfd.invokeExact(fd, set, SFD_CLOEXEC);
        } catch (Throwable e) {
            throw new RuntimeException("signalfd mask update failed", e);
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
        registry.put(signo(signame), runnables);
        rebuildMask();
    }

    @Override
    public synchronized void reset(String signame) {
        registry.remove(signo(signame));
        rebuildMask();
    }

    @Override
    public synchronized void resetAll() {
        registry.clear();
        rebuildMask();
    }

    @Override
    public Seqable currentRunnables(String signame) {
        Seqable s = registry.get(signo(signame));
        return s != null ? s : PersistentHashSet.EMPTY;
    }

    @Override
    public void raise(String signame) {
        int signo = signo(signame);
        // Serialize raises and wait for the dispatcher to fold the resulting
        // read, so a raise's handlers are observably finished before raise()
        // returns and signals cannot bleed into a later caller's handler set.
        synchronized (raiseLock) {
            CountDownLatch done = new CountDownLatch(1);
            raiseDone = done;
            try {
                // Direct the signal at the dispatcher thread (where it is
                // blocked), so it becomes pending there and is read from the fd.
                int r = (int) pthreadKill.invokeExact(dispatcherPthread, signo);
            } catch (Throwable e) {
                raiseDone = null;
                throw new RuntimeException("pthread_kill failed for " + signame, e);
            }
            try {
                done.await(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            raiseDone = null;
        }
    }
}
