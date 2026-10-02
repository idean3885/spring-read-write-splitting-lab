package lab.routing.web;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lab.routing.load.LoadRunner.Cpu;
import lab.routing.load.LoadRunner.Run;
import lab.routing.load.StackResult;
import lab.routing.load.StackResult.Latency;

final class ReportHtml {

  private ReportHtml() {}

  static String render(Run run) {
    var p = run.params();
    var sb = new StringBuilder();
    sb.append("""
        <!doctype html><html lang="ko"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>읽기 분리 PoC 보고서 #%d</title><style>%s</style></head><body><main>
        <h1>읽기 분리 PoC 보고서 <span class="muted">#%d · %s</span></h1>
        %s
        <h2>1. 목적</h2>
        <p><b>Spring · JPA 애플리케이션에서 읽기 분리를 어떻게 구성하는지, 구성하면 무엇이 달라지는지 확인합니다.</b><br>
        같은 부하를 읽기 분리가 없는 구성(single)과 있는 구성(split)에 걸고, 각 DB 가 실제로 받은 쿼리 수와 지연을 비교합니다.</p>
        <table><thead><tr><th>성공 기준 (실행 전에 정함)</th><th>single</th><th>split</th></tr></thead><tbody>
        <tr><th>집계 조회가 레플리카에 도착한 비율</th><td>0%% (기준선: 소스가 모두 받습니다)</td><td>100%% (조회는 모두 레플리카로)</td></tr>
        <tr><th>쓰기가 레플리카에 도착한 건수 · 오류</th><td>0 · 0</td><td>0 · 0</td></tr></tbody></table>

        <h2>2. 조건</h2>
        <h3>알아야 할 용어</h3>
        <table><tbody>
        <tr><th>소스</th><td>쓰기를 받는 원본 DB. 이 실험의 MySQL 1번 서버(server_id 1)</td></tr>
        <tr><th>레플리카</th><td>소스의 변경을 복제받는 사본 DB. 읽기만 허용합니다(read_only). MySQL 2번 서버(server_id 2)</td></tr>
        <tr><th>읽기 분리</th><td>조회는 레플리카, 쓰기는 소스로 보내 소스의 부하를 나누는 구성</td></tr>
        <tr><th>readOnly 트랜잭션</th><td>Spring 의 <code>@Transactional(readOnly = true)</code>. 이 표시를 보고 레플리카로 보냅니다</td></tr>
        <tr><th>라우팅 데이터소스</th><td>Spring 의 <code>AbstractRoutingDataSource</code>. 커넥션을 달라는 요청을 받는 순간, 현재 트랜잭션이 readOnly 인지 보고 소스 · 레플리카 중 하나에서 커넥션을 꺼냅니다</td></tr>
        <tr><th>지연 커넥션 프록시</th><td>Spring 의 <code>LazyConnectionDataSourceProxy</code>. 커넥션 요청을 받으면 대리 객체를 먼저 돌려주고, 첫 쿼리를 실행할 때 진짜 커넥션을 꺼냅니다</td></tr>
        </tbody></table>

        <h3>비교하는 두 구성</h3>
        <table><thead><tr><th></th><th>single</th><th>split</th></tr></thead><tbody>
        <tr><th>JPA 에 연결한 데이터소스</th><td>소스 커넥션 풀 하나</td><td>소스 · 레플리카 두 풀을 라우팅 데이터소스로 묶고, 지연 커넥션 프록시로 감싸서 연결</td></tr>
        <tr><th>조회 (readOnly)</th><td>소스</td><td>레플리카</td></tr>
        <tr><th>쓰기</th><td>소스</td><td>소스</td></tr>
        <tr><th>공통 환경</th><td colspan="2">MySQL 8.0 소스 1대 · 레플리카 1대 (GTID 비동기 복제, ROW 형식, 레플리카 read_only)<br>Spring Boot 3.5 · JpaTransactionManager · HikariCP 커넥션 풀 서버당 %d개</td></tr>
        </tbody></table>

        <h2>3. 절차</h2>
        <table><tbody>
        <tr><th>부하</th><td>동시 작업자 %d개가 구성당 %d초 동안 쉬지 않고 연산을 보냅니다.<br>single 을 먼저, split 을 다음에 실행합니다. 구성마다 측정 전 %d초 준비 부하를 걸고 버립니다(JVM · 커넥션 풀 준비)</td></tr>
        <tr><th>연산</th><td>집계 조회 %.0f%%: readOnly 트랜잭션으로 최근 %,d건 사용량의 건수 · 합계를 조회합니다<br>원천 적재 %.0f%%: 쓰기 트랜잭션으로 사용량 1행을 넣습니다</td></tr>
        <tr><th>판정 지표</th><td>도착 서버: 각 MySQL 의 문장 통계(<code>performance_schema.events_statements_summary_by_digest</code>)를 실행 전후로 뺀 값<br>복제는 ROW 형식이라 레플리카가 복제로 적용한 변경은 문장으로 잡히지 않습니다<br>그래서 레플리카에 잡힌 SELECT 는 앱이 보낸 것입니다</td></tr>
        <tr><th>교차 확인</th><td>조회 트랜잭션 안에서 받은 <code>@@server_id</code> (앱이 본 서버 번호)</td></tr>
        <tr><th>부가 지표</th><td>처리량 · 지연 p50 · p95 · p99 · max · 오류 · 복제 지연(0.5초마다 확인)</td></tr>
        </tbody></table>

        <h2>4. 결과</h2>
        """.formatted(run.id(), CSS, run.id(), run.finishedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), tiles(run),
        run.poolSize(), p.concurrency(), p.durationSec(), lab.routing.load.LoadRunner.WARMUP_SEC, p.readRatio() * 100, p.scanRows(), (1 - p.readRatio()) * 100));

    sb.append("<section class=\"cards\">");
    for (var r : run.results()) sb.append(card(r));
    sb.append("</section>");

    sb.append("<h3>성공 기준 대조</h3><table><thead><tr><th></th><th>기준</th><th>실측</th><th>판정</th></tr></thead><tbody>");
    for (var r : run.results()) criteria(sb, r);
    sb.append("</tbody></table>");

    sb.append("<h3>집계 조회가 도착한 서버 <span class=\"muted\">(서버 쪽 문장 수)</span></h3>");
    for (var r : run.results()) sb.append(bar(r));

    sb.append(charts(run));

    sb.append("<h3>세부 수치</h3><table><thead><tr><th></th>");
    for (var r : run.results()) sb.append("<th>").append(r.stack()).append("</th>");
    sb.append("</tr></thead><tbody>");
    row(sb, run, "처리량 (ops/s)", r -> fmt(r.opsPerSec()));
    row(sb, run, "조회 · 적재 건수", r -> "%,d · %,d".formatted(r.reads(), r.writes()));
    row(sb, run, "오류", r -> r.errors() == 0 ? "0" : r.errors() + " <span class=\"muted\">" + esc(r.firstError()) + "</span>");
    row(sb, run, "조회 지연 p50 · p95 · p99 · max (ms)", r -> lat(r.readLatency()));
    row(sb, run, "적재 지연 p50 · p95 · p99 · max (ms)", r -> lat(r.writeLatency()));
    row(sb, run, "앱 쪽 @@server_id (소스 · 레플리카)", r -> "%,d · %,d".formatted(r.appReadsOnSource(), r.appReadsOnReplica()));
    row(sb, run, "서버 쪽 소스 SELECT · INSERT", r -> "%,d · %,d".formatted(r.source().selects(), r.source().inserts()));
    row(sb, run, "서버 쪽 레플리카 SELECT · INSERT", r -> "%,d · %,d".formatted(r.replica().selects(), r.replica().inserts()));
    row(sb, run, "최대 복제 지연 (초)", r -> r.maxLagSec() < 0 ? "복제 없음" : String.valueOf(r.maxLagSec()));
    if (!run.cpu().isEmpty()) {
      row(sb, run, "CPU 평균 (소스 · 레플리카)", r -> Optional.ofNullable(run.cpu().get(r.stack()))
          .map(c -> "%.1f%% · %.1f%% <span class=\"muted\">(%d회 샘플)</span>".formatted(c.source(), c.replica(), c.samples())).orElse("-"));
    }
    sb.append("</tbody></table>");

    sb.append("<h2>5. 결론</h2>").append(conclusion(run)).append("</main></body></html>");
    return sb.toString();
  }

  private static void criteria(StringBuilder sb, StackResult r) {
    boolean single = r.stack().equals("single");
    double share = r.replicaShareOfReads();
    boolean shareOk = single ? share == 0 : share == 1;
    boolean cleanOk = r.replica().inserts() == 0 && r.errors() == 0;
    sb.append("<tr><th>%s</th><td>레플리카 비율 %s</td><td>%.1f%%</td><td>%s</td></tr>".formatted(
        r.stack(), single ? "0% (기준선)" : "100%", share * 100, mark(shareOk)));
    sb.append("<tr><th>%s</th><td>레플리카 쓰기 0 · 오류 0</td><td>%,d · %,d</td><td>%s</td></tr>".formatted(
        r.stack(), r.replica().inserts(), r.errors(), mark(cleanOk)));
  }

  private static String mark(boolean ok) {
    return ok ? "<span class=\"pass\">충족</span>" : "<span class=\"fail\">미충족</span>";
  }

  private static String conclusion(Run run) {
    var single = run.results().stream().filter(r -> r.stack().equals("single")).findFirst();
    var split = run.results().stream().filter(r -> r.stack().equals("split")).findFirst();
    if (single.isPresent() && split.isPresent()) {
      var b = single.get();
      var f = split.get();
      if (b.replicaShareOfReads() == 0 && f.replicaShareOfReads() == 1 && f.replica().inserts() == 0 && b.errors() + f.errors() == 0) {
        return """
            <p><b>읽기 분리를 켜면 조회는 레플리카가 받고, 소스는 쓰기만 받습니다.</b></p>
            <ul><li>근거: 집계 조회의 레플리카 도착 비율 single %.0f%% · split %.0f%% (서버 쪽 문장 수). 앱이 본 서버 번호는 split 레플리카 %,d건 · 서버 쪽 %,d건</li>
            <li>적재 지연 p50 · p99: %s · %s → %s · %s ms</li>
            <li>처리량: %s → %s ops/s</li>%s
            <li>구성의 요점: 라우팅 데이터소스를 지연 커넥션 프록시로 감싸서 JPA 에 넘깁니다. 감싸지 않으면 조회가 소스로 갑니다 (명세로 확인)</li>
            <li>대가: 복제 지연입니다. 방금 쓴 값을 바로 읽는 조회는 readOnly 를 빼서 소스로 보냅니다 (명세로 확인)</li></ul>
            """.formatted(b.replicaShareOfReads() * 100, f.replicaShareOfReads() * 100, f.appReadsOnReplica(), f.replica().selects(),
            fmt(b.writeLatency().p50()), fmt(b.writeLatency().p99()), fmt(f.writeLatency().p50()), fmt(f.writeLatency().p99()),
            fmt(b.opsPerSec()), fmt(f.opsPerSec()), cpuLine(run));
      }
      return "<p><b>성공 기준을 충족하지 못했습니다.</b> 위 성공 기준 대조에서 미충족 항목을 확인합니다.</p>";
    }
    var only = run.results().get(0);
    return "<p><b>%s 단독 실행.</b> %s. 두 구성을 비교하려면 「둘 다」로 실행합니다.</p>".formatted(only.stack(), esc(only.verdict()));
  }

  private static String cpuLine(Run run) {
    Cpu b = run.cpu().get("single"), f = run.cpu().get("split");
    if (b == null || f == null) return "";
    return "\n<li>CPU 평균 (소스 · 레플리카): %.1f%% · %.1f%% → %.1f%% · %.1f%%</li>".formatted(b.source(), b.replica(), f.source(), f.replica());
  }

  private static String card(StackResult r) {
    String tone = r.stack().equals("single") ? "" : r.replicaShareOfReads() == 1 && r.replica().inserts() == 0 ? "ok" : "bad";
    return """
        <div class="card %s"><div class="label">%s</div><div class="big">%.0f%%</div>
        <div class="muted">집계 조회 중 레플리카 비율</div><p>%s</p></div>""".formatted(
        tone, r.stack(), r.replicaShareOfReads() * 100, esc(r.verdict()));
  }

  private static String bar(StackResult r) {
    long s = r.source().selects(), rep = r.replica().selects(), t = Math.max(1, s + rep);
    return "<div class=\"barrow\"><div class=\"barname\">%s</div><div class=\"bar\">%s%s</div></div>".formatted(
        r.stack(), segment("src", "소스", s, t), segment("rep", "레플리카", rep, t));
  }

  private static String segment(String css, String label, long count, long total) {
    if (count == 0) return "";
    return "<div class=\"seg %s\" style=\"width:%.2f%%\">%s %,d</div>".formatted(css, count * 100.0 / total, label, count);
  }

  private static void row(StringBuilder sb, Run run, String label, java.util.function.Function<StackResult, String> f) {
    sb.append("<tr><th>").append(label).append("</th>");
    for (var r : run.results()) sb.append("<td>").append(f.apply(r)).append("</td>");
    sb.append("</tr>");
  }

  private static String lat(Latency l) {
    return "%s · %s · %s · %s".formatted(fmt(l.p50()), fmt(l.p95()), fmt(l.p99()), fmt(l.max()));
  }

  private static String fmt(double v) { return v == Math.rint(v) ? "%,.0f".formatted(v) : "%,.2f".formatted(v); }

  private static String esc(String s) {
    return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static Optional<StackResult> stack(Run run, String name) {
    return run.results().stream().filter(r -> r.stack().equals(name)).findFirst();
  }

  private static String tiles(Run run) {
    var single = stack(run, "single");
    var split = stack(run, "split");
    if (single.isEmpty() || split.isEmpty()) return "";
    var b = single.get();
    var f = split.get();
    var sb = new StringBuilder("<section class=\"tiles\">");
    sb.append(tile("조회가 레플리카에 도착", "%.0f%%".formatted(b.replicaShareOfReads() * 100), "%.0f%%".formatted(f.replicaShareOfReads() * 100), ""));
    sb.append(tile("쓰기 지연 p50", fmt(b.writeLatency().p50()), fmt(f.writeLatency().p50()) + " ms", change(b.writeLatency().p50(), f.writeLatency().p50())));
    sb.append(tile("쓰기 지연 p99", fmt(b.writeLatency().p99()), fmt(f.writeLatency().p99()) + " ms", change(b.writeLatency().p99(), f.writeLatency().p99())));
    sb.append(tile("처리량", "%.0f".formatted(b.opsPerSec()), "%.0f ops/s".formatted(f.opsPerSec()), change(b.opsPerSec(), f.opsPerSec())));
    Cpu bc = run.cpu().get("single"), fc = run.cpu().get("split");
    if (bc != null && fc != null) {
      sb.append(tile("소스 CPU", "%.0f%%".formatted(bc.source()), "%.0f%%".formatted(fc.source()), ""));
    }
    return sb.append("</section><p class=\"muted small\">각 타일은 single → split. 같은 부하를 두 구성에 차례로 걸었습니다</p>").toString();
  }

  private static String tile(String label, String before, String after, String change) {
    return """
        <div class="tile"><div class="tlabel">%s</div><div class="tval"><span class="before">%s</span> → <b>%s</b></div><div class="tchange">%s</div></div>"""
        .formatted(label, before, after, change);
  }

  private static String change(double before, double after) {
    if (before == 0) return "";
    double pct = (after - before) / before * 100;
    return "%s%.0f%%".formatted(pct > 0 ? "+" : "", pct);
  }

  private record Series(String name, String css, double[] xs, double[] ys) {}

  private static String charts(Run run) {
    if (run.results().isEmpty()) return "";
    var curve = new ArrayList<Series>();
    var ops = new ArrayList<Series>();
    var writeP99 = new ArrayList<Series>();
    double[] pxs = java.util.Arrays.stream(StackResult.CURVE_PERCENTILES).map(ReportHtml::nines).toArray();
    for (var r : run.results()) {
      String css = r.stack().equals("single") ? "src" : "rep";
      curve.add(new Series(r.stack(), css, pxs, r.writeLatency().curve()));
      double[] secs = r.timeline().stream().mapToDouble(StackResult.Second::sec).toArray();
      ops.add(new Series(r.stack(), css, secs, r.timeline().stream().mapToDouble(StackResult.Second::ops).toArray()));
      writeP99.add(new Series(r.stack(), css, secs, r.timeline().stream().mapToDouble(StackResult.Second::writeP99).toArray()));
    }
    double[] ticks = {nines(0), nines(50), nines(90), nines(99), nines(99.9)};
    String[] tickLabels = {"0%", "50%", "90%", "99%", "99.9%"};
    int dur = run.params().durationSec();
    List<Double> timeTicks = new ArrayList<>();
    for (int t = 0; t <= dur; t += Math.max(5, dur / 6)) timeTicks.add((double) t);
    double[] tt = timeTicks.stream().mapToDouble(Double::doubleValue).toArray();
    String[] tl = timeTicks.stream().map(t -> "%.0f초".formatted(t)).toArray(String[]::new);
    return "<h3>쓰기 지연 백분위 분포 <span class=\"muted\">(HdrHistogram 형식)</span></h3>"
        + chart(curve, ticks, tickLabels, "ms")
        + "<p class=\"muted small\">가로축은 백분위(오른쪽일수록 드문 느린 요청), 세로축은 지연입니다. 작업자가 응답을 받고 다음 요청을 보내는 닫힌 루프라 "
        + "서버가 느려진 동안 요청을 덜 보냅니다(coordinated omission). 꼬리 절댓값은 실제보다 작게 나올 수 있고, 두 구성 비교는 같은 방식이라 유효합니다</p>"
        + "<h3>초당 처리량 <span class=\"muted\">(Gatling 형식, 시간 순)</span></h3>"
        + chart(ops, tt, tl, "ops/s")
        + "<h3>초당 쓰기 지연 p99</h3>"
        + chart(writeP99, tt, tl, "ms")
        + "<p class=\"muted small\">값이 실행 내내 고르면 평균 · 백분위를 믿을 수 있습니다. 앞부분만 튀면 준비 구간입니다</p>";
  }

  private static double nines(double percentile) {
    return -Math.log10(1 - Math.min(percentile, 99.9) / 100);
  }

  private static String chart(List<Series> series, double[] xTicks, String[] xTickLabels, String unit) {
    int w = 760, h = 280, left = 56, right = 16, top = 36, bottom = 36;
    double xMin = xTicks[0], xMax = xTicks[xTicks.length - 1];
    double yMax = series.stream().flatMapToDouble(s -> java.util.Arrays.stream(s.ys())).max().orElse(1);
    double step = niceStep(yMax / 4);
    yMax = Math.max(step, Math.ceil(yMax / step) * step);
    double pw = w - left - right, ph = h - top - bottom;
    var sb = new StringBuilder("<svg class=\"chart\" viewBox=\"0 0 %d %d\" role=\"img\">".formatted(w, h));
    for (double y = 0; y <= yMax + step / 2; y += step) {
      double py = top + ph - y / yMax * ph;
      sb.append("<line class=\"grid\" x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\"/>".formatted(left, w - right, py, py));
      sb.append("<text class=\"axis\" x=\"%d\" y=\"%.1f\" text-anchor=\"end\">%s</text>".formatted(left - 6, py + 4, fmt(y)));
    }
    for (int i = 0; i < xTicks.length; i++) {
      double px = left + (xTicks[i] - xMin) / (xMax - xMin) * pw;
      sb.append("<text class=\"axis\" x=\"%.1f\" y=\"%d\" text-anchor=\"middle\">%s</text>".formatted(px, h - 12, xTickLabels[i]));
    }
    sb.append("<text class=\"axis\" x=\"%d\" y=\"%d\">%s</text>".formatted(left - 50, top - 8, unit));
    int legendX = w - right - 160;
    for (var s : series) {
      var pts = new StringBuilder();
      for (int i = 0; i < s.xs().length; i++) {
        double px = left + (s.xs()[i] - xMin) / (xMax - xMin) * pw;
        double py = top + ph - s.ys()[i] / yMax * ph;
        pts.append("%.1f,%.1f ".formatted(px, py));
      }
      sb.append("<polyline class=\"ln %s\" points=\"%s\"/>".formatted(s.css(), pts.toString().trim()));
      sb.append("<rect class=\"sw %s\" x=\"%d\" y=\"%d\" width=\"12\" height=\"4\"/>".formatted(s.css(), legendX, 10));
      sb.append("<text class=\"axis\" x=\"%d\" y=\"%d\">%s</text>".formatted(legendX + 16, 15, s.name()));
      legendX += 80;
    }
    return sb.append("</svg>").toString();
  }

  private static double niceStep(double raw) {
    if (raw <= 0) return 1;
    double mag = Math.pow(10, Math.floor(Math.log10(raw)));
    double n = raw / mag;
    return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * mag;
  }

  private static final String CSS = """
      :root{--bg:#fff;--fg:#1d1f23;--muted:#6b7280;--line:#e5e7eb;--card:#f8fafc;--src:#f97316;--rep:#2563eb;--ok:#16a34a;--bad:#dc2626}
      @media (prefers-color-scheme:dark){:root{--bg:#111318;--fg:#e5e7eb;--muted:#9ca3af;--line:#2a2f3a;--card:#181b22}}
      body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.6 -apple-system,BlinkMacSystemFont,"Apple SD Gothic Neo",sans-serif}
      main{max-width:960px;margin:0 auto;padding:24px 16px}h1{font-size:22px;margin:0 0 4px}h2{font-size:17px;margin:28px 0 10px}
      .muted{color:var(--muted);font-weight:400}code{font-size:13px}
      .cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:12px;margin-top:16px}
      .card{background:var(--card);border:1px solid var(--line);border-left:5px solid var(--muted);border-radius:8px;padding:14px 16px}
      .card.ok{border-left-color:var(--ok)}.card.bad{border-left-color:var(--bad)}.card p{margin:8px 0 0}
      .label{font-weight:700;text-transform:uppercase;letter-spacing:.04em}.big{font-size:34px;font-weight:700}
      .barrow{display:flex;align-items:center;gap:10px;margin:8px 0}.barname{width:60px;font-weight:700}
      .bar{flex:1;display:flex;height:30px;border-radius:6px;overflow:hidden;background:var(--line)}
      .seg{color:#fff;font-size:13px;display:flex;align-items:center;padding:0 8px;white-space:nowrap;overflow:hidden}
      .src{background:var(--src)}.rep{background:var(--rep)}
      table{width:100%;border-collapse:collapse;font-size:14px}th,td{border-bottom:1px solid var(--line);padding:7px 8px;text-align:left;vertical-align:top}
      thead th{font-weight:700}tbody th{font-weight:400;color:var(--muted);width:30%}h3{font-size:15px;margin:20px 0 8px}
      .pass{color:var(--ok);font-weight:700}.fail{color:var(--bad);font-weight:700}
      .small{font-size:13px}
      .tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:10px;margin:16px 0 4px}
      .tile{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:12px 14px}
      .tlabel{font-size:13px;color:var(--muted)}.tval{font-size:19px;margin-top:2px}.before{color:var(--muted)}
      .tchange{font-size:13px;color:var(--muted);min-height:1em}
      .chart{width:100%;height:auto;display:block;margin:4px 0}
      .chart .grid{stroke:var(--line);stroke-width:1}.chart .axis{fill:var(--muted);font-size:11px}
      .chart .ln{fill:none;stroke-width:2.2;stroke-linejoin:round}.chart .ln.src{stroke:var(--src)}.chart .ln.rep{stroke:var(--rep)}
      .chart .sw.src{fill:var(--src)}.chart .sw.rep{fill:var(--rep)}
      """;
}
