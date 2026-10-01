package lab.routing.load;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lab.routing.datasource.LabDbProperties;
import lab.routing.load.LoadParams.Mode;
import lab.routing.usage.BrokenUsageOps;
import lab.routing.usage.FixedUsageOps;
import lab.routing.usage.UsageOps;
import org.springframework.stereotype.Component;

@Component
public class LoadRunner {

  private final BrokenUsageOps broken;
  private final FixedUsageOps fixed;
  private final ServerProbe probe;
  private final LabDbProperties db;
  private final ExecutorService runnerThread = Executors.newSingleThreadExecutor();
  private final AtomicReference<Progress> progress = new AtomicReference<>(Progress.idle());
  private final List<Run> runs = new CopyOnWriteArrayList<>();

  public LoadRunner(BrokenUsageOps broken, FixedUsageOps fixed, ServerProbe probe, LabDbProperties db) {
    this.broken = broken;
    this.fixed = fixed;
    this.probe = probe;
    this.db = db;
  }

  public synchronized boolean start(LoadParams params) {
    if (progress.get().running()) return false;
    progress.set(new Progress(true, "준비", 0, params.durationSec(), 0, null));
    runnerThread.submit(() -> {
      try {
        var results = new ArrayList<StackResult>();
        if (params.mode() != Mode.FIXED) results.add(runStack("broken", broken, "sample_broken", params));
        if (params.mode() != Mode.BROKEN) results.add(runStack("fixed", fixed, "sample_fixed", params));
        runs.add(0, new Run(runs.size() + 1, LocalDateTime.now(), params, db.poolSize(), results));
        progress.set(Progress.idle());
      } catch (Exception e) {
        progress.set(new Progress(false, "실패", 0, 0, 0, e.getMessage()));
      }
    });
    return true;
  }

  public StackResult runStack(String name, UsageOps ops, String schema, LoadParams params) throws InterruptedException {
    var srcBefore = probe.counts(db.source(), schema);
    var repBefore = probe.counts(db.replica(), schema);
    var ops$ = new AtomicLong();
    var maxLag = new AtomicLong(-1);
    long start = System.nanoTime();
    long deadline = start + params.durationSec() * 1_000_000_000L;

    var pool = Executors.newFixedThreadPool(params.concurrency());
    var workers = new ArrayList<Future<Worker>>();
    for (int i = 0; i < params.concurrency(); i++) {
      workers.add(pool.submit(() -> new Worker().loop(ops, params, deadline, ops$)));
    }
    while (System.nanoTime() < deadline) {      maxLag.accumulateAndGet(probe.replicaLagSeconds(), Math::max);
      long elapsed = (System.nanoTime() - start) / 1_000_000_000L;
      progress.set(new Progress(true, name, elapsed, params.durationSec(), ops$.get(), null));
      Thread.sleep(500);
    }
    pool.shutdown();
    pool.awaitTermination(60, TimeUnit.SECONDS);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    var merged = new Worker();
    for (var f : workers) {
      try { merged.absorb(f.get()); } catch (ExecutionException e) { merged.fail(e.getCause()); }
    }
    var src = probe.counts(db.source(), schema).minus(srcBefore);
    var rep = probe.counts(db.replica(), schema).minus(repBefore);
    long total = merged.reads + merged.writes;
    return new StackResult(name, elapsedMs, merged.reads, merged.writes, merged.errors, merged.firstError,
        Math.round(total * 10_000.0 / elapsedMs) / 10.0,
        StackResult.Latency.of(merged.readNanos, merged.readN), StackResult.Latency.of(merged.writeNanos, merged.writeN),
        merged.onSource, merged.onReplica, src, rep, maxLag.get());
  }

  public Progress progress() { return progress.get(); }

  public List<Run> runs() { return runs; }

  public record Progress(boolean running, String stage, long elapsedSec, long totalSec, long ops, String error) {
    static Progress idle() { return new Progress(false, "대기", 0, 0, 0, null); }
  }

  public record Run(int id, LocalDateTime finishedAt, LoadParams params, int poolSize, List<StackResult> results) {}

  // why: 지연 시간은 작업자마다 자기 배열에만 쓰고 끝난 뒤 합친다. 공유 자료구조에 쓰면 측정이 경합을 잰다
  static final class Worker {
    long reads, writes, errors, onSource, onReplica;
    String firstError;
    long[] readNanos = new long[1024], writeNanos = new long[256];
    int readN, writeN;

    Worker loop(UsageOps ops, LoadParams p, long deadline, AtomicLong counter) {
      var rnd = ThreadLocalRandom.current();
      while (System.nanoTime() < deadline) {
        long t0 = System.nanoTime();
        try {
          if (rnd.nextDouble() < p.readRatio()) {
            var r = ops.aggregateRecent(p.windowMinutes());
            if (r.serverId() == UsageOps.SOURCE_ID) onSource++; else onReplica++;
            readNanos = put(readNanos, readN++, System.nanoTime() - t0);
            reads++;
          } else {
            ops.collect();
            writeNanos = put(writeNanos, writeN++, System.nanoTime() - t0);
            writes++;
          }
        } catch (RuntimeException e) {
          errors++;
          if (firstError == null) firstError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        counter.incrementAndGet();
      }
      return this;
    }

    void absorb(Worker w) {
      reads += w.reads; writes += w.writes; errors += w.errors; onSource += w.onSource; onReplica += w.onReplica;
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
