import { build } from "esbuild";
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const outDir = path.resolve(__dirname, "../src/main/resources/web");
const result = await build({
  entryPoints: [path.resolve(__dirname, "src/main.tsx")],
  bundle: true,
  write: false,
  outdir: path.resolve(__dirname, ".tmp-build"),
  minify: true,
  sourcemap: false,
  target: ["es2020"],
  jsx: "automatic",
  loader: { ".ts": "ts", ".tsx": "tsx", ".css": "css" }
});

const jsFile = result.outputFiles.find(file => file.path.endsWith(".js"));
const cssFile = result.outputFiles.find(file => file.path.endsWith(".css"));

if (!jsFile) {
  throw new Error("未生成 JS 构建产物");
}

const js = jsFile.text;
const css = cssFile?.text ?? "";

const html = `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>WorldGit 控制台</title>
  <style>${css}</style>
</head>
<body>
  <div id="root"></div>
  <script>${js}</script>
</body>
</html>
`;

await mkdir(outDir, { recursive: true });
await writeFile(path.join(outDir, "index.html"), html, "utf8");
