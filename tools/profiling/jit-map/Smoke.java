public class Smoke {
    static volatile double sink;
    static double hot(int n) {
        double result = 1;
        for (int i = 1; i < n; i++) result += Math.sqrt(i) / (i + result);
        return result;
    }
    public static void main(String[] args) {
        for (int i = 0; i < 30_000; i++) sink = hot(1000);
        System.out.println("JIT_MAP_SMOKE " + sink);
    }
}
