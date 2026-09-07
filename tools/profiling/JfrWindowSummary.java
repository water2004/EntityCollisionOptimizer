import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/** Java 25 source launcher: file.jfr start_epoch_ms end_epoch_ms exact_thread_name. */
public final class JfrWindowSummary {
    private static final class Samples {
        int count;
        final Map<String, Integer> leaves = new HashMap<>();
        final Map<String, Integer> leafLines = new HashMap<>();
        final Map<String, Integer> nearest = new HashMap<>();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException(
                "Usage: java tools/profiling/JfrWindowSummary.java file.jfr start_epoch_ms end_epoch_ms exact_thread_name");
        Instant start = Instant.ofEpochMilli(Long.parseLong(args[1]));
        Instant end = Instant.ofEpochMilli(Long.parseLong(args[2]));
        if (!end.isAfter(start)) throw new IllegalArgumentException("End must follow start");
        Map<String, Samples> groups = new LinkedHashMap<>();
        Map<String, Long> allocationWeights = new HashMap<>();
        Map<String, Long> vectorAllocations = new HashMap<>();
        var osThreadIds = new TreeSet<Long>();
        groups.put("jdk.ExecutionSample", new Samples());
        groups.put("jdk.NativeMethodSample", new Samples());
        try (var file = new RecordingFile(Path.of(args[0]))) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                if (event.getStartTime().isBefore(start) || event.getStartTime().isAfter(end)) continue;
                if (event.getEventType().getName().equals("jdk.ObjectAllocationSample")
                        && event.getThread() != null && args[3].equals(event.getThread().getJavaName())) {
                    allocationWeights.merge(event.getClass("objectClass").getName(), event.getLong("weight"), Long::sum);
                    if (event.getClass("objectClass").getName().equals("net.minecraft.world.phys.Vec3") && event.getStackTrace() != null) {
                        String stack = event.getStackTrace().getFrames().stream().limit(8)
                                .map(frame -> method(frame) + ":" + frame.getLineNumber())
                                .collect(java.util.stream.Collectors.joining(" <- "));
                        vectorAllocations.merge(stack, event.getLong("weight"), Long::sum);
                    }
                    continue;
                }
                Samples samples = groups.get(event.getEventType().getName());
                if (samples == null || event.getThread("sampledThread") == null
                        || !args[3].equals(event.getThread("sampledThread").getJavaName())) continue;
                samples.count++;
                osThreadIds.add(event.getThread("sampledThread").getOSThreadId());
                var stack = event.getStackTrace();
                String leaf = "<no stack>", nearest = "<outside ECO>";
                if (stack != null && !stack.getFrames().isEmpty()) {
                    leaf = method(stack.getFrames().getFirst());
                    samples.leafLines.merge(leaf + ":" + stack.getFrames().getFirst().getLineNumber(), 1, Integer::sum);
                    for (var frame : stack.getFrames()) {
                        if (method(frame).startsWith("org.edtp.entitycollisionoptimizer.")
                                || frame.getMethod().getName().startsWith("eco$")
                                || frame.getMethod().getName().startsWith("entityCollisionOptimizer$")) {
                            nearest = method(frame);
                            break;
                        }
                    }
                    if (nearest.equals("<outside ECO>") && stack.isTruncated()) nearest = "<outside ECO / truncated>";
                }
                samples.leaves.merge(leaf, 1, Integer::sum);
                samples.nearest.merge(nearest, 1, Integer::sum);
            }
        }
        System.out.println("OS thread IDs in the selected window: " + osThreadIds);
        groups.forEach((name, samples) -> {
            System.out.println(name + " samples=" + samples.count);
            print("leaf", samples.leaves, samples.count);
            print("leaf source line (JIT attribution, not instruction timing)", samples.leafLines, samples.count);
            print("nearest ECO caller", samples.nearest, samples.count);
            System.out.println("body boundary leaves (same event denominator, including zero counts)");
            for (String focus : new String[]{"jdk.internal.foreign.MemorySessionImpl.checkValidStateRaw",
                    "net.minecraft.world.entity.Entity.eco$publishBody",
                    "net.minecraft.world.entity.Entity.eco$synchronizeBody",
                    "net.minecraft.world.entity.Entity.eco$bindBody",
                    "net.minecraft.world.entity.Entity.eco$writePosition",
                    "net.minecraft.world.entity.Entity.eco$readVelocity",
                    "net.minecraft.world.entity.Entity.eco$writeVelocity",
                    "net.minecraft.world.entity.Entity.eco$readNeedsSync",
                    "net.minecraft.world.entity.Entity.eco$writeNeedsSync",
                    "net.minecraft.world.entity.Entity.entityCollisionOptimizer$pushState",
                    "org.edtp.entitycollisionoptimizer.natives.CollisionStateTable.velocity",
                    "org.edtp.entitycollisionoptimizer.natives.CollisionStateTable.position",
                    "org.edtp.entitycollisionoptimizer.natives.CollisionStateTable.slot",
                    "org.edtp.entitycollisionoptimizer.natives.CollisionStateTable.invalidateVelocity",
                    "org.edtp.entitycollisionoptimizer.natives.CollisionPushStates.refresh",
                    "org.edtp.entitycollisionoptimizer.natives.PushBatch.applyNativeRun"}) {
                int count = samples.leaves.getOrDefault(focus, 0);
                System.out.printf(Locale.ROOT, "%5d %6.2f%% %s%n", count,
                        samples.count == 0 ? 0.0 : 100.0 * count / samples.count, focus);
            }
        });
        long allocationTotal = allocationWeights.values().stream().mapToLong(Long::longValue).sum();
        System.out.printf(Locale.ROOT, "Allocation sample weights: %.2f MiB (estimates, not exact allocated bytes)%n",
                allocationTotal / 1048576.0);
        allocationWeights.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(20).forEach(entry -> System.out.printf(Locale.ROOT, "%10.2f MiB %s%n",
                        entry.getValue() / 1048576.0, entry.getKey()));
        System.out.println("Vec3 allocation weights by sampled stack (estimates; absent samples are not proof of no allocation)");
        vectorAllocations.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(15)
                .forEach(entry -> System.out.printf(Locale.ROOT, "%10.2f MiB %s%n", entry.getValue() / 1048576.0, entry.getKey()));
        System.out.println("Percentages are separate event-sample distributions, not CPU time; do not merge event types.");
    }

    private static String method(RecordedFrame frame) {
        return frame.getMethod().getType().getName() + "." + frame.getMethod().getName();
    }

    private static void print(String heading, Map<String, Integer> counts, int total) {
        System.out.println(heading);
        counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(12).forEach(entry -> System.out.printf(Locale.ROOT, "%5d %6.2f%% %s%n",
                        entry.getValue(), 100.0 * entry.getValue() / total, entry.getKey()));
    }
}
