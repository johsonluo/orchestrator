package it.reply.orchestrator.config;

import javax.annotation.Resource;
import javax.naming.NamingException;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import it.reply.workflowManager.spring.orchestrator.annotations.WorkflowPersistenceUnit;
import it.reply.workflowManager.utils.Constants;

@Configuration
@EnableTransactionManagement(proxyTargetClass = true)
public class WorklfowPersistenceConfigTest {

  @Resource
  private Environment environment;

  @Bean(destroyMethod = "shutdown")
  public DataSource workflowDataSource() {
    EmbeddedDatabaseBuilder builder = new EmbeddedDatabaseBuilder();
    EmbeddedDatabase dataSource = builder.setType(EmbeddedDatabaseType.H2)
        .addScripts("h2-jbpm-schema.sql").build();
    return dataSource;
  }

  @Bean
  @WorkflowPersistenceUnit
  public LocalContainerEntityManagerFactoryBean workflowEntityManagerFactory()
      throws NamingException {
    LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
    factory.setPersistenceUnitName(Constants.PERSISTENCE_UNIT_NAME);
    factory.setPersistenceXmlLocation("classpath:/META-INF/persistence-test.xml");
    factory.setJtaDataSource(workflowDataSource());
    return factory;
  }
}
