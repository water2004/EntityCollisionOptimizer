import java.io.*;

public class JitMapTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static JitMap parse(String text) throws IOException { return JitMap.read(new StringReader(text)); }
    static void rejects(String text) throws Exception {
        try { parse(text); throw new AssertionError("Accepted malformed map"); } catch (IOException expected) { }
    }
    public static void main(String[] args) throws Exception {
        String fixture = "H\t1\t42\t0\n"
                + "L\t1\t100\t4096\t100\tLOld;.run()V\n"
                + "I\t4100\tLLeaf;.a()V@1\tLOld;.run()V@2\n"
                + "I\t4150\tLLeaf;.b()V@1\tLOld;.run()V@3\nR\t1\t110\n"
                + "U\t200\t4096\nL\t2\t300\t4096\t80\tLNew;.run()V\nR\t2\t310\n"
                + "D\t3\t100\t8192\t100\tstub\nE\t1000\n";
        var map = parse(fixture);
        check(map.find(4095, 150, 0) == null);
        check(map.find(4096, 105, 0) == null);
        check(map.find(4096, 150, 0).owner.contains("Old"));
        check(map.find(4196, 150, 0) == null);
        check(map.find(4096, 205, 0) == null);
        check(map.find(4096, 350, 0).owner.contains("New"));
        check(map.find(4180, 350, 0) == null);
        check(map.find(4096, 195, 10) == null);
        check(map.find(8192, 350, 0).dynamic);
        var code = map.find(4100, 150, 0);
        check(code.scopeNear(4100).methods()[0].contains(".a"));
        check(code.scopeNear(4101).methods()[0].contains(".b"));
        check(code.scopeNear(4151) == null);
        rejects(fixture.replace("E\t1000\n", ""));
        rejects(fixture.replace("R\t1\t110\n", ""));
        rejects(fixture.replace("H\t1\t", "H\t2\t"));
        rejects(fixture + "U\t1100\t4096\n");
        rejects(fixture.replace("U\t200\t4096", "U\t200\t5000"));
        var ambiguous = parse(fixture.replace("E\t1000", "L\t4\t320\t4100\t80\tLOverlap;.run()V\nR\t4\t330\nE\t1000"));
        check(ambiguous.find(4120, 350, 0) == null);
        System.out.println("JIT_MAP_TEST_PASS (range, lifetime, reuse, guard, scopes, malformed input, overlap)");
    }
}
