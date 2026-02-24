/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.zeppelin.jdbc;

import static org.apache.commons.lang3.StringUtils.containsIgnoreCase;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.apache.commons.lang3.StringUtils.isNotEmpty;
import static org.apache.hadoop.security.UserGroupInformation.AuthenticationMethod.KERBEROS;

import org.apache.commons.dbcp2.ConnectionFactory;
import org.apache.commons.dbcp2.DriverManagerConnectionFactory;
import org.apache.commons.dbcp2.PoolableConnectionFactory;
import org.apache.commons.dbcp2.PoolingDriver;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.pool2.ObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.alias.CredentialProvider;
import org.apache.hadoop.security.alias.CredentialProviderFactory;
import org.apache.zeppelin.interpreter.SingleRowInterpreterResult;
import org.apache.zeppelin.interpreter.ZeppelinContext;
import org.apache.zeppelin.interpreter.util.SqlSplitter;
import org.apache.zeppelin.jdbc.hive.HiveUtils;
import org.apache.zeppelin.tabledata.TableDataUtils;
import org.apache.zeppelin.util.PropertiesUtil;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivilegedExceptionAction;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.zeppelin.interpreter.InterpreterContext;
import org.apache.zeppelin.interpreter.InterpreterException;
import org.apache.zeppelin.interpreter.InterpreterResult;
import org.apache.zeppelin.interpreter.InterpreterResult.Code;
import org.apache.zeppelin.interpreter.KerberosInterpreter;
import org.apache.zeppelin.interpreter.ResultMessages;
import org.apache.zeppelin.interpreter.thrift.InterpreterCompletion;
import org.apache.zeppelin.jdbc.security.JDBCSecurityImpl;
import org.apache.zeppelin.scheduler.Scheduler;
import org.apache.zeppelin.scheduler.SchedulerFactory;
import org.apache.zeppelin.user.UserCredentials;
import org.apache.zeppelin.user.UsernamePassword;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * JDBC interpreter for Zeppelin. This interpreter can also be used for accessing HAWQ,
 * GreenplumDB, MariaDB, MySQL, Postgres and Redshift.
 *
 * <ul>
 * <li>{@code default.url} - JDBC URL to connect to.</li>
 * <li>{@code default.user} - JDBC user name..</li>
 * <li>{@code default.password} - JDBC password..</li>
 * <li>{@code default.driver.name} - JDBC driver name.</li>
 * <li>{@code common.max.result} - Max number of SQL result to display.</li>
 * </ul>
 *
 * <p>
 * How to use: <br/>
 * {@code %jdbc.sql} <br/>
 * {@code
 * SELECT store_id, count(*)
 * FROM retail_demo.order_lineitems_pxf
 * GROUP BY store_id;
 * }
 * </p>
 */
public class JDBCInterpreter extends KerberosInterpreter {
  private static final Logger LOGGER = LoggerFactory.getLogger(JDBCInterpreter.class);

  static final String COMMON_KEY = "common";
  static final String MAX_LINE_KEY = "max_count";
  static final int MAX_LINE_DEFAULT = 1000;

  static final String DEFAULT_KEY = "default";
  static final String DRIVER_KEY = "driver";
  static final String URL_KEY = "url";
  static final String USER_KEY = "user";
  static final String PASSWORD_KEY = "password";
  static final String PRECODE_KEY = "precode";
  static final String STATEMENT_PRECODE_KEY = "statementPrecode";
  static final String COMPLETER_SCHEMA_FILTERS_KEY = "completer.schemaFilters";
  static final String COMPLETER_TTL_KEY = "completer.ttlInSeconds";
  static final String DEFAULT_COMPLETER_TTL = "120";
  static final String JDBC_JCEKS_FILE = "jceks.file";
  static final String JDBC_JCEKS_CREDENTIAL_KEY = "jceks.credentialKey";
  static final String PRECODE_KEY_TEMPLATE = "%s.precode";
  static final String STATEMENT_PRECODE_KEY_TEMPLATE = "%s.statementPrecode";
  static final String DOT = ".";

  private static final char WHITESPACE = ' ';
  private static final char NEWLINE = '\n';
  private static final char TAB = '\t';
  private static final String TABLE_MAGIC_TAG = "%table ";
  private static final String EXPLAIN_PREDICATE = "EXPLAIN ";
  private static final String CANCEL_REASON = "cancel_reason";

  static final String COMMON_MAX_LINE = COMMON_KEY + DOT + MAX_LINE_KEY;

  static final String DEFAULT_DRIVER = DEFAULT_KEY + DOT + DRIVER_KEY;
  static final String DEFAULT_URL = DEFAULT_KEY + DOT + URL_KEY;
  static final String DEFAULT_USER = DEFAULT_KEY + DOT + USER_KEY;
  static final String DEFAULT_PASSWORD = DEFAULT_KEY + DOT + PASSWORD_KEY;
  static final String DEFAULT_PRECODE = DEFAULT_KEY + DOT + PRECODE_KEY;
  static final String DEFAULT_STATEMENT_PRECODE = DEFAULT_KEY + DOT + STATEMENT_PRECODE_KEY;

  static final String EMPTY_COLUMN_VALUE = "";

  private static final String CONCURRENT_EXECUTION_KEY = "zeppelin.jdbc.concurrent.use";
  private static final String CONCURRENT_EXECUTION_COUNT =
          "zeppelin.jdbc.concurrent.max_connection";
  private static final String DBCP_STRING = "jdbc:apache:commons:dbcp:";
  private static final String MAX_ROWS_KEY = "zeppelin.jdbc.maxRows";
  private static final String FAIL_FAST_VALIDATE_URL = "http://spark-event-listener.prd.meesho.int/api/validate";

  private static final Set<String> PRESTO_PROPERTIES = new HashSet<>(Arrays.asList(
          "user", "password",
          "socksProxy", "httpProxy", "clientTags", "applicationNamePrefix", "accessToken",
          "SSL", "SSLKeyStorePath", "SSLKeyStorePassword", "SSLTrustStorePath",
          "SSLTrustStorePassword", "KerberosRemoteServiceName", "KerberosPrincipal",
          "KerberosUseCanonicalHostname", "KerberosServicePrincipalPattern",
          "KerberosConfigPath", "KerberosKeytabPath", "KerberosCredentialCachePath",
          "extraCredentials", "roles", "sessionProperties"));

  private static final String ALLOW_LOAD_LOCAL_IN_FILE_NAME = "allowLoadLocalInfile";

  private static final String AUTO_DESERIALIZE = "autoDeserialize";

  private static final String ALLOW_LOCAL_IN_FILE_NAME = "allowLocalInfile";

  private static final String ALLOW_URL_IN_LOCAL_IN_FILE_NAME = "allowUrlInLocalInfile";

  // database --> Properties
  private final HashMap<String, Properties> basePropertiesMap;
  // username --> User Configuration
  private final HashMap<String, JDBCUserConfigurations> jdbcUserConfigurationsMap;
  private final HashMap<String, SqlCompleter> sqlCompletersMap;

  private int maxLineResults;
  private int maxRows;
  private SqlSplitter sqlSplitter;

  private Map<String, ScheduledExecutorService> refreshExecutorServices = new HashMap<>();
  private Map<String, Boolean> isFirstRefreshMap = new HashMap<>();
  private Map<String, Boolean> paragraphCancelMap = new HashMap<>();

  public JDBCInterpreter(Properties property) {
    super(property);
    jdbcUserConfigurationsMap = new HashMap<>();
    basePropertiesMap = new HashMap<>();
    sqlCompletersMap = new HashMap<>();
    maxLineResults = MAX_LINE_DEFAULT;
  }

  @Override
  public ZeppelinContext getZeppelinContext() {
    return null;
  }

  @Override
  protected boolean runKerberosLogin() {
    // Initialize UGI before using
    Configuration conf = new org.apache.hadoop.conf.Configuration();
    conf.set("hadoop.security.authentication", KERBEROS.toString());
    UserGroupInformation.setConfiguration(conf);
    try {
      if (UserGroupInformation.isLoginKeytabBased()) {
        LOGGER.debug("Trying relogin from keytab");
        UserGroupInformation.getLoginUser().reloginFromKeytab();
        return true;
      } else if (UserGroupInformation.isLoginTicketBased()) {
        LOGGER.debug("Trying relogin from ticket cache");
        UserGroupInformation.getLoginUser().reloginFromTicketCache();
        return true;
      }
    } catch (Exception e) {
      LOGGER.error("Unable to run kinit for zeppelin", e);
    }
    LOGGER.debug("Neither Keytab nor ticket based login. " +
        "runKerberosLogin() returning false");
    return false;
  }

  @Override
  public void open() {
    super.open();
    for (String propertyKey : properties.stringPropertyNames()) {
      LOGGER.debug("propertyKey: {}", propertyKey);
      String[] keyValue = propertyKey.split("\\.", 2);
      if (2 == keyValue.length) {
        LOGGER.debug("key: {}, value: {}", keyValue[0], keyValue[1]);

        Properties prefixProperties;
        if (basePropertiesMap.containsKey(keyValue[0])) {
          prefixProperties = basePropertiesMap.get(keyValue[0]);
        } else {
          prefixProperties = new Properties();
          basePropertiesMap.put(keyValue[0].trim(), prefixProperties);
        }
        prefixProperties.put(keyValue[1].trim(), getProperty(propertyKey));
      }
    }

    Set<String> removeKeySet = new HashSet<>();
    for (String key : basePropertiesMap.keySet()) {
      if (!COMMON_KEY.equals(key)) {
        Properties properties = basePropertiesMap.get(key);
        if (!properties.containsKey(DRIVER_KEY) || !properties.containsKey(URL_KEY)) {
          LOGGER.error("{} will be ignored. {}.{} and {}.{} is mandatory.",
              key, DRIVER_KEY, key, key, URL_KEY);
          removeKeySet.add(key);
        }
      }
    }

    for (String key : removeKeySet) {
      basePropertiesMap.remove(key);
    }
    LOGGER.debug("JDBC PropertiesMap: {}", basePropertiesMap);

    setMaxLineResults();
    setMaxRows();

    //TODO(zjffdu) Set different sql splitter for different sql dialects.
    this.sqlSplitter = new SqlSplitter();
  }

  protected boolean isKerboseEnabled() {
    if (!isEmpty(getProperty("zeppelin.jdbc.auth.type"))) {
      UserGroupInformation.AuthenticationMethod authType = JDBCSecurityImpl.getAuthType(properties);
      if (authType.equals(KERBEROS)) {
        return true;
      }
    }
    return false;
  }

  private void setMaxLineResults() {
    if (basePropertiesMap.containsKey(COMMON_KEY) &&
        basePropertiesMap.get(COMMON_KEY).containsKey(MAX_LINE_KEY)) {
      maxLineResults = Integer.valueOf(basePropertiesMap.get(COMMON_KEY).getProperty(MAX_LINE_KEY));
    }
  }

  /**
   * Fetch MAX_ROWS_KEYS value from property file and set it to
   * "maxRows" value.
   */
  private void setMaxRows() {
    maxRows = Integer.valueOf(getProperty(MAX_ROWS_KEY, "1000"));
  }

  private SqlCompleter createOrUpdateSqlCompleter(SqlCompleter sqlCompleter,
      final Connection connection, String propertyKey, final String buf, final int cursor) {
    String schemaFiltersKey = String.format("%s.%s", propertyKey, COMPLETER_SCHEMA_FILTERS_KEY);
    String sqlCompleterTtlKey = String.format("%s.%s", propertyKey, COMPLETER_TTL_KEY);
    final String schemaFiltersString = getProperty(schemaFiltersKey);
    int ttlInSeconds = Integer.valueOf(
        StringUtils.defaultIfEmpty(getProperty(sqlCompleterTtlKey), DEFAULT_COMPLETER_TTL)
    );
    final SqlCompleter completer;
    if (sqlCompleter == null) {
      completer = new SqlCompleter(ttlInSeconds);
    } else {
      completer = sqlCompleter;
    }
    ExecutorService executorService = Executors.newFixedThreadPool(1);
    executorService.execute(new Runnable() {
      @Override
      public void run() {
        completer.createOrUpdateFromConnection(connection, schemaFiltersString, buf, cursor);
      }
    });

    executorService.shutdown();

    try {
      // protection to release connection
      executorService.awaitTermination(3, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      LOGGER.warn("Completion timeout", e);
      if (connection != null) {
        try {
          connection.close();
        } catch (SQLException e1) {
          LOGGER.warn("Error close connection", e1);
        }
      }
    }
    return completer;
  }

  private void initStatementMap() {
    for (JDBCUserConfigurations configurations : jdbcUserConfigurationsMap.values()) {
      try {
        configurations.initStatementMap();
      } catch (Exception e) {
        LOGGER.error("Error while closing paragraphIdStatementMap statement...", e);
      }
    }
  }

  private void initConnectionPoolMap() {
    for (String key : jdbcUserConfigurationsMap.keySet()) {
      try {
        closeDBPool(key);
      } catch (SQLException e) {
        LOGGER.error("Error while closing database pool.", e);
      }
      try {
        JDBCUserConfigurations configurations = jdbcUserConfigurationsMap.get(key);
        configurations.initConnectionPoolMap();
      } catch (SQLException e) {
        LOGGER.error("Error while closing initConnectionPoolMap.", e);
      }
    }
  }

  @Override
  public void close() {
    super.close();
    try {
      initStatementMap();
      initConnectionPoolMap();
    } catch (Exception e) {
      LOGGER.error("Error while closing...", e);
    }
  }

  public static ValidationResponse sendValidationRequest(ValidationRequest request) throws Exception {
    HttpURLConnection connection = createConnection();
    sendRequest(connection, request);
    return readResponse(connection);
  }

  private static HttpURLConnection createConnection() throws Exception {
    URL url = new URL(FAIL_FAST_VALIDATE_URL);
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setRequestMethod("POST");
    connection.setRequestProperty("Content-Type", "application/json");
    connection.setDoOutput(true); // Enable sending request body
    return connection;
  }

  private static void sendRequest(HttpURLConnection connection, ValidationRequest request) throws Exception {
    try (OutputStream os = connection.getOutputStream()) {
      String jsonRequest = request.toJson();
      byte[] input = jsonRequest.getBytes("utf-8");
      os.write(input, 0, input.length);
    }
  }

  private static ValidationResponse readResponse(HttpURLConnection connection) throws Exception {
    int statusCode = connection.getResponseCode();
    BufferedReader reader;

    if (statusCode == HttpURLConnection.HTTP_OK) {
      reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "utf-8"));
    } else {
      reader = new BufferedReader(new InputStreamReader(connection.getErrorStream(), "utf-8"));
    }

    StringBuilder responseBuilder = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) {
      responseBuilder.append(line.trim());
    }

    reader.close();
    connection.disconnect();

    return ValidationResponse.fromJson(responseBuilder.toString());
  }

  /* Get user of this sql.
   * 1. If shiro is enabled, use the login user
   * 2. Otherwise try to get it from interpreter setting, e.g. default.user
   */
  private String getUser(InterpreterContext context) {
    String user = context.getAuthenticationInfo().getUser();

    if ("anonymous".equalsIgnoreCase(user) && basePropertiesMap.containsKey(DEFAULT_KEY)) {
      String userInProperty = basePropertiesMap.get(DEFAULT_KEY).getProperty(USER_KEY);
      if (StringUtils.isNotBlank(userInProperty)) {
        user = userInProperty;
      }
    }
    return user;
  }

  private String getEntityName(String replName, String propertyKey) {
    if ("jdbc".equals(replName)) {
      return propertyKey;
    } else {
      return replName;
    }
  }

  /**
   * Builds a stable, compact pool name for the given user+url combination.
   * Uses the first 16 hex chars of the SHA-256 hash of the URL so the name is
   * safe for use as a DBCP pool key regardless of special characters in the URL.
   */
  static String buildPoolName(String user, String url) {
    String urlHash;
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(url.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(16);
      for (int i = 0; i < 8; i++) { // 8 bytes = 16 hex chars
        hex.append(String.format("%02x", hash[i]));
      }
      urlHash = hex.toString();
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is always available in Java SE; this branch is unreachable in practice
      LOGGER.warn("SHA-256 not available, falling back to sanitized URL for pool name");
      urlHash = url.replaceAll("[^a-zA-Z0-9]", "_");
    }
    return DEFAULT_KEY + user + "_" + urlHash;
  }

  private String getJDBCDriverName(String user, String url) {
    return DBCP_STRING + buildPoolName(user, url);
  }

  private boolean existAccountInBaseProperty(String propertyKey) {
    return basePropertiesMap.get(propertyKey).containsKey(USER_KEY) &&
        !isEmpty((String) basePropertiesMap.get(propertyKey).get(USER_KEY)) &&
        basePropertiesMap.get(propertyKey).containsKey(PASSWORD_KEY);
  }

  private UsernamePassword getUsernamePassword(InterpreterContext interpreterContext,
                                               String entity) {
    UserCredentials uc = interpreterContext.getAuthenticationInfo().getUserCredentials();
    if (uc != null) {
      return uc.getUsernamePassword(entity);
    }
    return null;
  }

  public JDBCUserConfigurations getJDBCConfiguration(String user) {
    JDBCUserConfigurations jdbcUserConfigurations = jdbcUserConfigurationsMap.get(user);

    if (jdbcUserConfigurations == null) {
      jdbcUserConfigurations = new JDBCUserConfigurations();
      jdbcUserConfigurationsMap.put(user, jdbcUserConfigurations);
    }

    return jdbcUserConfigurations;
  }

  private void closeDBPool(String user) throws SQLException {
    closeDBPool(user, null);
  }

  /**
   * Close database pool for user and optional URL
   * @param user Username
   * @param url URL to close specific pool, or null to close all pools for the user
   */
  private void closeDBPool(String user, String url) throws SQLException {
    if (url != null && !url.isEmpty()) {
      // Close only the pool for this specific URL.
      // We use getPoolingDriver() (non-destructive) so that other pools registered
      // for this user remain accessible — avoids the pool-leak bug where
      // removeDBDriverPool() would orphan all other pools.
      String poolName = buildPoolName(user, url);
      PoolingDriver driver = getJDBCConfiguration(user).getPoolingDriver();
      if (driver != null) {
        try {
          driver.closePool(poolName);
          LOGGER.info("Closed pool for user: {}, url: {}", user, url);
        } catch (Exception e) {
          LOGGER.warn("Could not close pool '{}': {}", poolName, e.getMessage());
        }
        getJDBCConfiguration(user).removePoolName(poolName);
      }
    } else {
      // Close all pools for this user and remove the driver reference.
      PoolingDriver poolingDriver = getJDBCConfiguration(user).removeDBDriverPool();
      if (poolingDriver != null) {
        String[] poolNames = poolingDriver.getPoolNames();
        String userPrefix = DEFAULT_KEY + user;
        for (String poolName : poolNames) {
          if (poolName.startsWith(userPrefix)) {
            try {
              poolingDriver.closePool(poolName);
              LOGGER.info("Closed pool: {}", poolName);
            } catch (Exception e) {
              LOGGER.warn("Could not close pool '{}': {}", poolName, e.getMessage());
            }
          }
        }
        LOGGER.info("Closed all pools for user: {}", user);
      }
    }
  }

  private void setUserProperty(InterpreterContext context)
      throws SQLException, IOException, InterpreterException {

    String user = getUser(context);
    JDBCUserConfigurations jdbcUserConfigurations = getJDBCConfiguration(user);
    if (basePropertiesMap.get(DEFAULT_KEY).containsKey(USER_KEY) &&
        !basePropertiesMap.get(DEFAULT_KEY).getProperty(USER_KEY).isEmpty()) {
      String password = getPassword(basePropertiesMap.get(DEFAULT_KEY));
      if (!isEmpty(password)) {
        basePropertiesMap.get(DEFAULT_KEY).setProperty(PASSWORD_KEY, password);
      }
    }
    jdbcUserConfigurations.setProperty(basePropertiesMap.get(DEFAULT_KEY));
    if (existAccountInBaseProperty(DEFAULT_KEY)) {
      return;
    }

    UsernamePassword usernamePassword = getUsernamePassword(context,
            getEntityName(context.getReplName(), DEFAULT_KEY));
    if (usernamePassword != null) {
      jdbcUserConfigurations.cleanUserProperty();
      jdbcUserConfigurations.setUserProperty(usernamePassword);
    } else {
      closeDBPool(user);
    }
  }

  private void configConnectionPool(GenericObjectPool connectionPool, Properties properties) {
    boolean testOnBorrow = "true".equalsIgnoreCase(properties.getProperty("testOnBorrow"));
    boolean testOnCreate = "true".equalsIgnoreCase(properties.getProperty("testOnCreate"));
    boolean testOnReturn = "true".equalsIgnoreCase(properties.getProperty("testOnReturn"));
    boolean testWhileIdle = "true".equalsIgnoreCase(properties.getProperty("testWhileIdle"));
    long timeBetweenEvictionRunsMillis = PropertiesUtil.getLong(
        properties, "timeBetweenEvictionRunsMillis", -1L);

    long maxWaitMillis = PropertiesUtil.getLong(properties, "maxWaitMillis", -1L);
    int maxIdle = PropertiesUtil.getInt(properties, "maxIdle", 8);
    int minIdle = PropertiesUtil.getInt(properties, "minIdle", 0);
    int maxTotal = PropertiesUtil.getInt(properties, "maxTotal", -1);

    connectionPool.setTestOnBorrow(testOnBorrow);
    connectionPool.setTestOnCreate(testOnCreate);
    connectionPool.setTestOnReturn(testOnReturn);
    connectionPool.setTestWhileIdle(testWhileIdle);
    connectionPool.setTimeBetweenEvictionRunsMillis(timeBetweenEvictionRunsMillis);
    connectionPool.setMaxIdle(maxIdle);
    connectionPool.setMinIdle(minIdle);
    connectionPool.setMaxTotal(maxTotal);
    connectionPool.setMaxWaitMillis(maxWaitMillis);
  }

  private void createConnectionPool(String url, String user,
      Properties properties) throws SQLException, ClassNotFoundException {

    LOGGER.info("Creating connection pool for url: {}, user: {}", url, user);

    /* Remove properties that is not valid properties for presto/trino by checking driver key.
     * - Presto: com.facebook.presto.jdbc.PrestoDriver
     * - Trino(ex. PrestoSQL): io.trino.jdbc.TrinoDriver / io.prestosql.jdbc.PrestoDriver
     */
    String driverClass = properties.getProperty(DRIVER_KEY);
    if (driverClass != null && (driverClass.equals("com.facebook.presto.jdbc.PrestoDriver")
            || driverClass.equals("io.prestosql.jdbc.PrestoDriver")
            || driverClass.equals("io.trino.jdbc.TrinoDriver"))) {
      for (String key : properties.stringPropertyNames()) {
        if (!PRESTO_PROPERTIES.contains(key)) {
          properties.remove(key);
        }
      }
    }

    ConnectionFactory connectionFactory =
            new DriverManagerConnectionFactory(url, properties);

    PoolableConnectionFactory poolableConnectionFactory = new PoolableConnectionFactory(
            connectionFactory, null);
    final String maxConnectionLifetime =
        StringUtils.defaultIfEmpty(getProperty("zeppelin.jdbc.maxConnLifetime"), "-1");
    poolableConnectionFactory.setMaxConnLifetimeMillis(Long.parseLong(maxConnectionLifetime));
    poolableConnectionFactory.setValidationQuery(
            PropertiesUtil.getString(properties, "validationQuery", "show databases"));
    ObjectPool connectionPool = new GenericObjectPool(poolableConnectionFactory);
    this.configConnectionPool((GenericObjectPool) connectionPool, properties);

    poolableConnectionFactory.setPool(connectionPool);
    Class.forName(driverClass);

    // Reuse the existing PoolingDriver if one has already been registered for this user,
    // rather than creating a new instance each time. All PoolingDriver instances share the
    // same global DBCP registry, so creating multiple instances is wasteful and makes
    // cleanup harder (removeDBDriverPool only retains the last reference).
    PoolingDriver driver = getJDBCConfiguration(user).getPoolingDriver();
    if (driver == null) {
      driver = new PoolingDriver();
    }
    String poolName = buildPoolName(user, url);
    driver.registerPool(poolName, connectionPool);
    getJDBCConfiguration(user).saveDBDriverPool(driver, poolName);
  }

  private Connection getConnectionFromPool(String url, String user,
      Properties properties) throws SQLException, ClassNotFoundException {
    String poolName = buildPoolName(user, url);
    String jdbcDriver = getJDBCDriverName(user, url);

    if (!getJDBCConfiguration(user).isConnectionInDBDriverPool(poolName)) {
      createConnectionPool(url, user, properties);
    }
    return DriverManager.getConnection(jdbcDriver);
  }

  public Connection getConnection(InterpreterContext context)
      throws ClassNotFoundException, SQLException, InterpreterException, IOException {
    return getConnection(context, null);
  }

  /**
   * Get connection with optional URL override
   * @param context Interpreter context
   * @param overrideUrl URL to use instead of default (pass null or empty string to use default)
   */
  public Connection getConnection(InterpreterContext context, String overrideUrl)
      throws ClassNotFoundException, SQLException, InterpreterException, IOException {

    if (basePropertiesMap.get(DEFAULT_KEY) == null) {
      LOGGER.warn("No default config");
      return null;
    }

    Connection connection = null;
    String user = getUser(context);
    JDBCUserConfigurations jdbcUserConfigurations = getJDBCConfiguration(user);
    setUserProperty(context);

    final Properties properties = jdbcUserConfigurations.getProperty();
    
    // Use override URL if provided, otherwise use default
    String url = (overrideUrl != null && !overrideUrl.isEmpty()) 
        ? overrideUrl 
        : properties.getProperty(URL_KEY);
    
    if (overrideUrl != null && !overrideUrl.isEmpty()) {
      LOGGER.info("Using override URL for this paragraph");
    }
    
    url = appendProxyUserToURL(url, user);
    String connectionUrl = appendTagsToURL(url, context);
    validateConnectionUrl(connectionUrl);

    String authType = getProperty("zeppelin.jdbc.auth.type", "SIMPLE")
            .trim().toUpperCase();
    switch (authType) {
      case "SIMPLE":
        connection = getConnectionFromPool(connectionUrl, user, properties);
        break;
      case "KERBEROS":
        LOGGER.debug("Calling createSecureConfiguration(); this will do " +
            "loginUserFromKeytab() if required");
        JDBCSecurityImpl.createSecureConfiguration(getProperties(),
                UserGroupInformation.AuthenticationMethod.KERBEROS);
        LOGGER.debug("createSecureConfiguration() returned");
        boolean isProxyEnabled = Boolean.parseBoolean(
                getProperty("zeppelin.jdbc.auth.kerberos.proxy.enable", "true"));
        if (basePropertiesMap.get(DEFAULT_KEY).containsKey("proxy.user.property")
                || !isProxyEnabled) {
          connection = getConnectionFromPool(connectionUrl, user, properties);
        } else {
          UserGroupInformation ugi = null;
          try {
            ugi = UserGroupInformation.createProxyUser(
                    user, UserGroupInformation.getCurrentUser());
          } catch (Exception e) {
            LOGGER.error("Error in getCurrentUser", e);
            throw new InterpreterException("Error in getCurrentUser", e);
          }

          final String finalUser = user;
          try {
            connection = ugi.doAs((PrivilegedExceptionAction<Connection>) () ->
                    getConnectionFromPool(connectionUrl, finalUser, properties));
          } catch (Exception e) {
            LOGGER.error("Error in doAs", e);
            throw new InterpreterException("Error in doAs", e);
          }
        }
        break;
    }

    return connection;
  }

  private void validateConnectionUrl(String url) {
    if (containsIgnoreCase(url, ALLOW_LOAD_LOCAL_IN_FILE_NAME) ||
        containsIgnoreCase(url, AUTO_DESERIALIZE) ||
        containsIgnoreCase(url, ALLOW_LOCAL_IN_FILE_NAME) ||
        containsIgnoreCase(url, ALLOW_URL_IN_LOCAL_IN_FILE_NAME)) {
      throw new IllegalArgumentException("Connection URL contains sensitive configuration");
    }
  }

  private String appendProxyUserToURL(String url, String user) {
    StringBuilder connectionUrl = new StringBuilder(url);

    if (user != null && !user.equals("anonymous") &&
        basePropertiesMap.get(DEFAULT_KEY).containsKey("proxy.user.property")) {

      Integer lastIndexOfUrl = connectionUrl.indexOf("?");
      if (lastIndexOfUrl == -1) {
        lastIndexOfUrl = connectionUrl.length();
      }
      LOGGER.info("Using proxy user as: {}", user);
      LOGGER.info("Using proxy property for user as: {}",
          basePropertiesMap.get(DEFAULT_KEY).getProperty("proxy.user.property"));
      connectionUrl.insert(lastIndexOfUrl, ";" +
          basePropertiesMap.get(DEFAULT_KEY).getProperty("proxy.user.property") + "=" + user + ";");
    } else if (user != null && !user.equals("anonymous") && url.contains("hive")) {
      LOGGER.warn("User impersonation for hive has changed please refer: http://zeppelin.apache" +
          ".org/docs/latest/interpreter/jdbc.html#apache-hive");
    }

    return connectionUrl.toString();
  }

  // only add tags for hive jdbc
  private String appendTagsToURL(String url, InterpreterContext context) {
    if (!Boolean.parseBoolean(getProperty("zeppelin.jdbc.hive.engines.tag.enable", "true"))) {
      return url;
    }

    StringBuilder builder = new StringBuilder(url);
    if (url.startsWith("jdbc:hive2:")) {
      Integer lastIndexOfQMark = builder.indexOf("?");
      if (lastIndexOfQMark == -1) {
        builder.append("?");
        lastIndexOfQMark = builder.length();
      } else {
        lastIndexOfQMark++;
      }
      builder.insert(lastIndexOfQMark, "mapreduce.job.tags=" + context.getParagraphId() + ";");
      builder.insert(lastIndexOfQMark, "tez.application.tags=" + context.getParagraphId() + ";");
    }
    return builder.toString();
  }


  private String getPassword(Properties properties) throws IOException, InterpreterException {
    if (isNotEmpty(properties.getProperty(PASSWORD_KEY))) {
      return properties.getProperty(PASSWORD_KEY);
    } else if (isNotEmpty(properties.getProperty(JDBC_JCEKS_FILE))
        && isNotEmpty(properties.getProperty(JDBC_JCEKS_CREDENTIAL_KEY))) {
      try {
        Configuration configuration = new Configuration();
        configuration.set(CredentialProviderFactory.CREDENTIAL_PROVIDER_PATH,
            properties.getProperty(JDBC_JCEKS_FILE));
        CredentialProvider provider = CredentialProviderFactory.getProviders(configuration).get(0);
        CredentialProvider.CredentialEntry credEntry =
            provider.getCredentialEntry(properties.getProperty(JDBC_JCEKS_CREDENTIAL_KEY));
        if (credEntry != null) {
          return new String(credEntry.getCredential());
        } else {
          throw new InterpreterException("Failed to retrieve password from JCEKS from key: "
              + properties.getProperty(JDBC_JCEKS_CREDENTIAL_KEY));
        }
      } catch (Exception e) {
        LOGGER.error("Failed to retrieve password from JCEKS \n" +
            "For file: {} \nFor key: {}", properties.getProperty(JDBC_JCEKS_FILE),
                properties.getProperty(JDBC_JCEKS_CREDENTIAL_KEY), e);
        throw e;
      }
    }
    return null;
  }

  private String getResults(ResultSet resultSet, boolean isTableType)
      throws SQLException {

    ResultSetMetaData md = resultSet.getMetaData();
    StringBuilder msg;
    if (isTableType) {
      msg = new StringBuilder(TABLE_MAGIC_TAG);
    } else {
      msg = new StringBuilder();
    }

    for (int i = 1; i < md.getColumnCount() + 1; i++) {
      if (i > 1) {
        msg.append(TAB);
      }
      if (StringUtils.isNotEmpty(md.getColumnLabel(i))) {
        msg.append(removeTablePrefix(replaceReservedChars(
                TableDataUtils.normalizeColumn(md.getColumnLabel(i)))));
      } else {
        msg.append(removeTablePrefix(replaceReservedChars(
                TableDataUtils.normalizeColumn(md.getColumnName(i)))));
      }
    }
    msg.append(NEWLINE);

    int displayRowCount = 0;
    boolean truncate = false;
    while (resultSet.next()) {
      if (displayRowCount >= getMaxResult()) {
        truncate = true;
        break;
      }
      for (int i = 1; i < md.getColumnCount() + 1; i++) {
        Object resultObject;
        String resultValue;
        resultObject = resultSet.getObject(i);
        if (resultObject == null) {
          resultValue = "null";
        } else {
          resultValue = resultSet.getString(i);
        }
        msg.append(replaceReservedChars(TableDataUtils.normalizeColumn(resultValue)));
        if (i != md.getColumnCount()) {
          msg.append(TAB);
        }
      }
      msg.append(NEWLINE);
      displayRowCount++;
    }

    if (truncate) {
      msg.append("\n" + ResultMessages.getExceedsLimitRowsMessage(getMaxResult(),
              String.format("%s.%s", COMMON_KEY, MAX_LINE_KEY)).toString());
    }
    return msg.toString();
  }

  private boolean isDDLCommand(int updatedCount, int columnCount) throws SQLException {
    return updatedCount < 0 && columnCount <= 0 ? true : false;
  }

  public InterpreterResult executePrecode(InterpreterContext interpreterContext)
          throws InterpreterException {
    InterpreterResult interpreterResult = null;
    for (String propertyKey : basePropertiesMap.keySet()) {
      String precode = getProperty(String.format("%s.precode", propertyKey));
      if (StringUtils.isNotBlank(precode)) {
        interpreterResult = executeSql(precode, interpreterContext);
        if (interpreterResult.code() != Code.SUCCESS) {
          break;
        }
      }
    }

    return interpreterResult;
  }

  //Just keep it for testing
  protected List<String> splitSqlQueries(String text) {
    return sqlSplitter.splitSql(text);
  }

  /**
   * Execute the sql statement.
   *
   * @param sql
   * @param context
   * @return
   * @throws InterpreterException
   */
  private InterpreterResult executeSql(String sql,
      InterpreterContext context) throws InterpreterException {
    Connection connection = null;
    // Track the URL used to open the current connection so we can detect URL changes
    String currentConnectionUrl = null;
    Statement statement;
    ResultSet resultSet = null;
    String paragraphId = context.getParagraphId();
    String user = getUser(context);

    String interpreterName = getInterpreterGroup().getId();

    try {
      List<String>  sqlArray = sqlSplitter.splitSql(sql);
      for (String sqlToExecute : sqlArray) {
        String sqlTrimmedLowerCase = sqlToExecute.trim().toLowerCase();
        if (sqlTrimmedLowerCase.startsWith("set ") ||
                sqlTrimmedLowerCase.startsWith("list ") ||
                sqlTrimmedLowerCase.startsWith("add ") ||
                sqlTrimmedLowerCase.startsWith("delete ")) {
          // some version of hive doesn't work with set statement with empty line ahead.
          // so we need to trim it first in this case.
          sqlToExecute = sqlToExecute.trim();
        }
        LOGGER.info("Execute sql: " + sqlToExecute);
        // Validate and get URL for THIS specific statement
        String sqlToValidate = sqlToExecute
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("\t", " ");
        
        // User config properties may be null until setUserProperty is called (e.g. first run for this user)
        Properties defaultProps = basePropertiesMap.get(DEFAULT_KEY);
        String targetJdbcUrl = (defaultProps != null ? defaultProps.getProperty(URL_KEY) : null);

        ValidationRequest request = new ValidationRequest(sqlToValidate, user, 
                                                                    interpreterName, sqlToExecute, targetJdbcUrl);
        ValidationResponse response = null;

        try {
          response = sendValidationRequest(request);
          
          if (response.getNewJdbcUrl() != null && 
              !response.getNewJdbcUrl().isEmpty()) {
            targetJdbcUrl = response.getNewJdbcUrl();
            LOGGER.info("Validation API returned new JDBC URL for statement");
          }
        } catch (Exception e) {
          LOGGER.warn("Failed to call validation API: {}", e.getMessage());
        }

        // Get or create connection for this URL if needed.
        // We compare against currentConnectionUrl (set when we opened the connection)
        try {
          boolean urlChanged = targetJdbcUrl != null
              && !targetJdbcUrl.equals(currentConnectionUrl);

          if (urlChanged && connection != null && !connection.isClosed()) {
            LOGGER.info("URL changed from '{}' to '{}', closing old connection",
                currentConnectionUrl, targetJdbcUrl);
            // Commit any pending DML (INSERT/UPDATE/UPSERT) before returning this
            // connection to the pool. Without this, an open transaction from the
            // previous statement would be inherited by the next pool borrower.
            try {
              if (!connection.getAutoCommit()) {
                connection.commit();
              }
            } catch (SQLException commitEx) {
              LOGGER.warn("Could not commit before URL switch for user: {}, error: {}",
                  user, commitEx.getMessage());
            }
            connection.close();
            connection = null;
            currentConnectionUrl = null;
          }

          if (connection == null || connection.isClosed()) {
            connection = getConnection(context, targetJdbcUrl);
            currentConnectionUrl = targetJdbcUrl;
          }
        } catch (IllegalArgumentException e) {
          LOGGER.error("Cannot run " + sqlToExecute, e);
          return new InterpreterResult(Code.ERROR, "Connection URL contains improper configuration");
        } catch (Exception e) {
          LOGGER.error("Fail to getConnection", e);
          try {
            closeDBPool(user);
          } catch (SQLException e1) {
            LOGGER.error("Cannot close DBPool for user: " + user , e1);
          }
          if (e instanceof SQLException) {
            return new InterpreterResult(Code.ERROR, e.getMessage());
          } else {
            return new InterpreterResult(Code.ERROR, ExceptionUtils.getStackTrace(e));
          }
        }
        
        if (connection == null) {
          return new InterpreterResult(Code.ERROR, "User's connection not found.");
        }
        statement = connection.createStatement();

        if (interpreterName != null && interpreterName.startsWith("spark_rca_")) {
          statement.setQueryTimeout(10800); // 10800 seconds = 3 hours
        }

        // fetch n+1 rows in order to indicate there's more rows available (for large selects)
        statement.setFetchSize(context.getIntLocalProperty("limit", getMaxResult()));
        statement.setMaxRows(context.getIntLocalProperty("limit", maxRows));

        if (statement == null) {
          return new InterpreterResult(Code.ERROR, "Prefix not found.");
        }

        try {
          getJDBCConfiguration(user).saveStatement(paragraphId, statement);

          String statementPrecode =
              getProperty(String.format(STATEMENT_PRECODE_KEY_TEMPLATE, DEFAULT_KEY));

          if (StringUtils.isNotBlank(statementPrecode)) {
            statement.execute(statementPrecode);
          }

          // start hive monitor thread if it is hive jdbc
          String jdbcURL = getJDBCConfiguration(user).getProperty().getProperty(URL_KEY);
          String driver =
                  getJDBCConfiguration(user).getProperty().getProperty(DRIVER_KEY);
          if (jdbcURL != null && jdbcURL.startsWith("jdbc:hive2://")
                  && driver != null && driver.equals("org.apache.hive.jdbc.HiveDriver")) {
            HiveUtils.startHiveMonitorThread(statement, context,
                    Boolean.parseBoolean(getProperty("hive.log.display", "true")), this);
          }

          try {
            if (response.isPreSubmitFail()) {
              if(response.getVersion() == "v1") {
                String outputMessage = response.getMessage();
                StringBuilder finalOutput = new StringBuilder();

                if (response.isFailFast()) {
                  context.out.write("Query Error: Partition Filters Missing\n" +
                          "Your query failed because some tables are missing partition filters. To avoid this, please ensure partition filters are applied to improve performance.\n");
                  JSONObject jsonObject = new JSONObject(outputMessage);
                  finalOutput.append("The following table(s) are missing partition filters:\n");

                  JSONArray tableNames = jsonObject.names();
                  if (tableNames != null) {
                    for (int i = 0; i < tableNames.length(); i++) {
                      String table = tableNames.getString(i);
                      JSONArray partitions = jsonObject.getJSONArray(table);
                      finalOutput.append("Table: ").append(table).append(", Partition filter's: ");

                      for (int j = 0; j < partitions.length(); j++) {
                        finalOutput.append(partitions.getString(j));
                        if (j < partitions.length() - 1) {
                          finalOutput.append(", ");
                        }
                      }
                      finalOutput.append("\n");
                    }
                  }
                } else if (response.isFailedByDeprecatedTable()) {
                  context.out.write("Query Error: Restricted Table Used\n");
                  JSONObject jsonObject = new JSONObject(outputMessage);
                  finalOutput.append("It seems you're trying to use a restricted table:\n");

                  JSONArray tableNames = jsonObject.names();
                  if (tableNames != null) {
                    for (int i = 0; i < tableNames.length(); i++) {
                      String table = tableNames.getString(i);
                      finalOutput.append("Use: ").append(jsonObject.getString(table)).append(" in place of ").append(table).append("\n");
                    }
                  }
                } else if (outputMessage.contains("UnAuthorized Query")) {
                    context.out.write("Query Error: UnAuthorized Query\n");
                    finalOutput.append("You are not authorized to execute this query.\n");
                }
                context.getLocalProperties().put(CANCEL_REASON, finalOutput.toString());
              } else {
                String errorHeader = response.getErrorHeader();
                context.out.write(errorHeader);
                
                String detailedMessage = response.getMessage();
                context.getLocalProperties().put(CANCEL_REASON, detailedMessage);
              }
              
              cancel(context);
              return new InterpreterResult(Code.ERROR);
            } else {
              // pre_submit_fail is false - show message as suggestion if present, but continue query execution
              String message = response.getMessage();
              if (message != null && !message.isEmpty()) {
                context.out.write("%text " + message + "\n\n");
                context.out.flush();
              }
              sqlToExecute = response.getNewQueryText() != null ? response.getNewQueryText() : sqlToExecute;
            }
          } catch (Exception e) {
            String error = "Error occurred while sending request " + e.getMessage();
            String mess = e.getLocalizedMessage();
            context.out.write(error);
            context.out.write(mess);
          }

          boolean isResultSetAvailable = statement.execute(sqlToExecute);
          getJDBCConfiguration(user).setConnectionInDBDriverPoolSuccessful();
          if (isResultSetAvailable) {
            resultSet = statement.getResultSet();

            // Regards that the command is DDL.
            if (isDDLCommand(statement.getUpdateCount(),
                resultSet.getMetaData().getColumnCount())) {
              context.out.write("%text Query executed successfully.\n");
            } else {
              String template = context.getLocalProperties().get("template");
              if (!StringUtils.isBlank(template)) {
                resultSet.next();
                SingleRowInterpreterResult singleRowResult =
                        new SingleRowInterpreterResult(getFirstRow(resultSet), template, context);

                if (isFirstRefreshMap.get(context.getParagraphId())) {
                  context.out.write(singleRowResult.toAngular());
                  context.out.write("\n%text ");
                  context.out.flush();
                  isFirstRefreshMap.put(context.getParagraphId(), false);
                }
                singleRowResult.pushAngularObjects();

              } else {
                String results = getResults(resultSet,
                        !containsIgnoreCase(sqlToExecute, EXPLAIN_PREDICATE));
                context.out.write(results);
                context.out.write("\n%text ");
                context.out.flush();
              }
            }
          } else {
            // Response contains either an update count or there are no results.
            int updateCount = statement.getUpdateCount();
            context.out.write("\n%text " +
                "Query executed successfully. Affected rows : " +
                    updateCount + "\n");
          }
        } finally {
          if (resultSet != null) {
            try {
              resultSet.close();
            } catch (SQLException e) { /*ignored*/ }
          }
          if (statement != null) {
            try {
              statement.close();
            } catch (SQLException e) { /*ignored*/ }
          }
        }
      }
    } catch (Throwable e) {
      LOGGER.error("Cannot run " + sql, e);
      if (e instanceof SQLException) {
        return new InterpreterResult(Code.ERROR,  e.getMessage());
      } else {
        return new InterpreterResult(Code.ERROR, ExceptionUtils.getStackTrace(e));
      }
    } finally {
      //In case user ran an insert/update/upsert statement
      if (connection != null) {
        try {
          if (!connection.getAutoCommit()) {
            connection.commit();
          }
          connection.close();
        } catch (SQLException e) { /*ignored*/ }
      }
      getJDBCConfiguration(user).removeStatement(paragraphId);
    }

    return new InterpreterResult(Code.SUCCESS);
  }

  private List getFirstRow(ResultSet rs) throws SQLException {
    List list = new ArrayList();
    ResultSetMetaData md = rs.getMetaData();
    for (int i = 1; i <= md.getColumnCount(); ++i) {
      Object columnObject = rs.getObject(i);
      String columnValue = null;
      if (columnObject == null) {
        columnValue = "null";
      } else {
        columnValue = rs.getString(i);
      }
      list.add(columnValue);
    }
    return list;
  }

  /**
   * For %table response replace Tab and Newline characters from the content.
   */
  private String replaceReservedChars(String str) {
    if (str == null) {
      return EMPTY_COLUMN_VALUE;
    }
    return str.replace(TAB, WHITESPACE).replace(NEWLINE, WHITESPACE);
  }

  /**
   * Hive will prefix table name before the column
   * @param columnName
   * @return
   */
  private String removeTablePrefix(String columnName) {
    int index = columnName.indexOf(".");
    if (index > 0) {
      return columnName.substring(index + 1);
    } else {
      return columnName;
    }
  }

  @Override
  protected boolean isInterpolate() {
    return Boolean.parseBoolean(getProperty("zeppelin.jdbc.interpolation", "false"));
  }

  private boolean isRefreshMode(InterpreterContext context) {
    return context.getLocalProperties().get("refreshInterval") != null;
  }

  @Override
  public InterpreterResult internalInterpret(String cmd, InterpreterContext context)
          throws InterpreterException {
    String dbprefix = getDBPrefix(context);
    if (!StringUtils.equals(dbprefix, DEFAULT_KEY)) {
      LOGGER.warn("DBprefix like %jdbc(db=mysql) or %jdbc(mysql) is not supported anymore！");
      LOGGER.warn("JDBC Interpreter would try to use default config.");
    }
    LOGGER.debug("Run SQL command '{}'", cmd);
    
    // Add STATEMENT_TIMEOUT for spark_rca_ interpreters
    String interpreterName = getInterpreterGroup().getId();
    if (interpreterName != null && interpreterName.startsWith("spark_rca_")) {
      cmd = "set STATEMENT_TIMEOUT=10800;\n" + cmd;
      LOGGER.debug("InterpreterName: {}, SQL command with timeout: '{}'", interpreterName, cmd);
    }
    final String finalCmd = cmd;
    
    if (!isRefreshMode(context)) {
      return executeSql(finalCmd, context);
    } else {
      int refreshInterval = Integer.parseInt(context.getLocalProperties().get("refreshInterval"));
      paragraphCancelMap.put(context.getParagraphId(), false);
      ScheduledExecutorService refreshExecutor = Executors.newSingleThreadScheduledExecutor();
      refreshExecutorServices.put(context.getParagraphId(), refreshExecutor);
      isFirstRefreshMap.put(context.getParagraphId(), true);
      final AtomicReference<InterpreterResult> interpreterResultRef = new AtomicReference();
      refreshExecutor.scheduleAtFixedRate(() -> {
        context.out.clear(false);
        try {
          InterpreterResult result = executeSql(finalCmd, context);
          context.out.flush();
          interpreterResultRef.set(result);
          if (result.code() != Code.SUCCESS) {
            refreshExecutor.shutdownNow();
          }
        } catch (Exception e) {
          LOGGER.warn("Fail to run sql", e);
        }
      }, 0, refreshInterval, TimeUnit.MILLISECONDS);

      while (!refreshExecutor.isTerminated()) {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          LOGGER.error("");
        }
      }
      refreshExecutorServices.remove(context.getParagraphId());
      if (paragraphCancelMap.getOrDefault(context.getParagraphId(), false)) {
        return new InterpreterResult(Code.ERROR);
      } else if (interpreterResultRef.get().code() == Code.ERROR) {
        return interpreterResultRef.get();
      } else {
        return new InterpreterResult(Code.SUCCESS);
      }
    }
  }

  @Override
  public void cancel(InterpreterContext context) {

    if (isRefreshMode(context)) {
      LOGGER.info("Shutdown refreshExecutorService for paragraph: {}", context.getParagraphId());
      ScheduledExecutorService executorService =
              refreshExecutorServices.get(context.getParagraphId());
      if (executorService != null) {
        executorService.shutdownNow();
      }
      paragraphCancelMap.put(context.getParagraphId(), true);
      return;
    }

    LOGGER.info("Cancel current query statement.");
    String paragraphId = context.getParagraphId();
    JDBCUserConfigurations jdbcUserConfigurations = getJDBCConfiguration(getUser(context));
    try {
      jdbcUserConfigurations.cancelStatement(paragraphId);
    } catch (SQLException e) {
      LOGGER.error("Error while cancelling...", e);
    }

    String cancelReason = context.getLocalProperties().get(CANCEL_REASON);
    if (StringUtils.isNotBlank(cancelReason)) {
      try {
        context.out.write(cancelReason);
      } catch (IOException e) {
        LOGGER.error("Fail to write cancel reason");
      }
    }
  }

  public void cancel(InterpreterContext context, String errorMessage) {
    context.getLocalProperties().put(CANCEL_REASON, errorMessage);
    cancel(context);
  }
  /**
   *
   *
   * @param context
   * @return
   */
  public String getDBPrefix(InterpreterContext context) {
    Map<String, String> localProperties = context.getLocalProperties();
    // It is recommended to use this kind of format: %jdbc(db=mysql)
    if (localProperties.containsKey("db")) {
      return localProperties.get("db");
    }
    // %jdbc(mysql) is only for backward compatibility
    for (Map.Entry<String, String> entry : localProperties.entrySet()) {
      if (entry.getKey().equals(entry.getValue())) {
        return entry.getKey();
      }
    }
    return DEFAULT_KEY;
  }

  @Override
  public FormType getFormType() {
    return FormType.SIMPLE;
  }

  @Override
  public int getProgress(InterpreterContext context) {
    return 0;
  }

  @Override
  public Scheduler getScheduler() {
    String schedulerName = JDBCInterpreter.class.getName() + this.hashCode();
    return isConcurrentExecution() ?
            SchedulerFactory.singleton().createOrGetParallelScheduler(schedulerName,
                getMaxConcurrentConnection())
            : SchedulerFactory.singleton().createOrGetFIFOScheduler(schedulerName);
  }

  @Override
  public List<InterpreterCompletion> completion(String buf, int cursor,
      InterpreterContext context) throws InterpreterException {
    List<InterpreterCompletion> candidates = new ArrayList<>();
    String sqlCompleterKey =
        String.format("%s.%s", getUser(context), DEFAULT_KEY);
    SqlCompleter sqlCompleter = sqlCompletersMap.get(sqlCompleterKey);

    Connection connection = null;
    try {
      if (context != null) {
        connection = getConnection(context);
      }
    } catch (ClassNotFoundException | SQLException | IOException e) {
      LOGGER.warn("SQLCompleter will created without use connection");
    }

    sqlCompleter = createOrUpdateSqlCompleter(sqlCompleter, connection, DEFAULT_KEY, buf, cursor);
    sqlCompletersMap.put(sqlCompleterKey, sqlCompleter);
    sqlCompleter.complete(buf, cursor, candidates);

    return candidates;
  }

  public int getMaxResult() {
    return maxLineResults;
  }

  boolean isConcurrentExecution() {
    return Boolean.valueOf(getProperty(CONCURRENT_EXECUTION_KEY));
  }

  int getMaxConcurrentConnection() {
    try {
      return Integer.valueOf(getProperty(CONCURRENT_EXECUTION_COUNT));
    } catch (Exception e) {
      LOGGER.error("Fail to parse {} with value: {}", CONCURRENT_EXECUTION_COUNT,
              getProperty(CONCURRENT_EXECUTION_COUNT));
      return 10;
    }
  }
}
