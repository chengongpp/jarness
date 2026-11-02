//! Jarness local executor.
//!
//! The executor is a small, long-lived process that connects back to the daemon over a JSON-lines
//! protocol and performs filesystem, process, and (later) network work. The daemon is the listener;
//! this process is the client. Every call carries a capability view and an HMAC token so the
//! executor never has to trust ambient JVM state.

use std::collections::HashMap;
use std::fs::File;
use std::io::{BufReader, Read, Write};
use std::net::TcpStream;
use std::path::{Component, Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use cap_std::ambient_authority;
use cap_std::fs::Dir;
use glob::Pattern;
use hmac::{Hmac, Mac};
use rustls::pki_types::ServerName;
use rustls::{ClientConfig, ClientConnection, RootCertStore, StreamOwned};
use serde_json::{json, Value};
use sha2::Sha256;

type HmacSha256 = Hmac<Sha256>;

const PROTOCOL: u64 = 1;
const TOOLS: &[&str] = &[
    "fs.read",
    "fs.write",
    "fs.edit",
    "fs.delete",
    "fs.glob",
    "fs.grep",
    "exec.run",
    "net.fetch",
];
const MAX_READ_LIMIT: usize = 2000;
const MAX_GREP_MATCHES: usize = 250;
const MAX_GLOB_RESULTS: usize = 100;
const MAX_FILE_BYTES: u64 = 2 * 1024 * 1024;
const SNIFF_BYTES: usize = 8192;
const MAX_EXEC_CHARS: usize = 60_000;

#[derive(Clone, Debug)]
struct Cap {
    kind: String,
    resource: String,
}

struct Shared {
    outbox: Arc<Mutex<Vec<Vec<u8>>>>,
    token: String,
    cancels: Arc<Mutex<HashMap<String, Arc<AtomicBool>>>>,
    cache: Arc<Mutex<HashMap<String, (Instant, Value)>>>,
}

/// The executor's connection, plaintext for local callbacks or TLS for remote ones.
enum Transport {
    Plain(TcpStream),
    Tls(StreamOwned<ClientConnection, TcpStream>),
}

impl Read for Transport {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        match self {
            Transport::Plain(stream) => stream.read(buf),
            Transport::Tls(stream) => stream.read(buf),
        }
    }
}

impl Write for Transport {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        match self {
            Transport::Plain(stream) => stream.write(buf),
            Transport::Tls(stream) => stream.write(buf),
        }
    }

    fn flush(&mut self) -> std::io::Result<()> {
        match self {
            Transport::Plain(stream) => stream.flush(),
            Transport::Tls(stream) => stream.flush(),
        }
    }
}

fn main() {
    // rustls 0.23 requires an explicit provider when more than one is compiled in.
    let _ = rustls::crypto::ring::default_provider().install_default();
    let mut connect_to: Option<String> = None;
    let mut tls = false;
    let mut ca: Option<String> = None;
    let mut server_name: Option<String> = None;
    let args: Vec<String> = std::env::args().collect();
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--connect" => {
                i += 1;
                connect_to = args.get(i).cloned();
            }
            "--tls" => tls = true,
            "--ca" => {
                i += 1;
                ca = args.get(i).cloned();
            }
            "--server-name" => {
                i += 1;
                server_name = args.get(i).cloned();
            }
            "--version" | "-V" => {
                println!("jarness-executor {}", env!("CARGO_PKG_VERSION"));
                return;
            }
            other => {
                eprintln!("unknown argument: {other}");
                std::process::exit(2);
            }
        }
        i += 1;
    }
    let addr = match connect_to {
        Some(value) => value,
        None => {
            eprintln!("usage: jarness-executor --connect host:port [--tls --ca cert.pem] (token in JARNESS_EXECUTOR_TOKEN)");
            std::process::exit(2);
        }
    };
    let token = std::env::var("JARNESS_EXECUTOR_TOKEN").unwrap_or_default();
    if token.is_empty() {
        eprintln!("JARNESS_EXECUTOR_TOKEN is required");
        std::process::exit(2);
    }

    let tcp = connect_with_retry(&addr);
    let mut transport = if tls {
        let ca = match ca {
            Some(path) => path,
            None => {
                eprintln!("--tls requires --ca <pem>");
                std::process::exit(2);
            }
        };
        let name = server_name.unwrap_or_else(|| {
            addr.rsplit_once(':')
                .map(|(host, _)| host.to_string())
                .unwrap_or_else(|| "localhost".to_string())
        });
        tls_transport(tcp, &ca, &name)
    } else {
        Transport::Plain(tcp)
    };
    let timeout = Some(Duration::from_millis(20));
    match &mut transport {
        Transport::Plain(stream) => {
            stream.set_read_timeout(timeout).ok();
        }
        Transport::Tls(stream) => {
            stream.sock.set_read_timeout(timeout).ok();
        }
    }

    let shared = Arc::new(Shared {
        outbox: Arc::new(Mutex::new(Vec::new())),
        token,
        cancels: Arc::new(Mutex::new(HashMap::new())),
        cache: Arc::new(Mutex::new(HashMap::new())),
    });
    let hello = json!({
        "type": "hello",
        "protocol": PROTOCOL,
        "executorId": format!("{}-{}", hostname(), std::process::id()),
        "token": shared.token,
        "platform": { "os": std::env::consts::OS, "arch": std::env::consts::ARCH },
        "tools": TOOLS,
    });
    send(&shared, &hello).expect("queue hello");
    eprintln!(
        "jarness-executor connected to {addr}{}",
        if tls { " (TLS)" } else { "" }
    );

    let mut buffer: Vec<u8> = Vec::new();
    let mut scratch = [0u8; 8192];
    loop {
        drain(&shared, &mut transport);
        match transport.read(&mut scratch) {
            Ok(0) => break,
            Ok(n) => {
                buffer.extend_from_slice(&scratch[..n]);
                while let Some(pos) = buffer.iter().position(|byte| *byte == b'\n') {
                    let line: Vec<u8> = buffer.drain(..=pos).collect();
                    let text = String::from_utf8_lossy(&line[..line.len().saturating_sub(1)]);
                    let frame: Value = match serde_json::from_str(text.trim()) {
                        Ok(value) => value,
                        Err(e) => {
                            eprintln!("bad frame: {e}");
                            continue;
                        }
                    };
                    match frame.get("type").and_then(Value::as_str).unwrap_or("") {
                        "call" => {
                            let shared = Arc::clone(&shared);
                            thread::spawn(move || handle_call(&shared, frame));
                        }
                        "cancel" => {
                            let call_id = frame.get("callId").and_then(Value::as_str).unwrap_or("");
                            if let Some(flag) = shared.cancels.lock().unwrap().get(call_id) {
                                flag.store(true, Ordering::SeqCst);
                            }
                        }
                        "hello_ack" => {}
                        "ping" => {
                            let _ = send(&shared, &json!({"type": "pong"}));
                        }
                        "shutdown" => return,
                        _ => {}
                    }
                }
            }
            Err(e)
                if e.kind() == std::io::ErrorKind::WouldBlock
                    || e.kind() == std::io::ErrorKind::TimedOut => {}
            Err(e) => {
                eprintln!("read error: {e}");
                break;
            }
        }
    }
    eprintln!("jarness-executor exiting");
}

fn drain(shared: &Shared, transport: &mut Transport) {
    let frames: Vec<Vec<u8>> = {
        let mut outbox = shared.outbox.lock().unwrap();
        std::mem::take(&mut *outbox)
    };
    for frame in frames {
        if let Err(e) = transport.write_all(&frame) {
            eprintln!("write error: {e}");
            break;
        }
    }
    let _ = transport.flush();
}

fn tls_transport(tcp: TcpStream, ca_path: &str, server_name: &str) -> Transport {
    let mut roots = RootCertStore::empty();
    let mut reader = BufReader::new(File::open(ca_path).unwrap_or_else(|e| {
        eprintln!("could not open CA {ca_path}: {e}");
        std::process::exit(1);
    }));
    for cert in rustls_pemfile::certs(&mut reader) {
        match cert {
            Ok(cert) => {
                if let Err(e) = roots.add(cert) {
                    eprintln!("invalid CA certificate: {e}");
                    std::process::exit(1);
                }
            }
            Err(e) => {
                eprintln!("could not parse CA: {e}");
                std::process::exit(1);
            }
        }
    }
    let config = ClientConfig::builder()
        .with_root_certificates(roots)
        .with_no_client_auth();
    let name = ServerName::try_from(server_name.to_string()).unwrap_or_else(|_| {
        eprintln!("invalid TLS server name: {server_name}");
        std::process::exit(1);
    });
    let connection = ClientConnection::new(Arc::new(config), name).unwrap_or_else(|e| {
        eprintln!("could not start TLS: {e}");
        std::process::exit(1);
    });
    let mut stream = StreamOwned::new(connection, tcp);
    stream.sock.set_read_timeout(None).ok();
    while stream.conn.is_handshaking() {
        if let Err(e) = stream.conn.complete_io(&mut stream.sock) {
            eprintln!("TLS handshake failed: {e}");
            std::process::exit(1);
        }
    }
    Transport::Tls(stream)
}

fn hostname() -> String {
    std::env::var("HOSTNAME").unwrap_or_else(|_| "localhost".to_string())
}

fn connect_with_retry(addr: &str) -> TcpStream {
    let mut last = None;
    for _ in 0..40 {
        match TcpStream::connect(addr) {
            Ok(stream) => {
                stream.set_nodelay(true).ok();
                return stream;
            }
            Err(e) => {
                last = Some(e);
                thread::sleep(Duration::from_millis(50));
            }
        }
    }
    eprintln!(
        "could not connect to {addr}: {}",
        last.map(|e| e.to_string()).unwrap_or_default()
    );
    std::process::exit(1);
}

fn send(shared: &Shared, frame: &Value) -> std::io::Result<()> {
    let mut bytes = serde_json::to_vec(frame)?;
    bytes.push(b'\n');
    shared.outbox.lock().unwrap().push(bytes);
    Ok(())
}

fn handle_call(shared: &Shared, frame: Value) {
    let call_id = frame
        .get("callId")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string();
    let tool = frame
        .get("tool")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string();
    let args_json = frame
        .get("argsJson")
        .and_then(Value::as_str)
        .unwrap_or("{}")
        .to_string();
    let deadline_ms = frame.get("deadlineMs").and_then(Value::as_u64).unwrap_or(0);
    let workspace = frame
        .get("workspace")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string();
    let sandbox = frame
        .get("sandbox")
        .and_then(Value::as_str)
        .unwrap_or("workspace-write")
        .to_string();
    let namespace = frame
        .get("namespace")
        .and_then(Value::as_str)
        .unwrap_or("default")
        .to_string();
    let caps_json = frame
        .get("capsJson")
        .and_then(Value::as_str)
        .unwrap_or("[]")
        .to_string();
    let cap_token = frame
        .get("capToken")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string();
    let idempotency = frame
        .get("idempotencyKey")
        .and_then(Value::as_str)
        .unwrap_or(&call_id)
        .to_string();
    let started = Instant::now();
    let cancel = Arc::new(AtomicBool::new(false));
    shared
        .cancels
        .lock()
        .unwrap()
        .insert(call_id.clone(), Arc::clone(&cancel));
    if cancel.load(Ordering::SeqCst) {
        let _ = emit_result(
            shared,
            &call_id,
            false,
            "",
            "cancelled",
            -1,
            started.elapsed().as_millis() as u64,
        );
        shared.cancels.lock().unwrap().remove(&call_id);
        return;
    }

    let canonical = serde_json::to_string(&json!([
        &call_id,
        &tool,
        &args_json,
        deadline_ms,
        &caps_json,
        &workspace,
        &sandbox,
        &namespace,
        &idempotency,
    ]))
    .unwrap_or_default();
    if !verify_hmac(&shared.token, &canonical, &cap_token) {
        let _ = emit_result(
            shared,
            &call_id,
            false,
            "",
            "auth_failed",
            -1,
            started.elapsed().as_millis() as u64,
        );
        shared.cancels.lock().unwrap().remove(&call_id);
        return;
    }
    if deadline_ms > 0 && now_ms() > deadline_ms {
        let _ = emit_result(
            shared,
            &call_id,
            false,
            "",
            "deadline exceeded before start",
            -1,
            0,
        );
        shared.cancels.lock().unwrap().remove(&call_id);
        return;
    }

    // Idempotency: return the cached result for a retried key, if still fresh.
    if let Some((at, cached)) = shared.cache.lock().unwrap().get(&idempotency).cloned() {
        if at.elapsed() < Duration::from_secs(600) {
            let mut frame = cached;
            frame["callId"] = json!(call_id);
            frame["durationMs"] = json!(0);
            let _ = send(shared, &frame);
            shared.cancels.lock().unwrap().remove(&call_id);
            return;
        }
    }

    let args: Value = serde_json::from_str(&args_json).unwrap_or_else(|_| json!({}));
    let caps: Vec<Cap> = serde_json::from_str::<Vec<Value>>(&caps_json)
        .unwrap_or_default()
        .into_iter()
        .filter_map(|value| {
            Some(Cap {
                kind: value.get("kind")?.as_str()?.to_string(),
                resource: value
                    .get("resource")
                    .and_then(Value::as_str)
                    .unwrap_or("*")
                    .to_string(),
            })
        })
        .collect();

    let outcome = if cancel.load(Ordering::SeqCst) {
        Err("cancelled".to_string())
    } else {
        dispatch(&tool, &args, &workspace, &sandbox, &caps, &cancel)
    };
    shared.cancels.lock().unwrap().remove(&call_id);
    let duration = started.elapsed().as_millis() as u64;
    match outcome {
        Ok(result) => {
            let frame = result_frame(&call_id, true, &result.text, "", result.exit_code, duration);
            if let Some(entry) = frame.as_object() {
                shared
                    .cache
                    .lock()
                    .unwrap()
                    .insert(idempotency, (Instant::now(), Value::Object(entry.clone())));
            }
            let _ = send(shared, &frame);
        }
        Err(error) => {
            let _ = emit_result(shared, &call_id, false, "", &error, -1, duration);
        }
    }
}

struct ToolResult {
    text: String,
    exit_code: i64,
}

fn dispatch(
    tool: &str,
    args: &Value,
    workspace: &str,
    sandbox: &str,
    caps: &[Cap],
    cancel: &AtomicBool,
) -> Result<ToolResult, String> {
    match tool {
        "fs.read" => read_file(args, workspace, sandbox, caps).map(|text| ToolResult {
            text,
            exit_code: -1,
        }),
        "fs.write" => write_file(args, workspace, sandbox, caps).map(|text| ToolResult {
            text,
            exit_code: -1,
        }),
        "fs.edit" => edit_file(args, workspace, sandbox, caps).map(|text| ToolResult {
            text,
            exit_code: -1,
        }),
        "fs.delete" => delete_path(args, workspace, sandbox, caps).map(|text| ToolResult {
            text,
            exit_code: -1,
        }),
        "fs.glob" => glob_files(args, workspace, sandbox, caps).map(|text| ToolResult {
            text,
            exit_code: -1,
        }),
        "fs.grep" => grep_files(args, workspace, sandbox, caps).map(|text| ToolResult {
            text,
            exit_code: -1,
        }),
        "exec.run" => run_command(args, workspace, sandbox, caps, cancel),
        "net.fetch" => fetch_url(args, caps).map(|text| ToolResult {
            text,
            exit_code: -1,
        }),
        other => Err(format!("unknown executor tool: {other}")),
    }
}

// ---------------------------------------------------------------- filesystem

fn read_file(args: &Value, workspace: &str, sandbox: &str, caps: &[Cap]) -> Result<String, String> {
    let path = resolve_path(workspace, required_path(args)?);
    let target = fs_target(workspace, sandbox, caps, "FS_READ", &path)?;
    let metadata = target
        .dir
        .metadata(&target.relative)
        .map_err(|e| format!("read failed for {}: {e}", path.display()))?;
    if metadata.is_dir() {
        return Err(format!(
            "path is a directory, not a file: {}",
            path.display()
        ));
    }
    let content = target
        .dir
        .read_to_string(&target.relative)
        .map_err(|e| format!("read failed: {e}"))?;
    let lines: Vec<&str> = content.lines().collect();
    let total = lines.len();
    let offset = args
        .get("offset")
        .and_then(Value::as_u64)
        .unwrap_or(1)
        .max(1) as usize;
    let limit = args
        .get("limit")
        .and_then(Value::as_u64)
        .unwrap_or(MAX_READ_LIMIT as u64) as usize;
    let limit = limit.min(MAX_READ_LIMIT).max(1);
    let from = (offset - 1).min(total);
    let to = (from + limit).min(total);
    let mut out = String::new();
    out.push_str(&path.display().to_string());
    out.push('\n');
    for i in from..to {
        out.push_str(&format!("{:>6}: {}\n", i + 1, lines[i]));
    }
    if to >= total {
        out.push_str(&format!("(End of file - total {total} lines)"));
    } else {
        out.push_str(&format!(
            "(truncated - showing lines {}-{} of {}; use offset to continue)",
            from + 1,
            to,
            total
        ));
    }
    Ok(out)
}

fn write_file(
    args: &Value,
    workspace: &str,
    sandbox: &str,
    caps: &[Cap],
) -> Result<String, String> {
    let path = resolve_path(workspace, required_path(args)?);
    let target = fs_target(workspace, sandbox, caps, "FS_WRITE", &path)?;
    let content = required_str(args, "content")?;
    if let Some(parent) = target.relative.parent() {
        target
            .dir
            .create_dir_all(parent)
            .map_err(|e| format!("write failed: {e}"))?;
    }
    target
        .dir
        .write(&target.relative, &content)
        .map_err(|e| format!("write failed: {e}"))?;
    Ok(format!(
        "wrote {} ({} chars)",
        path.display(),
        content.chars().count()
    ))
}

fn edit_file(args: &Value, workspace: &str, sandbox: &str, caps: &[Cap]) -> Result<String, String> {
    let path = resolve_path(workspace, required_path(args)?);
    let target = fs_target(workspace, sandbox, caps, "FS_WRITE", &path)?;
    let old = required_str(args, "old_string")?;
    let new = args.get("new_string").and_then(Value::as_str).unwrap_or("");
    let all = args
        .get("replace_all")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    let content = target
        .dir
        .read_to_string(&target.relative)
        .map_err(|e| format!("edit failed: {e}"))?;
    let count = content.matches(&old).count();
    if count == 0 {
        return Err(format!("old_string not found in {}", path.display()));
    }
    if !all && count != 1 {
        return Err(format!(
            "old_string appears {count} times in {}; add more context or use replace_all",
            path.display()
        ));
    }
    let replaced = if all {
        content.replace(&old, new)
    } else {
        content.replacen(&old, new, 1)
    };
    target
        .dir
        .write(&target.relative, replaced)
        .map_err(|e| format!("edit failed: {e}"))?;
    Ok(format!(
        "edited {} ({} replacement{})",
        path.display(),
        if all { count } else { 1 },
        if (if all { count } else { 1 }) == 1 {
            ""
        } else {
            "s"
        }
    ))
}

fn delete_path(
    args: &Value,
    workspace: &str,
    sandbox: &str,
    caps: &[Cap],
) -> Result<String, String> {
    let path = resolve_path(workspace, required_path(args)?);
    let target = fs_target(workspace, sandbox, caps, "FS_WRITE", &path)?;
    let metadata = target
        .dir
        .symlink_metadata(&target.relative)
        .map_err(|e| format!("delete failed for {}: {e}", path.display()))?;
    if metadata.is_dir() {
        target
            .dir
            .remove_dir(&target.relative)
            .map_err(|e| format!("delete failed: {e} (directory must be empty)"))?;
    } else {
        target
            .dir
            .remove_file(&target.relative)
            .map_err(|e| format!("delete failed: {e}"))?;
    }
    Ok(format!("deleted {}", path.display()))
}

fn glob_files(
    args: &Value,
    workspace: &str,
    sandbox: &str,
    caps: &[Cap],
) -> Result<String, String> {
    let pattern_text = required_str(args, "pattern")?;
    let pattern = Pattern::new(&pattern_text).map_err(|e| format!("invalid glob pattern: {e}"))?;
    let root = match args.get("path").and_then(Value::as_str) {
        Some(value) if !value.is_empty() => resolve_path(workspace, value.to_string()),
        _ => PathBuf::from(workspace),
    };
    let target = fs_target(workspace, sandbox, caps, "FS_READ", &root)?;
    let search_dir = target.dir.open_dir(&target.relative).map_err(|e| {
        format!(
            "glob root is not a searchable directory {}: {e}",
            root.display()
        )
    })?;
    let basename_only = !pattern_text.contains('/');
    let mut matches: Vec<(PathBuf, cap_std::time::SystemTime)> = Vec::new();
    walk(&search_dir, &mut |relative, dir| {
        let file = root.join(relative);
        if pattern.matches_path(relative)
            || (basename_only
                && file
                    .file_name()
                    .map(|name| pattern.matches(&name.to_string_lossy()))
                    .unwrap_or(false))
        {
            let modified = dir
                .metadata(relative)
                .and_then(|m| m.modified())
                .unwrap_or_else(|_| cap_std::time::SystemTime::from_std(UNIX_EPOCH));
            matches.push((file, modified));
        }
    })
    .map_err(|e| format!("glob failed: {e}"))?;
    matches.sort_by(|a, b| b.1.cmp(&a.1));
    let total = matches.len();
    let shown = total.min(MAX_GLOB_RESULTS);
    let mut out = String::new();
    for (file, _) in matches.iter().take(shown) {
        out.push_str(
            &file
                .strip_prefix(&root)
                .unwrap_or(file)
                .display()
                .to_string(),
        );
        out.push('\n');
    }
    if total > MAX_GLOB_RESULTS {
        out.push_str(&format!(
            "(truncated - showing {} of {} matches, newest first)",
            MAX_GLOB_RESULTS, total
        ));
    } else {
        out.push_str(&format!(
            "({} {})",
            total,
            if total == 1 { "file" } else { "files" }
        ));
    }
    Ok(out)
}

fn grep_files(
    args: &Value,
    workspace: &str,
    sandbox: &str,
    caps: &[Cap],
) -> Result<String, String> {
    let pattern_text = required_str(args, "pattern")?;
    let pattern =
        regex::Regex::new(&pattern_text).map_err(|e| format!("invalid regular expression: {e}"))?;
    let root = match args.get("path").and_then(Value::as_str) {
        Some(value) if !value.is_empty() => resolve_path(workspace, value.to_string()),
        _ => PathBuf::from(workspace),
    };
    let target = fs_target(workspace, sandbox, caps, "FS_READ", &root)?;
    let metadata = target
        .dir
        .metadata(&target.relative)
        .map_err(|e| format!("grep path does not exist {}: {e}", root.display()))?;
    let include = match args.get("include").and_then(Value::as_str) {
        Some(value) if !value.is_empty() => {
            Some(Pattern::new(value).map_err(|e| format!("invalid include glob: {e}"))?)
        }
        _ => None,
    };
    let (content_dir, files) = if metadata.is_file() {
        let parent = target.relative.parent().unwrap_or_else(|| Path::new("."));
        let dir = target
            .dir
            .open_dir(parent)
            .map_err(|e| format!("grep failed: {e}"))?;
        (
            dir,
            vec![target
                .relative
                .file_name()
                .map(PathBuf::from)
                .unwrap_or_default()],
        )
    } else {
        let dir = target
            .dir
            .open_dir(&target.relative)
            .map_err(|e| format!("grep failed: {e}"))?;
        let mut files = Vec::new();
        walk(&dir, &mut |file, _| files.push(file.to_path_buf()))
            .map_err(|e| format!("grep failed: {e}"))?;
        (dir, files)
    };
    let mut out = String::new();
    let mut matches = 0usize;
    let mut files_with_matches = 0usize;
    let mut truncated = false;
    for file in files {
        if let Some(include) = &include {
            let name = file
                .file_name()
                .map(|n| n.to_string_lossy().to_string())
                .unwrap_or_default();
            if !include.matches(&name) {
                continue;
            }
        }
        if content_dir.metadata(&file).map(|m| m.len()).unwrap_or(0) > MAX_FILE_BYTES
            || looks_binary(&content_dir, &file)
        {
            continue;
        }
        let text = match content_dir.read_to_string(&file) {
            Ok(value) => value,
            Err(_) => continue,
        };
        let relative = file.display().to_string();
        let mut file_matched = false;
        for (index, line) in text.lines().enumerate() {
            if !pattern.is_match(line) {
                continue;
            }
            if matches >= MAX_GREP_MATCHES {
                truncated = true;
                break;
            }
            if !file_matched {
                files_with_matches += 1;
                file_matched = true;
            }
            out.push_str(&format!("{}:{}: {}\n", relative, index + 1, line));
            matches += 1;
        }
        if truncated {
            break;
        }
    }
    if matches == 0 {
        return Ok("no matches".to_string());
    }
    if truncated {
        out.push_str(&format!("(truncated at {} matches)", MAX_GREP_MATCHES));
    } else {
        out.push_str(&format!(
            "({} {} in {} {})",
            matches,
            if matches == 1 { "match" } else { "matches" },
            files_with_matches,
            if files_with_matches == 1 {
                "file"
            } else {
                "files"
            }
        ));
    }
    Ok(out)
}

fn looks_binary(dir: &Dir, path: &Path) -> bool {
    let mut buffer = [0u8; SNIFF_BYTES];
    match dir.open(path) {
        Ok(mut file) => match file.read(&mut buffer) {
            Ok(read) => buffer[..read].contains(&0),
            Err(_) => true,
        },
        Err(_) => true,
    }
}

fn walk(root: &Dir, visit: &mut dyn FnMut(&Path, &Dir)) -> std::io::Result<()> {
    let mut stack = vec![PathBuf::new()];
    while let Some(dir_path) = stack.pop() {
        let read_path = if dir_path.as_os_str().is_empty() {
            Path::new(".")
        } else {
            dir_path.as_path()
        };
        for entry in root.read_dir(read_path)? {
            let entry = entry?;
            let path = dir_path.join(entry.file_name());
            let file_type = entry.file_type()?;
            if file_type.is_dir() {
                stack.push(path);
            } else if file_type.is_file() {
                // Symlinks are deliberately not followed during recursive search. Files opened
                // later still use the same beneath-root directory handle.
                visit(&path, root);
            }
        }
    }
    Ok(())
}

// ---------------------------------------------------------------- process

fn run_command(
    args: &Value,
    workspace: &str,
    sandbox: &str,
    caps: &[Cap],
    cancel: &AtomicBool,
) -> Result<ToolResult, String> {
    require_kind(caps, "EXEC")?;
    if sandbox == "read-only" {
        return Err("sandbox mode is read-only: exec is not allowed".to_string());
    }
    let command = required_str(args, "command")?;
    let cwd = match args.get("workdir").and_then(Value::as_str) {
        Some(value) if !value.is_empty() => resolve_path(workspace, value.to_string()),
        _ => PathBuf::from(workspace),
    };
    let cwd = if sandbox == "workspace-write" {
        let root = std::fs::canonicalize(workspace)
            .map_err(|e| format!("cannot resolve workspace {workspace}: {e}"))?;
        let actual = std::fs::canonicalize(&cwd)
            .map_err(|e| format!("cannot resolve working directory {}: {e}", cwd.display()))?;
        if !actual.starts_with(&root) {
            return Err(format!(
                "sandbox mode is workspace-write: {} is outside the workspace",
                actual.display()
            ));
        }
        actual
    } else {
        cwd
    };
    if !cwd.is_dir() {
        return Err(format!(
            "working directory does not exist: {}",
            cwd.display()
        ));
    }
    let timeout_ms = args
        .get("timeoutMs")
        .and_then(Value::as_u64)
        .unwrap_or(120_000)
        .min(600_000);
    let mut child = Command::new("bash")
        .arg("-c")
        .arg(command)
        .current_dir(&cwd)
        .env("JARNESS_WORKSPACE", workspace)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| format!("failed to start bash: {e}"))?;
    let stdout = child.stdout.take();
    let stderr = child.stderr.take();
    let out_handle = stdout.map(|mut out| {
        thread::spawn(move || {
            let mut bytes = Vec::new();
            let _ = out.read_to_end(&mut bytes);
            bytes
        })
    });
    let err_handle = stderr.map(|mut err| {
        thread::spawn(move || {
            let mut bytes = Vec::new();
            let _ = err.read_to_end(&mut bytes);
            bytes
        })
    });
    let start = Instant::now();
    let mut timed_out = false;
    loop {
        if cancel.load(Ordering::SeqCst) {
            let _ = child.kill();
            let _ = child.wait();
            return Err("cancelled".to_string());
        }
        if start.elapsed() > Duration::from_millis(timeout_ms) {
            let _ = child.kill();
            let _ = child.wait();
            timed_out = true;
            break;
        }
        match child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) => thread::sleep(Duration::from_millis(20)),
            Err(e) => return Err(format!("failed to wait for command: {e}")),
        }
    }
    let status = child
        .wait()
        .map_err(|e| format!("failed to wait for command: {e}"))?;
    let stdout = out_handle
        .map(|h| h.join().unwrap_or_default())
        .unwrap_or_default();
    let stderr = err_handle
        .map(|h| h.join().unwrap_or_default())
        .unwrap_or_default();
    let mut text = String::from_utf8_lossy(&stdout).to_string();
    let stderr_text = String::from_utf8_lossy(&stderr).to_string();
    if !stderr_text.trim().is_empty() {
        text.push_str("\n[stderr]\n");
        text.push_str(&stderr_text);
    }
    if timed_out {
        text.push_str(&format!("\n[timeout after {timeout_ms} ms]"));
    }
    text.push_str(&format!("\n[exit code: {}]", status.code().unwrap_or(-1)));
    if text.chars().count() > MAX_EXEC_CHARS {
        let chars: Vec<char> = text.chars().collect();
        let tail: String = chars[chars.len() - MAX_EXEC_CHARS..].iter().collect();
        text = format!("[truncated to the last {MAX_EXEC_CHARS} chars]\n{tail}");
    }
    Ok(ToolResult {
        text,
        exit_code: status.code().map(|code| code as i64).unwrap_or(-1),
    })
}

// ---------------------------------------------------------------- network

fn fetch_url(args: &Value, caps: &[Cap]) -> Result<String, String> {
    let initial = checked_url(required_str(args, "url")?, caps)?;
    let agent = ureq::AgentBuilder::new().redirects(0).build();
    let body = fetch_body(&agent, initial, caps, 0)?;
    let mut text = html_to_text(&body);
    if text.trim().is_empty() {
        return Ok("The following is external, untrusted content:\n(empty page)".to_string());
    }
    if text.chars().count() > 20_000 {
        text = text.chars().take(20_000).collect::<String>() + "\n(truncated at 20000 chars)";
    }
    Ok(format!(
        "The following is external, untrusted content:\n{text}"
    ))
}

fn checked_url(raw: String, caps: &[Cap]) -> Result<url::Url, String> {
    let url = url::Url::parse(&raw).map_err(|e| format!("invalid URL {raw}: {e}"))?;
    if url.scheme() != "http" && url.scheme() != "https" {
        return Err(format!("only http(s) URLs can be fetched: {url}"));
    }
    let host = url.host_str().unwrap_or("");
    require_net(caps, host)?;
    Ok(url)
}

fn fetch_body(
    agent: &ureq::Agent,
    url: url::Url,
    caps: &[Cap],
    redirects: u32,
) -> Result<String, String> {
    let response = agent
        .get(url.as_str())
        .set(
            "User-Agent",
            "Mozilla/5.0 (X11; Linux x86_64) JarnessExecutor",
        )
        .timeout(Duration::from_secs(30))
        .call();
    let response = match response {
        Ok(response) => response,
        Err(ureq::Error::Status(code, _)) => return Err(format!("HTTP {code} fetching {url}")),
        Err(ureq::Error::Transport(e)) => return Err(format!("web_fetch failed: {e}")),
    };
    if matches!(response.status(), 301 | 302 | 303 | 307 | 308) {
        if redirects >= 5 {
            return Err(format!("too many redirects fetching {url}"));
        }
        let location = response
            .header("Location")
            .ok_or_else(|| format!("redirect from {url} has no Location header"))?;
        let next = url
            .join(location)
            .map_err(|e| format!("invalid redirect from {url}: {e}"))?;
        let next = checked_url(next.to_string(), caps)?;
        return fetch_body(agent, next, caps, redirects + 1);
    }
    let mut body = String::new();
    response
        .into_reader()
        .take(MAX_FILE_BYTES + 1)
        .read_to_string(&mut body)
        .map_err(|e| format!("web_fetch failed: {e}"))?;
    if body.len() > MAX_FILE_BYTES as usize {
        return Err(format!(
            "web_fetch response exceeds {} bytes",
            MAX_FILE_BYTES
        ));
    }
    Ok(body)
}

fn html_to_text(html: &str) -> String {
    let mut text = html.to_string();
    for pattern in [
        r"(?is)<!--.*?-->",
        r"(?is)<script[^>]*>.*?</script>",
        r"(?is)<style[^>]*>.*?</style>",
        r"(?is)<[^>]+>",
    ] {
        if let Ok(regex) = regex::Regex::new(pattern) {
            text = regex.replace_all(&text, " ").to_string();
        }
    }
    for (entity, value) in [
        ("&amp;", "&"),
        ("&lt;", "<"),
        ("&gt;", ">"),
        ("&quot;", "\""),
        ("&#39;", "'"),
        ("&nbsp;", " "),
    ] {
        text = text.replace(entity, value);
    }
    let mut out = String::with_capacity(text.len());
    let mut last_space = false;
    for ch in text.chars() {
        if ch.is_whitespace() {
            if !last_space {
                out.push(' ');
                last_space = true;
            }
        } else {
            out.push(ch);
            last_space = false;
        }
    }
    out.trim().to_string()
}

fn require_net(caps: &[Cap], host: &str) -> Result<(), String> {
    let allowed = caps.iter().any(|cap| {
        if cap.kind != "NET" {
            return false;
        }
        if cap.resource == "*" {
            return true;
        }
        if let Some(suffix) = cap.resource.strip_prefix("*.") {
            return host == suffix || host.ends_with(&format!(".{suffix}"));
        }
        host == cap.resource
    });
    if allowed {
        Ok(())
    } else {
        Err(format!("capability denied: NET does not grant host {host}"))
    }
}

// ---------------------------------------------------------------- capabilities

/// A path resolved relative to an authorized directory handle. All subsequent filesystem
/// operations use `dir`, so symlinks and `..` cannot escape the selected capability root.
struct FsTarget {
    dir: Dir,
    relative: PathBuf,
}

fn fs_target(
    workspace: &str,
    sandbox: &str,
    caps: &[Cap],
    kind: &str,
    path: &Path,
) -> Result<FsTarget, String> {
    if kind == "FS_WRITE" {
        require_sandbox_write(sandbox, workspace, path)?;
    }
    for cap in caps.iter().filter(|cap| cap.kind == kind) {
        let (root, relative) = if cap.resource == "*" {
            // An unbounded write capability is still constrained by workspace-write. Use the cwd
            // itself as the directory capability rather than checking a path and then reopening it.
            let root = if kind == "FS_WRITE" && sandbox == "workspace-write" {
                normalize(Path::new(workspace))
            } else {
                PathBuf::from("/")
            };
            match path.strip_prefix(&root) {
                Ok(relative) => (root, relative.to_path_buf()),
                Err(_) => continue,
            }
        } else if let Some(prefix) = cap.resource.strip_suffix("/*") {
            let root = normalize(Path::new(prefix));
            if !path.starts_with(&root) {
                continue;
            }
            let relative = path.strip_prefix(&root).unwrap().to_path_buf();
            (root, relative)
        } else {
            let exact = normalize(Path::new(&cap.resource));
            if path != exact {
                continue;
            }
            let root = exact
                .parent()
                .unwrap_or_else(|| Path::new("/"))
                .to_path_buf();
            let relative = exact.file_name().map(PathBuf::from).unwrap_or_default();
            (root, relative)
        };
        let relative = if relative.as_os_str().is_empty() {
            PathBuf::from(".")
        } else {
            relative
        };
        if kind == "FS_WRITE" {
            std::fs::create_dir_all(&root)
                .map_err(|e| format!("cannot create capability root {}: {e}", root.display()))?;
        }
        let dir = Dir::open_ambient_dir(&root, ambient_authority())
            .map_err(|e| format!("cannot open capability root {}: {e}", root.display()))?;
        return Ok(FsTarget { dir, relative });
    }
    Err(format!(
        "capability denied: {kind} does not grant {kind} on {}",
        path.display()
    ))
}

fn require_kind(caps: &[Cap], kind: &str) -> Result<(), String> {
    if caps.iter().any(|cap| cap.kind == kind) {
        Ok(())
    } else {
        Err(format!("capability denied: missing {}", kind))
    }
}

fn require_sandbox_write(sandbox: &str, workspace: &str, path: &Path) -> Result<(), String> {
    match sandbox {
        "read-only" => Err("sandbox mode is read-only: writes are not allowed".to_string()),
        "workspace-write" => {
            if is_within(path, Path::new(workspace)) {
                Ok(())
            } else {
                Err(format!(
                    "sandbox mode is workspace-write: {} is outside the workspace {}",
                    path.display(),
                    workspace
                ))
            }
        }
        _ => Ok(()),
    }
}

fn is_within(path: &Path, root: &Path) -> bool {
    let path = normalize(path);
    let root = normalize(root);
    path == root || path.starts_with(&root)
}

// ---------------------------------------------------------------- helpers

fn resolve_path(workspace: &str, value: String) -> PathBuf {
    let path = PathBuf::from(value);
    let path = if path.is_absolute() {
        path
    } else if workspace.is_empty() {
        path
    } else {
        Path::new(workspace).join(path)
    };
    normalize(&path)
}

fn normalize(path: &Path) -> PathBuf {
    let mut out = PathBuf::new();
    for component in path.components() {
        match component {
            Component::RootDir => out.push("/"),
            Component::CurDir => {}
            Component::ParentDir => {
                out.pop();
            }
            Component::Normal(part) => out.push(part),
            Component::Prefix(_) => {}
        }
    }
    out
}

fn required_path(args: &Value) -> Result<String, String> {
    required_str(args, "path").or_else(|_| required_str(args, "file_path"))
}

fn required_str(args: &Value, key: &str) -> Result<String, String> {
    match args.get(key).and_then(Value::as_str) {
        Some(value) if !value.is_empty() => Ok(value.to_string()),
        _ => Err(format!("missing required parameter \"{key}\"")),
    }
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn result_frame(
    call_id: &str,
    ok: bool,
    text: &str,
    error: &str,
    exit_code: i64,
    duration_ms: u64,
) -> Value {
    json!({
        "type": "result",
        "callId": call_id,
        "ok": ok,
        "text": text,
        "error": error,
        "exitCode": exit_code,
        "durationMs": duration_ms,
    })
}

fn emit_result(
    shared: &Shared,
    call_id: &str,
    ok: bool,
    text: &str,
    error: &str,
    exit_code: i64,
    duration_ms: u64,
) -> std::io::Result<()> {
    send(
        shared,
        &result_frame(call_id, ok, text, error, exit_code, duration_ms),
    )
}

fn verify_hmac(token: &str, canonical: &str, given: &str) -> bool {
    let mut mac = match HmacSha256::new_from_slice(token.as_bytes()) {
        Ok(mac) => mac,
        Err(_) => return false,
    };
    mac.update(canonical.as_bytes());
    let expected = mac.finalize().into_bytes();
    let given_bytes = match hex_decode(given) {
        Some(bytes) => bytes,
        None => return false,
    };
    if expected.len() != given_bytes.len() {
        return false;
    }
    let mut diff = 0u8;
    for (a, b) in expected.iter().zip(given_bytes.iter()) {
        diff |= a ^ b;
    }
    diff == 0
}

fn hex_decode(value: &str) -> Option<Vec<u8>> {
    if value.len() % 2 != 0 {
        return None;
    }
    let mut out = Vec::with_capacity(value.len() / 2);
    let bytes = value.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        let high = hex_digit(bytes[i])?;
        let low = hex_digit(bytes[i + 1])?;
        out.push((high << 4) | low);
        i += 2;
    }
    Some(out)
}

fn hex_digit(byte: u8) -> Option<u8> {
    match byte {
        b'0'..=b'9' => Some(byte - b'0'),
        b'a'..=b'f' => Some(byte - b'a' + 10),
        b'A'..=b'F' => Some(byte - b'A' + 10),
        _ => None,
    }
}
