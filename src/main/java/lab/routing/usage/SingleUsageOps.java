package lab.routing.usage;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SingleUsageOps extends UsageOps {
  @PersistenceContext(unitName = "single") private EntityManager em;

  @Override protected EntityManager em() { return em; }

  @Override @Transactional(transactionManager = "singleTx", readOnly = true)
  public ReadResult aggregateRecent(int rows) { return doAggregate(rows); }

  @Override @Transactional(transactionManager = "singleTx")
  public WriteResult collect() { return doCollect(); }

  @Override @Transactional(transactionManager = "singleTx", readOnly = true)
  public int whereAmI() { return serverId(); }
}
