package lab.routing.web

import static lab.routing.load.LoadParams.Mode.BOTH

import java.time.LocalDateTime
import lab.routing.load.LoadParams
import lab.routing.load.LoadRunner.Run
import lab.routing.load.ServerProbe.Counts
import lab.routing.load.StackResult
import lab.routing.load.StackResult.Latency
import lab.routing.load.StackResult.Second
import spock.lang.Specification

class ReportHtmlSpec extends Specification {

  static StackResult stack(String name, int durationSec) {
    def timeline = (0..<durationSec).collect { new Second(it, 100, 10.0d) }
    new StackResult(name, durationSec * 1000L, 80, 20, 0, null, 100, Latency.NONE, Latency.NONE, 0, 0,
        new Counts(0, 20), new Counts(80, 0), 0, timeline)
  }

  def "실행 시간이 눈금 간격의 배수가 아니어도 시간 그래프의 마지막 눈금은 실행 시간이다"() {
    given:
    def params = new LoadParams(BOTH, 4, 7, 0.8, 1000)
    def run = new Run(1, LocalDateTime.now(), params, 40, [stack("single", 7), stack("split", 7)], [:])

    when:
    def html = ReportHtml.render(run)

    then:
    html.contains(">7초<")
    !html.contains(">10초<")
  }
}
