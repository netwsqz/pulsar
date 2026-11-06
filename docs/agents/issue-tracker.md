# Issue 跟踪：GitHub

本仓库的 issue 与 spec 以 GitHub Issues 形式存在，仓库为 `netwsqz/pulsar`。所有操作走 `gh` CLI。

## 约定

- **建 issue**：`gh issue create --title "..." --body "..."`。多行正文用 heredoc。
- **读 issue**：`gh issue view <number> --comments`，用 `jq` 过滤评论，并取 labels。
- **列 issue**：`gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'`，按需加 `--label` / `--state`。
- **评论**：`gh issue comment <number> --body "..."`。
- **加/删标签**：`gh issue edit <number> --add-label "..."` / `--remove-label "..."`。
- **关闭**：`gh issue close <number> --comment "..."`。

仓库从 `git remote -v` 推断 —— 在 clone 目录里 `gh` 会自动识别。

## PR 作为 triage 入口

**PR 作为需求入口：否。**

## 本地历史

`.scratch/` 下的工单是本地 Markdown tracker 时期的存量，**只作历史参考，不再新增**。新的工单、spec 一律走 GitHub Issues。

## 当某个 skill 说「发布到 issue tracker」

创建一个 GitHub issue。

## 当某个 skill 说「取出相关工单」

执行 `gh issue view <number> --comments`。

## Wayfinding 操作

供 `/wayfinder` 使用。**map** 是一个 issue，每个工单对应一个**子 issue**。

- **Map**：单个带 `wayfinder:map` 标签的 issue，承载 Notes / Decisions-so-far / Fog 正文。`gh issue create --label wayfinder:map`。
- **子工单**：作为 map 的 GitHub sub-issue 关联（`gh api` 调 sub-issues 端点）。若该仓库未启用 sub-issue，则把子工单加进 map 正文的任务列表，并在子工单顶部写 `Part of #<map>`。标签用 `wayfinder:<type>`（`research`/`prototype`/`grilling`/`task`）。认领后指派给执行者。
- **阻塞**：GitHub 原生 issue dependencies —— 用 `gh api --method POST repos/<owner>/<repo>/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>`，其中 `<blocker-db-id>` 是阻塞者的数字 **database id**（`gh api repos/<owner>/<repo>/issues/<n> --jq .id`，**不是** `#number` 也不是 `node_id`）。GitHub 用 `issue_dependencies_summary.blocked_by` 报告未关闭的阻塞者。原生依赖不可用时，回退到子工单顶部的 `Blocked by: #<n>, #<n>` 行。所有阻塞者关闭后解除阻塞。
- **Frontier 查询**：列出 map 下所有 open 子工单，剔除有未关闭阻塞者（`issue_dependencies_summary.blocked_by > 0`，或 `Blocked by` 行里有 open issue）或已指派给别人的；按 map 顺序取第一个。
- **认领**：`gh issue edit <n> --add-assignee @me` —— 本会话的第一次写操作。
- **解决**：`gh issue comment <n> --body "<answer>"`，然后 `gh issue close <n>`，再把一行上下文指针（要点 + 链接）追加到 map 的 Decisions-so-far。
