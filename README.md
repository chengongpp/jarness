# Jarness

Jarness 是一个基于 Java 21 LTS 的企业级多 Agent 运行时（架构草案见 `draft/`），
设计上参考 deepseek-harness 的轻量可组装风格与 NeoForge/FancyModLoader 的模组体系。

## 模块

| 模块 | 说明 |
| --- | --- |
| `jarness-api` | 稳定扩展面：Mod、Instrument、Hook、Tool、Provider、Session、Agent、Supervisor、Preset 等抽象 |
| `jarness-core` | 运行时：ModLoader（单例）、Supervisor（单例）、agent loop、SQLite 会话存储、三种 provider 适配、内建工具与模板 |
| `jarness-cli` | `jarness` 命令行（shade 成单个 `jarness.jar`） |
| `jarness-mod-tui` | TUI 系统 mod（由 `tui` preset 启用） |
| `jarness-sample-mod` | 示例外置 mod，用于测试 mods 目录发现与动态加载 |

## 快速开始

```bash
make            # 打包到 dist/（dist/jarness.jar + dist/mods/*.jar）
./jn            # 拉起交互式 TUI（tui preset）
./jn -c "你好"  # 一次性运行（等价于 jarness run）
./jn mods       # 透传其它 CLI 子命令（mods/presets/templates/providers/tui/run）
```

也可以直接用 jar：`java -jar dist/jarness.jar run "你好"`。

base dir 默认是 `$HOME/.local/share/jarness`：`mods/` 放外置 mod jar，`config/` 放配置
（`jarness.toml`、`providers.toml`、以及以 mod id 命名的 per-mod 配置目录），`presets/` 放预设。
每个 agent 的工作目录与 base dir 无关，默认在 `<base>/workspaces/agents/<id>` 下。

## 测试

```bash
make test       # 单元测试
make it         # 需要 tmp/endpoint.txt 的联调测试
```

## 设计决策

见 [docs/design-decisions.md](docs/design-decisions.md)。
