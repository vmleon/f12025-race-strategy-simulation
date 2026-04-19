package udp.server;

import oracle.ucp.jdbc.PoolDataSource;
import oracle.ucp.jdbc.PoolDataSourceFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates and manages an Oracle UCP connection pool from config properties.
 */
public class ConnectionFactory {

    private static final Logger log = LoggerFactory.getLogger(ConnectionFactory.class);

    private final PoolDataSource pool;

    public ConnectionFactory(Properties config) throws SQLException {
        pool = PoolDataSourceFactory.getPoolDataSource();
        pool.setConnectionFactoryClassName("oracle.jdbc.pool.OracleDataSource");
        pool.setURL(config.getProperty("db.url"));
        pool.setUser(config.getProperty("db.user"));
        pool.setPassword(config.getProperty("db.password", ""));
        String dbPassword = config.getProperty("db.password", "");
        log.info("DB config: url={} user={} password={}", config.getProperty("db.url"),
                config.getProperty("db.user"),
                dbPassword.isEmpty() ? "(empty)" : "(set, length=" + dbPassword.length() + ")");
        pool.setInitialPoolSize(Integer.parseInt(config.getProperty("db.pool.initialSize", "2")));
        pool.setMinPoolSize(Integer.parseInt(config.getProperty("db.pool.minSize", "2")));
        pool.setMaxPoolSize(Integer.parseInt(config.getProperty("db.pool.maxSize", "10")));
        pool.setConnectionWaitTimeout(5);
        pool.setInactiveConnectionTimeout(300);
    }

    public Connection getConnection() throws SQLException {
        Connection conn = pool.getConnection();
        conn.setAutoCommit(false);
        return conn;
    }

    public PoolDataSource getPool() {
        return pool;
    }
}
