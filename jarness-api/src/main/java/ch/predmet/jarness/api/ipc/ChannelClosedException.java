package ch.predmet.jarness.api.ipc;

/** Thrown when a channel is used after it was closed. */
public final class ChannelClosedException extends RuntimeException {

    /**
     * @param channelId channel id
     */
    public ChannelClosedException(String channelId) {
        super("channel " + channelId + " is closed");
    }
}
