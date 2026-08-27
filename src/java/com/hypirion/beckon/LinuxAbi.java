package com.hypirion.beckon;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.util.Set;

final class LinuxAbi {
    static final int SIGSET_SIZE = 128;
    static final int SIGINFO_SIZE = 128;
    static final int POLLFD_SIZE = 8;
    static final long POLLFD_FD_OFFSET = 0;
    static final long POLLFD_EVENTS_OFFSET = 4;
    static final long POLLFD_FIRST_REVENTS_OFFSET = 6;
    static final long POLLFD_SECOND_REVENTS_OFFSET = POLLFD_SIZE + 6;

    private static final Set<String> SUPPORTED_ARCHES = Set.of(
        "amd64", "x86_64", "aarch64", "arm64", "riscv64", "ppc64le", "s390x",
        "x86", "i386", "i486", "i586", "i686", "arm", "arm32", "ppc");
    private static final MemoryLayout POLLFD = MemoryLayout.structLayout(
        JAVA_INT.withName("fd"), JAVA_SHORT.withName("events"),
        JAVA_SHORT.withName("revents"));
    private static final MemoryLayout SIGINFO = MemoryLayout.structLayout(
        JAVA_INT.withName("ssi_signo"), JAVA_INT.withName("ssi_errno"),
        JAVA_INT.withName("ssi_code"), JAVA_INT.withName("ssi_pid"),
        JAVA_INT.withName("ssi_uid"), JAVA_INT.withName("ssi_fd"),
        JAVA_INT.withName("ssi_tid"), JAVA_INT.withName("ssi_overrun"),
        JAVA_INT.withName("ssi_trapno"), JAVA_INT.withName("ssi_status"),
        JAVA_INT.withName("ssi_int"), MemoryLayout.paddingLayout(4),
        JAVA_LONG.withName("ssi_ptr"), MemoryLayout.paddingLayout(72));

    private LinuxAbi() {}

    static void validateCurrentPlatform() {
        String os = System.getProperty("os.name", "");
        String arch = System.getProperty("os.arch", "");
        String failure = validate(os, arch, (int) MemoryLayout.paddingLayout(SIGSET_SIZE).byteSize(),
                                  (int) SIGINFO.byteSize(), (int) POLLFD.byteSize());
        if (failure != null) throw new IllegalStateException(failure);
    }

    static String validate(String os, String arch, int sigsetSize,
                           int siginfoSize, int pollfdSize) {
        if (!SUPPORTED_ARCHES.contains(arch)) {
            return "unsupported Linux ABI on " + os + "/" + arch
                + "; expected a known Linux architecture with sigset_t of "
                + SIGSET_SIZE + " bytes";
        }
        if (sigsetSize != SIGSET_SIZE)
            return mismatch("sigset_t", SIGSET_SIZE, sigsetSize, os, arch);
        if (siginfoSize != SIGINFO_SIZE)
            return mismatch("signalfd_siginfo", SIGINFO_SIZE, siginfoSize, os, arch);
        if (pollfdSize != POLLFD_SIZE)
            return mismatch("pollfd", POLLFD_SIZE, pollfdSize, os, arch);
        if (POLLFD.byteOffset(PathElement.groupElement("fd")) != POLLFD_FD_OFFSET
            || POLLFD.byteOffset(PathElement.groupElement("events")) != POLLFD_EVENTS_OFFSET
            || POLLFD.byteOffset(PathElement.groupElement("revents")) != 6)
            return "invalid pollfd revents offset on " + os + "/" + arch;
        if (POLLFD_SECOND_REVENTS_OFFSET != 14)
            return "invalid second pollfd revents offset on " + os + "/" + arch;
        if (SIGINFO.byteOffset(PathElement.groupElement("ssi_signo")) != 0)
            return "invalid signalfd_siginfo signo offset on " + os + "/" + arch;
        return null;
    }

    private static String mismatch(String type, int expected, int actual,
                                    String os, String arch) {
        return "expected " + type + " of " + expected + " bytes, got " + actual
            + " on " + os + "/" + arch;
    }
}
