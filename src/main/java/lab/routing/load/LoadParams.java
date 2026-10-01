package lab.routing.load;

public record LoadParams(Mode mode, int concurrency, int durationSec, double readRatio, int windowMinutes) {

  public enum Mode { BOTH, BROKEN, FIXED }

  public static LoadParams defaults() {
    return new LoadParams(Mode.BOTH, 16, 15, 0.8, 10);
  }

  public LoadParams {
    if (mode == null) mode = Mode.BOTH;
    if (concurrency < 1 || concurrency > 256) throw new IllegalArgumentException("동시 작업자는 1~256");
    if (durationSec < 3 || durationSec > 300) throw new IllegalArgumentException("실행 시간은 3~300초");
    if (readRatio < 0 || readRatio > 1) throw new IllegalArgumentException("읽기 비율은 0~1");
    if (windowMinutes < 1 || windowMinutes > 1440) throw new IllegalArgumentException("집계 구간은 1~1440분");
  }
}
