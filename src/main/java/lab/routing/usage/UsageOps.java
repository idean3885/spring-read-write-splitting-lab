package lab.routing.usage;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

public abstract class UsageOps {

  public static final int SOURCE_ID = 1;

  protected abstract EntityManager em();

  public abstract ReadResult aggregateRecent(int rows);

  public abstract WriteResult collect();

  public abstract int whereAmI();

  protected ReadResult doAggregate(int rows) {
    int serverId = serverId();
    Object[] row = (Object[]) em().createNativeQuery(
            "SELECT COUNT(*), COALESCE(SUM(value_mb), 0) FROM (SELECT value_mb FROM usage_sample ORDER BY id DESC LIMIT ?) recent")
        .setParameter(1, rows)
        .getSingleResult();
    return new ReadResult(serverId, ((Number) row[0]).longValue(), ((Number) row[1]).longValue());
  }

  protected WriteResult doCollect() {
    var sample = new UsageSample(LocalDateTime.now(), ThreadLocalRandom.current().nextInt(100, 4000));
    em().persist(sample);
    em().flush();
    return new WriteResult(serverId(), sample.id());
  }

  protected boolean doExists(long id) {
    return ((Number) em().createNativeQuery("SELECT COUNT(*) FROM usage_sample WHERE id = ?")
        .setParameter(1, id).getSingleResult()).longValue() > 0;
  }

  protected int serverId() {
    return ((Number) em().createNativeQuery("SELECT @@server_id").getSingleResult()).intValue();
  }

  public record ReadResult(int serverId, long rows, long sumMb) {}

  public record WriteResult(int serverId, long id) {}
}
