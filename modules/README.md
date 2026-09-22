# Plugin Market

This directory **is** the WebToApp Plugin Market. Every plugin the
in-app market shows is fetched directly from this folder over
`raw.githubusercontent.com`, with `cdn.jsdelivr.net/gh/` as a CDN fallback.
There is no other backend. A merged PR is published the moment it lands on
`main`.

This file is the canonical contribution guide. The root `README.md` and
`.github/CONTRIBUTING.md` only keep high-level summaries and link here for
the actual submission rules.

> **English** · [简体中文](#中文)

---

## At a glance

```
modules/
├── registry.json              ← index file the app downloads first
├── submissions.json           ← CI-generated PR / contributor metadata
├── README.md                  ← this file
└── <plugin-path>/             ← one folder per plugin
    ├── plugin.json            ← plugin manifest (required)
    ├── main.js                ← plugin source (required)
    ├── style.css              ← optional CSS, auto-injected at document-start
    ├── panel.html             ← optional plugin UI (hosted in a sheet/window)
    └── icon.png               ← optional 256 KB-max icon (also .svg/.webp/.jpg)
```

A plugin is a plain **HTML + CSS + JavaScript** package — the same trio you
already know, no custom DSL. `main.js` runs inside the page like a userscript,
`style.css` is injected automatically, and `panel.html` (if present) becomes
the plugin's own interface, hosted in a bottom sheet / floating window /
fullscreen — whichever the user picked.

When a user opens the market, the app fetches both `registry.json` and
`submissions.json`, then renders each entry that appears in **both** —
that's how we guarantee the catalog only shows plugins whose PR has
actually been merged. Tapping **Install** downloads the package files and
hands them to the plugin store. The registry is cached for one hour.

> **Compatibility:** the catalog is `plugin.json`-only. The retired
> self-developed `module.json` format was removed from the market together
> with its old submissions — CI rejects it.

---

## Submitting a plugin

1. **Fork** [`shiaho777/web-to-app`](https://github.com/shiaho777/web-to-app).
2. Create a unique kebab-case folder under `modules/`, e.g.
   `modules/dark-reader-lite/`. The folder name is your `path` in
   `registry.json`.
3. Add at minimum:
   - `plugin.json` — manifest, see [schema](#pluginjson-schema)
   - `main.js` — code that runs in the WebView
4. Add a matching entry to [`registry.json`](registry.json). Keep `id`,
   `name`, and `version` consistent between both files.
5. Open a pull request. CI validates the catalog — see
   [Local validation](#local-validation) to self-check first.
6. Once merged, every client picks up the new plugin on its next refresh.

There is no separate developer account, no API key, no submission portal.

---

## `plugin.json` schema

```json
{
  "id": "globally-unique-id",
  "name": "Display Name",
  "version": "1.0.0",
  "description": "Paragraph shown on the install page.",
  "author": "your name",
  "homepage": "https://…",
  "icon": "auto_awesome",
  "matches": ["*://*.example.com/*"],
  "excludeMatches": [],
  "runAt": "document_end",
  "permissions": ["STORAGE"],
  "toolbar": true
}
```

| Field | Required | Notes |
|-------|----------|-------|
| `id` | yes | Globally unique; never reuse. |
| `name` | yes | Display name. |
| `version` | | Semver string; bump it for updates. |
| `description` | | Shown in the market listing. |
| `author` / `homepage` | | Credits. |
| `icon` | | [Material Icons](https://fonts.google.com/icons) name, or an icon file in the package. For a branded picture prefer the registry-level `iconUrl`. |
| `matches` | | Chrome-style match patterns; `/regex/` denotes a regex (Tampermonkey `@include` convention). Default `["*"]`. |
| `excludeMatches` | | Same syntax; wins over `matches`. |
| `runAt` | | `document_start` · `document_end` (default) · `document_idle`. |
| `permissions` | | Capabilities used from `hcj.*`: `STORAGE` (config KV), `FETCH` (cross-origin), `NOTIFY`, `BADGE`, `CLIPBOARD`, `DOWNLOAD`. Page DOM access needs no permission. |
| `toolbar` | | Show an entry for this plugin in the plugin surface. Default `true`. |

## `registry.json` entry schema

Unchanged: `id`, `path`, `name`, `description`, `icon`, `category`, `tags`,
`version`, `minAppVersion`, `author`, `runAt`, `permissions`, `urlMatches`,
`hasCss`, optional `iconUrl`, `sourceType` (`CUSTOM` / `CHROME_EXTENSION` +
`storeId` for Chrome Web Store passthrough entries).

## `main.js` runtime contract

`main.js` executes inside the page with a scoped `hcj` API object:

```js
hcj.id                    // plugin id
hcj.manifest              // {id, name, version, icon, hasPanel}
hcj.lang                  // app language code

hcj.config.get(key, def)  // persistent KV (requires STORAGE permission)
hcj.config.set(key, val)
hcj.config.remove(key)
hcj.config.all()

hcj.fetch(url, opts)      // cross-origin fetch (requires FETCH) → Promise
hcj.notify(title, body)   // Android notification (requires NOTIFY)
hcj.badge(text, color)    // toolbar badge (requires BADGE)

hcj.panel.open()          // open this plugin's panel.html
hcj.panel.close()
hcj.panel.send(msg)       // page → panel message
hcj.panel.onMessage(fn)   // panel → page messages

hcj.on('action', fn)      // fired when the user taps the plugin entry
                          // (plugins without panel.html)
hcj.emit(evt, data)       // fire a custom event into your own handlers
```

Inside `panel.html`, a mirrored `hcjPanel` object is available:
`hcjPanel.config.*`, `hcjPanel.send(msg)`, `hcjPanel.onMessage(fn)`,
`hcjPanel.close()`.

Settings UI is *yours*: build it in `panel.html` with ordinary HTML/CSS/JS
and persist via `hcjPanel.config`. There is no form DSL to learn.

## Versioning

Bump `version` in both `plugin.json` and `registry.json`. Installed clients
see **Update available** when the registry version is newer than the local
one.

## Local validation

```bash
python3 .github/scripts/ci/validate_modules.py
```

## Reviewer checklist

- `plugin.json` parses and `id`/`name`/`version` match `registry.json`.
- `main.js` has no top-level `return`, no obfuscation, no remote-code fetch+eval.
- `permissions` only lists what the code actually calls.
- Icons stay under 256 KB; `iconUrl` relative paths point at allowed filenames.

---

## 中文

这个目录**就是** WebToApp 插件市场。App 内市场展示的每个插件都直接从这个
文件夹通过 `raw.githubusercontent.com` 拉取，`cdn.jsdelivr.net/gh/` 做 CDN
兜底，没有别的后端。PR 合入 `main` 即发布。

### 目录结构

```
modules/
├── registry.json              ← 应用首先下载的索引
├── submissions.json           ← CI 生成的 PR/贡献者元数据
└── <plugin-path>/             ← 每个插件一个文件夹
    ├── plugin.json            ← 插件清单（必需）
    ├── main.js                ← 插件源码（必需）
    ├── style.css              ← 可选 CSS，document-start 自动注入
    ├── panel.html             ← 可选插件界面（抽屉/浮窗/全屏宿主）
    └── icon.png               ← 可选图标，≤256 KB（.svg/.webp/.jpg 亦可）
```

插件就是一个纯 **HTML + CSS + JS** 包——就是你熟悉的三件套，没有自定义
DSL。`main.js` 像油猴脚本一样注入页面，`style.css` 自动注入，`panel.html`
（如果存在）就是插件自己的界面，由用户选择的宿主形态承载。

### `plugin.json` 清单

字段：`id`（全局唯一，必填）、`name`（必填）、`version`、`description`、
`author`、`homepage`、`icon`（Material 图标名）、`matches` / `excludeMatches`
（Chrome 风格匹配规则，`/regex/` 表示正则）、`runAt`
（`document_start` / `document_end` / `document_idle`）、`permissions`
（`STORAGE`/`FETCH`/`NOTIFY`/`BADGE`/`CLIPBOARD`/`DOWNLOAD`）、`toolbar`。

`registry.json` 条目字段不变：`id`、`path`、`name`、`description`、`icon`、
`category`、`tags`、`version`、`minAppVersion`、`author`、`runAt`、
`permissions`、`urlMatches`、`hasCss`、可选 `iconUrl`、`sourceType`
（`CUSTOM` / `CHROME_EXTENSION` + `storeId`）。

### `main.js` 运行时合约

页面内注入一个作用域化的 `hcj` API 对象：

- `hcj.config.get/set/remove/all` — 持久化 KV（需 `STORAGE` 权限）
- `hcj.fetch(url, opts)` — 跨域请求（需 `FETCH`），返回 Promise
- `hcj.notify(title, body)` — 系统通知（需 `NOTIFY`）
- `hcj.badge(text, color)` — 工具栏角标（需 `BADGE`）
- `hcj.panel.open/close/send/onMessage` — 面板控制与页面↔面板消息
- `hcj.on('action', fn)` — 用户点击插件入口时触发（无 panel.html 的插件）
- `hcj.id` / `hcj.manifest` / `hcj.lang`

`panel.html` 内有对应的 `hcjPanel`：`config.*`、`send`、`onMessage`、`close`。

设置界面完全由你自己用 HTML/CSS/JS 写——没有需要学习的表单 DSL。

### 提交流程

1. Fork 仓库，在 `modules/` 下建 kebab-case 文件夹。
2. 至少提供 `plugin.json` 和 `main.js`。
3. 在 `registry.json` 加对应条目，保持 `id`/`name`/`version` 一致。
4. 提 PR；本地可先跑 `python3 .github/scripts/ci/validate_modules.py` 自检。
5. 合入即发布，客户端下次刷新即可看到。

### 兼容性说明

目录只接受 `plugin.json` 格式。已退役的自研 `module.json` 格式连同旧
投稿一起从市场移除——CI 会直接拒绝。

### 审核 Checklist

- `plugin.json` 可解析，`id`/`name`/`version` 与 `registry.json` 一致。
- `main.js` 无顶层 `return`、无混淆、无远程拉取代码 eval。
- `permissions` 只声明实际调用的能力。
- 图标 ≤256 KB；`iconUrl` 相对路径指向允许的文件名。
