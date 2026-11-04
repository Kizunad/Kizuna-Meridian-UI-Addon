# 功法配置背景资源

资源：`client/src/main/resources/assets/meridian_ui/textures/gui/combat_book.png`。
1536×1024 RGB PNG，随客户端正式 JAR 打包，可由资源包替换。

2026-11-04 通过 CPA Image Gen（cliproxy 图片接口）生成，模型 `gpt-image-2.5-flare`，
请求 `1536x1024`、`quality=high`、`background=opaque`。原稿保持尺寸，只以 RGB PNG 优化编码保存。
背景不包含文字、技能图标或交互控件；游戏按窗口尺寸绘制双页，所有内容由 XML 与 owo 组件覆盖。
墨色沿用 Inventory UI 的浅色主题令牌；底部槽栏继续使用工作台主题。

生成提示词：

```text
Use case: stylized-concept
Asset type: production background texture for a Minecraft martial arts skill configuration window.
Primary request: a blank open Chinese martial arts manual, seen exactly from above, two facing parchment pages with a narrow vertical binding seam in the exact center. The spread fills the entire rectangular canvas edge to edge.
Style: restrained Minecraft-compatible pixel art, crisp block-shaped pixels, limited muted warm cream parchment and dark brown cloth binding palette, subtle pixel wear only along the outside edges. No perspective, no isometric angle, no photographic realism.
Composition: symmetrical left and right pages of equal width. A very thin dark cloth cover peeks around the outermost 2 percent of the canvas. Central binding occupies at most 3 percent of the width. Each page must have a large flat, light, quiet blank area for live UI text and skill icons: the inner 90 percent of both pages is clean pale ivory with almost no texture or contrast. Thin worn paper edges and tiny corner creases give it the feel of a used book. The page areas are rectangular and extend nearly to the top and bottom edges.
Lighting: flat and even, only slight darker pixel shading along the center seam and outer edge.
Constraints: background only. Absolutely no text, calligraphy, glyphs, numbers, diagrams, symbols, characters, hands, skill icons, buttons, grids, decorations on the blank pages, watermark, logo, surrounding desk, or other objects. No large shadow. No ornamental frames in the page interiors. The game draws all interactive content itself.
```
