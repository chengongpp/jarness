package ch.predmet.jarness.api.mailbox;

import java.time.Duration;
import java.util.Optional;

/**
 * A bounded per-agent mailbox.
 *
 * <p>Bounded on purpose: full mailboxes are backpressure, not an invitation to grow without limit.
 * {@link #offer} returns {@code false} when full so a sender can decide what to do.</p>
 */
public interface Mailbox extends AutoCloseable {

    /**
     * Offer a message without blocking.
     *
     * @param message message
     * @return whether it was accepted
     */
    boolean offer(MailboxMessage message);

    /**
     * Take the next message, blocking.
     *
     * @return the message
     * @throws InterruptedException when interrupted
     */
    MailboxMessage take() throws InterruptedException;

    /**
     * Poll for the next message.
     *
     * @param timeout maximum wait
     * @return the message, or empty
     */
    Optional<MailboxMessage> poll(Duration timeout);

    /** @return queued messages. */
    int size();

    /** @return configured capacity. */
    int capacity();

    /** @return whether the mailbox is closed. */
    boolean closed();

    @Override
    void close();
}
