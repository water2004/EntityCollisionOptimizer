package com.wiyuka.acceleratedrecoiling.natives;

import com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling;
import com.wiyuka.acceleratedrecoiling.config.FoldConfig;
import com.wiyuka.acceleratedrecoiling.ffm.FFM;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The single accelerated backend. When collision acceleration is disabled this
 * class is not called and Minecraft executes its original collision path.
 */
public final class FFMBackend {
    private static Linker linker;
    private static Arena nativeArena;
    private static MethodHandle pushMethodHandle;
    private static MethodHandle createCtxMethodHandle;
    private static MethodHandle destroyCtxMethodHandle;
    private static MethodHandle createCfgMethodHandle;
    private static MethodHandle updateCfgMethodHandle;
    private static MethodHandle destroyCfgMethodHandle;
    private static volatile boolean initialized;
    private static int generation;

    private static final Set<ThreadState> ALL_THREAD_STATES = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<ThreadState> THREAD_STATE = new ThreadLocal<>();

    private FFMBackend() {
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }

        try {
            loadNativeLibrary();
            generation++;
            initialized = true;
            AcceleratedRecoiling.LOGGER.info("FFM collision backend initialized");
        } catch (Throwable failure) {
            resetHandles();
            throw new IllegalStateException(
                    "FFM collision backend failed to initialize; no fallback backend is configured",
                    failure
            );
        }
    }

    public static void applyConfig() {
        if (!initialized) {
            return;
        }

        for (ThreadState state : ALL_THREAD_STATES) {
            try {
                updateCfgMethodHandle.invokeExact(
                        state.configPtr,
                        FoldConfig.maxCollision,
                        FoldConfig.gridSize,
                        FoldConfig.densityWindow,
                        FoldConfig.maxThreads
                );
            } catch (Throwable failure) {
                throw new IllegalStateException("Failed to update the FFM collision configuration", failure);
            }
        }
    }

    public static synchronized void destroy() {
        if (!initialized) {
            return;
        }

        initialized = false;
        for (ThreadState state : ALL_THREAD_STATES) {
            state.destroy();
        }
        ALL_THREAD_STATES.clear();
        THREAD_STATE.remove();
        resetHandles();
    }

    public static PushResult push(double[] aabbs, int entityCount) {
        if (!initialized) {
            initialize();
        }

        ThreadState state = threadState();
        try (Arena tempArena = Arena.ofConfined()) {
            int resultCapacity = Math.multiplyExact(
                    entityCount,
                    FoldConfig.effectiveMaxCollision(entityCount)
            );
            MemorySegment aabbMemory = FFM.allocateArray(tempArena, aabbs);
            PushResult result = state.allocateOutput(resultCapacity);

            final int collisionCount;
            try {
                collisionCount = (int) pushMethodHandle.invokeExact(
                        aabbMemory,
                        result.segmentA,
                        result.segmentB,
                        entityCount,
                        result.segmentDensity,
                        state.context,
                        state.configPtr
                );
            } catch (Throwable failure) {
                throw new IllegalStateException("FFM collision call failed", failure);
            }

            if (collisionCount < 0) {
                throw new IllegalStateException("FFM collision call returned invalid result size " + collisionCount);
            }
            result.size = collisionCount;
            return result;
        }
    }

    private static ThreadState threadState() {
        ThreadState state = THREAD_STATE.get();
        if (state == null || state.generation != generation) {
            state = new ThreadState(generation);
            THREAD_STATE.set(state);
            ALL_THREAD_STATES.add(state);
        }
        return state;
    }

    private static void loadNativeLibrary() throws IOException {
        String libraryName = System.mapLibraryName("AcceleratedRecoiling");
        String resourcePath = platformNativePath() + libraryName;
        File extractedLibrary;

        try (InputStream libraryStream = AcceleratedRecoiling.class.getResourceAsStream(resourcePath)) {
            if (libraryStream == null) {
                throw new FileNotFoundException("Cannot find native library resource " + resourcePath);
            }
            extractedLibrary = File.createTempFile(
                    UUID.randomUUID() + "_acceleratedRecoiling_",
                    "_" + libraryName
            );
            extractedLibrary.deleteOnExit();
            try (OutputStream output = new FileOutputStream(extractedLibrary)) {
                libraryStream.transferTo(output);
            }
        }

        AcceleratedRecoiling.LOGGER.info(
                "Extracted FFM native library {} to {}",
                resourcePath,
                extractedLibrary.getAbsolutePath()
        );

        linker = Linker.nativeLinker();
        nativeArena = Arena.global();
        SymbolLookup library = SymbolLookup.libraryLookup(extractedLibrary.getAbsolutePath(), nativeArena);
        pushMethodHandle = linker.downcallHandle(
                library.find("push").orElseThrow(() -> missingSymbol("push")),
                FunctionDescriptor.of(
                        JAVA_INT,
                        ADDRESS,
                        ADDRESS,
                        ADDRESS,
                        JAVA_INT,
                        ADDRESS,
                        ADDRESS,
                        ADDRESS
                )
        );
        createCtxMethodHandle = linker.downcallHandle(
                library.find("createCtx").orElseThrow(() -> missingSymbol("createCtx")),
                FunctionDescriptor.of(ADDRESS)
        );
        destroyCtxMethodHandle = linker.downcallHandle(
                library.find("destroyCtx").orElseThrow(() -> missingSymbol("destroyCtx")),
                FunctionDescriptor.ofVoid(ADDRESS)
        );
        createCfgMethodHandle = linker.downcallHandle(
                library.find("createCfg").orElseThrow(() -> missingSymbol("createCfg")),
                FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)
        );
        updateCfgMethodHandle = linker.downcallHandle(
                library.find("updateCfg").orElseThrow(() -> missingSymbol("updateCfg")),
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)
        );
        destroyCfgMethodHandle = linker.downcallHandle(
                library.find("destroyCfg").orElseThrow(() -> missingSymbol("destroyCfg")),
                FunctionDescriptor.ofVoid(ADDRESS)
        );
    }

    private static IllegalStateException missingSymbol(String symbol) {
        return new IllegalStateException("Native library is missing required FFM symbol '" + symbol + "'");
    }

    private static String platformNativePath() {
        String osName = System.getProperty("os.name").toLowerCase();
        String osArch = System.getProperty("os.arch").toLowerCase();
        String os;
        if (osName.contains("win")) {
            os = "windows";
        } else if (osName.contains("mac")) {
            os = "macos";
        } else if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) {
            os = "linux";
        } else {
            throw new UnsupportedOperationException("Unsupported OS for the FFM backend: " + osName);
        }

        String architecture;
        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            architecture = "x64";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            architecture = "arm64";
        } else {
            throw new UnsupportedOperationException("Unsupported architecture for the FFM backend: " + osArch);
        }
        return "/natives/" + os + "-" + architecture + "/";
    }

    private static void resetHandles() {
        linker = null;
        nativeArena = null;
        pushMethodHandle = null;
        createCtxMethodHandle = null;
        destroyCtxMethodHandle = null;
        createCfgMethodHandle = null;
        updateCfgMethodHandle = null;
        destroyCfgMethodHandle = null;
    }

    public static final class PushResult {
        private MemorySegment segmentA;
        private MemorySegment segmentB;
        private MemorySegment segmentDensity;
        private int size;

        private PushResult() {
        }

        private void update(MemorySegment a, MemorySegment b, MemorySegment density) {
            segmentA = a;
            segmentB = b;
            segmentDensity = density;
        }

        public int size() {
            return size;
        }

        public int getA(int index) {
            return segmentA.get(JAVA_INT, (long) index * Integer.BYTES);
        }

        public int getB(int index) {
            return segmentB.get(JAVA_INT, (long) index * Integer.BYTES);
        }

        public float getDensity(int index) {
            return segmentDensity.get(JAVA_FLOAT, (long) index * Float.BYTES);
        }
    }

    private static final class ThreadState {
        private final int generation;
        private final MemorySegment context;
        private final MemorySegment configPtr;
        private final PushResult result = new PushResult();
        private Arena bufferArena;
        private MemorySegment bufferA;
        private MemorySegment bufferB;
        private MemorySegment densityBuffer;
        private long byteCapacity;

        private ThreadState(int generation) {
            this.generation = generation;
            try {
                context = (MemorySegment) createCtxMethodHandle.invokeExact();
                configPtr = (MemorySegment) createCfgMethodHandle.invokeExact(
                        FoldConfig.maxCollision,
                        FoldConfig.gridSize,
                        FoldConfig.densityWindow,
                        FoldConfig.maxThreads
                );
            } catch (Throwable failure) {
                throw new IllegalStateException("Failed to create FFM collision state", failure);
            }

            if (context.equals(MemorySegment.NULL) || configPtr.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("Native library returned a null FFM collision state");
            }
        }

        private PushResult allocateOutput(int elementCapacity) {
            long requiredBytes = Math.max(1024L, (long) elementCapacity * Integer.BYTES);
            if (requiredBytes > byteCapacity) {
                if (bufferArena != null) {
                    bufferArena.close();
                }
                bufferArena = Arena.ofConfined();
                bufferA = bufferArena.allocate(requiredBytes);
                bufferB = bufferArena.allocate(requiredBytes);
                densityBuffer = bufferArena.allocate(requiredBytes);
                byteCapacity = requiredBytes;
            }
            result.update(bufferA, bufferB, densityBuffer);
            return result;
        }

        private void destroy() {
            if (bufferArena != null) {
                bufferArena.close();
                bufferArena = null;
            }
            try {
                destroyCtxMethodHandle.invokeExact(context);
                destroyCfgMethodHandle.invokeExact(configPtr);
            } catch (Throwable failure) {
                throw new IllegalStateException("Failed to destroy FFM collision state", failure);
            }
        }
    }
}
