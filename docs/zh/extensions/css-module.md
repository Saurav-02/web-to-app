# CSS 插件

CSS 插件是纯样式覆盖——给站点做主题、重排版或暗色模式。它和 [HCJ 插件](/zh/extensions/js-module) 用同一个 `plugin.json` 清单，只是主体是样式表。

## 文件结构

```
my-theme/
├── plugin.json    # 必需
├── main.js        # 必需 —— 可以是接近空的占位
├── style.css      # 实际样式
└── icon.png       # 可选
```

::: info `main.js` 仍然是必需的
即使是纯 CSS 插件也需要一个 `main.js`（最小占位即可）。把 `runAt` 设为 `document_start`，样式尽早生效，避免未样式化内容的闪烁。
:::

## `plugin.json`

```json
{
  "id": "dark-reader-lite",
  "name": "Dark Reader Lite",
  "description": "一个简单的暗色主题",
  "icon": "dark_mode",
  "runAt": "document_start",
  "matches": ["*://news.ycombinator.com/*"],
  "permissions": []
}
```

CSS 注入不需要权限——它就是包的一部分。

## CSS 如何注入

`style.css` 在 document-start 以 `<style id="hcj-css-<id>">` 元素注入，早于页面绘制和 `main.js` 运行。按插件、按文档幂等——反复导航不会重复叠加。

## `style.css` 示例

```css
:root {
  color-scheme: dark;
}
body {
  background: #111 !important;
  color: #ddd !important;
}
a {
  color: #60a5fa !important;
}
```

可用的 `document_start` 样式插件示例见 [`modules/`](https://github.com/shiaho777/web-to-app/tree/main/modules) 下的 `web-tint`。
