# Jarness 设计决策记录

本文档简要记录在实现 `draft/draft_202610141032.md` 时，对草案中未明确（unclear/ambiguous）之处所做的设计与选择，以及理由。

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

## 4. 可变事件载荷与活注册表是有意为之

草案明确"没有 FP 的无副作用洁癖"。因此 hook 事件（RequestEvent/ToolCallEvent/MessageEvent/ResponseEvent）
是可变对象，钩子就地改写；ToolRegistry/AgentTemplateRegistry/ProviderRegistry 运行时可写——
这正是 Full 模板"运行时手搓 template、动态加 mod"场景的基础。

## 5. base dir 与 agent workspace 严格分离

base dir（默认 `$HOME/.local/share/jarness`）只放 `mods/`、`config/`、`presets/`、`sessions.db`、
`workspaces/`。每个 agent 的 workspace 默认是 `<base>/workspaces/agents/<agentId>` 下的新目录，
也可以由创建方显式指定，但永远不等于 base dir。工具的相对路径一律相对 agent workspace 解析。

## 6. Supervisor 最先起、最后退

`Jarness.start()`：先 `supervisor.start()`（哪怕永远 0 个 agent 它也活过），再 bake mods，
最后发 `RUNTIME_START`。`close()`：先发 `RUNTIME_STOP`，停 supervisor（停所有 agent），
再关 provider/session store/jobs，最后才 `modLoader.close()`（它持有 mod 的 classloader）。
ModLoader 不随 bake 结束而销毁，因为它还要承担动态加载。

## 7. Session 存储默认 SQLite，JDBC URL 可配

`config/jarness.toml` 的 `session.store` 是完整 JDBC 串，默认 `jdbc:sqlite:<base>/sessions.db`。
想换后端（如 Postgres）只需改配置或让 mod 注册其它 SessionStore。
消息体以 ContentBlock 列表的 JSON 形式落库，reasoning / tool_call / tool_result 在会话恢复后不丢失。

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
