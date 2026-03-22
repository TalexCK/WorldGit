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
- Automatic region locking to prevent conflicting work
- Isolated branch worlds for editing
- Book-driven player menu with branch, review, and merge-history chest UIs
- Review queue: submit, approve, reject, confirm merge
- Approved branches become read-only until `/wg forceedit`
- Sign-based coordinate input for `Pos1` / `Pos2` and merge messages
- Crash-safe merge journal with startup recovery
- Invite system for shared branch access
- Queue system for locked regions
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
build/libs/worldgit-1.0.0-SNAPSHOT.jar
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
- `/wg queue`

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
- `Pos1` / `Pos2` support:
  - Left click to use the current player coordinates
  - Right click to open a sign input
  - Coordinate syntax supports absolute values, `~`, `~+n`, and `~-n`
- Approved branches are merged through a two-step Chest UI:
  - Pick the approved branch first
  - Confirm merge and enter a merge message through a sign input
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
- `worldgit.branch.queue`

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

### Create a Branch

1. Stand in the main world.
2. Select a cuboid region with WorldEdit.
3. Run `/wg create`.
4. Edit inside the generated branch world.

### Submit and Merge

1. Run `/wg submit [id]`.
2. The branch stays editable while it is only waiting for review.
3. An admin reviews the branch.
4. Once approved, the branch becomes read-only.
5. If you want to keep editing after approval, run `/wg forceedit [id]` first to remove approval and return the branch to edit mode immediately.
6. After editing again, run `/wg submit [id]` again for a new review.
7. If no more edits are needed, run `/wg confirm [id]`, choose the target branch in the Chest UI, and enter a merge message.
8. WorldGit merges the branch back into the main world.

### Queue for a Locked Region

1. Select the locked region.
2. Run `/wg queue`.
3. Wait for the unlock notification.
4. Re-run `/wg create`.

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
   - A region lock exists
   - A branch world named like `wg_<id>` exists
   - The selected blocks were copied correctly

### Protection

1. Try placing and breaking blocks in the main world.
2. Try buckets, bone meal, item frames, and armor stands in the main world.
3. Verify all changes are blocked unless the player has
   `worldgit.admin.bypass`.

### Review and Merge

1. Modify blocks in the branch world.
2. Run `/wg submit`.
3. Verify the branch is still editable while it is only waiting for review.
4. Approve with `/wg review approve <id>`.
5. Verify the approved branch becomes read-only until `/wg forceedit <id>` is used.
6. If no new edits were made after approval, run `/wg confirm <id>` to open the branch picker UI, then the final confirmation UI, then the merge-message sign input.
7. If the branch needs more work after approval, run `/wg forceedit <id>` first.
8. After editing again, submit it once more with `/wg submit`.
9. Verify:
   - Players are moved out safely
   - Blocks are merged into the main world
   - The lock is released
   - The branch world is deleted
   - The branch is marked `MERGED`
   - Merge history shows builders, approver, merger, merge message, and region coordinates

### Queue

1. Let player A lock a region.
2. Let player B select the same region and run `/wg queue`.
3. Release the lock by merge or abandon.
4. Verify player B gets notified.

### Recovery

1. Start a merge.
2. Stop the server during the merge process.
3. Restart the server.
4. Verify the merge journal resumes and the branch completes cleanly.

### Backup

1. Run `/wg admin backup`.
2. Verify a backup appears under the configured backup directory.
3. If scheduled backups are enabled, verify old backups are pruned.

## Notes

- This repository currently passes `./gradlew build`.
- No automated unit tests are included yet.
- Runtime validation should still be done on a real Paper 1.21.11 server with
  WorldEdit and Multiverse-Core installed.
