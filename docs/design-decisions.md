# Jarness 设计决策记录

本文档记录实现 `draft/draft_202610141032.md`（v0.1）时，对草案中未明确（unclear/ambiguous）之处所做的设计与选择，以及理由。

> **2026-11-01 更新**：`docs/harness-design.md`（v1.3）已成为目标设计。v0.1 的部分决策被它取代；
> 差异、逐条冲突与迁移计划见 [`docs/design-gap-analysis.md`](design-gap-analysis.md)。下文保留原始记录，
> 被取代处会就地标注 **[v1.3 取代]**。


## 0. 关于"极早期版本"

接手时仓库里只有 `draft/`（架构草案）和 `tmp/`（测试端点），文件系统中也没有找到其它 jarness 代码。
因此把草案 md 本身视为"极早期版本"的全部内容，从零实现。若另有早期代码，请告知，我再做对齐。

## 1. 系统 mod 用 ServiceLoader SPI，而不是 `META-INF/jnmods.toml`

草案要求系统 mod 直接放 CLASSPATH。但 CLI 最终会 shade 成一个 uber jar：若每个系统 mod 都写
`META-INF/jnmods.toml`，资源同名会互相覆盖。因此系统 mod 走标准 Java SPI
（`META-INF/services/ch.predmet.jarness.api.mod.ModProvider`，由 shade 的
`ServicesResourceTransformer` 正确合并），外部 mod（`mods/*.jar`）才走 `jnmods.toml`。
`jnmods.toml` 里 entrypoint 可省略，此时扫描 jar 中唯一的 `@Mod` 实现类（NeoForge 风格）。

## 2. instrument 同时支持编程式和注解式

`ModContext.instruments().register(...)` 是主路径（可在运行时构造）；
`@Instrument` 标注的 mod 方法会被装载器包装成同名 instrument。
应用顺序：priority 升序 → mod 装载序 → 名字字典序，保证确定性而不要求 instrument 之间建依赖图。
每个 instrument 有 `autoEnable`（自动/手动加载策略），preset 用 `modId:name`（或裸名）显式覆盖。

## 3. 没有交互式审批提示，用 SandboxMode 兜底

Jarness 首先是 server runtime / 可嵌入库，不能阻塞在"等一个人按 y"。因此默认不做审批提示，
而是用部署期声明的沙箱模式（`read-only` / `workspace-write`（默认） / `full-access`）约束工具的写权限。
需要审批的部署可以用 `BEFORE_TOOL_CALL` 钩子自行实现（事件可改写、可 skip）。

**[v1.3 取代]** v1.3 的 TOOL-1 要求 `approval policy`（`never` / `ask` / `allow`），`ask` 可异步挂起。
P0 仍不实现审批，但已经把 `PolicySet` 放进 namespace metadata 作占位；`ask` 依赖 P3 的 mailbox，
提前实现只会得到一套用不上的状态机。SandboxMode 保留为 LOCAL 工具的兜底，不再是能力的替代品。

## 4. 可变事件载荷与活注册表是有意为之

草案明确"没有 FP 的无副作用洁癖"。因此 hook 事件（RequestEvent/ToolCallEvent/MessageEvent/ResponseEvent）
是可变对象，钩子就地改写；ToolRegistry/AgentTemplateRegistry/ProviderRegistry 运行时可写——
这正是 Full 模板"运行时手搓 template、动态加 mod"场景的基础。

## 5. base dir 与 namespace cwd 严格分离

base dir（默认 `$HOME/.local/share/jarness`）只放 `mods/`、`config/`、`presets/`、`data/jarness.db`、
`workspaces/`。工具的相对路径一律相对 **namespace cwd** 解析，而不是 base dir。

**[v1.4 取代]** 不再有 per-agent workspace：同一 namespace 的所有 agent（包括 `subagent` /
`agent_spawn` 创建的子 agent）统一使用 `NamespaceMetadata.cwd()`。默认 namespace 的 cwd 是
`<workspace_root>/default`，额外 namespace 是 `<workspace_root>/<id>`，也可以由
`[defaults] cwd` / `[namespaces.<id>] cwd` 显式指定（相对路径相对 base dir）。这样同一 namespace
内的文件副作用天然共享，和 v1.3 把 namespace 视为执行环境的模型一致。旧的 `AgentRequest.workspace`
与 CLI `-w` 已删除；resume 时会把 session 记录的 workspace 重绑到当前 namespace cwd。

## 6. Supervisor 最先起、最后退

`Jarness.start()`：先 `supervisor.start()`（哪怕永远 0 个 agent 它也活过），再 bake mods，
最后发 `RUNTIME_START`。`close()`：先发 `RUNTIME_STOP`，停 supervisor（停所有 agent），
再关 provider/session store/jobs，最后才 `modLoader.close()`（它持有 mod 的 classloader）。
ModLoader 不随 bake 结束而销毁，因为它还要承担动态加载。

**[v1.3 补充]** supervisor 现在是监督树（pid 0 的 super agent）；上/下班顺序不变，但关停时会按深度逆序
停 agent，并先杀子树。

## 7. Session 存储默认 SQLite，JDBC URL 可配

`config/jarness.toml` 的 `session.store` 是完整 JDBC 串，默认 `jdbc:sqlite:<base>/data/jarness.db`。
想换后端（如 Postgres）只需改配置或让 mod 注册其它 SessionStore。
消息体以 ContentBlock 列表的 JSON 形式落库，reasoning / tool_call / tool_result 在会话恢复后不丢失。

**[v1.3 补充]** store 现在挂在 namespace 上：`[namespaces.<id>] store = "jdbc:sqlite:..."` 可为某个
namespace 指定独立库（未指定则共用默认库）。P1 已把默认库迁到 `data/jarness.db`，实现 turn 事务、
`agent_image`、`checkpoints` 与 `events`；旧 `<base>/sessions.db` 不自动迁移（schema 不兼容）。

## 8. 动态加载只进不出

`ModLoader.loadJar(path, applyNow)` 支持运行时加载（Full 模板的 `mod_load` 工具走这条路），
加载即生效（注册 + 应用启用的 instrument）。卸载明确不支持：Java 里安全卸载需要隔离 classloader
且清理所有注册，代价远大于收益；设计上一个 mod 一旦加载就是永久的。

## 9. Provider 的流式与 reasoning 字段

openai-compat 适配器始终走 SSE 流式（非流式 complete() 只是挂了 DISCARD sink）。
reasoning 增量读 `delta.reasoning`（实测端点的拼法），并兼容 `delta.reasoning_content`。
tool_calls 按 `index` 聚合，id/name 只在首帧出现。anthropic / responses 适配器先实现非流式
（把完整结果一次性喂给 sink），早期版本够用；端点测试只覆盖 openai-compat。

## 10. 不做自动重试

provider 失败直接把 turn 置为 FAILED 并带出 HTTP 状态与原因。重试策略（尤其带上下文改写的重试）
更适合做成 hook/mod，而不是写死在循环里。

**[v1.3 取代]** v1.3 的 LOOP-1 要求 API 超时 / 429 / 解析失败按退避重试（工具超时不重试）。
P0 仍是失败即 FAILED；重试与 context 压缩一起放到 P4 的循环策略里。

## 11. maxTokens 的取舍顺序

模型条目声明了 `default_max_tokens` 就用它，否则回退 `limits.max_tokens`（默认 32000）。
实测端点声明 80000，尊重端点的声明比配置文件里的保守值更少截断。

## 12. UI 是独立系统 mod，不参与动态加载

按草案，UI 不作为 cordis 式动态加载对象：`jarness-mod-tui` 是 CLASSPATH 上的系统 mod，
其唯一 instrument `tui.frontend` 是手动的，仅被内建 `tui` preset 启用。启用后在
`RUNTIME_START` 钩子上起一个读 System.in 的 REPL 虚拟线程。

## 13. Preset 语义

preset 文件（TOML）可含：mod 允许列表（空 = 全部；`required` 的系统 mod 不受限制）、
instrument 开关表、默认 template/provider/model。用户 preset（`<base>/presets/*.toml`）
与内建 preset（classpath `presets/*.toml`）同名时用户优先。预设未点名 instrument 时，
按 mod 作者的 `autoEnable` 策略执行——与草案一致。

## 14. 依赖求解参照 FancyModLoader 的简化版

`jnmods.toml` 的 `[[dependencies]]` 支持 kind（required/optional/incompatible）、
version 区间（`*`、精确、`1.2.x`、`^`、`~`、`>=` 等、Maven 区间记号 `[1.0,2.0)`）、
ordering（before/after）。求解为拓扑排序（Kahn），平局时系统 mod 优先、再按 id 字典序，
保证同输入同顺序。环、缺失必需依赖、版本不满足、incompatible 在场都会产生带 hint 的
`ModLoadError`（NeoForge 式诊断：phase + 文件 + 原因 + 建议）。

## 15. P0 的 v1.3 对齐取舍（2026-11-01）

把 v1.3 落成代码时，几处需要当场决定：

- **super agent pid 0**：P0 先把 pid 0 做成 supervisor 持有的合成根（全能力、无会话、不可执行工具），
  保证树、父子关系、权限交集先成立；P3 已替换为真 agent（`super` 模板 + 平台工具 + 平台能力），
  并改为惰性创建，避免每个 CLI 命令都产生一个会话。
- **模型档位**：从 `AgentTemplate` 删除 `model()`，改由 namespace 的 `ModelProfile` 提供；
  `AgentRequest.model` 与 `manifest.modelProfileId` 可在 spawn 时覆盖（Q38 是否受允许集约束，留到 P4）。
- **预算只校验不切分**：子预算必须 ≤ 父剩余（token/deadline/spawn depth），但父子各持独立计数器，
  不做逐层额度切分与归并统计。设计与实现都容易在 P1 加镜像预算字段时一次做对。
- **能力先做粗粒度**：`Tool.requiredCaps()` 只声明 `CapKind`（fs.read / fs.write / exec / ...），
  资源级 scope 已进入 `Capability.resource`，但执行侧强制（Rust executor）在 P2；P0 的
  “拒绝”是类型级的，路径级仍由 SandboxMode 兜底。
- **namespace 隔离先到 store 引用级**：每个 namespace 可以指向不同 SQLite 库（物理隔离），
  但共用库时还没有行级 namespace 列；随 P1 的 schema 重写加入。
- **`AgentRequest` 保留**：它是 CLI/工具用的便捷创建输入，内部立即转成 `AgentManifest`；
  新代码应优先直接调 `Supervisor.spawn(parentPid, nsId, manifest, caps, budget)`。


## 16. P1 镜像层的取舍（2026-11-01）

- **turn 事务用「缓冲 + 单事务 commit」实现**：`SessionImpl` 在 running turn 期间不写库，`commitTurn`
  一次性写 turn delta、messages、events、agent_image。这比 WAL/delta 的教科书实现少一层，但语义
  （未提交 turn 崩溃即回滚、commit 才 durable）是等价的；后续需要边写边读时再加 WAL。
- **同时保留 messages 物化表和 turn delta**：design 的 LOCAL-1 只有 `TURN.delta`；物化表让列表/调试
  便宜，checkpoint 时一并压缩。两者在同一事务里写，不会出现半份历史。
- **resume 从 root 重新挂**：原父节点可能已不在，ADOPT/DETACH 未实现；resume 的树位置在 P3/P5 的
  mailbox/restart 一起补。
- **checkpoint 只显式触发**：没有每 N turn 自动 checkpoint；阈值调优留给有真实负载之后。
- **能力恢复**：镜像存的是「上次 commit 的 caps」；resume 时再与 parent/ns 求交，派生边在 supervisor
  里重建，不持久化旧 capId。

## 17. P2 执行层的取舍（2026-11-01）

- **协议先用 JSON-lines**（Q37 的暂定倾向）：帧小、可读、调试成本低；每帧一个 JSON 对象，按 callId
  多路复用。等它成为瓶颈再换 protobuf/flatbuffers，现在换只会增加调试难度。
- **HMAC 覆盖「调用意图」而不是整条 TCP 流**：canonical 串 `callId|tool|argsJson|deadline|capsJson`
  由 daemon 签名，executor 常量时间校验；args/caps 以字符串原样传递，避免 JSON canonicalization
  两边不一致。token 是一次性 32 字节 secret，经 env 传给子进程，不出现在命令行。
- **daemon 监听、executor 回连**：本机与远端同一方向；远端部署不需要给 daemon 开入站端口。
- **Java 工具类保留**：`ReadFileTool` 等仍是 LOCAL 实现与 spec 的来源；relay 模式用 `RelayTool`
  包装同一 spec/caps，把调用发给 executor。`execution.mode="local"` 是显式回退，不是自动降级。
- **执行器工具名与模型工具名分离**：`read` → `fs.read`、`bash` → `exec.run`；模型看到的仍是
  deepseek-harness 风格的名字，Rust 侧用更窄的 internal 名字。
- **幂等缓存放在 executor**（10 分钟）：重试同一 callId 不重复写文件；daemon 只负责生成 key。
- **默认审批 ALLOW**：v0.1 的「不阻塞无人值守运行」仍然成立；`ask` 需要宿主注册 handler，没 handler
  就是 deny；`never` 是显式收紧，不是默认值。
- **ASK 先同步**：虚拟线程上阻塞等待 handler 最简单；设计允许的异步挂起要等 P3 的 mailbox，否则
  会得到一套没有事件驱动的挂起状态机。

## 18. P3 协作层的取舍（2026-11-01）

- **mailbox 与提交队列分开**：宿主/模型的 `submit` 仍走 turn 队列；mailbox 承载控制、协作和异步完成。
  两者都由同一个 worker 消费，避免两个线程同时开 turn。mailbox 有界，`Supervisor.deliver` 满即返回
  false，这就是背压。
- **异步结果用 user 消息开新 turn**：执行器返回时原来的 turn 已经 commit，直接塞 tool_result 会形成
  「无 assistant tool_call 的孤儿结果」；用一条带 `# Async tool result` 的 user 消息最稳，provider
  不用改消息协议。代价是模型看到的是叙述而非结构化 tool 消息。
- **IPC 是进程内对象，不是 JSON 协议**：`Shm`/`Channel` 直接是 Java 接口；shm 的 cell 以内存读路径
  加 SQLite 写穿持久化，`shm` / `agent_send` 工具显式声明 `ToolForm.LOCAL`，不会下发 executor。
  shm 按 namespace 命名空间隔离，creator 退出不会清空共享状态；channel 仍是 owner-bound 句柄。
  跨 daemon 明确不做。
- **super agent 惰性创建**：设计说启动 profile 默认 1 个；但每次 `jarness mods`/`sessions` 都建一个
  会话不划算。实现改为「第一次 root spawn 或 `jarness super` 时创建」，权限模型不变。
- **平台工具的只读/写分级**：`ns_create/ns_remove/agent_kill/agent_signal` 是 mutating；普通 agent
  即使通过 `allowTool` 强行加进来，也会被 `PLATFORM` 能力门拒绝。

## 19. P4 循环/扩展的取舍（2026-11-01）

- **turn 开始时 pin Registry 快照**：动态加载后「下一 turn 读新快照」靠这个实现；同一 turn 里新加的
  工具不会出现在当前请求中，符合 MOD-3。
- **Registry 版本是显式 bump**：初始 bake、`Supervisor.loadDynamic`、`mod_load` 工具各 bump 一次；
  直接调用 `tools().register` 的 mod 不会自动 bump（这是 mod 自己的责任），避免在注册表里埋回调。
- **重试只针对 provider**：`ProviderException.retryable()` 决定；工具超时不重试，按设计返回 timeout
  观测。重试有上限和 jitter，避免惊群。
- **压缩先做「丢最旧 + 说明」**：模型总结需要额外一次调用，且会改变上下文语义；先用可预测的
  窗口策略满足 LOOP-1 的 Compact 状态，未来可替换成摘要器。
- **schema 校验只查必填**：类型仍由工具 accessor 宽容处理（模型把 5 写成 "5" 是常态）；必填缺失在
  调度器就返回 `tool_error`，不再进入工具体。

## 20. P5 观测/恢复的取舍（2026-11-01）

- **CDC 用轮询而不是推送**：事件已经在 SQLite 的 `events` 表里，轮询实现最小、可重放、不引入第二套
  通知总线；`[observe] poll_ms` 可调。冷观测明确不做。
- **审计用 logger 而不是新表**：spawn/deny/restart、tool、executor call/result、approval 都写
  `ch.predmet.jarness.audit`；需要 SQL 审计时再加表，避免 P5 把 schema 再翻一遍。
- **重启从同一 session 重建**：`ON_FAILURE` 只在 `ExitStatus.FAILED` 时触发，`ALWAYS` 还要求退出不是
  主动 stop/shutdown/suspend/parent；`max_restarts` 防循环。重新 spawn 会走一遍 resume 依赖校验。
- **lazy 的 super 与 restart 的组合**：super 自身失败时 restart 逻辑会尝试以 pid 0 的 parent 重建；
  由于 super 已在 registry 里，这条路径目前会拒绝并记日志，不递归。真实部署里 super 的失败应由
  外部平台重启 daemon。

## 21. 远端 executor 的取舍（2026-11-01）

- **daemon 是 listener，executor 回连**：本地/远端共用方向；远端部署不需要在 daemon 上额外开放 executor 的入站端口。`RemoteExecutorServer` 接受连接、校验 enrollment token，再把连接包装成 REMOTE provider。
- **enrollment token 放在 env**：与本地 executor 一致，不出现在进程命令行；每个 call 仍有 HMAC。
- **TLS 用 PKCS12 + rustls**：daemon 侧 `SSLServerSocket` 从 PKCS12 keystore 取服务端证书；
  executor 侧用 `--ca` 做 CA 校验。`remote_tls=true` 而 keystore 缺失时直接不启动，避免「看起来安全、
  实际明文」。Q36 剩下的只是 secret 轮换策略，不是传输层。
- **executor 的 TLS I/O 是单线程 drain/read 循环**：TLS 流不能像 TcpStream 那样 clone 成读写两半；
  executor 用一个 20ms read timeout 的主循环同时写出站帧和读入站帧，call 线程只往 outbox 放结果。
  明文与 TLS 共用这个循环，避免两套路径。


## 22. TLS 与 net.fetch 的落地（2026-11-01）

- **TLS 用 PKCS12 + rustls**：daemon 侧 `SSLServerSocket` 从 PKCS12 keystore 取服务端证书；
  executor 侧 `--tls --ca` 做 CA 校验。`remote_tls=true` 而 keystore 缺失时直接不启动，避免
  「看起来安全、实际明文」。Q36 剩下的只是 secret 轮换，不是传输层。
- **TLS 的 I/O 用单线程 drain/read 循环**：TLS 流不能像 TcpStream 那样 clone 成读写两半；
  executor 用一个 20ms read timeout 的主循环同时写出站帧、读入站帧，call 线程只往 outbox 放结果。
  明文与 TLS 共用这一条路径。
- **net.fetch 在 executor**：用 ureq 取 HTTP(S)，先校验 NET capability（host scope），再剥 HTML
  标签、截断 20000 字符；`web_fetch` relay 时走它，`web_search` 暂时留在 daemon。
- **rustls provider**：同时编进 ring 与 aws-lc-rs 时必须显式 `install_default`；executor 启动时
  安装 ring provider。


## 23. v1.4：in-JVM IPC 与 namespace cwd（2026-11-02）

用户明确了几处语义，按此修正实现；`docs/harness-design.md` 里的 “Blackboard” 一词在代码与 API 中
统一称为 **Shm（Shared Memory）**。

- **IPC 不再有 executor 路径**：`shm` 与 `agent_send` 都是 `ToolForm.LOCAL` 工具，直接调用
  `runtime.ipc()` / `agent.mailbox()`，不经过 `ExecutionRegistry`。`IpcToolTest` 在
  `execution.mode = "relay"` 且没有任何 execution provider 的运行时里验证了这一点。
- **shm 是 namespace 级共享内存**：id 只在 namespace 内唯一；cell 先写 SQLite（`shm_boards` /
  `shm_cells`，值以 JSON 保存）再更新内存，读路径走内存。daemon 重启后按 id 重新 attach 即可恢复。
  shm 的 creator 退出不会删除共享状态（`closeOwned` 只回收 channel）；需要删除时用
  `IpcRegistry.removeShm(namespace, id)`。
- **mailbox 仍是直接投递**：`agent_send` 检查目标存在、同 namespace（或持有 `namespace`/`platform`
  能力），然后 `target.deliver(MailboxMessage.data(...))`；目标 agent 的 worker 把它当作普通
  `DATA` 消息开新 turn。没有 JSON 协议、没有 executor 跳转。
- **一个 namespace 一个 cwd**：`NamespaceMetadata.cwd` 是唯一工作目录来源。`AgentImpl` 不再看
  `AgentRequest.workspace`（字段已删），`SubagentTool` 不再创建 `subagents/<uuid>`；`bash` 的
  默认目录、`read/write/glob/grep` 的相对路径、沙箱 `workspace-write` 边界都跟着 namespace cwd。
- **配置**：默认 namespace 用 `[defaults] cwd`，额外 namespace 用 `[namespaces.<id>] cwd`；
  缺省值分别是 `<base>/workspaces/default` 与 `<base>/workspaces/<id>`。`jarness namespaces`
  和 `ns_list` 都会显示 cwd。
- **live endpoint**：`TestEndpoints` 优先读取已安装的
  `~/.local/share/jarness/config/providers.toml`（并用 `defaults.provider` 选 profile），
  读不到才回退到 `tmp/endpoint.txt`；`make it` 因此跟随 daemon 当前端点。


## 24. 2026-11-02 整体走查后的收敛决策（2026-11-02）

本轮走查的完整当前状态见 [`architecture-review-2026-11-02.md`](architecture-review-2026-11-02.md)。
对含糊或实现与目标冲突之处，采用以下取舍：

- **standalone 不是 daemon**：当前 CLI 每条命令各自启动 runtime 的事实明确写出，不再把同进程平台工具
  描述成能管理其它 CLI/TUI 进程。真正的控制协议、跨进程 image lease、BOOT-1 树恢复是后续架构工作，
  不在本轮用一个本地 socket 半成品冒充完成。
- **namespace 路由存选择器，不存连接对象**：`NamespaceSpec.executionProviders` 是有序 provider id 或
  `local`/`remote` kind；默认 namespace 为 `local`。物理 provider 仍在 runtime registry 中，断线即不可选。
  labels/load/capability 匹配留作路由策略扩展，不在 core 里隐式猜测。
- **RELAY 是 API 声明**：agent dispatcher 根据 `Tool.form()/relayName()` 统一路由，mod 不需要链接 core 的
  `RelayTool`。core 的 wrapper 仅用于复用内建工具 spec。
- **审批看声明的副作用**：`ToolSpec.readOnly=false` 即进入 approval policy，不再从 capability kind 反推。
  IPC/SPAWN/PLATFORM 等 mutating 工具因此同样受 ASK/NEVER 约束。
- **执行侧路径用 beneath 目录句柄**：Rust 引入 cap-std，而不是“词法前缀 + canonicalize + 再打开”。
  这样新文件写入与 symlink/TOCTOU 才有统一强制点。LOCAL 只作为兼容模式，也检查真实路径与资源 scope，
  但不宣称它提供同等级的竞态隔离。
- **HMAC 绑定完整执行上下文**：签名包含 call/tool/args/deadline/caps/workspace/sandbox/namespace/idempotency，
  并用 JSON 数组避免分隔符歧义。`web_fetch` 每一跳 redirect 都重新检查 NET host。
- **commit 失败停止 writer**：宁可让 agent FAILED 并走 restart，也不能把未落库的内存消息带到下一 turn。
  title/model 在 running turn 中不再独立 update，而由 turn commit 的同事务写入。
- **checkpoint 与 turn 串行，suspend 先停后快照**：最新 checkpoint 或 committed delta 解码失败时拒绝加载，
  不回退成静默截断。旧 ImageRef 不静默加载最新；真正 point-in-time resume 另做。
- **Shm 的状态和句柄分离**：cells/watchers/locks/store 共享，ownerPid/closed/watch 注册属于每个 handle。
  `findShm` 也需要 owner 和 IPC capability，不再返回无主的可写句柄。
- **async 完成先投递再清 pending**：mailbox 满时虚拟线程等待；停止时保留 pending 标记并记日志。
  这只解决运行中不丢结果，不等于已经实现崩溃后的 pending reconciliation。
- **Registry 快照冻结声明，物理连接保持活态**：tools/model providers/loopHooks 使用 turn snapshot；
  executor 连接必须能及时反映断线，因此不长期 pin 死 provider 对象。
- **后台 RELAY 复用 JobRegistry**：job 线程等待 executor 调用，不引入另一套后台协议；模型侧
  `job_output/job_kill` 在 local/relay 两种模式下语义一致。
- **预算先修不变量，再做层级记账**：本轮补 toolSlots/depth 衰减和请求 maxTokens/deadline 收窄；
  父子 reservation/归并仍明确留待后续，不把单次 admission 称为完整树级配额。
