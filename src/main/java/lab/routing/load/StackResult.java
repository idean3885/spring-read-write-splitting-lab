package lab.routing.load;

import java.util.Arrays;
import java.util.List;
import lab.routing.load.ServerProbe.Counts;

public record StackResult(
    String stack, long elapsedMs, long reads, long writes, long errors, String firstError,
    double opsPerSec, Latency readLatency, Latency writeLatency,
    long appReadsOnSource, long appReadsOnReplica,
    Counts source, Counts replica, long maxLagSec, List<Second> timeline) {

  public static final double[] CURVE_PERCENTILES = {0, 50, 75, 90, 95, 99, 99.5, 99.9};

  public double replicaShareOfReads() {
    long total = source.selects() + replica.selects();
    return total == 0 ? 0 : (double) replica.selects() / total;
  }

  public String verdict() {
    if (reads == 0) return "조회 없음";
    double share = replicaShareOfReads();
    if (share == 0) return "소스 하나가 조회와 쓰기를 모두 받았습니다";
    if (share == 1 && replica.inserts() == 0) return "조회는 레플리카, 쓰기는 소스가 받았습니다";
    return "조회 일부만 레플리카가 받았습니다 (레플리카 비율 %.1f%%)".formatted(share * 100);
  }

  public record Latency(double p50, double p95, double p99, double max, double[] curve) {
    public static final Latency NONE = new Latency(0, 0, 0, 0, new double[CURVE_PERCENTILES.length]);

    static Latency of(long[] nanos, int n) {
      if (n == 0) return NONE;
      Arrays.sort(nanos, 0, n);
      double[] curve = Arrays.stream(CURVE_PERCENTILES).map(p -> ms(at(nanos, n, p))).toArray();
      return new Latency(ms(at(nanos, n, 50)), ms(at(nanos, n, 95)), ms(at(nanos, n, 99)), ms(nanos[n - 1]), curve);
    }

    private static long at(long[] sorted, int n, double percentile) {
      return sorted[Math.min(n - 1, (int) (n * percentile / 100))];
    }

    private static double ms(long nanos) { return Math.round(nanos / 10_000.0) / 100.0; }
  }

  public record Second(int sec, int ops, double writeP99) {}
}
