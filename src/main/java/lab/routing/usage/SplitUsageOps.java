package lab.routing.usage;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SplitUsageOps extends UsageOps {
  @PersistenceContext(unitName = "split") private EntityManager em;

  @Override protected EntityManager em() { return em; }

  @Override @Transactional(transactionManager = "splitTx", readOnly = true)
  public ReadResult aggregateRecent(int rows) { return doAggregate(rows); }

  @Override @Transactional(transactionManager = "splitTx")
  public WriteResult collect() { return doCollect(); }

  @Override @Transactional(transactionManager = "splitTx", readOnly = true)
  public int whereAmI() { return serverId(); }

  @Transactional(transactionManager = "splitTx", readOnly = true)
  public boolean existsInReadOnlyTx(long id) { return doExists(id); }

  @Transactional(transactionManager = "splitTx")
  public boolean existsInWriteTx(long id) { return doExists(id); }
}
