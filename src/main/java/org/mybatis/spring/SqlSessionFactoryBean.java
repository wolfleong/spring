/**
 * Copyright 2010-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.mybatis.spring;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.ibatis.builder.xml.XMLConfigBuilder;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.TypeHandler;
import org.mybatis.logging.Logger;
import org.mybatis.logging.LoggerFactory;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.NestedIOException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.ClassMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.util.ClassUtils;

import static org.springframework.util.Assert.notNull;
import static org.springframework.util.Assert.state;
import static org.springframework.util.ObjectUtils.isEmpty;
import static org.springframework.util.StringUtils.hasLength;
import static org.springframework.util.StringUtils.tokenizeToStringArray;

/**
 * 实现 FactoryBean、InitializingBean、ApplicationListener 接口，负责创建 SqlSessionFactory 对象
 *
 * {@code FactoryBean} that creates a MyBatis {@code SqlSessionFactory}. This is the usual way to set up a shared
 * MyBatis {@code SqlSessionFactory} in a Spring application context; the SqlSessionFactory can then be passed to
 * MyBatis-based DAOs via dependency injection.
 *
 * Either {@code DataSourceTransactionManager} or {@code JtaTransactionManager} can be used for transaction demarcation
 * in combination with a {@code SqlSessionFactory}. JTA should be used for transactions which span multiple databases or
 * when container managed transactions (CMT) are being used.
 *
 * @author Putthiphong Boonphong
 * @author Hunter Presnall
 * @author Eduardo Macarron
 * @author Eddú Meléndez
 * @author Kazuki Shimizu
 *
 * @see #setConfigLocation
 * @see #setDataSource
 */
public class SqlSessionFactoryBean
    implements FactoryBean<SqlSessionFactory>, InitializingBean, ApplicationListener<ApplicationEvent> {

  private static final Logger LOGGER = LoggerFactory.getLogger(SqlSessionFactoryBean.class);

  private static final ResourcePatternResolver RESOURCE_PATTERN_RESOLVER = new PathMatchingResourcePatternResolver();
  private static final MetadataReaderFactory METADATA_READER_FACTORY = new CachingMetadataReaderFactory();

  /**
   * 指定 mybatis-config.xml 路径的 Resource 对象
   */
  private Resource configLocation;

  private Configuration configuration;

  /**
   * 指定 Mapper 路径的 Resource 数组
   */
  private Resource[] mapperLocations;

  private DataSource dataSource;

  private TransactionFactory transactionFactory;

  private Properties configurationProperties;

  private SqlSessionFactoryBuilder sqlSessionFactoryBuilder = new SqlSessionFactoryBuilder();

  private SqlSessionFactory sqlSessionFactory;

  // EnvironmentAware requires spring 3.1
  private String environment = SqlSessionFactoryBean.class.getSimpleName();

  private boolean failFast;

  private Interceptor[] plugins;

  private TypeHandler<?>[] typeHandlers;

  private String typeHandlersPackage;

  private Class<?>[] typeAliases;

  private String typeAliasesPackage;

  private Class<?> typeAliasesSuperType;

  private LanguageDriver[] scriptingLanguageDrivers;

  private Class<? extends LanguageDriver> defaultScriptingLanguageDriver;

  // issue #19. No default provider.
  private DatabaseIdProvider databaseIdProvider;

  private Class<? extends VFS> vfs;

  private Cache cache;

  private ObjectFactory objectFactory;

  private ObjectWrapperFactory objectWrapperFactory;

  /**
   * Sets the ObjectFactory.
   *
   * @since 1.1.2
   * @param objectFactory
   *          a custom ObjectFactory
   */
  public void setObjectFactory(ObjectFactory objectFactory) {
    this.objectFactory = objectFactory;
  }

  /**
   * Sets the ObjectWrapperFactory.
   *
   * @since 1.1.2
   * @param objectWrapperFactory
   *          a specified ObjectWrapperFactory
   */
  public void setObjectWrapperFactory(ObjectWrapperFactory objectWrapperFactory) {
    this.objectWrapperFactory = objectWrapperFactory;
  }

  /**
   * Gets the DatabaseIdProvider
   *
   * @since 1.1.0
   * @return a specified DatabaseIdProvider
   */
  public DatabaseIdProvider getDatabaseIdProvider() {
    return databaseIdProvider;
  }

  /**
   * Sets the DatabaseIdProvider. As of version 1.2.2 this variable is not initialized by default.
   *
   * @since 1.1.0
   * @param databaseIdProvider
   *          a DatabaseIdProvider
   */
  public void setDatabaseIdProvider(DatabaseIdProvider databaseIdProvider) {
    this.databaseIdProvider = databaseIdProvider;
  }

  /**
   * Gets the VFS.
   *
   * @return a specified VFS
   */
  public Class<? extends VFS> getVfs() {
    return this.vfs;
  }

  /**
   * Sets the VFS.
   *
   * @param vfs
   *          a VFS
   */
  public void setVfs(Class<? extends VFS> vfs) {
    this.vfs = vfs;
  }

  /**
   * Gets the Cache.
   *
   * @return a specified Cache
   */
  public Cache getCache() {
    return this.cache;
  }

  /**
   * Sets the Cache.
   *
   * @param cache
   *          a Cache
   */
  public void setCache(Cache cache) {
    this.cache = cache;
  }

  /**
   * Mybatis plugin list.
   *
   * @since 1.0.1
   *
   * @param plugins
   *          list of plugins
   *
   */
  public void setPlugins(Interceptor... plugins) {
    this.plugins = plugins;
  }

  /**
   * Packages to search for type aliases.
   *
   * <p>
   * Since 2.0.1, allow to specify a wildcard such as {@code com.example.*.model}.
   *
   * @since 1.0.1
   *
   * @param typeAliasesPackage
   *          package to scan for domain objects
   *
   */
  public void setTypeAliasesPackage(String typeAliasesPackage) {
    this.typeAliasesPackage = typeAliasesPackage;
  }

  /**
   * Super class which domain objects have to extend to have a type alias created. No effect if there is no package to
   * scan configured.
   *
   * @since 1.1.2
   *
   * @param typeAliasesSuperType
   *          super class for domain objects
   *
   */
  public void setTypeAliasesSuperType(Class<?> typeAliasesSuperType) {
    this.typeAliasesSuperType = typeAliasesSuperType;
  }

  /**
   * Packages to search for type handlers.
   *
   * <p>
   * Since 2.0.1, allow to specify a wildcard such as {@code com.example.*.typehandler}.
   *
   * @since 1.0.1
   *
   * @param typeHandlersPackage
   *          package to scan for type handlers
   *
   */
  public void setTypeHandlersPackage(String typeHandlersPackage) {
    this.typeHandlersPackage = typeHandlersPackage;
  }

  /**
   * Set type handlers. They must be annotated with {@code MappedTypes} and optionally with {@code MappedJdbcTypes}
   *
   * @since 1.0.1
   *
   * @param typeHandlers
   *          Type handler list
   */
  public void setTypeHandlers(TypeHandler<?>... typeHandlers) {
    this.typeHandlers = typeHandlers;
  }

  /**
   * List of type aliases to register. They can be annotated with {@code Alias}
   *
   * @since 1.0.1
   *
   * @param typeAliases
   *          Type aliases list
   */
  public void setTypeAliases(Class<?>... typeAliases) {
    this.typeAliases = typeAliases;
  }

  /**
   * If true, a final check is done on Configuration to assure that all mapped statements are fully loaded and there is
   * no one still pending to resolve includes. Defaults to false.
   *
   * @since 1.0.1
   *
   * @param failFast
   *          enable failFast
   */
  public void setFailFast(boolean failFast) {
    this.failFast = failFast;
  }

  /**
   * Set the location of the MyBatis {@code SqlSessionFactory} config file. A typical value is
   * "WEB-INF/mybatis-configuration.xml".
   *
   * @param configLocation
   *          a location the MyBatis config file
   */
  public void setConfigLocation(Resource configLocation) {
    this.configLocation = configLocation;
  }

  /**
   * Set a customized MyBatis configuration.
   *
   * @param configuration
   *          MyBatis configuration
   * @since 1.3.0
   */
  public void setConfiguration(Configuration configuration) {
    this.configuration = configuration;
  }

  /**
   * Set locations of MyBatis mapper files that are going to be merged into the {@code SqlSessionFactory} configuration
   * at runtime.
   *
   * This is an alternative to specifying "&lt;sqlmapper&gt;" entries in an MyBatis config file. This property being
   * based on Spring's resource abstraction also allows for specifying resource patterns here: e.g.
   * "classpath*:sqlmap/*-mapper.xml".
   *
   * @param mapperLocations
   *          location of MyBatis mapper files
   */
  public void setMapperLocations(Resource... mapperLocations) {
    this.mapperLocations = mapperLocations;
  }

  /**
   * Set optional properties to be passed into the SqlSession configuration, as alternative to a
   * {@code &lt;properties&gt;} tag in the configuration xml file. This will be used to resolve placeholders in the
   * config file.
   *
   * @param sqlSessionFactoryProperties
   *          optional properties for the SqlSessionFactory
   */
  public void setConfigurationProperties(Properties sqlSessionFactoryProperties) {
    this.configurationProperties = sqlSessionFactoryProperties;
  }

  /**
   * Set the JDBC {@code DataSource} that this instance should manage transactions for. The {@code DataSource} should
   * match the one used by the {@code SqlSessionFactory}: for example, you could specify the same JNDI DataSource for
   * both.
   *
   * A transactional JDBC {@code Connection} for this {@code DataSource} will be provided to application code accessing
   * this {@code DataSource} directly via {@code DataSourceUtils} or {@code DataSourceTransactionManager}.
   *
   * The {@code DataSource} specified here should be the target {@code DataSource} to manage transactions for, not a
   * {@code TransactionAwareDataSourceProxy}. Only data access code may work with
   * {@code TransactionAwareDataSourceProxy}, while the transaction manager needs to work on the underlying target
   * {@code DataSource}. If there's nevertheless a {@code TransactionAwareDataSourceProxy} passed in, it will be
   * unwrapped to extract its target {@code DataSource}.
   *
   * @param dataSource
   *          a JDBC {@code DataSource}
   *
   */
  public void setDataSource(DataSource dataSource) {
    if (dataSource instanceof TransactionAwareDataSourceProxy) {
      // If we got a TransactionAwareDataSourceProxy, we need to perform
      // transactions for its underlying target DataSource, else data
      // access code won't see properly exposed transactions (i.e.
      // transactions for the target DataSource).
      this.dataSource = ((TransactionAwareDataSourceProxy) dataSource).getTargetDataSource();
    } else {
      this.dataSource = dataSource;
    }
  }

  /**
   * Sets the {@code SqlSessionFactoryBuilder} to use when creating the {@code SqlSessionFactory}.
   *
   * This is mainly meant for testing so that mock SqlSessionFactory classes can be injected. By default,
   * {@code SqlSessionFactoryBuilder} creates {@code DefaultSqlSessionFactory} instances.
   *
   * @param sqlSessionFactoryBuilder
   *          a SqlSessionFactoryBuilder
   *
   */
  public void setSqlSessionFactoryBuilder(SqlSessionFactoryBuilder sqlSessionFactoryBuilder) {
    this.sqlSessionFactoryBuilder = sqlSessionFactoryBuilder;
  }

  /**
   * Set the MyBatis TransactionFactory to use. Default is {@code SpringManagedTransactionFactory}
   *
   * The default {@code SpringManagedTransactionFactory} should be appropriate for all cases: be it Spring transaction
   * management, EJB CMT or plain JTA. If there is no active transaction, SqlSession operations will execute SQL
   * statements non-transactionally.
   *
   * <b>It is strongly recommended to use the default {@code TransactionFactory}.</b> If not used, any attempt at
   * getting an SqlSession through Spring's MyBatis framework will throw an exception if a transaction is active.
   *
   * @see SpringManagedTransactionFactory
   * @param transactionFactory
   *          the MyBatis TransactionFactory
   */
  public void setTransactionFactory(TransactionFactory transactionFactory) {
    this.transactionFactory = transactionFactory;
  }

  /**
   * <b>NOTE:</b> This class <em>overrides</em> any {@code Environment} you have set in the MyBatis config file. This is
   * used only as a placeholder name. The default value is {@code SqlSessionFactoryBean.class.getSimpleName()}.
   *
   * @param environment
   *          the environment name
   */
  public void setEnvironment(String environment) {
    this.environment = environment;
  }

  /**
   * Set scripting language drivers.
   *
   * @param scriptingLanguageDrivers
   *          scripting language drivers
   * @since 2.0.2
   */
  public void setScriptingLanguageDrivers(LanguageDriver... scriptingLanguageDrivers) {
    this.scriptingLanguageDrivers = scriptingLanguageDrivers;
  }

  /**
   * Set a default scripting language driver class.
   *
   * @param defaultScriptingLanguageDriver
   *          A default scripting language driver class
   * @since 2.0.2
   */
  public void setDefaultScriptingLanguageDriver(Class<? extends LanguageDriver> defaultScriptingLanguageDriver) {
    this.defaultScriptingLanguageDriver = defaultScriptingLanguageDriver;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void afterPropertiesSet() throws Exception {
    notNull(dataSource, "Property 'dataSource' is required");
    notNull(sqlSessionFactoryBuilder, "Property 'sqlSessionFactoryBuilder' is required");
    state((configuration == null && configLocation == null) || !(configuration != null && configLocation != null),
        "Property 'configuration' and 'configLocation' can not specified with together");

    // 创建 SqlSessionFactory 对象
    this.sqlSessionFactory = buildSqlSessionFactory();
  }

  /**
   * Build a {@code SqlSessionFactory} instance.
   *
   * The default implementation uses the standard MyBatis {@code XMLConfigBuilder} API to build a
   * {@code SqlSessionFactory} instance based on an Reader. Since 1.3.0, it can be specified a {@link Configuration}
   * instance directly(without config file).
   *
   * @return SqlSessionFactory
   * @throws Exception
   *           if configuration is failed
   */
  protected SqlSessionFactory buildSqlSessionFactory() throws Exception {

    //全局配置
    final Configuration targetConfiguration;

    // XMLConfigBuilder 对象
    XMLConfigBuilder xmlConfigBuilder = null;
    //如果全局配置不为 null
    if (this.configuration != null) {
      //设置配置
      targetConfiguration = this.configuration;
      //如果 targetConfiguration 上的变量是空
      if (targetConfiguration.getVariables() == null) {
        //设置来自 configurationProperties 的变量
        targetConfiguration.setVariables(this.configurationProperties);
        //如果 targetConfiguration 上的 properties 不为 null
      } else if (this.configurationProperties != null) {
        //合并 targetConfiguration 和 configurationProperties 上的 properties
        targetConfiguration.getVariables().putAll(this.configurationProperties);
      }
      //如果配置资源文件不为 null
    } else if (this.configLocation != null) {
      //创建 XMLConfigBuilder
      xmlConfigBuilder = new XMLConfigBuilder(this.configLocation.getInputStream(), null, this.configurationProperties);
      //获取新建的配置
      targetConfiguration = xmlConfigBuilder.getConfiguration();
      //如果 configuration 和 configLocation 都为 null
    } else {
      LOGGER.debug(
          () -> "Property 'configuration' or 'configLocation' not specified, using default MyBatis Configuration");
      //手动创建 Configuration
      targetConfiguration = new Configuration();
      //设置配置的 configurationProperties
      Optional.ofNullable(this.configurationProperties).ifPresent(targetConfiguration::setVariables);
    }

    //设置 objectFactory
    Optional.ofNullable(this.objectFactory).ifPresent(targetConfiguration::setObjectFactory);
    //设置 objectWrapperFactory
    Optional.ofNullable(this.objectWrapperFactory).ifPresent(targetConfiguration::setObjectWrapperFactory);
    //设置 vfs
    Optional.ofNullable(this.vfs).ifPresent(targetConfiguration::setVfsImpl);

    //设置 typeAliasesPackage, 注册别名类
    if (hasLength(this.typeAliasesPackage)) {
      //扫描指定包下的类
      scanClasses(this.typeAliasesPackage, this.typeAliasesSuperType).stream()
          .filter(clazz -> !clazz.isAnonymousClass()).filter(clazz -> !clazz.isInterface())
          .filter(clazz -> !clazz.isMemberClass()).forEach(targetConfiguration.getTypeAliasRegistry()::registerAlias);
    }

    //如果类型别名不为null
    if (!isEmpty(this.typeAliases)) {
      Stream.of(this.typeAliases).forEach(typeAlias -> {
        //注册类别名
        targetConfiguration.getTypeAliasRegistry().registerAlias(typeAlias);
        LOGGER.debug(() -> "Registered type alias: '" + typeAlias + "'");
      });
    }

    //如果插件不为空, 添加
    if (!isEmpty(this.plugins)) {
      Stream.of(this.plugins).forEach(plugin -> {
        targetConfiguration.addInterceptor(plugin);
        LOGGER.debug(() -> "Registered plugin: '" + plugin + "'");
      });
    }

    //处理 TypeHandler 的包
    if (hasLength(this.typeHandlersPackage)) {
      scanClasses(this.typeHandlersPackage, TypeHandler.class).stream().filter(clazz -> !clazz.isAnonymousClass())
          .filter(clazz -> !clazz.isInterface()).filter(clazz -> !Modifier.isAbstract(clazz.getModifiers()))
          .forEach(targetConfiguration.getTypeHandlerRegistry()::register);
    }

    //处理 typeHandler
    if (!isEmpty(this.typeHandlers)) {
      Stream.of(this.typeHandlers).forEach(typeHandler -> {
        targetConfiguration.getTypeHandlerRegistry().register(typeHandler);
        LOGGER.debug(() -> "Registered type handler: '" + typeHandler + "'");
      });
    }

    //处理 LanguageDriver
    if (!isEmpty(this.scriptingLanguageDrivers)) {
      Stream.of(this.scriptingLanguageDrivers).forEach(languageDriver -> {
        targetConfiguration.getLanguageRegistry().register(languageDriver);
        LOGGER.debug(() -> "Registered scripting language driver: '" + languageDriver + "'");
      });
    }

    //如果有默认的 LanguageDriver , 设置
    Optional.ofNullable(this.defaultScriptingLanguageDriver)
        .ifPresent(targetConfiguration::setDefaultScriptingLanguage);

    //设置 databaseIdProvider
    if (this.databaseIdProvider != null) {// fix #64 set databaseId before parse mapper xmls
      try {
        targetConfiguration.setDatabaseId(this.databaseIdProvider.getDatabaseId(this.dataSource));
      } catch (SQLException e) {
        throw new NestedIOException("Failed getting a databaseId", e);
      }
    }

    //如果有自定义缓存, 则设置
    Optional.ofNullable(this.cache).ifPresent(targetConfiguration::addCache);

    //如果 xmlConfigBuilder 不为 null
    if (xmlConfigBuilder != null) {
      try {
        //解析
        xmlConfigBuilder.parse();
        LOGGER.debug(() -> "Parsed configuration file: '" + this.configLocation + "'");
      } catch (Exception ex) {
        throw new NestedIOException("Failed to parse config resource: " + this.configLocation, ex);
      } finally {
        ErrorContext.instance().reset();
      }
    }

    //设置 Environment, 并且设置事务工厂
    targetConfiguration.setEnvironment(new Environment(this.environment,
        this.transactionFactory == null ? new SpringManagedTransactionFactory() : this.transactionFactory,
        this.dataSource));

    //如果 mapperLocations 不为 null
    if (this.mapperLocations != null) {
      if (this.mapperLocations.length == 0) {
        LOGGER.warn(() -> "Property 'mapperLocations' was specified but matching resources are not found.");
      } else {
        //遍历, 逐个解析
        for (Resource mapperLocation : this.mapperLocations) {
          if (mapperLocation == null) {
            continue;
          }
          try {
            XMLMapperBuilder xmlMapperBuilder = new XMLMapperBuilder(mapperLocation.getInputStream(),
                targetConfiguration, mapperLocation.toString(), targetConfiguration.getSqlFragments());
            xmlMapperBuilder.parse();
          } catch (Exception e) {
            throw new NestedIOException("Failed to parse mapping resource: '" + mapperLocation + "'", e);
          } finally {
            ErrorContext.instance().reset();
          }
          LOGGER.debug(() -> "Parsed mapper file: '" + mapperLocation + "'");
        }
      }
    } else {
      LOGGER.debug(() -> "Property 'mapperLocations' was not specified.");
    }

    //创建 SqlSessionFactory
    return this.sqlSessionFactoryBuilder.build(targetConfiguration);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public SqlSessionFactory getObject() throws Exception {
    //如果 sqlSessionFactory 为 null
    if (this.sqlSessionFactory == null) {
      //调用 afterPropertiesSet 进行初始化
      afterPropertiesSet();
    }

    //返回指定的 sqlSessionFactory
    return this.sqlSessionFactory;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Class<? extends SqlSessionFactory> getObjectType() {
    //返回 SqlSessionFactory 的类型
    return this.sqlSessionFactory == null ? SqlSessionFactory.class : this.sqlSessionFactory.getClass();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isSingleton() {
    //单例, 全局只有一个
    return true;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void onApplicationEvent(ApplicationEvent event) {
    //当 failFast 为true , 且是事件类型是 ContextRefreshedEvent
    if (failFast && event instanceof ContextRefreshedEvent) {
      //如果 MapperStatement 们，没有都初始化完成，会抛出 IncompleteElementException 异常
      // fail-fast -> check all statements are completed
      this.sqlSessionFactory.getConfiguration().getMappedStatementNames();
    }
  }

  private Set<Class<?>> scanClasses(String packagePatterns, Class<?> assignableType) throws IOException {
    //保存类
    Set<Class<?>> classes = new HashSet<>();
    //包名可能有多个, 用指定的分割符切割
    String[] packagePatternArray = tokenizeToStringArray(packagePatterns,
        ConfigurableApplicationContext.CONFIG_LOCATION_DELIMITERS);
    //遍历所有的包
    for (String packagePattern : packagePatternArray) {
      //获取包下所有类资源
      Resource[] resources = RESOURCE_PATTERN_RESOLVER.getResources(ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX
          + ClassUtils.convertClassNameToResourcePath(packagePattern) + "/**/*.class");
      //遍历资源
      for (Resource resource : resources) {
        try {
          //获取类的元数据
          ClassMetadata classMetadata = METADATA_READER_FACTORY.getMetadataReader(resource).getClassMetadata();
          //加载类
          Class<?> clazz = Resources.classForName(classMetadata.getClassName());
          //如果指定父类是 null , 或者此类是 assignableType 的子类, 则保存
          if (assignableType == null || assignableType.isAssignableFrom(clazz)) {
            classes.add(clazz);
          }
        } catch (Throwable e) {
          LOGGER.warn(() -> "Cannot load the '" + resource + "'. Cause by " + e.toString());
        }
      }
    }
    //返回解析的类
    return classes;
  }

}
