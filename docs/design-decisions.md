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

## 5. base dir 与 agent workspace 严格分离

base dir（默认 `$HOME/.local/share/jarness`）只放 `mods/`、`config/`、`presets/`、`data/jarness.db`、
`workspaces/`。每个 agent 的 workspace 默认是 `<base>/workspaces/agents/agent-<pid>` 下的新目录，
也可以由创建方显式指定，但永远不等于 base dir。工具的相对路径一律相对 agent workspace 解析。

**[v1.3 补充]** pid 由 supervisor 分配，workspace 目录名随之从随机 id 改为稳定的 `agent-<pid>`；
子 agent（`subagent` / `agent_spawn`）仍默认落在父 workspace 的 `subagents/` 下。

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
- **IPC 是进程内对象，不是 JSON 协议**：`Blackboard`/`Channel` 直接是 Java 接口；句柄即能力
  （创建要 `ipc`），owner 退出 `closeOwned`。跨 daemon 明确不做。
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
- **TLS 暂不做**：Q36 未定（远端是否强制 TLS、secret 轮换策略）。先提供 `remote_tls` 开关，但开启时
  只记录警告并退回明文回环，避免给出「看起来安全、实际没有」的假象。Q36 定案后把 `SSLServerSocket`
  和 rustls 客户端接到同一协议即可。
