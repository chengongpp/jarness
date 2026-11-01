package ch.predmet.jarness.core.exec;

import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.capability.Capability;
import ch.predmet.jarness.api.capability.CapabilitySet;
import ch.predmet.jarness.api.exec.ExecProviderKind;
import ch.predmet.jarness.api.exec.ExecRequest;
import ch.predmet.jarness.api.exec.ExecResult;
import ch.predmet.jarness.api.exec.ExecutionException;
import ch.predmet.jarness.api.exec.ExecutionProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The local execution provider: starts the Rust executor as a child process, accepts its callback
 * connection, and bridges calls over a JSON-lines protocol.
 *
 * <p>The daemon is the listener and the executor connects back, so a remote executor can use the
 * same protocol without an inbound port on the daemon. Every call carries a capability view and an
 * HMAC token derived from a one-time secret; the executor verifies both before touching a file or a
 * process.</p>
 */
public final class LocalExecutionProvider implements ExecutionProvider {

    /** Default executor tools advertised before the hello frame arrives. */
    public static final Set<String> DEFAULT_TOOLS = Set.of(
            "fs.read", "fs.write", "fs.edit", "fs.delete", "fs.glob", "fs.grep", "exec.run");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String id;
    private final String token;
    private final Path binary;
    private final ServerSocket server;
    private final Process process;
    private final Socket socket;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final Thread readerThread;
    private final Map<String, CompletableFuture<ExecResult>> pending = new ConcurrentHashMap<>();
    private final Set<String> tools = new LinkedHashSet<>(DEFAULT_TOOLS);

    private volatile boolean closed;
    private volatile String executorId = "local";

    private LocalExecutionProvider(String token, Path binary, ServerSocket server, Process process, Socket socket)
            throws IOException {
        this.id = "local-executor";
        this.token = token;
        this.binary = binary;
        this.server = server;
        this.process = process;
        this.socket = socket;
        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        handshake();
        this.readerThread = Thread.ofVirtual().name("jarness-executor-reader").start(this::readLoop);
    }

    /**
     * Start the local executor.
     *
     * @param baseDir the runtime base directory, used to find {@code bin/jarness-executor}
     * @return the provider
     * @throws IOException when the binary is missing or the handshake fails
     */
    public static LocalExecutionProvider start(Path baseDir) throws IOException {
        Path binary = findBinary(baseDir).orElseThrow(() -> new IOException(
                "no jarness executor binary found; build it with `make executor` or set JARNESS_EXECUTOR"));
        String token = randomToken();
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        server.setSoTimeout(20_000);
        Process process = null;
        Socket socket = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(binary.toString(),
                    "--connect", "127.0.0.1:" + server.getLocalPort());
            builder.environment().put("JARNESS_EXECUTOR_TOKEN", token);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            process = builder.start();
            socket = server.accept();
            socket.setTcpNoDelay(true);
            return new LocalExecutionProvider(token, binary, server, process, socket);
        } catch (IOException | RuntimeException e) {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
            if (process != null) {
                process.destroyForcibly();
            }
            try {
                server.close();
            } catch (IOException ignored) {
                // best effort
            }
            throw e;
        }
    }

    /**
     * Locate the executor binary.
     *
     * @param baseDir runtime base directory
     * @return the binary, if found
     */
    public static Optional<Path> findBinary(Path baseDir) {
        String property = System.getProperty("jarness.executor");
        if (property != null && !property.isBlank()) {
            return existing(Path.of(property));
        }
        String env = System.getenv("JARNESS_EXECUTOR");
        if (env != null && !env.isBlank()) {
            return existing(Path.of(env));
        }
        if (baseDir != null) {
            Optional<Path> fromBase = existing(baseDir.resolve("bin").resolve("jarness-executor"));
            if (fromBase.isPresent()) {
                return fromBase;
            }
        }
        Path cwd = Path.of("").toAbsolutePath();
        for (String candidate : new String[]{
                "dist/executor/jarness-executor",
                "executor/target/release/jarness-executor",
                "executor/target/debug/jarness-executor"}) {
            Optional<Path> path = existing(cwd.resolve(candidate));
            if (path.isPresent()) {
                return path;
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> existing(Path path) {
        return path != null && Files.isRegularFile(path) && Files.isExecutable(path)
                ? Optional.of(path.toAbsolutePath().normalize()) : Optional.empty();
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return hex(bytes);
    }

    private void handshake() throws IOException {
        String line = reader.readLine();
        if (line == null) {
            throw new IOException("executor closed the connection before hello");
        }
        JsonNode hello = parse(line);
        if (!"hello".equals(hello.path("type").asText())) {
            throw new IOException("expected hello, got " + hello.path("type").asText());
        }
        if (hello.path("protocol").asInt() != 1) {
            throw new IOException("unsupported executor protocol " + hello.path("protocol").asInt());
        }
        String presented = hello.path("token").asText("");
        if (!MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new IOException("executor authentication failed");
        }
        if (hello.has("executorId")) {
            executorId = hello.path("executorId").asText(executorId);
        }
        if (hello.path("tools").isArray()) {
            for (JsonNode tool : hello.path("tools")) {
                tools.add(tool.asText());
            }
        }
        ObjectNode ack = MAPPER.createObjectNode();
        ack.put("type", "hello_ack");
        ack.put("protocol", 1);
        send(ack);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public ExecProviderKind kind() {
        return ExecProviderKind.LOCAL;
    }

    @Override
    public Set<String> tools() {
        return Set.copyOf(tools);
    }

    @Override
    public Set<CapKind> capabilities() {
        return Set.of(CapKind.FS_READ, CapKind.FS_WRITE, CapKind.EXEC);
    }

    @Override
    public boolean alive() {
        return !closed && process.isAlive() && socket.isConnected() && !socket.isClosed();
    }

    @Override
    public ExecResult invoke(ExecRequest request) {
        if (!alive()) {
            throw new ExecutionException(ExecutionException.Reason.PROVIDER_DEAD,
                    "local executor is not running");
        }
        String callId = request.callId() == null || request.callId().isBlank()
                ? UUID.randomUUID().toString() : request.callId();
        long deadlineMs = request.deadline() == null
                ? System.currentTimeMillis() + 120_000L
                : request.deadline().toEpochMilli();
        CompletableFuture<ExecResult> future = new CompletableFuture<>();
        pending.put(callId, future);
        try {
            sendCall(request, callId, deadlineMs);
        } catch (IOException e) {
            pending.remove(callId);
            throw new ExecutionException(ExecutionException.Reason.PROVIDER_DEAD,
                    "could not write to executor: " + e.getMessage());
        }
        try {
            long wait = Math.max(1L, deadlineMs - System.currentTimeMillis());
            return future.get(wait, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(callId);
            cancel(callId, "deadline");
            return ExecResult.timeout("call " + callId + " exceeded its deadline");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(callId);
            return ExecResult.error("interrupted while waiting for executor");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ExecutionException execution) {
                throw execution;
            }
            throw new ExecutionException(ExecutionException.Reason.PROVIDER_DEAD,
                    "executor call failed: " + (cause == null ? e.getMessage() : cause.getMessage()));
        }
    }

    private void sendCall(ExecRequest request, String callId, long deadlineMs) throws IOException {
        String argsJson = MAPPER.writeValueAsString(request.args());
        String capsJson = capsJson(request.capabilities());
        String canonical = callId + "|" + request.tool() + "|" + argsJson + "|" + deadlineMs + "|" + capsJson;
        ObjectNode frame = MAPPER.createObjectNode();
        frame.put("type", "call");
        frame.put("callId", callId);
        frame.put("tool", request.tool());
        frame.put("argsJson", argsJson);
        frame.put("deadlineMs", deadlineMs);
        frame.put("mode", request.mode().name());
        frame.put("idempotencyKey", request.idempotencyKey() == null ? callId : request.idempotencyKey());
        frame.put("workspace", request.workspace() == null ? "" : request.workspace());
        frame.put("sandbox", request.sandbox());
        frame.put("namespace", request.namespace());
        frame.put("capsJson", capsJson);
        frame.put("capToken", hmac(canonical));
        send(frame);
    }

    @Override
    public void cancel(String callId, String reason) {
        if (!alive()) {
            return;
        }
        ObjectNode frame = MAPPER.createObjectNode();
        frame.put("type", "cancel");
        frame.put("callId", callId);
        frame.put("reason", reason == null ? "cancelled" : reason);
        try {
            send(frame);
        } catch (IOException e) {
            // the provider is going away; the pending future will be failed by the read loop
        }
    }

    private void readLoop() {
        try {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                JsonNode frame = parse(line);
                String type = frame.path("type").asText("");
                switch (type) {
                    case "result" -> complete(frame);
                    case "heartbeat", "ping" -> sendPong();
                    case "log" -> { /* executor diagnostics already go to stderr */ }
                    default -> { /* ack frames are not needed by the call path */ }
                }
            }
        } catch (IOException e) {
            if (!closed) {
                failPending("executor connection lost: " + e.getMessage());
            }
        } finally {
            if (!closed) {
                failPending("executor connection closed");
            }
        }
    }

    private void complete(JsonNode frame) {
        String callId = frame.path("callId").asText("");
        CompletableFuture<ExecResult> future = pending.remove(callId);
        if (future == null) {
            return;
        }
        JsonNode structured = frame.get("structured");
        if (structured != null && structured.isNull()) {
            structured = null;
        }
        ExecResult result = new ExecResult(
                frame.path("ok").asBoolean(false),
                frame.path("text").asText(""),
                frame.path("error").asText(""),
                frame.path("exitCode").asInt(-1),
                structured,
                Duration.ofMillis(frame.path("durationMs").asLong(0L)));
        future.complete(result);
    }

    private void failPending(String reason) {
        ExecutionException failure = new ExecutionException(ExecutionException.Reason.PROVIDER_DEAD, reason);
        for (Map.Entry<String, CompletableFuture<ExecResult>> entry : pending.entrySet()) {
            entry.getValue().completeExceptionally(failure);
        }
        pending.clear();
    }

    private void sendPong() throws IOException {
        ObjectNode frame = MAPPER.createObjectNode();
        frame.put("type", "pong");
        send(frame);
    }

    private synchronized void send(JsonNode frame) throws IOException {
        writer.write(MAPPER.writeValueAsString(frame));
        writer.write('\n');
        writer.flush();
    }

    private static JsonNode parse(String line) throws IOException {
        return MAPPER.readTree(line);
    }

    private String hmac(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static String capsJson(CapabilitySet caps) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Capability cap : caps.capabilities()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"kind\":\"").append(cap.kind().name()).append("\",\"resource\":\"")
                    .append(cap.resource().replace("\\", "\\\\").replace("\"", "\\\""))
                    .append("\"}");
        }
        return sb.append(']').toString();
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            ObjectNode frame = MAPPER.createObjectNode();
            frame.put("type", "shutdown");
            send(frame);
        } catch (IOException ignored) {
            // best effort
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // best effort
        }
        try {
            server.close();
        } catch (IOException ignored) {
            // best effort
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        failPending("executor stopped");
    }

    /** @return the child process (for diagnostics and tests). */
    public Process process() {
        return process;
    }

    /** @return the executor id reported in the hello frame. */
    public String executorId() {
        return executorId;
    }

    /** @return the binary path used for this executor. */
    public Path binaryPath() {
        return binary;
    }
}
