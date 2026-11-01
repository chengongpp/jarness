package ch.predmet.jarness.core.agent;

import ch.predmet.jarness.api.mailbox.Mailbox;
import ch.predmet.jarness.api.mailbox.MailboxMessage;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** Bounded mailbox backed by an {@link ArrayBlockingQueue}. */
final class MailboxImpl implements Mailbox {

    private final BlockingQueue<MailboxMessage> queue;
    private volatile boolean closed;

    MailboxImpl(int capacity) {
        this.queue = new ArrayBlockingQueue<>(Math.max(1, capacity));
    }

    @Override
    public boolean offer(MailboxMessage message) {
        return !closed && message != null && queue.offer(message);
    }

    @Override
    public MailboxMessage take() throws InterruptedException {
        while (true) {
            MailboxMessage message = queue.poll(100, TimeUnit.MILLISECONDS);
            if (message != null) {
                return message;
            }
            if (closed) {
                throw new IllegalStateException("mailbox is closed");
            }
        }
    }

    @Override
    public Optional<MailboxMessage> poll(Duration timeout) {
        try {
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                return Optional.ofNullable(queue.poll());
            }
            return Optional.ofNullable(queue.poll(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    @Override
    public int size() {
        return queue.size();
    }

    @Override
    public int capacity() {
        return queue.size() + queue.remainingCapacity();
    }

    @Override
    public boolean closed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
    }
}
