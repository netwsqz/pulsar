# 领域文档（Domain Docs）

本仓库的工程类 skill 在探查代码库时，应如何消费这里的领域文档。

## 探查之前，先读这些

- 仓库根目录的 **`CONTEXT.md`**，或者
- 仓库根目录的 **`CONTEXT-MAP.md`**（如果存在）—— 它指向每个上下文各一份 `CONTEXT.md`，读取与当前话题相关的那些。
- **`docs/adr/`** —— 读与你要动手的区域相关的 ADR。多上下文仓库里，还要看 `src/<context>/docs/adr/` 下的上下文级决策。

如果这些文件不存在，**静默继续**。不要指出它们缺失，也不要主动建议先创建它们。`/domain-modeling` skill（经由 `/grill-with-docs` 和 `/improve-codebase-architecture` 触达）会在术语或决策真正被敲定时惰性创建它们。

## 文件结构

单上下文仓库（绝大多数仓库）：

```
/
├── CONTEXT.md
├── docs/adr/
│   ├── 0001-event-sourced-orders.md
│   └── 0002-postgres-for-write-model.md
└── src/
```

多上下文仓库（根目录存在 `CONTEXT-MAP.md` 即为标志）：

```
/
├── CONTEXT-MAP.md
├── docs/adr/                          ← 系统级决策
└── src/
    ├── ordering/
    │   ├── CONTEXT.md
    │   └── docs/adr/                  ← 上下文级决策
    └── billing/
        ├── CONTEXT.md
        └── docs/adr/
```

**本仓库：单上下文**（单 Gradle 模块 `app`，无 workspace 信号）。所以用仓库根的 `CONTEXT.md` + `docs/adr/`。

## 用术语表里的词汇

当你的产出命名了一个领域概念（issue 标题、重构提案、假设、测试名），使用 `CONTEXT.md` 里定义的那个词。不要漂移到术语表明确回避的同义词。

如果你需要的概念还没进术语表，这本身是个信号 —— 要么你在发明项目不用的语言（重新考虑），要么是真的有缺口（记下来交给 `/domain-modeling`）。

## 与 ADR 冲突要明说

如果你的产出与已有 ADR 矛盾，明确地把它摆出来，而不是悄悄覆盖：

> _与 ADR-0007（事件溯源订单）冲突 —— 但值得重新讨论，因为……_

## 本仓库补充

- **语言**：代码注释、`CLAUDE.md`、`docs/` 下的文档一律中文；与用户沟通也默认中文。
- **已有的长文档别重复造**：`docs/superpowers/`（旧 skill 流程留下的 `plans/` `specs/` `handover/`）、`docs/usb-exclusive-research.md`、`docs/ui-improvement-suggestions.md`、`docs/usb-volume-key-interception.md`、根目录 `DESIGN_SYSTEM.md`。动手前先扫一眼有没有现成结论，避免重复调研。
- **`CLAUDE.md` 是本仓库事实上的架构地图**（数据层 / 播放 / USB 子系统 / UI 分层，以及一堆踩坑红线）。它比 `CONTEXT.md` 更全，优先读它。
