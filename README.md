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
make            # 打包到 dist/（dist/jarness.jar + dist/mods/*.jar）
./jn            # 拉起交互式 TUI（tui preset）
./jn -c "你好"  # 一次性运行（等价于 jarness run）
./jn sessions   # 列镜像（turns/budget/checkpoint/deps）
./jn mods       # 透传其它 CLI 子命令（mods/presets/templates/providers/namespaces/sessions/tui/run）
```

也可以直接用 jar：`java -jar dist/jarness.jar run "你好"`。

base dir 默认是 `$HOME/.local/share/jarness`：`mods/` 放外置 mod jar，`config/` 放配置
（`jarness.toml`、`providers.toml`、以及以 mod id 命名的 per-mod 配置目录），`presets/` 放预设，
`data/jarness.db` 是镜像库（SQLite，turn 事务 + checkpoint）。每个 agent 的工作目录与 base dir 无关，
默认在 `<base>/workspaces/agents/agent-<pid>` 下。

镜像可以恢复：

```bash
./jn run --session <session-id> "继续上次的活"       # 依赖匹配时直接 resume
./jn run --session <session-id> --force-resume "..." # 依赖缺失时降级加载
```

## Agent、namespace 与能力

每个 agent 都是监督树中的一个节点：有 pid、parent、唯一 namespace、能力集合与预算。
root agent 挂在 supervisor 的合成根 pid 0 下；`subagent` / `agent_spawn` 创建的是调用者的子节点，
能力按 `requested ∩ parent ∩ namespace.defaults` 逐层衰减，父节点退出时子树被 kill。

namespace 可从 `config/jarness.toml` 声明：

```toml
[supervisor]
max_depth = 16       # 0 = 不限制
max_children = 32    # 0 = 不限制

# 默认 namespace 由 [defaults] 构造；这里只声明额外的 namespace。
[namespaces.review]
template = "minimal"
model = "GB10/DeepSeek-V4-Flash"
store = "jdbc:sqlite:/var/lib/jarness/review.db"   # 可选：独立镜像库
capabilities = ["fs.read", "fs.write", "model"]     # 可选：缺省为本地全套（不含 platform）
```

`./jn namespaces` 或 `java -jar dist/jarness.jar namespaces` 会列出各 namespace 的能力上限、
模型档位与默认模板。

## 测试

```bash
make test       # 单元测试
make it         # 需要 tmp/endpoint.txt 的联调测试
```

## 设计决策

见 [docs/design-decisions.md](docs/design-decisions.md) 与
[docs/design-gap-analysis.md](docs/design-gap-analysis.md)。
