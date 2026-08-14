interface IframePageProps {
  url?: string;
  title: string;
}

export function IframePage({ url, title }: IframePageProps) {
  if (!url) {
    return <div className="empty-state">未配置 {title} 地址</div>;
  }

  return (
    <div className="iframe-page">
      <div className="iframe-bar">
        <span className="iframe-bar-url">{url}</span>
        <a
          className="hui-btn hui-btn--ghost hui-btn--sm"
          href={url}
          target="_blank"
          rel="noreferrer"
          style={{ textDecoration: "none" }}
        >
          新窗口
        </a>
      </div>
      <iframe className="iframe-frame" title={title} src={url} loading="lazy" referrerPolicy="no-referrer" />
    </div>
  );
}
