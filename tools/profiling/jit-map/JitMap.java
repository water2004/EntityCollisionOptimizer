import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.*;

/** Address/lifetime lookup. Inline scopes are sparse debug metadata, not exact instruction ownership. */
final class JitMap {
    record Scope(long pc, String[] methods) {}
    static final class Code {
        long id, receipt, ready, address, size, end = Long.MAX_VALUE;
        boolean dynamic;
        String owner;
        final NavigableMap<Long, Scope> scopes = new TreeMap<>();
        boolean contains(long pc) { return pc >= address && pc - address < size; }
        Scope scopeNear(long pc) {
            var entry = scopes.ceilingEntry(pc);
            return entry == null ? null : entry.getValue();
        }
    }
    final long pid, start, end;
    final List<Code> codes;
    private final Map<Long, List<Code>> pages = new HashMap<>();

    private JitMap(long pid, long start, long end, List<Code> codes) {
        this.pid = pid; this.start = start; this.end = end; this.codes = codes;
        for (Code c : codes) {
            for (long page = c.address >>> 12; page <= (c.address + c.size - 1) >>> 12; page++) {
                pages.computeIfAbsent(page, ignored -> new ArrayList<>()).add(c);
            }
        }
    }

    static JitMap read(Reader input) throws IOException {
        var reader = input instanceof BufferedReader br ? br : new BufferedReader(input);
        String header = reader.readLine();
        if (header == null) throw new IOException("Empty JIT map");
        String[] h = header.split("\t", -1);
        if (h.length != 4 || !h[0].equals("H") || !h[1].equals("1")) throw new IOException("Invalid JIT map header/version");
        long pid = Long.parseLong(h[2]), start = Long.parseLong(h[3]), end = -1;
        var codes = new ArrayList<Code>();
        var unloads = new ArrayList<long[]>();
        var strings = new HashMap<String, String>();
        var ids = new HashSet<Long>();
        Code pending = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (end != -1) throw new IOException("Data follows VMDeath");
            String[] f = line.split("\t", -1);
            switch (f[0]) {
                case "L", "D" -> {
                    if (pending != null || f.length != 6) throw new IOException("Invalid load transaction");
                    var c = new Code();
                    c.id = Long.parseLong(f[1]);
                    c.receipt = c.ready = Long.parseLong(f[2]);
                    c.address = Long.parseLong(f[3]);
                    c.size = Long.parseLong(f[4]);
                    if (!ids.add(c.id) || c.size <= 0 || c.address < 0 || c.size > Long.MAX_VALUE - c.address)
                        throw new IOException("Invalid code range/id");
                    c.owner = strings.computeIfAbsent(f[5], key -> key);
                    c.dynamic = f[0].equals("D");
                    codes.add(c);
                    if (!c.dynamic) pending = c;
                }
                case "I" -> {
                    if (pending == null || f.length < 3) throw new IOException("Inline scope outside load");
                    long pc = Long.parseLong(f[1]);
                    var methods = new String[f.length - 2];
                    for (int i = 2; i < f.length; i++) {
                        int bci = f[i].lastIndexOf('@');
                        if (bci < 0) throw new IOException("Inline BCI missing");
                        String method = f[i].substring(0, bci);
                        methods[i - 2] = strings.computeIfAbsent(method, key -> key);
                    }
                    pending.scopes.put(pc, new Scope(pc, methods));
                }
                case "R" -> {
                    if (pending == null || f.length != 3 || pending.id != Long.parseLong(f[1]))
                        throw new IOException("Invalid load completion");
                    pending.ready = Long.parseLong(f[2]);
                    if (pending.ready < pending.receipt) throw new IOException("Clock moved backwards");
                    pending = null;
                }
                case "U" -> {
                    if (pending != null || f.length != 3) throw new IOException("Invalid unload");
                    unloads.add(new long[]{Long.parseLong(f[1]), Long.parseLong(f[2])});
                }
                case "E" -> {
                    if (pending != null || f.length != 2) throw new IOException("Incomplete load at VMDeath");
                    end = Long.parseLong(f[1]);
                }
                default -> throw new IOException("Unknown JIT map record: " + f[0]);
            }
        }
        if (pending != null || end < start) throw new IOException("Truncated JIT map (VMDeath missing)");
        var byAddress = new HashMap<Long, NavigableMap<Long, Code>>();
        for (Code c : codes) {
            if (c.receipt < start || c.ready > end) throw new IOException("Code event outside JVM lifetime");
            if (!c.dynamic) byAddress.computeIfAbsent(c.address, ignored -> new TreeMap<>()).put(c.receipt, c);
        }
        for (long[] u : unloads) {
            var history = byAddress.get(u[1]);
            var entry = history == null ? null : history.floorEntry(u[0]);
            if (entry == null || entry.getValue().end != Long.MAX_VALUE) throw new IOException("Unmatched/duplicate unload");
            entry.getValue().end = u[0];
        }
        return new JitMap(pid, start, end, codes);
    }

    Code find(long pc, long time, long guardUs) {
        if (time < start || time >= end) return null;
        Code compiled = null, stub = null;
        boolean ambiguous = false, boundary = false;
        for (Code c : pages.getOrDefault(pc >>> 12, List.of())) {
            if (!c.contains(pc) || time < c.receipt || time >= c.end) continue;
            if (time < c.ready + guardUs || c.end - time <= guardUs) { boundary = true; continue; }
            if (!c.dynamic) {
                if (compiled != null) ambiguous = true;
                compiled = c;
            } else if (stub == null || c.size < stub.size) {
                stub = c;
            } else if (c.size == stub.size && !c.owner.equals(stub.owner)) {
                // No dynamic-unload event exists: do not guess for reused stub addresses.
                ambiguous = true;
            }
        }
        return ambiguous || boundary ? null : compiled != null ? compiled : stub;
    }
}
