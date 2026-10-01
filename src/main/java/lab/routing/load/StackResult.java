package lab.routing.load;

import lab.routing.load.ServerProbe.Counts;

public record StackResult(
    String stack, long elapsedMs, long reads, long writes, long errors, String firstError,
    double opsPerSec, Latency readLatency, Latency writeLatency,
    long appReadsOnSource, long appReadsOnReplica,
    Counts source, Counts replica, long maxLagSec) {

  public double replicaShareOfReads() {
    long total = source.selects() + replica.selects();
    return total == 0 ? 0 : (double) replica.selects() / total;
  }

  public String verdict() {
    if (reads == 0) return "조회 없음";
    double share = replicaShareOfReads();
    if (share == 0) return "읽기 분리가 동작하지 않는다. 집계 조회가 전부 소스로 갔다";
    if (share == 1 && replica.inserts() == 0) return "읽기 분리가 동작한다. 집계 조회는 레플리카, 쓰기는 소스";
    return "부분 분리 (레플리카 비율 %.1f%%)".formatted(share * 100);
  }

  public record Latency(double p50, double p95, double p99, double max) {
    static Latency of(long[] nanos, int n) {
      if (n == 0) return new Latency(0, 0, 0, 0);
      java.util.Arrays.sort(nanos, 0, n);
      return new Latency(ms(nanos[(int) (n * 0.50)]), ms(nanos[Math.min(n - 1, (int) (n * 0.95))]),
          ms(nanos[Math.min(n - 1, (int) (n * 0.99))]), ms(nanos[n - 1]));
    }

    private static double ms(long nanos) { return Math.round(nanos / 10_000.0) / 100.0; }
  }
}
