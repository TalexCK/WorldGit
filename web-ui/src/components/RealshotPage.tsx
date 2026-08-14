import {
  Button,
  Field,
  Label,
  Listbox,
  ListboxButton,
  ListboxOption,
  ListboxOptions,
  Textarea,
  Transition
} from "@headlessui/react";
import { useEffect, useRef, useState } from "react";
import type { ChangeEvent } from "react";
import { api, ApiError } from "../api";
import type { EditableBranch, RealshotRequest, SessionInfo } from "../types";
import { formatDateTime, shortUuid } from "../utils";

interface RealshotPageProps {
  session: SessionInfo | null;
  sessionToken: string;
  branches: EditableBranch[];
  onOpenLogin: () => void;
}

export function RealshotPage({ session, sessionToken, branches, onOpenLogin }: RealshotPageProps) {
  const [requests, setRequests] = useState<RealshotRequest[]>([]);
  const [selectedBranch, setSelectedBranch] = useState<EditableBranch | null>(branches[0] ?? null);
  const [question, setQuestion] = useState("");
  const [uploadingRequestId, setUploadingRequestId] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!branches.length) { setSelectedBranch(null); return; }
    if (!selectedBranch || !branches.some(branch => branch.id === selectedBranch.id)) {
      setSelectedBranch(branches[0]);
    }
  }, [branches, selectedBranch]);

  useEffect(() => {
    if (!sessionToken) return;
    void refresh();
  }, [sessionToken]);

  async function refresh() {
    if (!sessionToken) return;
    setLoading(true);
    setError(null);
    try {
      const payload = await api.getRealshotRequests(sessionToken);
      setRequests(payload.requests);
    } catch (e) {
      setError(extractError(e));
    } finally {
      setLoading(false);
    }
  }

  async function createRequest() {
    if (!sessionToken) { onOpenLogin(); return; }
    if (!selectedBranch) { setError("必须先绑定一个分支。"); return; }
    if (!question.trim()) { setError("请填写需要的实景图片。"); return; }
    setLoading(true);
    setError(null);
    try {
      const request = await api.createRealshotRequest(sessionToken, selectedBranch.id, question.trim());
      setRequests(prev => [request, ...prev.filter(item => item.id !== request.id)]);
      setQuestion("");
    } catch (e) {
      setError(extractError(e));
    } finally {
      setLoading(false);
    }
  }

  async function uploadFiles(requestId: string, files: FileList | null) {
    if (!sessionToken || !files?.length) return;
    setUploadingRequestId(requestId);
    setError(null);
    try {
      const payloadFiles = await Promise.all(Array.from(files).map(async file => ({
        fileName: file.name || "upload",
        mimeType: file.type,
        base64Data: await fileToBase64(file)
      })));
      const updated = await api.uploadRealshotMedia(sessionToken, requestId, payloadFiles);
      setRequests(prev => prev.map(item => item.id === updated.id ? updated : item));
    } catch (e) {
      setError(extractError(e));
    } finally {
      setUploadingRequestId(null);
      if (fileInputRef.current) fileInputRef.current.value = "";
    }
  }

  if (!session || !sessionToken) {
    return (
      <div className="ai-page">
        <div className="card">
          <div className="section-title">实景图片</div>
          <div className="empty-panel">请先在登录页输入玩家ID，并在服务器内确认。</div>
          <Button className="hui-btn hui-btn--primary" onClick={onOpenLogin} style={{ marginTop: 12 }}>
            去登录
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="realshot-page">
      <div className="session-bar">
        <div>
          <span className="session-bar-name">{session.playerName}</span>
          <span className="session-bar-meta" style={{ marginLeft: 8 }}>
            {shortUuid(session.playerUuid)} · 到期 {formatDateTime(session.expiresAt)}
          </span>
        </div>
        <Button className="hui-btn hui-btn--ghost hui-btn--sm" onClick={refresh} disabled={loading}>
          {loading ? "刷新中..." : "刷新"}
        </Button>
      </div>

      <div className="card">
        <div className="section-title">提出实景图片需求</div>
        <Field className="hui-field">
          <Label className="hui-label">绑定分支</Label>
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
                    {branches.map(branch => (
                      <ListboxOption key={branch.id} value={branch} className="listbox-option">
                        <div>{branch.label} / {branch.role === "owner" ? "所有者" : "协作者"} / {branch.status}</div>
                        <div className="listbox-option-sub">{branch.worldName}</div>
                      </ListboxOption>
                    ))}
                  </ListboxOptions>
                </Transition>
              </Listbox>
            </div>
          ) : (
            <div className="empty-panel">当前没有可绑定的参与分支。</div>
          )}
        </Field>
        <Field className="hui-field">
          <Label className="hui-label">需要的图片或视频</Label>
          <Textarea
            className="hui-textarea"
            value={question}
            onChange={(e: ChangeEvent<HTMLTextAreaElement>) => setQuestion(e.target.value)}
            placeholder="说明需要拍摄的角度、对象、细节或参考范围..."
          />
        </Field>
        <Button className="hui-btn hui-btn--primary" onClick={createRequest} disabled={loading || !branches.length}>
          提交需求
        </Button>
        {error && <div className="error-msg">{error}</div>}
      </div>

      <div className="realshot-list">
        {requests.length ? requests.map(request => (
          <article className="card realshot-card" key={request.id}>
            <div className="realshot-head">
              <div>
                <div className="realshot-branch">{request.branchLabel}</div>
                <div className="realshot-meta">
                  {request.requesterName} · {formatDateTime(request.createdAt)} · {request.branchWorldName}
                </div>
              </div>
              <label className={`hui-btn hui-btn--ghost hui-btn--sm${uploadingRequestId === request.id ? " is-disabled" : ""}`}>
                {uploadingRequestId === request.id ? "上传中..." : "提供素材"}
                <input
                  ref={fileInputRef}
                  type="file"
                  accept="image/*,video/*"
                  multiple
                  onChange={event => uploadFiles(request.id, event.target.files)}
                  disabled={uploadingRequestId === request.id}
                />
              </label>
            </div>
            <div className="realshot-question">{request.question}</div>
            {request.media.length ? (
              <div className="realshot-media-grid">
                {request.media.map(media => (
                  <a className="realshot-media" key={media.id} href={media.url} target="_blank" rel="noreferrer">
                    {media.kind === "video" ? (
                      <video src={media.url} controls preload="metadata" />
                    ) : (
                      <img src={media.url} alt={media.fileName} loading="lazy" />
                    )}
                    <span>{media.uploaderName} · {formatDateTime(media.createdAt)}</span>
                  </a>
                ))}
              </div>
            ) : (
              <div className="empty-panel">还没有玩家提供素材。</div>
            )}
          </article>
        )) : (
          <div className="card">
            <div className="empty-panel">当前可见分支还没有实景图片需求。</div>
          </div>
        )}
      </div>
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

function fileToBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = typeof reader.result === "string" ? reader.result : "";
      resolve(result.includes(",") ? result.split(",", 2)[1] : result);
    };
    reader.onerror = () => reject(new Error("读取文件失败"));
    reader.readAsDataURL(file);
  });
}
