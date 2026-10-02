package lab.routing.web;

import java.util.List;
import java.util.Map;
import lab.routing.load.LoadParams;
import lab.routing.load.LoadRunner;
import lab.routing.load.ServerProbe;
import lab.routing.usage.SingleUsageOps;
import lab.routing.usage.SplitUsageOps;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class LabController {

  private final LoadRunner runner;
  private final SingleUsageOps single;
  private final SplitUsageOps split;
  private final ServerProbe probe;

  public LabController(LoadRunner runner, SingleUsageOps single, SplitUsageOps split, ServerProbe probe) {
    this.runner = runner;
    this.single = single;
    this.split = split;
    this.probe = probe;
  }

  @GetMapping("/api/defaults")
  public LoadParams defaults() { return LoadParams.defaults(); }

  @GetMapping("/api/check")
  public Map<String, Object> check() {
    return Map.of("singleReadOnlyServerId", single.whereAmI(), "splitReadOnlyServerId", split.whereAmI(),
        "replicaLagSec", probe.replicaLagSeconds());
  }

  @PostMapping("/api/runs")
  public ResponseEntity<?> start(@RequestBody LoadParams params) {
    return runner.start(params) ? ResponseEntity.accepted().build()
        : ResponseEntity.status(409).body(Map.of("error", "이미 실행 중"));
  }

  @PostMapping("/api/runs/{id}/cpu")
  public ResponseEntity<?> cpu(@PathVariable int id, @RequestBody Map<String, LoadRunner.Cpu> cpu) {
    return runner.attachCpu(id, cpu) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
  }

  @GetMapping("/api/progress")
  public LoadRunner.Progress progress() { return runner.progress(); }

  @GetMapping("/api/runs")
  public List<LoadRunner.Run> runs() { return runner.runs(); }

  @GetMapping(value = "/report.html", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
  public ResponseEntity<String> report(@RequestParam(required = false) Integer id) {
    var run = runner.runs().stream().filter(r -> id == null || r.id() == id).findFirst();
    return run.map(r -> ResponseEntity.ok(ReportHtml.render(r)))
        .orElse(ResponseEntity.status(404).body("<p>아직 실행 결과가 없다</p>"));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> bad(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
  }
}
