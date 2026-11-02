# Jarness

Jarness 是一个基于 Java 21 LTS 的企业级多 Agent 运行时。
目标架构见 [docs/harness-design.md](docs/harness-design.md)（v1.3），架构草案见 `draft/`；
两者之间的差异与迁移计划见 [docs/design-gap-analysis.md](docs/design-gap-analysis.md)。
设计上参考 deepseek-harness 的轻量可组装风格与 NeoForge/FancyModLoader 的模组体系。

## 模块

| 模块 | 说明 |
| --- | --- |
| `jarness-api` | 稳定扩展面：Mod、Instrument、Hook、Tool、Provider、Session、Agent、Supervisor、Namespace、Capability、Preset 等抽象 |
| `jarness-core` | 运行时：ModLoader（单例）、监督树 Supervisor（单例）、namespace 注册表、agent loop、SQLite 会话存储、三种 provider 适配、内建工具与模板 |
| `jarness-cli` | `jarness` 命令行（shade 成单个 `jarness.jar`） |
| `jarness-mod-tui` | TUI 系统 mod（由 `tui` preset 启用） |
| `jarness-sample-mod` | 示例外置 mod，用于测试 mods 目录发现与动态加载 |

## 快速开始

```bash
make            # 打包到 dist/（jarness.jar + mods/*.jar + executor/jarness-executor）
./jn            # 拉起交互式 TUI（tui preset）
./jn -c "你好"  # 一次性运行（等价于 jarness run）
./jn sessions   # 列镜像（turns/budget/checkpoint/deps）
./jn super      # 和平台 super agent（pid 0）对话
./jn mods       # 透传其它 CLI 子命令（mods/presets/templates/providers/namespaces/sessions/super/tui/run）
```

也可以直接用 jar：`java -jar dist/jarness.jar run "你好"`。

base dir 默认是 `$HOME/.local/share/jarness`：`mods/` 放外置 mod jar，`config/` 放配置
（`jarness.toml`、`providers.toml`、以及以 mod id 命名的 per-mod 配置目录），`presets/` 放预设，
`data/jarness.db` 是镜像库（SQLite，turn 事务 + checkpoint，也存 shm cell）。工作目录与 base dir
分离，但不再按 agent 创建：同一 namespace 的所有 agent（包括 subagent）共享
`<base>/workspaces/<namespace-id>`，可用 `[defaults] cwd` / `[namespaces.<id>] cwd` 覆盖。

镜像可以恢复：

```bash
./jn run --session <session-id> "继续上次的活"       # 依赖匹配时直接 resume
./jn run --session <session-id> --force-resume "..." # 依赖缺失时降级加载
```

## 执行器（Rust executor）

默认 `[execution] mode = "relay"`：`read`/`write`/`edit`/`delete`/`glob`/`grep`/`bash` 不再在 daemon
JVM 里执行，而是由 `dist/executor/jarness-executor`（Rust）执行。daemon 监听 loopback，executor 回连；
每次调用带能力视图和 HMAC token。`make` 会构建它，也可单独 `make executor`；找不到时可显式退回
`[execution] mode = "local"`（RELAY 缺 provider 时不会静默回退）。

```toml
[execution]
mode = "relay"          # 或 "local"

[approval]
policy = "allow"        # allow | ask | never
allow = ["write"]       # policy=never 时的白名单
timeout_seconds = 300   # policy=ask 的等待上限

# 可选：接受远端 executor 回连
# remote_enabled = true
# remote_host = "127.0.0.1"
# remote_port = 0
# remote_token = "change-me"
# remote_tls = true                     # 需要 PKCS12 keystore
# remote_keystore = "/etc/jarness/server.p12"
# remote_keystore_password = "changeit"
```

远端 executor 用 `--connect host:port --tls --ca server.pem` 启动，token 放在
`JARNESS_EXECUTOR_TOKEN`。`remote_tls=true` 但 keystore 缺失时 remote listener 不会启动（不会退化成明文）。

## Agent、namespace 与能力

每个 agent 都是监督树中的一个节点：有 pid、parent、唯一 namespace、能力集合与预算。
pid 0 是真实的 **super agent**（平台能力：`platform` / `namespace`），首次 root spawn 或
`jarness super` 时惰性创建；root agent 挂在它下面。`subagent` / `agent_spawn` 创建调用者的子节点，
能力按 `requested ∩ parent ∩ namespace.defaults` 逐层衰减，父节点退出时子树被 kill。
super agent 有 `ns_list/ns_create/ns_remove/daemon_status/agent_tree/agent_kill/agent_signal` 平台工具。

namespace 可从 `config/jarness.toml` 声明；`cwd` 是同一 namespace 内所有 agent（含 subagent）的
共享工作目录：

```toml
[supervisor]
max_depth = 16       # 0 = 不限制
max_children = 32    # 0 = 不限制

# 默认 namespace 由 [defaults] 构造；这里只声明额外的 namespace。
[defaults]
cwd = "workspaces/default"   # 可选；默认 <base>/workspaces/default

[namespaces.review]
template = "minimal"
cwd = "workspaces/review"    # 可选；默认 <base>/workspaces/review
model = "GB10/deepseek-v4.1-flash"
store = "jdbc:sqlite:/var/lib/jarness/review.db"   # 可选：独立镜像库
capabilities = ["fs.read", "fs.write", "model"]     # 可选：缺省为本地全套（不含 platform）
```

`./jn namespaces` 或 `java -jar dist/jarness.jar namespaces` 会列出各 namespace 的 cwd、能力上限、
模型档位与默认模板。

## 协作、异步与观测

- **mailbox**：每个 agent 有有界 mailbox；`Supervisor.deliver` 走 mailbox（满即拒绝），异步工具完成后
  结果作为新 turn 回来。
- **IPC/shm**：`runtime.ipc()` 提供 namespace 内的 **Shm**（Shared Memory；版本化 cell / CAS /
  watch / 锁，内存读 + SQLite 写穿，daemon 重启可恢复）和 channel（有界/rendezvous / select）。
  模型侧工具 `shm` 与 `agent_send` 都是 **JVM 内**执行（`ToolForm.LOCAL`），不会下发 executor：
  `shm` 直接读写共享内存，`agent_send` 直接把 `DATA` 消息投到目标 agent 的 mailbox。
  shm 需要 `ipc` 能力；creator 退出不会清空共享状态，channel 仍随 owner 回收。
- **观测**：`runtime.observation().subscribe(sessionId, consumer)` 订阅 committed 事件；
  `events(sessionId, fromTurn)` 做回放。所有事件都在 turn 的同一事务里写入。
- **重启**：`RestartPolicy.ON_FAILURE/ALWAYS` + `[supervisor] restart_delay_ms/max_restarts`，
  失败后从同一 session/image 重建。
- **循环钩子**：`runtime.loopHooks()` 提供 `beforeTurn/beforeStep/afterStep/afterTurn`；
  `runtime.registry()` 暴露带版本的扩展点快照，每个 turn pin 一份。

## 测试

```bash
make test       # 单元测试
make it         # 联调测试；优先使用已安装的 ~/.local/share/jarness/config/providers.toml
```

## 设计决策

见 [docs/design-decisions.md](docs/design-decisions.md) 与
[docs/design-gap-analysis.md](docs/design-gap-analysis.md)。
