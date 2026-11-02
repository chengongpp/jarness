# Jarness 架构走查与优化记录（2026-11-02）

> 对照：`docs/harness-design.md` v1.3，以及用户在 v1.4 对 IPC 与 namespace cwd 的修正。
> 起点：`09a3a46`。本文是当前状态报告；`design-gap-analysis.md` 的 P0–P5 段落保留为历史记录。
> 方法：两份独立只读走查 + 完整调用链核对 + 仓库外最小复现 + 本轮回归测试。

## 1. 结论

实现已经具备监督树、namespace、capability、turn 事务、Rust executor、mailbox、Shm/Channel、
super agent 和 mod 装载的主体形状，但还不能称为完整的 v1.3 运行时。

**最大的剩余架构缺口不是某个工具，而是控制面与恢复面**：当前每条 CLI 命令都会创建并关闭一个
standalone JVM runtime，不是长驻 daemon 的客户端；启动时也不会按 BOOT-1 恢复监督树。平台工具
因此只能管理当前进程，不能管理另一个 TUI/CLI 进程。

本轮优先修复了可复现的安全与正确性问题，而不是仅把缺口改写成文档。

## 2. 本轮已修复

| 提交 | 修复 | 主要回归 |
| --- | --- | --- |
| `7f4435b` | 同 runtime 的 image 单写者；禁止跨 ns 强迁 image；toolSlots/depth 预算衰减；删除绕过 spawn 的 `Supervisor.register` | 双 writer、跨 ns、slots/depth |
| `3a48341` | namespace 级 executor id/kind 路由；remote EOF 标死；重复在飞 call id 拒绝；relay call id 按 agent 隔离 | local/remote 同名工具、断线、重复 id |
| `c033fa7` | 所有 mutating 工具进入审批；API 级 RELAY 由 dispatcher 路由；模型工具集与 caps 求交；provider/loopHook 使用 turn 快照；submission 有界；mailbox 优先且 async 投递有背压 | API-only RELAY、IPC 审批、动态 hook、async turn |
| `af2965f` | executor 使用 cap-std 目录句柄；修 sibling-prefix 与 symlink；glob/grep 不跟随 symlink；workdir 真实路径；redirect 逐跳授权；HMAC 覆盖完整执行上下文 | FS 读写/搜索/exec、redirect、relay 协议 |
| `280c928` | Shm 共享状态与 per-owner handle 分离；关闭单句柄不再关闭共享状态；查找 handle 也检查 IPC cap | 写入归属、独立句柄、持久化 |
| `153d4d7` | commit 失败不再返回成功；失败 writer 停止；checkpoint 与 turn 串行；suspend 先停 worker 再 checkpoint；损坏 checkpoint 拒绝加载；逻辑 summary 不随压缩缩水 | store outage、resume、checkpoint、损坏镜像 |
| `361ab27` | preset 默认模板/模型在 namespace 构建前生效；TUI 初次 spawn 失败会退出；relay 后台 bash 接入 JobRegistry | preset、TUI、后台任务 |
| `5470404`, `5d5e95a` | 删除重复 JsonConfigView；observation/CLI sessions 支持独立 ns store；PLATFORM/NAMESPACE 只能由 pid 0 持有 | 独立 store CDC、平台授权 |
| `2b8fe3f` | 显式 LOCAL 模式也检查 FS/NET resource scope；web_fetch 逐跳检查 redirect；web_search 检查后端域名 | LOCAL sibling/symlink/redirect |
| `21d18db` | restart 保留完整创建参数；fork 保留 manifest 字段；transport/parse 可重试；模型请求受剩余 token/deadline 约束 | retry、fork、maxTokens |

## 3. 明确未实现或仅部分实现

### P0：控制面与启动恢复

| 主题 | 当前事实 | 影响与建议 |
| --- | --- | --- |
| 长驻 daemon / 客户端协议 | `RunCommand`、`SuperCommand`、`SessionsCommand` 都各自 build/start/close runtime；没有 daemon listener 或 thin client | 不同命令看不到同一监督树；`super`/`ns_*` 只管理当前进程。需要认证控制协议、daemon 生命周期和 CLI attach；不能靠共享 SQLite 假装同 runtime |
| 跨进程 image ownership | 单 runtime 已拒绝双 writer，但两个 standalone JVM 仍可恢复同一 session | 在 daemon 化之前至少需要 SQLite lease + 进程身份/过期策略；根本方案仍是单一 writer daemon |
| BOOT-1 自动恢复 | start 只装 mod、起 executor、发 RUNTIME_START；不会扫描 image 或重建 parent/children | image 目前只能显式 resume，且挂回 super。需要版本化 parent/depth/manifest/cap DAG，并定义自动续跑与 Suspended 的边界 |
| pending async 对账 | image 仍只记录 callId/tool，不保存完整 args/provider/idempotency；resume 对 pending 默认拒绝，force 也不重连 | 运行中 mailbox 满已不再丢结果，但崩溃恢复仍不完整。需要持久 PendingCall + executor query/replay + delivery ack/outbox |

### P1：一致性、扩展和调度

| 主题 | 当前事实 | 下一步 |
| --- | --- | --- |
| fork 的“整份镜像” | 仍复制 messages，不复制完整 turn/pending 历史，也未在父 worker 的 committed boundary 做原子快照 | 由 SessionStore 在父 agent 锁内复制完整 committed image；补 pending fork |
| resume 依赖兼容性 | 已校验 template/schema/tool 是否存在、mod id；但未比较 mod version，loopRef 只是字符串，tool schema 无版本 | 引入 loop registry/schema 指纹与明确兼容策略 |
| Registry 扩展面 | tools/templates/model providers/loopHooks 可 pin；缺 policies/contextAssembler/namespaceTemplates；execution provider 使用活连接表 | 物理连接可在断线时失效，建议把“声明快照”与“物理路由”分开，而非永久 pin 死连接 |
| 动态 mod 原子性 | 没有 supervisor 串行命令队列；instrument apply 失败可能留下部分注册；version 与内容不是一次原子发布 | stage 所有注册、失败后回收、一次性发布不可变 RegistryState |
| SessionStore/模板扩展承诺 | store 在 mod 加载前直接 new LocalSessionStore；没有 bootstrap store SPI；AgentTemplate 没有可用 loopRef/defaultCaps | 做两阶段 bootstrap，或删除“mod 可替换 store”的承诺；模板 caps 与 spawn 求交 |
| 层级预算 | admission 已校验全部轴，单请求也收窄 maxTokens/deadline；但父子仍无 reservation/归并，多个子节点可重复占用父额度 | 引入 reservation/lease 与实际消耗归并，而不只是 fitsWithin |
| 调用级 SYNC/ASYNC | 当前由 `Tool.mode()` 静态决定，ToolInvocation 无 mode/deadline/idempotency 选项 | 增加调用级选项，并声明工具支持的模式 |
| executor 背压与观测 | 无 per-stream credit/progress；Rust 每 call 一个 OS thread；模型/executor 无共享并发槽 | 增加有界 worker/queue、progress、credit；当前异步 mailbox 背压不等于 executor 流控 |
| provider 路由策略 | 已支持 namespace 内 provider id/kind 顺序；不支持 labels/load，也没有把 `ExecutionProvider.capabilities()` 纳入匹配 | 增加 provider labels/load 快照与工具 requiredCaps 匹配，策略交给 Registry 扩展点 |
| capability 撤销 | 下一调用/下一 step 会失效，但在飞 executor 调用没有按 capId 级联取消 | 调用表记录 cap grant -> callId，撤销时发送 cancel |
| ASK 审批 | 所有 mutating 工具已进入策略，但 ASK 仍是同步 handler 阻塞虚拟线程 | 将 approval decision 作为 mailbox/control 事件，原 turn 只保留 pending |
| 持久事件面 | SQLite events 只有 turn/message；spawn/revoke/ns/tree 仍主要是 HookBus/logger | 持久化生命周期与能力事件，并定义它们与 image 的事务顺序 |
| super 控制来源 | pid 0 能力唯一性已强制；但 lazy 创建不是 profile 启动策略，kill 也没有外部来源签名 | daemon 控制面落地时一起定义身份与授权，不在 standalone 工具里伪造来源 |

### P2：明确的语义差异

- **CANCELLED turn 会 commit**：当前把中止输入和半截工作写入权威历史；设计 IMAGE-2 的“中止则丢弃”与此不同。
  这是需要产品决策的差异，不能在代码里继续默认双方等价。
- **point-in-time resume**：`ImageRef` 的旧 checkpoint 会被明确拒绝，避免静默加载最新；真正的历史镜像载入尚未实现。
- **context compaction**：是确定性的窗口裁剪，不是模型总结。
- **Q36/Q38**：enrollment secret 轮换、manifest 模型覆盖允许集仍未闭环。
- **web_search**：仍由 daemon 内 LOCAL 实现；web_fetch 可走 executor。两者都有 NET host 检查。

## 4. 冗余、混乱与旧架构残留的处理

| 项 | 结论 |
| --- | --- |
| `AgentRequest` vs `AgentManifest` | 保留前者作为 CLI/工具的 ergonomic wrapper，策略性 spawn 用后者；restart 已不再丢失 request 字段。长期可把 manifest 直接嵌入 request，避免双配置源 |
| 合成根 vs 真 super | 已收敛为 pid 0 真 super 语义；旧注释与公开 register 旁路已删除 |
| `Registry.Snapshot` | 不再只有 tools 有消费者；provider/loopHooks 已接入。executionProviders 仍是物理活路由，不应伪装成可长期保存的连接快照 |
| `SqliteSessionStore` | 只是 LocalSessionStore 的兼容别名，无独立行为；可保留，不属于架构冲突 |
| `LocalExecutionProvider(kind=REMOTE)` | 同类协议实现复用是合理实现形状，不要求为 remote 另写一套 |
| `Agent.workspace()` / `ExecRequest.workspace` | 作为 namespace cwd 的兼容命名保留；唯一来源已是 `NamespaceMetadata.cwd`，没有 per-subagent 目录 |
| 默认 relay 的 Jobs UI | 不再残留不可用入口；JobRegistry 等待 executor 调用，执行副作用仍在 executor |
| Blackboard 命名 | 代码/API 使用 Shm；目标文档中的 Blackboard 表示同一原语。v1.4 明确 shm 是 ns 级共享状态，creator 退出不清空，channel 才 owner-bound |

## 5. 不应误报为缺口

- 正常 turn commit 的确是单 SQLite 事务；问题是本轮已修的失败传播与 checkpoint 边界，不是“没有事务”。
- 缓冲整个 turn 再原子 commit 是当前明确的实现取舍，不要求逐步 write-behind WAL。
- JSON-lines 是 Q37 的既定起步方案。
- 跨 daemon IPC、COW、ADOPT/DETACH、速率限制、冷观测属于设计明确暂不做，不列实现遗漏。

## 6. 推荐后续顺序

1. **daemon + 控制协议 + 跨进程 image lease**，先消除多 JVM writer 与“平台工具管理临时进程”的错觉。
2. **版本化 AgentImage + BOOT-1 拓扑恢复 + pending async 对账**。
3. **完整 fork、层级预算 reservation、撤销时在飞取消**。
4. **bootstrap Registry 与原子动态加载**，补 loop/context/policy/namespaceTemplate 扩展。
5. **调用级 async、executor/model 并发槽、credit/progress、labels/load 路由**。
6. **持久监督/能力事件与 ASK mailbox 化**。

每一阶段都应做真正的 daemon 进程重启/崩溃测试，而不只是同 JVM 内 close/reopen store。
