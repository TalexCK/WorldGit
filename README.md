# WorldGit

WorldGit is a Paper plugin for Minecraft 1.21.11 that introduces a Git-like
workflow for collaborative world building.

Players branch a selected region from the protected main world into an isolated
branch world, edit safely, submit their work for review, and merge approved
changes back into the main world. The main world stays read-only except through
explicit merges.

For the Chinese version, see [README.zh-CN.md](README.zh-CN.md).

## Target Stack

- Minecraft server: Paper `1.21.11`
- Java: `21`
- Build tool: Gradle Kotlin DSL + Shadow
- Main dependencies:
  - Paper API `1.21.11-R0.1-SNAPSHOT`
  - WorldEdit `7.4.1-SNAPSHOT`
  - Multiverse-Core `5.5.3`
  - SQLite JDBC `3.47.2.0`
- Optional integration:
  - LuckPerms API

## Core Features

- Region-based branch creation from a WorldEdit selection
- Concurrent overlapping branches during the editing phase
- Isolated branch worlds for editing
- Book-driven player menu with branch-detail, conflict-center, review, and merge-history chest UIs
- Review flow: submit, approve, reject, confirm merge
- Region-scoped rebase onto the latest main-world revision with three-way conflict detection
- Conflict groups with `mine`, `theirs`, and manual resolution flows
- Approved branches are re-checked before merge; stale approvals are revoked and require re-review
- Approved branches become read-only until `/wg forceedit`
- Sign-based coordinate input for `Pos1` / `Pos2` and merge messages
- Crash-safe merge journal and rebase journal with startup recovery
- Invite system for shared branch access
- Revision and commit tracking for the protected main world
- Short-lived integration lock during merge only
- Main-world protection to enforce read-only behavior
- Periodic and manual backups

## Runtime Dependencies

Install these plugins on the Paper 1.21.11 test server:

- WorldEdit
- Multiverse-Core
- WorldGit

LuckPerms is optional and only needed if you want to manage permissions through
it.

## Build

```bash
./gradlew build
```

Output JAR:

```text
build/libs/worldgit-1.1.0.jar
```

## Installation

1. Copy the built JAR to the server `plugins/` directory.
2. Make sure `WorldEdit` and `Multiverse-Core` are also installed.
3. Start the server once to generate `plugins/WorldGit/config.yml`.
4. Edit the config if needed.
5. Restart the server.

## Default Configuration

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

## Commands

### Player Commands

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

### Admin Commands

- `/wg review list`
- `/wg review approve <id> [note]`
- `/wg review reject <id> <note>`
- `/wg admin close <id>`
- `/wg admin assign <player>`
- `/wg admin list [player]`
- `/wg admin backup`
- `/wg admin locks`
- `/wg reload`

## Player Menu

- Players receive a menu book in hotbar slot `9`.
- Right-click the book to open the main player menu.
- Branch cards now open a dedicated branch-detail chest UI.
- `Pos1` / `Pos2` support:
  - Left click to use the current player coordinates
  - Right click to open a sign input
  - Coordinate syntax supports absolute values, `~`, `~+n`, and `~-n`
- Branch detail exposes:
  - Teleport
  - Rebase
  - Conflict Center
  - Submit for review
  - Confirm merge
  - Force edit
  - Abandon branch
- Conflict Center exposes:
  - Conflict-group list
  - Accept mine
  - Accept theirs
  - Teleport for manual resolution
  - Mark resolved
- Approved branches are merged through the final confirmation UI plus a sign-based merge message input.
- The paper item in the lower-left area opens paged merge history with:
  - Builders
  - Approver
  - Merger
  - Merge message
  - `Pos1` / `Pos2`
  - Teleport entry back to the branch world

## Permissions

### Player Permissions

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

### Admin Permissions

- `worldgit.admin.review`
- `worldgit.admin.close`
- `worldgit.admin.assign`
- `worldgit.admin.list`
- `worldgit.admin.backup`
- `worldgit.admin.locks`
- `worldgit.admin.reload`
- `worldgit.admin.bypass`

## Workflow

## Terminology

- WorldGit 1.1.0 uses **Rebase** as a product term, and that is acceptable because the plugin is intentionally Git-flavored.
- Technically, this is **not** full Git commit-history rewriting.
- It is a **region-scoped three-way replay** of the branch world onto the latest main-world revision:
  - `base`: snapshot captured when the branch was created or last rebased
  - `ours`: current branch-world blocks
  - `theirs`: latest main-world blocks
- In user-facing docs and UI, the clearest wording is: **Rebase (sync to latest main world)**.

### Create a Branch

1. Stand in the main world.
2. Select a cuboid region with WorldEdit.
3. Run `/wg create`.
4. Edit inside the generated branch world.

### Rebase and Resolve Conflicts

1. Open the branch-detail UI from the player menu.
2. If the main world has overlapping updates since your base revision, the branch is marked stale.
3. Click **Rebase** to replay your region onto the latest main-world revision.
4. If auto-merge is possible, the branch becomes clean immediately.
5. If conflicts remain, open **Conflict Center**.
6. Resolve each conflict group with:
   - `mine`
   - `theirs`
   - manual fix in-world, then mark resolved
7. Once all conflict groups are resolved, the branch becomes clean again.

### Submit, Review, and Merge

1. Run `/wg submit [id]` or submit through the branch-detail UI.
2. If the branch is stale, submission is rejected until it is rebased.
3. An admin reviews the branch.
4. Once approved, the branch becomes read-only.
5. If you want to keep editing after approval, run `/wg forceedit [id]` first.
6. After editing again, re-submit the branch for a fresh review.
7. If the main world changes after approval but before merge, the approval is revoked automatically.
8. Rebase the branch again, resolve conflicts if needed, and submit for re-review.
9. If the branch is still current, run `/wg confirm [id]`, enter a merge message, and merge it back into the main world.

## Main-World Protection Coverage

The plugin blocks direct edits in the configured main world, including:

- Block place and multi-place
- Block break
- Bucket empty/fill
- Bone meal and fertilization updates
- Fluid and block-state changes such as spread, fade, form, and flow
- Pistons
- Explosions including TNT and fireball-style explosions
- Hanging entities such as paintings and item frames
- Armor stand manipulation and damage
- Spawn eggs, dispenser-fired spawn eggs, and egg hatching into chicks
- Natural creature and monster spawning, spawners, and trial spawners
- Patrol, raid, reinforcement, and similar hostile mob spawns
- Entering the Nether or the End from the main world

Admins with `worldgit.admin.bypass` can bypass player-driven protection checks.

## Paper 1.21.11 Test Checklist

### Environment Setup

1. Start a fresh Paper `1.21.11` server.
2. Install `WorldEdit`, `Multiverse-Core`, and `WorldGit`.
3. Confirm all three plugins load without errors.
4. Confirm the main world name matches `config.yml`.

### Branch Creation

1. Join as a player with `worldgit.branch.create`.
2. Select a small cuboid region in the main world.
3. Run `/wg create`.
4. Verify:
   - A branch record exists in SQLite
   - A branch world named like `wg_<id>` exists
   - The selected blocks were copied correctly
   - A base snapshot exists for the branch
   - The branch stores a base revision

### Protection

1. Try placing and breaking blocks in the main world.
2. Try buckets, bone meal, item frames, and armor stands in the main world.
3. Verify all changes are blocked unless the player has
   `worldgit.admin.bypass`.

### Rebase, Review, and Merge

1. Let player A and player B create overlapping branches from the same region.
2. Let player A edit and merge first.
3. On player B, run `/wg submit`.
4. Verify submission is rejected and the branch is marked stale.
5. Use the branch-detail UI to run **Rebase**.
6. If conflicts appear, verify Conflict Center can:
   - list groups
   - accept mine
   - accept theirs
   - teleport for manual repair
   - mark a group resolved
7. After the branch is clean, submit it again.
8. Approve with `/wg review approve <id>`.
9. Before merging, let another overlapping branch update the main world again.
10. Run `/wg confirm <id>`.
11. Verify the approval is revoked automatically and the branch must be rebased and re-reviewed.
12. Complete the second rebase and submit again.
13. Approve once more, then merge.
14. Verify:
    - Players are moved out safely
    - Blocks are merged into the main world
    - A short-lived merge lock is created and released
    - A world commit entry is recorded
    - The branch world is unloaded after merge and can be reopened from merge history if needed
    - The branch is marked `MERGED`
    - Merge history shows builders, approver, merger, merge message, and region coordinates

### Recovery

1. Start a merge.
2. Stop the server during the merge or rebase process.
3. Restart the server.
4. Verify incomplete merge and rebase state is recovered cleanly.

### Backup

1. Run `/wg admin backup`.
2. Verify a backup appears under the configured backup directory.
3. If scheduled backups are enabled, verify old backups are pruned.

## Notes

- This repository currently passes `./gradlew build`.
- No automated unit tests are included yet.
- Runtime validation should still be done on a real Paper 1.21.11 server with
  WorldEdit and Multiverse-Core installed.
