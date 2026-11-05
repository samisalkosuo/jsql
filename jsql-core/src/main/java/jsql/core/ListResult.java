package jsql.core;

import java.util.List;

/**
 * A list that may have been cut at the configured maximum.
 *
 * @param items     the items, at most the configured maximum
 * @param truncated true if more items exist than were returned
 */
public record ListResult<T>(List<T> items, boolean truncated) {
}
