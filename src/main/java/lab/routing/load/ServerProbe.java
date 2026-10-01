package lab.routing.load;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import lab.routing.datasource.LabDbProperties;
import org.springframework.stereotype.Component;

@Component
public class ServerProbe {

  private final LabDbProperties p;

  public ServerProbe(LabDbProperties p) { this.p = p; }

  // why: 복제가 ROW 형식이라 레플리카가 적용한 변경은 문장으로 잡히지 않는다. 레플리카의 SELECT 는 클라이언트가 보낸 것이다
  public Counts counts(LabDbProperties.Node node, String schema) {
    String sql = """
        SELECT COALESCE(SUM(CASE WHEN DIGEST_TEXT LIKE 'SELECT%' THEN COUNT_STAR END), 0),
               COALESCE(SUM(CASE WHEN DIGEST_TEXT LIKE 'INSERT%' THEN COUNT_STAR END), 0)
        FROM performance_schema.events_statements_summary_by_digest
        WHERE SCHEMA_NAME = ? AND DIGEST_TEXT LIKE '%`usage_sample`%'""";
    try (Connection c = admin(node); var ps = c.prepareStatement(sql)) {
      ps.setString(1, schema);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return new Counts(rs.getLong(1), rs.getLong(2));
      }
    } catch (SQLException e) {
      throw new IllegalStateException("문장 통계 조회 실패: " + node, e);
    }
  }

  public long replicaLagSeconds() {
    try (Connection c = admin(p.replica()); var st = c.createStatement(); ResultSet rs = st.executeQuery("SHOW REPLICA STATUS")) {
      if (!rs.next()) return -1;
      long v = rs.getLong("Seconds_Behind_Source");
      return rs.wasNull() ? -1 : v;
    } catch (SQLException e) {
      return -1;
    }
  }

  private Connection admin(LabDbProperties.Node node) throws SQLException {
    return DriverManager.getConnection(node.jdbcUrl("performance_schema"), p.adminUsername(), p.adminPassword());
  }

  public record Counts(long selects, long inserts) {
    public Counts minus(Counts o) { return new Counts(selects - o.selects, inserts - o.inserts); }
  }
}
