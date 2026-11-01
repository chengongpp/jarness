package ch.predmet.jarness.api.ipc;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * A bounded message channel with a single consumer per message.
 *
 * <p>A capacity of 0 is a rendezvous channel: {@link #send} blocks until a receiver takes the
 * message. Broadcast is deliberately not a channel feature; use a topic with per-subscriber
 * mailboxes instead.</p>
 */
public interface Channel extends AutoCloseable {

    /** @return channel id. */
    String id();

    /** @return configured capacity; 0 means rendezvous. */
    int capacity();

    /** @return queued messages. */
    int size();

    /** @return whether the channel is closed. */
    boolean closed();

    /**
     * Send, blocking when full.
     *
     * @param value message
     * @throws ChannelClosedException when the channel is closed
     */
    void send(Object value);

    /**
     * Send without blocking.
     *
     * @param value message
     * @return whether the message was accepted
     */
    boolean trySend(Object value);

    /**
     * Receive, blocking until a message exists.
     *
     * @return the message
     * @throws ChannelClosedException when the channel is closed and drained
     */
    Object recv();

    /**
     * Receive without blocking.
     *
     * @return the message, or empty
     */
    Optional<Object> tryRecv();

    /** Close the channel; blocked senders/receivers wake up. */
    @Override
    void close();

    /** A selected message. */
    record Selection(Channel channel, Object value) {
    }

    /**
     * Wait for the first message on any channel.
     *
     * @param channels channels to poll
     * @param timeout  maximum wait
     * @return the selected message, or empty on timeout
     */
    static Optional<Selection> select(List<Channel> channels, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            for (Channel channel : channels) {
                Optional<Object> value = channel.tryRecv();
                if (value.isPresent()) {
                    return Optional.of(new Selection(channel, value.get()));
                }
                if (channel.closed()) {
                    return Optional.of(new Selection(channel, null));
                }
            }
            if (System.nanoTime() >= deadline) {
                return Optional.empty();
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }
}
