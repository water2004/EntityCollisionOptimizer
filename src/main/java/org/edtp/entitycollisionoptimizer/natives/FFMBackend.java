package org.edtp.entitycollisionoptimizer.natives;

import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
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
    private static MethodHandle insertEntity, removeEntity, updateEntitySection;
    private static MethodHandle updateEntityState;
    private static MethodHandle invalidateEntityPushEligibilityCache;
    private static MethodHandle invalidatePushEligibilityCacheFields;
    private static MethodHandle queryHard;
    private static MethodHandle queryEntities;
    private static MethodHandle queryPushable;
    private static MethodHandle executeRun;
    private static MethodHandle movement;
    private static MethodHandle blockScan;
    private static MethodHandle prepareMovement;
    private static final Set<Context> CONTEXTS = ConcurrentHashMap.newKeySet();
    private static volatile boolean initialized;

    public static int scanBlocks(
            MemorySegment collisionRows,
            MemorySegment queryState,
            MemorySegment outputRecords,
            int outputCapacity
    ) {
        ensureInitialized();
        try {
            int recordCount = (int) blockScan.invokeExact(
                    collisionRows, queryState, outputRecords, outputCapacity
            );
            if (recordCount < 0) throw new IllegalStateException("Invalid native block scan: " + recordCount);
            return recordCount;
        } catch (Throwable failure) { throw new IllegalStateException("Native block scan failed", failure); }
    }

    private FFMBackend() {
    }

    public static void solveMovement(
            MemorySegment body,
            MemorySegment movementPacket,
            MemorySegment shapeReferences,
            int shapeCount,
            int movementPhase
    ) {
        ensureInitialized();
        try {
            int status = (int) movement.invokeExact(
                    body, movementPacket, shapeReferences, shapeCount, movementPhase
            );
            checkStatus("solve native movement", status);
        } catch (Throwable failure) {
            throw new IllegalStateException("FFM movement call failed", failure);
        }
    }

    public static void prepareMovement(MemorySegment entityBounds, MemorySegment movementPacket) {
        ensureInitialized();
        try {
            checkStatus(
                    "prepare native movement",
                    (int) prepareMovement.invokeExact(entityBounds, movementPacket)
            );
        } catch (Throwable failure) {
            throw new IllegalStateException("FFM movement preparation failed", failure);
        }
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

    public static Context createContext() {
        ensureInitialized();
        MemorySegment address = MemorySegment.NULL;
        try {
            address = (MemorySegment) createContextHandle.invokeExact();
            if (address.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("Native library returned a null collision context");
            }
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

    public static void updateEntityState(
            Context nativeContext,
            int nativeId,
            MemorySegment entityBounds,
            boolean selectable,
            boolean passenger,
            boolean vanillaEntityPush,
            boolean allowsDeferredVelocityWrites,
            int teamId,
            int collisionRule,
            int bodySlot,
            boolean hardCollidable
    ) {
        synchronized (nativeContext) {
            nativeContext.ensureOpen();
            try {
                int status = (int) updateEntityState.invokeExact(
                        nativeContext.address,
                        nativeId,
                        entityBounds,
                        selectable ? 1 : 0,
                        passenger ? 1 : 0,
                        vanillaEntityPush ? 1 : 0,
                        allowsDeferredVelocityWrites ? 1 : 0,
                        teamId,
                        collisionRule,
                        bodySlot,
                        hardCollidable ? 1 : 0
                );
                checkStatus("update native entity", status);
            } catch (Throwable failure) {
                throw new IllegalStateException("FFM updateEntityState call failed", failure);
            }
        }
    }

    public static void insertEntity(
            Context nativeContext,
            int nativeId,
            AABB entityBounds,
            int sectionX,
            int sectionY,
            int sectionZ
    ) {
        synchronized (nativeContext) {
            MemorySegment boundsBuffer = prepareEntityBounds(nativeContext, nativeId, entityBounds);
            try { checkStatus("insert persistent entity", (int) insertEntity.invokeExact(
                    nativeContext.address,
                    nativeId,
                    boundsBuffer,
                    sectionX,
                    sectionY,
                    sectionZ
            )); }
            catch (Throwable failure) { throw new IllegalStateException("Persistent entity insertion failed", failure); }
        }
    }

    private static MemorySegment prepareEntityBounds(Context nativeContext, int nativeId, AABB entityBounds) {
        nativeContext.ensureOpen();
        nativeContext.ensureOutputCapacity(nativeId + 1);
        return prepareBounds(nativeContext.boundsBuffer, entityBounds);
    }

    private static MemorySegment prepareBounds(MemorySegment boundsBuffer, AABB entityBounds) {
        boundsBuffer.set(JAVA_DOUBLE, 0, entityBounds.minX);
        boundsBuffer.set(JAVA_DOUBLE, 8, entityBounds.minY);
        boundsBuffer.set(JAVA_DOUBLE, 16, entityBounds.minZ);
        boundsBuffer.set(JAVA_DOUBLE, 24, entityBounds.maxX);
        boundsBuffer.set(JAVA_DOUBLE, 32, entityBounds.maxY);
        boundsBuffer.set(JAVA_DOUBLE, 40, entityBounds.maxZ);
        return boundsBuffer;
    }

    public static void removeEntity(Context nativeContext, int nativeId) {
        synchronized (nativeContext) {
            nativeContext.ensureOpen();
            try {
                checkStatus("remove persistent entity", (int) removeEntity.invokeExact(
                        nativeContext.address, nativeId
                ));
            }
            catch (Throwable failure) { throw new IllegalStateException("Persistent entity removal failed", failure); }
        }
    }

    public static void updateEntitySection(
            Context nativeContext,
            int nativeId,
            int sectionX,
            int sectionY,
            int sectionZ
    ) {
        synchronized (nativeContext) {
            nativeContext.ensureOpen();
            try { checkStatus("update persistent entity section", (int) updateEntitySection.invokeExact(
                    nativeContext.address,
                    nativeId,
                    sectionX,
                    sectionY,
                    sectionZ
            )); }
            catch (Throwable failure) { throw new IllegalStateException("Persistent entity section update failed", failure); }
        }
    }

    public static void invalidateEntityPushEligibilityCache(Context nativeContext, int nativeId) {
        synchronized (nativeContext) {
            nativeContext.ensureOpen();
            try {
                int status = (int) invalidateEntityPushEligibilityCache.invokeExact(nativeContext.address, nativeId);
                checkStatus("invalidate native entity push-eligibility cache", status);
            } catch (Throwable failure) {
                throw new IllegalStateException("FFM invalidateEntityPushEligibilityCache call failed", failure);
            }
        }
    }

    public static void invalidatePushEligibilityCacheFields(Context nativeContext, int fieldsToInvalidate) {
        synchronized (nativeContext) {
            nativeContext.ensureOpen();
            try {
                int status = (int) invalidatePushEligibilityCacheFields.invokeExact(
                        nativeContext.address, fieldsToInvalidate);
                checkStatus("invalidate native push eligibility fields", status);
            } catch (Throwable failure) {
                throw new IllegalStateException("FFM invalidatePushEligibilityCacheFields call failed", failure);
            }
        }
    }

    public static QueryResult queryHard(
            Context nativeContext,
            AABB scan,
            int excludedNativeId,
            boolean hardOnly,
            int nativeIdCapacity
    ) {
        synchronized (nativeContext) {
            nativeContext.ensureOpen();
            nativeContext.ensureOutputCapacity(nativeIdCapacity);
            try {
                int resultSize = (int) queryHard.invokeExact(
                        nativeContext.address,
                        scan.minX,
                        scan.minY,
                        scan.minZ,
                        scan.maxX,
                        scan.maxY,
                        scan.maxZ,
                        excludedNativeId,
                        hardOnly ? 1 : 0,
                        nativeContext.outputBuffer,
                        nativeContext.outputCapacity
                );
                if (resultSize < 0 || resultSize > nativeContext.outputCapacity) {
                    throw new IllegalStateException(
                            "Native hard collision query returned invalid size " + resultSize
                    );
                }
                QueryResult result = nativeContext.queryResult;
                result.offset = 0;
                result.size = resultSize;
                result.metadataRequired = false;
                result.pushableCount = 0;
                result.nonPassengerCount = 0;
                return result;
            } catch (Throwable failure) {
                throw new IllegalStateException("FFM hard collision query failed", failure);
            }
        }
    }

    /** Whole-level box scan in vanilla candidate order. */
    public static QueryResult queryEntities(
            Context nativeContext,
            AABB scan,
            int nativeIdCapacity
    ) {
        synchronized (nativeContext) {
            nativeContext.ensureOpen();
            nativeContext.ensureOutputCapacity(nativeIdCapacity);
            try {
                int resultSize = (int) queryEntities.invokeExact(
                        nativeContext.address,
                        scan.minX,
                        scan.minY,
                        scan.minZ,
                        scan.maxX,
                        scan.maxY,
                        scan.maxZ,
                        nativeContext.outputBuffer,
                        nativeContext.outputCapacity
                );
                if (resultSize < 0 || resultSize > nativeContext.outputCapacity) {
                    throw new IllegalStateException("Invalid native entity query size: " + resultSize);
                }
                QueryResult result = new QueryResult();
                result.offset = 0;
                result.size = resultSize;
                result.output = nativeContext.outputBuffer;
                result.nativePushFlags = nativeContext.nativePushBuffer;
                result.metadataRequired = false;
                result.pushableCount = 0;
                result.nonPassengerCount = 0;
                return result;
            } catch (Throwable failure) {
                throw new IllegalStateException("FFM entity query failed", failure);
            }
        }
    }

    public static QueryResult queryPushable(
            Context nativeContext,
            AABB sourceBounds,
            int excludedNativeId,
            int sourceTeamId,
            int sourceCollisionRule,
            boolean sourceNativePushEligible,
            int nativeIdCapacity
    ) {
        synchronized (nativeContext) {
            nativeContext.ensureOpen();
            nativeContext.ensureOutputCapacity(nativeIdCapacity);
            MemorySegment sourceBoundsBuffer = prepareBounds(nativeContext.boundsBuffer, sourceBounds);
            try {
                int resultSize = (int) queryPushable.invokeExact(
                        nativeContext.address,
                        sourceBoundsBuffer,
                        excludedNativeId,
                        sourceTeamId,
                        sourceCollisionRule,
                        sourceNativePushEligible ? 1 : 0,
                        nativeContext.outputBuffer,
                        nativeContext.nativePushBuffer,
                        nativeContext.outputCapacity
                );
                if (resultSize < 0 || resultSize > nativeContext.outputCapacity) {
                    throw new IllegalStateException(
                            "Native pushable collision query returned invalid size " + resultSize
                    );
                }
                QueryResult result = nativeContext.queryResult;
                result.offset = 3;
                result.bodyOffset = 3 + nativeContext.outputCapacity;
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

    /** Native owns velocity/version/sync and consumes shared guards. Only target body slots are submitted. */
    public static void executePushRun(
            Context nativeContext,
            MemorySegment sourceBody,
            MemorySegment targetBodies,
            int targetCapacity,
            int[] targetSlots,
            int targetStart,
            int targetCount
    ) {
        if (!sourceBody.isNative() || sourceBody.isReadOnly()
                || sourceBody.byteSize() < CollisionStateTable.STRIDE_BYTES
                || !targetBodies.isNative() || targetBodies.isReadOnly()
                || targetBodies.byteSize() < (long) targetCapacity * CollisionStateTable.STRIDE_BYTES
                || targetStart < 0 || targetCount < 0
                || targetStart > targetSlots.length - targetCount) {
            throw new IllegalArgumentException("Invalid native push run size " + targetCount);
        }
        synchronized (nativeContext) {
            nativeContext.ensureOpen();
            if (targetCount == 0) return;
            nativeContext.ensureOutputCapacity(targetCount);
            MemorySegment.copy(
                    targetSlots,
                    targetStart,
                    nativeContext.runIdBuffer,
                    JAVA_INT,
                    0,
                    targetCount
            );
            try {
                int status = (int) executeRun.invokeExact(sourceBody, targetBodies, targetCapacity,
                        nativeContext.runIdBuffer, targetCount);
                checkStatus("execute native push run", status);
            } catch (Throwable failure) {
                throw new IllegalStateException("FFM push run execution failed", failure);
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
        blockScan = linker.downcallHandle(library.find("scanCollisionBlocks").orElseThrow(() -> missingSymbol("scanCollisionBlocks")),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
        createContextHandle = linker.downcallHandle(
                library.find("createCollisionContext").orElseThrow(() -> missingSymbol("createCollisionContext")),
                FunctionDescriptor.of(ADDRESS)
        );
        destroyContextHandle = linker.downcallHandle(
                library.find("destroyCollisionContext").orElseThrow(() -> missingSymbol("destroyCollisionContext")),
                FunctionDescriptor.ofVoid(ADDRESS)
        );
        insertEntity = linker.downcallHandle(
                library.find("insertCollisionEntity").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS,
                        JAVA_INT, JAVA_INT, JAVA_INT)
        );
        removeEntity = linker.downcallHandle(library.find("removeCollisionEntity").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        updateEntitySection = linker.downcallHandle(
                library.find("updateCollisionEntitySection").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT,
                        JAVA_INT, JAVA_INT, JAVA_INT)
        );
        FunctionDescriptor updateEntityDescriptor = FunctionDescriptor.of(
                        JAVA_INT,
                        ADDRESS,
                        JAVA_INT,
                        ADDRESS,
                        JAVA_INT,
                        JAVA_INT,
                        JAVA_INT,
                        JAVA_INT,
                        JAVA_INT,
                        JAVA_INT,
                        JAVA_INT,
                        JAVA_INT
                );
        updateEntityState = linker.downcallHandle(
                library.find("updateCollisionEntityState")
                        .orElseThrow(() -> missingSymbol("updateCollisionEntityState")), updateEntityDescriptor);
        invalidateEntityPushEligibilityCache = linker.downcallHandle(
                library.find("invalidateEntityPushEligibilityCache")
                        .orElseThrow(() -> missingSymbol("invalidateEntityPushEligibilityCache")),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)
        );
        invalidatePushEligibilityCacheFields = linker.downcallHandle(
                library.find("invalidatePushEligibilityCacheFields")
                        .orElseThrow(() -> missingSymbol("invalidatePushEligibilityCacheFields")),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)
        );
        queryHard = linker.downcallHandle(
                library.find("queryHardCollisionEntities")
                        .orElseThrow(() -> missingSymbol("queryHardCollisionEntities")),
                FunctionDescriptor.of(
                        JAVA_INT,
                        ADDRESS,
                        JAVA_DOUBLE,
                        JAVA_DOUBLE,
                        JAVA_DOUBLE,
                        JAVA_DOUBLE,
                        JAVA_DOUBLE,
                        JAVA_DOUBLE,
                        JAVA_INT,
                        JAVA_INT,
                        ADDRESS,
                        JAVA_INT
                )
        );
        queryEntities = linker.downcallHandle(
                library.find("queryEntitiesInBox")
                        .orElseThrow(() -> missingSymbol("queryEntitiesInBox")),
                FunctionDescriptor.of(
                        JAVA_INT,
                        ADDRESS,
                        JAVA_DOUBLE,
                        JAVA_DOUBLE,
                        JAVA_DOUBLE,
                        JAVA_DOUBLE,
                        JAVA_DOUBLE,
                        JAVA_DOUBLE,
                        ADDRESS,
                        JAVA_INT
                )
        );
        queryPushable = linker.downcallHandle(
                library.find("queryPushableEntities")
                        .orElseThrow(() -> missingSymbol("queryPushableEntities")),
                FunctionDescriptor.of(
                        JAVA_INT,
                        ADDRESS,
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
        executeRun = linker.downcallHandle(
                library.find("executePushRun").orElseThrow(() -> missingSymbol("executePushRun")),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT)
        );
        movement = linker.downcallHandle(
                library.find("solveMovement").orElseThrow(() -> missingSymbol("solveMovement")),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
        prepareMovement = linker.downcallHandle(
                library.find("prepareMovement").orElseThrow(() -> missingSymbol("prepareMovement")),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), Linker.Option.critical(false));
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
        insertEntity = removeEntity = updateEntitySection = null;
        updateEntityState = null;
        invalidateEntityPushEligibilityCache = null;
        invalidatePushEligibilityCacheFields = null;
        queryHard = null;
        queryEntities = null;
        queryPushable = null;
        executeRun = null;
        movement = null;
    }

    public static final class Context implements AutoCloseable {
        private MemorySegment address;
        private Arena outputArena;
        private MemorySegment outputBuffer = MemorySegment.NULL;
        private MemorySegment nativePushBuffer = MemorySegment.NULL;
        private MemorySegment runIdBuffer = MemorySegment.NULL;
        private MemorySegment boundsBuffer = MemorySegment.NULL;
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
            if (outputArena != null && requiredElements <= outputCapacity) {
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
            boundsBuffer = outputArena.allocate(48, Double.BYTES);
            outputBuffer = outputArena.allocate(
                    ((long) newCapacity * 2 + 3) * Integer.BYTES,
                    Integer.BYTES
            );
            nativePushBuffer = outputArena.allocate((long) newCapacity * Integer.BYTES, Integer.BYTES);
            runIdBuffer = outputArena.allocate((long) newCapacity * Integer.BYTES, Integer.BYTES);
            outputCapacity = newCapacity;
            queryResult.output = outputBuffer;
            queryResult.nativePushFlags = nativePushBuffer;
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
                nativePushBuffer = MemorySegment.NULL;
                runIdBuffer = MemorySegment.NULL;
                boundsBuffer = MemorySegment.NULL;
                outputCapacity = 0;
                queryResult.output = MemorySegment.NULL;
                queryResult.nativePushFlags = MemorySegment.NULL;
                CONTEXTS.remove(this);
            }
        }
    }

    public static final class QueryResult {
        private MemorySegment output = MemorySegment.NULL;
        private MemorySegment nativePushFlags = MemorySegment.NULL;
        private int offset;
        private int bodyOffset;
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

        void copyBodiesTo(int[] bodySlots, int[] nativePushFlags) {
            if (metadataRequired || offset != 3) throw new IllegalStateException("No resolved push batch");
            if (size == 0) return;
            // Fixed-capacity regions let native fill IDs/slots/flags together, without a second traversal.
            MemorySegment.copy(output, JAVA_INT, (long) bodyOffset * Integer.BYTES, bodySlots, 0, size);
            MemorySegment.copy(this.nativePushFlags, JAVA_INT, 0, nativePushFlags, 0, size);
        }
    }
}
