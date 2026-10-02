package lab.routing.load

import lab.routing.load.ServerProbe.Counts
import lab.routing.load.StackResult.Latency
import spock.lang.Specification

class StackResultSpec extends Specification {

  static final Latency NONE = new Latency(0, 0, 0, 0)

  static StackResult arrived(long sourceSelects, long replicaSelects, long replicaInserts) {
    new StackResult("sut", 1000, 10, 2, 0, null, 12, NONE, NONE, 0, 0,
        new Counts(sourceSelects, 2), new Counts(replicaSelects, replicaInserts), 0)
  }

  def "집계 조회가 소스에 #sourceSelects 건, 레플리카에 #replicaSelects 건 도착하면 레플리카 비율을 #share 로 본다"() {
    expect:
    arrived(sourceSelects, replicaSelects, 0).replicaShareOfReads() == share

    where:
    sourceSelects | replicaSelects || share
    10            | 0              || 0.0
    0             | 10             || 1.0
    3             | 7              || 0.7
  }

  def "집계 조회가 하나도 레플리카에 가지 않으면 소스 하나가 모두 받았다고 적는다"() {
    expect:
    arrived(10, 0, 0).verdict().contains("소스 하나가")
  }

  def "집계 조회가 모두 레플리카에 가고 레플리카에 쓰기가 없으면 조회와 쓰기가 나뉘었다고 적는다"() {
    expect:
    arrived(0, 10, 0).verdict().contains("조회는 레플리카, 쓰기는 소스")
  }

  def "일부만 레플리카에 가면 레플리카 비율을 판정에 적는다"() {
    expect:
    arrived(3, 7, 0).verdict().contains("70.0%")
  }
}
