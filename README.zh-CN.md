# WorldGit

WorldGit 是一个面向 Minecraft `1.21.11` Paper 服务端的协作建造插件，
它把 Git 风格的工作流带到地图编辑里。

玩家可以从受保护的主世界中“分支”出一个 WorldEdit 选区到独立的分支世界，
在分支世界中自由编辑，提交审核，通过后再把改动合并回主世界。主世界默认
保持只读，只有显式 merge 才允许写回。

英文版见 [README.md](README.md)。

## 目标环境

- 服务端：Paper `1.21.11`
- Java：`21`
- 构建工具：Gradle Kotlin DSL + Shadow
- 主要依赖：
  - Paper API `1.21.11-R0.1-SNAPSHOT`
  - WorldEdit `7.4.1-SNAPSHOT`
  - Multiverse-Core `5.5.3`
  - SQLite JDBC `3.47.2.0`
- 可选集成：
  - LuckPerms API

## 核心能力

- 基于 WorldEdit 选区创建分支
- 编辑阶段允许同一区域并发分支
- 独立分支世界编辑
- 菜单书驱动的玩家面板、分支详情、冲突中心、审核面板与合并记录 Chest UI
- 审核流：提交、批准、拒绝、确认合并
- 支持把分支通过三方合并 rebase 到最新主线版本
- 冲突按组展示，支持 `mine`、`theirs` 和手动处理
- 审核通过后的分支会进入只读状态，直到执行 `/wg forceedit`
- 若审核后主线继续变化，会撤销批准并要求重新审核
- `Pos1` / `Pos2` 与 merge message 支持告示牌输入
- 崩溃恢复的 merge journal 与 rebase journal
- 分支邀请协作
- 主世界 revision / commit 记录
- 合并阶段使用短时集成锁
- 主世界只读保护
- 定时与手动备份

## 运行时依赖

在 Paper `1.21.11` 测试服中安装以下插件：

- WorldEdit
- Multiverse-Core
- WorldGit

如果你要通过 LuckPerms 管理权限，再额外安装 LuckPerms。

## 构建

```bash
./gradlew build
```

输出 JAR：

```text
build/libs/worldgit-1.1.0.jar
```

## 安装

1. 把构建出的 JAR 放进服务端 `plugins/` 目录。
2. 确保 `WorldEdit` 和 `Multiverse-Core` 也已经安装。
3. 首次启动服务端，生成 `plugins/WorldGit/config.yml`。
4. 按需修改配置。
5. 重启服务端。

## 默认配置

```yaml
main-world: "world"
max-region-size-x: 50
max-region-size-z: 50
use-full-height: false
max-active-branches: 2

backup:
  enabled: true
  interval-minutes: 30
  max-backups: 10
  directory: "backups"

branch-world:
  prefix: "wg_"

database:
  file: "worldgit.db"
```

## 命令

### 玩家命令

- `/wg create`
- `/wg abandon <id>`
- `/wg list`
- `/wg info <id>`
- `/wg submit [id]`
- `/wg confirm [id]`
- `/wg forceedit [id]`
- `/wg invite <player> [id]`
- `/wg uninvite <player> [id]`
- `/wg tp <id>`
- `/wg return`

### 管理员命令

- `/wg review list`
- `/wg review approve <id> [note]`
- `/wg review reject <id> <note>`
- `/wg admin close <id>`
- `/wg admin assign <player>`
- `/wg admin list [player]`
- `/wg admin backup`
- `/wg admin locks`
- `/wg reload`

## 玩家菜单

- 玩家会在快捷栏第 `9` 格拿到菜单书。
- 右键菜单书可以打开玩家主菜单。
- 分支卡片左键会进入独立的“分支详情”菜单。
- `Pos1` / `Pos2` 支持：
  - 左键直接使用玩家当前坐标
  - 右键打开告示牌输入
  - 坐标语法支持绝对值、`~`、`~+n`、`~-n`
- 分支详情里可以执行：
  - 进入分支
  - Rebase
  - 打开冲突中心
  - 提交审核
  - 确认合并
  - 切回编辑
  - 放弃分支
- 冲突中心里可以执行：
  - 查看冲突组列表
  - 接受 mine
  - 接受 theirs
  - 传送到现场手动处理
  - 标记当前组已解决
- 已审核通过的分支会通过最终确认 UI + 告示牌输入 merge message 来完成 merge。
- 左下区域的纸张会打开分页合并记录，里面会显示：
  - 建造者
  - 批准者
  - 合并者
  - merge message
  - `Pos1` / `Pos2`
  - 回到对应分支世界的传送入口

## 权限

### 玩家权限

- `worldgit.branch.create`
- `worldgit.branch.abandon`
- `worldgit.branch.list`
- `worldgit.branch.info`
- `worldgit.branch.submit`
- `worldgit.branch.confirm`
- `worldgit.branch.forceedit`
- `worldgit.branch.invite`
- `worldgit.branch.tp`
- `worldgit.branch.return`

### 管理员权限

- `worldgit.admin.review`
- `worldgit.admin.close`
- `worldgit.admin.assign`
- `worldgit.admin.list`
- `worldgit.admin.backup`
- `worldgit.admin.locks`
- `worldgit.admin.reload`
- `worldgit.admin.bypass`

## 工作流

## 术语说明

- WorldGit 1.1.0 继续使用 **Rebase** 这个产品术语，我认为是合适的，因为整个插件本来就是 Git 风格建模。
- 但它在技术上 **不是** 完整的 Git 提交历史重写。
- 这里的 rebase 更准确地说，是把一个区域分支通过三方比较“重放到最新主线”：
  - `base`：创建分支或上次 rebase 时保存的基线快照
  - `ours`：当前分支世界里的方块状态
  - `theirs`：当前主世界里的最新方块状态
- 所以在中文文档里，最合适的写法是：**Rebase（同步到最新主线）**。

### 创建分支

1. 站在主世界中。
2. 用 WorldEdit 选择一个长方体区域。
3. 执行 `/wg create`。
4. 在生成的分支世界中编辑。

### Rebase 与冲突处理

1. 从玩家菜单进入“分支详情”。
2. 如果主线自基线版本以来发生了重叠更新，分支会被标记为过期。
3. 点击 **Rebase**，把当前区域重放到最新主线。
4. 如果可以自动合并，分支会立即回到干净状态。
5. 如果出现冲突，进入 **冲突中心**。
6. 对每个冲突组选择：
   - `mine`
   - `theirs`
   - 进世界里手动修，再标记已解决
7. 当所有冲突组都处理完后，分支重新变为可提交状态。

### 提交、审核与合并

1. 执行 `/wg submit [id]`，或在分支详情里提交审核。
2. 如果分支已过期，提交会被拒绝，必须先 rebase。
3. 管理员审核该分支。
4. 一旦审核通过，分支会进入只读状态。
5. 如果审核通过后还想继续修改，先执行 `/wg forceedit [id]`。
6. 修改完成后重新提交，等待新一轮审核。
7. 如果审核后主线又变了，批准会在 merge 前自动失效。
8. 这时需要重新 rebase、处理冲突，并重新提交复审。
9. 当分支仍与主线对齐时，执行 `/wg confirm [id]`，填写 merge message，然后合并回主世界。

## 主世界保护覆盖范围

插件会阻止主世界中的直接改动，包括：

- 方块放置与多方块放置
- 方块破坏
- Axiom 的改块包（如 Replace / Angel / Buffer 等）会被直接拦截
- 水桶/岩浆桶相关操作
- 骨粉和施肥引起的方块更新
- 液体流动、蔓延、褪变、生成等状态变化
- 活塞推动/拉回
- 爆炸，包括 TNT 和火焰弹等爆炸来源
- 挂画、展示框等 Hanging 实体
- 盔甲架操作与伤害
- 刷怪蛋、发射器发射刷怪蛋、鸡蛋孵化小鸡
- 生物与怪物的自然生成、刷怪笼、试炼刷怪器
- 巡逻、袭击、援军等敌对生物生成
- 从主世界进入地狱和末地

拥有 `worldgit.admin.bypass` 的管理员，可以绕过由玩家触发的保护检查。

## Paper 1.21.11 测服联调清单

### 环境准备

1. 启动一个全新的 Paper `1.21.11` 服务端。
2. 安装 `WorldEdit`、`Multiverse-Core` 和 `WorldGit`。
3. 若服务端同时安装了 `AxiomPaper`，还需要安装 `ProtocolLib`，否则无法拦截 Axiom 自定义改块包。
4. 确认相关插件都能正常加载，无报错。
5. 确认 `config.yml` 里的主世界名与服务端一致。

### 分支创建

1. 使用有 `worldgit.branch.create` 权限的玩家登录。
2. 在主世界选择一个小型长方体区域。
3. 执行 `/wg create`。
4. 验证：
   - SQLite 中有 branch 记录
   - 生成了类似 `wg_<id>` 的分支世界
   - 选区方块被正确复制
   - 为该分支生成了基线快照
   - 分支记录了 base revision

### 主世界保护

1. 在主世界尝试放置和破坏方块。
2. 在主世界尝试水桶、骨粉、展示框、盔甲架等操作。
3. 验证所有改动都会被拦截，除非玩家拥有
   `worldgit.admin.bypass`。

### Rebase、审核与合并

1. 让玩家 A 和玩家 B 在同一区域各自创建分支。
2. 让玩家 A 先修改并合并。
3. 让玩家 B 执行 `/wg submit`。
4. 验证提交会被拒绝，且分支被标记为过期。
5. 在分支详情里执行 **Rebase**。
6. 如果出现冲突，验证冲突中心可以：
   - 展示冲突组列表
   - 接受 mine
   - 接受 theirs
   - 传送到现场手动修复
   - 标记当前组已解决
7. 当分支恢复干净后，再次提交审核。
8. 用 `/wg review approve <id>` 批准。
9. 在 merge 前再让另一条重叠分支更新主世界。
10. 执行 `/wg confirm <id>`。
11. 验证系统会自动撤销批准，并要求该分支重新 rebase 和复审。
12. 完成第二次 rebase 后重新提交、重新批准，再执行 merge。
13. 验证：
   - 玩家会被安全移出分支世界
   - 方块正确写回主世界
   - 会创建并释放短时 merge 锁
   - 主世界新增一条 commit 记录
   - 分支世界在合并后会被卸载，必要时可从合并记录重新打开
   - 分支状态变成 `MERGED`
   - 合并记录中可以看到建造者、批准者、合并者、merge message 和区域坐标

### 崩溃恢复

1. 发起一次 merge 或 rebase。
2. 在执行中途强制停服。
3. 重启服务端。
4. 验证未完成的 merge / rebase 状态都能被正确恢复或安全回退。

### 备份

1. 执行 `/wg admin backup`。
2. 验证备份目录下出现新的备份。
3. 如果启用了定时备份，验证旧备份会被裁剪。

## 说明

- 当前仓库已经通过 `./gradlew build`。
- 目前还没有自动化单元测试。
- 仍然建议在真实的 Paper 1.21.11 + WorldEdit + Multiverse-Core
  环境里做一次完整联调。
