package com.hypirion.beckon;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class BsdAbi {
    static final int KEVENT_SIZE = 32;
    static final long IDENT_OFFSET = 0;
    static final long FILTER_OFFSET = 8;
    static final long FLAGS_OFFSET = 10;
    static final long FFLAGS_OFFSET = 12;
    static final long DATA_OFFSET = 16;
    static final long UDATA_OFFSET = 24;
    private static final Set<String> SUPPORTED_64_BIT = Set.of(
        "amd64", "x86_64", "aarch64", "arm64", "riscv64", "ppc64le");
    private static final Set<String> SUPPORTED_32_BIT = Set.of(
        "x86", "i386", "i486", "i586", "i686", "arm", "arm32", "ppc");

    private BsdAbi() {}

    static boolean word64(String arch) {
        return SUPPORTED_64_BIT.contains(arch);
    }

    private static MemoryLayout keventLayout(String arch) {
        boolean is64 = word64(arch);
        List<MemoryLayout> fields = new ArrayList<>();
        fields.add((is64 ? JAVA_LONG : JAVA_INT).withName("ident"));
        fields.add(JAVA_SHORT.withName("filter"));
        fields.add(JAVA_SHORT.withName("flags"));
        fields.add(JAVA_INT.withName("fflags"));
        if (!is64) fields.add(MemoryLayout.paddingLayout(4));
        fields.add(JAVA_LONG.withName("data"));
        fields.add((is64 ? JAVA_LONG : JAVA_INT).withName("udata"));
        if (!is64) fields.add(MemoryLayout.paddingLayout(4));
        return MemoryLayout.structLayout(fields.toArray(new MemoryLayout[0]));
    }

    static void validateCurrentPlatform() {
        String os = System.getProperty("os.name", "");
        String arch = System.getProperty("os.arch", "");
        if (!SUPPORTED_64_BIT.contains(arch) && !SUPPORTED_32_BIT.contains(arch)) {
            throw new IllegalStateException("unsupported BSD kevent ABI on " + os + "/" + arch
                + "; expected a 32-bit or 64-bit BSD architecture");
        }
        MemoryLayout kevent = keventLayout(arch);
        if (kevent.byteSize() != KEVENT_SIZE)
            throw new IllegalStateException("expected kevent of " + KEVENT_SIZE
                + " bytes, got " + kevent.byteSize() + " on " + os + "/" + arch);
        if (kevent.byteOffset(PathElement.groupElement("ident")) != IDENT_OFFSET
            || kevent.byteOffset(PathElement.groupElement("filter")) != FILTER_OFFSET
            || kevent.byteOffset(PathElement.groupElement("flags")) != FLAGS_OFFSET
            || kevent.byteOffset(PathElement.groupElement("fflags")) != FFLAGS_OFFSET
            || kevent.byteOffset(PathElement.groupElement("data")) != DATA_OFFSET
            || kevent.byteOffset(PathElement.groupElement("udata")) != UDATA_OFFSET) {
            throw new IllegalStateException("invalid kevent layout on " + os + "/" + arch);
        }
    }
}
