package jsql.core;

/**
 * Limits applied to every query that returns data.
 *
 * @param maxRows             maximum number of rows returned; more rows set the truncated flag
 * @param queryTimeoutSeconds statement timeout, 0 means no timeout
 * @param maxValueLength      maximum characters of a single value; longer values are cut
 */
public record QueryLimits(int maxRows, int queryTimeoutSeconds, int maxValueLength) {

    public static final QueryLimits DEFAULT = new QueryLimits(1000, 60, 1000);

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
    }
}
