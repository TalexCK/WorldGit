import {
  Button,
  Field,
  Input,
  Label,
  Listbox,
  ListboxButton,
  ListboxOption,
  ListboxOptions,
  Textarea,
  Transition
} from "@headlessui/react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, ApiError } from "../api";
import type {
  AiJobStatusResponse,
  AiRuntimeStatus,
  EditableBranch,
  ImagePayload,
  LogEntry,
  SessionInfo,
  ToolDefinition
} from "../types";
import { formatCompactNumber, formatDateTime, shortUuid } from "../utils";

interface AiPageProps {
  runtime: AiRuntimeStatus | null;
  session: SessionInfo | null;
  sessionToken: string;
  branches: EditableBranch[];
  tools: ToolDefinition[];
  onLoginSuccess: (p: {
    session: SessionInfo;
    runtime: AiRuntimeStatus;
    branches: EditableBranch[];
    realshotBranches: EditableBranch[];
    tools: ToolDefinition[];
    sessionToken: string;
  }) => void;
  onSessionRefresh: (p: {
    session: SessionInfo;
    runtime: AiRuntimeStatus;
    branches: EditableBranch[];
    realshotBranches: EditableBranch[];
  }) => void;
  onLogout: () => void;
}

export function AiPage({
  runtime,
  session,
  sessionToken,
  branches,
  tools,
  onLoginSuccess,
  onSessionRefresh,
  onLogout
}: AiPageProps) {
  const [secret, setSecret] = useState("");
  const [selectedBranch, setSelectedBranch] = useState<EditableBranch | null>(branches[0] ?? null);
  const [prompt, setPrompt] = useState("");
  const [image, setImage] = useState<ImagePayload | null>(null);
  const [lastRun, setLastRun] = useState<AiJobStatusResponse | null>(null);
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const pollingRef = useRef(false);
  const [error, setError] = useState<string | null>(null);
  const [dragActive, setDragActive] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const toolDefinitionMap = useMemo(() => {
    return new Map(tools.map(tool => [tool.name, tool]));
  }, [tools]);

  useEffect(() => {
    if (!branches.length) { setSelectedBranch(null); return; }
    if (!selectedBranch || !branches.some(b => b.id === selectedBranch.id)) {
      setSelectedBranch(branches[0]);
    }
  }, [branches, selectedBranch]);

  // Paste handler for images
  useEffect(() => {
    function handlePaste(e: ClipboardEvent) {
      const items = e.clipboardData?.items;
      if (!items) return;
      for (const item of items) {
        if (item.type.startsWith("image/")) {
          e.preventDefault();
          const file = item.getAsFile();
          if (file) processFile(file);
          return;
        }
      }
    }
    document.addEventListener("paste", handlePaste);
    return () => document.removeEventListener("paste", handlePaste);
  }, []);

  const processFile = useCallback(async (file: File) => {
    const base64Data = await fileToBase64(file);
    setImage({
      fileName: file.name || "pasted-image.png",
      mimeType: file.type,
      base64Data,
      previewUrl: `data:${file.type};base64,${base64Data}`
    });
  }, []);

  function handleDrop(e: React.DragEvent) {
    e.preventDefault();
    setDragActive(false);
    const file = e.dataTransfer.files[0];
    if (file?.type.startsWith("image/")) processFile(file);
  }

  function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (file) processFile(file);
    if (fileInputRef.current) fileInputRef.current.value = "";
  }

  async function handleLogin() {
    if (!secret.trim()) { setError("请输入 Secret。"); return; }
    setLoading(true);
    setError(null);
    try {
      const payload = await api.login(secret.trim());
      const toolsPayload = payload.session.aiAllowed
        ? await api.getTools(payload.session.token)
        : { tools: [] };
      setSelectedBranch(payload.branches[0] ?? null);
      onLoginSuccess({
        session: payload.session,
        runtime: payload.runtime,
        branches: payload.branches,
        realshotBranches: payload.realshotBranches,
        tools: toolsPayload.tools,
        sessionToken: payload.session.token
      });
      setLogs([]);
      setLastRun(null);
      setSecret("");
    } catch (e) {
      setError(extractError(e));
    } finally {
      setLoading(false);
    }
  }

  async function handleRun() {
    if (!sessionToken) { setError("请先登录。"); return; }
    if (!prompt.trim()) { setError("请输入 Prompt。"); return; }
    if (pollingRef.current) { setError("当前还有任务在执行，请等待完成。"); return; }
    setLoading(true);
    setError(null);
    const runPrompt = prompt.trim();
    try {
      const latest = await api.getSession(sessionToken);
      onSessionRefresh({
        session: latest.session,
        runtime: latest.runtime,
        branches: latest.branches,
        realshotBranches: latest.realshotBranches
      });
      const branch = selectedBranch
        ? latest.branches.find(b => b.id === selectedBranch.id) ?? latest.branches[0]
        : latest.branches[0];
      if (!branch) throw new ApiError("当前没有可编辑分支。");
      setSelectedBranch(branch);

      const started = await api.startAiJob(
        latest.session.token,
        branch.id,
        runPrompt,
        image ? { fileName: image.fileName, mimeType: image.mimeType, base64Data: image.base64Data } : null
      );

      const userEntry: LogEntry = {
        level: "info",
        type: "user_prompt",
        message: runPrompt,
        createdAt: new Date().toISOString()
      };
      setLogs(prev => [...prev, userEntry]);
      setPrompt("");
      setImage(null);
      pollingRef.current = true;

      let cursor = 0;
      while (pollingRef.current) {
        await sleep(1000);
        let snapshot: AiJobStatusResponse;
        try {
          snapshot = await api.getAiJob(latest.session.token, started.jobId, cursor);
        } catch (pollErr) {
          setError(extractError(pollErr));
          break;
        }
        if (snapshot.logs.length > 0) {
          setLogs(prev => [...prev, ...snapshot.logs]);
          cursor = snapshot.nextLogCursor;
        }
        if (snapshot.status !== "running") {
          setLastRun(snapshot);
          if (snapshot.status === "error" && snapshot.errorMessage) {
            setError(snapshot.errorMessage);
          }
          break;
        }
      }
    } catch (e) {
      const msg = extractError(e);
      setError(msg);
      setLogs(prev => [
        ...prev,
        { level: "error", type: "request_error", message: msg, createdAt: new Date().toISOString() }
      ]);
    } finally {
      pollingRef.current = false;
      setLoading(false);
    }
  }

  const loggedIn = !!session && !!sessionToken;
  const replyText = useMemo(() => {
    if (lastRun?.finalText?.trim()) {
      return lastRun.finalText.trim();
    }
    const finalLog = [...logs].reverse().find(log => log.type === "assistant_final");
    const payloadText = readStringField(finalLog?.data, "text");
    if (payloadText?.trim()) {
      return payloadText.trim();
    }
    return "";
  }, [logs, lastRun]);

  return (
    <div className="ai-page">
      {!loggedIn ? (
        <div className="card">
          <div className="section-title">登录</div>
          <Field className="hui-field">
            <Label className="hui-label">Web Secret</Label>
            <Input
              className="hui-input"
              type="password"
              value={secret}
              onChange={(e: React.ChangeEvent<HTMLInputElement>) => setSecret(e.target.value)}
              onKeyDown={(e: React.KeyboardEvent) => e.key === "Enter" && handleLogin()}
              placeholder="执行 /secret 获取"
            />
          </Field>
          <Button className="hui-btn hui-btn--primary" onClick={handleLogin} disabled={loading}>
            {loading ? "登录中..." : "登录"}
          </Button>
          {error && <div className="error-msg">{error}</div>}
        </div>
      ) : (
        <>
          <div className="session-bar">
            <div>
              <span className="session-bar-name">{session.playerName}</span>
              <span className="session-bar-meta" style={{ marginLeft: 8 }}>
                {shortUuid(session.playerUuid)} · 到期 {formatDateTime(session.expiresAt)}
              </span>
            </div>
            <Button className="hui-btn hui-btn--ghost hui-btn--sm" onClick={onLogout}>
              登出
            </Button>
          </div>

          <div className="card">
            <div className="section-title">执行</div>
            {!session.aiAllowed && (
              <div className="error-msg" style={{ marginBottom: 12 }}>
                该 Secret 生成时没有 worldgit.ai.use 权限，不能用于 AI。可继续在“实景图片”页使用。
              </div>
            )}

            {/* Branch selector */}
            <Field className="hui-field">
              <Label className="hui-label">目标分支</Label>
              {branches.length > 0 ? (
                <div className="listbox-wrapper">
                  <Listbox value={selectedBranch} onChange={setSelectedBranch} disabled={loading}>
                    <ListboxButton className="listbox-trigger">
                      <span>{selectedBranch ? `${selectedBranch.label} / ${selectedBranch.role === "owner" ? "所有者" : "协作者"}` : "选择分支"}</span>
                      <ChevronIcon />
                    </ListboxButton>
                    <Transition
                      enter="transition-slide-enter"
                      enterFrom="transition-slide-enter"
                      enterTo="transition-slide-enter-to"
                      leave="transition-slide-leave"
                      leaveFrom="transition-slide-leave"
                      leaveTo="transition-slide-leave-to"
                    >
                      <ListboxOptions className="listbox-options">
                        {branches.map(b => (
                          <ListboxOption key={b.id} value={b} className="listbox-option">
                            <div>{b.label} / {b.role === "owner" ? "所有者" : "协作者"} / {b.status}</div>
                            <div className="listbox-option-sub">
                              {b.worldName} · ({b.bounds.minX}, {b.bounds.minY}, {b.bounds.minZ}) → ({b.bounds.maxX}, {b.bounds.maxY}, {b.bounds.maxZ})
                            </div>
                          </ListboxOption>
                        ))}
                      </ListboxOptions>
                    </Transition>
                  </Listbox>
                </div>
              ) : (
                <div style={{ fontSize: 13, color: "var(--text-tertiary)" }}>无可编辑分支</div>
              )}
              {selectedBranch && (
                <div className="branch-info">
                  {selectedBranch.worldName} · ({selectedBranch.bounds.minX}, {selectedBranch.bounds.minY}, {selectedBranch.bounds.minZ}) → ({selectedBranch.bounds.maxX}, {selectedBranch.bounds.maxY}, {selectedBranch.bounds.maxZ})
                </div>
              )}
            </Field>

            {/* Prompt */}
            <Field className="hui-field">
              <Label className="hui-label">Prompt</Label>
              <Textarea
                className="hui-textarea"
                value={prompt}
                onChange={(e: React.ChangeEvent<HTMLTextAreaElement>) => setPrompt(e.target.value)}
                placeholder="描述你要 AI 做的建造任务..."
              />
            </Field>

            {/* Image: drop / paste / upload */}
            <Field className="hui-field">
              <Label className="hui-label">参考图片（可选，支持粘贴/拖放/上传）</Label>
              {!image ? (
                <div
                  className={`drop-zone${dragActive ? " drop-zone--active" : ""}`}
                  onDragOver={e => { e.preventDefault(); setDragActive(true); }}
                  onDragLeave={() => setDragActive(false)}
                  onDrop={handleDrop}
                  onClick={() => fileInputRef.current?.click()}
                >
                  <div>点击上传、拖放、或 Ctrl+V 粘贴图片</div>
                  <div className="drop-zone-hint">PNG / JPEG / GIF / WebP</div>
                  <input
                    ref={fileInputRef}
                    type="file"
                    accept="image/png,image/jpeg,image/gif,image/webp"
                    onChange={handleFileChange}
                    tabIndex={-1}
                  />
                </div>
              ) : (
                <div className="image-preview">
                  <img src={image.previewUrl} alt={image.fileName} />
                  <div className="image-preview-info">
                    <div className="image-preview-name">{image.fileName}</div>
                    <div className="image-preview-type">{image.mimeType}</div>
                  </div>
                  <Button
                    className="hui-btn hui-btn--ghost hui-btn--sm"
                    onClick={() => setImage(null)}
                  >
                    移除
                  </Button>
                </div>
              )}
            </Field>

            <div className="btn-row" style={{ marginTop: 4 }}>
              <Button
                className="hui-btn hui-btn--primary"
                onClick={handleRun}
                disabled={loading || !branches.length || !session.aiAllowed}
              >
                {loading ? "执行中..." : "执行"}
              </Button>
              {(lastRun || logs.length > 0) && (
                <Button
                  className="hui-btn hui-btn--ghost"
                  onClick={() => { setLastRun(null); setLogs([]); setError(null); }}
                  disabled={loading}
                >
                  清空
                </Button>
              )}
            </div>

            {error && <div className="error-msg">{error}</div>}
          </div>

          {/* Results: flat multi-turn log stream */}
          {(lastRun || logs.length > 0) && (
            <div className="result-section">
              {(lastRun && replyText) && (
                <div className="card">
                  <div className="section-title">最新回复</div>
                  <div className="reply-box">{replyText}</div>
                  {lastRun.provider && (
                    <div className="result-chips">
                      <span className="chip">{lastRun.provider} / {lastRun.model}</span>
                      {typeof lastRun.toolRounds === "number" && (
                        <span className="chip">工具轮次 {formatCompactNumber(lastRun.toolRounds)}</span>
                      )}
                      {typeof lastRun.totalBlockChanges === "number" && (
                        <span className="chip">改块 {formatCompactNumber(lastRun.totalBlockChanges)}</span>
                      )}
                    </div>
                  )}
                </div>
              )}

              <div className="card">
                <div className="section-title">运行日志{loading ? "（进行中…）" : ""}</div>
                <div className="timeline">
                  {logs.map((log, i) => {
                    const toolName = log.type === "tool_call"
                      ? (readStringField(log.data, "tool")
                        || log.message.replace(/^模型请求工具:\s*/u, "").trim()
                        || "未知工具")
                      : "";
                    const toolDesc = toolName ? toolDefinitionMap.get(toolName)?.description ?? "" : "";
                    return (
                      <div key={`${log.type}-${i}-${log.createdAt}`} className="log-item">
                        <div className="log-head">
                          <span className="log-type">{log.type}</span>
                          <span className={`log-level${log.level === "warn" ? " log-level--warn" : log.level === "error" ? " log-level--error" : ""}`}>
                            {log.level}
                          </span>
                        </div>
                        <div className="log-msg">
                          {toolName ? `${toolName}${toolDesc ? ` — ${toolDesc}` : ""}` : log.message}
                        </div>
                        <div className="log-time">{formatDateTime(log.createdAt)}</div>
                        {log.data !== undefined && log.data !== null && (
                          <pre className="log-data">{JSON.stringify(log.data, null, 2)}</pre>
                        )}
                      </div>
                    );
                  })}
                </div>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}

function ChevronIcon() {
  return (
    <svg className="listbox-chevron" viewBox="0 0 20 20" fill="currentColor">
      <path fillRule="evenodd" d="M5.23 7.21a.75.75 0 011.06.02L10 11.168l3.71-3.938a.75.75 0 111.08 1.04l-4.25 4.5a.75.75 0 01-1.08 0l-4.25-4.5a.75.75 0 01.02-1.06z" clipRule="evenodd" />
    </svg>
  );
}

function extractError(e: unknown): string {
  if (e instanceof ApiError) return e.message;
  if (e instanceof Error) return e.message;
  return "请求失败";
}

function readStringField(data: unknown, fieldName: string): string {
  if (!data || typeof data !== "object" || !(fieldName in data)) {
    return "";
  }
  const value = (data as Record<string, unknown>)[fieldName];
  return typeof value === "string" ? value : "";
}

function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function fileToBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = typeof reader.result === "string" ? reader.result : "";
      resolve(result.includes(",") ? result.split(",", 2)[1] : result);
    };
    reader.onerror = () => reject(new Error("读取图片失败"));
    reader.readAsDataURL(file);
  });
}
