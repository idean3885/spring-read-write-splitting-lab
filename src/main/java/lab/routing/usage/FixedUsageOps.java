package lab.routing.usage;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FixedUsageOps extends UsageOps {
  @PersistenceContext(unitName = "fixed") private EntityManager em;

  @Override protected EntityManager em() { return em; }

  @Override @Transactional(transactionManager = "fixedTx", readOnly = true)
  public ReadResult aggregateRecent(int minutes) { return doAggregate(minutes); }

  @Override @Transactional(transactionManager = "fixedTx")
  public int collect() { return doCollect(); }

  @Override @Transactional(transactionManager = "fixedTx", readOnly = true)
  public int whereAmI() { return serverId(); }
}
