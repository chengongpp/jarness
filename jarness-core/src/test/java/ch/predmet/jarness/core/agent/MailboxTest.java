package ch.predmet.jarness.core.agent;

import ch.predmet.jarness.api.mailbox.Mailbox;
import ch.predmet.jarness.api.mailbox.MailboxMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailboxTest {

    @Test
    void capacityIsABackpressurePoint() {
        Mailbox mailbox = new MailboxImpl(1);
        assertTrue(mailbox.offer(MailboxMessage.data(0, "first")));
        assertFalse(mailbox.offer(MailboxMessage.data(0, "second")));
        assertEquals(1, mailbox.size());
        assertEquals("first", mailbox.poll(java.time.Duration.ZERO).orElseThrow().payload());
        assertTrue(mailbox.offer(MailboxMessage.data(0, "second")));
    }

    @Test
    void closeMakesTheMailboxUnavailable() {
        Mailbox mailbox = new MailboxImpl(2);
        mailbox.close();
        assertTrue(mailbox.closed());
        assertFalse(mailbox.offer(MailboxMessage.data(0, "x")));
    }
}
