package ch.predmet.jarness.api.mailbox;

import java.time.Instant;

/**
 * One message in an agent's mailbox.
 *
 * <p>The mailbox is the agent's inbound edge: control signals, collaboration messages, and
 * asynchronous tool completions all arrive here. Data messages become turns; control messages are
 * delivered to the agent's signal handler.</p>
 *
 * @param kind    message kind
 * @param fromPid sender pid, {@code 0} for the host or supervisor
 * @param payload text for data/async messages, or an {@code AgentSignal} for control
 * @param at      creation time
 */
public record MailboxMessage(Kind kind, long fromPid, Object payload, Instant at) {

    /** Message kind. */
    public enum Kind {
        /** A control signal; payload is an {@code AgentSignal}. */
        CONTROL,
        /** Text to run as a new turn. */
        DATA,
        /** Completion of an asynchronous tool call. */
        ASYNC_RESULT
    }

    /** Payload of an async completion. */
    public record AsyncResult(String callId, String tool, boolean ok, String text) {
    }

    public MailboxMessage {
        payload = payload == null ? "" : payload;
        at = at == null ? Instant.now() : at;
    }

    /** @return a data message that becomes a turn. */
    public static MailboxMessage data(long fromPid, String text) {
        return new MailboxMessage(Kind.DATA, fromPid, text, Instant.now());
    }

    /** @return a control message. */
    public static MailboxMessage control(long fromPid, ch.predmet.jarness.api.agent.AgentSignal signal) {
        return new MailboxMessage(Kind.CONTROL, fromPid, signal, Instant.now());
    }

    /** @return an async completion message. */
    public static MailboxMessage asyncResult(long fromPid, AsyncResult result) {
        return new MailboxMessage(Kind.ASYNC_RESULT, fromPid, result, Instant.now());
    }
}
