import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

public class FakeOracleDriver implements Driver {

    static final String DOCUMENT = "{\"keyStatus\":\"ACTIVE\",\"renderedAt\":\"2026-10-06T08:14:19.475Z\",\"config\":{\"projects\":{\"gui\":{\"site\":\"Z\u00fcrich\\/Basel\",\"tab\":\"a\tb\"}}}}";

    static {
        try {
            DriverManager.registerDriver(new FakeOracleDriver());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        String mode = url.substring("jdbc:fake:".length());
        String password = info.getProperty("password");
        List<String> problems = new ArrayList<>();
        expect(problems, info, "oracle.net.CONNECT_TIMEOUT", "10000");
        expect(problems, info, "oracle.jdbc.ReadTimeout", "30000");
        expect(problems, info, "oracle.jdbc.timezoneAsRegion", "false");
        boolean tcps = mode.contains("tcps");
        expect(problems, info, "oracle.net.encryption_client", tcps ? null : "REQUIRED");
        expect(problems, info, "oracle.net.encryption_types_client", tcps ? null : "(AES256)");
        expect(problems, info, "oracle.net.crypto_checksum_client", tcps ? null : "REQUIRED");
        expect(problems, info, "oracle.net.crypto_checksum_types_client", tcps ? null : "(SHA256)");
        if (DriverManager.getLoginTimeout() != 15) {
            problems.add("login timeout " + DriverManager.getLoginTimeout());
        }
        if (!problems.isEmpty()) {
            throw new SQLException("fake driver: " + problems);
        }
        if (mode.equals("auth")) {
            throw new SQLException("ORA-01017: invalid credential or not authorized; logon denied\nhttps://docs.oracle.com/error-help/db/ora-01017/", "72000", 1017);
        }
        if (mode.equals("locked")) {
            throw new SQLException("ORA-28000: The account is locked.", "99999", 28000);
        }
        if (mode.equals("network")) {
            throw new SQLRecoverableException("IO Error: The Network Adapter could not establish the connection", "08006", 17002);
        }
        return connection(mode, password);
    }

    static void expect(List<String> problems, Properties info, String name, String value) {
        String actual = info.getProperty(name);
        if (value == null ? actual != null : !value.equals(actual)) {
            problems.add(name + "=" + actual);
        }
    }

    static Connection connection(String mode, String password) {
        boolean[] state = new boolean[2];
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "setAutoCommit":
                    state[0] = !(Boolean) args[0];
                    return null;
                case "prepareStatement":
                    if (!"SELECT DSO_PORTAL.DSO_LIBRARY_CONFIG(?) FROM DUAL".equals(args[0])) {
                        throw new SQLException("fake driver: unexpected statement " + args[0]);
                    }
                    return statement(mode, password);
                case "rollback":
                    state[1] = true;
                    return null;
                case "getWarnings":
                    return mode.equals("warn") ? new SQLWarning("ORA-28098: The password will expire within 7 days.", "99999", 28098) : null;
                case "close":
                    if (!state[0] || !state[1]) {
                        throw new SQLException("fake driver: autoCommit off " + state[0] + ", rolled back " + state[1]);
                    }
                    return null;
                default:
                    throw new SQLException("fake driver: unexpected call " + method.getName());
            }
        };
        return (Connection) Proxy.newProxyInstance(FakeOracleDriver.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
    }

    static PreparedStatement statement(String mode, String password) {
        String[] key = new String[1];
        int[] timeout = new int[1];
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "setQueryTimeout":
                    timeout[0] = (Integer) args[0];
                    return null;
                case "setString":
                    key[0] = (String) args[1];
                    return null;
                case "executeQuery":
                    if (timeout[0] != 20) {
                        throw new SQLException("fake driver: query timeout " + timeout[0]);
                    }
                    if (mode.equals("grant")) {
                        throw new SQLException("ORA-00942: table or view does not exist (" + key[0] + " as " + password + ")", "42000", 942);
                    }
                    return rows(key[0].endsWith("0000") ? null : DOCUMENT);
                case "close":
                    return null;
                default:
                    throw new SQLException("fake driver: unexpected call " + method.getName());
            }
        };
        return (PreparedStatement) Proxy.newProxyInstance(FakeOracleDriver.class.getClassLoader(), new Class<?>[] {PreparedStatement.class}, handler);
    }

    static ResultSet rows(String value) {
        boolean[] read = new boolean[1];
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "next":
                    boolean first = !read[0];
                    read[0] = true;
                    return first;
                case "getString":
                    return value;
                case "close":
                    return null;
                default:
                    throw new SQLException("fake driver: unexpected call " + method.getName());
            }
        };
        return (ResultSet) Proxy.newProxyInstance(FakeOracleDriver.class.getClassLoader(), new Class<?>[] {ResultSet.class}, handler);
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith("jdbc:fake:");
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getGlobal();
    }
}
