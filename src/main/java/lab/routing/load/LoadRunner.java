package lab.routing.load;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lab.routing.datasource.LabDbProperties;
import lab.routing.load.LoadParams.Mode;
import lab.routing.usage.SingleUsageOps;
import lab.routing.usage.SplitUsageOps;
import lab.routing.usage.UsageOps;
import org.springframework.stereotype.Component;

@Component
public class LoadRunner {

  public static final int WARMUP_SEC = 5;

  private final SingleUsageOps single;
  private final SplitUsageOps split;
  private final ServerProbe probe;
  private final LabDbProperties db;
  private final ExecutorService runnerThread = Executors.newSingleThreadExecutor();
  private final AtomicReference<Progress> progress = new AtomicReference<>(Progress.idle());
  private final List<Run> runs = new CopyOnWriteArrayList<>();

  public LoadRunner(SingleUsageOps single, SplitUsageOps split, ServerProbe probe, LabDbProperties db) {
    this.single = single;
    this.split = split;
    this.probe = probe;
    this.db = db;
  }

  public synchronized boolean start(LoadParams params) {
    if (progress.get().running()) return false;
    progress.set(new Progress(true, "준비", 0, params.durationSec(), 0, null));
    runnerThread.submit(() -> {
      try {
        var results = new ArrayList<StackResult>();
        if (params.mode() != Mode.SPLIT) results.add(runStack("single", single, "sample_single", params));
        if (params.mode() != Mode.SINGLE) results.add(runStack("split", split, "sample_split", params));
        runs.add(0, new Run(runs.size() + 1, LocalDateTime.now(), params, db.poolSize(), results, new ConcurrentHashMap<>()));
        progress.set(Progress.idle());
      } catch (Exception e) {
        progress.set(new Progress(false, "실패", 0, 0, 0, e.getMessage()));
      }
    });
    return true;
  }

  public StackResult runStack(String name, UsageOps ops, String schema, LoadParams params) throws InterruptedException {
    return runStack(name, ops, schema, params, WARMUP_SEC);
  }

  public StackResult runStack(String name, UsageOps ops, String schema, LoadParams params, int warmupSec) throws InterruptedException {
    warmUp(name, ops, params, warmupSec);
    probe.resetHistory(db.source());
    probe.resetHistory(db.replica());
    var srcBefore = probe.counts(db.source(), schema);
    var repBefore = probe.counts(db.replica(), schema);
    var ops$ = new AtomicLong();
    var maxLag = new AtomicLong(-1);
    long start = System.nanoTime();
    long deadline = start + params.durationSec() * 1_000_000_000L;

    var pool = Executors.newFixedThreadPool(params.concurrency());
    var workers = new ArrayList<Future<Worker>>();
    for (int i = 0; i < params.concurrency(); i++) {
      workers.add(pool.submit(() -> new Worker(params.durationSec()).loop(ops, params, start, deadline, ops$)));
    }
    while (System.nanoTime() < deadline) {
      maxLag.accumulateAndGet(probe.replicaLagSeconds(), Math::max);
      long elapsed = (System.nanoTime() - start) / 1_000_000_000L;
      progress.set(new Progress(true, name, elapsed, params.durationSec(), ops$.get(), null));
      Thread.sleep(500);
    }
    pool.shutdown();
    pool.awaitTermination(60, TimeUnit.SECONDS);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    var merged = new Worker(params.durationSec());
    for (var f : workers) {
      try { merged.absorb(f.get()); } catch (ExecutionException e) { merged.fail(e.getCause()); }
    }
    var src = probe.counts(db.source(), schema).minus(srcBefore);
    var rep = probe.counts(db.replica(), schema).minus(repBefore);
    long total = merged.reads + merged.writes;
    // why: Latency.of 가 지연 배열을 제자리 정렬한다. 초 단위 기록은 정렬 전 순서에 기대므로 먼저 만든다
    var timeline = merged.timeline();
    return new StackResult(name, elapsedMs, merged.reads, merged.writes, merged.errors, merged.firstError,
        Math.round(total * 10_000.0 / elapsedMs) / 10.0,
        StackResult.Latency.of(merged.readNanos, merged.readN), StackResult.Latency.of(merged.writeNanos, merged.writeN),
        merged.onSource, merged.onReplica, src, rep, maxLag.get(), timeline);
  }

  // why: 먼저 실행되는 구성만 JVM · 커넥션 풀 준비 비용을 떠안으면 비교가 기운다. 구성마다 같은 시간 부하를 걸고 버린다
  private void warmUp(String name, UsageOps ops, LoadParams params, int warmupSec) throws InterruptedException {
    if (warmupSec <= 0) return;
    progress.set(new Progress(true, name + " 준비", 0, warmupSec, 0, null));
    long start = System.nanoTime();
    long deadline = start + warmupSec * 1_000_000_000L;
    var pool = Executors.newFixedThreadPool(params.concurrency());
    for (int i = 0; i < params.concurrency(); i++) {
      pool.submit(() -> new Worker(warmupSec).loop(ops, params, start, deadline, new AtomicLong()));
    }
    pool.shutdown();
    if (!pool.awaitTermination(warmupSec + 60L, TimeUnit.SECONDS)) {
      pool.shutdownNow();
      throw new IllegalStateException("준비 부하가 제시간에 끝나지 않아 측정을 시작하지 않습니다.");
    }
  }

  public Progress progress() { return progress.get(); }

  public List<Run> runs() { return runs; }

  public boolean attachCpu(int runId, Map<String, Cpu> cpu) {
    var run = runs.stream().filter(r -> r.id() == runId).findFirst();
    run.ifPresent(r -> r.cpu().putAll(cpu));
    return run.isPresent();
  }

  public record Progress(boolean running, String stage, long elapsedSec, long totalSec, long ops, String error) {
    static Progress idle() { return new Progress(false, "대기", 0, 0, 0, null); }
  }

  public record Run(int id, LocalDateTime finishedAt, LoadParams params, int poolSize, List<StackResult> results,
                    Map<String, Cpu> cpu) {}

  public record Cpu(double source, double replica, int samples) {}

  // why: 지연 시간은 작업자마다 자기 배열에만 쓰고 끝난 뒤 합친다. 공유 자료구조에 쓰면 측정이 경합을 잰다
  static final class Worker {
    long reads, writes, errors, onSource, onReplica;
    String firstError;
    long[] readNanos = new long[1024], writeNanos = new long[256], writeAtSec = new long[256];
    int readN, writeN;
    final int[] opsPerSec;

    Worker(int durationSec) { opsPerSec = new int[durationSec + 1]; }

    Worker loop(UsageOps ops, LoadParams p, long start, long deadline, AtomicLong counter) {
      var rnd = ThreadLocalRandom.current();
      while (System.nanoTime() < deadline) {
        long t0 = System.nanoTime();
        try {
          if (rnd.nextDouble() < p.readRatio()) {
            var r = ops.aggregateRecent(p.scanRows());
            if (r.serverId() == UsageOps.SOURCE_ID) onSource++; else onReplica++;
            readNanos = put(readNanos, readN++, System.nanoTime() - t0);
            reads++;
          } else {
            ops.collect();
            writeAtSec = put(writeAtSec, writeN, secondOf(t0, start));
            writeNanos = put(writeNanos, writeN++, System.nanoTime() - t0);
            writes++;
          }
        } catch (RuntimeException e) {
          errors++;
          if (firstError == null) firstError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        opsPerSec[secondOf(t0, start)]++;
        counter.incrementAndGet();
      }
      return this;
    }

    private int secondOf(long t, long start) {
      return (int) Math.min(opsPerSec.length - 1, (t - start) / 1_000_000_000L);
    }

    List<StackResult.Second> timeline() {
      var perSec = new ArrayList<StackResult.Second>();
      for (int sec = 0; sec < opsPerSec.length - 1; sec++) {
        final int s = sec;
        long[] lat = java.util.stream.IntStream.range(0, writeN).filter(i -> writeAtSec[i] == s)
            .mapToLong(i -> writeNanos[i]).sorted().toArray();
        double p99 = lat.length == 0 ? 0 : Math.round(lat[Math.min(lat.length - 1, (int) (lat.length * 0.99))] / 10_000.0) / 100.0;
        perSec.add(new StackResult.Second(sec, opsPerSec[sec], p99));
      }
      return perSec;
    }

    void absorb(Worker w) {
      reads += w.reads; writes += w.writes; errors += w.errors; onSource += w.onSource; onReplica += w.onReplica;
      for (int i = 0; i < opsPerSec.length; i++) opsPerSec[i] += w.opsPerSec[i];
      writeAtSec = concat(writeAtSec, writeN, w.writeAtSec, w.writeN);
      if (firstError == null) firstError = w.firstError;
      readNanos = concat(readNanos, readN, w.readNanos, w.readN); readN += w.readN;
      writeNanos = concat(writeNanos, writeN, w.writeNanos, w.writeN); writeN += w.writeN;
    }

    void fail(Throwable t) {
      errors++;
      if (firstError == null) firstError = String.valueOf(t);
    }

    private static long[] put(long[] a, int i, long v) {
      if (i == a.length) a = Arrays.copyOf(a, a.length * 2);
      a[i] = v;
      return a;
    }

    private static long[] concat(long[] a, int an, long[] b, int bn) {
      long[] r = Arrays.copyOf(a, Math.max(an + bn, 1));
      System.arraycopy(b, 0, r, an, bn);
      return r;
    }
  }
}
