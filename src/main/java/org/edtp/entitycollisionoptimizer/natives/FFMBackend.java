package org.edtp.entitycollisionoptimizer.natives;

import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.config.CollisionOptimizerConfig;
import org.edtp.entitycollisionoptimizer.ffm.FFM;
import net.minecraft.world.phys.AABB;

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
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * FFM bindings for the live native spatial index. There is deliberately no
 * alternate accelerated backend or silent fallback.
 */
public final class FFMBackend {
    private static Arena nativeArena;
    private static MethodHandle createContextHandle;
    private static MethodHandle destroyContextHandle;
    private static MethodHandle beginFrame;
    private static MethodHandle setGridSize;
    private static MethodHandle addEntity;
    private static MethodHandle updateEntity;
    private static MethodHandle updateEntityMetadata;
    private static MethodHandle invalidateEntityMetadata;
    private static MethodHandle invalidateMetadata;
    private static MethodHandle query;
    private static MethodHandle queryPushable;
    private static final Set<Context> CONTEXTS = ConcurrentHashMap.newKeySet();
    private static volatile boolean initialized;

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
            initialized = true;
            EntityCollisionOptimizer.LOGGER.info("FFM collision backend initialized");
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
        for (Context context : CONTEXTS) {
            context.applyConfig();
        }
    }

    public static Context createContext() {
        ensureInitialized();
        MemorySegment address = MemorySegment.NULL;
        try {
            address = (MemorySegment) createContextHandle.invokeExact();
            if (address.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("Native library returned a null collision context");
            }
            int status = (int) setGridSize.invokeExact(address, CollisionOptimizerConfig.gridSize);
            checkStatus("configure native collision grid", status);
            Context context = new Context(address);
            CONTEXTS.add(context);
            return context;
        } catch (Throwable failure) {
            if (!address.equals(MemorySegment.NULL) && destroyContextHandle != null) {
                try {
                    destroyContextHandle.invokeExact(address);
                } catch (Throwable cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw new IllegalStateException("Failed to create an FFM collision context", failure);
        }
    }

    public static void beginFrame(
            Context nativeContext,
            double[] aabbs,
            double[] positions,
            int[] sections,
            int entityCount,
            int gridSize
    ) {
        synchronized (nativeContext) {
            nativeContext.ensureOpen();
            nativeContext.ensureOutputCapacity(entityCount);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment aabbMemory = FFM.allocateArray(arena, aabbs);
                MemorySegment positionMemory = FFM.allocateArray(arena, positions);
                MemorySegment sectionMemory = FFM.allocateArray(arena, sections);
                final int status;
                try {
                    status = (int) beginFrame.invokeExact(
                            nativeContext.address,
                            aabbMemory,
                            positionMemory,
                            sectionMemory,
                            entityCount,
                            gridSize
                    );
                } catch (Throwable failure) {
                    throw new IllegalStateException("FFM beginFrame call failed", failure);
                }
                checkStatus("build native collision frame", status);
            }
        }
    }

    public static int addEntity(
            Context nativeContext,
            AABB box,
            double positionX,
            double positionZ,
            int sectionX,
            int sectionY,
            int sectionZ
    ) {
        synchronized (nativeContext) {
            nativeContext.ensureOpen();
            try {
                int nativeId = (int) addEntity.invokeExact(
                        nativeContext.address,
                        box.minX,
                        box.minY,
                        box.minZ,
                        box.maxX,
                        box.maxY,
                        box.maxZ,
                        positionX,
                        positionZ,
                        sectionX,
                        sectionY,
                        sectionZ
                );
                if (nativeId < 0) {
                    throw new IllegalStateException("Native collision index rejected an entity");
                }
                nativeContext.ensureOutputCapacity(nativeId + 1);
                return nativeId;
            } catch (Throwable failure) {
                throw new IllegalStateException("FFM addEntity call failed", failure);
            }
        }
    }

    public static void updateEntity(
            Context nativeContext,
            int nativeId,
            AABB box,
            double positionX,
            double positionZ,
            int sectionX,
            int sectionY,
            int sectionZ
    ) {
        synchronized (nativeContext) {
            nativeContext.ensureOpen();
            try {
                int status = (int) updateEntity.invokeExact(
                        nativeContext.address,
                        nativeId,
                        box.minX,
                        box.minY,
                        box.minZ,
                        box.maxX,
                        box.maxY,
                        box.maxZ,
                        positionX,
                        positionZ,
                        sectionX,
                        sectionY,
                        sectionZ
                );
                checkStatus("update native entity bounds", status);
            } catch (Throwable failure) {
                throw new IllegalStateException("FFM updateEntity call failed", failure);
            }
        }
    }

    public static void updateEntityMetadata(
            Context nativeContext,
            int nativeId,
            boolean selectable,
            boolean passenger,
            boolean vehicle,
            boolean noPhysics,
            boolean vanillaEntityPush,
            boolean vanillaVectorPush,
            int teamId,
            int collisionRule
    ) {
        synchronized (nativeContext) {
            nativeContext.ensureOpen();
            try {
                int status = (int) updateEntityMetadata.invokeExact(
                        nativeContext.address,
                        nativeId,
                        selectable ? 1 : 0,
                        passenger ? 1 : 0,
                        vehicle ? 1 : 0,
                        noPhysics ? 1 : 0,
                        vanillaEntityPush ? 1 : 0,
                        vanillaVectorPush ? 1 : 0,
                        teamId,
                        collisionRule
                );
                checkStatus("update native entity collision metadata", status);
            } catch (Throwable failure) {
                throw new IllegalStateException("FFM updateEntityMetadata call failed", failure);
            }
        }
    }

    public static void invalidateEntityMetadata(Context nativeContext, int nativeId) {
        synchronized (nativeContext) {
            nativeContext.ensureOpen();
            try {
                int status = (int) invalidateEntityMetadata.invokeExact(nativeContext.address, nativeId);
                checkStatus("invalidate native entity collision metadata", status);
            } catch (Throwable failure) {
                throw new IllegalStateException("FFM invalidateEntityMetadata call failed", failure);
            }
        }
    }

    public static void invalidateMetadata(Context nativeContext, int mask) {
        synchronized (nativeContext) {
            nativeContext.ensureOpen();
            try {
                int status = (int) invalidateMetadata.invokeExact(nativeContext.address, mask);
                checkStatus("invalidate native collision metadata", status);
            } catch (Throwable failure) {
                throw new IllegalStateException("FFM invalidateMetadata call failed", failure);
            }
        }
    }

    public static QueryResult query(Context nativeContext, int sourceId, int entityCount) {
        synchronized (nativeContext) {
            nativeContext.ensureOpen();
            nativeContext.ensureOutputCapacity(entityCount);
            try {
                int resultSize = (int) query.invokeExact(
                        nativeContext.address,
                        sourceId,
                        nativeContext.outputBuffer,
                        nativeContext.outputCapacity
                );
                if (resultSize < 0 || resultSize > nativeContext.outputCapacity) {
                    throw new IllegalStateException("Native collision query returned invalid size " + resultSize);
                }
                QueryResult result = nativeContext.queryResult;
                result.offset = 0;
                result.size = resultSize;
                result.metadataRequired = false;
                result.pushableCount = 0;
                result.nonPassengerCount = 0;
                return result;
            } catch (Throwable failure) {
                throw new IllegalStateException("FFM collision query failed", failure);
            }
        }
    }

    public static QueryResult queryPushable(
            Context nativeContext,
            int sourceId,
            int sourceTeamId,
            int sourceCollisionRule,
            boolean sourceUsesVanillaPush,
            int entityCount
    ) {
        synchronized (nativeContext) {
            nativeContext.ensureOpen();
            nativeContext.ensureOutputCapacity(entityCount);
            try {
                int resultSize = (int) queryPushable.invokeExact(
                        nativeContext.address,
                        sourceId,
                        sourceTeamId,
                        sourceCollisionRule,
                        sourceUsesVanillaPush ? 1 : 0,
                        nativeContext.outputBuffer,
                        nativeContext.impulseBuffer,
                        nativeContext.outputCapacity
                );
                if (resultSize < 0 || resultSize > nativeContext.outputCapacity) {
                    throw new IllegalStateException(
                            "Native pushable collision query returned invalid size " + resultSize
                    );
                }
                QueryResult result = nativeContext.queryResult;
                result.offset = 3;
                result.size = resultSize;
                result.metadataRequired = nativeContext.outputBuffer.get(JAVA_INT, 0) != 0;
                result.pushableCount = nativeContext.outputBuffer.get(JAVA_INT, Integer.BYTES);
                result.nonPassengerCount = nativeContext.outputBuffer.get(
                        JAVA_INT,
                        (long) Integer.BYTES * 2
                );
                return result;
            } catch (Throwable failure) {
                throw new IllegalStateException("FFM pushable collision query failed", failure);
            }
        }
    }

    public static synchronized void destroy() {
        if (!initialized) {
            return;
        }

        try {
            for (Context context : Set.copyOf(CONTEXTS)) {
                context.close();
            }
        } finally {
            initialized = false;
            resetHandles();
        }
    }

    private static void ensureInitialized() {
        if (!initialized) {
            initialize();
        }
    }

    private static void loadNativeLibrary() throws IOException {
        String libraryName = System.mapLibraryName("EntityCollisionOptimizer");
        String resourcePath = platformNativePath() + libraryName;
        File extractedLibrary;

        try (InputStream libraryStream = EntityCollisionOptimizer.class.getResourceAsStream(resourcePath)) {
            if (libraryStream == null) {
                throw new FileNotFoundException("Cannot find native library resource " + resourcePath);
            }
            extractedLibrary = File.createTempFile(
                    UUID.randomUUID() + "_entityCollisionOptimizer_",
                    "_" + libraryName
            );
            extractedLibrary.deleteOnExit();
            try (OutputStream output = new FileOutputStream(extractedLibrary)) {
                libraryStream.transferTo(output);
            }
        }

        EntityCollisionOptimizer.LOGGER.info(
                "Extracted FFM native library {} to {}",
                resourcePath,
                extractedLibrary.getAbsolutePath()
        );

        Linker linker = Linker.nativeLinker();
        nativeArena = Arena.global();
        SymbolLookup library = SymbolLookup.libraryLookup(extractedLibrary.getAbsolutePath(), nativeArena);
        createContextHandle = linker.downcallHandle(
                library.find("createCollisionContext").orElseThrow(() -> missingSymbol("createCollisionContext")),
                FunctionDescriptor.of(ADDRESS)
        );
        destroyContextHandle = linker.downcallHandle(
                library.find("destroyCollisionContext").orElseThrow(() -> missingSymbol("destroyCollisionContext")),
                FunctionDescriptor.ofVoid(ADDRESS)
        );
        beginFrame = linker.downcallHandle(
                library.find("beginCollisionFrame").orElseThrow(() -> missingSymbol("beginCollisionFrame")),
                FunctionDescriptor.of(
                        JAVA_INT,
                        ADDRESS,
                        ADDRESS,
                        ADDRESS,
                        ADDRESS,
                        JAVA_INT,
                        JAVA_INT
                )
        );
        setGridSize = linker.downcallHandle(
                library.find("setCollisionGridSize").orElseThrow(() -> missingSymbol("setCollisionGridSize")),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)
        );
        addEntity = linker.downcallHandle(
                library.find("addCollisionEntity").orElseThrow(() -> missingSymbol("addCollisionEntity")),
                FunctionDescriptor.of(
                        JAVA_INT,
                        ADDRESS,
                        JAVA_DOUBLE,
                        JAVA_DOUBLE,
                        JAVA_DOUBLE,
                        JAVA_DOUBLE,
                        JAVA_DOUBLE,
                        JAVA_DOUBLE,
                        JAVA_DOUBLE,
                        JAVA_DOUBLE,
                        JAVA_INT,
                        JAVA_INT,
                        JAVA_INT
                )
        );
        updateEntity = linker.downcallHandle(
                library.find("updateCollisionEntity").orElseThrow(() -> missingSymbol("updateCollisionEntity")),
                FunctionDescriptor.of(
                        JAVA_INT,
                        ADDRESS,
                        JAVA_INT,
                        JAVA_DOUBLE,
                        JAVA_DOUBLE,
                        JAVA_DOUBLE,
                        JAVA_DOUBLE,
                        JAVA_DOUBLE,
                        JAVA_DOUBLE,
                        JAVA_DOUBLE,
                        JAVA_DOUBLE,
                        JAVA_INT,
                        JAVA_INT,
                        JAVA_INT
                )
        );
        updateEntityMetadata = linker.downcallHandle(
                library.find("updateCollisionEntityMetadata")
                        .orElseThrow(() -> missingSymbol("updateCollisionEntityMetadata")),
                FunctionDescriptor.of(
                        JAVA_INT,
                        ADDRESS,
                        JAVA_INT,
                        JAVA_INT,
                        JAVA_INT,
                        JAVA_INT,
                        JAVA_INT,
                        JAVA_INT,
                        JAVA_INT,
                        JAVA_INT,
                        JAVA_INT
                )
        );
        invalidateEntityMetadata = linker.downcallHandle(
                library.find("invalidateCollisionEntityMetadata")
                        .orElseThrow(() -> missingSymbol("invalidateCollisionEntityMetadata")),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)
        );
        invalidateMetadata = linker.downcallHandle(
                library.find("invalidateCollisionMetadata")
                        .orElseThrow(() -> missingSymbol("invalidateCollisionMetadata")),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)
        );
        query = linker.downcallHandle(
                library.find("queryCollisionEntities").orElseThrow(() -> missingSymbol("queryCollisionEntities")),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT)
        );
        queryPushable = linker.downcallHandle(
                library.find("queryPushableEntities")
                        .orElseThrow(() -> missingSymbol("queryPushableEntities")),
                FunctionDescriptor.of(
                        JAVA_INT,
                        ADDRESS,
                        JAVA_INT,
                        JAVA_INT,
                        JAVA_INT,
                        JAVA_INT,
                        ADDRESS,
                        ADDRESS,
                        JAVA_INT
                )
        );
    }

    private static void checkStatus(String operation, int status) {
        if (status != 0) {
            throw new IllegalStateException("Failed to " + operation + "; native status=" + status);
        }
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
        CONTEXTS.clear();
        nativeArena = null;
        createContextHandle = null;
        destroyContextHandle = null;
        beginFrame = null;
        setGridSize = null;
        addEntity = null;
        updateEntity = null;
        updateEntityMetadata = null;
        invalidateEntityMetadata = null;
        invalidateMetadata = null;
        query = null;
        queryPushable = null;
    }

    public static final class Context implements AutoCloseable {
        private MemorySegment address;
        private Arena outputArena;
        private MemorySegment outputBuffer = MemorySegment.NULL;
        private MemorySegment impulseBuffer = MemorySegment.NULL;
        private int outputCapacity;
        private final QueryResult queryResult = new QueryResult();

        private Context(MemorySegment address) {
            this.address = address;
        }

        private void ensureOpen() {
            if (address.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("FFM collision context is closed");
            }
        }

        private void ensureOutputCapacity(int requiredElements) {
            if (requiredElements <= outputCapacity) {
                return;
            }
            int newCapacity = Math.max(
                    requiredElements,
                    outputCapacity + (outputCapacity >> 1) + 256
            );
            if (outputArena != null) {
                outputArena.close();
            }
            outputArena = Arena.ofShared();
            outputBuffer = outputArena.allocate(
                    (long) (newCapacity + 3) * Integer.BYTES,
                    Integer.BYTES
            );
            impulseBuffer = outputArena.allocate(
                    (long) newCapacity * 4 * Double.BYTES,
                    Double.BYTES
            );
            outputCapacity = newCapacity;
            queryResult.output = outputBuffer;
            queryResult.impulses = impulseBuffer;
        }

        private synchronized void applyConfig() {
            ensureOpen();
            try {
                int status = (int) setGridSize.invokeExact(address, CollisionOptimizerConfig.gridSize);
                checkStatus("configure native collision grid", status);
            } catch (Throwable failure) {
                throw new IllegalStateException("Failed to update the FFM collision configuration", failure);
            }
        }

        @Override
        public synchronized void close() {
            if (address.equals(MemorySegment.NULL)) {
                return;
            }
            MemorySegment closingAddress = address;
            address = MemorySegment.NULL;
            try {
                destroyContextHandle.invokeExact(closingAddress);
            } catch (Throwable failure) {
                throw new IllegalStateException("Failed to destroy an FFM collision context", failure);
            } finally {
                if (outputArena != null) {
                    outputArena.close();
                    outputArena = null;
                }
                outputBuffer = MemorySegment.NULL;
                impulseBuffer = MemorySegment.NULL;
                outputCapacity = 0;
                queryResult.output = MemorySegment.NULL;
                queryResult.impulses = MemorySegment.NULL;
                CONTEXTS.remove(this);
            }
        }
    }

    public static final class QueryResult {
        private MemorySegment output = MemorySegment.NULL;
        private MemorySegment impulses = MemorySegment.NULL;
        private int offset;
        private int size;
        private boolean metadataRequired;
        private int pushableCount;
        private int nonPassengerCount;

        private QueryResult() {
        }

        public int size() {
            return size;
        }

        public boolean metadataRequired() {
            return metadataRequired;
        }

        public int pushableCount() {
            return pushableCount;
        }

        public int nonPassengerCount() {
            return nonPassengerCount;
        }

        public int get(int index) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException(index);
            }
            return output.get(JAVA_INT, (long) (offset + index) * Integer.BYTES);
        }

        public boolean hasNativeImpulse(int index) {
            return !Double.isNaN(impulse(index, 0));
        }

        public double sourceImpulseX(int index) {
            return impulse(index, 0);
        }

        public double sourceImpulseZ(int index) {
            return impulse(index, 1);
        }

        public double targetImpulseX(int index) {
            return impulse(index, 2);
        }

        public double targetImpulseZ(int index) {
            return impulse(index, 3);
        }

        private double impulse(int index, int component) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException(index);
            }
            return impulses.get(
                    JAVA_DOUBLE,
                    ((long) index * 4 + component) * Double.BYTES
            );
        }
    }
}
