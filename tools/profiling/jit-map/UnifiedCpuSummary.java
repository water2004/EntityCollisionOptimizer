import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** One ETW SampledProfile denominator; JFR is deliberately not an input. */
public final class UnifiedCpuSummary {
    static String friendly(String method) {
        if (!method.startsWith("L")) return method;
        return method.substring(1).replace(";.", ".").replace('/', '.');
    }
    static String csv(String value) { return '"' + value.replace("\"", "\"\"") + '"'; }
    static void report(Path path, Map<String, Long> rows, long total, int top) throws Exception {
        var sorted = rows.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).toList();
        try (var out = Files.newBufferedWriter(path)) {
            out.write("Samples,ThreadPercent,Name\n");
            for (var row : sorted) out.write(String.format(Locale.ROOT, "%d,%.6f,%s%n", row.getValue(), 100.0 * row.getValue() / total, csv(row.getKey())));
        }
        System.out.println(path.getFileName());
        sorted.stream().limit(top).forEach(row -> System.out.printf(Locale.ROOT, "%7d %7.3f%% %s%n", row.getValue(), 100.0 * row.getValue() / total, row.getKey()));
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 8) throw new IllegalArgumentException("map.tsv samples.csv pid tid etl_start_utc start_epoch_ms end_epoch_ms output_prefix");
        JitMap map;
        try (var in = Files.newBufferedReader(Path.of(args[0]))) { map = JitMap.read(in); }
        long pid = Long.parseLong(args[2]), tid = Long.parseLong(args[3]);
        if (map.pid != pid) throw new IllegalArgumentException("JIT map PID mismatch");
        Instant epoch = Instant.parse(args[4]);
        long originUs = epoch.getEpochSecond() * 1_000_000L + epoch.getNano() / 1000;
        long start = Long.parseLong(args[5]) * 1000, end = Long.parseLong(args[6]) * 1000;
        if (end <= start || start < map.start || end > map.end) throw new IllegalArgumentException("Invalid measurement window");
        var groups = new HashMap<String, Long>();
        var owners = new HashMap<String, Long>();
        var nearLeaves = new HashMap<String, Long>();
        var nearStacks = new HashMap<String, Long>();
        var unknowns = new HashMap<String, Long>();
        long total = 0, recovered = 0, rawUnknown = 0, sensitivity = 0;
        String processTag = "(" + pid + ")";
        try (var input = Files.newBufferedReader(Path.of(args[1]))) {
            String line;
            while ((line = input.readLine()) != null) {
                if (!line.contains("SampledProfile,") || !line.contains(processTag)) continue;
                String[] fields = line.split(",");
                if (Long.parseLong(fields[3].trim()) != tid) continue;
                long time = originUs + Long.parseLong(fields[1].trim());
                if (time < start || time > end) continue;
                long count = Long.parseLong(fields[fields.length - 2].trim());
                long pc = Long.parseUnsignedLong(fields[4].trim().substring(2), 16);
                String nativeName = String.join(",", Arrays.copyOfRange(fields, 7, fields.length - 2)).trim();
                int bang = nativeName.indexOf('!');
                String module = bang < 0 ? nativeName : nativeName.substring(0, bang);
                String owner = nativeName, group = module, near = nativeName, stack = nativeName;
                if (module.equals("\"Unknown\"") || module.equals("Unknown")) {
                    rawUnknown += count;
                    JitMap.Code code = map.find(pc, time, 1000);
                    if (code != map.find(pc, time, 10_000)) sensitivity += count;
                    if (code != null) {
                        recovered += count;
                        group = code.dynamic ? "JVM generated stubs" : "Java JIT compiled";
                        owner = friendly(code.owner);
                        var scope = code.scopeNear(pc);
                        near = scope == null ? owner : friendly(scope.methods()[0]);
                        stack = scope == null ? owner : String.join(" <- ", Arrays.stream(scope.methods()).map(UnifiedCpuSummary::friendly).toList());
                    } else {
                        group = "Unresolved";
                        unknowns.merge(fields[4].trim(), count, Long::sum);
                    }
                }
                total += count;
                groups.merge(group, count, Long::sum);
                owners.merge(owner, count, Long::sum);
                nearLeaves.merge(near, count, Long::sum);
                nearStacks.merge(stack, count, Long::sum);
            }
        }
        if (total == 0) throw new IllegalArgumentException("No samples in selected PID/TID/window");
        System.out.printf("PID=%d TID=%d samples=%d rawUnknown=%d recovered=%d remaining=%d guard1msVs10ms=%d codeRecords=%d%n",
                pid, tid, total, rawUnknown, recovered, rawUnknown - recovered, sensitivity, map.codes.size());
        report(Path.of(args[7] + "-groups.csv"), groups, total, 30);
        report(Path.of(args[7] + "-owners.csv"), owners, total, 40);
        // scopeNear is a diagnostic view, NOT a byte-exact attribution for every instruction.
        report(Path.of(args[7] + "-near-leaves.csv"), nearLeaves, total, 30);
        report(Path.of(args[7] + "-near-stacks.csv"), nearStacks, total, 0);
        report(Path.of(args[7] + "-unresolved.csv"), unknowns, total, 10);
    }
}
