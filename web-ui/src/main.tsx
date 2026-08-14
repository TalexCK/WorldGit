import { Tab, TabGroup, TabList, TabPanel, TabPanels } from "@headlessui/react";
import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { api } from "./api";
import { AiPage } from "./components/AiPage";
import { DashboardPage } from "./components/DashboardPage";
import { IframePage } from "./components/IframePage";
import { LoginPage } from "./components/LoginPage";
import { RealshotPage } from "./components/RealshotPage";
import type {
  AiRuntimeStatus,
  EditableBranch,
  HealthResponse,
  MergeLeaderboardResponse,
  RecentActivityResponse,
  SessionResponse,
  SessionInfo,
  ToolDefinition
} from "./types";
import { deleteCookie, getCookie, setCookie } from "./utils";
import "./styles.css";

const DASHBOARD_ACTIVITY_LIMIT = 12;
const DASHBOARD_LEADERBOARD_LIMIT = 10;

function App() {
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [runtime, setRuntime] = useState<AiRuntimeStatus | null>(null);
  const [activity, setActivity] = useState<RecentActivityResponse | null>(null);
  const [leaderboard, setLeaderboard] = useState<MergeLeaderboardResponse | null>(null);
  const [session, setSession] = useState<SessionInfo | null>(null);
  const [branches, setBranches] = useState<EditableBranch[]>([]);
  const [realshotBranches, setRealshotBranches] = useState<EditableBranch[]>([]);
  const [tools, setTools] = useState<ToolDefinition[]>([]);
  const [dashboardLoading, setDashboardLoading] = useState(true);
  const [dashboardError, setDashboardError] = useState<string | null>(null);
  const [authLoading, setAuthLoading] = useState(() => !!getCookie("wg_session"));
  const [sessionToken, setSessionToken] = useState(() => getCookie("wg_session"));
  const [selectedTabKey, setSelectedTabKey] = useState("home");

  useEffect(() => {
    void loadDashboard();
  }, []);

  useEffect(() => {
    if (!sessionToken) return;
    void restoreSession(sessionToken);
  }, [sessionToken]);

  async function loadDashboard() {
    setDashboardLoading(true);
    setDashboardError(null);

    const [healthResult, activityResult, leaderboardResult] = await Promise.allSettled([
      api.getHealth(),
      api.getRecentActivity(DASHBOARD_ACTIVITY_LIMIT),
      api.getMergeLeaderboard(DASHBOARD_LEADERBOARD_LIMIT)
    ]);
    const aiStatus = await api.getAiStatus().catch(() => null);
    if (aiStatus) {
      setRuntime(aiStatus);
    }

    const failures: string[] = [];

    if (healthResult.status === "fulfilled") {
      setHealth(healthResult.value);
    } else {
      failures.push("主页状态");
    }

    if (activityResult.status === "fulfilled") {
      setActivity(activityResult.value);
    } else {
      failures.push("分支动态");
    }

    if (leaderboardResult.status === "fulfilled") {
      setLeaderboard(leaderboardResult.value);
    } else {
      failures.push("排行榜");
    }

    setDashboardError(failures.length ? `部分数据加载失败：${failures.join("、")}` : null);
    setDashboardLoading(false);
  }

  async function restoreSession(token: string) {
    setAuthLoading(true);
    try {
      const sessionPayload = await api.getSession(token);
      const toolsPayload = sessionPayload.session.aiAllowed
        ? await api.getTools(token)
        : { tools: [] };
      setSession(sessionPayload.session);
      setBranches(sessionPayload.branches);
      setRealshotBranches(sessionPayload.realshotBranches);
      setRuntime(sessionPayload.runtime);
      setTools(toolsPayload.tools);
    } catch {
      deleteCookie("wg_session");
      setSessionToken("");
      setSession(null);
      setBranches([]);
      setRealshotBranches([]);
      setTools([]);
    } finally {
      setAuthLoading(false);
    }
  }

  async function handleAuthenticated(payload: SessionResponse) {
    const token = payload.session.token;
    setSession(payload.session);
    setBranches(payload.branches);
    setRealshotBranches(payload.realshotBranches);
    setRuntime(payload.runtime);
    setSessionToken(token);
    setCookie("wg_session", token, 7 * 24 * 60 * 60);
    const toolsPayload = payload.session.aiAllowed
      ? await api.getTools(token).catch(() => ({ tools: [] }))
      : { tools: [] };
    setTools(toolsPayload.tools);
  }

  const displayName = health?.prefix || "WorldGit";
  const blueMapUrl = runtime?.blueMapUrl || health?.blueMapUrl;
  const pointCloudUrl = runtime?.pointCloudUrl || health?.pointCloudUrl;

  if (authLoading) {
    return (
      <div className="login-page">
        <div className="login-panel">
          <div className="login-brand">WorldGit</div>
          <div className="login-status">正在恢复登录状态...</div>
        </div>
      </div>
    );
  }

  if (!session || !sessionToken) {
    return <LoginPage onAuthenticated={handleAuthenticated} />;
  }

  const tabs: { key: string; label: string; content: React.ReactNode }[] = [
    {
      key: "home",
      label: "首页",
      content: (
        <DashboardPage
          health={health}
          activity={activity}
          leaderboard={leaderboard}
          blueMapUrl={blueMapUrl}
          pointCloudUrl={pointCloudUrl}
          loading={dashboardLoading}
          error={dashboardError}
          onRefresh={loadDashboard}
          onOpenAi={() => setSelectedTabKey("ai")}
        />
      )
    },
    {
      key: "ai",
      label: "AI",
      content: (
        <AiPage
          runtime={runtime}
          session={session}
          sessionToken={sessionToken}
          branches={branches}
          tools={tools}
          onLoginSuccess={({ session: s, runtime: r, branches: b, realshotBranches: rb, tools: tl, sessionToken: tok }) => {
            setSession(s);
            setRuntime(r);
            setBranches(b);
            setRealshotBranches(rb);
            setTools(tl);
            setSessionToken(tok);
            setCookie("wg_session", tok, 7 * 24 * 60 * 60);
          }}
          onSessionRefresh={({ session: s, runtime: r, branches: b, realshotBranches: rb }) => {
            setSession(s);
            setRuntime(r);
            setBranches(b);
            setRealshotBranches(rb);
          }}
          onLogout={() => {
            deleteCookie("wg_session");
            setSessionToken("");
            setSession(null);
            setBranches([]);
            setRealshotBranches([]);
            setTools([]);
          }}
        />
      )
    },
    {
      key: "realshots",
      label: "实景图片",
      content: (
        <RealshotPage
          session={session}
          sessionToken={sessionToken}
          branches={realshotBranches}
          onOpenLogin={() => setSelectedTabKey("ai")}
        />
      )
    }
  ];

  if (blueMapUrl) {
    tabs.push({
      key: "bluemap",
      label: "BlueMap",
      content: <IframePage url={blueMapUrl} title="BlueMap" />
    });
  }

  if (pointCloudUrl) {
    tabs.push({
      key: "pointcloud",
      label: "点云",
      content: <IframePage url={pointCloudUrl} title="点云" />
    });
  }

  const selectedIndex = Math.max(0, tabs.findIndex((tab) => tab.key === selectedTabKey));

  return (
    <TabGroup
      as="div"
      className="shell"
      selectedIndex={selectedIndex}
      onChange={(index) => setSelectedTabKey(tabs[index]?.key ?? "home")}
    >
      <header className="header">
        <span className="header-title">{displayName}</span>
        <TabList className="tab-list">
          {tabs.map((tab) => (
            <Tab key={tab.key} className="tab-btn">{tab.label}</Tab>
          ))}
        </TabList>
        <div className="header-right">
          <span className="header-status">
            <span className={`dot ${health?.status === "ok" ? "dot--green" : "dot--amber"}`} />
            {health?.status === "ok" ? "在线" : "离线"}
          </span>
        </div>
      </header>

      <TabPanels className="tab-panels">
        {tabs.map((tab) => (
          <TabPanel key={tab.key} className="tab-panel">
            {tab.content}
          </TabPanel>
        ))}
      </TabPanels>
    </TabGroup>
  );
}

const rootElement = document.getElementById("root");
if (!rootElement) throw new Error("找不到 root 节点");

createRoot(rootElement).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
