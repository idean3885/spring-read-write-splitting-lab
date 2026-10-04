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
  // why: 다이제스트 요약은 여러 스레드가 한 행을 함께 갱신해 동시 실행 중 증가분을 가끔 잃는다. 스레드별로 남는 문장 이력을 집계한다
  public Counts counts(LabDbProperties.Node node, String schema) {
    String sql = """
        SELECT COALESCE(SUM(UPPER(SQL_TEXT) LIKE 'SELECT%USAGE_SAMPLE%'), 0),
               COALESCE(SUM(UPPER(SQL_TEXT) LIKE 'INSERT%USAGE_SAMPLE%'), 0),
               COUNT(*) >= @@performance_schema_events_statements_history_long_size
        FROM performance_schema.events_statements_history_long
        WHERE CURRENT_SCHEMA = ?""";
    try (Connection c = admin(node); var ps = c.prepareStatement(sql)) {
      ps.setString(1, schema);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        if (rs.getBoolean(3)) {
          throw new IllegalStateException("문장 이력이 가득 차 집계할 수 없습니다(%s). 실행 시간이나 작업자 수를 줄입니다.".formatted(node));
        }
        return new Counts(rs.getLong(1), rs.getLong(2));
      }
    } catch (SQLException e) {
      throw new IllegalStateException("문장 이력 조회 실패: " + node, e);
    }
  }

  public void resetHistory(LabDbProperties.Node node) {
    try (Connection c = admin(node); var st = c.createStatement()) {
      st.execute("TRUNCATE TABLE performance_schema.events_statements_history_long");
    } catch (SQLException e) {
      throw new IllegalStateException("문장 이력 초기화 실패: " + node, e);
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
