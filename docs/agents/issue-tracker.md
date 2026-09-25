# Issue 跟踪：本地 Markdown

本仓库的 issue 与 spec 以 markdown 文件形式存在 `.scratch/` 下。

## 约定

- 一个功能一个目录：`.scratch/<feature-slug>/`
- spec 是 `.scratch/<feature-slug>/spec.md`
- 实现类工单一票一文件：`.scratch/<feature-slug>/issues/<NN>-<slug>.md`，从 `01` 开始编号 —— 绝不合并成一个 tickets 大文件
- 每个工单文件靠顶部一行 `Status:` 记录 triage 状态（角色字符串见 `triage-labels.md`）
- 评论与讨论追加到文件底部的 `## Comments` 标题下

## 当某个 skill 说「发布到 issue tracker」

在 `.scratch/<feature-slug>/` 下新建文件（目录不存在就建）。

## 当某个 skill 说「取出相关工单」

读取所指路径的文件。用户通常会直接给你路径或工单号。

## Wayfinding 操作

供 `/wayfinder` 使用。**map** 是一个文件，每个工单对应一个**子文件**。

- **Map**：`.scratch/<effort>/map.md` —— 承载 Notes / Decisions-so-far / Fog 正文。
- **子工单**：`.scratch/<effort>/issues/NN-<slug>.md`，从 `01` 编号，问题写在正文里。`Type:` 行记录工单类型（`research`/`prototype`/`grilling`/`task`）；`Status:` 行记录 `claimed`/`resolved`。
- **阻塞**：顶部附近一行 `Blocked by: NN, NN`。当它列出的每个文件都是 `resolved` 时，该工单解除阻塞。
- **Frontier**：扫描 `.scratch/<effort>/issues/` 找 open、未阻塞、未认领的文件；编号小的优先。
- **认领**：动手前先设 `Status: claimed` 并保存。
- **解决**：在 `## Answer` 标题下追加答案，设 `Status: resolved`，然后把一行上下文指针（要点 + 链接）追加到 `map.md` 的 Decisions-so-far。
