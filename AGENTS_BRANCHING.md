# AGENTS_BRANCHING.md

任何会修改文件、创建或切换分支、commit、push、发 PR 或合并的任务开始前，必须先读这里。

## 分支角色

| 分支 | 角色 | 进入方式 |
|---|---|---|
| `main` | 唯一长期分支、稳定可交付基线 | 只允许通过 PR 合并；验证后按 tag 发布 Release |
| `feature/*`、`feat/*` | 普通功能更新 | 从最新 `main` 派生，minor 版本递增后发 PR 回 `main` |
| `fix/*` | 缺陷和紧急修复 | 从最新 `main` 派生，patch 版本递增后发 PR 回 `main` |
| `docs/*`、`chore/*` 等 | 其它单一任务 | 从最新 `main` 派生，明确选择 patch、minor 或 major 版本后发 PR 回 `main` |

`main` 上的每个提交都应当可构建、可验证、可安装，并能作为后续任务的可靠基点。未完成或未验收的中间状态只留在短生命周期任务分支。当前不维护 `develop` 或 `release/*`；只有未来明确需要并行维护多个版本线时才重新设计发布分支。

## 版本规则

- `source/tablet-live-wallpaper/version.properties` 是 Android `versionName` 与 `versionCode` 的唯一权威入口。
- `versionName` 使用完整三段 SemVer。普通功能做一次精确 minor 递增，例如 `1.1.0 -> 1.2.0`；修复做一次精确 patch 递增，例如 `1.1.0 -> 1.1.1`。
- `versionCode` 每次合入 `main` 必须恰好加一。版本更新由 PR 显式提交，CI/CD 不直接向 `main` 回写版本。
- `feature/*`、`feat/*` 和 `fix/*` 的分支类型会由 PR CI 与版本增量交叉校验。其它分支也必须选择一次合法的 patch、minor 或 major 增量。
- Release 使用 `v<versionName>` 标签；已有版本标签属于不可覆盖的历史记录。构建与外置签名见 `docs/releasing.md`。

## 修改前检查

```sh
git branch --show-current
git status --short --branch
git log --oneline -n 20
```

- 确认当前仓库、分支和任务目标一致；普通任务不得直接在 `main` 上开始修改。
- 工作区存在其它任务的改动或未跟踪文件时，不覆盖、不整理、不顺带提交。必要时使用独立分支和 worktree 隔离。
- 新任务从最新 `main` 创建 `feature/*`、`feat/*`、`fix/*`、`docs/*` 或其它语义清晰的短生命周期分支。
- Codex 创建分支时默认使用 `codex/` 前缀；用户指定命名时服从用户要求。

## PR-only 规则

- 禁止把业务代码、测试、配置、资源或普通文档改动直接 push 到 `main`。
- `main` 只允许通过 PR 合并。即使本地提交已经通过验证，也必须先推送任务分支并创建目标明确的 PR。
- 一个 PR 对应一个清晰、可验证、可回滚的变更单元；不要夹带无关格式化、本机配置、构建缓存或其它任务文件。
- PR 合并前按改动类型完成 `AGENTS_DEV.md` 要求的验证，并在说明中区分自动测试、模拟器验证、真机验收和未验证项。
- 只有用户针对当前任务明确授权一次性例外时，才可直接 push 到受保护基线。例外不会改变后续任务的默认规则，也不能被推断为长期授权。

## Force push 边界

### `main`

- 严格禁止任何形式的 force push，包括 `--force` 和 `--force-with-lease`。
- 不得重写、删除或替换 `main` 的既有提交。需要修正时，通过新的任务分支和 PR 追加修复或 revert。

## Push 前检查

- 先 `git fetch` 并确认本地目标基线未落后或意外分叉。
- 用 `git status --short` 和 `git diff --cached --stat` 确认暂存区只含本次任务文件。
- 检查 staged diff 中没有密钥、设备私人内容、完整用户 URI、本机路径覆盖或无关产物。
- commit message 遵循仓库历史风格；不确定时以最近提交为准。
- 用户没有要求 push、PR 或合并时，完成本地修改和验证后停止，不自行扩大外部变更范围。

## 合并与紧急修复

- `main` 的修复从最新 `main` 派生 `fix/*`，以 patch 版本验证后通过 PR 回到 `main`。
- 普通功能从最新 `main` 派生 `feature/*` 或 `feat/*`，以 minor 版本验证后通过 PR 回到 `main`。
- 不把长期未完成分支直接合并到稳定基线，也不因紧急程度跳过最低必要验证。
- revert、回滚和冲突解决也属于代码变更，进入 `main` 时同样遵守 PR-only 和版本递增规则。
