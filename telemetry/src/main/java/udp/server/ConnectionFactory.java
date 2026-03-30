package udp.server;

import oracle.ucp.jdbc.PoolDataSource;
import oracle.ucp.jdbc.PoolDataSourceFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Creates and manages an Oracle UCP connection pool from config properties.
 */
public class ConnectionFactory {

    private final PoolDataSource pool;

    public ConnectionFactory(Properties config) throws SQLException {
        pool = PoolDataSourceFactory.getPoolDataSource();
        pool.setConnectionFactoryClassName("oracle.jdbc.pool.OracleDataSource");
        pool.setURL(config.getProperty("db.url"));
        pool.setUser(config.getProperty("db.user"));
        String password = config.getProperty("db.password", "");
        if (password.isEmpty()) {
            password = System.getenv().getOrDefault("F1STRATEGY_DB_PASSWORD", "");
        }
        pool.setPassword(password);
        System.out.println("DB config: url=" + config.getProperty("db.url")
                + " user=" + config.getProperty("db.user")
                + " password=" + (password.isEmpty() ? "(empty)" : "(set, length=" + password.length() + ")"));
        pool.setInitialPoolSize(Integer.parseInt(config.getProperty("db.pool.initialSize", "2")));
        pool.setMinPoolSize(Integer.parseInt(config.getProperty("db.pool.minSize", "2")));
        pool.setMaxPoolSize(Integer.parseInt(config.getProperty("db.pool.maxSize", "10")));
        pool.setConnectionWaitTimeout(5);
        pool.setInactiveConnectionTimeout(300);
    }

    public Connection getConnection() throws SQLException {
        return pool.getConnection();
    }

    public PoolDataSource getPool() {
        return pool;
    }
}
