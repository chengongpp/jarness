package ch.predmet.jarness.api.namespace;

import ch.predmet.jarness.api.session.SessionStore;

/**
 * One execution environment: {@code Metadata + SessionStore + defaultAgentTemplate}.
 *
 * <p>A namespace decides three things at once: what an agent is (metadata, model tier, capability
 * ceiling), where its image lives (the session store), and which template it starts from when the
 * manifest names none. Every agent is bound to exactly one namespace for its whole life.</p>
 *
 * <p>Execution providers will join this interface with the execution layer; until then the
 * namespace is the routing point that later gains them.</p>
 */
public interface Namespace {

    /** The id of the namespace that always exists. */
    String DEFAULT_ID = "default";

    /** @return the namespace id, unique within the runtime. */
    String id();

    /** @return whether this is the {@code default} namespace. */
    boolean isDefault();

    /** @return the namespace metadata. */
    NamespaceMetadata metadata();

    /** @return the store that owns this namespace's images. */
    SessionStore store();

    /** @return the id of the template used when a manifest names none. */
    String defaultTemplateId();

    /** @return the required template version, or an empty string for "any version". */
    String defaultTemplateVersion();
}
