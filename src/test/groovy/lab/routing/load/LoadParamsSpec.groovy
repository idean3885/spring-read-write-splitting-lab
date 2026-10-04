package lab.routing.load

import static lab.routing.load.LoadParams.Mode.BOTH

import spock.lang.Specification

class LoadParamsSpec extends Specification {

  def "#field 이(가) #value 이면 부하를 시작하기 전에 거절한다"() {
    when:
    new LoadParams(BOTH, concurrency, durationSec, readRatio, scanRows)

    then:
    thrown(IllegalArgumentException)

    where:
    field          | value  || concurrency | durationSec | readRatio | scanRows
    "동시 작업자"   | 0      || 0           | 15          | 0.8       | 1000
    "동시 작업자"   | 257    || 257         | 15          | 0.8       | 1000
    "실행 시간"     | 2      || 16          | 2           | 0.8       | 1000
    "실행 시간"     | 301    || 16          | 301         | 0.8       | 1000
    "읽기 비율"     | -0.1   || 16          | 15          | -0.1      | 1000
    "읽기 비율"     | 1.1    || 16          | 15          | 1.1       | 1000
    "집계 건수"     | 0      || 16          | 15          | 0.8       | 0
    "집계 건수"     | 200001 || 16          | 15          | 0.8       | 200001
  }

  def "기본값은 두 구성을 비교하는 설정이다"() {
    when:
    def defaults = LoadParams.defaults()

    then: "화면에 들어와 버튼만 누르면 비교가 시작되도록"
    defaults.mode() == BOTH
    defaults.readRatio() == 0.8d
  }
}
