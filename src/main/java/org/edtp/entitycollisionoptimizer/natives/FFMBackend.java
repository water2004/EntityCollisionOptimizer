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
import static java.lang.foreign.ValueLayout.JAVA_LONG;

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
    private static MethodHandle putEntity, removeEntity, updateLocation;
    private static MethodHandle updateEntity;
    private static MethodHandle invalidateEntityPushabilityCache;
    private static MethodHandle invalidatePushEligibilityFields;
    private static MethodHandle query;
    private static MethodHandle queryHard;
    private static MethodHandle queryEntities;
    private static MethodHandle queryPushable;
    private static MethodHandle executeRun;
    private static MethodHandle movement;
    private static MethodHandle blockScan;
    private static MethodHandle prepareMovement;
    private static final Set<Context> CONTEXTS = ConcurrentHashMap.newKeySet();
    private static volatile boolean initialized;

    public static int scanBlocks(MemorySegment rows, MemorySegment query, MemorySegment output, int capacity) {
        ensureInitialized();
        try {
            int count = (int) blockScan.invokeExact(rows, query, output, capacity);
            if (count < 0) throw new IllegalStateException("Invalid native block scan: " + count);
            return count;
        } catch (Throwable failure) { throw new IllegalStateException("Native block scan failed", failure); }
    }

    private FFMBackend() {
    }

    public static void solveMovement(MemorySegment body, MemorySegment packet, MemorySegment shapes, int count, int phase) {
        ensureInitialized();
        try {
            int status = (int) movement.invokeExact(body, packet, shapes, count, phase);
            checkStatus("solve native movement", status);
        } catch (Throwable failure) {
            throw new IllegalStateException("FFM movement call failed", failure);
        }
    }

    public static void prepareMovement(MemorySegment bounds, MemorySegment packet) {
        ensureInitialized();
        try {
            checkStatus("prepare native movement", (int) prepareMovement.invokeExact(bounds, packet));
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
            int[] sections,
            int entityCount,
            int gridSize
    ) {
        synchronized (nativeContext) {
            nativeContext.ensureOpen();
            nativeContext.ensureOutputCapacity(entityCount);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment aabbMemory = FFM.allocateArray(arena, aabbs);
                MemorySegment sectionMemory = FFM.allocateArray(arena, sections);
                final int status;
                try {
                    status = (int) beginFrame.invokeExact(
                            nativeContext.address,
                            aabbMemory,
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
            MemorySegment bounds,
            boolean selectable,
            boolean passenger,
            boolean vehicle,
            boolean noPhysics,
            boolean vanillaEntityPush,
            boolean vanillaVectorPush,
            int teamId,
            int collisionRule,
            int bodySlot,
            boolean hardCollidable,
            long sectionOrder
    ) {
        synchronized (nativeContext) {
            nativeContext.ensureOpen();
            try {
                int status = (int) updateEntity.invokeExact(
                        nativeContext.address,
                        nativeId,
                        bounds,
                        selectable ? 1 : 0,
                        passenger ? 1 : 0,
                        vehicle ? 1 : 0,
                        noPhysics ? 1 : 0,
                        vanillaEntityPush ? 1 : 0,
                        vanillaVectorPush ? 1 : 0,
                        teamId,
                        collisionRule,
                        bodySlot,
                        hardCollidable ? 1 : 0,
                        sectionOrder
                );
                checkStatus("update native entity", status);
            } catch (Throwable failure) {
                throw new IllegalStateException("FFM updateEntity call failed", failure);
            }
        }
    }

    public static void putEntity(
            Context context, int id, AABB box, int x, int y, int z
    ) {
        synchronized (context) {
            MemorySegment bounds = prepareEntityBounds(context, id, box);
            try { checkStatus("insert persistent entity", (int) putEntity.invokeExact(
                    context.address, id, bounds, x, y, z
            )); }
            catch (Throwable failure) { throw new IllegalStateException("Persistent entity insertion failed", failure); }
        }
    }

    public static void putOrderedEntity(
            Context context, int id, AABB box, int x, int y, int z, long sectionOrder
    ) {
        synchronized (context) {
            MemorySegment bounds = prepareEntityBounds(context, id, box);
            try { checkStatus("insert persistent entity", (int) putEntity.invokeExact(
                    context.address, id, bounds, x, y, z, sectionOrder
            )); }
            catch (Throwable failure) { throw new IllegalStateException("Persistent entity insertion failed", failure); }
        }
    }

    private static MemorySegment prepareEntityBounds(Context context, int id, AABB box) {
        context.ensureOpen();
        context.ensureOutputCapacity(id + 1);
        MemorySegment bounds = context.boundsBuffer;
        bounds.set(JAVA_DOUBLE, 0, box.minX);
        bounds.set(JAVA_DOUBLE, 8, box.minY);
        bounds.set(JAVA_DOUBLE, 16, box.minZ);
        bounds.set(JAVA_DOUBLE, 24, box.maxX);
        bounds.set(JAVA_DOUBLE, 32, box.maxY);
        bounds.set(JAVA_DOUBLE, 40, box.maxZ);
        return bounds;
    }

    public static void removeEntity(Context context, int id) {
        synchronized (context) {
            context.ensureOpen();
            try { checkStatus("remove persistent entity", (int) removeEntity.invokeExact(context.address, id)); }
            catch (Throwable failure) { throw new IllegalStateException("Persistent entity removal failed", failure); }
        }
    }

    public static void updateLocation(
            Context context, int id, int x, int y, int z
    ) {
        synchronized (context) {
            context.ensureOpen();
            try { checkStatus("update persistent location", (int) updateLocation.invokeExact(
                    context.address, id, x, y, z
            )); }
            catch (Throwable failure) { throw new IllegalStateException("Persistent location update failed", failure); }
        }
    }

    public static void updateOrderedLocation(
            Context context, int id, int x, int y, int z, long sectionOrder
    ) {
        synchronized (context) {
            context.ensureOpen();
            try { checkStatus("update persistent location", (int) updateLocation.invokeExact(
                    context.address, id, x, y, z, sectionOrder
            )); }
            catch (Throwable failure) { throw new IllegalStateException("Persistent location update failed", failure); }
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
            int collisionRule,
            int bodySlot,
            boolean hardCollidable,
            long sectionOrder
    ) {
        updateEntity(nativeContext, nativeId, MemorySegment.NULL, selectable, passenger, vehicle,
                noPhysics, vanillaEntityPush, vanillaVectorPush, teamId, collisionRule, bodySlot,
                hardCollidable, sectionOrder);
    }

    public static void invalidateEntityPushabilityCache(Context nativeContext, int nativeId) {
        synchronized (nativeContext) {
            nativeContext.ensureOpen();
            try {
                int status = (int) invalidateEntityPushabilityCache.invokeExact(nativeContext.address, nativeId);
                checkStatus("invalidate native entity pushability cache", status);
            } catch (Throwable failure) {
                throw new IllegalStateException("FFM invalidateEntityPushabilityCache call failed", failure);
            }
        }
    }

    public static void invalidatePushEligibilityFields(Context nativeContext, int fieldsToInvalidate) {
        synchronized (nativeContext) {
            nativeContext.ensureOpen();
            try {
                int status = (int) invalidatePushEligibilityFields.invokeExact(
                        nativeContext.address, fieldsToInvalidate);
                checkStatus("invalidate native push eligibility fields", status);
            } catch (Throwable failure) {
                throw new IllegalStateException("FFM invalidatePushEligibilityFields call failed", failure);
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

    public static QueryResult queryHard(
            Context nativeContext,
            AABB scan,
            int excludeId,
            boolean hardOnly,
            int entityCount
    ) {
        synchronized (nativeContext) {
            nativeContext.ensureOpen();
            nativeContext.ensureOutputCapacity(entityCount);
            try {
                int resultSize = (int) queryHard.invokeExact(
                        nativeContext.address,
                        scan.minX,
                        scan.minY,
                        scan.minZ,
                        scan.maxX,
                        scan.maxY,
                        scan.maxZ,
                        excludeId,
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

    /** Whole-level box scan, ordered when the startup configuration requires it. */
    public static QueryResult queryEntities(
            Context nativeContext,
            AABB scan,
            int entityCount
    ) {
        synchronized (nativeContext) {
            nativeContext.ensureOpen();
            nativeContext.ensureOutputCapacity(entityCount);
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
                result.nativePush = nativeContext.nativePushBuffer;
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

    /** Native owns velocity/version/sync and consumes shared guards. Only target IDs are submitted. */
    public static void executePushRun(Context context, MemorySegment bodies, int capacity, int sourceSlot,
                                      int[] targetSlots, int from, int count) {
        if (!bodies.isNative() || bodies.isReadOnly() || bodies.byteSize() < (long) capacity * CollisionStateTable.STRIDE_BYTES
                || sourceSlot < 0 || sourceSlot >= capacity || from < 0 || count < 0
                || from > targetSlots.length - count) {
            throw new IllegalArgumentException("Invalid native push run size " + count);
        }
        synchronized (context) {
            context.ensureOpen();
            if (count == 0) return;
            context.ensureOutputCapacity(count);
            MemorySegment.copy(targetSlots, from, context.runIdBuffer, JAVA_INT, 0, count);
            try {
                int status = (int) executeRun.invokeExact(bodies, capacity, sourceSlot,
                        context.runIdBuffer, count);
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
        String libraryName = System.mapLibraryName(CollisionOptimizerConfig.STARTUP_VANILLA_ORDER
                ? "EntityCollisionOptimizer" : "EntityCollisionOptimizerUnordered");
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
        beginFrame = linker.downcallHandle(
                library.find("beginCollisionFrame").orElseThrow(() -> missingSymbol("beginCollisionFrame")),
                FunctionDescriptor.of(
                        JAVA_INT,
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
        FunctionDescriptor putEntityDescriptor = CollisionOptimizerConfig.STARTUP_VANILLA_ORDER
                ? FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS,
                        JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG)
                : FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS,
                        JAVA_INT, JAVA_INT, JAVA_INT);
        putEntity = linker.downcallHandle(
                library.find("putCollisionEntity").orElseThrow(), putEntityDescriptor
        );
        removeEntity = linker.downcallHandle(library.find("removeCollisionEntity").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        FunctionDescriptor updateLocationDescriptor = CollisionOptimizerConfig.STARTUP_VANILLA_ORDER
                ? FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT,
                        JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG)
                : FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT,
                        JAVA_INT, JAVA_INT, JAVA_INT);
        updateLocation = linker.downcallHandle(
                library.find("updateCollisionLocation").orElseThrow(), updateLocationDescriptor
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
                        JAVA_INT,
                        JAVA_INT,
                        JAVA_INT
                )
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
                        JAVA_INT,
                        JAVA_INT,
                        JAVA_INT,
                        JAVA_LONG
                );
        if (!CollisionOptimizerConfig.STARTUP_VANILLA_ORDER) {
            var layouts = updateEntityDescriptor.argumentLayouts();
            updateEntityDescriptor = FunctionDescriptor.of(JAVA_INT,
                    layouts.subList(0, layouts.size() - 1).toArray(java.lang.foreign.MemoryLayout[]::new));
        }
        updateEntity = linker.downcallHandle(
                library.find("updateCollisionEntity")
                        .orElseThrow(() -> missingSymbol("updateCollisionEntity")), updateEntityDescriptor);
        if (!CollisionOptimizerConfig.STARTUP_VANILLA_ORDER) {
            // Discard the constant placeholder before crossing FFM; unordered native has no order argument.
            updateEntity = java.lang.invoke.MethodHandles.dropArguments(updateEntity, 13, long.class);
        }
        invalidateEntityPushabilityCache = linker.downcallHandle(
                library.find("invalidateEntityPushabilityCache")
                        .orElseThrow(() -> missingSymbol("invalidateEntityPushabilityCache")),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)
        );
        invalidatePushEligibilityFields = linker.downcallHandle(
                library.find("invalidatePushEligibilityFields")
                        .orElseThrow(() -> missingSymbol("invalidatePushEligibilityFields")),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)
        );
        query = linker.downcallHandle(
                library.find("queryCollisionEntities").orElseThrow(() -> missingSymbol("queryCollisionEntities")),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT)
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
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)
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
        beginFrame = null;
        setGridSize = null;
        addEntity = null;
        putEntity = removeEntity = updateLocation = null;
        updateEntity = null;
        invalidateEntityPushabilityCache = null;
        invalidatePushEligibilityFields = null;
        query = null;
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
            queryResult.nativePush = nativePushBuffer;
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
                nativePushBuffer = MemorySegment.NULL;
                runIdBuffer = MemorySegment.NULL;
                boundsBuffer = MemorySegment.NULL;
                outputCapacity = 0;
                queryResult.output = MemorySegment.NULL;
                queryResult.nativePush = MemorySegment.NULL;
                CONTEXTS.remove(this);
            }
        }
    }

    public static final class QueryResult {
        private MemorySegment output = MemorySegment.NULL;
        private MemorySegment nativePush = MemorySegment.NULL;
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

        public boolean usesNativePush(int index) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException(index);
            }
            return nativePush.get(JAVA_INT, (long) index * Integer.BYTES) != 0;
        }

        void copyBodiesTo(int[] bodySlots, int[] nativeFlags) {
            if (metadataRequired || offset != 3) throw new IllegalStateException("No resolved push batch");
            if (size == 0) return;
            // Fixed-capacity regions let native fill IDs/slots/flags together, without a second traversal.
            MemorySegment.copy(output, JAVA_INT, (long) bodyOffset * Integer.BYTES, bodySlots, 0, size);
            MemorySegment.copy(nativePush, JAVA_INT, 0, nativeFlags, 0, size);
        }
    }
}
