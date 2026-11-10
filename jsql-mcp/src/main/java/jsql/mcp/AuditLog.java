package jsql.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Server-side audit logger that records statement executions without credentials or row data.
 */
@ApplicationScoped
public class AuditLog {

    private static final Logger LOG = Logger.getLogger(AuditLog.class.getName());

    public void log(String connectionName, String sql, long elapsedMs, int rowCount) {
        String hash = sqlHash(sql);
        String conn = connectionName == null ? "default" : connectionName;
        LOG.info("[AUDIT] conn=" + conn + " elapsed=" + elapsedMs + "ms rows=" + rowCount + " sqlHash=" + hash);
    }

    static String sqlHash(String sql) {
        if (sql == null) {
            return "none";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sql.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }
}
