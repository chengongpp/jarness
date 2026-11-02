package ch.predmet.jarness.tui;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.agent.TurnResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.List;

/**
 * A deliberately small terminal UI: one input line is one turn on the active agent.
 *
 * <p>Slash commands: {@code /help}, {@code /agents}, {@code /new <template> [name]},
 * {@code /use <id-prefix>}, {@code /stop}, {@code /quit}. Anything else goes to the active agent
 * as a user input.</p>
 */
final class TuiRepl implements Runnable {

    private final JarnessRuntime runtime;
    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
    private final PrintStream out = System.out;
    private volatile Agent active;

    TuiRepl(JarnessRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public void run() {
        try {
            out.println("jarness " + runtime.version() + " TUI — /help for commands, /quit to exit");
            String template = runtime.namespaces().defaultNs().defaultTemplateId();
            if (template == null || template.isBlank()) {
                out.println("tui: the default namespace has no default template; exiting TUI");
                return;
            }
            active = spawn(template, "main");
            if (active == null) {
                out.println("tui: could not spawn an agent; exiting TUI");
                return;
            }
            out.println("talking to " + active.name() + " (" + active.id() + ", template " + template + ")");
            String line;
            while (runtime.isRunning() && (line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith("/")) {
                    if (!command(line)) {
                        break;
                    }
                } else {
                    turn(line);
                }
            }
        } catch (IOException e) {
            out.println("tui: input failed: " + e.getMessage());
        } finally {
            // Every exit path, including initial spawn failure, releases the runtime and lets the
            // CLI's wait loop terminate instead of leaving a headless process running forever.
            runtime.close();
        }
    }

    private void turn(String input) {
        try {
            TurnResult result = active.submit(input).join();
            if (!result.output().isBlank()) {
                out.println(result.output());
            }
            if (!result.ok()) {
                out.println("[" + result.state() + (result.error() == null ? "" : ": " + result.error()) + "]");
            }
        } catch (RuntimeException e) {
            out.println("turn failed: " + e.getMessage());
        }
    }

    private boolean command(String line) {
        String[] parts = line.split("\\s+");
        switch (parts[0]) {
            case "/quit", "/exit" -> {
                return false;
            }
            case "/help" -> out.println("""
                    /agents              list running agents
                    /new <tpl> [name]    spawn an agent and switch to it
                    /super               switch to the platform super agent (pid 0)
                    /use <id-prefix>     switch the active agent
                    /stop                stop the active agent
                    /quit                leave the TUI""");
            case "/super" -> {
                Agent superAgent = runtime.supervisor().ensureSuperAgent();
                active = superAgent;
                out.println("now talking to the super agent (" + superAgent.id() + ")");
            }
            case "/agents" -> runtime.supervisor().statuses().forEach(s ->
                    out.printf("%s  %-14s %-8s %-8s turns=%d%n", s.agentId(), s.name(),
                            s.templateId(), s.state(), s.turns()));
            case "/new" -> {
                String template = parts.length > 1 ? parts[1]
                        : runtime.namespaces().defaultNs().defaultTemplateId();
                String name = parts.length > 2 ? parts[2] : null;
                Agent agent = spawn(template, name);
                if (agent != null) {
                    active = agent;
                    out.println("now talking to " + agent.name() + " (" + agent.id() + ")");
                }
            }
            case "/use" -> {
                if (parts.length < 2) {
                    out.println("usage: /use <id-prefix>");
                } else {
                    List<Agent> matches = runtime.supervisor().agents().stream()
                            .filter(a -> a.id().startsWith(parts[1])).toList();
                    if (matches.size() == 1) {
                        active = matches.get(0);
                        out.println("now talking to " + active.name() + " (" + active.id() + ")");
                    } else {
                        out.println("no unique agent for prefix " + parts[1] + " (" + matches.size() + " matches)");
                    }
                }
            }
            case "/stop" -> {
                active.stop("tui /stop");
                out.println("stopped " + active.id());
            }
            default -> out.println("unknown command " + parts[0] + " — /help lists them");
        }
        return true;
    }

    private Agent spawn(String template, String name) {
        try {
            return runtime.spawn(AgentRequest.builder(template).name(name).build());
        } catch (RuntimeException e) {
            out.println("spawn failed: " + e.getMessage());
            return null;
        }
    }
}
