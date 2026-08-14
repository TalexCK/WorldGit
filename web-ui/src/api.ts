import type {
  AiJobStartResponse,
  AiJobStatusResponse,
  AiRuntimeStatus,
  HealthResponse,
  LoginRequestResponse,
  MergeLeaderboardResponse,
  RecentActivityResponse,
  RealshotListResponse,
  SessionResponse,
  ToolsResponse
} from "./types";

class ApiError extends Error {
  constructor(message: string, readonly status?: number) {
    super(message);
    this.name = "ApiError";
  }
}

async function request<T>(
  path: string,
  options: RequestInit = {},
  sessionToken?: string
): Promise<T> {
  const headers = new Headers(options.headers ?? {});
  if (options.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (sessionToken && !headers.has("Authorization")) {
    headers.set("Authorization", `Bearer ${sessionToken}`);
  }

  const response = await fetch(path, { ...options, headers });
  const text = await response.text();
  let data: unknown = {};

  try {
    data = text ? JSON.parse(text) : {};
  } catch {
    throw new ApiError(text || `请求失败: ${response.status}`, response.status);
  }

  if (!response.ok) {
    const message =
      typeof data === "object" && data !== null && "message" in data &&
      typeof (data as { message?: unknown }).message === "string"
        ? (data as { message: string }).message
        : `请求失败: ${response.status}`;
    throw new ApiError(message, response.status);
  }

  return data as T;
}

export const api = {
  getHealth: () => request<HealthResponse>("/api/health"),
  getRecentActivity: (limit = 12) =>
    request<RecentActivityResponse>(`/api/activity/recent?limit=${encodeURIComponent(String(limit))}`),
  getMergeLeaderboard: (limit = 10) =>
    request<MergeLeaderboardResponse>(`/api/stats/merge-leaderboard?limit=${encodeURIComponent(String(limit))}`),
  getAiStatus: () => request<AiRuntimeStatus>("/api/ai/status"),
  login: (secret: string) =>
    request<SessionResponse>("/api/ai/login", {
      method: "POST",
      body: JSON.stringify({ secret })
    }),
  createLoginRequest: (playerId: string) =>
    request<LoginRequestResponse>("/api/auth/login-requests", {
      method: "POST",
      body: JSON.stringify({ playerId })
    }),
  getLoginRequest: (requestId: string) =>
    request<LoginRequestResponse>(`/api/auth/login-requests/${encodeURIComponent(requestId)}`),
  getSession: (token: string) => request<SessionResponse>("/api/ai/session", {}, token),
  getTools: (token: string) => request<ToolsResponse>("/api/ai/tools", {}, token),
  startAiJob: (
    token: string,
    branchId: string,
    prompt: string,
    image: { fileName: string; mimeType: string; base64Data: string } | null
  ) =>
    request<AiJobStartResponse>("/api/ai/run", {
      method: "POST",
      body: JSON.stringify({ branchId, prompt, image })
    }, token),
  getAiJob: (token: string, jobId: string, sinceLog: number) =>
    request<AiJobStatusResponse>(
      `/api/ai/jobs/${encodeURIComponent(jobId)}?sinceLog=${encodeURIComponent(String(sinceLog))}`,
      {},
      token
    ),
  getRealshotRequests: (token: string) =>
    request<RealshotListResponse>("/api/realshots/requests", {}, token),
  createRealshotRequest: (token: string, branchId: string, question: string) =>
    request<{ ok: boolean; request: RealshotListResponse["requests"][number] }>("/api/realshots/requests", {
      method: "POST",
      body: JSON.stringify({ branchId, question })
    }, token).then(payload => payload.request),
  uploadRealshotMedia: (
    token: string,
    requestId: string,
    files: { fileName: string; mimeType: string; base64Data: string }[]
  ) =>
    request<{ ok: boolean; request: RealshotListResponse["requests"][number] }>(`/api/realshots/requests/${encodeURIComponent(requestId)}/media`, {
      method: "POST",
      body: JSON.stringify({ files })
    }, token).then(payload => payload.request)
};

export { ApiError };
