# fnos-auth

飞牛 fnOS 相册的 **登录鉴权 + 图库数据** UTS 插件（Android / uni-app x）。

把参考实现 [SMTM-PH/NasPhotoTV](https://github.com/SMTM-PH/NasPhotoTV) 的登录链路
（访问码网关 → WS 取 RSA 公钥 → AES+RSA 加密信封 → user.login → 两步验证 → authx 签名请求）
封装成一组 Promise API，并补上电视端需要的图库时间轴、分页与缩略图下载。

## 平台

仅 `app-android`（UTS Kotlin 混编）。iOS / 鸿蒙 / web 未实现。

## 安装

把整个 `fnos-auth` 目录放到项目的 `uni_modules/` 下即可（本仓库已经放好）。
不需要额外 npm 依赖：加密走 Android 内置 JCE，WebSocket 与 HTTP 都是 JDK/Android 自带实现。

## 使用

```ts
import { login, timeline, page, thumb } from '@/uni_modules/fnos-auth'

const r = await login('192.168.31.216', 5666, false, '黄俊淞', 'password', '')
if (r.ok) {
  const tl = await timeline()          // { total, years:[{year,offset}] }
  const p = await page(0, 60)          // { items:[{id,dt,thumb,medium,original,name,...}] }
  const local = await thumb(p.items[0].thumb)      // 缩略图：本地文件路径，可直接给 <image>
  const big = await image(p.items[0].original)     // 查看器原图（大图）
}
```

## API

| 函数 | 返回 | 说明 |
| --- | --- | --- |
| `login(host, port, useHttps, username, password, accessCode)` | `Promise<FnOsLoginResult>` | `useHttps=false` 走 HTTP/WS（默认 5666），`true` 走 HTTPS/WSS（5667，自签证书 TOFU） |
| `verifyTwoFactor(code)` | `Promise<FnOsLoginResult>` | 仅在上一次 `need2fa=true` 后调用；必须同一会话，不能并发登录 |
| `stat()` | `Promise<FnOsStatResult>` | `/user_photo/stat`，token 失效自动重登一次 |
| `timeline()` | `Promise<FnOsTimeline>` | `/gallery/timeline` → 年份 + 起始 offset + 总数 |
| `page(offset, limit)` | `Promise<FnOsPhotoPage>` | `/gallery/getList`，时间倒序分页 |
| `photoDetail(id)` | `Promise<FnOsPhotoDetail>` | `getOne` + `album/photo/belong` + `ai-person/personInPhoto`：详情、大小、相机、曝光、路径、地址/坐标、相册、人物（列表接口没有这些） |
| `thumb(path)` | `Promise<string>` | 带 AccessToken 下载缩略图到缓存，返回本地路径 |
| `image(path)` | `Promise<string>` | 通用图片下载（查看器原图），与 `thumb` 共用线程池/缓存，缓存文件保留 URL 扩展名 |
| `getToken()` / `logout()` | | token 只存内存 |
| `saveConfig(...)` / `getSavedConfig()` | | 连接配置（含协议），用于冷启动自动重登 |
| `startTvKeyHook(cb)` / `stopTvKeyHook(id)` | | 遥控器按键上报（Android KeyEvent.keyCode）；`start` 返回订阅 id，`stop` 必须带上它 |

类型定义见 `utssdk/interface.uts`。

## 实测行为（真实 fnOS）

- 图片流 `/p/api/v1/stream/...` **只认 `AccessToken` 请求头**；把 token 放 query 会 401 —— 所以必须有 `thumb()` 这一步。
- `gallery/getList` 的 `count`/`hasNext` 恒为 null：翻页以「本页 < limit」为止。
- `gallery/timeline` 返回按天的 `itemCount`，累计值正好是 `getList` 的 offset。
- 缩略图：`xsUrl` 180×240（约 9KB）适合电视网格；`sUrl` 240×320；`mUrl` 1280；`xxsUrl` 仅 60×80。

## 行为约定

- 所有回调都在主线程；
- `login()` 会先保存配置再发请求，失败后地址与账号仍保留；
- 拿到 token 后立即关闭登录用的 WebSocket（业务走 HTTP）；
- token 不落盘，冷启动由业务请求触发自动重登；
- 缩略图下载用 4 线程池 + 磁盘缓存（`cacheDir/fnos_thumbs`），下载过程不持全局锁。

## 已知边界

- 业务错误码只处理 `401 / 5001 / 5000`；其它 code 原样包装成错误信息；
- 只支持验证器（TOTP）两步验证，邮箱验证码会返回可读错误；
- `SALT`/`SECRET` 硬编码（与参考实现一致），换服务端版本可能需要更新。
