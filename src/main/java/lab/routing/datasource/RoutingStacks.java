package lab.routing.datasource;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import javax.sql.DataSource;
import lab.routing.datasource.ReplicaRoutingDataSource.Node;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class RoutingStacks {

  public static final String BROKEN = "broken";
  public static final String FIXED = "fixed";

  @Bean @Primary
  DataSource brokenDataSource(LabDbProperties p) {
    return routing(p, "sample_broken");
  }

  @Bean @Primary
  LocalContainerEntityManagerFactoryBean brokenEntityManagerFactory(@Qualifier("brokenDataSource") DataSource ds) {
    return emf(BROKEN, ds);
  }

  @Bean @Primary
  PlatformTransactionManager brokenTx(@Qualifier("brokenEntityManagerFactory") LocalContainerEntityManagerFactoryBean emf) {
    return new JpaTransactionManager(emf.getObject());
  }

  @Bean
  DataSource fixedDataSource(LabDbProperties p) {
    // why: broken 과의 차이는 이 한 줄이다. 커넥션 획득을 첫 쿼리 시점으로 미뤄 readOnly 표시가 켜진 뒤 키를 고르게 한다
    return new LazyConnectionDataSourceProxy(routing(p, "sample_fixed"));
  }

  @Bean
  LocalContainerEntityManagerFactoryBean fixedEntityManagerFactory(@Qualifier("fixedDataSource") DataSource ds) {
    return emf(FIXED, ds);
  }

  @Bean
  PlatformTransactionManager fixedTx(@Qualifier("fixedEntityManagerFactory") LocalContainerEntityManagerFactoryBean emf) {
    return new JpaTransactionManager(emf.getObject());
  }

  private static DataSource routing(LabDbProperties p, String schema) {
    var routing = new ReplicaRoutingDataSource();
    var source = hikari(p, p.source().jdbcUrl(schema), schema + "-source");
    routing.setTargetDataSources(Map.of(Node.SOURCE, source, Node.REPLICA, hikari(p, p.replica().jdbcUrl(schema), schema + "-replica")));
    routing.setDefaultTargetDataSource(source);
    routing.afterPropertiesSet();
    return routing;
  }

  private static HikariDataSource hikari(LabDbProperties p, String url, String poolName) {
    HikariDataSource ds = DataSourceBuilder.create().type(HikariDataSource.class)
        .url(url).username(p.username()).password(p.password()).build();
    ds.setPoolName(poolName);
    ds.setMaximumPoolSize(p.poolSize());
    return ds;
  }

  private static LocalContainerEntityManagerFactoryBean emf(String unit, DataSource ds) {
    var emf = new LocalContainerEntityManagerFactoryBean();
    emf.setPersistenceUnitName(unit);
    emf.setDataSource(ds);
    emf.setPackagesToScan("lab.routing.usage");
    emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    emf.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
    return emf;
  }
}
