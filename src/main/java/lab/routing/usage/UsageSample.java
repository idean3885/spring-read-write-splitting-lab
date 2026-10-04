package lab.routing.usage;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "usage_sample")
public class UsageSample {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
  @Column(name = "collected_at", nullable = false) LocalDateTime collectedAt;
  @Column(name = "value_mb", nullable = false) int valueMb;

  protected UsageSample() {}

  public UsageSample(LocalDateTime collectedAt, int valueMb) {
    this.collectedAt = collectedAt;
    this.valueMb = valueMb;
  }

  public Long id() { return id; }
}
