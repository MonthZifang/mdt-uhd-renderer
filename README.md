# MDT UHD Renderer

为其他 MDT 插件提供统一的 HUD/弹窗渲染能力。

## 支持参数

- `com id`
- `title`
- `content`
- `duration`
- `allowManualClose`
- `windowWidth`
- `windowHeight`
- `playerScreenX`
- `playerScreenY`
- `mapX`
- `mapY`

## 规则

- `com id = 0` 表示全体发送
- `duration = 0` 表示持续时间使用一个超长值模拟常驻
- 玩家窗口使用 `infoPopup` 风格
- 地图渲染使用世界标签
- 通过 `mdt-list-data-system` 的 `player_bind` 列表反查 `com id -> playerUuid`
- 默认仅允许插件内部反射调用，可在配置里开放 `uhd-render-json`
