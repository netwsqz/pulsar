# Triage 标签

各 skill 用五个规范 triage 角色说话。本文件把这些角色映射到本仓库实际使用的标签字符串。

| mattpocock/skills 里的标签 | 本仓库 tracker 里的标签 | 含义 |
| --- | --- | --- |
| `needs-triage`    | `needs-triage`    | 维护者需要评估此 issue |
| `needs-info`      | `needs-info`      | 等报告人补充信息 |
| `ready-for-agent` | `ready-for-agent` | 已完整规格化，可交给 AFK agent |
| `ready-for-human` | `ready-for-human` | 需要人来实现 |
| `wontfix`         | `wontfix`         | 不会处理 |

当某个 skill 提到某个角色（例如「打上 AFK-ready 的 triage 标签」）时，使用本表对应行的标签字符串。

注意：本仓库用的是**本地 Markdown** tracker（见 `issue-tracker.md`），没有真正的 label 机制 —— 这些字符串就写在工单文件顶部附近的 `Status:` 行里。

想换命名就改右列。
