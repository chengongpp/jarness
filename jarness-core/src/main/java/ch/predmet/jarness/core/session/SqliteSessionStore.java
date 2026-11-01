package ch.predmet.jarness.core.session;

/**
 * Historic name of {@link LocalSessionStore}, kept so existing embeddings keep compiling.
 *
 * @deprecated use {@link LocalSessionStore}; this class carries no behavior of its own
 */
@Deprecated
public class SqliteSessionStore extends LocalSessionStore {

    /**
     * @param jdbcUrl the JDBC URL
     */
    public SqliteSessionStore(String jdbcUrl) {
        super(jdbcUrl);
    }
}
