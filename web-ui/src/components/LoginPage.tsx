import { Button, Field, Input, Label } from "@headlessui/react";
import type { ChangeEvent, KeyboardEvent } from "react";
import { useRef, useState } from "react";
import { api, ApiError } from "../api";
import type { LoginRequestResponse, SessionResponse } from "../types";
import { formatDateTime } from "../utils";

interface LoginPageProps {
  onAuthenticated: (payload: SessionResponse) => void;
}

export function LoginPage({ onAuthenticated }: LoginPageProps) {
  const [playerId, setPlayerId] = useState("");
  const [request, setRequest] = useState<LoginRequestResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pollingRef = useRef(false);

  async function startLogin() {
    if (!playerId.trim()) { setError("请输入玩家ID。"); return; }
    setLoading(true);
    setError(null);
    setRequest(null);
    pollingRef.current = true;
    try {
      const created = await api.createLoginRequest(playerId.trim());
      setRequest(created);
      let current = created;
      while (pollingRef.current && current.status === "pending") {
        await sleep(1000);
        current = await api.getLoginRequest(created.requestId);
        setRequest(current);
        if (current.status === "accepted" && current.sessionPayload) {
          pollingRef.current = false;
          onAuthenticated(current.sessionPayload);
          return;
        }
        if (current.status === "denied") {
          throw new ApiError("玩家已拒绝本次登录。");
        }
        if (current.status === "expired") {
          throw new ApiError("登录请求已过期，请重新发起。");
        }
      }
    } catch (e) {
      setError(extractError(e));
    } finally {
      pollingRef.current = false;
      setLoading(false);
    }
  }

  return (
    <div className="login-page">
      <div className="login-panel">
        <div className="login-brand">WorldGit</div>
        <div className="login-title">Web 登录</div>
        <div className="login-subtitle">输入在线玩家ID后，在服务器聊天消息中确认登录。</div>
        <Field className="hui-field">
          <Label className="hui-label">玩家ID</Label>
          <Input
            className="hui-input"
            value={playerId}
            onChange={(event: ChangeEvent<HTMLInputElement>) => setPlayerId(event.target.value)}
            onKeyDown={(event: KeyboardEvent) => event.key === "Enter" && startLogin()}
            placeholder="例如 Steve"
            disabled={loading}
            autoFocus
          />
        </Field>
        <Button className="hui-btn hui-btn--primary" onClick={startLogin} disabled={loading}>
          {loading ? "等待确认..." : "登录"}
        </Button>
        {request && (
          <div className="login-status">
            已发送给 {request.playerName}，有效期至 {formatDateTime(request.expiresAt)}
          </div>
        )}
        {error && <div className="error-msg">{error}</div>}
      </div>
    </div>
  );
}

function extractError(e: unknown): string {
  if (e instanceof ApiError) return e.message;
  if (e instanceof Error) return e.message;
  return "请求失败";
}

function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}
