package ch.predmet.jarness.core.provider;

import ch.predmet.jarness.api.provider.ModelInfo;
import ch.predmet.jarness.api.provider.Provider;
import ch.predmet.jarness.api.provider.ProviderKind;
import ch.predmet.jarness.api.provider.ProviderProfile;

import java.util.List;
import java.util.Objects;

/**
 * Base for the HTTP-backed provider adapters: profile bookkeeping shared by every wire protocol.
 */
abstract class AbstractHttpProvider implements Provider {

    private final ProviderProfile profile;
    private final ProviderKind kind;
    final HttpTransport http;

    AbstractHttpProvider(ProviderProfile profile, ProviderKind kind, HttpTransport.AuthStyle authStyle) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.http = new HttpTransport(profile, authStyle);
    }

    @Override
    public String id() {
        return profile.id();
    }

    @Override
    public ProviderKind kind() {
        return kind;
    }

    @Override
    public ProviderProfile profile() {
        return profile;
    }

    @Override
    public List<ModelInfo> models() {
        return profile.models();
    }
}
