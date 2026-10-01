package lab.routing

import static lab.routing.load.LoadParams.Mode.BOTH

import java.sql.DriverManager
import java.sql.SQLException
import lab.routing.datasource.LabDbProperties
import lab.routing.load.LoadParams
import lab.routing.load.LoadRunner
import lab.routing.load.ServerProbe
import lab.routing.usage.BrokenUsageOps
import lab.routing.usage.FixedUsageOps
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.utility.MountableFile
import spock.lang.Specification

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReadRoutingIntegrationSpec extends Specification {

  static final int SOURCE_ID = 1
  static final int REPLICA_ID = 2
  static final LoadParams SHORT_LOAD = new LoadParams(BOTH, 4, 3, 0.8, 10)

  static final MySQLContainer SOURCE = mysql("--server-id=$SOURCE_ID")
  static final MySQLContainer REPLICA = mysql("--server-id=$REPLICA_ID", "--read-only=ON")

  static MySQLContainer mysql(String... command) {
    def container = new MySQLContainer("mysql:8.0")
        .withUsername("root").withPassword("root")
        .withCommand(command)
        .withCopyFileToContainer(MountableFile.forHostPath("docker/init.sql"), "/docker-entrypoint-initdb.d/01-init.sql")
    container.start()
    container
  }

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry registry) {
    registry.add("lab.db.source.host", SOURCE::getHost)
    registry.add("lab.db.source.port", { SOURCE.getMappedPort(3306) })
    registry.add("lab.db.replica.host", REPLICA::getHost)
    registry.add("lab.db.replica.port", { REPLICA.getMappedPort(3306) })
  }

  @Autowired BrokenUsageOps broken
  @Autowired FixedUsageOps fixed
  @Autowired ServerProbe probe
  @Autowired LabDbProperties db
  @Autowired LoadRunner runner

  def cleanupSpec() {
    [SOURCE, REPLICA]*.stop()
  }

  def "고치기 전 구성은 읽기 전용 트랜잭션이어도 소스에서 조회한다"() {
    given:
    def before = snapshot("sample_broken")

    when:
    def answered = broken.aggregateRecent(10)

    then: "앱이 받은 서버 번호가 소스다"
    answered.serverId() == SOURCE_ID

    and: "서버가 받은 문장도 소스에만 있다. 이 명세는 결함을 고정한다"
    def arrived = delta("sample_broken", before)
    arrived.source.selects() == 1
    arrived.replica.selects() == 0
  }

  def "커넥션을 첫 쿼리 때 얻도록 감싸면 읽기 전용 트랜잭션은 레플리카에서 조회한다"() {
    given:
    def before = snapshot("sample_fixed")

    when:
    def answered = fixed.aggregateRecent(10)

    then:
    answered.serverId() == REPLICA_ID

    and:
    def arrived = delta("sample_fixed", before)
    arrived.replica.selects() == 1
    arrived.source.selects() == 0
  }

  def "감싼 구성에서도 쓰기 트랜잭션은 소스에 적재한다"() {
    given:
    def before = snapshot("sample_fixed")

    when:
    def answeredServerId = fixed.collect()

    then:
    answeredServerId == SOURCE_ID

    and:
    def arrived = delta("sample_fixed", before)
    arrived.source.inserts() == 1
    arrived.replica.inserts() == 0
  }

  def "레플리카에 직접 쓰면 읽기 전용이라 거절한다"() {
    when:
    DriverManager.getConnection(db.replica().jdbcUrl("sample_fixed"), db.username(), db.password()).withCloseable { c ->
      c.createStatement().executeUpdate("INSERT INTO usage_sample (collected_at, value_mb) VALUES (NOW(3), 1)")
    }

    then: "쓰기를 잘못 보내면 바로 실패한다. 읽기를 잘못 보내면 아무 오류 없이 성공하므로 도착 서버를 직접 봐야 한다"
    def e = thrown(SQLException)
    e.message.contains("read-only")
  }

  def "부하를 걸어도 고치기 전 구성의 집계 조회는 레플리카에 하나도 가지 않는다"() {
    when:
    def result = runner.runStack("broken", broken, "sample_broken", SHORT_LOAD)

    then:
    result.errors() == 0
    result.reads() > 0
    result.replica().selects() == 0
  }

  def "부하를 걸면 감싼 구성의 집계 조회는 모두 레플리카에 간다"() {
    when:
    def result = runner.runStack("fixed", fixed, "sample_fixed", SHORT_LOAD)

    then:
    result.errors() == 0
    result.source().selects() == 0
    result.replica().selects() == result.reads()
  }

  def "부하 중 앱이 받은 서버 번호와 서버가 집계한 문장 수가 일치한다"() {
    when:
    def result = runner.runStack("fixed", fixed, "sample_fixed", SHORT_LOAD)

    then: "두 관찰이 서로를 확인한다. 하나만 보면 그 관찰 수단이 틀렸을 때 걸러지지 않는다"
    result.appReadsOnReplica() == result.replica().selects()
    result.appReadsOnSource() == result.source().selects()
  }

  private Map<String, ServerProbe.Counts> snapshot(String schema) {
    [source: probe.counts(db.source(), schema), replica: probe.counts(db.replica(), schema)]
  }

  private Map<String, ServerProbe.Counts> delta(String schema, Map<String, ServerProbe.Counts> before) {
    def now = snapshot(schema)
    [source: now.source.minus(before.source), replica: now.replica.minus(before.replica)]
  }
}
