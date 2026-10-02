package lab.routing

import java.sql.DriverManager
import lab.routing.usage.SplitUsageOps
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.containers.Network
import org.testcontainers.utility.MountableFile
import spock.lang.Specification

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReplicationLagSpec extends Specification {

  static final int SOURCE_ID = 1
  static final int DELAY_SEC = 3
  static final Network NETWORK = Network.newNetwork()

  static final MySQLContainer SOURCE = start(new MySQLContainer("mysql:8.0")
      .withNetworkAliases("source")
      .withCommand("--server-id=1", "--log-bin=mysql-bin", "--binlog-format=ROW", "--gtid-mode=ON", "--enforce-gtid-consistency=ON")
      .withCopyFileToContainer(MountableFile.forHostPath("docker/init.sql"), "/docker-entrypoint-initdb.d/01-init.sql"))
  static final MySQLContainer REPLICA = start(new MySQLContainer("mysql:8.0")
      .withCommand("--server-id=2", "--relay-log=relay-bin", "--gtid-mode=ON", "--enforce-gtid-consistency=ON", "--read-only=ON"))
  static final boolean REPLICATING = replicate()

  static MySQLContainer start(MySQLContainer container) {
    container.withNetwork(NETWORK).withUsername("root").withPassword("root").start()
    container
  }

  static boolean replicate() {
    onReplica("""CHANGE REPLICATION SOURCE TO SOURCE_HOST='source', SOURCE_PORT=3306, SOURCE_USER='repl', SOURCE_PASSWORD='repl',
        SOURCE_AUTO_POSITION=1, GET_SOURCE_PUBLIC_KEY=1""", "START REPLICA")
    long deadline = System.currentTimeMillis() + 120_000
    while (System.currentTimeMillis() < deadline) {
      if (replicaRows() == 200_000) return true
      sleep(500)
    }
    throw new IllegalStateException("레플리카가 초기 데이터를 따라잡지 못했다")
  }

  static long replicaRows() {
    try {
      DriverManager.getConnection(REPLICA.jdbcUrl, "root", "root").withCloseable { c ->
        def rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM sample_split.usage_sample")
        rs.next()
        rs.getLong(1)
      }
    } catch (Exception ignored) {
      -1
    }
  }

  static void onReplica(String... statements) {
    DriverManager.getConnection(REPLICA.jdbcUrl, "root", "root").withCloseable { c ->
      statements.each { c.createStatement().execute(it) }
    }
  }

  static void delayReplica(int seconds) {
    onReplica("STOP REPLICA SQL_THREAD", "CHANGE REPLICATION SOURCE TO SOURCE_DELAY=$seconds", "START REPLICA SQL_THREAD")
  }

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry registry) {
    registry.add("lab.db.source.host", SOURCE::getHost)
    registry.add("lab.db.source.port", { SOURCE.getMappedPort(3306) })
    registry.add("lab.db.replica.host", REPLICA::getHost)
    registry.add("lab.db.replica.port", { REPLICA.getMappedPort(3306) })
  }

  @Autowired SplitUsageOps split

  def setup() {
    delayReplica(DELAY_SEC)
  }

  def cleanup() {
    delayReplica(0)
  }

  def cleanupSpec() {
    [REPLICA, SOURCE]*.stop()
    NETWORK.close()
  }

  def "레플리카가 늦으면 쓰자마자 읽기 전용 트랜잭션으로 조회한 행은 보이지 않는다"() {
    when:
    def written = split.collect()

    then: "쓰기는 소스가 받았다"
    written.serverId() == SOURCE_ID

    and: "읽기 전용 트랜잭션은 레플리카로 가고, 레플리카에는 아직 그 행이 없다"
    !split.existsInReadOnlyTx(written.id())
  }

  def "쓰자마자 읽어야 하는 조회는 readOnly 를 빼면 소스가 받아 바로 보인다"() {
    when:
    def written = split.collect()

    then:
    split.existsInWriteTx(written.id())
  }

  def "지연이 지나면 레플리카에서도 그 행이 보인다"() {
    given:
    def written = split.collect()

    when:
    long deadline = System.currentTimeMillis() + (DELAY_SEC + 10) * 1000L
    boolean visible = false
    while (!visible && System.currentTimeMillis() < deadline) {
      visible = split.existsInReadOnlyTx(written.id())
      if (!visible) sleep(200)
    }

    then: "복제 지연은 데이터가 틀린 것이 아니라 늦게 도착하는 것이다"
    visible
  }
}
