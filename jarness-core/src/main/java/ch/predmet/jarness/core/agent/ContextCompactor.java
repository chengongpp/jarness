package ch.predmet.jarness.core.agent;

import ch.predmet.jarness.api.session.Message;
import ch.predmet.jarness.api.session.Role;

import java.util.ArrayList;
import java.util.List;

/**
 * Drops the oldest part of a conversation when it grows past the configured window, keeping the
 * cut on a user-message boundary so tool-call / tool-result pairs are not split.
 */
final class ContextCompactor {

    private ContextCompactor() {
    }

    /**
     * @param messages conversation to compact
     * @param keep     maximum number of trailing messages to preserve
     * @return the compacted conversation with an explicit omission note
     */
    static List<Message> compact(List<Message> messages, int keep) {
        if (messages == null || messages.size() <= keep || keep <= 0) {
            return messages == null ? List.of() : messages;
        }
        int cut = messages.size() - keep;
        while (cut < messages.size() && messages.get(cut).role() != Role.USER) {
            cut++;
        }
        if (cut >= messages.size()) {
            cut = Math.max(0, messages.size() - keep);
        }
        List<Message> out = new ArrayList<>(messages.size() - cut + 1);
        out.add(Message.user("[Context compacted: " + cut + " earlier messages omitted]"));
        out.addAll(messages.subList(cut, messages.size()));
        return out;
    }
}
