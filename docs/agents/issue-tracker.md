# Issue 跟踪：GitHub

issue 与 spec 都存在 GitHub Issues（`netwsqz/pulsar`），全部走 `gh` CLI；仓库从 `git remote -v` 自动识别。

## 常用命令

- 建：`gh issue create --title "..." --body "..."`（多行正文用 heredoc）。
- 读：`gh issue view <n> --comments`。
- 列：`gh issue list --state open --json number,title,body,labels,comments`，按需加 `--label` / `--state`，用 `jq` 过滤。
- 评论：`gh issue comment <n> --body "..."`。
- 标签：`gh issue edit <n> --add-label "..."` / `--remove-label "..."`。
- 关闭：`gh issue close <n> --comment "..."`。

## 约定

- **PR 不作为需求入口**。
- `.scratch/` 是本地 Markdown tracker 时期的存量，只作历史，不再新增。
- skill 说「发布到 issue tracker」= 建 GitHub issue；说「取出相关工单」= `gh issue view <n> --comments`。

## Wayfinding（`/wayfinder` 用）

map 是一个带 `wayfinder:map` 标签的 issue，每个工单是它的 sub-issue（未启用 sub-issue 时写进 map 正文任务列表并在子工单顶部标 `Part of #<map>`）。阻塞用 GitHub issue dependencies（`blocked_by` 传阻塞者的 **database id**，不是 `#number`）。Frontier = map 下第一个无未关闭阻塞者、未指派给别人的 open 子工单。认领用 `gh issue edit <n> --add-assignee @me`；解决后评论、关票，并把一行指针追加到 map 的 Decisions-so-far。
