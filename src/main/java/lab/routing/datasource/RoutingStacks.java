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

  public static final String SINGLE = "single";
  public static final String SPLIT = "split";

  @Bean @Primary
  DataSource singleDataSource(LabDbProperties p) {
    return hikari(p, p.source().jdbcUrl("sample_single"), "single-source");
  }

  @Bean @Primary
  LocalContainerEntityManagerFactoryBean singleEntityManagerFactory(@Qualifier("singleDataSource") DataSource ds) {
    return emf(SINGLE, ds);
  }

  @Bean @Primary
  PlatformTransactionManager singleTx(@Qualifier("singleEntityManagerFactory") LocalContainerEntityManagerFactoryBean emf) {
    return new JpaTransactionManager(emf.getObject());
  }

  @Bean
  DataSource splitDataSource(LabDbProperties p) {
    // why: JpaTransactionManager 는 readOnly 표시를 켜기 전에 커넥션부터 얻는다. 감싸지 않으면 키를 고르는 순간 표시가 꺼져 있어 늘 소스가 골라진다
    return new LazyConnectionDataSourceProxy(routing(p, "sample_split"));
  }

  @Bean
  LocalContainerEntityManagerFactoryBean splitEntityManagerFactory(@Qualifier("splitDataSource") DataSource ds) {
    return emf(SPLIT, ds);
  }

  @Bean
  PlatformTransactionManager splitTx(@Qualifier("splitEntityManagerFactory") LocalContainerEntityManagerFactoryBean emf) {
    return new JpaTransactionManager(emf.getObject());
  }

  public static DataSource routing(LabDbProperties p, String schema) {
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

  public static LocalContainerEntityManagerFactoryBean emf(String unit, DataSource ds) {
    var emf = new LocalContainerEntityManagerFactoryBean();
    emf.setPersistenceUnitName(unit);
    emf.setDataSource(ds);
    emf.setPackagesToScan("lab.routing.usage");
    emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    emf.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
    return emf;
  }
}
