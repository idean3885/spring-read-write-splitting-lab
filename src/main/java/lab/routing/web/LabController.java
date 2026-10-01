package lab.routing.web;

import java.util.List;
import java.util.Map;
import lab.routing.load.LoadParams;
import lab.routing.load.LoadRunner;
import lab.routing.load.ServerProbe;
import lab.routing.usage.BrokenUsageOps;
import lab.routing.usage.FixedUsageOps;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class LabController {

  private final LoadRunner runner;
  private final BrokenUsageOps broken;
  private final FixedUsageOps fixed;
  private final ServerProbe probe;

  public LabController(LoadRunner runner, BrokenUsageOps broken, FixedUsageOps fixed, ServerProbe probe) {
    this.runner = runner;
    this.broken = broken;
    this.fixed = fixed;
    this.probe = probe;
  }

  @GetMapping("/api/defaults")
  public LoadParams defaults() { return LoadParams.defaults(); }

  @GetMapping("/api/check")
  public Map<String, Object> check() {
    return Map.of("brokenReadOnlyServerId", broken.whereAmI(), "fixedReadOnlyServerId", fixed.whereAmI(),
        "replicaLagSec", probe.replicaLagSeconds());
  }

  @PostMapping("/api/runs")
  public ResponseEntity<?> start(@RequestBody LoadParams params) {
    return runner.start(params) ? ResponseEntity.accepted().build()
        : ResponseEntity.status(409).body(Map.of("error", "이미 실행 중"));
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
