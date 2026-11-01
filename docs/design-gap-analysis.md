# 现有实现 ↔ `harness-design.md` v1.3 差异审计

> 2026-11-01 · 审计对象：`docs/harness-design.md`（v1.3）与当前 `main`（7 个提交，v0.1 运行时）
> 结论：**存在系统性冲突**，不是补几个接口能对齐的。现有实现对应的是旧 draft 的「平面 supervisor + 全局 store + JVM 内工具」；v1.3 要求「监督树 + namespace + capability + 镜像事务 + daemon 外 executor」。
>
> 处理原则：以设计文档为准；0.1.x 允许破坏 API；可复用的层（provider、mod loader、tool、TUI/CLI）保留并逐步改造。

## 0. 总账

| 类别 | 数量 | 说明 |
| --- | --- | --- |
| 直接冲突（C） | 15 | 现状与文档的语义/结构相反，继续叠加会越走越远 |
| 缺口（G） | 12 | 文档要求、代码里完全没有；不与现状矛盾 |
| 可复用资产 | 8 | 不需要重写，只需挂到新结构上 |

冲突集中在四层：内核（树/预算/清单）、环境（namespace/capability/模板）、状态（镜像/事务）、执行（executor/IPC）。扩展层（ModLoader）方向一致但 API 形状有差距。

## 1. 直接冲突

| # | 主题 | 现状 | 目标 | 证据 | 处理 |
| --- | --- | --- | --- | --- | --- |
| C1 | supervisor 是平面注册表，不是监督树 | 所有 agent 平铺在 `Map<String, Agent>` 中；无 parent、depth、children、子树 kill、配额 | TREE-1/TREE-2：spawn/fork 必带 parent，层级配额，父死 kill 子树 | `SupervisorImpl`、`Supervisor` | P0 重写为树，保留 String 便捷方法 |
| C2 | 模型选择属于 AgentTemplate | `AgentTemplate.model()`、`SimpleAgentTemplate.model`，循环里模板优先 | I7/TEMPLATE-1：模板不含模型；ModelProfile 属 namespace，spawn 时 manifest 可覆盖 | `AgentTemplate`、`AgentImpl.resolveModel` | P0 删除模板 model，落到 namespace metadata |
| C3 | 没有 namespace | 单份 `SessionStore`、单份模板表、单份 provider 表；agent 无 ns 绑定 | NS-1/NS-2：每 ns 有 Metadata + store + defaultAgentTemplate；跨 ns 需能力 | `JarnessRuntimeImpl`、`AgentImpl` | P0 引入 NamespaceRegistry + per-ns store 引用 |
| C4 | capability 缺失，只有全局 SandboxMode | `SandboxMode` 是唯一「能碰什么」的表达；无多资源、无衰减、无撤销 | CAP-1/CAP-2/I3：kind/resource/constraints、`requested ∩ parent ∩ ns`、派生 DAG | `SandboxMode`、`ToolContext.resolve` | P0 引入 CapabilitySet；SandboxMode 降级为 LOCAL 工具的兜底 |
| C5 | 无 AgentManifest / Budget / 生命周期策略 | `AgentRequest` 把清单、创建参数、模型混在一起；无 restart/orphan/origin/loopRef、无预算 | PROC-1/TREE-1/FORK-1/LIFE-1 | `AgentRequest` | P0 增加 AgentManifest、Budget、Origin、Restart/Orphan |
| C6 | 持久化不是 turn 事务 | 每条 message 立刻落库；每个 step 结束 `saveTurn`；turn 中途崩溃留下半截状态 | I4/IMAGE-1/IMAGE-2：turn 是事务，commit 才 durable；WAL + checkpoint | `AgentImpl.appendMessage/saveTurnQuietly`、`SqliteSessionStore` schema | P1 重写 store（delta/WAL/commit/checkpoint） |
| C7 | 工具在 daemon JVM 内执行 | `ReadFileTool`/`BashTool` 等直接读写本机、起子进程 | TOOL-2/EXEC-1：默认 Rust executor 子进程，长连接回连；Tool 与 Exec 解耦 | `core/tool/builtin/*` | P2 引入 ExecutionProvider + RELAY，LOCAL 只留纯计算工具 |
| C8 | 无 SYNC/ASYNC、无 mailbox、无 IPC | agent 一个 `LinkedBlockingQueue<Submitted>`；工具全部同步等待 | TOOL-3/CONC-1/IPC-1..3：mailbox 有界、异步结果开新 turn、黑板/通道 | `AgentImpl.queue` | P3 引入 mailbox + 控制/数据分离 + IPC 原语 |
| C9 | 无审批策略，capability 无强制点 | 明确「不做审批提示」，只靠沙箱模式；工具不做能力校验 | TOOL-1/CAP-2：能力校验 → 副作用分级 → approval policy | `docs/design-decisions.md` D7、`ToolContext` | P0 先做工具级能力门；审批与执行侧强制 P2 |
| C10 | 扩展点与 Registry 形状不一致 | `InstrumentRegistry` + `HookBus`；ModDescriptor 无 apiVersion；`ModLoader.loadAll` 而非 `loadBoot/loadDynamic` | MOD-1：tools/loopHooks/policies/templates/executionProviders/sessionStores/namespaceTemplates + 只读快照 | `api/mod/*`、`api/instrument/*` | P4 收敛为 Registry；P0 不改 mod 加载 |
| C11 | 没有 super agent | 命令行/工具直接 `runtime.spawn(...)`，没有 pid 0 平台主体 | SUPER-1：唯一持平台能力的 agent；跨 ns、平台工具、管 ns | `SupervisorImpl` | P0 用合成根节点 pid 0 占位；P3 落成真 agent |
| C12 | 循环没有重试/压缩/超时契约 | provider 失败即 FAILED；无退避重试、无 context 压缩、工具无 deadline/cancel/idempotency | LOOP-1/TURN-1/TOOL-3 | `AgentImpl.runTurn` | P4 循环策略 + 重试；P3 异步超时 |
| C13 | 无 checkpoint/resume/suspend | 有 session 载入，但没有「镜像」概念、没有依赖清单校验、没有强制加载降级提示 | IMAGE-1 §3.2、LIFE-1 | `AgentImpl`、`SessionStore` | P1 |
| C14 | 生命周期/进程视图不完整 | `AgentState` 只有 NEW/IDLE/RUNNING/WAITING/STOPPED/FAILED；无 exit status、tree、stats、await | PROC-1/LIFE-1 | `api/agent/AgentState` | P0 加 pid/parent/tree/stats/await；状态枚举 P1 再扩 |
| C15 | 路径与布局不一致 | 会话库 `<base>/sessions.db`；无 `data/` | LOCAL-1：`<jarness_home>/data/jarness.db` | `JarnessLayout.sessionDb` | P1 迁移（保留旧库只读兼容） |

## 2. 缺口（不矛盾，但文档要求而代码没有）

| # | 缺口 | 目标 | 计划 |
| --- | --- | --- | --- |
| G1 | ExecutionProvider 接口与 Local/Remote 实现 | TOOL-2/EXEC-1 | P2 |
| G2 | Rust executor 二进制、握手、帧协议、认证 | EXEC-1 | P2 |
| G3 | Blackboard / Channel / handle-as-capability | IPC-1..3 | P3 |
| G4 | mailbox 有界、背压、异步完成事件 | CONC-1/TOOL-3 | P3 |
| G5 | super agent 的平台工具（ns 管理、daemon 状态） | SUPER-1 | P3 |
| G6 | loopHooks / policies / contextAssembler 扩展点 | MOD-1 | P4 |
| G7 | Registry 快照版本（动态加载后下一 turn 读新快照） | MOD-3 | P4 |
| G8 | checkpoint / suspend / resume / restart policy | LIFE-1/IMAGE-1 | P1 |
| G9 | 依赖清单校验 + `--force` 降级提示词 | IMAGE-1 §3.2 | P1 |
| G10 | 观测面：CDC、只读连接、审计 | LOCAL-1/EVT-1 | P5 |
| G11 | remote executor、TLS/enrollment、防 C2 | EXEC-1 Q36/Q37 | P5 |
| G12 | 跨 daemon IPC、COW、ADOPT/DETACH、速率限制 | 文档明确「暂不做」 | 不做（记录） |

## 3. 可复用资产

1. **provider 适配层**：`OpenAiCompatProvider`/`AnthropicProvider`/`OpenAiResponsesProvider` 与 `ProviderProfile`，v1.3 没有推翻（只是模型选择改由 ModelProfile 指向）。
2. **mod 加载与解析**：`ModLoaderImpl`/`ModResolver`/`jnmods.toml`/`Version`，与 MOD-2/MOD-3 的「仅加载、不卸载、拓扑序」一致。
3. **instrument/preset**：作为「mod 内可开关的装配单元」保留；P4 再映射到 Registry 扩展点。
4. **tool 实现体**：可作为 LOCAL 工具或 RELAY 的 daemon 侧 fallback 复用。
5. **hook bus + 事件 payload**：与 EVT-1 的「事件流」方向一致，后续接 WAL。
6. **session/message/content block 编解码**：P1 重写 schema 时可复用 `MessageCodec`。
7. **CLI/TUI**：只依赖 `JarnessRuntime`/`Agent`，P0 加 ns/pid 展示即可。
8. **测试基建**：`TestEndpoints` 实时端点、mod 动态加载测试、CLI 冒烟测试。

## 4. 迭代计划

### P0（本轮）：内核 + 环境

范围：pid/监督树、AgentManifest、Budget、CapabilitySet、NamespaceRegistry、per-ns store/template/model、工具能力门、子 agent 衰减。

验收：

- [x] `Supervisor.spawn(parentPid, nsId, manifest, caps, budget)` / `fork(...)` 可用；深度、子数、预算、越权、跨 ns 五类拒绝都有明确原因与事件。
- [x] 父死 → 子树 kill；`tree(pid)` / `stats(pid)` / `await(pid)` 可观测。
- [x] namespace 可从配置创建；agent 绑定唯一 ns；ns 可指定独立 store、default template、model profile。
- [x] `requested ∩ parent ∩ ns.defaults` 生效；工具声明 requiredCaps，缺能力时返回 `capability_denied`。
- [x] 现有 provider/mod/tool/TUI 冒烟不回归（实时端点测试因上游 503 未复跑，见 §6）。
- [x] `docs/design-decisions.md` 标注被 v1.3 取代的条目。

不在 P0：镜像事务（P1）、executor（P2）、IPC/异步/审批（P3）、Registry 收敛（P4）。

### 后续

- **P1 状态层**：`data/jarness.db`、`agent_image/turn/event/checkpoint`、turn 原子 commit、checkpoint/resume、依赖校验与降级提示。
- **P2 执行层**：ExecutionProvider、LocalExecutionProvider（桥）、JSON-lines 本地 executor 子进程、LOCAL/RELAY、SYNC/ASYNC、timeout/cancel/idempotency、审批策略、能力 scope 执行侧强制。
- **P3 协作层**：mailbox、黑板/通道、异步结果 turn、super agent 与平台工具。
- **P4 扩展/循环**：Registry 扩展点与快照、loopHooks/policies、重试/压缩、mod disable 语义。
- **P5 观测/远端**：CDC、只读快照、审计、remote executor、TLS/enrollment。

## 5. P0 中的取舍与开放问题

1. **合成根节点 pid 0**：v1.3 的根是 super agent（真 agent）。P0 先用 supervisor 持有的合成根（全平台能力、无会话），保证树与权限模型成立；P3 再把它换成可执行平台工具的 agent。
2. **配额不做逐层记账**：P0 校验「子预算 ≤ 父剩余」，但父/子各持独立计数器，不做额度切分与归并统计。文档 §1.2 的「逐层切分」留到 P1 与镜像预算字段一起做，避免两套记账。
3. **namespace 隔离粒度**：P0 的 ns 可指向不同 store（不同 DB 文件即物理隔离），但默认仍共用一份 SQLite；行级 namespace 列随 P1 的 schema 重写加入。
4. **Q38（manifest 覆盖模型档位）**：P0 允许 `manifest.modelProfileId` 在 ns 的 profiles 内选择；直接 `AgentRequest.model` 仍可覆盖。是否受允许集/预算约束，P4 与策略层一起定。
5. **审批策略**：P0 不做，保留 `PolicySet` 占位；`ask` 的异步挂起依赖 P3 的 mailbox，提前实现会得到一套用不上的状态机。
6. **`AgentRequest` 与 `AgentManifest` 并存**：前者是给 CLI/工具用的便捷创建输入，内部立即转成 manifest；新代码应直接用 manifest。

## 6. P0 落地记录（2026-11-01）

已实现（提交 `feat(kernel): supervision tree, namespaces, capabilities, budgets` 之后的代码）：

| 设计 | 落点 |
| --- | --- |
| PROC-1 / TREE-1 / TREE-2 | `Supervisor.spawn(parentPid, nsId, manifest, caps, budget)` / `fork` / `signal(pid, signal, subtree)` / `tree` / `stats` / `await`；`SupervisorImpl` 维护 pid → parent/children，深度与子数限制来自 `[supervisor] max_depth/max_children` |
| I3 能力衰减 | `CapabilitySet.intersect`（resource 取更窄、limits 取更窄）+ `SupervisorImpl.assignCapIds` 记录派生边；`Supervisor.revokeCap` 级联到持有者 |
| I6 spawn / fork | `spawn` = 新镜像；`fork` = 复制父会话消息（turns 的镜像复制随 P1 的 image 一起做） |
| NS-1 / NS-2 | `NamespaceRegistry` + `NamespaceSpec`；`config [namespaces.<id>]` 支持 `template` / `model` / `store` / `capabilities`；默认 ns 由 `[defaults]` 构造 |
| I7 模型解耦 | 删掉 `AgentTemplate.model()`；`ModelProfile` 在 ns metadata，`manifest.modelProfileId` / `AgentRequest.model` 可覆盖 |
| CAP-1 / CAP-2（core 侧） | `CapKind` / `Capability` / `CapabilitySet` / `Constraints` / `CapabilityDerivation`；工具用 `Tool.requiredCaps()` 或 `CapabilityTool` 包装，缺能力时 `capability_denied` |
| TREE-2 拒绝事件 | `SpawnDeniedException`（PARENT/NAMESPACE/DEPTH/CHILDREN/BUDGET/CAPABILITY/TEMPLATE/MODEL）+ `HookPoint.SPAWN_DENIED` / `SpawnEvent` |
| CONC-1 背压（最小版） | 每个 agent 一个有界 submission queue；预算在 turn 内按 step 扣 token、按调用扣 tool slot |
| 可观测 | `AgentStatus` 增加 pid/parent/ns/depth/toolCalls；`jarness namespaces` 命令 |

测试：`CapabilitySetTest`（6）、`BudgetTest`（5）、`SupervisorTreeTest`（10，覆盖树/衰减/深度/宽度/跨 ns/父死/预算/撤销/fork/独立 store）；其余 26 个既有单元测试通过。

未闭环（按计划留到后续）：

- 实时端点联调本轮未复跑：GB10 端点对 `DeepSeek-V4-Flash` 返回 HTTP 503
  `model_not_found / No available channel`，CLI 的 spawn/TUI 路径本身已验证（`./jn` TUI 启动、
  `jarness namespaces`、`jarness run` 的失败路径）。端点恢复后应重跑 `make it`。
- 镜像事务、`data/jarness.db`、checkpoint/resume：P1。
- ExecutionProvider / Rust executor / 资源级能力强制：P2。
- mailbox / IPC / 审批 / super agent 平台工具：P3。

## 7. P1 落地记录（2026-11-01）

目标：把「镜像 = 唯一权威、turn = 事务」落成可恢复的存储层。

已实现：

| 设计 | 落点 |
| --- | --- |
| LOCAL-1 路径 | `<base>/data/jarness.db`；`JarnessLayout.imageDb()` / `legacySessionDb()`；默认 `session.store` 指向新路径（旧的 `sessions.db` 原样保留，未自动迁移） |
| IMAGE-1 / IMAGE-2 | `SessionImpl` 在 turn 运行期间只把 message 缓冲在内存；`Session.commitTurn(turn, image)` 通过 `LocalSessionStore` 在**一个 SQLite 事务**里写入 turn delta + messages + events + `agent_image`；未提交 turn 崩溃即消失 |
| turn 状态 | `turns.status` 只有 `committed`；running 不落盘，回放只读 committed |
| checkpoint | `LocalSessionStore.checkpoint` 把完整 messages/turns 快照写入 `checkpoints`，更新 `agent_image.checkpoint_turn`，删除被覆盖的 messages/turns；`load` 走「最新 checkpoint + 重放 seq > checkpoint 的 committed delta」 |
| 事件 | `events` 表与 turn 同事务写入（`turn.committed` / `message.appended`），`SessionStore.events` 可按 turn 读取 |
| 依赖清单 | `agent_image.deps` 记录 schema / template+version / loop / namespace / mods / tools；`AgentImpl` 在 spawn 时写、每次 commit 更新 |
| resume 校验 | `AgentImpl` 比较 deps；不匹配抛 `ResumeDeniedException`；`AgentRequest.forceResume` 时只保留可用工具并注入 `# Degraded image` 系统提示词 |
| 预算/能力恢复 | `agent_image.budget` / `caps` 持久化；resume 时预算取「父预算 ∩ 镜像预算」，能力在 supervisor 里再与镜像 caps 求交 |
| checkpoint / suspend API | `Supervisor.checkpoint(pid) -> ImageRef`、`Supervisor.suspend(pid)`（checkpoint + `SUSPENDED`）、`Supervisor.resume(ImageRef)` |
| CLI | `jarness sessions [--image]`、`jarness run --session <id> [--force-resume]` |

测试（新增 10 个）：`LocalSessionStoreTest` 覆盖「运行中不可见 / 未提交崩溃即丢 / checkpoint 压缩+重放 / image 往返 / 事件 / 连续 turn 编号」；`ResumeTest` 覆盖「依赖不匹配拒绝 / 强制降级 + 预算/能力恢复 / resume+checkpoint+suspend」。

明确的取舍与未闭环：

- **schema 形状**：LOCAL-1 把 messages 放在 `TURN.delta`；本实现同时保留 `messages` 物化表（按 turn_seq 归属），便于列表/调试；`turns.delta` 里仍带一份完整 turn。checkpoint 后两份都会被压缩。语义（turn 原子、commit 才 durable）一致，形状不同。
- **旧库迁移**：`sessions.db` 不自动迁移到 `data/jarness.db`（旧 schema 与 v1.3 不兼容）；需要迁移时应写一次性迁移工具，而不是在启动路径里猜。
- **checkpoint 触发**：只支持显式 `Supervisor.checkpoint` / `suspend`，没有「每 N 个 turn 自动 checkpoint」；设计也允许阈值后调。
- **镜像里的树/异步**：`parentPid`、pending async calls、capability 派生边还没有进镜像；tree position 和 pending 对账分别在 P3 的 mailbox/异步层补齐。
- **resume 的权限上下文**：resume 作为 root agent 重新挂到 pid 0；原父节点不在时不做 ADOPT（P3/P5 的 restart/orphan 一起做）。

P1 完成后，C6 / C13 / C15 的目标语义已经成立（C13 的 tree position 除外）；下一阶段是 P2 执行层。

## 8. P2 落地记录（2026-11-01）

目标：Tool 与 Exec 解耦，默认把 fs/exec 工作交给 daemon 外的 Rust executor。

已实现：

| 设计 | 落点 |
| --- | --- |
| TOOL-4 实现形态 | `Tool.form()` = LOCAL / RELAY，`relayName()`；`RelayTool` 保留原 spec/caps，把调用交给 `ExecutionRegistry` |
| TOOL-2 / EXEC-1 | `executor/` Rust crate（`jarness-executor`）：daemon 监听 loopback，executor 主动回连；JSON-lines 帧 `hello / hello_ack / call / cancel / result / ping / shutdown`；按 callId 多路复用 |
| 认证 | 一次性 32 字节 token（env 传入）+ 每次 call 的 HMAC-SHA256（`callId|tool|argsJson|deadline|capsJson`），executor 常量时间校验；loopback 本地免 TLS（远端 TLS 在 P5） |
| 执行工具 | `fs.read` / `fs.write` / `fs.edit` / `fs.delete` / `fs.glob` / `fs.grep` / `exec.run`；输出格式对齐原 daemon 工具 |
| 能力/沙箱强制 | executor 校验 FS_READ/FS_WRITE/EXEC kind、resource scope、`read-only`/`workspace-write`/`full-access`；daemon 侧仍有工具级能力门与 SandboxMode |
| 超时/取消/幂等 | `ExecRequest.deadline` → provider 超时后发 `cancel` 并返回 timeout；executor 对 `idempotencyKey` 缓存结果（10 分钟）命中直接返回；`exec.run` 自带 timeoutMs 与取消 kill |
| 审批（TOOL-1） | `ApprovalPolicy` ALLOW / ASK / NEVER + `ApprovalRegistry`；ASK 调 handler（默认无 handler 即 deny）；NEVER 只放行 allow-list；配置 `[approval] policy/allow/timeout_seconds`，namespace 可覆盖 |
| 构建 | `make executor` 构建 Rust 并 stage 到 `dist/executor/jarness-executor`；`make dist` 同时产出 jar + executor；`jn` 用 `-Djarness.executor` 指绝对路径 |
| 回退 | `[execution] mode = "local"` 显式使用 daemon 内工具；RELAY 缺 provider 时返回 `execution_failed`，不静默回退 |

测试（新增 9 个）：`ExecutorIntegrationTest`（写/读/exec、能力拒绝、workspace 越界、provider deadline、幂等）、`RelayAgentTurnTest`（脚本模型 → write 工具 → Rust executor → 文件落盘 → turn commit）、`RelayToolTest`（relay 注册/provider alive）、`ApprovalTest`（ask allow/deny、never allow-list）。

未闭环：

- **ASYNC**：`ToolMode.ASYNC` 已在 API/协议里，但异步结果要经 mailbox 开新 turn，属于 P3；当前 relay 一律 SYNC。
- **net 工具**：`web_search`/`web_fetch` 仍是 LOCAL；executor 的 `net.fetch` 与 NET scope 强制放 P5，和 remote executor 一起做。
- **流控/进度**：协议有 call/cancel/result，没有 per-stream credit 与 progress 帧；P5 补。
- **executor 崩溃重启**：provider 标 dead、在飞调用失败；自动重启交给 P5 的 restart policy。
- **审批异步挂起**：ASK 现在同步调 handler（虚拟线程阻塞）；`ask` 的「挂起 → 决策事件 → 继续」复用 P3 的 mailbox。

## 9. P3 落地记录（2026-11-01）

目标：协作层原语、异步结果、平台级 super agent。

已实现：

| 设计 | 落点 |
| --- | --- |
| CONC-1 mailbox | 每个 agent 一个有界 `Mailbox`（默认 256，`[mailbox] capacity`）；`DATA` 变 turn、`CONTROL` 走 signal、`ASYNC_RESULT` 在空闲时开新 turn；`Supervisor.deliver` 走 mailbox，满即拒绝（背压） |
| TOOL-3 异步 | `Tool.mode()==ASYNC` 时当前 turn 记录 `accepted callId`，工具体在虚拟线程执行，完成后投递 mailbox；结果成为下一 turn 的用户消息 |
| IPC-1/2/3 | `Blackboard`（版本化 cell、CAS、watch、悲观锁、snapshot）、`Channel`（有界/rendezvous、send/trySend/recv/tryRecv/close/select）；`IpcRegistry` 持有句柄 |
| 句柄即能力 | 创建 board/channel 要求 `IPC` capability；owner pid 退出时 `closeOwned` 关闭全部句柄；`Supervisor.createBoard/createChannel` 委托 |
| SUPER-1 | pid 0 的真实 super agent：`CapabilitySet.root()`（含 PLATFORM/NAMESPACE）、`super` 模板、平台工具 `ns_list/ns_create/ns_remove/daemon_status/agent_tree/agent_kill/agent_signal`；首次 root spawn（或 `ensureSuperAgent`）时惰性创建 |
| 平台工具 | 每个平台工具都要求 `PLATFORM`，普通 agent 即使强行 allowTool 也会被能力门拒绝；root agent 挂在 super 下，cap 仍是 ns.defaults |
| CLI/TUI | `jarness super` 直接和 pid 0 对话；TUI `/super` 切换；`jarness sessions` 可见 super 会话 |

测试（新增 10 个）：`IpcTest`、`MailboxTest`、`MailboxTurnTest`（异步工具开新 turn）、`SuperAgentTest`（平台能力/父子/daemon_status 调用）。

未闭环：

- **pending async 对账**：镜像 deps 记录 pending call，但 resume 不会重新挂起或对账；现在把「checkpoint 时仍有 pending」当作 resume 问题，需 `--force-resume` 并接受结果丢失。
- **审批异步挂起**：ASK 仍是同步 handler（虚拟线程阻塞），没有走 mailbox 的「挂起 → 决策事件 → 继续」。
- **super agent 启动策略**：为避免每个 CLI 命令都产生会话，pid 0 是惰性创建；设计说 profile 默认 1 个，行为等价但不是启动即创建。
- **kill 权限**：平台 kill 工具要求 PLATFORM，但 supervisor API 本身仍可被宿主调用；「只接受外部平台信号」没有额外的来源签名。

## 10. P4 落地记录（2026-11-01）

| 设计 | 落点 |
| --- | --- |
| MOD-3 快照 | `Registry.version()/snapshot()`；每次 turn 开始时 pin 一份 snapshot，工具解析和调用都走这份快照；动态加载/`Supervisor.loadDynamic` bump 版本，下一 turn 才可见 |
| MOD-1 扩展点 | Snapshot 暴露 tools / templates / providers / executionProviders / loopHooks / sessionStores；`sessionStores` 按 namespace 聚合 |
| apiVersion | `ModDescriptor.apiVersion`（默认 `1`），`jnmods.toml` 支持 `api_version`/`apiVersion`；不兼容在 `ModLoaderImpl` 以 `API` phase 报错 |
| loopHooks | `LoopHook.beforeTurn/beforeStep/afterStep/afterTurn` + 有序 `LoopHookRegistry`；异常隔离 |
| LOOP-1 重试 | `completeWithRetry`：仅对 `ProviderException.retryable()`（408/409/429/5xx）退避重试，指数退避 + jitter，`provider.retries/backoff_ms/backoff_max_ms`；工具超时不重试 |
| 压缩 | `ContextCompactor` 在消息数超过 `limits.compact_after_messages` 时保留尾部 `compact_keep_messages`，切在 user 边界并插入省略说明；不落盘 |
| TOOL-1 schema | 调度器先检查必填参数，再能力/审批/预算；必填缺失直接 `tool_error` |

测试：`RegistryTest`、`LoopHookTest`、`RetryTest`、`CompactionTest`。

未闭环：

- **Registry 还没有 namespaceTemplates / contextAssembler / policies 表**；loopHooks 覆盖了结构性钩子，策略目前只有 approval。
- **压缩不是模型总结**：为了不额外消耗一次模型调用，当前是丢最旧消息 + 说明；需要模型总结时可以替换 `ContextCompactor`。
- **mod disable**：仍然只加载、不卸载（设计允许）。
- **Q38**（manifest 覆盖模型档位是否受允许集/预算约束）仍未决，当前允许覆盖。

## 11. P5 落地记录（2026-11-01）

| 设计 | 落点 |
| --- | --- |
| EVT-1/LOCAL-1 观测 | `Observation.events(sessionId, fromTurn)` 回放 committed 事件；`Observation.subscribe` 轮询 `events` 表做 CDC，只在 turn 事务提交后可见；`[observe] poll_ms` |
| 审计 | `ch.predmet.jarness.audit` logger：spawn/deny/restart、tool 调用、executor call/result、approval |
| LIFE-1 restart | `RestartPolicy.ON_FAILURE/ALWAYS`：失败退出后按 `supervisor.restart_delay_ms` 重建同一 session，`max_restarts` 限制；`AGENT_RESTARTED` 事件 |
| 生命周期 | `AgentState` 增补 `SUSPENDED` / `DEACTIVATING` / `RESTARTING`；`Supervisor.suspend` = checkpoint + SUSPENDED |
| 冷观测 | 明确 v1 不做（设计如此） |

测试：`ObservationTest`、`RestartTest`。

**明确未实现（开放问题，不是遗漏）**：

- **remote executor / TLS / enrollment**：设计把它标为可选，且 Q36（远端 TLS 是否强制、secret 轮换）、Q37（帧格式）仍是待定；本地 executor 已完整，远端沿用同一协议的扩展点留待 Q36 定案。
- **Q38**：模型档位覆盖的约束。
- **per-stream credit / progress 帧**：本地调用是请求-结果多路复用，没有流控和进度回调。
- **净网工具**：`web_search/web_fetch` 仍在 daemon 内（NET 能力门在工具级），executor 的 net.fetch 与 NET scope 强制未做。
- **checkpoint 自动阈值**：支持 `session.checkpoint_every`，默认关闭；设计允许阈值后调。

## 12. 对照不变量

| 不变量 | 状态 |
| --- | --- |
| I1 每个 agent 在树中且绑定唯一 ns | 是；super 也是树节点，root 挂 super |
| I2 子 agent 只能经 spawn/fork | 是；工具走 `spawnFrom`，裸线程不创建 agent |
| I3 能力沿树只减不增 | 是；`requested ∩ parent ∩ ns`，resume 再与镜像 caps 求交 |
| I4 镜像唯一权威、turn 事务 | 是；`LocalSessionStore` 单事务 commit，未提交 turn 崩溃即丢 |
| I5 副作用只从 core/executor 强制点 | 部分；默认 relay 从 executor 出，`execution.mode=local` 仍可在 daemon 内 |
| I6 spawn=exec / fork=fork | 是；fork 复制消息（旧 turn 的镜像复制待细化） |
| I7 ModelProfile 属 ns | 是；模板无模型字段，manifest/request 可覆盖（Q38） |
