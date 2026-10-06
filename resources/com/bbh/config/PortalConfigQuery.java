import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLWarning;
import java.util.Locale;
import java.util.Properties;

public class PortalConfigQuery {

    public static void main(String[] args) throws IOException {
        Files.write(Paths.get(System.getenv("DSO_PORTAL_OUTPUT")), run().getBytes(StandardCharsets.UTF_8));
    }

    static String run() {
        String url = env("DSO_PORTAL_DB_URL").trim();
        String user = env("DSO_PORTAL_DB_USER");
        String password = env("DSO_PORTAL_DB_PASSWORD");
        String schema = env("DSO_PORTAL_DB_SCHEMA").trim();
        String keys = env("DSO_PORTAL_KEYS").trim();
        if (url.isEmpty() || user.isEmpty() || password.isEmpty() || keys.isEmpty()) {
            return error("config", "DSO_PORTAL_DB_URL, DSO_PORTAL_DB_USER, DSO_PORTAL_DB_PASSWORD and DSO_PORTAL_KEYS must be set");
        }
        if (!schema.matches("[A-Za-z][A-Za-z0-9_$#]{0,127}")) {
            return error("config", "DSO_PORTAL_DB_SCHEMA is not an Oracle schema name");
        }
        String[] list = keys.split(",");
        long[] waits = {5000L, 15000L};
        for (int attempt = 0; ; attempt++) {
            try {
                return query(url, user, password, schema, list);
            } catch (SQLException e) {
                String type = classify(e);
                if (!"network".equals(type) || attempt == waits.length) {
                    return error(type, describe(e, attempt + 1, password, list));
                }
                pause(waits[attempt]);
            }
        }
    }

    static String query(String url, String user, String password, String schema, String[] keys) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("oracle.net.CONNECT_TIMEOUT", "10000");
        properties.setProperty("oracle.jdbc.ReadTimeout", "30000");
        properties.setProperty("oracle.jdbc.timezoneAsRegion", "false");
        if (!url.toLowerCase(Locale.ROOT).contains("tcps")) {
            properties.setProperty("oracle.net.encryption_client", "REQUIRED");
            properties.setProperty("oracle.net.encryption_types_client", "(AES256)");
            properties.setProperty("oracle.net.crypto_checksum_client", "REQUIRED");
            properties.setProperty("oracle.net.crypto_checksum_types_client", "(SHA256)");
        }
        DriverManager.setLoginTimeout(15);
        StringBuilder out = new StringBuilder("{\"results\":[");
        try (Connection connection = DriverManager.getConnection(url, properties)) {
            connection.setAutoCommit(false);
            try {
                for (int i = 0; i < keys.length; i++) {
                    try (PreparedStatement statement = connection.prepareStatement("SELECT " + schema + ".DSO_LIBRARY_CONFIG(?) FROM DUAL")) {
                        statement.setQueryTimeout(20);
                        statement.setString(1, keys[i]);
                        try (ResultSet rows = statement.executeQuery()) {
                            String text = rows.next() ? rows.getString(1) : null;
                            out.append(i == 0 ? "" : ",").append(text == null ? "null" : entry(text));
                        }
                    }
                }
            } finally {
                connection.rollback();
            }
            out.append(']');
            for (SQLWarning warning = connection.getWarnings(); warning != null; warning = warning.getNextWarning()) {
                if (warning.getErrorCode() == 28002 || warning.getErrorCode() == 28098) {
                    out.append(",\"warning\":\"password expires soon\"");
                    break;
                }
            }
        }
        return out.append('}').toString();
    }

    static String entry(String text) {
        String json = ascii(text).trim();
        String body = json.substring(0, json.lastIndexOf('}')).trim();
        return body + (body.endsWith("{") ? "" : ",") + "\"sha256\":\"" + sha256(text) + "\"}";
    }

    static String ascii(String text) {
        StringBuilder out = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString && c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(++i);
                out.append(next == '/' ? "/" : "\\" + next);
            } else if (c == '"') {
                inString = !inString;
                out.append(c);
            } else if (c >= 0x20 && c <= 0x7E) {
                out.append(c);
            } else if (inString) {
                out.append(String.format("\\u%04x", (int) c));
            }
        }
        return out.toString();
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i] & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String classify(SQLException e) {
        int code = e.getErrorCode();
        String message = String.valueOf(e.getMessage());
        if (code == 1017) {
            return "auth";
        }
        if (code == 28000) {
            return "locked";
        }
        if (code == 28001) {
            return "expired";
        }
        if (code == 942 || code == 1031 || code == 6550 || code == 904) {
            return "grant";
        }
        if (e instanceof SQLRecoverableException || code == 12170 || code == 12514 || code == 12541 || code == 3113
                || code == 17002 || message.contains("IO Error")) {
            return "network";
        }
        if (message.startsWith("No suitable driver")) {
            return "driver";
        }
        return "other";
    }

    static String describe(SQLException e, int attempts, String password, String[] keys) {
        String text = String.valueOf(e.getMessage()).split("\\R", 2)[0];
        if (e.getErrorCode() > 0 && !text.startsWith("ORA-")) {
            text = String.format("ORA-%05d: %s", e.getErrorCode(), text);
        }
        if (text.length() > 300) {
            text = text.substring(0, 300);
        }
        for (String key : keys) {
            text = text.replace(key, "***");
        }
        if (!password.isEmpty()) {
            text = text.replace(password, "***");
        }
        return attempts > 1 ? text + " (after " + attempts + " attempts)" : text;
    }

    static String error(String type, String message) {
        return "{\"error\":" + quote(type) + ",\"message\":" + quote(message) + "}";
    }

    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20 || c > 0x7E) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }

    static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
