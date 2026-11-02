# 更新日志维护

参考 Dao Browser，采用「开发时维护 `Unreleased`，发布时自动归档」的方式。Android 应用与桌面工作台共用版本号，因此只维护一套版本历史；豆馅 OS 镜像的独立版本不并入这里。

## 唯一内容源

- [en.md](changelog/en.md)：英文原文。
- [zh-CN.md](changelog/zh-CN.md)：对应的简体中文翻译。

根目录 `CHANGELOG.md` 只提供入口。GitHub Release 说明从这两份文件生成，不另写一份；应用内更新检查使用该 Release 说明。

`1.0.0`、`1.0.1`、`1.1.0` 的记录根据各 tag 及其提交范围补齐，日期采用 tag 所指提交的本地日历日期，不代表已核验的 GitHub 发布时间。新增版本的归档日期使用执行发布命令的本机日期。

## 日常维护

更新日志是写给普通用户看的，重点回答「我现在能做什么」「之前哪个问题修好了」。用简短、日常的表达，中文优先标明「手机端」或「电脑端」。例如，将「保留对话上下文」写成「助手会记住当前任务中聊过的内容，不用重复交代」。

不展开内部模块名、请求和重试流程、截图判断或日志保存机制；纯内部诊断改动不必写入日志。系统要求、需要额外设置、默认是否开启等会影响使用的信息应保留。简化措辞不能夸大功能，也不能把「便于排查」写成「问题已修复」。

1. 用户可感知的功能、修复或行为变更，在对应实现中同步更新两份文件的 `## [Unreleased]`。
2. 先写英文，再同步中文；两种语言的条目按相同顺序一一对应。条目标明手机端、电脑端或两者。
3. 每项使用一行 `- ` 开头的平铺条目，写明用户得到什么或哪个问题被修复。不要使用嵌套列表、分类小标题或多行条目；文件使用 UTF-8 编码与 LF 换行，末尾保留换行。
4. 同一功能的补充调整合并到已有条目。纯测试、CI、内部重构、格式调整和开发文档一般不记录；影响安装包交付的修复可以说明影响。
5. 已发布章节视为历史记录，默认不改写。版本格式为 `X.Y.Z`，不含 `v`；章节按版本从新到旧排列，中英文版本、日期和条目数必须一致。

结构示例：

```markdown
# Changelog

## [Unreleased]

- Android: Describe a pending user-visible change.

## [1.1.0] - 2026-10-31

- Desktop: Describe a released change.
```

提交前检查（Python 3 标准库，无额外依赖）：

```sh
python3 scripts/changelog.py check
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_*.py'
bash -n release.sh
```

CI 在相关更新中运行同样的检查。检查器验证格式、版本顺序及中英文结构，不判断翻译含义是否一致，仍需人工审阅。

## 发布

发布前确保变更已经提交、工作区干净，Python 3 与 GitHub CLI 可用且已登录，分支不落后于上游。`Unreleased` 必须包含本次发布说明。

```sh
# 可选：只检查目标版本，不修改文件
python3 scripts/changelog.py check 1.2.0

# 实际发布；也支持 minor、major 或显式 X.Y.Z
./release.sh patch
```

`release.sh` 在确认前检查两种语言、待发布条目与目标版本。确认后将两份 `Unreleased` 归档到 `## [X.Y.Z] - YYYY-MM-DD`，保留空的 `Unreleased`，并把更新日志和应用版本号放入同一个发布提交。随后创建 tag、推送并发布 GitHub Release；说明按简体中文、英文顺序生成，现有 CI 继续上传 APK 和桌面安装包。

无需手动先归档，否则发布预检会因目标版本已存在而拒绝继续。`python3 scripts/changelog.py archive X.Y.Z` 仅供独立归档操作或脚本调用。

发布过程不提供跨 Git 与 GitHub 的自动回滚。中途失败时先检查 `git status`、最新提交、tag 与远端 Release 状态，避免直接重复运行发布脚本。若发布提交和 tag 已成功推送、只有 GitHub Release 创建失败，可从已发布 tag 生成说明，再重试创建（把示例版本换成实际版本）：

```sh
# 先确认当前 HEAD 就是该发布 tag，确保读取对应版本的说明
test "$(git rev-parse HEAD)" = "$(git rev-parse v1.2.0)" &&
  python3 scripts/changelog.py notes 1.2.0 > /tmp/open-doubao-v1.2.0-notes.md &&
  gh release create v1.2.0 --verify-tag --title v1.2.0 --notes-file /tmp/open-doubao-v1.2.0-notes.md
```

`notes` 只读取指定已归档版本，不包含 `Unreleased` 或其他历史。恢复命令仅适用于远端尚未创建该 Release 的情况；已存在时先查看它的状态和构建结果。
