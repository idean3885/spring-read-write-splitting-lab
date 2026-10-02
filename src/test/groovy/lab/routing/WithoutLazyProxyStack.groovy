package lab.routing

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import javax.sql.DataSource
import lab.routing.datasource.LabDbProperties
import lab.routing.datasource.RoutingStacks
import lab.routing.usage.UsageOps
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional

/**
 * split 과 같은 라우팅 데이터소스를 지연 커넥션 프록시 없이 JPA 에 넘긴 구성.
 * 프록시 한 줄이 왜 필요한지 보이려고 명세에서만 띄운다.
 */
@TestConfiguration
class WithoutLazyProxyStack {

  @Bean
  DataSource withoutLazyDataSource(LabDbProperties p) {
    RoutingStacks.routing(p, "sample_split")
  }

  @Bean
  LocalContainerEntityManagerFactoryBean withoutLazyEntityManagerFactory(@Qualifier("withoutLazyDataSource") DataSource ds) {
    RoutingStacks.emf("withoutLazy", ds)
  }

  @Bean
  PlatformTransactionManager withoutLazyTx(@Qualifier("withoutLazyEntityManagerFactory") LocalContainerEntityManagerFactoryBean emf) {
    new JpaTransactionManager(emf.object)
  }

  @Bean
  WithoutLazyUsageOps withoutLazyUsageOps() {
    new WithoutLazyUsageOps()
  }

  static class WithoutLazyUsageOps extends UsageOps {
    @PersistenceContext(unitName = "withoutLazy")
    private EntityManager em

    @Override
    protected EntityManager em() { em }

    @Override
    @Transactional(transactionManager = "withoutLazyTx", readOnly = true)
    ReadResult aggregateRecent(int rows) { doAggregate(rows) }

    @Override
    @Transactional(transactionManager = "withoutLazyTx")
    WriteResult collect() { doCollect() }

    @Override
    @Transactional(transactionManager = "withoutLazyTx", readOnly = true)
    int whereAmI() { serverId() }
  }
}
