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

    expect: "키를 고르는 규칙 자체는 맞다. 결함은 이 규칙이 불리는 시점에 있어서 단위 명세는 결함이 있어도 통과한다"
    sut.determineCurrentLookupKey() == REPLICA
  }

  def "읽기 전용 표시가 없으면 소스를 고른다"() {
    expect:
    sut.determineCurrentLookupKey() == SOURCE
  }
}
