package lab.routing

import static lab.routing.load.LoadParams.Mode.BOTH

import java.sql.DriverManager
import java.sql.SQLException
import lab.routing.datasource.LabDbProperties
import lab.routing.load.LoadParams
import lab.routing.load.LoadRunner
import lab.routing.load.ServerProbe
import lab.routing.usage.SingleUsageOps
import lab.routing.usage.SplitUsageOps
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.utility.MountableFile
import spock.lang.Specification

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(WithoutLazyProxyStack)
class ReadRoutingIntegrationSpec extends Specification {

  static final int SOURCE_ID = 1
  static final int REPLICA_ID = 2
  static final LoadParams SHORT_LOAD = new LoadParams(BOTH, 4, 3, 0.8, 1000)

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

  @Autowired SingleUsageOps single
  @Autowired SplitUsageOps split
  @Autowired WithoutLazyProxyStack.WithoutLazyUsageOps withoutLazy
  @Autowired ServerProbe probe
  @Autowired LabDbProperties db
  @Autowired LoadRunner runner

  def cleanupSpec() {
    [SOURCE, REPLICA]*.stop()
  }

  def "읽기 분리가 없으면 읽기 전용 트랜잭션도 소스에서 조회한다"() {
    given:
    def before = snapshot("sample_single")

    when:
    def answered = single.aggregateRecent(1000)

    then: "앱이 받은 서버 번호가 소스다"
    answered.serverId() == SOURCE_ID

    and: "서버가 받은 문장도 소스에만 있다"
    def arrived = delta("sample_single", before)
    arrived.source.selects() == 1
    arrived.replica.selects() == 0
  }

  def "읽기 분리 구성에서 읽기 전용 트랜잭션은 레플리카에서 조회한다"() {
    given:
    def before = snapshot("sample_split")

    when:
    def answered = split.aggregateRecent(1000)

    then:
    answered.serverId() == REPLICA_ID

    and:
    def arrived = delta("sample_split", before)
    arrived.replica.selects() == 1
    arrived.source.selects() == 0
  }

  def "읽기 분리 구성에서도 쓰기 트랜잭션은 소스에 적재한다"() {
    given:
    def before = snapshot("sample_split")

    when:
    def written = split.collect()

    then:
    written.serverId() == SOURCE_ID

    and:
    def arrived = delta("sample_split", before)
    arrived.source.inserts() == 1
    arrived.replica.inserts() == 0
  }

  def "라우팅 데이터소스를 지연 커넥션 프록시로 감싸지 않으면 읽기 전용 트랜잭션도 소스에서 조회한다"() {
    given:
    def before = snapshot("sample_split")

    when:
    def answered = withoutLazy.aggregateRecent(1000)

    then: "JpaTransactionManager 가 readOnly 표시를 켜기 전에 커넥션을 얻어, 키를 고르는 순간 표시가 꺼져 있다"
    answered.serverId() == SOURCE_ID

    and: "오류 없이 소스가 대신 응답한다. 결과값만 보는 테스트로는 이 차이가 드러나지 않는다"
    def arrived = delta("sample_split", before)
    arrived.source.selects() == 1
    arrived.replica.selects() == 0
  }

  def "레플리카에 직접 쓰면 읽기 전용이라 거절한다"() {
    when:
    DriverManager.getConnection(db.replica().jdbcUrl("sample_split"), db.username(), db.password()).withCloseable { c ->
      c.createStatement().executeUpdate("INSERT INTO usage_sample (collected_at, value_mb) VALUES (NOW(3), 1)")
    }

    then: "쓰기가 레플리카로 잘못 가면 바로 실패한다. 읽기는 어느 쪽으로 가도 성공하므로 도착 서버를 직접 본다"
    def e = thrown(SQLException)
    e.message.contains("read-only")
  }

  def "읽기 분리가 없으면 부하 중 집계 조회는 모두 소스에 간다"() {
    when:
    def result = runner.runStack("single", single, "sample_single", SHORT_LOAD)

    then:
    result.errors() == 0
    result.reads() > 0
    result.replica().selects() == 0
  }

  def "부하를 걸면 읽기 분리 구성의 집계 조회는 모두 레플리카에 간다"() {
    when:
    def result = runner.runStack("split", split, "sample_split", SHORT_LOAD)

    then:
    result.errors() == 0
    result.source().selects() == 0
    result.replica().selects() == result.reads()
  }

  def "부하 중 앱이 받은 서버 번호와 서버가 집계한 문장 수가 일치한다"() {
    when:
    def result = runner.runStack("split", split, "sample_split", SHORT_LOAD)

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
