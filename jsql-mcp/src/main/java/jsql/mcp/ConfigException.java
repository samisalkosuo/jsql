package jsql.mcp;

import java.util.List;

/** The server configuration (environment variables) is invalid. The message lists every problem. */
public class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConfigException(List<String> problems) {
        super(String.join("; ", problems));
    }
}
