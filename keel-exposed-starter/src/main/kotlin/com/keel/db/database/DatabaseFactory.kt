package com.keel.db.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig as ExposedDatabaseConfig
import org.jetbrains.exposed.sql.Database as ExposedDatabase
import com.keel.db.logging.DbScopeLogger
import java.io.File
import javax.sql.DataSource

/**
 * Configuration data class for database connection settings.
 * Provides all necessary parameters to create a database connection.
 *
 * @property jdbcUrl The JDBC URL for the database (e.g., "jdbc:h2:mem:testdb")
 * @property driverClassName The fully qualified name of the JDBC driver class
 * @property username Database username (default: empty for H2)
 * @property password Database password (default: empty for H2)
 * @property poolSize Connection pool size (default: 10)
 * @property maxLifetime Maximum lifetime of a connection in milliseconds (default: 30 minutes)
 * @property minIdle Minimum number of idle connections (default: 2)
 * @property connectionTimeout Connection timeout in milliseconds (default: 20 seconds)
 * @property schema Optional schema to use
 * @property exposedConfig Optional Exposed DatabaseConfig for additional settings
 */
data class DatabaseConfig(
    val jdbcUrl: String,
    val driverClassName: String? = null,
    val username: String = "",
    val password: String = "",
    val poolSize: Int = 10,
    val maxLifetime: Long = 30 * 60 * 1000,
    val minIdle: Int = 2,
    val connectionTimeout: Long = 20_000,
    val schema: String? = null,
    val exposedConfig: ExposedDatabaseConfig? = null
) {
    /**
     * Auto-detect the driver class name based on the JDBC URL.
     */
    fun resolvedDriverClassName(): String {
        return driverClassName ?: when {
            jdbcUrl.startsWith("jdbc:h2:") -> "org.h2.Driver"
            jdbcUrl.startsWith("jdbc:postgresql:") -> "org.postgresql.Driver"
            jdbcUrl.startsWith("jdbc:mysql:") -> "com.mysql.cj.jdbc.Driver"
            jdbcUrl.startsWith("jdbc:sqlite:") -> "org.sqlite.JDBC"
            jdbcUrl.startsWith("jdbc:oracle:") -> "oracle.jdbc.OracleDriver"
            jdbcUrl.startsWith("jdbc:sqlserver:") -> "com.microsoft.sqlserver.jdbc.SQLServerDriver"
            else -> throw IllegalArgumentException("Cannot auto-detect driver for JDBC URL: $jdbcUrl")
        }
    }

    companion object {
        /**
         * Create an H2 in-memory database configuration.
         * Useful for testing.
         *
         * @param name Database name (default: "testdb")
         * @param username Username (default: "sa")
         * @param password Password (default: "")
         * @param poolSize Connection pool size (default: 10)
         */
        fun h2Memory(
            name: String = "testdb",
            username: String = "sa",
            password: String = "",
            poolSize: Int = 10
        ): DatabaseConfig {
            return DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1",
                driverClassName = "org.h2.Driver",
                username = username,
                password = password,
                poolSize = poolSize
            )
        }

        /**
         * Create an H2 file-based database configuration.
         *
         * @param filePath Path to the database file (without extension)
         * @param username Username (default: "sa")
         * @param password Password (default: "")
         * @param poolSize Connection pool size (default: 10)
         */
        fun h2File(
            filePath: String,
            username: String = "sa",
            password: String = "",
            poolSize: Int = 10
        ): DatabaseConfig {
            return DatabaseConfig(
                jdbcUrl = "jdbc:h2:file:$filePath",
                driverClassName = "org.h2.Driver",
                username = username,
                password = password,
                poolSize = poolSize
            )
        }

        /**
         * Create a PostgreSQL database configuration.
         *
         * @param host Database host
         * @param port Database port (default: 5432)
         * @param database Database name
         * @param username Username
         * @param password Password
         * @param poolSize Connection pool size (default: 10)
         */
        fun postgresql(
            host: String,
            port: Int = 5432,
            database: String,
            username: String,
            password: String,
            poolSize: Int = 10
        ): DatabaseConfig {
            return DatabaseConfig(
                jdbcUrl = "jdbc:postgresql://$host:$port/$database",
                driverClassName = "org.postgresql.Driver",
                username = username,
                password = password,
                poolSize = poolSize
            )
        }

        /**
         * Create a MySQL database configuration.
         *
         * @param host Database host
         * @param port Database port (default: 3306)
         * @param database Database name
         * @param username Username
         * @param password Password
         * @param poolSize Connection pool size (default: 10)
         */
        fun mysql(
            host: String,
            port: Int = 3306,
            database: String,
            username: String,
            password: String,
            poolSize: Int = 10
        ): DatabaseConfig {
            return DatabaseConfig(
                jdbcUrl = "jdbc:mysql://$host:$port/$database?useSSL=false&allowPublicKeyRetrieval=true",
                driverClassName = "com.mysql.cj.jdbc.Driver",
                username = username,
                password = password,
                poolSize = poolSize
            )
        }

        /**
         * Create a SQLite database configuration.
         *
         * @param filePath Path to the SQLite file
         */
        fun sqlite(filePath: String): DatabaseConfig {
            // Ensure parent directory exists
            val file = File(filePath)
            file.parentFile?.mkdirs()

            return DatabaseConfig(
                jdbcUrl = "jdbc:sqlite:$filePath",
                driverClassName = "org.sqlite.JDBC",
                poolSize = 1 // SQLite doesn't support concurrent writes well
            )
        }
    }
}

/**
 * Database factory for creating and managing database connections.
 * Provides a fluent builder API and connection pooling via HikariCP.
 */
class DatabaseFactory private constructor(
    private val config: DatabaseConfig
) {
    private val logger = DbScopeLogger.getLogger("DatabaseFactory")

    private var exposedDatabase: ExposedDatabase? = null
    private var dataSource: DataSource? = null

    /**
     * Initialize the database and return a KeelDatabase instance.
     * This creates the connection pool and registers the database with Exposed.
     */
    fun init(): KeelDatabase {
        logger.info("Initializing database with URL: ${config.jdbcUrl}")

        // Register the driver
        val driverClass = config.resolvedDriverClassName()
        try {
            Class.forName(driverClass)
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException("JDBC driver not found: $driverClass", e)
        }

        // Create HikariCP config for connection pooling
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            driverClassName = driverClass
            username = config.username
            password = config.password

            // Pool settings
            maximumPoolSize = config.poolSize
            minimumIdle = config.minIdle
            maxLifetime = config.maxLifetime
            connectionTimeout = config.connectionTimeout

            // Connection validation
            connectionTestQuery = "SELECT 1"

            // Performance optimizations
            addDataSourceProperty("cachePrepStmts", "true")
            addDataSourceProperty("prepStmtCacheSize", "250")
            addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
        }

        // Create the data source
        dataSource = HikariDataSource(hikariConfig)

        // Create and register the Exposed database
        exposedDatabase = ExposedDatabase.connect(
            datasource = dataSource!!,
            databaseConfig = config.exposedConfig
        )

        logger.info("Database initialized successfully with pool size: ${config.poolSize}")

        return KeelDatabase(exposedDatabase!!)
    }

    /**
     * Close the database connections and shutdown the connection pool.
     */
    fun close() {
        logger.info("Closing database connections")

        // Clear the Exposed database reference (connections will be closed with data source)
        exposedDatabase = null

        // Close HikariCP data source
        (dataSource as? HikariDataSource)?.let { ds ->
            try {
                ds.close()
            } catch (e: Exception) {
                logger.warn("Error closing data source", e)
            }
        }
        dataSource = null

        logger.info("Database connections closed")
    }

    companion object {
        /**
         * Create a DatabaseFactory from a DatabaseConfig.
         */
        fun fromConfig(config: DatabaseConfig): DatabaseFactory {
            return DatabaseFactory(config)
        }

        /**
         * Create an H2 in-memory database factory.
         * Useful for testing.
         */
        fun h2Memory(
            name: String = "testdb",
            username: String = "sa",
            password: String = "",
            poolSize: Int = 10
        ): DatabaseFactory {
            return DatabaseFactory(DatabaseConfig.h2Memory(name, username, password, poolSize))
        }

        /**
         * Create an H2 file-based database factory.
         */
        fun h2File(
            filePath: String,
            username: String = "sa",
            password: String = "",
            poolSize: Int = 10
        ): DatabaseFactory {
            return DatabaseFactory(DatabaseConfig.h2File(filePath, username, password, poolSize))
        }

        /**
         * Create a PostgreSQL database factory.
         */
        fun postgresql(
            host: String,
            port: Int = 5432,
            database: String,
            username: String,
            password: String,
            poolSize: Int = 10
        ): DatabaseFactory {
            return DatabaseFactory(
                DatabaseConfig.postgresql(host, port, database, username, password, poolSize)
            )
        }

        /**
         * Create a MySQL database factory.
         */
        fun mysql(
            host: String,
            port: Int = 3306,
            database: String,
            username: String,
            password: String,
            poolSize: Int = 10
        ): DatabaseFactory {
            return DatabaseFactory(
                DatabaseConfig.mysql(host, port, database, username, password, poolSize)
            )
        }

        /**
         * Create a SQLite database factory.
         */
        fun sqlite(filePath: String): DatabaseFactory {
            return DatabaseFactory(DatabaseConfig.sqlite(filePath))
        }
    }
}
