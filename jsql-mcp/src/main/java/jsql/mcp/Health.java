package jsql.mcp;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Health endpoints for container probes: {@code /health/live} and {@code /health/ready}.
 * They deliberately do not connect to the database: the server may have no default account, and a
 * database outage must not make the platform restart this server.
 */
public class Health {

    @Liveness
    @ApplicationScoped
    public static class Live implements HealthCheck {
        @Override
        public HealthCheckResponse call() {
            return HealthCheckResponse.up("jsql-mcp");
        }
    }

    @Readiness
    @ApplicationScoped
    public static class Ready implements HealthCheck {
        @Inject
        ServerState state;

        @Override
        public HealthCheckResponse call() {
            String problem = state.configProblem();
            if (problem == null) {
                return HealthCheckResponse.up("jsql-mcp-configuration");
            }
            return HealthCheckResponse.named("jsql-mcp-configuration").down().withData("problem", problem).build();
        }
    }
}
