import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Java 25 source launcher: file.jfr start_epoch_ms end_epoch_ms exact_thread_name. */
public final class JfrWindowSummary {
    private static final class Samples {
        int count;
        final Map<String, Integer> leaves = new HashMap<>();
        final Map<String, Integer> nearest = new HashMap<>();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException(
                "Usage: java tools/profiling/JfrWindowSummary.java file.jfr start_epoch_ms end_epoch_ms exact_thread_name");
        Instant start = Instant.ofEpochMilli(Long.parseLong(args[1]));
        Instant end = Instant.ofEpochMilli(Long.parseLong(args[2]));
        if (!end.isAfter(start)) throw new IllegalArgumentException("End must follow start");
        Map<String, Samples> groups = new LinkedHashMap<>();
        groups.put("jdk.ExecutionSample", new Samples());
        groups.put("jdk.NativeMethodSample", new Samples());
        try (var file = new RecordingFile(Path.of(args[0]))) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                Samples samples = groups.get(event.getEventType().getName());
                if (samples == null || event.getStartTime().isBefore(start) || event.getStartTime().isAfter(end)
                        || event.getThread("sampledThread") == null
                        || !args[3].equals(event.getThread("sampledThread").getJavaName())) continue;
                samples.count++;
                var stack = event.getStackTrace();
                String leaf = "<no stack>", nearest = "<outside ECO>";
                if (stack != null && !stack.getFrames().isEmpty()) {
                    leaf = method(stack.getFrames().getFirst());
                    for (var frame : stack.getFrames()) {
                        if (method(frame).startsWith("org.edtp.entitycollisionoptimizer.")) {
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
        groups.forEach((name, samples) -> {
            System.out.println(name + " samples=" + samples.count);
            print("leaf", samples.leaves, samples.count);
            print("nearest ECO caller", samples.nearest, samples.count);
        });
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
