export interface HealthResponse {
  status: string;
  plugin: string;
  version: string;
  prefix: string;
  blueMapUrl?: string;
  pointCloudUrl?: string;
  generatedAt: string;
}

export interface AiRuntimeStatus {
  enabled: boolean;
  configured: boolean;
  runtimeAvailable: boolean;
  provider: string;
  model: string;
  openaiMode: string;
  blueMapUrl?: string;
  pointCloudUrl?: string;
  runtimeMessage: string;
  limits: {
    maxToolRounds: number;
    maxBoxBlocks: number;
    maxTotalBlockChanges: number;
    maxPromptCharacters: number;
    maxImageBytes: number;
    sessionTtlMinutes: number;
  };
}

export interface BranchActivityItem {
  branchId: string;
  eventType: string;
  eventAt?: string;
  ownerName: string;
  ownerUuid: string;
  branchLabel: string;
  worldName: string;
  mainWorld: string;
  status: string;
  createdAt: string;
  submittedAt?: string;
  mergedAt?: string;
  mergedByUuid?: string;
  mergeMessage?: string;
}

export interface RecentActivityResponse {
  generatedAt: string;
  limit: number;
  created: BranchActivityItem[];
  submitted: BranchActivityItem[];
  merged: BranchActivityItem[];
}

export interface MergeLeaderboardEntry {
  playerUuid: string;
  playerName: string;
  totalChangedBlocks: number;
  mergedBranchCount: number;
  lastMergedAt?: string;
}

export interface MergeLeaderboardResponse {
  generatedAt: string;
  limit: number;
  leaderboard: MergeLeaderboardEntry[];
}

export interface SessionInfo {
  token: string;
  playerUuid: string;
  playerName: string;
  aiAllowed: boolean;
  expiresAt: string;
}

export interface EditableBranch {
  id: string;
  label: string;
  worldName: string;
  status: string;
  ownerUuid: string;
  ownerName: string;
  role: "owner" | "collaborator";
  bounds: {
    minX: number;
    minY: number;
    minZ: number;
    maxX: number;
    maxY: number;
    maxZ: number;
  };
}

export interface SessionResponse {
  ok: boolean;
  session: SessionInfo;
  runtime: AiRuntimeStatus;
  branches: EditableBranch[];
  realshotBranches: EditableBranch[];
}

export interface LoginRequestResponse {
  ok: boolean;
  requestId: string;
  playerUuid: string;
  playerName: string;
  status: "pending" | "accepted" | "denied" | "expired";
  expiresAt: string;
  sessionPayload?: SessionResponse;
}

export interface ToolDefinition {
  name: string;
  description: string;
  parametersSchema: Record<string, unknown>;
}

export interface ToolsResponse extends SessionResponse {
  tools: ToolDefinition[];
}

export interface LogEntry {
  level: "info" | "warn" | "error" | string;
  type: string;
  message: string;
  createdAt: string;
  data?: unknown;
}

export interface AiJobStartResponse {
  ok: boolean;
  jobId: string;
  session: SessionInfo;
  branch: EditableBranch;
  sourceBranchId: string;
  previewBranchId: string;
}

export interface AiJobStatusResponse {
  ok: boolean;
  jobId: string;
  status: "running" | "done" | "error";
  previewBranchId: string;
  sourceBranchId: string;
  nextLogCursor: number;
  logs: LogEntry[];
  provider?: string;
  model?: string;
  finalText?: string;
  toolRounds?: number;
  totalBlockChanges?: number;
  errorMessage?: string;
}

export interface ImagePayload {
  fileName: string;
  mimeType: string;
  base64Data: string;
  previewUrl: string;
}

export interface RealshotMedia {
  id: string;
  requestId: string;
  branchId: string;
  uploaderUuid: string;
  uploaderName: string;
  fileName: string;
  mimeType: string;
  fileSize: number;
  createdAt: string;
  url: string;
  kind: "image" | "video";
}

export interface RealshotRequest {
  id: string;
  branchId: string;
  branchLabel: string;
  branchWorldName: string;
  requesterUuid: string;
  requesterName: string;
  question: string;
  createdAt: string;
  media: RealshotMedia[];
}

export interface RealshotListResponse {
  ok: boolean;
  generatedAt: string;
  requests: RealshotRequest[];
}
