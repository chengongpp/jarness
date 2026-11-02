package ch.predmet.jarness.core.exec;

import ch.predmet.jarness.api.exec.ExecutionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import javax.net.ssl.SSLContext;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Listener for remote executors.
 *
 * <p>The direction is the same as the local executor: the executor connects back to the daemon and
 * presents an enrollment token. Each accepted connection becomes a
 * {@link ch.predmet.jarness.api.exec.ExecutionProvider} of kind {@code REMOTE}. The listener is
 * opt-in and defaults to loopback; TLS uses the configured PKCS12 context and never silently falls
 * back to plaintext when certificate loading fails.</p>
 */
public final class RemoteExecutorServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RemoteExecutorServer.class);

    private final ServerSocket server;
    private final String token;
    private final ExecutionRegistry registry;
    private final Thread acceptThread;
    private final AtomicInteger counter = new AtomicInteger();
    private final List<LocalExecutionProvider> accepted = new ArrayList<>();
    private volatile boolean closed;

    private RemoteExecutorServer(ServerSocket server, String token, ExecutionRegistry registry) {
        this.server = server;
        this.token = token;
        this.registry = registry;
        this.acceptThread = Thread.ofVirtual().name("jarness-remote-executor-accept").start(this::acceptLoop);
    }

    /**
     * Bind and start accepting remote executors.
     *
     * @param host     bind address
     * @param port     bind port; {@code 0} for an ephemeral port
     * @param token    enrollment token the executor must present
     * @param registry registry to add providers to
     * @return the server
     * @throws IOException when the socket cannot be bound
     */
    public static RemoteExecutorServer start(String host, int port, String token, ExecutionRegistry registry)
            throws IOException {
        return start(host, port, token, registry, null);
    }

    /**
     * Bind and start accepting remote executors, optionally over TLS.
     *
     * @param host     bind address
     * @param port     bind port; {@code 0} for an ephemeral port
     * @param token    enrollment token the executor must present
     * @param registry registry to add providers to
     * @param tls      TLS context, or {@code null} for plaintext loopback
     * @return the server
     * @throws IOException when the socket cannot be bound
     */
    public static RemoteExecutorServer start(String host, int port, String token, ExecutionRegistry registry,
                                             SSLContext tls) throws IOException {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("remote enrollment token must not be blank");
        }
        ServerSocket socket = tls == null ? new ServerSocket()
                : tls.getServerSocketFactory().createServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(host == null || host.isBlank() ? "127.0.0.1" : host, port));
        RemoteExecutorServer server = new RemoteExecutorServer(socket, token, registry);
        LOG.info("remote executor listener on {}:{} ({})", socket.getInetAddress().getHostAddress(),
                socket.getLocalPort(), tls == null ? "plaintext" : "TLS");
        return server;
    }

    /** @return the bound port. */
    public int port() {
        return server.getLocalPort();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = server.accept();
                String id = "remote-executor-" + counter.incrementAndGet();
                try {
                    LocalExecutionProvider provider = LocalExecutionProvider.acceptRemote(id, token, socket);
                    synchronized (accepted) {
                        accepted.add(provider);
                    }
                    registry.register(provider);
                    LOG.info("remote executor {} connected from {}", id, socket.getRemoteSocketAddress());
                } catch (IOException e) {
                    LOG.warn("rejected remote executor connection: {}", e.getMessage());
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // best effort
                    }
                }
            } catch (IOException e) {
                if (!closed) {
                    LOG.warn("remote executor accept failed: {}", e.getMessage());
                }
                return;
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // best effort
        }
        synchronized (accepted) {
            for (LocalExecutionProvider provider : accepted) {
                provider.close();
            }
            accepted.clear();
        }
        acceptThread.interrupt();
    }
}
