package ch.predmet.jarness.core.ipc;

import ch.predmet.jarness.api.ipc.Channel;
import ch.predmet.jarness.api.ipc.ChannelClosedException;

import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/**
 * Bounded channel; capacity 0 is a rendezvous channel.
 */
final class ChannelImpl implements Channel {

    private final String id;
    private final int capacity;
    private final BlockingQueue<Object> queue;
    private volatile boolean closed;

    ChannelImpl(String id, int capacity) {
        this.id = id;
        this.capacity = Math.max(0, capacity);
        this.queue = this.capacity == 0 ? new SynchronousQueue<>() : new LinkedBlockingQueue<>(this.capacity);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public int size() {
        return queue.size();
    }

    @Override
    public boolean closed() {
        return closed;
    }

    @Override
    public void send(Object value) {
        if (closed) {
            throw new ChannelClosedException(id);
        }
        try {
            while (!closed) {
                if (queue.offer(value, 100, TimeUnit.MILLISECONDS)) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelClosedException(id);
        }
        throw new ChannelClosedException(id);
    }

    @Override
    public boolean trySend(Object value) {
        return !closed && queue.offer(value);
    }

    @Override
    public Object recv() {
        try {
            while (true) {
                if (closed && queue.isEmpty()) {
                    throw new ChannelClosedException(id);
                }
                Object value = queue.poll(100, TimeUnit.MILLISECONDS);
                if (value != null) {
                    return value;
                }
                if (closed) {
                    throw new ChannelClosedException(id);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelClosedException(id);
        }
    }

    @Override
    public Optional<Object> tryRecv() {
        if (closed && queue.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(queue.poll());
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public String toString() {
        return "Channel[" + id + ", capacity=" + capacity + "]";
    }
}
