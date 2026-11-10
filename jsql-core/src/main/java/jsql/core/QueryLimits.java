package jsql.core;

/**
 * Limits applied to every query that returns data.
 *
 * @param maxRows             maximum number of rows returned; more rows set the truncated flag
 * @param queryTimeoutSeconds statement timeout, 0 means no timeout
 * @param maxValueLength      maximum characters of a single value; longer values are cut
 */
public record QueryLimits(int maxRows, int queryTimeoutSeconds, int maxValueLength, int maxQueryTimeoutSeconds) {

    public static final QueryLimits DEFAULT = new QueryLimits(1000, 60, 1000, 3600);

    public QueryLimits(int maxRows, int queryTimeoutSeconds, int maxValueLength) {
        this(maxRows, queryTimeoutSeconds, maxValueLength, 3600);
    }

    public QueryLimits {
        if (maxRows < 1) {
            throw new IllegalArgumentException("maxRows must be at least 1");
        }
        if (queryTimeoutSeconds < 0) {
            throw new IllegalArgumentException("queryTimeoutSeconds must not be negative");
        }
        if (maxValueLength < 1) {
            throw new IllegalArgumentException("maxValueLength must be at least 1");
        }
        if (maxQueryTimeoutSeconds < 0) {
            throw new IllegalArgumentException("maxQueryTimeoutSeconds must not be negative");
        }
    }

    /**
     * The timeout to apply: the configured default when none is asked for, otherwise the asked-for
     * value cut to {@link #maxQueryTimeoutSeconds()}.
     *
     * @throws InvalidRequestException if a timeout is given that is zero or negative
     */
    public int effectiveTimeout(Integer customTimeoutSeconds) {
        if (customTimeoutSeconds == null) {
            if (queryTimeoutSeconds > 0 && maxQueryTimeoutSeconds > 0) {
                return Math.min(queryTimeoutSeconds, maxQueryTimeoutSeconds);
            }
            return queryTimeoutSeconds;
        }
        if (customTimeoutSeconds <= 0) {
            throw new InvalidRequestException("timeout_seconds must be at least 1.");
        }
        if (maxQueryTimeoutSeconds > 0) {
            return Math.min(customTimeoutSeconds, maxQueryTimeoutSeconds);
        }
        return customTimeoutSeconds;
    }
}
