package lab.routing.usage;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

public abstract class UsageOps {

  public static final int SOURCE_ID = 1;

  protected abstract EntityManager em();

  public abstract ReadResult aggregateRecent(int minutes);

  public abstract int collect();

  public abstract int whereAmI();

  protected ReadResult doAggregate(int minutes) {
    int serverId = serverId();
    Object[] row = (Object[]) em().createNativeQuery(
            "SELECT COUNT(*), COALESCE(SUM(value_mb), 0) FROM usage_sample WHERE collected_at >= ?")
        .setParameter(1, LocalDateTime.now().minusMinutes(minutes))
        .getSingleResult();
    return new ReadResult(serverId, ((Number) row[0]).longValue(), ((Number) row[1]).longValue());
  }

  protected int doCollect() {
    em().persist(new UsageSample(LocalDateTime.now(), ThreadLocalRandom.current().nextInt(100, 4000)));
    em().flush();
    return serverId();
  }

  protected int serverId() {
    return ((Number) em().createNativeQuery("SELECT @@server_id").getSingleResult()).intValue();
  }

  public record ReadResult(int serverId, long rows, long sumMb) {}
}
