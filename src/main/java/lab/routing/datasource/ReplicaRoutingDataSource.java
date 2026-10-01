package lab.routing.datasource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class ReplicaRoutingDataSource extends AbstractRoutingDataSource {

  public enum Node { SOURCE, REPLICA }

  // why: 키는 커넥션을 얻는 순간 한 번 정해진다. 트랜잭션 매니저가 readOnly 표시보다 커넥션을 먼저 얻으면 이 값은 늘 SOURCE 다
  @Override
  protected Object determineCurrentLookupKey() {
    return TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? Node.REPLICA : Node.SOURCE;
  }
}
