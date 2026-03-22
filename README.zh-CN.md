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
- 区域锁防止冲突编辑
- 独立分支世界编辑
- 菜单书驱动的玩家面板、审核面板与合并记录 Chest UI
- 审核流：提交、批准、拒绝、确认合并
- 审核通过后的分支会进入只读状态，直到执行 `/wg forceedit`
- `Pos1` / `Pos2` 与 merge message 支持告示牌输入
- 崩溃恢复的 merge journal
- 分支邀请协作
- 锁区排队
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
build/libs/worldgit-1.0.0-SNAPSHOT.jar
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
max-queue-entries: 1

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
- `/wg queue`

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
- `Pos1` / `Pos2` 支持：
  - 左键直接使用玩家当前坐标
  - 右键打开告示牌输入
  - 坐标语法支持绝对值、`~`、`~+n`、`~-n`
- 已审核通过的分支会通过两步 Chest UI 进行 merge：
  - 先选择目标分支
  - 再确认 merge，并通过告示牌输入 merge message
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
- `worldgit.branch.queue`

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

### 创建分支

1. 站在主世界中。
2. 用 WorldEdit 选择一个长方体区域。
3. 执行 `/wg create`。
4. 在生成的分支世界中编辑。

### 提交与合并

1. 执行 `/wg submit [id]`。
2. 分支在“等待审核”阶段仍然允许继续建造。
3. 管理员审核该分支。
4. 一旦审核通过，分支会进入只读状态。
5. 如果审核通过后还想继续修改，先在分支世界输入 `/wg forceedit [id]`，分支会立刻撤销通过状态并切回编辑状态。
6. 重新修改完成后，再次执行 `/wg submit [id]` 等待审核。
7. 如果不再需要修改，执行 `/wg confirm [id]`，并在 Chest UI 中选择目标分支，再输入 merge message。
8. WorldGit 把分支改动合并回主世界。

### 锁区排队

1. 选择被锁定的区域。
2. 执行 `/wg queue`。
3. 等待解锁通知。
4. 重新执行 `/wg create`。

## 主世界保护覆盖范围

插件会阻止主世界中的直接改动，包括：

- 方块放置与多方块放置
- 方块破坏
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
3. 确认三个插件都能正常加载，无报错。
4. 确认 `config.yml` 里的主世界名与服务端一致。

### 分支创建

1. 使用有 `worldgit.branch.create` 权限的玩家登录。
2. 在主世界选择一个小型长方体区域。
3. 执行 `/wg create`。
4. 验证：
   - SQLite 中有 branch 记录
   - 有 region lock 记录
   - 生成了类似 `wg_<id>` 的分支世界
   - 选区方块被正确复制

### 主世界保护

1. 在主世界尝试放置和破坏方块。
2. 在主世界尝试水桶、骨粉、展示框、盔甲架等操作。
3. 验证所有改动都会被拦截，除非玩家拥有
   `worldgit.admin.bypass`。

### 审核与合并

1. 在分支世界里修改方块。
2. 执行 `/wg submit`。
3. 验证分支在“等待审核”阶段仍然可以继续编辑。
4. 用 `/wg review approve <id>` 批准。
5. 验证审核通过后的分支会进入只读状态，直到执行 `/wg forceedit <id>`。
6. 若审核通过后没有继续修改，用 `/wg confirm <id>` 打开分支选择 UI、最终确认 UI，再进入 merge message 告示牌输入。
7. 若审核通过后继续修改，先输入 `/wg forceedit <id>` 切回编辑状态。
8. 修改完成后重新执行 `/wg submit`，等待再次审核。
9. 验证：
   - 玩家会被安全移出分支世界
   - 方块正确写回主世界
   - 区域锁被释放
   - 分支世界被删除
   - 分支状态变成 `MERGED`
   - 合并记录中可以看到建造者、批准者、合并者、merge message 和区域坐标

### 排队

1. 让玩家 A 锁定一个区域。
2. 让玩家 B 选择同一区域并执行 `/wg queue`。
3. 通过 merge 或 abandon 释放锁。
4. 验证玩家 B 会收到通知。

### 崩溃恢复

1. 发起一次 merge。
2. 在 merge 中途强制停服。
3. 重启服务端。
4. 验证 merge journal 能继续恢复并完整收尾。

### 备份

1. 执行 `/wg admin backup`。
2. 验证备份目录下出现新的备份。
3. 如果启用了定时备份，验证旧备份会被裁剪。

## 说明

- 当前仓库已经通过 `./gradlew build`。
- 目前还没有自动化单元测试。
- 仍然建议在真实的 Paper 1.21.11 + WorldEdit + Multiverse-Core
  环境里做一次完整联调。
