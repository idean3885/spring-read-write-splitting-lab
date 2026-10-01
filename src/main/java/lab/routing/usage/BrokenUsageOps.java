package lab.routing.usage;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BrokenUsageOps extends UsageOps {
  @PersistenceContext(unitName = "broken") private EntityManager em;

  @Override protected EntityManager em() { return em; }

  @Override @Transactional(transactionManager = "brokenTx", readOnly = true)
  public ReadResult aggregateRecent(int minutes) { return doAggregate(minutes); }

  @Override @Transactional(transactionManager = "brokenTx")
  public int collect() { return doCollect(); }

  @Override @Transactional(transactionManager = "brokenTx", readOnly = true)
  public int whereAmI() { return serverId(); }
}
