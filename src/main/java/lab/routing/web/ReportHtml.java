package lab.routing.web;

import java.time.format.DateTimeFormatter;
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

        <h2>1. 목적</h2>
        <p><b>읽기 전용 트랜잭션을 레플리카 DB 로 보내도록 만든 설정이, 실제로 쿼리를 레플리카에 도착시키는지 확인한다.</b><br>
        설정이 틀려도 소스 DB 가 대신 응답하므로 오류가 나지 않는다.<br>
        그래서 앱의 동작이 아니라, 각 DB 가 실제로 받은 쿼리 수로 판정한다.</p>
        <table><thead><tr><th>성공 기준 (실행 전에 정함)</th><th>broken</th><th>fixed</th></tr></thead><tbody>
        <tr><th>집계 조회가 레플리카에 도착한 비율</th><td>0%% 면 결함 재현</td><td>100%% 면 해결</td></tr>
        <tr><th>쓰기가 레플리카에 도착한 건수 · 오류</th><td>0 · 0</td><td>0 · 0</td></tr></tbody></table>

        <h2>2. 조건</h2>
        <h3>알아야 할 용어</h3>
        <table><tbody>
        <tr><th>소스</th><td>쓰기를 받는 원본 DB. 이 실험의 MySQL 1번 서버(server_id 1)</td></tr>
        <tr><th>레플리카</th><td>소스의 변경을 복제받는 사본 DB. 읽기만 허용한다(read_only). MySQL 2번 서버(server_id 2)</td></tr>
        <tr><th>읽기 분리</th><td>조회는 레플리카, 쓰기는 소스로 보내 소스의 부하를 나누는 구성</td></tr>
        <tr><th>readOnly 트랜잭션</th><td>Spring 의 <code>@Transactional(readOnly = true)</code>. 이 표시를 보고 레플리카로 보낸다</td></tr>
        <tr><th>라우팅 데이터소스</th><td>Spring 의 <code>AbstractRoutingDataSource</code>. DB 커넥션을 달라는 요청을 받는 순간, 현재 트랜잭션이 readOnly 인지 보고 소스 · 레플리카 중 하나에서 커넥션을 꺼낸다</td></tr>
        <tr><th>지연 커넥션 프록시</th><td>Spring 의 <code>LazyConnectionDataSourceProxy</code>. 커넥션 요청을 받으면 진짜 커넥션 대신 대리 객체를 돌려주고, 첫 쿼리를 실행할 때 진짜 커넥션을 꺼낸다</td></tr>
        </tbody></table>

        <h3>비교하는 두 구성</h3>
        <p>두 구성은 코드가 같다.<br>
        다른 것은 JPA(Hibernate)에 어떤 데이터소스를 연결했느냐 하나뿐이다.</p>
        <table><thead><tr><th></th><th>broken</th><th>fixed</th></tr></thead><tbody>
        <tr><th>JPA 에 연결한 데이터소스</th><td>라우팅 데이터소스를 <b>직접</b> 연결</td><td>라우팅 데이터소스를 지연 커넥션 프록시로 <b>감싸서</b> 연결</td></tr>
        <tr><th>소스 · 레플리카를 고르는 시점</th><td>트랜잭션이 시작될 때.<br>이때는 readOnly 표시가 아직 켜지지 않았다</td><td>첫 쿼리를 실행할 때.<br>이때는 readOnly 표시가 켜져 있다</td></tr>
        <tr><th>기대 동작</th><td>readOnly 트랜잭션도 소스로 간다 (결함)</td><td>readOnly 트랜잭션은 레플리카, 쓰기는 소스로 간다</td></tr>
        <tr><th>공통 환경</th><td colspan="2">MySQL 8.0 소스 1대 · 레플리카 1대 (GTID 비동기 복제, ROW 형식, 레플리카 read_only)<br>Spring Boot 3.5 · JpaTransactionManager · HikariCP 커넥션 풀 서버당 %d개</td></tr>
        </tbody></table>

        <h2>3. 절차</h2>
        <table><tbody>
        <tr><th>부하</th><td>동시 작업자 %d개가 구성당 %d초 동안 쉬지 않고 연산을 보낸다.<br>broken 을 먼저, fixed 를 다음에 실행한다</td></tr>
        <tr><th>연산</th><td>집계 조회 %.0f%%: readOnly 트랜잭션으로 최근 %d분 사용량의 건수 · 합계를 조회한다<br>원천 적재 %.0f%%: 쓰기 트랜잭션으로 사용량 1행을 넣는다</td></tr>
        <tr><th>판정 지표</th><td>도착 서버: 각 MySQL 의 문장 통계(<code>performance_schema.events_statements_summary_by_digest</code>)를 실행 전후로 뺀 값<br>복제는 ROW 형식이라 레플리카가 복제로 적용한 변경은 문장으로 잡히지 않는다<br>그래서 레플리카에 잡힌 SELECT 는 앱이 보낸 것이다</td></tr>
        <tr><th>교차 확인</th><td>조회 트랜잭션 안에서 받은 <code>@@server_id</code> (앱이 본 서버 번호)</td></tr>
        <tr><th>부가 지표</th><td>처리량 · 지연 p50 · p95 · p99 · max · 오류 · 복제 지연(1초마다 확인)</td></tr>
        </tbody></table>

        <h2>4. 결과</h2>
        """.formatted(run.id(), CSS, run.id(), run.finishedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
        run.poolSize(), p.concurrency(), p.durationSec(), p.readRatio() * 100, p.windowMinutes(), (1 - p.readRatio()) * 100));

    sb.append("<section class=\"cards\">");
    for (var r : run.results()) sb.append(card(r));
    sb.append("</section>");

    sb.append("<h3>성공 기준 대조</h3><table><thead><tr><th></th><th>기준</th><th>실측</th><th>판정</th></tr></thead><tbody>");
    for (var r : run.results()) criteria(sb, r);
    sb.append("</tbody></table>");

    sb.append("<h3>집계 조회가 도착한 서버 <span class=\"muted\">(서버 쪽 문장 수)</span></h3>");
    for (var r : run.results()) sb.append(bar(r));

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
    sb.append("</tbody></table>");

    sb.append("<h2>5. 결론</h2>").append(conclusion(run)).append("</main></body></html>");
    return sb.toString();
  }

  private static void criteria(StringBuilder sb, StackResult r) {
    boolean broken = r.stack().equals("broken");
    double share = r.replicaShareOfReads();
    boolean shareOk = broken ? share == 0 : share == 1;
    boolean cleanOk = r.replica().inserts() == 0 && r.errors() == 0;
    sb.append("<tr><th>%s</th><td>레플리카 비율 %s</td><td>%.1f%%</td><td>%s</td></tr>".formatted(
        r.stack(), broken ? "0% (결함 재현)" : "100% (해결)", share * 100, mark(shareOk)));
    sb.append("<tr><th>%s</th><td>레플리카 쓰기 0 · 오류 0</td><td>%,d · %,d</td><td>%s</td></tr>".formatted(
        r.stack(), r.replica().inserts(), r.errors(), mark(cleanOk)));
  }

  private static String mark(boolean ok) {
    return ok ? "<span class=\"pass\">충족</span>" : "<span class=\"fail\">미충족</span>";
  }

  private static String conclusion(Run run) {
    var broken = run.results().stream().filter(r -> r.stack().equals("broken")).findFirst();
    var fixed = run.results().stream().filter(r -> r.stack().equals("fixed")).findFirst();
    if (broken.isPresent() && fixed.isPresent()) {
      var b = broken.get();
      var f = fixed.get();
      if (b.replicaShareOfReads() == 0 && f.replicaShareOfReads() == 1 && f.replica().inserts() == 0) {
        return """
            <p><b>라우팅 데이터소스를 JPA 에 직접 연결하면 읽기 분리는 동작하지 않는다.<br>지연 커넥션 프록시(LazyConnectionDataSourceProxy)로 감싸서 연결해야 동작한다.</b></p>
            <ul><li>근거: 집계 조회의 레플리카 도착 비율 broken %.0f%% · fixed %.0f%%, 앱 쪽 관찰과 일치</li>
            <li>부가: fixed 는 소스가 쓰기만 받아 적재 지연 p50 이 %s → %s ms</li>
            <li>제약: 고치면 복제 지연이 새 문제로 들어온다. 방금 쓴 값을 바로 읽는 조회는 레플리카로 보내지 않는다</li></ul>
            """.formatted(b.replicaShareOfReads() * 100, f.replicaShareOfReads() * 100,
            fmt(b.writeLatency().p50()), fmt(f.writeLatency().p50()));
      }
      return "<p><b>성공 기준을 충족하지 못했다.</b> 위 성공 기준 대조에서 미충족 항목을 본다.</p>";
    }
    var only = run.results().get(0);
    return "<p><b>%s 단독 실행.</b> %s. 두 구성을 비교하려면 「둘 다」로 실행한다.</p>".formatted(only.stack(), esc(only.verdict()));
  }

  private static String card(StackResult r) {
    boolean ok = r.replicaShareOfReads() == 1 && r.replica().inserts() == 0;
    return """
        <div class="card %s"><div class="label">%s</div><div class="big">%.0f%%</div>
        <div class="muted">집계 조회 중 레플리카 비율</div><p>%s</p></div>""".formatted(
        ok ? "ok" : "bad", r.stack(), r.replicaShareOfReads() * 100, esc(r.verdict()));
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
      """;
}
