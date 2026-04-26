package dev.axionize.sqlite_jdbc;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Public API for accessing the SQLite JDBC driver bundled with this holder
 * mod, bypassing the parent-first classloader delegation that otherwise
 * resolves to the server's bundled sqlite-jdbc.
 *
 * <h2>Default path (use this 99% of the time)</h2>
 *
 * On Bukkit/Spigot/Paper, sqlite-jdbc lives on the server's parent
 * classloader, so the standard JDBC entry points just work:
 *
 * <pre>{@code
 * try (Connection c = DriverManager.getConnection("jdbc:sqlite:foo.db")) {
 *     // ... uses the SERVER's bundled engine ...
 * }
 * }</pre>
 *
 * You don't need to softdepend on this holder, you don't need this API,
 * and on Bukkit-family servers installing this mod is redundant. Install
 * the holder only on Fabric/NeoForge (where vanilla MC ships no JDBC
 * drivers) or on Bukkit forks that have stripped the bundled driver.
 *
 * <h2>Workaround path: explicitly use this holder's driver</h2>
 *
 * You only need the API on this class when you want a SQLite engine
 * NEWER than what the server bundles. The holder ships the most recent
 * Xerial release; the server may bundle one years older (e.g. Paper
 * 1.21.11 bundles engine 3.49.1.0; this holder ships 3.53.0.0+). For
 * features only in the newer engine — {@code RETURNING}, {@code STRICT}
 * tables, recent JSON functions, etc. — use this API.
 *
 * <p>Your plugin softdepends on this holder so its classes are on your
 * plugin's classloader graph:
 *
 * <pre>
 * # in plugin.yml
 * softdepend: [minecraft-sqlite-jdbc]
 * </pre>
 *
 * Then call directly:
 *
 * <pre>{@code
 * Connection c;
 * try {
 *     c = MinecraftSqliteJdbc.connect("jdbc:sqlite:foo.db");
 * } catch (NoClassDefFoundError | ClassNotFoundException notInstalled) {
 *     // holder isn't installed — fall back to bundled
 *     c = DriverManager.getConnection("jdbc:sqlite:foo.db");
 * }
 * }</pre>
 *
 * The returned connection routes through the SQLite engine bundled with
 * this holder, not the server's. {@link #engineVersion()} reports which
 * version that is.
 *
 * <h2>Caveats</h2>
 *
 * <ul>
 * <li>The returned {@link Connection} is a {@code java.sql.Connection}
 *     interface — safe to cast and use through standard JDBC. The
 *     implementation type ({@code org.sqlite.SQLiteConnection}) lives in
 *     this holder's child-first classloader; if you down-cast to that to
 *     use SQLite-specific extension methods, you'll get a
 *     {@link ClassCastException}. Stick to the {@code java.sql.*}
 *     interfaces.</li>
 * <li>{@link java.sql.DriverManager#getConnection} won't return this
 *     driver — it iterates registered drivers and returns the first that
 *     accepts the URL, which is the bundled one. Always use {@link #connect}
 *     directly.</li>
 * <li>The native SQLite library is loaded once per holder instance (one
 *     JNI extraction). On hardened servers where {@code /tmp} is mounted
 *     {@code noexec}, set {@code -Dorg.sqlite.lib.path=/path/to/writable}
 *     so the library extracts somewhere it can run from.</li>
 * </ul>
 */
public final class MinecraftSqliteJdbc {

    private static final Logger LOG = Logger.getLogger("MinecraftSqliteJdbc");
    private static final Object LOCK = new Object();

    private static volatile URLClassLoader childFirst;
    private static volatile Driver driver;
    private static volatile String engineVersion;
    private static volatile String driverVersion;

    private MinecraftSqliteJdbc() {}

    /**
     * Open a connection through this holder's bundled driver.
     *
     * @param url JDBC URL ({@code jdbc:sqlite:...})
     * @return open connection — caller closes
     */
    public static Connection connect(String url) throws SQLException {
        return connect(url, new Properties());
    }

    /**
     * Open a connection through this holder's bundled driver.
     *
     * @param url JDBC URL ({@code jdbc:sqlite:...})
     * @param properties driver properties (may be null)
     * @return open connection — caller closes
     */
    public static Connection connect(String url, Properties properties) throws SQLException {
        Driver d = driver();
        Connection c = d.connect(url, properties == null ? new Properties() : properties);
        if (c == null) {
            throw new SQLException("MinecraftSqliteJdbc: driver did not accept URL: " + url);
        }
        return c;
    }

    /**
     * Returns the {@link Driver} bundled with this holder. Subsequent calls
     * return the same instance (cached). The returned Driver is loaded from
     * a child-first {@link URLClassLoader} pointing at this holder's own
     * jar, so its class identity differs from the server's bundled
     * {@code org.sqlite.JDBC}.
     */
    public static Driver driver() throws SQLException {
        Driver d = driver;
        if (d != null) return d;
        synchronized (LOCK) {
            if (driver != null) return driver;
            initLocked();
            return driver;
        }
    }

    /**
     * SQLite engine version this holder ships (e.g. {@code "3.53.0"}).
     * Memoized after the first call.
     */
    public static String engineVersion() throws SQLException {
        String v = engineVersion;
        if (v != null) return v;
        synchronized (LOCK) {
            if (engineVersion != null) return engineVersion;
            try (Connection c = connect("jdbc:sqlite::memory:");
                 Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT sqlite_version()")) {
                if (rs.next()) {
                    engineVersion = rs.getString(1);
                }
            }
            return engineVersion;
        }
    }

    /**
     * sqlite-jdbc driver version this holder ships (e.g. {@code "3.53.0.0"}).
     * Differs from {@link #engineVersion()} only in the trailing
     * Xerial-revision component (the SQLite engine version is the leading
     * triple). Memoized after the first call.
     */
    public static String driverVersion() throws SQLException {
        String v = driverVersion;
        if (v != null) return v;
        synchronized (LOCK) {
            if (driverVersion != null) return driverVersion;
            Driver d = driver();
            try {
                Class<?> loader = Class.forName(
                        "org.sqlite.SQLiteJDBCLoader",
                        true,
                        d.getClass().getClassLoader());
                Object res = loader.getMethod("getVersion").invoke(null);
                driverVersion = res != null ? res.toString() : null;
            } catch (Throwable t) {
                // best-effort fallback if internal layout changes
                driverVersion = d.getMajorVersion() + "." + d.getMinorVersion();
            }
            return driverVersion;
        }
    }

    /**
     * Eagerly construct the holder's classloader + driver so any
     * initialization failure surfaces at plugin enable rather than on the
     * first JDBC call. Safe to call multiple times — caches.
     */
    public static void eagerInit() {
        try {
            driver();
        } catch (Throwable t) {
            LOG.severe("eagerInit failed: " + t);
        }
    }

    /**
     * Release the child-first classloader and forget the cached Driver.
     * Optional — call from plugin/mod disable to release file handles
     * promptly. JVM exit also cleans up.
     */
    public static void shutdown() {
        synchronized (LOCK) {
            driver = null;
            engineVersion = null;
            driverVersion = null;
            URLClassLoader cl = childFirst;
            childFirst = null;
            if (cl != null) {
                try { cl.close(); } catch (IOException ignore) {}
            }
        }
    }

    // ---------- internals ----------

    private static void initLocked() throws SQLException {
        try {
            URL ourJar = MinecraftSqliteJdbc.class
                    .getProtectionDomain().getCodeSource().getLocation();
            if (ourJar == null) {
                throw new SQLException(
                        "MinecraftSqliteJdbc: cannot locate this holder's jar URL"
                                + " (running from non-jar source?)");
            }
            // Parent = ext/platform classloader. Has java.* / javax.* / sun.*
            // / jdk.* but NOT org.sqlite.*. So org.sqlite.JDBC must come from
            // our own jar, not from the bundled copy on the server's main
            // classloader chain. Java 8 + Java 9+ both yield a usable parent
            // here (ext loader on 8, platform loader on 9+).
            ClassLoader parent = ClassLoader.getSystemClassLoader().getParent();
            URLClassLoader cf = new ChildFirstURLClassLoader(new URL[]{ourJar}, parent);
            Class<?> dc = Class.forName("org.sqlite.JDBC", true, cf);
            Driver d = (Driver) dc.getDeclaredConstructor().newInstance();
            childFirst = cf;
            driver = d;
            LOG.info("MinecraftSqliteJdbc: initialised driver from " + ourJar);
        } catch (SQLException e) {
            throw e;
        } catch (Throwable t) {
            throw new SQLException("MinecraftSqliteJdbc: init failed: " + t, t);
        }
    }

    /**
     * Standard child-first {@link URLClassLoader}: our URLs are tried before
     * the parent. JVM-internal packages always go to the parent for safety.
     */
    private static final class ChildFirstURLClassLoader extends URLClassLoader {
        ChildFirstURLClassLoader(URL[] urls, ClassLoader parent) { super(urls, parent); }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    if (name.startsWith("java.") || name.startsWith("javax.")
                            || name.startsWith("sun.") || name.startsWith("jdk.")) {
                        c = super.loadClass(name, false);
                    } else {
                        try {
                            c = findClass(name);
                        } catch (ClassNotFoundException e) {
                            c = super.loadClass(name, false);
                        }
                    }
                }
                if (resolve) resolveClass(c);
                return c;
            }
        }
    }
}
