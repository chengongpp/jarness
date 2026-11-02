# Jarness Mod 开发手册

本文档面向 mod 开发者，覆盖：mod 的概念模型、工程搭建、`jnmods.toml` 清单、instrument、
工具/模板/Provider/钩子的注册方法、配置、依赖管理、打包安装、动态加载与调试。
阅读前建议先浏览 [README](../README.md) 与 [design-decisions.md](design-decisions.md)。

---

## 1. 概念模型

Jarness 参考 NeoForge 的模组体系，但本体是轻量的 server runtime：

```
┌─ Mod（模组）─────────────────────────────────────────┐
│  一个 jar（或 CLASSPATH 上的一个条目），包含：          │
│    · META-INF/jnmods.toml   静态清单（id/版本/依赖）    │
│    · 一个 @Mod 入口类        实现 JarnessMod           │
│    · 任意业务代码                                    │
│                                                     │
│  Mod 对运行时的一切调整都叫 Instrument（插桩）：        │
│    · 有名字（mod 内唯一）、有加载策略（自动/手动）        │
│    · 有优先级（小的先应用）、可被 preset 单独开关         │
└──────────────────────────────────────────────────┘
```

关键规则：

1. **`JarnessMod.register(ModContext)` 只负责"声明"**。你在 `register` 里通过
   `ctx.instruments().register(...)` 声明若干 instrument；真正接触运行时注册表
   （tools/templates/providers/hooks）的代码写在 instrument 的 `apply(InstrumentContext)` 里。
   这样 preset 才能按名字开关你的每一部分能力，装载诊断也能精确告诉用户"哪一段生效了"。
2. **装载顺序**：发现（DISCOVERY）→ 解析清单（PARSE）→ 依赖求解（RESOLVE）→
   实例化并调用 `register`（INSTANTIATE/REGISTER）→ 按 preset 决定启用哪些 instrument
   （PRESET）→ 按序应用（APPLY）。任何一步出错都会产生带 phase、文件、原因、修复建议的
   `ModLoadError`。
3. **两类 mod**：
   - **系统 mod**：直接在 CLASSPATH 上，通过 Java SPI
     `META-INF/services/ch.predmet.jarness.api.mod.ModProvider` 声明（不是 `jnmods.toml`，
     后者在 shade 成单 jar 时会同名互相覆盖）。运行时的 `jarness.core`、`jarness.tui` 就是系统 mod。
   - **外置 mod**：打成 jar 放进 `<base>/mods/`，由 `jnmods.toml` 定义。三方 mod 都属于这类。
4. **加载是永久的**：支持运行时动态加载（`mod_load` 工具 / `ModLoader.loadJar`），
   不支持卸载。设计 mod 时假设自己一旦被加载就会活到运行时结束。

base dir 默认 `$HOME/.local/share/jarness`：`mods/` 放 jar，`config/` 放配置，
`presets/` 放预设。下文用 `<base>` 指代。

---

## 2. 工程搭建

一个最小外置 mod 的 Maven 工程：

```xml
<project ...>
  <groupId>com.example</groupId>
  <artifactId>jarness-hello</artifactId>
  <version>1.0.0</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
  </properties>

  <dependencies>
    <!-- 只依赖 API；provided 因为运行时已经带着它 -->
    <dependency>
      <groupId>ch.predmet.jarness</groupId>
      <artifactId>jarness-api</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <scope>provided</scope>
    </dependency>
  </dependencies>
</project>
```

目录：

```
src/main/java/com/example/hello/HelloMod.java
src/main/resources/META-INF/jnmods.toml
```

注意事项：

- **只面向 `jarness-api` 编程**。`jarness-core` 的实现类不对外承诺稳定，且外置 mod 的
  classloader 以 API 为父加载器——你只能链接到 API 包（`ch.predmet.jarness.api.*`）。
- 目标版本是 **Java 21 LTS**（`--release 21`）。record、sealed interface、pattern matching、
  虚拟线程都可以用。
- 如果你的 mod 需要第三方库（比如 OkHttp），直接 shade 进自己的 jar。每个外置 mod 有独立的
  `URLClassLoader`，不同 mod 携带不同版本的同一个库互不影响；但不要用 mod 去替换运行时的
  Jackson/slf4j——API 里暴露的类型（`JsonNode`、`Logger`）来自父加载器。

---

## 3. `jnmods.toml` 清单

外置 mod 必须有 `META-INF/jnmods.toml`：

```toml
# 必填：mod id，全局唯一，小写字母/数字/点/横线
id = "jarness.hello"
# 可选：展示名、版本、描述、入口类全限定名
name = "Hello Mod"
version = "1.0.0"
description = "A minimal example mod."
entrypoint = "com.example.hello.HelloMod"   # 可省略，见下

# 依赖声明（可多个）
[[dependencies]]
modId = "jarness.core"
version = "*"            # 版本区间，见 §8
kind = "required"        # required | optional | incompatible
ordering = "after"       # none | before | after
reason = "needs the builtin tools"   # 可选，出错时展示给用户
```

**entrypoint 省略规则**：省略时，装载器扫描 jar 里被 `@Mod` 标注且实现 `JarnessMod` 的类；
恰好一个则用之，零个或多个都会报错并提示。写上 entrypoint 更明确，推荐。

**没有 `jnmods.toml` 的 jar**：装载器跳过并记一条 warning（"not a jarness mod, skipped"）。
**损坏的 jar / 清单解析失败**：PARSE 错误，整个装载批次失败——这是有意的，NeoForge 式原则
"宁可清楚报错，不可带病启动"。

---

## 4. Mod 入口类

```java
package com.example.hello;

import ch.predmet.jarness.api.annotation.Mod;
import ch.predmet.jarness.api.mod.JarnessMod;
import ch.predmet.jarness.api.mod.ModContext;

@Mod("jarness.hello")   // id 必须与 jnmods.toml 一致
public class HelloMod implements JarnessMod {

    @Override
    public void register(ModContext ctx) {
        ctx.instruments().register("greeter", true, 500,
                "Contributes the hello_greet tool.",
                ic -> ic.tools().register(new GreetTool()));
    }
}
```

`register` 里**不要做**这些事：直接注册工具/模板（绕过 instrument，preset 就管不到你了）、
开线程干长活、读自己的配置然后立刻用（配置应在 instrument apply 时读，见 §7）。
`register` 抛异常 = REGISTER 错误，mod 装载失败。

### 4.1 ModContext 提供什么

| 方法 | 说明 |
| --- | --- |
| `descriptor()` | 自己的清单元数据 |
| `modId()` | 自己的 id |
| `runtimeConfig()` | 运行时配置只读视图（`ConfigView`，点分路径取值） |
| `modConfig()` | 自己的配置（`<base>/config/<modId>/<modId>.toml`），见 §7 |
| `configDir()` | 自己的配置目录（按需创建），可写 |
| `baseDir()` | 运行时 base dir（只读使用，别往里写东西） |
| `modFile()` | 自己的 jar 路径；系统 mod 为 `null` |
| `instruments()` | instrument 注册器 |
| `log()` | 已带 mod id 标签的 slf4j Logger |

### 4.2 注解式 instrument

简单场景可以不写 `ctx.instruments().register(...)`，直接在入口类上标方法：

```java
@Mod("jarness.hello")
public class HelloMod implements JarnessMod {

    @Override
    public void register(ModContext ctx) {
        // 可以留空；也可以两种风格混用
    }

    @Instrument(value = "greeter", autoEnable = true, priority = 500,
                description = "Contributes the hello_greet tool.")
    void greeter(InstrumentContext ic) {
        ic.tools().register(new GreetTool());
    }
}
```

要求：实例方法、返回 `void`、恰好一个 `InstrumentContext` 参数，否则 REGISTER 错误。
两种风格等价；编程式适合运行期才能确定名字/数量的 instrument，注解式适合静态声明。

---

## 5. Instrument 详解

instrument 是 preset 与诊断的最小管理单位。**把 mod 的每一块能力都拆成 instrument**：
一个工具组一个、prompt 调整一个、钩子组一个……参考 `jarness.core` 的拆法
（`tools.fs` / `tools.exec` / `tools.web` / `tools.agent` / `prompt.baseline` / `templates`）。

### 5.1 三要素

- **名字**：mod 内唯一，全局限定名是 `modId:name`（preset 里两种写法都认）。
  命名习惯用点分层：`tools.fs`、`prompt.baseline`、`tui.frontend`。
- **加载策略**：`autoEnable = true` 自动启用（默认）；`false` 手动启用，只有 preset 显式
  打开才应用（如 `jarness.tui:tui.frontend`）。
- **优先级**：整数，小的先应用。注册表内的总序 = (priority, mod 装载序, 名字)。
  建议：基础设施 100–199，工具 200–299，prompt/上下文 300–399，模板 400–499，UI 900+。

### 5.2 InstrumentContext 提供什么

`runtime()`、`tools()`、`templates()`、`hooks()`、`providers()`、`runtimeConfig()`、
`modConfig()`、`modId()`、`instrument()`、`log()`。这些注册表都是**活的、可变的**——
Jarness 不追求无副作用，直接往里注册即可。

apply 抛异常 = APPLY 错误（错误信息里带 instrument 全名），该 instrument 之后的应用继续，
但本批次报告失败。

---

## 6. 注册能力 cookbook

### 6.1 注册工具

```java
public class GreetTool implements Tool {

    private final ToolSpec spec = ToolSpec.readOnly("hello_greet",
            "Greet someone by name.",   // 1-3 句，给模型看的
            Map.of("name", ToolParameter.required("string", "Who to greet.")));

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) {
        String name = invocation.requireString("name");   // 缺失时抛 ToolException
        return ToolOutcome.ok("Hello, " + name + "!");
    }
}
```

要点：

- **命名和参数风格对齐 deepseek-harness**（`read`/`write`/`edit`/`glob`/`grep`/`bash`，
  参数 `file_path`、`old_string` 等）。模型对这些词是后训练过的，新工具沿用这套词汇
  能白嫖工具调用稳定性。
- `ToolInvocation` 的参数已经按 JSON 解析好：`requireString` / `string(k, fallback)` /
  `integer(k, fallback)` / `bool(k, fallback)` / `stringList(k)` / `table(k)`。
  模型把数字写成字符串是常态，accessor 对此宽容。
- `ToolException` = 给模型看的可恢复错误（参数错、路径越权等）；其它异常会被循环包成
  `tool failed: ...` 的失败观测。**别让异常裸奔**。
- 工具可以声明所需能力：覆盖 `requiredCaps()` 返回 `Set<CapKind>`，或者用 core 的
  `CapabilityTool.of(delegate, CapKind.FS_READ)` 包装。agent 的能力集合不包含对应 kind 时，
  调用会在工具体执行前被拒绝（模型看到 `capability denied: ...`）。当前是类型级门；
  路径/域名级 scope 由 P2 的 execution provider 强制。
- 路径一律过 `ctx.resolve(path, intent)`：`intent=false` 只解析；`intent=true` 会按沙箱
  模式（`read-only` / `workspace-write`（默认） / `full-access`）拦截 namespace cwd 外的写入。
- 读写文件前想清楚 `readOnly` 标志：只读工具允许在只读沙箱里用，也允许并行调用；
  实现必须是线程安全的。
- 单条观测超过 `limits.tool_output_max_chars`（默认 120000）会被循环截断，自己不用管，
  但输出里带上"已截断"提示对模型更友好。
- 长任务用后台 job：`ctx.jobs().start(label, body)`，`body` 里通过 `JobHandle.write` 推输出、
  轮询 `handle.cancelled()` 响应取消；告诉模型用 `job_output`/`job_kill` 收尾。

### 6.2 注册 Agent 模板

```java
ic.templates().register(SimpleAgentTemplate.builder("web-bot")
        .name("Web Bot")
        .description("A bot that only drives a web system.")
        .systemPrompt("You operate the ACME web console and nothing else.")
        .tools("web_fetch", "mymod_console_click")   // 工具名按提示词顺序排列
        .maxSteps(32)                                 // 可选：-1 = 用运行时默认
        .temperature(0.2)                             // 可选
        .version("2")                                 // 可选：镜像恢复时校验
        .dynamicMods(false)                           // 只有 Full 该是 true
        .build());
```

模板不再携带模型（v1.3 的 I7 / TEMPLATE-1）：模型由 agent 所在 namespace 的
`ModelProfile` 决定，spawn 时可由 `AgentRequest.model` 或 `manifest.modelProfileId` 覆盖。
想换模型，改 namespace 配置（`[namespaces.<id>] model = "..."`），而不是改模板。

这正是草案里"手搓小 bot"场景的另一半：Full agent 先 `mod_load` 你的 mod，
再 `template_register` 一个只含你的工具的模板，再 `agent_spawn` 出小 bot。
你的 mod 也可以反过来主动注册模板，让预设/用户直接 `-t web-bot` 使用。

### 6.3 注册 Provider

```java
ic.providers().register(ProviderFactory.create(profile));   // core 的工厂；或自己实现 Provider
```

自己实现 `Provider` 时：实现 `complete(ChatRequest)` 与 `complete(ChatRequest, StreamSink)`，
把响应归一化成 `Message`（Text/Reasoning/ToolCall 块）+ `Usage`；失败抛 `ProviderException`
（带 HTTP 状态码，`retryable()` 由状态码推导）。参考 `jarness-core` 里三个适配器的写法。

### 6.4 挂钩子

钩子挂在 `HookPoint` 上，同点按 (priority, 注册序) 执行。载荷是可变事件对象，
改写它是正经用法：

```java
ic.hooks().on(HookPoint.BEFORE_TOOL_CALL, "mymod:guard", 100, ctx -> {
    ToolCallEvent e = ctx.payload(ToolCallEvent.class);
    if ("bash".equals(e.invocation().name())
            && e.invocation().requireString("command").contains("rm -rf /")) {
        e.skip("dangerous command refused by mymod:guard");   // 模型会读到这条拒绝
    }
});

ic.hooks().on(HookPoint.BEFORE_REQUEST, "mymod:trim", 900, ctx -> {
    RequestEvent e = ctx.payload(RequestEvent.class);
    // e.setMessages(...)/e.setSystem(...)/e.setTools(...)/e.cancel("...") 都可以
});
```

全部挂点（见 [HookPoint.java](../jarness-api/src/main/java/ch/predmet/jarness/api/hook/HookPoint.java)）：

| 分组 | 挂点 | 载荷 |
| --- | --- | --- |
| 运行时 | `RUNTIME_START` / `RUNTIME_STOP` | `JarnessRuntime` |
| 模组 | `MODS_LOADED` / `MOD_LOADED` / `INSTRUMENT_APPLIED` | `LoadReport` / `ModEvent` |
| Agent | `AGENT_CREATED` / `AGENT_STARTING` / `AGENT_STOPPED` / `AGENT_SIGNAL` | `AgentEvent` |
| 监督 | `SPAWN_DENIED` | `SpawnEvent`（parent / namespace / manifest / 拒绝原因） |
| 会话 | `SESSION_CREATED` / `SESSION_ENDED` | `SessionEvent` |
| 循环 | `TURN_START` / `STEP_START` / `TURN_END` | `TurnEvent` |
| 请求 | `BEFORE_REQUEST` / `AFTER_RESPONSE` | `RequestEvent`（可变）/ `ResponseEvent`（可变） |
| 工具 | `BEFORE_TOOL_CALL` / `AFTER_TOOL_CALL` | `ToolCallEvent`（可 skip/改写） |
| 消息 | `MESSAGE_APPENDED` | `MessageEvent`（可改写/丢弃） |

钩子抛异常会被隔离（记日志，不影响同点后面的钩子）；`ctx.cancel(reason)` 用于可取消的挂点
（语义上第一个 cancel 生效），`ctx.stopPropagation()` 阻止同点后续钩子。

### 6.5 按 agent 定制（AgentContext）

全局钩子 + 载荷过滤 = 按 agent 定制。惯用法：在 `AGENT_CREATED` 里给新 agent 挂私有配置：

```java
ic.hooks().on(HookPoint.AGENT_CREATED, "mymod:per-agent", 100, ctx -> {
    Agent agent = ctx.payload(AgentEvent.class).agent();
    agent.ctx().systemPromptSection("mymod.rules", 800,
            () -> "Follow the ACME console etiquette at all times.");
    agent.ctx().userContext("mymod.state", 800,
            () -> "current page: " + MyState.currentPage(agent.id()));
    agent.ctx().denyTool("bash");                    // 收窄可见工具
    agent.ctx().put("mymod.session", new MyState()); // 随 agent 生命周期的草稿袋
    agent.ctx().on(HookPoint.TURN_END, null, 1000, c -> MyState.bump(agent.id()));
});
```

- `systemPromptSection(name, order, supplier)`：名字幂等（同名覆盖），supplier 每次拼提示词
  时重新求值，所以可以放动态内容。order 建议：模板正文之后、环境（900）之前的自定义内容用
  700–899。
- `userContext(name, order, supplier)`：每个请求末尾以一条不落盘的 `# Context` 用户消息
  重新陈述，适合"当前页/当前焦点"这类易漂移的状态。
- `restrictTools` / `allowTool` / `denyTool` / `visibleToolNames()`：工具可见性是 per-agent 的，
  不影响注册表。

### 6.6 能力、namespace 与子 agent

agent 不是"平铺的对象"，而是监督树节点：

```java
Agent a = ctx.payload(AgentEvent.class).agent();
a.pid();              // supervisor 分配，全局唯一
a.parentPid();        // 0 = super agent（平台主体）
a.depth();            // 根 agent = 1
a.namespaceId();
a.capabilities();     // 当前持有的 CapabilitySet（可被 revoke 收窄）
a.budget();           // 剩余 token / deadline / tool slots / spawn depth
a.manifest();         // 创建时的清单：template / loop / restart / orphan / model profile
```

从 mod 创建子 agent 时，用 `runtime.spawnFrom(parent, request)`（`subagent` / `agent_spawn`
内部就是这么做的），不要用 `runtime.spawn(request)` 造第二个根。前者会：

1. 把 child 挂到 parent 的 pid 下，并继承 parent 的 namespace；
2. 用 `requested ∩ parent.caps ∩ ns.defaults` 算 effective 能力；
3. 校验 child 预算 ≤ parent 剩余，且 parent 持有 `spawn` 能力；
4. 默认把 parent 的 `maxSpawnDepth - 1` 传给 child。

拒绝会以 `SpawnDeniedException`（`reason()` 五类 + 模板/模型两类）抛出，同时发
`SPAWN_DENIED` 事件。要让你的 agent 能跨 namespace，必须显式拿到 `CapKind.NAMESPACE`；
默认 namespace 只给本地能力（`fs.read` / `fs.write` / `exec` / `net` / `model` / `spawn` /
`extend` / `ipc`），`platform` 保留给 super agent。

**工作目录**：`AgentRequest` 不再有 `workspace` 字段。每个 agent（包括 `subagent` /
`agent_spawn` 创建的子 agent）都使用 `agent.namespace().cwd()`，也就是
`NamespaceMetadata.cwd`。默认 namespace 是 `<base>/workspaces/default`，额外 namespace 是
`<base>/workspaces/<id>`；配置里用 `[defaults] cwd` / `[namespaces.<id>] cwd` 覆盖。不要再为子
agent 创建私有目录；同一 namespace 内的相对路径和文件副作用本来就是共享的。

### 6.7 镜像、turn 事务与恢复

如果你自己驱动 `Session`（而不是用 `AgentImpl`），要遵守同一套持久化契约：

```java
Turn turn = session.startTurn(input);     // 只在内存里建 turn
session.append(Message.user(input));      // running turn 期间只缓冲
session.append(Message.toolResult(...));  // 同上
turn.finish(TurnState.COMPLETED, text, null);
session.commitTurn(turn, imageMetadata);  // 唯一 durability 边界：单事务落库
```

- `session.saveTurn(turn)` 在 running 状态是 no-op；终态才等价于 commit（不带 image metadata）。
- 崩溃/进程退出时未 commit 的 turn 整体消失；不要指望 `messages()` 里的在飞消息已经落盘。
- `namespace.store().image(sessionId)` 返回镜像元数据（deps / budget / caps / checkpoint）；
  `store.checkpoint(session, image)` 写快照并压缩旧 delta。
- 想从镜像恢复：`Supervisor.resume(ImageRef)`，或者 `AgentRequest.sessionId(...)` 再让
  `AgentImpl` 做依赖校验；不匹配时用 `forceResume(true)` 降级。
- 工具/mod/template 的依赖清单由 `AgentImpl` 自动写；自定义 agent 需要自己填
  `ImageMetadata.deps()`（template id+version、loop、mods、tools）。

### 6.8 执行器、工具形态与审批

工具默认 `form() == LOCAL`（daemon 内执行）。要交给 Rust executor：

```java
Tool tool = CapabilityTool.of(new MyTool(), CapKind.FS_WRITE);
ic.tools().register(RelayTool.of(tool, "fs.write"));   // 模型仍看到 MyTool.spec()
```

- `relayName()` 是 executor 的 internal 名称；`exec.run` / `fs.*` 由内置 executor 提供。自定义 executor
  工具需要同时改 `executor/src/main.rs` 的 `dispatch` 与 `TOOLS`，否则 `execution_failed: unknown ...`。
- `ExecRequest` 带 `workspace/sandbox/caps`；这里的 `workspace` 就是调用者所在 namespace 的
  cwd。executor 再做一次能力和沙箱校验；不要指望「daemon 已经校验过」而跳过 executor 侧检查。
- `Tool.mode()` 目前只用 `SYNC`；`ASYNC` 的完成事件在 P3 的 mailbox 里接。
- 审批：`runtime.approvals().register("tui", (request, timeout) -> ...)`。`[approval] policy = "ask"`
  时没有 handler 就 deny；`never` 只放行 `allow` 列表里的工具名。审批发生在能力门之后、执行之前。

### 6.9 协作原语与平台面

- **mailbox**：`agent.mailbox()`；`agent.deliver(MailboxMessage.data(...))` 或
  `runtime.supervisor().deliver(id, text)`。满时 `offer` 返回 false，按背压处理，不要忙等。
- **shm（Shared Memory）**：`runtime.ipc().createShm(owner, "task-board")` 或
  `attachShm(owner, id)`；`read/write/compareAndSet/watch/lock`。shm 按 namespace 命名，cell 是
  “内存读 + SQLite 写穿”，daemon 重启后可按 id re-attach。创建/attach 需要 `ipc` 能力；creator
  退出不会清空共享内容，显式 `removeShm(namespace, id)` 才删除。模型侧对应 `shm` 工具
  （create/read/write/cas/delete/list/snapshot），它和 `agent_send` 都是 `ToolForm.LOCAL`，
  直接跑在 daemon JVM 里，**不会**经过 executor。
- **agent_send**：模型工具，直接把 `MailboxMessage.data(senderPid, text)` 投到目标 agent 的
  mailbox；目标 worker 会把它当作 `DATA` 消息开新 turn。同 namespace 即可；跨 namespace 需要
  `namespace` 或 `platform` 能力。
- **channel**：`runtime.ipc().createChannel(owner, capacity)`；capacity=0 是会合通道；多消费者时每条
  消息只被一个接收者取走，广播请用 topic + 每订阅者 mailbox。
- **super agent**：`runtime.supervisor().ensureSuperAgent()` 或 `jarness super`；平台工具
  `ns_*`/`daemon_status`/`agent_tree`/`agent_kill`/`agent_signal` 要求 `platform` 能力。
  普通 agent 不要试图调用这些工具，能力门会拒绝。
- **异步工具**：`Tool.mode()` 返回 `ASYNC` 即进入异步路径；当前 turn 只看到 `accepted callId`，
  完成后 mailbox 投递结果并新开一个 turn。工具实现要保证幂等，因为调用方可能重试。
- **观测**：`runtime.observation().subscribe(sessionId, consumer)`；只有 committed 事件可见。

---

## 7. Mod 配置

规范：TOML，必须放在 `<base>/config/<modId>/<modId>.toml`：

```toml
# ~/.local/share/jarness/config/jarness.hello/jarness.hello.toml
greeting = "Howdy"

[limits]
max_greet_length = 80
```

读取（在 instrument apply 时，而不是 register 时——此时配置目录一定已就绪）：

```java
ic -> {
    String greeting = ic.modConfig().string("greeting", "Hello");
    int max = ic.modConfig().integer("limits.max_greet_length", 80);
    ic.tools().register(new GreetTool(greeting, max));
}
```

`ConfigView` 是只读点分路径视图：`findString` / `string(k, fallback)` / `findInt` /
`integer(k, fallback)` / `bool` / `stringList` / `table` / `tables` / `raw()`（Jackson `JsonNode`
逃生舱）。缺文件 = 空视图，所有取值走 fallback，**不要求用户必须建配置文件**。
要在自己的配置目录里写文件，用 `ModContext.configDir()`（已建好目录）。

---

## 8. 依赖管理

`[[dependencies]]` 三要素（参照 FancyModLoader 的简化版）：

- **kind**
  - `required`：缺失或版本不满足 = RESOLVE 错误，装载失败；
  - `optional`：在则排序，不在拉倒；
  - `incompatible`：在场 = RESOLVE 错误。
- **version**：区间语法 `*`（任意）、`1.2.3`（精确）、`1.2.x`（补丁任意）、`^1.2.3`
  （同 major，major 为 0 时锁 minor）、`~1.2.3`（同 minor）、`>=1.2` 等比较符、
  Maven 区间 `[1.0,2.0)`、逗号合取 `>=1.0,<2.0`。
- **ordering**：`before` / `after` 只影响装载顺序，不影响是否装载。

求解是拓扑排序（Kahn），平局时系统 mod 优先、再按 id 字典序——同输入永远同顺序。
环、缺依赖、版本不满足、incompatible 在场，都会得到带 `hint` 的中文/英文可读错误。

---

## 9. Preset 与 mod 的关系

preset 决定"哪些 mod 装载、哪些 instrument 启用"。作为 mod 作者你要知道的：

```toml
# <base>/presets/web.toml
name = "web"
description = "Only web-oriented mods."
mods = ["jarness.core", "jarness.hello"]   # 允许列表；空 = 全部；required 系统 mod 不受限

[instruments]
"jarness.core:tools.web" = true
"jarness.hello:greeter" = true             # 全名限定
"prompt.baseline" = false                  # 也认裸名；关掉基线提示词段

[defaults]
template = "standard"
```

- 预设没点名的 instrument 按你的 `autoEnable` 走。
- 手动 instrument（`autoEnable = false`）只有被预设显式 `= true` 才应用。
- 发布 mod 时，如果某些 instrument 是"重口味"（UI、拦截器、调试器），标成手动，
  并附一段示例 preset 给用户。

---

## 10. 打包、安装、动态加载

```bash
mvn package
cp target/jarness-hello-1.0.0.jar ~/.local/share/jarness/mods/
jarness mods        # 应能看到 jarness.hello 与它的 instruments
```

运行时动态加载（无需重启，Full 模板或编程调用）：

```bash
# 让 Full agent 自己加载
./jn -c "用 mod_load 加载 /path/to/jarness-hello-1.0.0.jar" -t full
```

```java
// 嵌入方
runtime.modLoader().loadJar(Path.of("/path/to/jarness-hello-1.0.0.jar"), true);
```

动态加载同样走 清单解析→依赖求解（对着已加载集合）→注册→应用 的完整管线，
错误以 `LoadReport` 返回。**已加载 mod 的 classloader 永不关闭**（直到运行时结束），
所以同一个 id 加载两次是错误。

---

## 11. 调试与排错

- 装载失败时，CLI 会打印 `LoadReport.render()`：`ERROR [PHASE] modId: message` +
  file / detail / hint 四段式。先修 phase 最早的错误（通常 PARSE/RESOLVE）。
- `jarness mods` 查看装载序与每个 instrument 的开关状态、优先级。
- 日志：`jarness.mod.<modId>` 是你的 logger；`config/jarness.toml` 里 `logging.level = "debug"`
  能看到会话存储、provider 收发的细节。
- 单测你的 instrument 不需要完整运行时：`new ModLoaderImpl(layout, () -> null)` +
  `loadJar(...)` 即可在无运行时的情况下验证发现/解析/求解（参考
  [DynamicLoadTest](../jarness-core/src/test/java/ch/predmet/jarness/core/mod/DynamicLoadTest.java)）。
- 常见坑：
  1. `@Mod("...")` 的 id 与 `jnmods.toml` 不一致 → 装载器报 id 冲突；
  2. 在 `register()` 里直接注册工具 → preset 管不到，诊断也看不见；
  3. 把 `jarness-api` 打成 compile 依赖塞进了自己的 jar → 类身份冲突（必须 provided）；
  4. 工具里自己 `Paths.get(...)` 没走 `ctx.resolve` → 绕过沙箱，审查时会被打回；
  5. 假设 instrument 之间存在隐式顺序 → 显式给 priority。

---

## 12. 完整示例

可直接参考仓库里的 [jarness-sample-mod](../jarness-sample-mod/)：一个带 `jnmods.toml`、
声明对 `jarness.core` 的 required+after 依赖、注册一个 instrument 贡献 `sample_echo` 工具的
最小外置 mod。它被 CLI 的 `SampleModLoadTest` 用来同时验证 mods 目录发现与 `loadJar`
动态加载两条路径。

更丰富的一步到位示例（钉钉/企微式"通知机器人"）：一个 mod 拆三个 instrument——
`tools.notify`（自动，注册 `notify_send` 工具）、`prompt.etiquette`（自动，给所有 agent
追加一段通知礼仪的 prompt section）、`webhook.server`（**手动**，起一个 HTTP 回调服务，
只有被 preset 显式打开才应用）——这个拆法能让用户在 default preset 里安静使用工具，
在专门 preset 里才拉起端口监听。
