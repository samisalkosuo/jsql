package jsql.core;

/** A table identified by the names the database itself reports. Catalog or schema may be null. */
public record TableRef(String catalog, String schema, String name) {
}
