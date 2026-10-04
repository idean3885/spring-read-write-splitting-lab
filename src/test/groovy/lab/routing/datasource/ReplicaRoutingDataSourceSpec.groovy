package lab.routing.datasource

import static lab.routing.datasource.ReplicaRoutingDataSource.Node.REPLICA
import static lab.routing.datasource.ReplicaRoutingDataSource.Node.SOURCE

import org.springframework.transaction.support.TransactionSynchronizationManager
import spock.lang.Specification

class ReplicaRoutingDataSourceSpec extends Specification {

  def sut = new ReplicaRoutingDataSource()

  def cleanup() {
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false)
  }

  def "읽기 전용 표시가 켜져 있으면 레플리카를 고른다"() {
    given:
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true)

    expect: "규칙만 본다. 이 규칙이 언제 불리는지는 단위 층에서 보이지 않아 통합 명세가 확인한다"
    sut.determineCurrentLookupKey() == REPLICA
  }

  def "읽기 전용 표시가 없으면 소스를 고른다"() {
    expect:
    sut.determineCurrentLookupKey() == SOURCE
  }
}
