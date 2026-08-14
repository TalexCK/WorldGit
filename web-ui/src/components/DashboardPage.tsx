import type {
  BranchActivityItem,
  HealthResponse,
  MergeLeaderboardEntry,
  MergeLeaderboardResponse,
  RecentActivityResponse
} from "../types";
import { formatCompactNumber, formatDateTime, shortUuid } from "../utils";

interface DashboardPageProps {
  health: HealthResponse | null;
  activity: RecentActivityResponse | null;
  leaderboard: MergeLeaderboardResponse | null;
  blueMapUrl?: string;
  pointCloudUrl?: string;
  loading: boolean;
  error: string | null;
  onRefresh: () => void;
  onOpenAi: () => void;
}

const activityLanes = [
  {
    key: "created",
    title: "最近创建",
    description: "新开的分支会先出现在这里。",
    tone: "blue"
  },
  {
    key: "submitted",
    title: "最近提交",
    description: "等待审核确认的分支。",
    tone: "amber"
  },
  {
    key: "merged",
    title: "最近合并",
    description: "已经进入主世界的改动。",
    tone: "green"
  }
] as const;

export function DashboardPage({
  health,
  activity,
  leaderboard,
  blueMapUrl,
  pointCloudUrl,
  loading,
  error,
  onRefresh,
  onOpenAi
}: DashboardPageProps) {
  const generatedAt = activity?.generatedAt ?? leaderboard?.generatedAt ?? health?.generatedAt;
  const createdCount = activity?.created.length ?? 0;
  const submittedCount = activity?.submitted.length ?? 0;
  const mergedCount = activity?.merged.length ?? 0;
  const leaderboardCount = leaderboard?.leaderboard.length ?? 0;

  return (
    <div className="dashboard-page">
      <section className="hero-card">
        <div className="hero-main">
          <div className="hero-eyebrow">WORLDGIT DASHBOARD</div>
          <h1 className="hero-title">{health?.prefix ?? "WorldGit"} 分支总览</h1>
          <p className="hero-description">
            首页默认展示最近分支动态和合并排行榜，AI、BlueMap、点云保留在独立入口。
          </p>
          <div className="hero-pill-row">
            <span className="info-pill">
              <span className={`dot ${health?.status === "ok" ? "dot--green" : "dot--amber"}`} />
              {health?.status === "ok" ? "服务在线" : "服务离线"}
            </span>
            <span className="info-pill">版本 {health?.version ?? "-"}</span>
            <span className="info-pill">更新时间 {formatDateTime(generatedAt)}</span>
            <span className="info-pill">插件 {health?.plugin ?? "WorldGit"}</span>
          </div>
          <div className="btn-row hero-actions">
            <button type="button" className="hui-btn hui-btn--primary" onClick={onOpenAi}>
              打开 AI
            </button>
            <button type="button" className="hui-btn hui-btn--ghost" onClick={onRefresh} disabled={loading}>
              {loading ? "刷新中..." : "刷新数据"}
            </button>
          </div>
        </div>

        <aside className="hero-links">
          <div className="hero-links-title">快捷入口</div>
          <div className="hero-links-grid">
            <button type="button" className="quick-link-card" onClick={onOpenAi}>
              <span className="quick-link-name">AI 工作台</span>
              <span className="quick-link-meta">网页输入玩家ID并在服务器内确认后使用</span>
            </button>
            {blueMapUrl ? (
              <a className="quick-link-card" href={blueMapUrl} target="_blank" rel="noreferrer">
                <span className="quick-link-name">BlueMap</span>
                <span className="quick-link-meta">地图预览与定位</span>
              </a>
            ) : (
              <div className="quick-link-card quick-link-card--muted">
                <span className="quick-link-name">BlueMap</span>
                <span className="quick-link-meta">当前未配置地址</span>
              </div>
            )}
            {pointCloudUrl ? (
              <a className="quick-link-card" href={pointCloudUrl} target="_blank" rel="noreferrer">
                <span className="quick-link-name">点云</span>
                <span className="quick-link-meta">查看导出的空间点云</span>
              </a>
            ) : (
              <div className="quick-link-card quick-link-card--muted">
                <span className="quick-link-name">点云</span>
                <span className="quick-link-meta">当前未配置地址</span>
              </div>
            )}
          </div>
        </aside>
      </section>

      {error && <div className="error-msg dashboard-error">{error}</div>}

      <section className="dashboard-stats">
        <StatCard label="最近创建" value={createdCount} tone="blue" detail="新的分支入口" />
        <StatCard label="最近提交" value={submittedCount} tone="amber" detail="等待审核确认" />
        <StatCard label="最近合并" value={mergedCount} tone="green" detail="已进入主世界" />
        <StatCard label="上榜玩家" value={leaderboardCount} tone="slate" detail="活跃贡献者排行" />
      </section>

      <section className="dashboard-grid">
        <div className="card dashboard-panel">
          <div className="panel-header">
            <div>
              <div className="section-title">分支动态</div>
              <div className="panel-subtitle">按创建、提交、合并三个阶段展示最近分支事件。</div>
            </div>
            <span className="panel-note">最近 {activity?.limit ?? 0} 条</span>
          </div>
          <div className="activity-grid">
            {activityLanes.map((lane) => (
              <ActivityLane
                key={lane.key}
                title={lane.title}
                description={lane.description}
                tone={lane.tone}
                items={activity?.[lane.key] ?? []}
              />
            ))}
          </div>
        </div>

        <div className="card dashboard-panel leaderboard-panel">
          <div className="panel-header">
            <div>
              <div className="section-title">合并排行榜</div>
              <div className="panel-subtitle">按累计合并改块数排序，辅助识别近期核心贡献者。</div>
            </div>
            <span className="panel-note">Top {leaderboard?.limit ?? 0}</span>
          </div>
          <div className="leaderboard-list">
            {leaderboard?.leaderboard.length ? (
              leaderboard.leaderboard.map((entry, index) => (
                <LeaderboardRow key={entry.playerUuid} entry={entry} rank={index + 1} />
              ))
            ) : (
              <div className="empty-panel">暂无排行榜数据</div>
            )}
          </div>
        </div>
      </section>
    </div>
  );
}

interface StatCardProps {
  label: string;
  value: number;
  tone: "blue" | "amber" | "green" | "slate";
  detail: string;
}

function StatCard({ label, value, tone, detail }: StatCardProps) {
  return (
    <div className={`stat-card stat-card--${tone}`}>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{formatCompactNumber(value)}</div>
      <div className="stat-detail">{detail}</div>
    </div>
  );
}

interface ActivityLaneProps {
  title: string;
  description: string;
  tone: "blue" | "amber" | "green";
  items: BranchActivityItem[];
}

function ActivityLane({ title, description, tone, items }: ActivityLaneProps) {
  return (
    <div className={`activity-lane activity-lane--${tone}`}>
      <div className="activity-lane-head">
        <div className="activity-lane-title">{title}</div>
        <div className="activity-lane-desc">{description}</div>
      </div>
      <div className="branch-list">
        {items.length ? (
          items.map((item) => (
            <article key={`${item.eventType}-${item.branchId}-${item.eventAt ?? item.createdAt}`} className="branch-item">
              <div className="branch-item-top">
                <div className="branch-item-title">{resolveBranchTitle(item)}</div>
                <span className="branch-item-status">{formatStatus(item.status)}</span>
              </div>
              <div className="branch-item-id">ID · {formatBranchId(item.branchId)}</div>
              <div className="branch-item-meta">{item.ownerName} · {item.worldName}</div>
              <div className="branch-item-time">{formatDateTime(item.eventAt ?? item.createdAt)}</div>
              {item.mergeMessage ? <div className="branch-item-note">{item.mergeMessage}</div> : null}
            </article>
          ))
        ) : (
          <div className="empty-panel">暂无分支事件</div>
        )}
      </div>
    </div>
  );
}

function LeaderboardRow({ entry, rank }: { entry: MergeLeaderboardEntry; rank: number }) {
  return (
    <article className="leaderboard-row">
      <div className={`leaderboard-rank${rank <= 3 ? " leaderboard-rank--top" : ""}`}>#{rank}</div>
      <div className="leaderboard-main">
        <div className="leaderboard-name">{entry.playerName}</div>
        <div className="leaderboard-meta">
          {shortUuid(entry.playerUuid)} · 最近合并 {formatDateTime(entry.lastMergedAt)}
        </div>
      </div>
      <div className="leaderboard-values">
        <div className="leaderboard-value">{formatCompactNumber(entry.totalChangedBlocks)}</div>
        <div className="leaderboard-label">改块 / {formatCompactNumber(entry.mergedBranchCount)} 分支</div>
      </div>
    </article>
  );
}

function resolveBranchTitle(item: BranchActivityItem) {
  const label = item.branchLabel?.trim();
  if (!label || label === item.branchId || looksLikeOpaqueId(label)) {
    return formatBranchId(item.branchId);
  }
  return label;
}

function formatBranchId(value?: string) {
  return value ? value.slice(0, 8) : "-";
}

function looksLikeOpaqueId(value?: string) {
  if (!value) {
    return false;
  }
  const normalized = value.trim();
  return /^[a-f0-9]{12,}$/i.test(normalized) || /^wg_[a-f0-9]{8,}$/i.test(normalized);
}

function formatStatus(value?: string) {
  switch ((value ?? "").toLowerCase()) {
    case "open":
      return "开发中";
    case "submitted":
      return "待审核";
    case "approved":
      return "已批准";
    case "merged":
      return "已合并";
    case "abandoned":
      return "已关闭";
    default:
      return value || "未知";
  }
}
