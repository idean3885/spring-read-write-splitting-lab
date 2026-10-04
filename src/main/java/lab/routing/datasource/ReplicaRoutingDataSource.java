package lab.routing.datasource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class ReplicaRoutingDataSource extends AbstractRoutingDataSource {

  public enum Node { SOURCE, REPLICA }

  // why: 키는 커넥션을 얻는 순간 한 번 정해진다. 그래서 이 규칙보다 이 규칙이 불리는 시점이 중요하다
  @Override
  protected Object determineCurrentLookupKey() {
    return TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? Node.REPLICA : Node.SOURCE;
  }
}
