package ch.predmet.jarness.core.mod;

import ch.predmet.jarness.api.instrument.Instrument;
import ch.predmet.jarness.api.instrument.InstrumentContext;
import ch.predmet.jarness.api.instrument.InstrumentRegistrar;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * {@link InstrumentRegistrar} bound to one mod id. Collects the instruments a mod declares while
 * {@code register()} runs; the loader drains them afterwards.
 */
public final class InstrumentRegistrarImpl implements InstrumentRegistrar {

    private final String modId;
    private final List<Instrument> collected = new ArrayList<>();

    /**
     * Create a registrar for one mod.
     *
     * @param modId the owning mod id; stamped onto every lambda instrument
     */
    public InstrumentRegistrarImpl(String modId) {
        this.modId = Objects.requireNonNull(modId, "modId");
    }

    @Override
    public Instrument register(String name, boolean autoEnable, int priority, String description,
                               Consumer<InstrumentContext> body) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(body, "body");
        Instrument instrument = new LambdaInstrument(modId, name, autoEnable, priority,
                description == null ? "" : description, body);
        collected.add(instrument);
        return instrument;
    }

    @Override
    public Instrument register(Instrument instrument) {
        Objects.requireNonNull(instrument, "instrument");
        if (!modId.equals(instrument.modId())) {
            throw new IllegalArgumentException("instrument '" + instrument.name() + "' declares mod '"
                    + instrument.modId() + "' but is registered by mod '" + modId + "'");
        }
        collected.add(instrument);
        return instrument;
    }

    /**
     * Take every collected instrument, clearing the registrar.
     *
     * @return the instruments declared since the last drain, in declaration order
     */
    public List<Instrument> drain() {
        List<Instrument> out = List.copyOf(collected);
        collected.clear();
        return out;
    }

    private record LambdaInstrument(String modId, String name, boolean autoEnable, int priority,
                                    String description, Consumer<InstrumentContext> body)
            implements Instrument {

        @Override
        public void apply(InstrumentContext ctx) {
            body.accept(ctx);
        }
    }
}
