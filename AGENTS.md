# AGENTS.md — 交接给 AI 代码编辑器的工程说明书

> 给接手本项目的 AI（Cursor / Trae / Claude Code / Codex 等）：**先读完本文件再动手**。
> 这是一个已经跑通过登录与图库接口的 uni-app x 工程，不要重新猜协议。

## 0. 项目是什么

飞牛 fnOS 相册的 **Android TV 客户端**（uni-app x + UTS/Kotlin 插件）。
当前完成：连接登录（含两步验证）、**首页大屏（智能场景轮播，登录后的落地页）**、图库网格（按天分组）、
右侧年份时间轴、缩略图下载缓存、相册列表与内页、照片查看器、**共用导航栏组件**、
**设置页（登录状态 / 退出登录 / 视频清晰度五档，其余两项占位，见 5.35.2）**、**AI 搜索（结果 8 列网格 + 分批渲染，见 5.17）**、
**视频播放（本机回环代理 + 清晰度五档 + 竖屏旋转补偿 + 片尾收尾与 OK 重播，真机已验证，
见 5.18 / 5.19 / 5.35 / 5.38）**。
版本划分（2026-11-20 用户确定，明细见 §7）：**1.0** = 现已实现的功能 + T5 遥控器操作方式重构与测试
（**T5 已于 2026-11-25 完成并通过真机遥控器验收，1.0 的功能项全部收口**）；
**2.0** = T4.5 首页展示内容 / T4.7 主题设置 / T4.10 相册页顶部功能栏 / T4.11 首页托盘缩小 50% / T6 健壮性；
**3.0** = T7 AI 评价照片。

> ⚠️ 2026-11-15 之前所有功能**只在浏览器里验过**，第一次真机编译才发现 5 类历史编译错误 + 播放器 401。
> 以后每轮改动**至少跑一次「运行到 Android App 基座」**，浏览器绿了不等于能用。

**最近一轮（2026-11-24 ~ 11-25，全部真机验证）做了什么** —— 详见 `§5.34 ~ §5.38`：

1. **正式包视频全黑**（浏览器/基座都正常，只有本地打包出来的包黑屏）：离线打包工程的
   `app/build.gradle` 少了 `buildConfigField UTSEasyCom` → `resolveComponent("video")` 返回 null，
   `<video>` 退化成裸 `UniElementImpl`。补上即好（**5.34**）。
2. **清晰度从三档扩到五档**：原图 / NAS转码原画（源分辨率，码率封顶 20M）/ 1080P / 标清 / 低清；
   顺手修了 `parseInt("4k")==4` 导致 4K 档永远选不中的 bug（**5.35.2**）。
   原画卡顿的根因也测清了：源码率 61.7Mbps vs 实测链路 66Mbps（**5.35.1**）。
3. **竖屏转码躺倒**：NAS 转码流（TS）不带旋转信息，客户端按 `decode/info` 的
   `videoStreamInfo[0].rotation` 补 90°（**5.35.3**）；连带修了「内置居中播放按钮跟着转、三角朝下」
   （`:show-center-play-btn="false"`，**5.35.5**）。
4. **相册内页翻久了卡 / 光标消失 / 返回相册封面变空**：根因是「所有已加载照片都建原生视图」
   （实测翻 60 行 221MB）。分三步做：光标改浮动框 → 缩略图窗口化 → **虚拟列表**（只渲染约 108 张，
   上下 spacer 撑高度，坐标体系不变）→ 91MB、p50/p90 都优于图库页（**5.35.5 / 5.35.7 / 5.35.8**）。
5. **实况照片既没角标也不会播**：`isLive` 是**枚举码 0/2**，不是布尔；代码写的 `== 1` 永远假。
   抽出 `liveFlag()` 统一三条数据路径（**5.35.9**）。
6. 其它：底部「已经到底了」被裁一半（`TAIL_RPX`）、实况片段只回 `@timeupdate` 不回 `@play`
   导致视频一直透明（只剩声音）。
7. **排障方法沉淀**：把 `FnOsMediaProxy` 当「带 token 的 GET 客户端」用（`adb forward` + `/v/<b64>`，
   **5.35.4**）；用 `dumpsys gfxinfo` 量化掉帧，并**以图库页为基线**（p50 38ms）而不是绝对值（**5.35.8**）。
8. **视频停在片尾 + 「OK 重播」播不动**（2026-11-25 晚，release 包真机验证）：31s 的片子走到 `0:30`
   就静止、转圈一路跑到 125s。先用代理当 GET 客户端证明**文件与 Range 响应完全正常**
   （`206` + `Content-Range` + 字节数对得上），结论是 **ExoPlayer 对 moov 在尾部的渐进式 mp4 不发 `@ended`**；
   看门狗兜底「位置贴片尾 + 停 > 2.5s = 播完」→ 状态行变「已结束 · OK 重播」（**5.38**）。
   重播连过三关才成：下发同一 src 不重载、`ctx.play()` 立刻又结束、`seek(0)+play()` 完全无效
   （**5.38.1 / 5.38.2**），最终必须「卸载 → **换 `<video>` 的 id** → 重建 → 延后 play」——
   因为 uni 的原生播放器**按元素 id 复用 ExoPlayer 实例**（**5.38.3**，连续三次重播已验证）。
9. **T5 遥控器操作方式重构收口（2026-11-25，用户确认完成）**：分发层（`common/keys.uts` 层级栈 +
   统一按键常量 + `KeyDebounce` + `[keys]` 日志）、返回键逐级、导航栏纳入焦点链、
   真机「订阅 id 撞车」全部落地并在电视上验过。**1.0 的功能项至此全部完成**，明细见 §7 T5 / 5.22 / 5.29。

**上一轮（2026-11-15 ~ 11-19，真机为主）做了什么**：

1. 图库「跳转后上滑加载不出更早照片 / 高亮与内容不符 / 到顶出现空白 + 年月标题」专项：
   修掉了补页触发、失败误判、高亮取样、跳转落点（`scroll-into-view`）等问题，
   **但「补页后视口该停在哪」的观感仍未达用户预期，用户明确「先这样」** —— 全部结论见 **5.20**。
2. `FnOsTvKeys` 的内存泄漏（LeakCanary 实测）：静态强引用包装器把销毁的 Activity 钉住 → 改 `WeakReference`；
   期间踩到「UTS 插件不能实现带 default 方法的接口（`$-CC` 崩溃）」，见 5.2 / 5.12。
3. 新增 `[lib]` 诊断日志（`jumpTo` / `onScroll` / `active` / `loadPrev` / `atTop`），
   真机上排查滚动问题先看它；收尾时按 5.20.5 删除或收进开关。
4. **T4.8「HEVC 转 H.264」**：设置页新增开关（默认关闭、全局生效），原画 `@error` 时请 NAS 转 H.264 HLS
  并从当前进度续播，见 **5.21**；真机装过，但**测试机支持 HEVC 硬解 → 兜底链没触发过**（未验证），
  复验方法见 **5.21.1**。
5. **T5 遥控器分发层**（2026-11-21）：新增 `common/keys.uts`（按键码唯一定义 + `dispatchKey` 层级栈 +
  连按去抖 + `[keys]` 日志），9 个页面/组件全部改成「注册层」，页面里再没有 `code == 19` 这类字面量；
  收键通道未动。设计与各页层表见 **5.22**，**真机 5 项测试还没跑**。

## 1. 硬约束

- **必须用 HBuilderX 4.25+**：UTS 插件官方只支持 HBuilderX 编译，**不能**用 CLI/npm 构建。
- 工程是 **VDOM 模式**（`manifest.json` 里 `uni-app-x.vapor = false`），页面脚本用 `<script setup lang="uts">`。
- 只支持 `app-android`。iOS/鸿蒙/web 没有实现，不要假装兼容。
- **web 端只用于调 UI，不做协议实现**：`index.html`（web 入口）+ `utssdk/web/index.uts`（同源请求）
  + 根目录 `vite.config.js`（dev 代理，代持 `AccessToken` 并计算 `authx` 签名）。
  浏览器只请求同源 `/nas/*`，登录态由代理持有（登录页 POST `/nas-login`，见 5.1）。
  **但 web 端可以用物理键盘模拟遥控器按键**（`utssdk/web` 的 `startTvKeyHook` 接管 keydown，
  见 5.11.1）——这是电视连不上基座时的主力调试手段，不要删掉。
  登录（WS + RSA 信封）和缩略图本地缓存仍然只在 `app-android` 里；
  **不要**把登录加密逻辑搬进 `utssdk/web` 或浏览器，也不要让 SALT/SECRET 进前端产物。
- 插件 **零三方依赖**：加密用 Android JCE，HTTP 用 `HttpURLConnection`，WebSocket 是手写的最小 RFC6455。
  **不要**给插件加 okhttp/Okio/Coil 之类的依赖。
- **不要修改** `SALT` / `SECRET` / 加密信封 / 签名顺序 —— 这些是与真实 NAS 对齐的，改了就登录不上。
- **不要把 AccessToken 放到 URL**：实测 `?AccessToken=` 返回 401，只认请求头。
- **不要把账号密码写进任何提交文件**（包括 `AGENTS.md`、脚本、示例）。脚本一律读环境变量。
  唯一的例外是本机调试态的凭据留存：dev server 进程内存 + 浏览器 `localStorage`（键 `fnos.web.cfg`），
  两处都在本机、不落仓库、不进产物，见 5.6。

## 2. 测试环境

| 项 | 值 |
| --- | --- |
| NAS | `http://192.168.31.216:5666`（**HTTP**，不是 5667） |
| 账号 | 问项目所有者，不要落盘 |
| 协议自测 | `FNOS_USER=xxx FNOS_PASS=yyy node tools/fnos-probe.mjs`（Node 20+，无依赖） |
| 真机 | TV 盒子开 adb，`adb connect <ip>:5555`，HBuilderX 运行到 Android |
| 浏览器调试 | HBuilderX「运行到浏览器」；配置见 5.1，原理图：浏览器 → `/nas-login`/`/nas/*` → dev 代理 → NAS |

`tools/fnos-probe.mjs` 会跑：登录 → stat → timeline → getList → album/list → album/item →
album/photos → 缩略图鉴权，任何协议改动前后都应跑一次。
搜索（`POST magic-search/do`）不在 probe 里，单独用 `tools/fnos-search.mjs`（见 5.17.4）。
预期输出（可作为回归基线）：

```
[2/5] 登录成功，token 长度 44
[3/5] stat: code=0 photo=68464 video=3438
[4/5] timeline: 514 天, 合计 71902 条, 最近 2026-11-2
[5/5] getList: code=0 返回 3 条
[+] album/list: code=0 total=undefined 本页 5 条
      黄钦安 照片=412 视频=249 共享=1 封面=27425/05765fd5-...
[+] album/item: code=0 name=黄钦安 照片=412 视频=249
[+] album/photos: code=0 返回 2 条
      2026:09:11 14:42:31 photo isLive=1 缩略图 -> 200
      缩略图鉴权: 无头=401 头=200 query=401
```

`total=undefined` 不是 bug：`album/list` 本来就不返回 `total`（见 §3）。

## 3. 已实测的接口事实（不要重新猜）

| 事实 | 说明 |
| --- | --- |
| 登录 | `WS /websocket?type=main`：`util.crypto.getRSAPub` 取 `pub`/`si` → AES-256-CBC + RSA 信封发 `user.login` → 44 位 `token` |
| 两步验证 | `token` 为空且 `isBindTwofaSecret` 为真 → 保留**同一连接**发 `user.2fa.loginVerify` |
| 媒体鉴权 | `/p/api/v1/stream/...`（图片 / 原视频 / 转码流）**只认 `AccessToken` 请求头**：无头 401、`?AccessToken=` 401、cookie 401（2026-11-15 复测）。原视频支持 `Range` → **206 + Content-Range**（实测 134MB 的 mp4 分块可读），所以代理方案能拖进度 |
| `video/decode/*` | 清晰度三件套（2026-11-15 真机跑通）：`GET decode/info?id=` → `data.quality[]`（实测 25 档，`resolution` 为 `"1080"/"720"/"540"/"480"/"360"`，**原始画质那项 `bitrate=0`**）；`POST decode/play`（body `{id,bitrate,resolution,audioIndex:-2,audioEncoder:'aac',videoEncoder:'h264',channels:2}`，签名用**请求体原文**）→ `data.playLink` = **`/media/<32位hash>/preset.m3u8`（是路径，不是查询串！）**；播放地址 = `/p/api/v1/video/decode/stream` **直拼** playLink（不要加 `?`）→ 合法 m3u8（`00000.ts` 相对分片），分片同样只认 `AccessToken` 头。各档码率实测差 6 倍以上（720p@5M 首片 1.53MB/2s，360p@0.2M 首片 234KB/2s） |
| `gallery/getList` | 参数 `start_time`/`end_time`/`mode=index`/`limit`/`offset`；返回 `{list:[...]}`，**`count`/`hasNext` 恒为 null** |
| 分页结束 | 以「本页条数 < limit」判定，不要依赖 hasNext |
| `gallery/timeline` | 返回 `{list:[{year,month,day,itemCount}]}`（约 514 天，**倒序**），累计 itemCount 正好是 getList 的 offset |
| 时间轴聚合 | 插件把按天数据压成 `years[]`（年 + offset）和 `months[]`（年/月 + offset），**同一份累计逻辑**；月份只包含有照片的月 |
| `album/list` | 相册页的数据源：`?sort_by=album_name&sort_direction=desc&offset=0&limit=1000` → `{list:[{albumId,albumName,photoCount,videoCount,shared,posterUrl,startDateTime,endDateTime,category,birthday}]}`。**没有 `total`**，本机 54 个一次拉完即可；`posterUrl` 已是 `s` 尺寸封面流地址（`/p/api/v1/stream/p/t/{id}/s/{uuid}`），`shared=1` 表示已共享，`category=1` 只出现在宝宝相册（带 `birthday`） |
| `album/item` | `?album_id=` → 与 `album/list` 的一项同结构（相册名 / photoCount / videoCount / posterUrl / startDateTime / ownerName），相册内页头部用它 |
| `album/photos` | **相册内页该用它**（网页版相册内页就是它）：`?album_id=&sort_by=date_time&sort_direction=desc\|asc&offset=&limit=` → `{list:[...]}`，列表项**与 `gallery/getList` 完全同构**（含 `additional.thumbnail` 的 sUrl/mUrl/xsUrl/xxsUrl/`originalUrl`、`videoUrl`、`isLive`、`mediaDuration`、`photoUUID`），所以缩略图 / 实况角标 / 播放地址都能直接复用图库那套解析 |
| `album/normal/getList` | 另一套「按天视图」接口：`?albumId=&startTime=&endTime=&offset=&limit=&sortDirection=`（**驼峰参数**）→ 列表项**没有 `additional.thumbnail`、也没有 `isLive`**，只能拿 `id + photoUUID` 拼 `/p/api/v1/stream/p/t/{id}/{size}/{photoUUID}`（xxs/xs/s/m 可用，原图 `o`，`l` 是 404）。相册内页**不用**它（实况会丢） |
| `album/normal/timeline` | `?albumId=&sortBy=&sortDirection=` → `{list:[{year,month,day,itemCount}]}`，按天倒序；相册内页目前不用它（本地按 `dateTime` 分组即可） |
| `user/info` | `GET /p/api/v1/user/info` → `{id, name, photoCount:null, videoCount:null, isAdmin, nasUid}`。**设置页「当前状态」用它验证 token**（只看 `getToken()` 非空不可靠）；401 / 业务码非 0 即视为未登录，`name` 比本地保存的账号更权威 |
| `ai-smart-rec/categories` | 首页大屏的数据源（网页版 `/p/smart_category`，**自带网页入口，返回 HTML，不要请求它**）：`?limit=-1&mainCategory=scene` → `data:{list:[{name,itemCount,poster:{photoId,uuid}}]}`。实测 **54** 个场景，按 `itemCount` 倒序，**没有 `total`**。海报没有现成 URL，用 `poster` 拼 `/p/api/v1/stream/p/t/{photoId}/{size}/{uuid}`：`xxs/xs/s/m` 可用（m ≈1280 宽 147KB）、`l` 是 **404**、`o` 是原图（约 3MB，别用）。`mainCategory` 换成别的值可拿其它智能分类（人物/地点等），目前只用 scene |
| 缩略图尺寸 | `xxsUrl` 60×80(2KB)、`xsUrl` 180×240(9KB)、`sUrl` 240×320(15KB)、`mUrl` 1280(257KB)；网格现用 **sUrl** |
| offset 非线性 | 相邻 offset 可能跨很久（例如 2024 年 1 月只有几十张）；`offset-60` 不等于「上一个月」，只能当作「更晚的几十张」 |
| 数据规模 | 71,902 条；单日最多 16,274 条（`2026-11-02`），所以「按天分组 + 分页」必须保留 |
| 时间格式 | `2026:11:02 17:27:09`（冒号分隔，不是 ISO） |
| 视频时长 | `getList` 项的 `mediaDuration`，**单位秒**（照片为 0）；NAS `/photo` 前端用 `dayjs.duration(mediaDuration,'seconds')` 画在缩略图角标上。老固件可能叫 `duration`/`videoDuration`，插件两端都按序容错（>86400 视为毫秒） |
| 签名 | `md5(SALT_path_nonce_毫秒时间戳_md5(payload)_SECRET)`，payload 默认用 **RAW** 值；业务码 5000 时切 ENCODED 重试 |
| 登录设备展示（T4.9） | **2026-11-19 按 fnOS 网页端 bundle 实测**：`user.login` / `user.2fa.loginVerify` 的展示字段只有三个 —— `deviceName`、`deviceType`、`did`；**没有** `osName` / `osVersion` / `deviceModel` / `clientName`。`deviceName` 必须是 **`<类型>-<客户端>`**：设备管理页按第一个 `-` 拆两段，标题显示第二段（客户端名，如 `NAS Photo TV`），副行显示 `第一段 登录于 …`（本端固定写 `TV`），拆不出第二段就整条显示「未知设备」。`deviceType` 前端只认 `Browser` / `PC` 两张专属图标，其余值（含 `Mobile` / `AndroidTV` / `TV`）一律落「手机」那个通用图标 —— **NAS 前端根本没有电视图标**，TV 只能靠 `deviceName` 第一段的 `TV` 表达身份；「授权设备」列表的取值枚举是 `Agent` / `Browser` / `PC` / `Mobile`。本端固定发 `deviceName = "TV-NAS Photo TV"`（真机与 web dev server 完全同名）。`did` 网页端是 `<分钟时间戳36进制>-<random13>-<random13>`，本端仍用 24 位 hex：能用且会被复用，**不要**为了格式而换（换了要重新输动态码信任设备）；同一 `did` 的旧记录也**不会**刷新 `deviceName`，改名后必须删旧记录重登 |
| `magic-search/do` | **AI 搜索**（T4.4）：`POST /p/api/v1/magic-search/do`，请求体 `{"keyword":"海边","antiFilters":[]}`，鉴权同上（`AccessToken` + `authx`，**签名 payload 是请求体原文**，不是 query）。⚠️ 返回结构**尚未在真机确认**（没拿到 token 时只验到 401），插件两端按「先找照片数组、再按图库字段容错、缺流地址用 `id + photoUUID` 拼 `/p/api/v1/stream/p/t/{id}/s/{uuid}`」解析（`FnOsLogin.searchJsonFrom` / web 的 `collectSearchItemsWeb`）。确认结构后跑一次 `FNOS_USER=xxx FNOS_PASS=yyy node tools/fnos-search.mjs 海边`，把脚本打印的键结构补进本行。**已接入**：`FnOsLogin.search` / `search()`（两端）+ `pages/search/search.uvue`，细节与踩坑见 **5.17** |

## 4. 代码地图

```
pages/index/index.uvue        连接页（设置页）：协议/地址/端口/账号/密码/访问码、2FA、自动登录；登录成功后 reLaunch 首页
pages/home/home.uvue          首页大屏：智能场景全屏海报 + 右上实时时钟 + 底部横向卡片托盘（10 秒自动轮播）+ 图库/相册入口（见 5.14）
pages/settings/settings.uvue  设置页：左右 3:7 布局（左选项 / 右内容），登录状态（IP/端口/账号/密码脱敏/HTTPS/退出登录）+ 视频清晰度五档（见 5.15 / 5.18 / 5.35.2），首页展示设置与主题设置占位
pages/library/library.uvue    图库：日期分组网格（8/6/4 列可切）+ 类型角标 + 年月时间轴 + 遥控器导航；
                             导航栏用共用 NavBar；查看器已改为 PhotoViewer 组件（见 5.13）。
                             **向上补页 / 跳转定位 / 高亮取样的全部约定与被否方案见 5.20，改滚动先读它**
pages/library/ThumbImage.uvue 缩略图组件：调 thumb(path)，失败补 file:// 重试
pages/album/album.uvue       相册列表：`album/list` 一次拉全量，6 列（窄屏递减）封面卡片 + 数量/共享标记 + 宝宝相册角标 + 遥控器焦点；入口是共用 NavBar 的「相册」标签（与图库互切用 redirectTo，「首页」用 reLaunch）
pages/album/detail.uvue      相册内页：`album/item` 头部信息 + `album/photos` 分页网格（按年/月 + 日期分组，点「拍摄时间」切升降序）+ 复用 PhotoViewer；入口是点击相册卡片（navigateTo）
pages/search/search.uvue     搜索页（T4.4）：**导航栏照常显示且「搜索」按钮选中**，下方一条深色搜索框（放大镜 + 输入 + ×，无 AI 图标/筛选）+ 一排 8 个正方形缩略图（**分批渲染**）+ 类型角标（视频带时长 / 实况）+ 复用 PhotoViewer；入口是各页导航栏的「搜索」（navigateTo，见 5.17）
components/PhotoViewer.uvue  照片查看器（**图库 / 相册内页 / 搜索共用**）：图片 / 视频（OK 播放、进度、±10s、倍速、清晰度五档取流 + 竖屏旋转补偿）/
                             实况片段 / 详情面板 + 遥控器按键；播放地址一律走 `mediaUrl()` 本机代理（见 5.19），状态行显示实际档位+码率
components/NavBar.uvue       全局导航栏（**首页 / 图库 / 相册 / 设置 / 搜索共用**）：悬浮顶部、品牌 + 分区标签 + 搜索/设置 + 实时时间；`settingsActive` / `searchActive` 两个高亮开关，props/emits 见 5.4
common/photo.uts             PhotoItem 类型 + durationClock / videoTagText / showLiveTag，两个页面与查看器共用同一份
common/scene.uts             首页大屏的 SceneItem 类型（name / count / cover / thumb），页面不直接引用 uni_modules 内部类型
common/video.uts             视频清晰度档位（T4.6）：存储键 + original/native/1080/sd/low 常量 + 五档选项 / 规范化 / 目标高度 / 码率上限 / 分辨率折算（`resolutionHeight`，别再 parseInt）；
                             另含 HEVC 转 H.264 开关（T4.8）的 `fnos.video.hevc` 键与 on/off 规范化，纯函数、不含 storage 读写
common/keys.uts              遥控器按键分发层（T5）：**全工程唯一的按键码定义处** + `dispatchKey`（层级栈，顶层优先后
                             逐层下传）+ `KeyDebounce`（连按去抖）+ `[keys]` 日志；用法与各页层表见 5.22
uni_modules/fnos-auth/
  utssdk/interface.uts        对外类型（改 API 必须同步这里）
  utssdk/app-android/index.uts  Promise 封装（JSON ↔ 类型对象）
  utssdk/web/index.uts      Web 实现（走 /nas 代理拿真数据，不实现登录协议）
  utssdk/app-android/FnOsLogin.kt   登录/2FA/签名/图库/缩略图/原图/视频片段下载（核心，900+ 行）
  utssdk/app-android/FnOsCrypto.kt  MD5 / AES / RSA
  utssdk/app-android/FnOsTls.kt     自签证书 TOFU
  utssdk/app-android/FnOsWebSocket.kt  ws/wss 最小客户端
  utssdk/app-android/FnOsHttp.kt     HTTP/HTTPS + cookie
  utssdk/app-android/FnOsTvKeys.kt   遥控器按键 Hook（Window.Callback 委托；订阅 id / 常驻包装器见 5.12；
                                     静态字段必须 WeakReference，否则 Activity 泄漏，见 5.2）
  utssdk/app-android/FnOsMediaProxy.kt 本机回环媒体代理（127.0.0.1，补 AccessToken + 透传 Range + 重写 m3u8，见 5.19）
static/                       图片资源：`logo.png`（官方 logo 母版 750×737）、`logo-512/256/128.png`
                              （同图缩放版）。导航栏用 128、登录页用 256；改 logo 只换这几个文件，
                              不要在页面里写死别的路径（web 端 `/static/*` 同源直出，App 端打进包）
index.html                     uni-app x 的 web 入口（含 rem 根字号基准，见 5.1）
vite.config.js                浏览器调试用 dev 代理：注入 AccessToken/authx + /nas-login + 401 静默续期 + uni 插件
.env.local                    本地调试凭据（FNOS_TOKEN/FNOS_BASE），已在 .gitignore
tools/fnos-login.mjs          Node 侧 WS 登录（probe 与 dev 代理共用）
tools/fnos-probe.mjs          协议回归脚本（`--token` 可打印 AccessToken）
tools/get-token.mjs           交互式取 token 并写入 .env.local
tools/fnos-search.mjs         AI 搜索接口自测（登录 → POST magic-search/do → 打印返回的键结构）
tools/fnos-video.mjs          视频清晰度自测（T4.6）：decode/info 各档 → 逐档 decode/play → 转码流的三种鉴权方式各试一次
```

## 5. 架构约定（改代码前必须知道）

1. **Kotlin ↔ UTS 只传 JSON 字符串**：Kotlin 侧统一 `post(callback, json)` 回主线程，UTS 侧 `JSON.parseObject` 解析。
   新增接口按这个模式走，不要试图直接跨语言传 Kotlin 对象。
2. **新增 API 的标准步骤**：
   `FnOsLogin.kt` 加方法 → `interface.uts` 加类型 → `app-android/index.uts` 加 Promise 封装 → 页面调用。
3. **uvue 限制**：文字必须在 `<text>` 内；没有原生焦点树（按键全靠 `FnOsTvKeys`）；样式是 CSS 子集，
   优先 flex + rpx，避免 `gap`、伪类、后代选择器。
4. **尺寸**：全工程按 `750rpx = 屏宽` 设计。图库网格支持 **8 / 6 / 4 列**切换（右上角控件 + 遥控器进入），
   格子是**正方形**、`aspectFill` 居中裁切；宽高由 `cellStyle`（内联 rpx）动态给出，
   可用宽度常量 `GRID_WIDTH_RPX = 668`（750 − 页面边距 12 − 时间轴 70）。
   `cellPx`（行高基准，含外边距）必须与格子尺寸同步，否则滚动映射和年份跳转会错位。
5. **缩略图**：app-android 必须走 `thumb(path)` 下载到本地缓存（`<image>` 加不了请求头）；
   web 端 `thumb()` 直接返回 `/nas` + path，同源加载、不落盘。
   两端都不要在页面里直接拼 NAS 绝对地址。
6. **视频播放地址**：app-android 一律用 `mediaUrl(path)`（→ `http://127.0.0.1:<port>/v/<base64>`，
   由插件内 `FnOsMediaProxy` 补 `AccessToken`、透传 `Range`、重写 m3u8 分片行）；
   **不要**再用 `streamUrl()` + `<video :header>` —— 真机实测该 header 进不到 ExoPlayer，必然 401（见 5.18.4 / 5.19）。
   web 端 `mediaUrl()` 就是 `/nas` + path。
7. **按键码**：19 上 / 20 下 / 21 左 / 22 右 / 23 OK / 66 回车 / 4 返回。
   **页面里不许再写这些数字**：一律 `import { KEY_UP, ... } from '@/common/keys.uts'`，
   并按分发层注册「层」（见 **5.22**）。
   唯一的按键通道是 `FnOsTvKeys` 包在 `window.callback` 上的那一层，
   **包装器常驻、退订必须带 id**，机制与踩坑见 **5.12**。
8. **HTTP 明文**：插件 `AndroidManifest.xml` 里已有 `android:usesCleartextTraffic="true"`，不要删。
9. **时间轴**：`railNodes` 把年/月全部平铺（年一行 + 该年每个有照片的月一行），
   容器是 `scroll-view`；`syncRailScroll()` 让高亮项保持可见（目标行上方留约 10 个标准行），
   `railRowPx()` 按年份行 / 月份行分别累加真实高度 —— `railItemPx`、`RAIL_MONTH_RPX`、
   `RAIL_YEAR_RPX` 必须与 `.rail-item` / `.rail-item-year` 的 height 一一对应，改样式必改常量。
   样式细节、尺寸联动和末月跳转落位见 **5.3**。

### 5.1 Web 调试环境（2026-11 已踩通，照抄即可）

缺一样就会报「Failed to load url」「MIME 类型不允许」「invalid JS syntax」之类：

1. **`node_modules` 必须是目录联接**，指向 HBuilderX 内置依赖，否则 `vue`/裸模块全部解析不到：
   ```
   mklink /J <项目>\node_modules E:\HBuilderX\plugins\uniapp-cli-vite\node_modules
   ```
   （网络盘/mapped drive 不支持 junction，工程必须放在本地 NTFS 盘。）
2. **`vite.config.js` 里必须显式 `plugins: [uni()]`**：项目根一旦存在这个文件，
   HBuilderX 就不再注入 uni 插件链，`.uvue` / `pages.json` 全部不会被编译。
3. **`resolve.extensions` 必须整体覆盖并含 `.uvue` / `.uvx` / `.uts`**（vite 默认没有这些）。
4. **入口**：`index.html` 用 `<script type="module" src="/main.ts">`，并新建 `main.ts`
   （内容与 `main.uts` 相同；`main.uts` 是 App 入口，web 端用 `.ts` 更稳）。
5. **rpx 在 web 端被编译成 rem**（`1rpx = 1/32rem`），必须给根元素定字号，否则整页缩成一小块：
   ```html
   <style>html { font-size: calc(100vw / 23.4375) !important; }</style>
   ```
6. **鉴权全部在 Node 侧**：`/nas-login`（登录页 POST 账号密码 → `tools/fnos-login.mjs` 走 WS 登录）
   + `/nas/*` 代理转发时补 `AccessToken` 和 `authx` 签名。token 只在进程内存或 `.env.local`。
   **凭据也在 dev server 进程内存留一份**，代理收到 401 时静默续期；浏览器侧再存一份到
   `localStorage`（键 `fnos.web.cfg`），刷新 / 隔夜 / dev server 重启都能自动登录。
   细节、根因和坑见 **5.6**。
7. 改 `vite.config.js` / `.env.local` **必须重启** dev server（HMR 不重新加载）。
8. 改 `FnOsLogin.kt` 等 **Kotlin 代码后必须重新运行/安装基座**；热刷新只覆盖 `.uvue` / `.uts`。
   改 `AndroidManifest.xml` 或加原生依赖还要**重做自定义调试基座**。

### 5.2 踩坑记录（都是真机/web 实测踩过的）

| 坑 | 现象 | 正确做法 |
| --- | --- | --- |
| UTS 局部函数顺序 | 编译报「找不到名称 xxx」 | 编译成 Kotlin 局部函数，**必须先定义后调用**，不能依赖提升。2026-11-15 首次安卓编译一次性暴露 3 处历史遗留：`album.uvue` 的 `onCardClick`、`album/detail.uvue` 的 `armKeyHook`、`library.uvue` / `search.uvue` 的 `armKeyHook`/`handleKey`/`closeSearch`/`onViewerClose` 互调 —— 已按「被调用者在前」重排。**改这块后跑一次顺序扫描**（脚本思路：抓 `function name(` 的行号，再看 name 在前面的行有没有被调用） |
| UTS 的 `watch` 必须显式类型 | 编译报 `Return type mismatch: expected 'Function', actual 'Number'`，指向 `watch(() => props.index, ...)` 的箭头 | getter 必须写返回类型、回调参数也要标类型：`watch((): number => props.index, (v: number, o: number) => {})`（官方示例同写法）。**所有 `watch` 都要这么写**，别用裸 `() => x` |
| 模板里调用 `common/*.uts` 的导入函数 | 三个页面全中：`{{ videoTagText(c.photo) }}` / `v-if="showLiveTag(c.photo)"` 编译报 `找不到名称 invoke` + `Function invocation 'xxx(...)' expected`（模板被编译成 `unref(fn)(arg)`）。**本文件的函数（如 `groupId(...)`）在模板里调用没问题，只有跨模块 import 的函数不行** | 把值**预先算进数据项**，模板只读字段：`library.uvue` 的 `CellItem` / `album/detail.uvue` 的 `Cell` 加 `tag: string` + `live: boolean`，`search.uvue` 的 `visible` 改成 `Array<CellView>`（`photo` + `tag` + `live`）。文案实现仍只有 `common/photo.uts` 一份 |
| `<image>` 默认尺寸 | 320×240，图片溢出格子/偏移 | 显式 `width/height: 100%`，外层容器加 `overflow: hidden` |
| `scroll-into-view` | web 端完全不生效（停在列表开头） | 不要用于跳转定位；分组 id 里也别带 `:`（选择器特殊字符） |
| `scroll-top` 时机 | 内容未渲染完就被旧高度截断；`scroll-view` 重建时初始值不生效 | 跳转**不要**靠「先加载再滚」；让列表直接从目标 offset 开始、`scrollTop = 0` |
| 跳转预载上一页 | 先闪上个月再跳回；末尾月份定位失效（atEnd 导致补页被跳过） | 不预载。想看更早照片用时间轴或遥控器上键（`loadPrev`） |
| 向上翻页无响应 | 已在顶部，连 scroll 事件都不产生 | 列表顶部留 `prev-gap`（24rpx）提供可滚余量，跳转后把 `scrollTop` 设到留白之后（到达全库最前时这段留白要收起，见下一条） |
| 向上翻页跳变 / 跳转落点偏移（2026-11-15 真机） | 补页或跳转时「先跳到上一个时间的第一页，再跳回来」：真机上内容变化与 `scroll-top` 生效不在同一帧，只在 microtask 里设一次会被丢弃或按旧内容高度钳制；而且值相同时 uvue 不会重新下发该属性 | 统一走 `applyScrollTop(target)`（`library.uvue`）：先同步设一次，再由 `resendScrollTop` 在 30/80/130/200/280ms 重申，每轮先设 `target+1` 再设 `target` 靠 1px 抖动强制触发；指令带 `scrollCmd` 序号，新的定位 / 用户主动滚动（`scrollBy`）都会作废旧重申；补偿基准 `currentScroll` 也由 `scrollBy` 自己维护。**补偿「停在哪」的口径见 5.20** |
| `loadPrev` 请求失败 | 偶发「按上键没反应」，再按一次才行：`!p.ok` 和「返回 0 条」被同一个分支处理，一次超时就把 `atNewest` 置真、`startOffset` 归零，之后永不重试 | `!p.ok` 只打日志 `return`，**只有 `items.length == 0` 才判定到顶** |
| 补页瞬间闪出「年月」标题（2026-11-18 真机日志确认） | 往上滑补页时先闪一下新内容的月份标题，再跳回原来的照片：插入头部后内容先长高、`scrollTop` 补偿晚一帧，那两帧视口正好落在**新内容头部**（真机：`top=25` 插入 60 张，补偿到 1553.8） | 曾试过「新插入分组 `opacity: 0` 占位」——**反而更糟，用户滑到顶看到整片空白**，已删除。最终做法见 **5.20**：距顶一屏就预加载 + 到顶时让新内容直接填满视口 |
| 高亮提前跳到下个月 | 视口顶部只露出上一行照片的最后二三十像素（补页补偿的余数），按「顶部那 1px」取样就把高亮判给了那张照片的月份（真机：`top=1553.8` 判定 2026-01-01，屏幕主体还是 2025-12-31） | `updateActive` 的取样点用 `top + cellPx * 0.7`（`probe`），只露出边角的照片不算「视口顶部那张」；`openViewer` 仍用 `currentScroll`，保持一致 |
| 连续往上翻时反复闪出年月 | 补页后继续按上键，补偿被 `scrollBy` 的 `scrollCmd++` 作废，而 `onScroll` 回传的仍是**补偿前的旧位置**，却无条件覆盖了模型 `currentScroll`（真机：补偿到 1528，回调报 `top=0` → 模型变 0 → 视口停在新内容顶部，全是年月标题） | 曾试过「补偿有效期 + `pushCurrent()` 回推视图（`compensateUntil` / `compensatePushes`）」——**真机上表现为慢慢往上滑会突然推不动**，已删除。现在 `onScroll` 一律以实际位置为准，不再回推；补页策略见 **5.20** |
| LeakCanary 报 Activity 泄漏 | `FnOsTvKeys.wrapper`（Kotlin `object` 静态字段）→ 包装器持有 `window.callback` → `AppCompatWindowCallback.mWrapped` → 已销毁的 `settings` / `library` Activity 整页泄漏 | 静态引用一律用 `WeakReference`（包装器 / Activity），装 hook 前用 `Activity.isDestroyed` 收掉上一任（`dropDeadActivity`）。`pages/index/index`（`io.dcloud.px.f` 静态）和 `pages/home/home`（`UniNativeDocumentImpl.calculatedCallback`）两条链在 uni-app x 框架内部，改不到，先记录 |
| UTS 插件里实现带 default 方法的接口（2026-11-15 真机崩） | 想用 `Application.ActivityLifecycleCallbacks` 清泄漏，真机一按就 `NoClassDefFoundError: android.app.Application$ActivityLifecycleCallbacks$-CC`，崩点是我们匿名实现里的 `onActivityPrePaused`：该接口在 Android 12+ 有 default 方法，编译器生成了调 `$-CC` 的桥接，而**插件的 dex 不做 Java 8 interface default 的 desugar** | UTS 插件（`utssdk/app-android/*.kt`）里**不要实现**任何带 default 方法的接口（ActivityLifecycleCallbacks、TextWatcher 之外的新 API 接口都先查一眼）。只能用弱引用 / 显式 `isDestroyed` 这类不依赖 default 方法的手段；`Window.Callback` 的全抽象接口委托是安全的 |
| 内容不足一屏 | 跳到只剩几张照片的末尾月份（如全库最早的月份），向上滚毫无反应：`scroll-view` 没有可滚范围，连 scroll 事件都不产生，`loadPrev` 永远触发不了 | `jumpTo` 之后调用 `fillViewport()` 主动往前补页（期间 `suppressCompensate` 关掉滚动补偿），把内容撑过一屏后再定位到 `prevGapPx + 补页高度` |
| 末月高亮被抢 | 跳到全库最早的月份后，时间轴高亮变成上一批照片的年份/月份：目标月份下方不足一屏，落点被浏览器钳制到最大滚动值，而定位产生的 scroll 回调又把高亮算到了补进来的照片上 | `jumpTo` 先按 `maxTop = prevGapPx + 内容高 − 视口高` 预先钳制落点（同时纠正 `currentScroll`），再用 `pinnedTop / pinGuard` 锁住选中月份的高亮（位置差 ≤48px 或 400ms 内忽略 scroll 回调），用户真正滚动后再恢复「高亮跟随视口」 |
| 跳转后立即上滑不补页（2026-11-15 真机） | 图库跳到指定时间后直接按上键无反应，得先往下翻一次再往上翻才加载：落点紧贴顶部留白（`top ≈ gapPx()`），上滑位移不到半格，永远出不了 `pinnedTop` 的 48px 锁定区，而 `loadPrev` 判定写在锁定 `return` 之后，被一并吞掉；`prevGuard`（600ms）又拦一道 | 补页判定抽成 `maybeLoadPrev(top, goingUp)`，**锁定分支里也调用**（只跳过高亮更新）；`prevGuard` 收到 250ms；遥控器上键改用「按一下就到顶」（`currentScroll − 两行 ≤ gapPx()`）而不是 `currentScroll <= 0` 触发，避免真机上程序化 `scrollTop` 后 scroll 事件不回传导致 `currentScroll` 停在几十像素 |
| 滚到全库最前留白滚不动 | 列表第一条就是最新照片时，顶部 `prev-gap` 变成一条怎么滚都不动的空白带，看着像卡住 / 没加载完 | `atNewest`（`startOffset == 0`，含跳转到 offset 0）为真时 `v-if` 不渲染 `prev-gap`；所有像素映射改用 `gapPx()`（最前时返回 0）而不是常量 `prevGapPx`；`loadPrev` 补到最前时把 `scrollTop` 减去收起的留白高度，避免内容整体上移造成跳动 |
| 高亮跟随 | 按像素区间归属分组，交界处差 1px 就判给上一组 | 按「视口顶部第一张照片的日期」判断（`indexAtScroll` → `photos[idx].dt`） |
| storage 布尔 | `getStorageSync` 读回字符串 `"true"`，`as boolean` 抛 ClassCastException | 只存字符串，读端按 `'true'` 比较 |
| CSS `baseline` | `align-items: baseline` 不支持 | 用 `flex-end` 代替 |
| CSS `text-shadow` | 想给压在照片上的白字加阴影，uni-app x 不支持（`@dcloudio` 里搜不到该属性） | 用半透明胶囊底衬，见 5.14 |
| 横向 `scroll-view` 的 `scroll-left` | 自动切换了但卡片条不动、选中项滚出屏幕 | 内容容器（flex row）会被拉成 100% 宽，必须显式给 `width`，见 5.14 |
| 悬浮导航栏点不动 | 进图库 / 相册后导航栏所有按钮无反应 | 被后方的 `.nav-spacer` 盖住，给悬浮层加 `z-index`，见 5.4 / 5.16 |
| `align-self: center` | 设置页按钮居中不生效 | 外层用 `flex-direction: row` + `justify-content: center`，见 5.15 |
| 遥控器进图库后全失效 | 登录页遥控器正常，`reLaunch` 进图库后方向键 / OK / 返回全部没反应；桩点是旧页面 `onUnload` 里的 `stopTvKeyHook()` 把 `window.callback` 还原了，而 `reLaunch` 时新旧页面的 `onLoad` / `onUnload` 顺序不保证，于是新页面刚装好的 Hook 被摘掉 | `FnOsTvKeys` 的包装器**装上去不再摘**，`stop()` 只清订阅回调；退订改成带 `id`（`startTvKeyHook` 返回 `Date.now()`），`stopTvKeyHook(id)` 里 `id` 不匹配就忽略（过期页面 / 重复 stop）；图库页在 `onLoad` 和 `onShow` 各订阅一次，`start` 内检测 `window.callback !== 自己的包装器` 就重新包装（兼容 Activity 重建 / 运行时替换） |

### 5.3 右侧时间轴（rail）：尺寸整修与跳转落位（2026-11）

这一轮把时间轴从「右侧一大条蓝色胶囊」改成「贴边细刻度线」，并修掉了末月跳转的两个定位 bug。
改这块前先把下面 6 条看完，全是实际发生过的。

| 遇到的问题 | 解决办法 / 现状 |
| --- | --- |
| 时间轴占屏过大、元素太粗（像串珠） | 逐档缩小：`rail` 宽度 120→**70rpx**，月份行 24→**15rpx**、年份行 30→**18rpx**，年份字 9rpx/加粗、月份字 8rpx，圆点 7→**6rpx**，连接线 **1rpx**；页面 `padding` 12→**6rpx**，`GRID_WIDTH_RPX` 598→**668**（750 − 12 − 70） |
| 圆点在最左、标签被挤在右边 | 改成**标签在左（`width: 50rpx` + `text-align: right`，紧贴连线）、节点列在右（`14rpx`，`margin-left: 2rpx`）**；连线距屏幕右边缘约 17rpx |
| 年份行和月份行一样高，没有分组感 | 年份行 18rpx、月份行 15rpx；年份行的节点位**画一颗星 `★`（`.rail-star`，8rpx，高亮时随文字变蓝）**而不是圆点，不用改行高 |
| 蓝色胶囊高亮太抢眼 | **去掉底色**，选中只把文字变蓝 `#4C97FF`；`.rail-halo` / `.rail-dot-on` / `.rail-line-on` 全部删除，未选中的点一律灰点。仅保留遥控器焦点描边 `.rail-item-selected` |
| 跳到末尾月份后向上滚毫无反应 | `jumpTo` 末尾调用 `fillViewport()`：内容比视口矮就循环 `loadPrev()` 补页（≤10 页，`suppressCompensate` 期间不做滚动补偿），补过一屏后再定位 |
| 跳到末尾月份后高亮变成上一批照片的年份 | 目标月份下方不足一屏，落点必然被浏览器钳制到 `maxTop`；`jumpTo` 先按 `maxTop = gapPx() + 内容高 − 视口高` 钳制并同步 `currentScroll`，再用 `pinnedTop / pinGuard` 忽略定位产生的 scroll 回调，**高亮保持在被点击的月份**，用户真正滚动后才恢复跟随 |
| 常量与 CSS 不同步导致高亮滚动定位漂移 | 四处必须同时改：`GRID_WIDTH_RPX` ↔ `.rail` 宽度；`RAIL_MONTH_RPX` ↔ `.rail-item` 高度；`RAIL_YEAR_RPX` ↔ `.rail-item-year` 高度；`railItemPx`（初值 + `onLoad` 两处）↔ 月份行高。`scrollRailTo` 按 `railRowPx(i)` 累加真实行高，不再乘平均行高 |

不改的东西：`railNodes` 数据结构、点击/遥控器按键流程、`activeIndex` 的取值规则。

### 5.4 顶栏（NavBar 组件 + topbar）（2026-11）

导航栏已经**抽成 `components/NavBar.uvue`**，首页 / 图库 / 相册共用同一份（不许再各页重写），
视觉以首页大屏为准，且**悬浮在页面顶部、不占布局高度**（`position: absolute`，页面 `.page` 必须是 `position: relative`）：
左侧「品牌图标 + NAS 相册 + 分区标签（首页 / 图库 / 相册）」是一个半透明胶囊，
右侧「搜索 / 设置 / 实时时间」是小胶囊，由 props `showSearch` / `showSettings` / `showClock` 控制；
`settingsActive`（设置页传 `true`）让「设置」按钮变成蓝底白字，表示当前就在设置页；
`searchActive`（搜索页传 `true`）同理让「搜索」按钮蓝底白字 + 图标变白，表示当前就在搜索。
两个都是**必填** prop：新增页面照抄现有五个页面（首页 / 图库 / 相册 / 设置 / 搜索）的 7 个 props 一起传，漏传会编译报警。

组件只管渲染与点击：分区 emit `nav(index)`、搜索 / 设置 emit `search` / `settings`，
跳转一律由页面处理；遥控器焦点通过 prop `focusIndex` 传入（-1 = 无焦点，只画蓝描边），
**组件不订阅按键**（按键通道仍归各页 `startTvKeyHook`，见 5.12）。
页面侧必须紧跟一个 `.nav-spacer`（高 60rpx + 8rpx 间距）补回顶部占位，
这样各页 `viewportPx = windowHeight − 118rpx` 的估算不用动。

各页 `active`：首页 0、图库 1、相册 2；**设置页与搜索页传 -1**（不属于任何分区，不高亮标签）。
点击「首页」`reLaunch` 回 `pages/home/home`，图库 ↔ 相册仍用 `redirectTo`。
「搜索」按钮**五页都已接真实跳转**：`navigateTo('/pages/search/search')`，搜索页本身传 `searchActive=true` 高亮自己；
「设置」按钮五页统一 `navigateTo('/pages/settings/settings')` 进设置页（返回键回原页，退出登录在设置页里，见 5.15 / 5.17）。
右侧开关：各页统一 = **搜索 + 设置 + 时钟**（`showSearch` / `showSettings` / `showClock` 全传 `true`），
新增页面也照此传，不要只开一部分（用户要求导航栏全局一致）。
设计稿里的「最近添加 / 收藏 · 人物 · 地点 / 屏保」已按要求删除，不要再加回来。

**`.navbar` 必须带 `z-index: 10`**：页面里紧跟的 `.nav-spacer` 在文档流中位于组件之后，
不加层级会把导航栏整条盖住、吃掉所有点击（web 实测 `elementFromPoint` 命中的是 spacer，
表现为「进图库 / 相册后点导航栏毫无反应」）。同理，任何后续加到页面顶部的元素都要注意层级。

| 元素 | 尺寸 |
| --- | --- |
| `.navbar` 定位 | `position: absolute`（left/right 16rpx、top 12rpx）+ **`z-index: 10`**，不占布局高度 |
| `.nav-spacer` 高 | 60rpx + 8rpx 间距（= 原 navbar 占位；改它必须同步 `onLoad` 里 `viewportPx` 估算） |
| `.brand` 胶囊 | 高 30rpx / 圆角 15rpx / `rgba(0,0,0,.42)`（背景照片不压暗，靠它保证白字可读） |
| `.nav-logo` 品牌图标 | **官方 logo 图片** 24×24rpx（`/static/logo-128.png`，`mode="aspectFit"`）。原来是纯 CSS 画的显示器，已弃用；换图只改 `static/` 里的文件，不要改样式尺寸（会撑破 30rpx 的胶囊） |
| `.brand-title`「NAS 相册」 | 17rpx / 加粗 / `#FFFFFF` |
| `.entry` 分区标签 | 高 24rpx / 圆角 12rpx / 左右 10rpx / 间距 6rpx / 文字 10rpx |
| `.entry-on` 选中标签 | `#3388FF` 实底 + 文字 `#FFFFFF` 加粗（同 `.col-btn-on` / `.col-btn-text-on`） |
| `.entry-focus` 遥控器焦点 | 只加 `border-color: #3388FF`，不要再用实底（避免与选中态撞色） |
| `.act` 右侧入口 | 高 26rpx / 圆角 13rpx / 左右 9rpx / 间距 6rpx / 文字 10rpx `#E4E4E8` |
| `.clock` 时间胶囊 | 高 26rpx / 圆角 13rpx / 左右 12rpx / 文字 13rpx `#FFFFFF`（组件内每秒自刷） |
| 图标（搜索/设置） | 搜索 = 12rpx 方框内 9rpx 圆环 + 2×5rpx 手柄 `transform: rotate(-45deg)`（app-android 支持 rotate）；设置 = `⚙` 字形。**无图片资源** |
| 搜索图标的选中态 | 必须**拆成两个类**：`.ico-ring-on` 只改 `border-color`、`.ico-handle-on` 只改 `background-color`。曾经合并成一个 `.ico-search-on { border-color; background-color }`：圆环被填成实心白/蓝圆块，放大镜变成「圆球」，用户当场指出「搜索图标不对」（NavBar 选中 + 搜索页输入框各出现一次）。搜索页的放大镜**不跟随焦点变色**，焦点只用搜索框描边表示 |
| `.topbar` 高 | 22rpx（改它必须同步 `onLoad` 里 `viewportPx` 估算） |
| `.tb-sub` 总数 | 12rpx / `#7C7D7F` |
| `.col-btn` 档位方块 | 20rpx 见方、圆角 5rpx、间距 4rpx |
| `.col-btn-text` 档位数字 | 10rpx（生效档白 + 加粗，遥控器焦点档蓝） |
| `.col-switch-label` / `.col-switch-unit`「每行」「张」 | 10rpx / `#5A5B5C` |

响应式：`compact` 现在算在 **NavBar 组件内部**（`onMounted` 里 `windowWidth < 600px`，手机竖屏），
此时隐藏分区标签与入口文字、只留图标；各页不用再管。
三个分区标签（每个约 44rpx）+ 品牌 + 「搜索 / 设置 / 时间」≈ 540rpx（可用 738rpx），
还有余量，但新增项请先算宽度（中文按字号 1:1 估算）。

`118rpx` 这个视口估算 = `.nav-spacer` 60 + 其下外边距 8 + topbar 22 + 其下外边距 8
\+ 页面上下内边距 12 + 网格底部内边距 8。

### 5.5 导航栏改版踩坑记录（2026-11，全部已实测/已修）

这一轮把图库页顶栏从「图库 + 头像占位」改成设计稿式的品牌栏（品牌 / 分区标签 / 功能入口）。
下面每条都是真踩过的，改这块前先看：

| 问题 | 现象 | 解决办法 |
| --- | --- | --- |
| 照抄设计稿的网页像素尺寸 | 设计稿是 1210px 宽的网页 mock，直接把 px 当 rpx 用会横向溢出、字号悬殊 | 一律换算成 `750rpx = 屏宽`：整条栏目标总量 ≈ 715rpx（可用 738rpx）时已经贴边，删项后 ≈ 470rpx（加「首页」标签后 ≈ 520rpx）。**新增标签/入口前先按「中文宽 ≈ 字号 × 字数、拉丁 ≈ 0.5 × 字号」估算总宽** |
| 设计稿的绿色强调色与工程蓝冲突 | 同页出现绿色（导航）+ 蓝色（每行档位/时间轴）两套强调色 | 强调色统一成工程既有 `#3388FF`：品牌图标描边/底座、选中标签实底；选中标签＝**蓝底 + 白字加粗**，与 `.col-btn-on` / `.col-btn-text-on` 完全一致，不再用 `#35E08C` |
| 导航栏高度变了但没改视口估算 | `jumpTo` 落点被浏览器钳制、末尾月份向上补页判断偏、高亮错位 | 改 `.navbar` / `.topbar` 高度必须同步 `onLoad` 里 `viewportPx = windowHeight − 118rpx`（navbar 60 + 8 + topbar 22 + 8 + 页面内边距 12 + 网格底部 8） |
| 搜索图标做成「圆环 + 竖手柄」 | 渲染出来像 `O.`，完全不像放大镜（用户当场指出） | 手柄必须斜 45°：`.ico-search` 12×12rpx `position: relative`，圆环 9×9rpx 绝对定位左上，手柄 2×5rpx 定位 `left/top: 7rpx` 后 `transform: rotate(-45deg)`；文档确认 app-android **支持** `rotate()`（不支持 `skew()`） |
| 旋转后的手柄被裁掉 | uvue 的 `overflow` 默认是 **hidden**，超出父框的旋转部分会消失 | 让旋转后的端点落在父框内（12rpx 框里终点约 9.8, 11.3rpx）；必要时才给父框加 `overflow: visible`，别指望默认值 |
| 没有图标素材 | 工程没有 `static/`，uvue 不支持伪元素，也没有 SVG 图标方案 | 图标只能用嵌套 `view` 拼几何形状，或直接用字形（`⚙`）；字形在部分 Android 上会被渲染成彩色 emoji，所以能用 view 拼的就别用 emoji |
| 设计稿里的幽灵功能 | 「最近添加 / 收藏 · 人物 · 地点 / 屏保 / Home-NAS 在线」都没有对应实现，留着会误导 | 已按要求删除（含 `.ico-play` 等死样式）；交互只保留「选中态」+「设置」真实跳转，未实现项不做假跳转、不加 toast 假装可用 |
| 悬停态在 TV 上不存在 | 设计稿的 hover 无法落地，遥控器只有点击/焦点 | 只用「选中态」表达当前分区（`NAV_ACTIVE`）；uvue 也不支持伪类，不要写 `:hover` |
| 窄屏/手机竖屏挤压 | 一排标签 + 入口在 375px 宽屏上会互相压 | `onLoad` 里 `compact = windowWidth < 600px`：隐藏中间分区标签、右侧入口只留图标。**只算一次**，web 端拖动窗口大小不会重算，需刷新 |

### 5.6 web 登录态持久化（2026-11，修「隔夜回来要重新登录」）

现象（用户上报）：头一天打开页面没操作，第二天刷新被踢回登录页，**而且账号密码框是空的**，
必须重新手输一遍。

根因是三条叠加，缺任何一条都不会这么难受：

| # | 根因 | 位置 |
| --- | --- | --- |
| 1 | web 端 `getSavedConfig()` 恒返回 `saved:false`、`saveConfig()` 是空函数 → 浏览器完全没有登录态记忆，F5 回登录页且输入框全空 | `utssdk/web/index.uts`（改造前） |
| 2 | AccessToken 只活在 dev server 进程内存，而 NAS 的 token 有有效期，隔夜后 `stat` / `timeline` 直接 401 | `vite.config.js` + NAS 侧 |
| 3 | 401 命中 `redirectToLogin()` 时置了 `fnos.skipAuto`，等于明确禁止登录页自动重登 → 只剩手敲这条路 | `pages/library/library.uvue` |

修复（按层，改这块前先对照）：

| 层 | 改法 |
| --- | --- |
| dev server | `/nas-login` 成功后把 host/port/https/账号/密码**留在进程内存**；代理 `proxyRes` 收到 401 且内存里有凭据时调 `renewSession()` 静默续期（用 `renewing` Promise 去重，并发 401 只续一次）；没有凭据时打印「dev server 重启过？请在页面登录一次」 |
| 设备标识 | `tools/fnos-login.mjs` 的 `login()` 支持传 `did`，dev server 每个进程生成一个固定 `webDid`，续期时 NAS 复用同一条设备记录，不会每次登录都新增设备 |
| 浏览器 | web 的 `saveConfig()` 把配置写进 `localStorage`（键 `fnos.web.cfg`，JSON 字符串），`getSavedConfig()` 读回并校验 host / password 非空；返回 `saved:true` 时登录页 `onLoad` 走原有自动登录分支 |
| 图库页 | `redirectToLogin()` **不再置 `skipAuto`**（token 过期 ≠ 账号密码失效）；`skipAuto` 只留给「用户主动进设置页 / 按返回键」 |
| 登录页 | 新增 `allowAutoLogin()` 节流：`fnos.autoTry` 存 `首次时间戳:次数`，60 秒内最多自动登录 3 次，超限就停在登录页并提示手动登录 |

改完后的预期行为：

| 场景 | 结果 |
| --- | --- |
| F5 / 重新打开 tab | 登录页闪一下「正在登录…」→ 直接进图库，不用输 |
| 隔夜 token 过期后刷新 | 代理先静默续期；万一续期失败，图库 401 → 回登录页 → 用本地凭据自动重登 |
| dev server 重启 | 内存凭据没了，但浏览器 `localStorage` 还在 → 仍然自动登录 |
| NAS 上改了密码 / NAS 离线 | 自动登录失败并提示错误，60 秒窗口到点后停止自动重试，表单里仍是上次填的值 |
| 第一次部署这套改动 | 浏览器里还没有 `fnos.web.cfg`，**必须手输一次**，之后不再需要 |

踩过的坑 / 不能动的地方：

| 坑 | 说明 |
| --- | --- |
| 别让 `logout()` 清掉浏览器配置 | 图库 `redirectToLogin()` 会先 `logout()`；web 的 `logout()` 只清内存 token，**不能删 `fnos.web.cfg`**，否则自动重登失效（App 端 `FnOsLogin.logout()` 同样只清 token，保持一致） |
| 自动登录会成环 | 密码真失效时「图库 401 → 登录页 → 自动登录 → 图库」会无限闪，`allowAutoLogin()` 就是为此存在，别删 |
| `getStorageSync` 读回来可能是字符串 | 沿用 5.2：只按字符串比较 / 解析，不要 `as boolean` |
| 改 `vite.config.js` 必须重启 dev server | 热更新不重载代理配置，不重启会以为修复没生效 |
| 凭据边界 | 密码只在「本机浏览器 `localStorage` + dev server 进程内存」，**不落盘（不写 `.env.local`）、不进前端产物、不进提交文件**；要清掉就删浏览器的 `fnos.web.cfg` |

### 5.7 图片查看器（T1）：做了什么、踩了什么（2026-11，代码已迁入组件，见 5.13）

> 本节写的**行为要求与踩坑**仍然有效；但实现已经搬到 `components/PhotoViewer.uvue`，
> `library.uvue` 里只剩网格 / 时间轴 / 分页与一行组件标签。改查看器请看 **5.13.4** 的契约。

T1 已实现：全屏看大图、左右连续翻页、详情面板。改这块前先看完本节，
下面每条都是实现过程中真实遇到或专门规避的。

**结构与交互**

| 项 | 现状 |
| --- | --- |
| 载体 | 不新建页面，`library.uvue` 里的整页覆盖层 `.viewer`（`v-if="viewerOpen"`） |
| 定位 | `position: absolute; left/top: 0; width/height: 100%`（**不要用 fixed**，见下） |
| 打开 | 鼠标点格子（`onCellClick`）或遥控器 OK；OK 打开 `indexAtScroll(currentScroll)` 那张 |
| 切换 | 左右键 / 两侧透明热区；**跨页连续翻**，不受单页 60 张限制 |
| 关闭 | TV：返回键 / OK；浏览器：右上角 `✕`（`#ifdef WEB`） |
| 出图 | `image(medium)` 先出（与网格同 sUrl，多半已缓存）→ 停留 300ms → `image(original)` 换原图 |
| 详情 | TV 下键开、上键收；浏览器 `!` 切换；面板在**图片下方**（流式），展开时图片自动缩小。数据来自 `gallery/getOne`（见下） |
| 返回图库 | 覆盖层只是隐藏，grid 没有重建，**滚动位置保持**（T1 验收项） |

**踩坑 / 设计决定**

| 问题 | 改法 |
| --- | --- |
| 原图字段各固件不一致 | `pageJson` 按 `originalUrl → lUrl → mUrl` 依次退化，输出到 `FnOsPhoto.original`；web 实现同步 |
| 原图可能是 png / heic | 新增 `image(path)`：缓存文件名 = `md5(path) + 原扩展名`；无扩展名回落 `jpg`（BitmapFactory 按文件头解码，也能复用 thumb 同路径缓存）。`thumb` 仍固定 `.jpg`，不动已有缓存 |
| 原图与缩略图抢线程 | 复用现有 `thumbPool`（4 线程）；查看器同时只请求当前一张原图，不为邻居预取原图 |
| 快速连切时几张几 MB 原图排队 | medium 上屏后 `setTimeout(300ms)` 才发起原图；每次切换 `viewerSeq + 1`，旧定时器/旧回调直接作废 |
| 切换时清空 src 会黑一帧 | 切换**不清空** `viewerSrc`，新图就绪才替换；`viewerSeq` 防止旧图回调盖回来 |
| 只能翻当前 60 张 | 用全库下标 `viewerOffset`（`viewerIndex` 只是窗口内下标）；`ensureLoaded(target)`：更早 `loadPrev()`、更新 `loadMore()`，到两端或失败返回 false 并停在当前张 |
| `loadPrev` 插入头部后 `viewerIndex` 失效 | 每次跳转都用 `target - startOffset` 重算窗口内索引；`viewerOffset` 始终是全库下标 |
| 连按左右键会一张张排队 | `viewerTarget` 累加最终目标，`viewerMoving` 保证只有一个追赶循环（`moveViewer`） |
| 关闭后立刻重开，旧循环 await 回来会覆盖新图 | `viewerSession` 每次开关 +1，循环每轮校验；`openViewer`/`closeViewer` 同时复位 `viewerTarget` / `viewerMoving` |
| 覆盖层背后 grid 滚动触发 `loadPrev`，把 `photos` 换掉导致查看器错位 | `onScroll` 里的补页条件加 `!viewerOpen`（只挡补页，高亮逻辑不动） |
| uvue 上 `position: fixed` 的裁剪行为依赖父级 | 覆盖层改用 `absolute + width/height:100%`；`.page` 本来就是 `relative`，absolute 的 containing block 含 padding 区，四边刚好贴满 |
| 详情面板浮在右上角挡住图片 | 改成图片下方的流式区块（520rpx 居中）：`.vw-stage` 是 `flex: 1`，面板出现时 stage 让高 → 图片自动缩小；两者间距 8rpx |
| 顶栏/底栏信息太占屏（用户要求删） | 顶部信息栏、底部提示条整块删除；信息只放详情面板；`!` / `✕` 仅浏览器（`#ifdef WEB`），TV 用下/上键与返回键 |
| UTS 里 `++` 没有先例 | 一律写 `x = x + 1`（`seen`、`viewerSeq` 等都这么写），避免编译器差异 |
| 数组下标可能被判为可空 | `const p = list[idx]` 后补 `if (p == null) return`，沿用 `updateActive` 的写法 |
| 信息字段可能为空 | 文件名按 `name → URL basename → id` 兜底；日期把 `:` 换成 `-`；分辨率缺失显示「未知」 |
| 详情信息比 Web 端少（只有文件名/时间/分辨率/类型/ID） | `getList` 不返回 EXIF/大小/路径。新增 `photoDetail(id)` 调 `/p/api/v1/gallery/getOne?id=`，拿 `fileSize`、`make/model`、`fNumber`、`exposureTime`、`isoSpeedRatings`、`focalLength`、`latitude/longitude`、`filePath`、`description` |
| getOne 的字段类型 / 拼写不稳定 | `fileSize` 可能是字节数也可能是 `"2.03 MB"`；光圈有 `fNumber` 与 `fumber` 两种拼写；ISO 可能是数字。插件统一格式化成 `fileSize / camera / aperture / exposure / iso / focal` 展示文本，页面不再判断类型 |
| 详情面板行数不可控、空字段难看 | 页面用 `InfoRow[]` 渲染，值为空的字段**整行不 push**；曝光四项合成一行（`f/1.5 · 1/3704s · ISO50 · 5.7mm`），分辨率与文件大小合成一行 |

**不要动**

- `viewerOffset` 的全库语义：任何"当前第几张"的判断都不要再用 `viewerIndex` 直接跨页比较。
- `loadPrev` 后"索引要重算"这条：新增查看器相关逻辑若在 `loadPrev` 之后直接用旧 `viewerIndex`，必然错位。
- `.vw-stage` 的 `flex: 1`：去掉它详情面板就不会挤压图片，"图片缩小 + 信息在下方"的布局会失效。

**验证状态（接手必看）**

本轮改动用的是静态检查，**尚未在 HBuilderX 里编译 / 真机运行过**（UTS 插件不能用 CLI 构建）。
接手后先跑第 8 节第 4 条，重点确认三处最容易出差异的地方：
`setTimeout` 在 UTS 中的可用性、模板里 `#ifdef WEB` 的裁剪结果、以及 `.viewer` 的 absolute 铺满效果。

### 5.8 照片详情接口（`gallery/getOne`）需求说明

查看器里「详情」面板的数据源就是 Web 版点「信息」时调的那个接口：

```http
GET /p/api/v1/gallery/getOne?id=73281
```

`getList` 只返回 id / 时间 / 宽高 / 缩略图，**文件大小、相机、曝光参数、存储路径、GPS 只有这个接口有**，
所以详情必须单独请求，不能指望列表数据。

#### 5.8.1 触发场景与前置条件

| 场景 | 行为 |
| --- | --- |
| 查看器打开 / 切换照片 | 200ms 后**预取**当前这张的详情：串行请求 `getOne` + `album/photo/belong` + `ai-person/list`（人物档案头像）+ `ai-person/personInPhoto`（快速连切时被 `viewerSeq` 丢弃，不排队） |
| TV 按下键打开详情面板 | 已预取则直接显示完整信息；未完成则先显示基础信息 + 「正在读取详细信息…」 |
| 浏览器点右上角 `!` | 同 TV，只是入口换成按钮 |
| 详情面板已展开时左右切换 | `showPhoto()` 重新填基础信息并重新预取，面板保持展开 |

前置条件：

1. 已登录且 token 可用（`ensureToken()`，失效由 `apiGet` 内部重登一次）；
2. 查看器已打开，且存在当前照片（`photos[viewerIndex]`）；
3. 当前照片的 `id` 非空；
4. 网络可达 NAS（HTTP 5666 / HTTPS 5667，自签证书走 TOFU）；
5. App 端请求必须带 `AccessToken` 头与 `authx` 签名；web 端由 dev 代理注入，**token / SALT / SECRET 不进前端**。

约束：**禁止在网格加载时批量调 `getOne`**（全库 7 万多条），只对"当前查看的这一张"请求；
它不是列表接口，不要拿它做分页或预加载。

#### 5.8.2 请求参数

| 参数 | 来源 | 规则 |
| --- | --- | --- |
| `id` | `FnOsPhoto.id`，即 `getList` 返回项的 `item.id`（全库唯一，与 Web 版 `?id=73281` 同一个值） | `trim()` 后**必须非空**，否则不发请求，直接返回 `ok=false, error="缺少照片 ID"`；拼 query 时做 URL 编码（App 端 `apiGet` 统一编码，web 端 `encodeURIComponent`） |

请求头（App 端）：`AccessToken`、`authx`（`md5(SALT_path_nonce_ts_md5(payload)_SECRET)`）、`User-Agent`。
签名 payload 只有 `id=...` 一项；业务码 5000 时 `apiGet` 自动切 ENCODED 再试一次。

#### 5.8.3 预期响应结构

`photoDetail(id)` 一次调用**串行打 3 个接口**（App / web 两端实现一致）：

| 接口 | 用途 | 失败影响 |
| --- | --- | --- |
| `GET /p/api/v1/gallery/getOne?id=` | 详情主体 | 直接 `ok=false` |
| `GET /p/api/v1/album/photo/belong?id=` | 所属相册 | 只留空 `albums`，详情照常返回 |
| `GET /p/api/v1/ai-person/personInPhoto?id=` | 照片中的人物 | 只留空 `persons`，详情照常返回 |
| `GET /p/api/v1/ai-person/list?getAll=true&limit=-1&orderBy=0` | 人物档案头像映射（`personId → faceId`） | 映射为空，头像退回 `personInPhoto` 的 faceId |

> 不调 `ai-person/list`：它只有 `id/name/faceId/itemCount`，没有头像 URL，人物名字用
> `personInPhoto` 就够了。Web 端调它应该是为了头像，本项目暂不做头像。

**getOne 实测响应（2026-11，id=73280，已脱敏路径）**：

```json
{
  "code": 0,
  "data": {
    "id": 73280,
    "dateTime": "2023:11:18 09:18:34",
    "photoDateTime": "2023:11:18 09:18:34",
    "fileName": "IMG_5294.HEIC",
    "fileSize": 1719678,
    "filePath": "/vol2/1000/Photos/xxx/PhotoLibrary/2023/11/IMG_5294.HEIC",
    "showFilePath": "",
    "mp": "7.2MP",
    "width": 2316,
    "height": 3088,
    "make": "Apple",
    "model": "iPhone 14",
    "fumber": "f/1.9",
    "exposureTime": "1/143s",
    "isoSpeedRatings": "ISO20",
    "focalLength": "2.7mm",
    "geo": "中国,广西壮族自治区,北海市,海城区,涠洲岛",
    "countryCode": "CN",
    "latitude": "21.030247",
    "longitude": "109.127572",
    "geoLatitude": "21.02787",
    "geoLongitude": "109.131838",
    "description": "",
    "isLive": 0,
    "rotation": 0,
    "additional": { "thumbnail": { "sUrl": "...", "originalUrl": "..." } }
  }
}
```

**关键差异（务必按实测写，别照抄示意）**：

- `latitude` / `longitude` 是**字符串**（`"21.030247"`），不是数字 —— App 端 `optDouble` 能解析，
  web 端必须 `parseFloat` 兜底，否则位置行永远不显示（这个坑实际踩过）；
- 地址名在 `geo`（完整中文地址），坐标在 `latitude/longitude`；`geoLatitude/geoLongitude` 是
  另一套坐标（推测是 IMG 坐标），页面不用；
- 光圈字段实测拼写是 **`fumber`**，且值已带 `f/`；`exposureTime` 已带 `s`、
  `isoSpeedRatings` 已带 `ISO`、`focalLength` 已带 `mm`；
- `fileSize` 是**字节数**（1719678 → 1.64 MB），`mp` 是字符串 `"7.2MP"`；
- `photoDateTime` 用冒号分隔：`2023:11:18 09:18:34`，页面展示时转成 `2023-11-18 周六 09:18`；
- 坐标/地址都没有的照片，`geo`、`latitude`、`longitude` 为空或 `"0"`，位置行不显示。

**album/photo/belong 实测**：

```json
{ "list": [ {
  "albumId": 17,
  "albumName": "2023-11-18-柳州",
  "photoCount": 159,
  "videoCount": 83,
  "shared": 1,
  "startDateTime": "2023:11:17 20:26:24",
  "endDateTime": "2023:11:19 10:36:42"
} ] }
```

相册行取 `albumName` + `startDateTime` 的年月（`2023年11月`）+ (`photoCount + videoCount`) 项
+ `shared == 1` 时的「已共享」，多个相册用 ` / ` 连接。

**ai-person/personInPhoto 实测**：

```json
{ "list": [ {
  "name": "刘佳雯",
  "personId": 211,
  "faceId": 39162,
  "bbox": [0.13, 0.19, 0.73, 0.79]
} ] }
```

只取 `name`；多人用「、」连接；`bbox` / `personId` 目前不用。

**getOne 字段解析表**（上面 3 个接口的原始字段怎么变成 `FnOsPhotoDetail`）：

| 字段 | 类型（可能） | 用途 | 缺失 / 异常时的处理 |
| --- | --- | --- | --- |
| `code` | number | 业务码，**必须为 0** | 非 0 视为失败，不读 `data` |
| `data` | object | 详情主体 | 不是对象（缺失/数组）时返回 `ok=false`，页面保留基础信息 |
| `fileName` | string | 文件名 | 回落到列表的 `name` → URL basename → `id` |
| `filePath` | string | NAS 路径 | 为空则**整行不显示**（`showFilePath` 实测为空，不使用） |
| `fileSize` | number（字节）或 string | 文件大小 | 数字按 B/KB/MB 格式化（`1.64 MB`）；字符串原样；缺失隐藏 |
| `mp` | string | `"7.2MP"` | 直接拼进「分辨率」行 |
| `width` / `height` | number | 分辨率 | 为 0 时用列表里的 `w`/`h` |
| `photoDateTime` | string | 拍摄时间 | 兼容 `dateTime` 字段；都没有则用列表 `dt`；页面转成 `2023-11-18 周六 09:18` |
| `make` / `model` | string | 相机 | `make + model`，model 已含品牌时不重复；都为空则隐藏「设备」行 |
| `fumber`（历史文档写作 `fNumber`） | 实测 string，已带 `f/` | 光圈 | 没带 `f/` 时补前缀 |
| `exposureTime` | 实测 string，已带 `s` | 快门 | 数字且 < 1 时还原成 `1/3704s`；字符串没带 `s` 时补 |
| `isoSpeedRatings` | 实测 string，已带 `ISO` | ISO | 没带前缀时补 `ISO` |
| `focalLength` | 实测 string，已带 `mm` | 焦距 | 没带单位时补 `mm` |
| `geo` | string | 地址名（完整中文地址） | 为空则视为没有地理信息 |
| `latitude` / `longitude` | **string**（也可能是 number） | GPS | `parseFloat` 兼容；两者都为 0 视为没有定位，隐藏「位置」行（**不反查地址**） |
| `description` | string | 描述 | 空则隐藏 |
| `albums` | `FnOsAlbumBrief[]` | 来自 `album/photo/belong`，含 `cover` 封面路径 | 请求失败/无相册时为空数组 |
| `persons` | `FnOsPersonBrief[]` | 来自 `ai-person/personInPhoto`，含 `avatar` 头像路径 | 请求失败/无人脸时为空数组 |

解析必须**宽松**：字段名/类型按上表做兼容（不同固件确实不一样），任何字段取不到都不能抛异常、
不能让详情面板整体失败。

#### 5.8.4 页面展示

详情面板在图片下方（见 5.7），内容是一个 `InfoRow[]`，**按固定顺序、空值整行不 push**：

详情面板在图片下方、**左右铺满**（宽度 100%，左右 padding 14rpx），内部两栏：

| 区域 | 内容 |
| --- | --- |
| 左栏（280rpx） | **人物**：圆形头像 32rpx + 名字，横向 `flex-wrap`，**多人自动换行**（必须给多人留位置）；**相册**：封面 34rpx + 名称 / 年月 / 项数 / 已共享，多个纵向排在下面 |
| 右栏（flex:1） | 参数两列排布：时间、分辨率、设备、曝光、类型、ID 各占半行；文件名、路径、位置、描述占整行 |

右栏字段（空值不 push）：

| label | value 组成 |
| --- | --- |
| 文件名 | `fileName` → 列表 `name` → URL basename → `id`（整行） |
| 时间 | `photoDateTime`/`dateTime` → 列表 `dt`，格式化 `2023-11-18 周六 09:18` |
| 分辨率 | `mp · 宽 × 高 · 文件大小`，例 `7.2MP · 2316 × 3088 · 1.64 MB` |
| 设备 | `make + model`，例 `Apple iPhone 14` |
| 曝光 | `f/1.9 · 1/143s · ISO20 · 2.7mm` |
| 路径 | `filePath`（整行，长路径换行） |
| 位置 | `geo · 纬度, 经度`（整行） |
| 描述 | `description`（整行） |
| 类型 | `视频` / `照片`（列表 `cat`，不依赖详情） |
| ID | 列表 `id`（详情没有 id 也不影响） |

头像与封面（**实测 URL 规则**）：

| 项 | URL | 加载方式 |
| --- | --- | --- |
| 人物头像 | `/p/api/v1/stream/face/{faceId}`，`faceId` 取 **`ai-person/list` 里该 person 的 faceId**（先按 `personId` 建映射）；`personInPhoto` 的 faceId 只是「这张照片里的那张脸」（常是侧脸），仅作兜底 | 文本先出，再 `image(path)` 下载到本地缓存；失败回退「姓名首字」圆形色块 |
| 相册封面 | `/p/api/v1/stream/p/t/{posterUpId}/xs/{posterUUID}`（`belong` 返回的字段） | 同上；失败回退「册」字色块 |

展示约束：

- 面板左右铺满，不再是居中卡片；左栏固定 280rpx，右栏 `flex:1`；
- 人物头像横向换行，**多人不会把右栏挤没**；相册多个纵向排列；
- 空值整项不显示（不写「无」「未知」）；文件名 / 路径 / 位置 / 描述占整行；
- 图片区 `.vw-stage` 仍是 `flex: 1`，面板变高时图片自动缩小；
- `label` 唯一（模板当 `:key`），不要加同名项。

#### 5.8.5 加载状态

| 状态 | 表现 |
| --- | --- |
| 刚打开查看器（0~200ms） | 面板若已打开，显示列表基础信息（文件名/时间/分辨率/类型/ID），**不显示加载提示** |
| 请求中且面板已打开 | 基础信息下方灰色「正在读取详细信息…」；3 个请求串行，局域网一般 300ms 内结束 |
| 成功 | 用完整信息**整体替换** `infoFields`，同时填充 `infoPersons` / `infoAlbums`；头像与封面再异步下载后填 `src` |
| 头像/封面加载中 | 先显示「姓名首字」圆块 /「册」字块，`image()` 回来替换；失败调用 `onPersonImgError` / `onAlbumImgError` 退回占位 |
| 失败 | 保留基础信息，加载提示消失，**不弹 toast、不退出查看器**（看图优先） |
| 切换照片 | `updateInfo()` 先重置为基础信息、`infoLoading=false`，旧响应由 `viewerSeq` 丢弃 |
| 关闭查看器 | `viewerSession`/`viewerSeq` 递增，在途响应一律丢弃；`infoLoading` 复位 |

其他约定：

- 同一张照片再次打开会重新请求（当前没有做详情缓存）；如果以后加缓存，key 用 `id`，并保证
  「切换后旧的迟到响应不得覆盖新照片」这条依旧成立；
- 请求 200ms 去抖只用于**预取**，用户按下键时若还没发起，也走同一次预取，不会重复请求。

#### 5.8.6 失败与异常处理

| 情况 | 结果 |
| --- | --- |
| `id` 为空 / 只有空格 | 不发请求，`ok=false, error="缺少照片 ID"`，面板只剩基础信息 |
| HTTP 401（网关） | App：有访问码则重校验一次再重试；仍失败 → `ok=false` |
| 业务码 401 / 5001（token 失效） | `apiGet` 清 token 重新登录一次再重试；仍失败 → `ok=false`，**不触发图库的 401 跳登录**（详情失败不该把用户踢出查看器） |
| 业务码其它非 0 | 返回 `ok=false`，`error="照片详情获取失败（code X）"`，面板保留基础信息 |
| 超时 / 连接失败 / DNS 失败 | `describe(e)` 转成中文错误，`ok=false`，面板保留基础信息 |
| 返回不是 JSON / `data` 不是对象 | `ok=false`，同上 |
| 字段缺失或类型不符 | 该行隐藏或用列表数据兜底，不影响其它行 |
| 相册（belong）或人物（personInPhoto）失败 | 只留空 `albums` / `persons`，详情本体照常显示，不报错 |
| `ai-person/list` 失败 | 头像映射为空，`persons` 仍返回，头像退回 `personInPhoto` 的 faceId |
| 快速连切 / 关闭查看器后才返回 | 按 `viewerSeq` 直接丢弃，**不改当前面板**（防止 A 的详情显示到 B 上） |

错误信息只写进 `FnOsPhotoDetail.error`，页面目前不展示（避免遮挡图片）；排查时可在
`loadDetail` 里临时打印，提交前删掉。

#### 5.8.7 验收标准

1. TV 打开任意照片 → 下键，2 秒内出现文件名/时间/分辨率+MP+大小/设备/曝光/相册/人物/路径/位置，
   与 Web 版「信息」的条目一致（人物头像、「查看文件 / EXIF 信息」入口除外）；
2. 浏览器点 `!` 同样的内容，`✕` 关闭正常；
3. 无 EXIF 的照片不出现「设备」「曝光」行；无 GPS 的不出现「位置」行；路径为空不出现「路径」行；
4. 快速左右翻 10 张后再打开详情，显示的是**当前这张**，不串图；
5. 停掉 NAS 或断网后打开详情：图片仍可左右翻，面板保留基础信息，不崩、不退出查看器；
6. 详情请求不会在网格加载时批量发生（Network 里只有当前查看的那一张）。

### 5.9 详情面板重排版与人物/相册头像（2026-11）

详情从「单列键值对」改成「左右两栏、左右铺满」，并补上人物头像、相册封面。
下面记这次怎么找到真实资源地址、以及踩到的坑。

**目标与结果**

| 项 | 现状 |
| --- | --- |
| 面板宽度 | 100%（左右 padding 14rpx），不再居中卡片 |
| 左栏 | 280rpx：人物（圆形头像 32rpx + 名字，`flex-wrap` 支持多人）、相册（封面 34rpx + 名称/年月/项数/已共享，多个纵向排列） |
| 右栏 | `flex:1`：字段两列，时间/分辨率/设备/曝光/类型/ID 各半行；文件名/路径/位置/描述占整行（`InfoField.full`） |
| 头像/封面 | 文本先出，`image(path)` 异步下载后填 `src`；失败回退「姓名首字」圆块 /「册」字块 |
| 图片区 | `.vw-stage` 仍 `flex: 1`，面板变高时图片缩小；加 `min-height: 110rpx` 兜底 |

**怎么确认真实地址（别再猜）**

1. 本机 dev server 在跑时，可以借它的代理直接读 NAS 真实响应，不需要账号密码：
   ```
   Invoke-RestMethod "http://127.0.0.1:5173/nas/p/api/v1/gallery/getOne?id=73280"
   ```
   代理会自动注入 AccessToken + authx；`/nas` 后面接 NAS 的原始路径即可。
2. 图片地址同理可以直接 GET 验证（看状态码 / Content-Type / 大小）：
   - `/p/api/v1/stream/p/t/{posterUpId}/xs/{posterUUID}` → 200 `image/jpeg`（相册封面）
   - `/p/api/v1/stream/face/{faceId}` → 200 `image/jpeg`（人物头像）
3. NAS 相册 Web 是独立 SPA，入口 `/photos/`，静态资源在 `/p/assets/`；
   详情组件在 `CSlpp80iFRfVx1Ru.js`，人物列表/头像逻辑也在该 chunk 内。
   找 chunk 时**不要只按 `xxx-8位hash.js` 匹配**：实际有 `xxx2.js` 这类名字，
   应提取所有 `*.js` 字符串再去 `/p/assets/{name}` 搜关键字（如 `stream/face`）。

**人物头像的坑（最重要）**

| 问题 | 结论 |
| --- | --- |
| 之前用 `personInPhoto.faceId` 做头像 | 那是「这张照片里的那张脸」，常是侧脸/车内自拍，与 Web 显示不一致 |
| Web 的真实做法 | 拉 `ai-person/list?getAll=true&limit=-1&orderBy=0`，按 `personId` 找 `person.faceId`，头像 = `/p/api/v1/stream/face/{person.faceId}` |
| 实现 | `personsOf()` 先拉 list 建 `personId → faceId` 映射，`personInPhoto` 的 faceId 只作兜底；list 失败时退回 |
| 待确认 | 实测 person 211 的 faceId=108，但服务端当前返回的是户外侧脸；若 Web 仍显示正脸，可能是浏览器缓存了旧人脸图。需要强刷 Web 对比，或提供头像 URL |

**接口变化**

| 项 | 变化 |
| --- | --- |
| `photoDetail(id)` | 由 3 个请求变 4 个：`getOne` + `album/photo/belong` + `ai-person/list` + `ai-person/personInPhoto` |
| `FnOsAlbumBrief` | 新增 `cover`（封面流路径；`posterUpId`/`posterUUID` 缺失时为空） |
| `FnOsPhotoDetail.persons` | 从 `string[]` 改为 `FnOsPersonBrief[]`（`name` + `avatar`） |
| 页面 | `infoRows` 拆成 `infoFields`（右栏，带 `full`）+ `infoPersons` / `infoAlbums`（左栏） |

**踩坑 / 改法**

| 问题 | 改法 |
| --- | --- |
| 经纬度是字符串 → web 端 `getNumber` 得 null，位置行消失 | `numberField()`：先 `getNumber`，再 `getString` + `parseFloat`；Kotlin 用 `doubleField` 兼容 Number/String |
| web 端 `getJson` 是回调式，没法串行 await | 新增 `getJsonAsync()`；返回 `Promise<UTSJSONObject>`，失败 resolve 空对象（避免 `UTSJSONObject \| null` 的兼容风险），调用方用 `data == null` 判断 |
| 头像/封面异步改对象属性不刷新 | 统一整块替换数组：`const next = list.slice(); next[i] = {...}; infoXxx.value = next` |
| 模板里直接给 `<image>` 塞 NAS 相对路径（App 端 401） | 一律先 `image(path)` 换成本地路径；web 端由插件加 `/nas` 前缀 |
| 头像加载失败留空白圆 | `onPersonImgError` / `onAlbumImgError` 清空 `src`，回退首字 /「册」块 |
| 详情面板太高把图片压没 | `.vw-stage` 加 `min-height: 110rpx`；左栏字号/头像尺寸收到 32rpx |
| `UTSJSONObject.getString` 对数字返回 null | 数字字段先 `getNumber` 再 `getString`（`fieldText` 两个都试） |
| 切换照片后旧头像盖回来 | 头像/封面加载也传 `seq`，`seq != viewerSeq` 直接丢弃 |

**验证状态**

- 静态检查通过，但**未在 HBuilderX 编译 / 真机运行**；
- web 端刷新即可验证（dev server 不用重启），App 端改了 Kotlin/UTS 需要重新运行基座；
- 头像问题按本节「待确认」处理：先强刷 Web 对比，仍不一致就要头像 URL。

### 5.10 网格类型角标与实况照片播放（2026-11）

> 角标文案函数已抽到 `common/photo.uts`（`videoTagText` / `showLiveTag`），实况播放代码在
> `components/PhotoViewer.uvue`（见 **5.13.4**）；本节的现象与踩坑仍然适用。

这一轮补两件事：**网格里分清照片 / 视频 / 实况**，以及**实况照片在查看器里自动播片段**。
动手前先看完本节，特别是最后的「没验证到的部分」。

**需求（用户原话 → 逐条落地）**

| 需求 | 落地 |
| --- | --- |
| 网格要能表现实况图、视频 | 视频格「▶ 视频」、实况格「实况」角标（见下表） |
| 实况图点击后自动播放 | `showPhoto()` 里判断 `cat != "video" && live && video 非空` → `playLive(p, false)`；静态图并行加载，片段就绪后盖上去播 |
| 播放完展示图片 | `@ended` → `stopLive()` 卸载 `<video>`；静态图一直在下面，视觉上回到图片 |
| 遥控器点播放可以再次播放 | 查看器内 **OK 键**：实况照片 = 重播，其它照片仍 = 关闭；**返回键始终关闭** |
| 浏览器右上角有个播放按钮 | `#ifdef WEB` 的 `.vw-tools` 加 `▶`，仅实况照片显示（`canReplayLive()`） |

**网格角标**

| 项 | 现状 |
| --- | --- |
| 视频格 | `cat == "video"` → 左下角深色半透明胶囊「▶ 视频 01:12」；时长取 `FnOsPhoto.duration`（`mediaDuration` 秒，0 时只显示「视频」），≥1 小时显示 `h:mm:ss`，见第 3 节 |
| 实况格 | `cat != "video"` 且 `live` → 左下角胶囊「实况」，图标是两层 `view` 拼的同心圆（不用 emoji，避免被渲染成彩色） |
| 尺寸 | 胶囊高 13rpx、字号 8rpx、图标 7rpx、底色 `rgba(0,0,0,0.55)`；8 列时格子约 79rpx 仍放得下 |
| 定位 | `.cell` 加 `position: relative`（原有 `overflow: hidden` 保留），角标 `position: absolute` 贴左下；绝对定位**不参与 `contentHeight`**，不影响滚动映射和年份跳转 |

**实况播放（查看器）**

| 场景 | 行为 |
| --- | --- |
| 打开实况照片 | 静态图照常走 `image()`，片段下载完**自动播一遍（带原声）**，`@ended` 卸载 `<video>`，视觉上回到图片 |
| 重播（TV） | 遥控器 **OK**：实况照片时重播（带声音）；其它照片保持原来的「OK 关闭」。**返回键始终关闭** |
| 重播（浏览器） | 右上角工具条多一个 `▶`，仅实况照片显示，点击重播 |
| **挂载 → 首帧之间** | 先透明（`.vw-live-idle`）挡住黑底和原生播放按钮，`@play`（真的出画面了）后才可见 —— 原因与改法见下面「实测反馈与修复」第 2 条 |
| 左右切换 / 关闭查看器 | `stopLive()` 卸载 `<video>`，不留后台声音 |
| 片段放不出来 | `@error` → App 端补一次 `file://` 前缀重试，仍失败静默退回图片，不弹错、不退出查看器 |

**为什么片段要落盘（App 端）**

> ❗2026-11-15 真机更正：`<video>` 的 `header` 属性**在 uni-app x App-Android 上并没有把 header
> 交给 ExoPlayer**。logcat 实测 `HttpDataSource$InvalidResponseCodeException: Response code: 401`
> （直链 MP4 与 HLS 都是），所以「直链 + header」这条路是死的 —— 播放地址必须走进程内的
> **本机回环代理** `mediaUrl()`（见 **5.19**），由插件自己补 `AccessToken` 并透传 `Range`。
> 实况片段仍然下载落盘，是因为它小、且缓存后重播不再请求。
>
> `<image>` 依旧加不了请求头，缩略图/原图仍然走下载缓存。

所以 `FnOsLogin.video(path)` 复用 `image()` 那一套：同一个缓存目录 `cacheDir/fnos_thumbs`、
同一个 4 线程池、同样的 401 重登重试，只是缓存文件固定 `.mp4`，回传本地绝对路径。
实况片段通常 1~3 秒（几十 KB ~ 几 MB），下载完即命中缓存，重播不再发请求。

web 端**不下载**：`video(path)` 直接返回 `/nas` + path，`<video>` 同源播放，token 由 dev server 代持；
组件发的 Range 请求由代理原样透传（`vite.config.js` 不用改）。

**重播为什么必须换 key**

只改 `src` 不行 —— 重播时 src 还是同一个值，组件不会回到第一帧，看起来像没反应。
所以 `playLive()` 里每次都 `liveKey++`，模板用 `:key="liveKey"` 强制重建 `<video>`。

**实测反馈与修复（两轮，都是浏览器实测反馈的）**

| # | 现象 | 根因 | 改法 |
| --- | --- | --- | --- |
| 1 | 首次进查看器**没声音**，二次点播放才有 | 自动播放一开始固定 `muted = true`（怕浏览器拦截），只有点 `▶` 重播才走 `muted = false` | 自动播放改成 `playLive(p, false)`，`liveMuted` 默认 `false`；浏览器拦截的情况用下面的 700ms 兜底处理，App 端无此限制 |
| 2 | 进查看器的一瞬间**黑屏**，还闪出一个**播放按钮** | ① `<video>` 一挂载就铺满舞台，挂载→首帧解码（web 端还要先拉流）这段是黑底；② `show-center-play-btn` 默认 `true`，光设 `controls=false` 关不掉它 | ① 新增 `liveReady`：video 先挂 `.vw-live-idle`（`opacity: 0`），`@play` 之后才切成可见，下面一直是静态图；② 控件全关：`show-center-play-btn` / `show-play-btn` / `show-progress` / `show-fullscreen-btn` / `show-loading`；③ `.vw-live` 加 `background-color: transparent` |

兜底细节（对应问题 1 的另一半）：

- 浏览器可能拦下「有声自动播放」（冷启动 Chrome / Safari，表现是停在首帧、不报错也不动）；
- `playLive()` 末尾 `setTimeout 700ms` 检查 `@play` 是否触发过，没触发就 `liveMuted = true` + `liveKey++`
  重播一次 —— 保证片段一定会动，最坏情况只是没声音；
- 这段用 `#ifdef WEB` 包着，App 端不走。

排错提示：

| 现象 | 结论 |
| --- | --- |
| 自动播放**和**手动重播都没声音 | 不是静音策略，是片段本身没音轨（`videoUrl` 指向的转码文件可能只有视频轨） |
| 自动播放没声音、`▶` 重播有 | 浏览器拦了有声自动播放且兜底没生效 → 检查 `@play` 事件是否真的触发 |
| 重播没反应（还是停在末尾） | `liveKey` 没变 → 见上面「重播为什么必须换 key」 |

**新增 API**

| 端 | 签名 | 说明 |
| --- | --- | --- |
| Kotlin | `FnOsLogin.video(path, callback)` | 下载到 `fnos_thumbs/<md5>.mp4`，回 `{path, error}` |
| UTS(app-android) | `video(path): Promise<string>` | 本地绝对路径，直接给 `<video src>` |
| UTS(web) | `video(path): Promise<string>` | `/nas` + path |

`FnOsPhoto.live` / `FnOsPhoto.video` 插件早就返回了（`isLive` / `additional.thumbnail.videoUrl`），
本轮只是页面 `Photo` 类型之前漏接，这次补齐（`loadMore` / `loadPrev` 两处构造都要带上）。

**怎么用 / 怎么验**

1. 网格：视频格左下角「▶ 视频」，实况格「实况」，普通照片没有角标；
2. 点实况格进查看器 → 静态图先出 → 1 秒内动起来（**带声音**；浏览器拦下会自动静音重播一次）→ 播完停回图片；
   **中间不该出现黑屏，也不该闪出播放按钮**（video 在 `@play` 之前是 `opacity: 0`）；
3. TV：再按 **OK** 重播（这次有声音）；**返回** 关闭；左右键切图不残留声音；
4. 浏览器：右上角 `▶` 重播、`!` 看详情、`✕` 关闭；
5. 切到非实况照片：没有 `▶` 按钮，OK 键恢复「关闭」；
6. 改了 Kotlin 必须**重新运行/安装基座**（热刷新只覆盖 `.uvue` / `.uts`）；只改页面时 web 刷新即可。

**没验证到的部分（重要）**

| 项 | 说明 |
| --- | --- |
| `videoUrl` 字段名 | **没有在真实数据上确认过**（本轮 `.env.local` 的 `FNOS_TOKEN` 是空的，`fnos-probe.mjs` 也没打印它）。若实况只出静态图不动：先跑 probe 或借 dev 代理抓一次 `getList`，看 `additional.thumbnail` 里到底叫什么（`videoUrl` / `liveVideoUrl` / 其它），然后同步改 `FnOsLogin.pageJson()` 与 web 的 `page()` 两处 |
| 降级路径 | `@error` → `file://` 重试只是代码兜底，**没在真机跑过** |
| App 端 `opacity` | `.vw-live-idle` 的 `opacity: 0` 在 web 端实测有效（消除黑屏与播放按钮闪烁）；App 端原生 video 是否吃这个样式没实测。App 播的是本地文件，首帧很快，即使无效也只是极短一闪 |
| 原生层级 | App 端 `<video>` 是原生组件，播放期间展开详情面板可能压在面板之上（未实测；片段只有 1~3 秒，实际影响小） |
| 视频文件 | 5.10 这一轮只在网格加了角标；查看器里的视频播放器是后来补的，见 **5.11**（同样未真机验证） |

### 5.11 视频播放器（T2）：不自动播放、直链播放、倍速（2026-11，两轮）

> 本节的行为要求与踩坑仍然有效（包括函数定义顺序、`<video>` 重挂必须换 key、`:header` 类型）。
> 代码已迁入 `components/PhotoViewer.uvue`（见 **5.13.4**），下文说的「页面」即组件内部，
> `<video id>` 从 `vw-video` 改成了 **`pw-video`**（`uni.createVideoContext` 已同步）。

视频文件（`cat == "video"`）在同一个查看器覆盖层里播放，不新建页面。

**功能清单（现在生效的）**

| 需求 | 落地 |
| --- | --- |
| 进入**不自动播放** | 只准备 src / 请求头，`videoStarted=false` 时 `<video>` 根本没挂载，状态行写「按 OK 开始播放」；按 OK 才挂载并自动播 |
| 播放 / 暂停 | OK 或点播放键：开始 → 暂停 ↔ 继续；播完显示「已结束 · OK 重播」，OK 从 0 重挂重播 |
| 快进 / 后退 | 左 / 右 = ±10 秒；连按累积，进度条立刻跳目标，180ms 去抖后才真正 `seek()` |
| 进度显示 | `.vp-bar`：播放键 + `mm:ss` + 进度条 + `mm:ss`；`@timeupdate` 约 250ms 更新 |
| 倍速 | 状态行右侧 `1.0x` chip **点一下切下一档、循环**；TV 按**上**键等价。档位升序 `0.5 → 0.75 → 1.0 → 1.5 → 2.0 → 0.5` |
| 每个视频默认 1.0x | `videoFreshOpen()` 复位 `videoRateIndex = VIDEO_RATE_DEFAULT`；同一视频内重播保留当前倍速 |
| 清晰度 | **播放器里没有入口**（用户明确要放设置页）；档位在设置页选，查看器按档位取流，见 **5.18** |
| 关掉不残留声音 | `stopVideo()` 清空 src 卸载 `<video>`（切片 / 关查看器都调） |

**基础行为**

| 项 | 现状 |
| --- | --- |
| 地址 | `photo.video`：thumbnail 里按 `videoUrl → liveVideoUrl → 条目级 videoUrl` 容错取（Kotlin `videoUrlOf()` / web `pickVideoUrl()`）。取不到显示「该视频没有可播放地址」 |
| 取流 | App 用 **`mediaUrl(path)`（本机回环代理，见 5.19）**广播 `http://127.0.0.1:<port>/v/<base64>`，由插件补 `AccessToken` 并透传 `Range`；web 用 `/nas` 同源代理。**不再先下载整段视频**。⚠️ `streamUrl()` + `<video :header>` **在 uni-app x 真机是 401**（5.18.4），别再用 |
| 回退 | App 端 `@error` 时回退一次「`video(path)` 下载到本地再播」（`videoFallbackTried` 只回退一次），仍失败才卸载并报错 |
| 遥控器 | OK = 开始 / 暂停 ↔ 继续（播完重播）；左/右 = ±10 秒；**上 = 切下一档倍速**（详情展开时先收详情）；下 = 详情；返回 = 关闭 |
| 状态区 | `.vp-wrap` **在画面下方**，绝不叠在画面上：App 原生 `<video>` 会压住覆盖层。上排 `.vp-line`（状态文案 + 倍速 chip），下排 `.vp-bar`（播放键 + 进度条） |
| 连按 / 边界 | `videoSeekTarget` 累积 + `videoHoldUntil` 保护 700ms；已在 0 / 已到结尾只提示不越界；seek 上界 `duration − 0.3`，避免立刻触发 ended |

**倍速（现在的实现）**

- 档位数组 `VIDEO_RATES = [0.5, 0.75, 1.0, 1.5, 2.0]`（**升序**，和 fnOS web 端的
  `preview_video_playbackrate_plugin` 是同一组值，只是 web 端按降序排菜单），`VIDEO_RATE_DEFAULT = 2`（1.0x）。
- 切档只有一条路径：`cycleVideoRate()` —— 下标 +1、到头回 0，然后 `applyVideoRate()` →
  `VideoContext.playbackRate()`，同时状态行 `flashVideoHint('倍速 1.5x')` 给反馈。
- 触发方式：状态行右侧 `1.0x` chip **点一下**（浏览器）；TV 按**上**（详情面板展开时先收详情）。
- **没有设置面板**：早期做过「‹ 值 ›」两行面板，用户明确否掉了（见下面踩坑第 5 条），不要再加回来。
- `applyVideoRate()` 拿不到 `VideoContext` 时 120ms 重试一次；重挂 `<video>`（重播 / 回退下载）会丢倍速，
  所以 `onVideoPlay()` 里再补一次。
- **不跨视频记忆**：`videoFreshOpen()` 复位 `videoRateIndex = VIDEO_RATE_DEFAULT`
  → 每个视频进来都是 1.0x；同一个视频内重播保留当前倍速。

**清晰度（照抄了 web 端，但暂时不放进播放器）**

web 端播放器分包（从 NAS 的 `/p/assets` 里抓出来对读过的）做法：

| 能力 | web 端做法 |
| --- | --- |
| 清晰度来源 | `GET /p/api/v1/video/decode/info?id=<photoId>` → `data.quality[]`，每项 `{resolution, bitrate, progressive}`；**原始画质那项没有 bitrate** |
| 菜单去重 | 按 resolution 去重、同分辨率取最大 bitrate、跳过没 bitrate 的；标签 `Ht(resolution, progressive)` = 有 progressive 补 `p`（`1080` → `1080p`，注意它是**逐行/隔行**的意思，不是「渐进式下载」） |
| 取转码流 | `POST /p/api/v1/video/decode/play`，body `{id, bitrate, resolution, audioIndex, audioEncoder, videoEncoder, channels}` → `data.playLink`，播放地址 = `/p/api/v1/video/decode/stream` + playLink（**HLS / m3u8**，web 用 hls.js） |
| 切换时机 | 切完用 `startPosition` 续播；那一路播完用 `it.destroy()` 释放 |

本工程现状：

| 项 | 状态 |
| --- | --- |
| 播放器入口 | **已删除**：查看器里只有倍速，档位在**设置页**选（T4.6，见 5.18） |
| 插件接口 | **在用**：Kotlin `videoInfo(id)` / `videoPlay(id, bitrate, resolution)` / `remoteUrl(path)`；UTS 同名导出；web 端是返回错误的桩（`playVideo()` 里用 `#ifdef WEB` 跳过转码） |
| 复用点 | `mountVideoStream(url, pos, autoplay)` 负责「换源 + 按原位置续播」，转码流失败回退原画也走它 |
| 生效档位 | `fnos.video.quality` 默认 **`sd`（标清）**（用户要求），原图档不发任何转码请求 |

**平台差异（重要）**：清晰度切换是**服务端转码 + HLS**，浏览器 `<video>` 播不了 HLS，dev 代理也只签 GET 的
authx，所以 **web 调试端验证不了清晰度**（`videoInfo`/`videoPlay` 在 web 端返回错误；设置页只存值）；
真机 App-Android 走 ExoPlayer，HLS 支持（`manifest` 里已有明文流量开关）。

**踩坑表（都是这两轮真实遇到的）**

| # | 现象 | 根因 | 改法 |
| --- | --- | --- | --- |
| 1 | 进查看器就自动播、还要先等「正在准备视频…」好几秒（大视频更久） | 旧实现是「整段下载到本地再播」；而且 `FnOsHttp.bytes()` 会把**整个响应读进 ByteArray**，几百 MB 的视频直接 OOM | ① 改成直链播放（第 2 条）；② `video()` 保留给实况片段 / 回退，下载改成**流式写盘** `FnOsHttp.download()`，先写 `<md5>.mp4.part`、成功后改名，失败删残文件（否则半截文件会被 `cachedVideo()` 当命中缓存，播出来是坏的） |
| 2 | 文档里一直写着「`<video>` 加不了 `AccessToken` 头（header 是 Vapor 接口）」 | ❗**2026-11-15 真机实测：这句「加不了」是对的** —— `:header` prop 虽在（`header?: UTSJSONObject`），但 uni-app x 真机上并没有把 header 交给 ExoPlayer：直链 MP4 与 HLS 都是 `InvalidResponseCodeException: 401`（logcat 证据见 5.18.4）。中间那轮「源码核对说能注入」的判断被真机推翻了 | 改用**插件内的本机回环代理**：`mediaUrl()` → `http://127.0.0.1:<port>/v/<base64>`，由 `FnOsMediaProxy` 补 `AccessToken` 并透传 `Range`（完整设计见 **5.19**）；`@error` 时仍回退一次下载（`videoFallbackTried`） |
| 3 | 想做清晰度，但不知道 fnOS 怎么做的 | 没有现成文档 | 从 NAS 前端 bundle 里扒出 `decode/info`、`decode/play`、`decode/stream` 三个接口和 body 参数（见上表），插件 `videoInfo` / `videoPlay` 按它实现 |
| 4 | 清晰度菜单在浏览器里只有「原画」，选不了；真机上也没得选 | 转码流是 **HLS**，Chrome `<video>` 播不了；`decode/play` 是 POST，dev 代理只签 GET，连 playLink 都拿不到 | 用户拍板：**播放器里不做清晰度**，后续进设置页；插件接口保留，浏览器端 `videoInfo`/`videoPlay` 明确返回错误，不要假装能用 |
| 5 | 浏览器里倍速调不了 | 页面只接了 TV 按键通道（`FnOsTvKeys`），浏览器没有任何「左/右」入口 | 先做成「chip 打开面板 + 点 `‹`/`›`」，用户反馈**不要弹面板**；最终改成 **chip 点一下循环切档**，TV 用「上」键，两处共用 `cycleVideoRate()` |
| 6 | 点「›」反而变慢 | 档位数组是降序（`2 → 0.5`，照抄 web 菜单顺序），下标 +1 = 更慢 | `VIDEO_RATES` 改成**升序**，循环方向就是「往后加」 |
| 7 | 上一个视频调了 0.5x，下一个视频还是 0.5x | `videoRateIndex` 是页面级状态，换片时没复位 | `videoFreshOpen()` 复位到 `VIDEO_RATE_DEFAULT`（用户要求每个视频默认 1 倍速） |
| 8 | 状态文案和倍速 chip 挤在进度条那一行，放不下 | `.vp-bar` 一行要放 播放键 + 两个时间 + 进度条 + 状态 + chip | 拆成两行：`.vp-line`（状态 + chip，22rpx）+ `.vp-bar`（播放键 + 时间 + 进度，26rpx）；`VP_TRACK_RPX` 随之 470 → **610** |
| 9 | `playVideo` 里调 `videoFreshOpen()` 编译不过 | UTS 编译成 Kotlin 局部函数，**必须先定义后调用**（见 5.2 第一条） | 把 `videoFreshOpen()` 挪到 `playVideo()` 前面；同类问题还有 `flashVideoHint` / `videoContext` 必须定义在 `cycleVideoRate` / `applyVideoRate` 之前 |
| 10 | `:header` 传 `null` 不放心 / 类型对不上 | prop 声明是 `UTSJSONObject`（非 null） | 用 `ref<UTSJSONObject>(JSON.parseObject('{}')!)` 初始化，插件返回 null 时不覆盖 |

**新增 / 改动的接口（设置在 `uni_modules/fnos-auth`）**

| 层 | 签名 | 用途 |
| --- | --- | --- |
| Kotlin | `FnOsLogin.remoteUrl(path)` | 相对路径 → NAS 绝对地址（直链播放） |
| Kotlin | `FnOsLogin.videoInfo(id, cb)` / `videoPlay(id, bitrate, resolution, cb)` | 清晰度列表 / 申请转码流（**当前页面不调用**，留给设置页） |
| Kotlin | `FnOsHttp.post(...)` / `FnOsLogin.apiPost(...)` / `bodyPayload(...)` | POST + authx（签名用请求体原文，`code 5000` 用排序 `k=v` 重试） |
| Kotlin | `FnOsHttp.download(...)` | 视频流式写盘（替代会把整段读进内存的 `bytes()`） |
| UTS | `streamUrl(path)` / `videoHeader()` | 直链地址 / `<video :header>` 用的 `{AccessToken}` |
| UTS | `videoInfo(id)` / `videoPlay(...)` | 同上，web 端是返回错误的桩 |
| 页面 | `cycleVideoRate()` / `applyVideoRate()` / `videoFreshOpen()` / `mountVideoStream()` | 倍速循环 / 落倍速 / 打开视频复位 / 换源续播 |

**不要动 / 坑**

- `.vp-wrap` 不要改成叠在 `.vw-stage` 里的浮层（App 原生 `<video>` 会压住覆盖层）；
- `VP_TRACK_RPX = 610` = 750 − 条内边距 24 − 图标 24 − 两个时间 80 − 轨道外边距 12；
  改 `.vp-bar` / `.vp-ico` / `.vp-time` 尺寸必须同步。进度条用**显式 rpx** 而不是 `width:%`；
- **视频模式下左右键不切图**（换成快进/后退）。要连续翻页只能先返回网格，别偷偷改回切图；
- 「上」键在视频模式下被倍速占用：**详情展开时先收详情**，再按才是切倍速，别把顺序改反；
- `videoStarted` 是「有没有按过 OK」的唯一开关：`mountVideoStream()` 在 `videoStarted=false` 时只换 src 不挂载，
  否则「不自动播放」会被绕过；
- 播放器里不要重新加清晰度入口（用户已明确要放设置页）；
- POST 的 authx 签名用**请求体原文**做 payload，`code 5000` 时改用排序后的 `k=v` 再试一次
  （`FnOsLogin.apiPost` / `bodyPayload`）。这条**没有在真机上验证过**。

**验证状态（2026-11-15 更新）**：真机已跑通（`mediaUrl` 本机代理，见 5.19）——
不自动播放（「按 OK 开始播放」）、OK 播放/暂停、±10 秒、进度条、倍速循环、关掉不残留声音都验过。
两条早期悬案结论：`<video :header>` **真机无效**（401，`streamUrl` 只留着看，播放必须用 `mediaUrl`）；
`video/decode/*` 的 POST 签名（请求体原文）**被 NAS 接受**（`code=0`，`playLink` 正常返回）。

### 5.11.1 在电脑上测遥控器（2026-11-22，电视连不上基座时的替代方案）

电视装不了 / 连不上调试基座时，按下面顺序选，**越靠前越接近真机**：

| 方式 | 能测到什么 | 怎么用 |
| --- | --- | --- |
| **Android 手机 / 盒子 + scrcpy** | 全部（含 Kotlin 的 BACK 独占） | 基座装到任意 Android 设备 → `scrcpy` 投屏；**方向键 = D-pad、右键 = 返回、中键 = 主页**；也可以 `adb shell input keyevent <code>` 精确发码 |
| **Android 模拟器（TV 镜像）** | 全部 | AVD 用 Android TV 镜像并打开 `hw.keyboard=yes`；⚠️ `manifest.json` 的 `abiFilters` 只有 arm，**临时加 `"x86_64"` 并重做自定义基座**，测完删掉（否则 x86 模拟器装不上） |
| **Web 端键盘模拟** | 分发层 / 焦点模型 / 层级栈 / 返回键语义；**测不到** Kotlin 侧 BACK 独占、软键盘、原生 video | `uni_modules/fnos-auth/utssdk/web/index.uts` 里的 `startTvKeyHook` 已接管 `keydown`：↑↓←→ / Enter=OK / Esc 或 Backspace=返回 / M=菜单（输入框里只放行 Esc） |

`adb shell input keyevent` 的码值（与 §5 第 7 条一致）：
`19 上 / 20 下 / 21 左 / 22 右 / 23 OK / 66 回车 / 4 返回 / 82 菜单`。
批量连按（验长按、去抖）：`for i in $(seq 20); do adb shell input keyevent 20; done`
或 `adb shell input keyevent --longpress 20`（API 23+）。

> 注意：web 端能测「焦点是否跟着动、层级是否正确、返回是否越级」，
> 但 `console.log` 的按键日志要先把 `common/keys.uts` 的 `KEY_DEBUG` 改成 `true` 才看得到（默认关，见 5.24.2）。

### 5.12 遥控器按键通道（FnOsTvKeys）：为什么「进图库后按键全失效」（2026-11，待真机复验）

**真机上报的现象**：登录页遥控器完全正常（上下选行、OK、返回都能用），
登录成功 `reLaunch` 进图库后，**方向键 / OK / 返回全部没有任何反应**，只能用鼠标或重启。
遥控器是普通的蓝牙/红外 D-pad（上/下/左/右/OK + 返回/主页/菜单），按键码仍是标准 Android KeyEvent。

**机制（改这块前必须知道）**：uvue 在 Android 上没有原生焦点树，D-pad 不会自动在元素间移动，
所以唯一通道是 `FnOsTvKeys` —— 在 Activity 的 `window.callback` 上包一层 `Window.Callback by base`，
只重写 `dispatchKeyEvent`（只上报 `ACTION_DOWN`），其余**原样转发**给 base，
再由 `startTvKeyHook(cb)` 把 keyCode 交给页面自己的 `handleKey`。
也就是说：**这层包装器一旦被摘掉，整页就彻底聋了**，页面 JS 里怎么写都没用。

**根因（当时的实现）**：`stop()` 做的是「把 `window.callback` 还原成原始对象」，
而 `uni.reLaunch` 时**新旧页面的 `onLoad` / `onUnload` 顺序不保证**。于是出现：

| 顺序 | 结果 |
| --- | --- |
| 图库 `onLoad` 装包装器 → 登录页 `onUnload` 调 `stop()` | `stop()` 把**别人的**包装器还原掉 → 图库全聋（真机就是这种） |
| 登录页 `onUnload` → 图库 `onLoad` | 正常，所以这个问题看起来「时好时坏」 |

登录页之所以一直正常：它是首个页面，没有「上一个页面的 onUnload」来抢着摘钩子。

**现在的实现（照这个来，不要再改回去）**：

| 项 | 现状 | 为什么 |
| --- | --- | --- |
| 包装器生命周期 | **装上去就不再摘**，直到进程结束；`stop()` 只清订阅回调 | 谁也别想「还原」掉别人的通道；方向键 / OK / 输入法等一律 `return base.dispatchKeyEvent(event)`，对系统与输入框零影响。注意它**确实会持 Activity**（`by base` → `AppCompatWindowCallback.mWrapped` → Activity），所以静态字段必须是 `WeakReference`，见 5.2 的 LeakCanary 行 |
| 订阅归属 | `startTvKeyHook(cb)` 返回订阅 id（`Date.now()`），`stopTvKeyHook(id)` 只在自己是当前 id 时才生效，**过期 id 直接忽略** | `reLaunch` 两种时序下都安全；id 不用模块级可变全局变量（UTS 侧不存状态） |
| 重复 start | 已挂着本包装器时只换回调，不重复包装 | 图库页 `onLoad` + `onShow` 各订阅一次，避免嵌套层层包装 |
| 被换掉时自愈 | `start()` 内检测 `window.callback !== 自己的包装器` 就重新包装 | 兼容 Activity 重建 / 运行时替换 callback |
| 页面侧 | 每个页面自己存 `keyHook`（`let keyHook = 0`），`onLoad` 里 `keyHook = startTvKeyHook(...)`，**所有退订都传 `keyHook`** | 不要把 id 写成全局共享，否则又是「谁都能关」 |

**页面调用点（搜这几个关键字就能定位全部）**：

| 文件 | 位置 | 行为 |
| --- | --- | --- |
| `pages/index/index.uvue` | `onLoad` 订阅；`doLogin` / `confirm2fa` 成功后、`onUnload` 退订 | 进图库前主动退订，让图库拿到最新 id |
| `pages/library/library.uvue` | `armKeyHook()`：`onLoad` + `onShow` 各订阅一次；`onUnload`、`redirectToLogin()`、`onOpenSettings()`、返回键分支退订 | `onShow` 那次是兜底，防「页面显示后通道被替换」 |
| `components/PhotoViewer.uvue` | `onMounted` 订阅、`onUnmounted` 退订；`closeSelf()` 只 emit `close`，由页面在回调里 `armKeyHook()` 收回 | 查看器打开期间**独占**通道（覆盖页面的订阅），页面 `handleKey` 不再处理查看器按键 |
| `pages/album/album.uvue` / `pages/album/detail.uvue` | 同图库：`onLoad` + `onShow` 订阅；`onUnload`、进内页（`navigateTo` 前）、`onViewerClose` 退订/重订 | 进内页用 `navigateTo`，返回时靠 `onShow` 重新订阅 |

**真机排查手册**：Hook 里保留了一行按键日志（`Log.d`，TAG = `FnOsTvKeys`），
HBuilderX 控制台 / `adb logcat -s FnOsTvKeys` 都能看到：

| 控制台里看到什么 | 结论 |
| --- | --- |
| `key hook installed for id=...` + `key=<id>/<code> hasCallback=true` | 通道正常，问题在页面 `handleKey` 的分支（或被某个原生控件抢了焦点） |
| `key=... hasCallback=false` | 通道在、但没人订阅：`stopTvKeyHook` 被提前调了，或页面 id 存错 |
| 完全没有任何 `key=` 行 | 事件在视图树（如 `scroll-view` / 输入框）里被消费，没走到 Window：要查焦点 |
| `install failed: no uni activity / no window / no window callback` | 装钩子的时机太早，拿不到 Activity，找 `onShow` / 延迟重试 |

**不要动 / 坑**：

- 不要恢复「`stop()` 里还原 `window.callback`」的写法 —— 这就是本次全失效的根因；
- 不要把 `stopTvKeyHook()` 改回无参（会重新变成「任何页面都能关掉整条通道」）；
- 不要在两个页面之间共享同一个 `keyHook` 变量，也不要把 id 存进 storage（跨页面复用 = 又能误关）；
- `object FnOsTvKeys` 里的静态字段（包装器 / Activity / 订阅回调）**一律不许写成强引用**：
  包装器持有 `window.callback` → 旧 Activity，实测会把整页（settings / library）钉住不回收；
- 不要用 `Application.ActivityLifecycleCallbacks` 之类的接口做清理：插件 dex 没有 interface default
  方法的 desugar，真机直接 `NoClassDefFoundError: ...$-CC`（见 5.2）。要去掉「上一任 Activity」的
  引用就用 `Activity.isDestroyed` 判断（`dropDeadActivity`）；
- 包装器里**只能上报 `ACTION_DOWN`**；除返回键外必须 `return base.dispatchKeyEvent(event)`，
  否则输入法、系统音量会被一起吃掉。**返回键是唯一例外**：有订阅时 `return true` 由 App 独占
  （否则一次返回被处理两遍，见 **5.23.1**）；
- 按键码固定：19 上 / 20 下 / 21 左 / 22 右 / 23 OK / 66 回车 / 4 返回（见 §5 第 7 条）；
- 改 Kotlin **必须重新运行到 Android（重建 UTS 插件 / 自定义基座）**，热刷新 `.uvue` 不会更新这里。

**验证状态**：改完后**只做了代码层核对，尚未真机复验**。真机跑一次后请把上面的日志行贴回本节，
按排查表对号入座即可定位。

### 5.13 相册（列表 / 内页）与查看器组件化（2026-11，浏览器已验，真机遥控器待查）

本轮做完 T4.2「相册功能」，同时把上一轮临时写的简化查看器**收敛成一个组件**，图库与相册内页共用。

#### 5.13.1 需求（用户原话摘要）

1. 相册列表照 Web 版 `/p/album` 的排版：封面卡 + 名称 + 张数；
2. 相册内页照 `/p/album/48`：顶部相册信息 + 按年/月 + 日期分组的网格；
   **顶部功能只保留「拍摄时间」排序**（搜索 / 添加照片 / 共享 / 更多都不做）；
3. 宝宝相册默认放第一个（**不要**给它单独一行 —— 试过，用户反馈「太丑了」）；
4. 实况照片要有标识；
5. 图库与相册内页的图片 / 视频 / 实况必须是**同一套实现**（不要各写一份）。

#### 5.13.2 相册列表 `pages/album/album.uvue`

| 项 | 值 |
| --- | --- |
| 入口 | 图库导航栏「相册」标签 → `onNavClick` → `uni.redirectTo('/pages/album/album')`（互切用 redirectTo，栈里只留一个内容页） |
| 数据 | `album/list` 一次拉全量（`limit=1000`，本机 54 个），不分页 |
| 列数 | 6 列（`windowWidth ≥ 900`）→ 5 列(≥640) → 4 列(≥480) → 3 列；`738/cols − 6` 为单格 rpx |
| 卡片 | 正方形封面（`posterUrl`，s 级）+ 名称（单行裁切）+ 「N项」（`photoCount+videoCount`）+ 「已共享」（`shared`） |
| 宝宝相册 | `category == 1`：封面左下角角标「宝宝相册」**且强制排在数组最前**（`babies + normals` 拼接），排布与普通卡片完全一致 |
| 焦点 | 遥控器左右行内 ±1、上下 ±cols；焦点只换 `border-color`（2rpx 边框常驻），不改宽度，避免卡片位移 |
| 进入 | 点卡片 / 按 OK → `navigateTo('/pages/album/detail?id=' + albumId)`，列表页保留滚动与焦点 |

#### 5.13.3 相册内页 `pages/album/detail.uvue`

| 项 | 值 |
| --- | --- |
| 头部 | `album/item` → 封面 60rpx + 名称 + 「235 张照片 · 49 个视频」+「2025年12月」；**放在 scroll-view 里随内容滚走**（不要吸顶） |
| 照片 | `album/photos`（**不是** `album/normal/getList`，见 5.13.5），`PAGE_SIZE=60`，滚到底 `loadPage()` |
| 分组 | 本地按 `dateTime` 分组：月份变 → 「2025年12月」，日期变 → 「2025年12月14日 周日」；不用 `album/normal/timeline` |
| 排序 | 顶部唯一功能「拍摄时间 ↓/↑」，点一下切 `sort_direction=desc/asc`，清空列表 + `offset=0` 重拉第一页 |
| 角标 | 视频「▶ 视频 00:32」（`videoTagText`）、实况「◎ 实况」（`showLiveTag`），都来自 `common/photo.uts` |
| 遥控器（网格） | 上下滚一行、OK 打开**视口顶部那张**（`cellTops` + `indexAtScroll`）、返回回相册列表 |
| 尺寸常量 | `HEAD_RPX=84`（头部 76 + 外边距 8）、`MONTH_ROW_RPX=30`、`DAY_ROW_RPX=24`、`CELL_MARGIN_RPX=6`；改 CSS 必须同步这几个，否则 `cellTops` 算错、OK 打开的就不是视口首张 |

#### 5.13.4 查看器组件 `components/PhotoViewer.uvue`

**契约**（导航留在页面，组件只管「当前这张」）：

```text
props:  photos: Array<PhotoItem>   // 页面自己的列表（图库是分页窗口）
        index: number
        canPrev / canNext: boolean // 只影响两侧热区箭头
emits:  nav(delta)                 // 上一张 / 下一张
        close()                    // 页面负责：收起覆盖层 + armKeyHook()
```

| 点 | 说明 |
| --- | --- |
| 为什么导航不放组件 | 图库左右翻页要**跨页补数据**（`photos` 只是窗口，翻到边缘得 `loadPrev`/`loadMore`），这套逻辑只有页面知道。图库：`nav → requestStep → ensureLoaded → 补页 → viewerIndex`；相册：数组内 ±1 后 `viewerIndex` 变化 |
| 重载触发 | 组件 `watch(() => props.index)` → `loadCurrent()`（图片 medium→原图、视频 `playVideo`、实况 `playLive`、详情 `scheduleDetail`） |
| 按键 | 组件 `onMounted` 调 `startTvKeyHook` **独占**通道，页面 `handleKey` 不再有查看器分支；`closeSelf()` emit `close`，页面 `armKeyHook()` 收回（AGENTS 5.12 的单订阅者模型） |
| 卸载 | `onUnmounted` 里 `alive=false` + 停实况/视频 + `stopTvKeyHook(自己的id)`；页面此时 id 已换，退订被忽略 |
| 视频 | `<video id="pw-video">`（`uni.createVideoContext('pw-video')` 与它对应）；进入不自动播，OK 才挂载 |
| 图库侧的删除 | 上一轮内联在 `library.uvue` 的查看器（模板 180 行 + 脚本约 870 行 + 样式 354 行）已整体删除，页面只剩网格/时间轴/分页 + 7 行组件标签 |

`common/photo.uts` 是两页与组件共用的唯一类型来源：`PhotoItem` + `durationClock` / `videoTagText` / `showLiveTag`。
**新增照片展示功能先改这里和组件**，不要在页面里再复制一份。

#### 5.13.5 踩坑（全部实际发生过）

| 坑 | 现象 | 做法 |
| --- | --- | --- |
| **用错接口** | 相册内页没有实况角标；缩略图只能拿 `id + photoUUID` 自己拼 | `album/normal/getList` 是「按天视图」接口：**列表项没有 `additional.thumbnail`、也没有 `isLive`**。改用 `album/photos`（网页版相册内页用的就是它），字段与 `gallery/getList` 同构，`pageJson`/`photoFromWeb` 直接复用 |
| 怎么确认该用哪个接口 | 用户只给了 4 个 URL，不知道相册页实际请求哪个 | 拉网页版：`GET /nas/p/album/48` 拿 HTML → 取 `/(p/assets/*.js)/` 全部下载 → grep `normal/getList` / `isLive`，在 `NmXIkJgjrYO4sRpG.js` 里找到 `album/photos` 与 `sort_by=date_time` |
| `ThumbImage` 只加载一次 | 相册封面一直是灰色「·」 | 组件只在 `onMounted` 调 `thumb()`，path 后到时不会重载。页面用 `v-if="cover.length > 0"` 等数据回来再挂载（`v-else` 留占位块，布局不跳） |
| 信息区要随滚动消失 | 一开始 `.head` 在 scroll-view 外，看起来吸顶了 | 把 `.head` 移进 scroll-view 首个子节点；**同时**改 `viewportPx`（172→88rpx）与 `rebuildTops()` 的起始 `y = headPx`，否则行高累计整体错一行 |
| 6 列折行 | 加了 `padding-left/right` 后每行只排 5 张 | 可用宽度必须正好 **738rpx**（750 − 12）；`.grid` 不能有左右 padding；单格 `738/cols − 6` |
| 焦点描边撑开布局 | 卡片位置随焦点跳动 | 焦点 / 非焦点都保留 2rpx 边框，只改 `border-color`，并显式 `box-sizing: border-box` |
| UTS 局部函数顺序 | 编译报「找不到名称 monthText」 | `rangeText`（computed）调用 `monthText` 时，`monthText` 必须写在前面；同一坑见 5.2 |
| 宝宝相册单独一行 | 用户反馈「太丑了」 | 只做**置顶**，不独占行；两个页面都不需要两段 layout / `rowOfIndex` / `rowRange` |
| 精简弹窗式查看器 | 相册内页没有视频进度、倍速、实况播放、详情面板 | 直接抽 `PhotoViewer` 组件复用，不再各写一份 |

#### 5.13.6 本轮验证手段（下次改接口照抄）

1. **接口验证不用登录**：dev server 在跑时，直接
   `Invoke-WebRequest http://localhost:5173/nas/p/api/v1/album/photos?album_id=48&sort_by=date_time&sort_direction=desc&offset=0&limit=2`
   —— 代理会补 `AccessToken` 与 `authx`，返回真数据；图片同理（`/nas/p/api/v1/stream/...`）。
   端口以实际 dev server 为准（本机是 5173，可用 `Get-NetTCPConnection -State Listen` 查）；
   这条比 `tools/fnos-probe.mjs` 省事——**不需要账号密码**，适合快速确认字段。
   PowerShell 里**直接打印 `$r.Content`** 即可（已是 string）；别再套
   `[System.Text.Encoding]::UTF8.GetString($r.Content)`（参数类型是 string，会报
   「无法将值…转换为类型 System.Byte」并显示乱码），这是本轮浪费时间的坑之一。
2. **编译校验**：请求 `http://localhost:5173/<path>.uvue?import`（模板）与
   `?import&vue&type=script&setup=true&lang.uts`（脚本），200 即通过、500 会带编译错误；
   插件 web 端用 `/uni_modules/fnos-auth/utssdk/web/index.uts?import`。
3. 改 `pages.json` 要重启 dev server；改 Kotlin / `utssdk/app-android` 要重新运行基座。

#### 5.13.7 状态与遗留

- ✅ 浏览器实测通过：相册列表（含宝宝置顶/角标）、内页（封面、分组、排序、分页、实况角标）、
  查看器（图片 / 视频 / 实况 / 详情）。
- ✅ 真机按键路径已解决（2026-11-25 T5 收口）：相册列表 / 内页的遥控器滚动、返回键逐级、
  导航栏焦点都在电视上验过；按键分发统一走 `common/keys.uts`（5.22），
  不要再往页面里加 `if (code == 19)` 这类分支。
- ⬜ 未做：相册内页的「相册内搜索/筛选」、创建相册、共享管理（用户明确不需要）；
  实况片段在**相册内页**的自动播放也走同一组件，若要区分入口再改 props。
- ⬜ 相册内页目前不带右侧年月时间轴（图库有）；用户未要求，需要时再抽。

### 5.14 首页大屏（`pages/home/home.uvue`，2026-11）

登录后的落地页。全屏场景海报 + 右上角实时时钟 + 底部横向卡片托盘（每 10 秒自动切下一个），
数据源是 `ai-smart-rec/categories?mainCategory=scene`（54 个场景，见 §3），**不按原 T4.1 用 timeline/getList**。
卡片点击暂不打开详情（用户明确这一轮不做），只做「选中即切换 + 重置计时」。

| 要点 | 说明 |
| --- | --- |
| 尺寸基线 | 全工程 `750rpx = 屏宽`，但 **16:9 电视的可视高度只有约 422rpx**（750×9/16）。首次按竖屏直觉写（卡片 150×200rpx、hero `bottom:300rpx`）直接占掉半屏、标题被顶到顶栏；现为**正方形卡片 76×76rpx**（海报 `aspectFill` 居中裁切）、托盘总高约 100rpx、hero `bottom:140rpx`。改卡片尺寸**必须同步**脚本里的 `CARD_RPX`（= 卡片宽 = 高）/ `GAP_RPX` / `TRAY_PAD_RPX` / `TRAY_MARGIN_RPX`，以及 `.card` / `.tray-scroll` / `.tray-inner` 三处尺寸 |
| 横向滚动必须给内容显式宽度 | `scroll-view` 里放 `flex-direction: row` 的容器时，容器会被拉伸成 100% 宽，卡片只是「溢出」而不是撑开内容 → `scrollWidth == 视口宽`，`scroll-left` 永远被钳到 0（表现：自动切换了但卡片条不动、选中项滚出屏幕）。用 `:style="innerStyle"` 按卡片数算出 `width: N rpx` |
| 居中偏移 | `TRAY_MARGIN_RPX`（12rpx，`.tray` 的左右外边距）与 `TRAY_PAD_RPX`（8rpx，`.tray-inner` 内留白）都要从视口里扣掉，否则选中卡片偏右约 40px |
| 自动轮播 | `setInterval` 每 `intervalSec` 秒 `select(index+1)`；`select` 内部取模回绕 = 循环。**`select` 不负责重置计时**，由调用点（点击卡片 / 遥控器左右）自己 `startAuto()` —— 因为 UTS 编译成 Kotlin 局部函数、必须先定义后调用，`select` 与 `startAuto` 互相调用会编译失败 |
| 停留才算 | `onShow` 启动时钟 + 轮播、`onHide` / `onUnload` 停止；页面首次加载完成前 `startAuto()` 会因 `items.length < 2` 直接返回 |
| 间隔可配置 | 读 `uni.getStorageSync('fnos.home.interval')`（秒，字符串），缺省 10；设置页以后写这个键即可生效。读取端按 `parseInt` + 反向判断兜住 `NaN`（见 5.2 的 storage 铁律）。**T4.5 起只允许 5 / 10 / 30 三档**（其它值回落 10），且 `onShow` 要重新读一次，否则从设置页返回仍是旧间隔 |
| 背景图 | 场景海报的 m 尺寸（≈1280 宽 / 147KB），App 端必须走 `image(path)` 下载成本地文件（`<image>` 加不了 `AccessToken` 头），web 端返回 `/nas` 同源地址。只加载当前 + 预取下一张，`bgToken` 让连按左右时只有最后一张能落到 `bgSrc`，避免闪回 |
| 时间 | `new Date()` + `getHours/getMinutes` 手动拼「下午 1:16」（12 小时制，与示例图一致），每秒刷新；不引入 dayjs |
| 背景不压暗，文字自带底衬 | 用户明确要求「背景必须是原图亮度」，所以**没有整屏 `.bg-dim`、也没有底部暗带**（两者都试过，前者让照片发灰、后者在画面上留一条矩形分界线，都被要求去掉）。可读性靠局部：`.brand`（品牌 + 入口）与 `.hero`（场景名 / 数量）各自是一块 `rgba(0,0,0,.42~.45)` 圆角底，右上时间本来就有胶囊底。`text-shadow` 不被 uni-app x 支持（`@dcloudio` 里搜不到），不要用它兜底 |
| 导航闭环 | 首页 → 图库/相册用 `redirectTo`（栈里只留一个内容页）；图库 / 相册列表的**返回键改为回首页**（首页再返回才回连接页并置 `skipAuto`），导航栏「设置」进设置页（`navigateTo`，返回键回原页）。改这两处返回键时别把 `skipAuto` 又加回去 |
| 导航栏统一 | 首页 / 图库 / 相册三页的导航栏是**同一个组件** `components/NavBar.uvue`（悬浮顶部、不占布局高度，页面里用 `.nav-spacer` 补位），不要再往页面里写第二份。详见 5.4 |
| 自动轮播时托盘缩小 | **待做**：用户要求「进入自动轮播后底部图片列表缩小 50%」，把焦点让给 hero 大图。尺寸仍由 `CARD_RPX` / `GAP_RPX` / `TRAY_PAD_RPX` / `TRAY_MARGIN_RPX` 与 `.card` / `.tray-scroll` / `.tray-inner` 决定，改尺寸必须两边同步并重算 `innerStyle` / 居中偏移 / `scroll-left`。需求口径、待确认点与验收见 §7 **T4.11** |

### 5.15 设置页（`pages/settings/settings.uvue`，2026-11）

导航栏「设置」三页都 `navigateTo` 进来（不再回连接页；退出登录收进本页）。
顶部沿用 `components/NavBar.uvue`，`active = -1`（不属于任何分区、不高亮），右侧仍是搜索 + 设置 + 时间，
页面里同样用 `.nav-spacer` 补 60 + 8rpx。

| 要点 | 说明 |
| --- | --- |
| 左右 3:7 | `.main` 是 `flex-direction: row`；`.side` 固定 **216rpx**（738 × 0.3，再减 8rpx 间距），`.panel` 用 `flex: 1` 吃掉剩下的约 514rpx。改比例只动这两个数 |
| 左选项 | 4 项：登录状态 / 首页展示设置 / 视频清晰度 / 主题设置。每项高 40rpx、圆角 8rpx、间距 6rpx，选中蓝底白字加粗（与导航栏同色 `#3388FF`） |
| 登录状态 | 信息行：当前状态、服务器 IP、端口、账号、密码（**固定 8 个 `*`**，不透露长度）、HTTPS。连接信息来自 `getSavedConfig()`（`saved=false` 时 IP / 端口都显示 `—`）；**「已登录 / 未登录」由 `userInfo()`（`GET /p/api/v1/user/info`）判定**，不能用 `getToken()` 非空来判断（跨夜 / 改密码后 token 可能是失效串），成功时用接口返回的 `name` 覆盖账号显示。`onShow` 每次进来都重新校验，期间显示「检测中…」 |
| 退出登录 | 红框按钮，**在右栏整行居中**（外层 `.danger-wrap` 用 `flex-direction: row` + `justify-content: center`，比 `align-self` 稳）。**二次确认**：第一次点击/OK 变成「再按一次确认退出」，2.5 秒内再按才执行，超时自动复位。执行后 `logout()` + `setStorageSync('fnos.skipAuto', true)` + `reLaunch('/pages/index/index')`，避免连接页拿保存的密码自动重登 |
| 遥控器 | 上/下切选项（焦点在右栏时只在栏内移动，`focusRow`）、右进右栏、左/返回出右栏、OK = 当前焦点行的动作（退出登录 / 选清晰度档位 / 开关 HEVC 兼容）、返回键最后 `navigateBack`。可进右栏的选项：登录状态、视频清晰度 |
| 其余两项 | 右栏显示「暂未开放」+ 一行占位说明（首页展示设置 / 主题）。**不要放假控件**，功能没做就只显示文案。落地计划见 §7 **T4.5 / T4.7**；视频清晰度（T4.6）已落地，见 **5.18**；后续还要再加「AI 照片评价」开关（T7） |
| web 调试注意 | web 端 `userInfo()` 走 `/nas` 代理，只要 `tools/get-token.mjs` / `.env.local` 里的 token 有效就会显示「已登录」（账号取自接口 `name`）；IP / 端口 / 密码读 localStorage `fnos.web.cfg`（登录页写过才有值，否则显示 `—`）。App 端按 Kotlin 侧的配置与 token 判断 |

### 5.16 本轮（首页大屏 / 导航栏组件 / 设置页）汇总（2026-11）

三块功能都只在**浏览器**验过，真机未验。细节分散在 5.4 / 5.14 / 5.15，这里做「一页看完」的索引 + 踩坑表。

#### 5.16.1 新增 / 改动清单

| 位置 | 内容 |
| --- | --- |
| `pages/home/home.uvue` | 首页大屏：场景海报（**不压暗**）+ 局部底衬文字 + 正方形卡片托盘 + 10 秒轮播 + 图库/相册入口 |
| `components/NavBar.uvue` | 全局导航栏组件（首页 / 图库 / 相册 / 设置 / 搜索共用）：悬浮顶部、分区 emit、`settingsActive` / `searchActive` 高亮、内置时钟 |
| `pages/settings/settings.uvue` | 设置页：左右 3:7，登录状态（`user/info` 判活）+ 退出登录二次确认（首页展示设置 / 主题仍是占位；视频清晰度见 5.18） |
| `common/scene.uts` | `SceneItem` 类型（首页不直接引用 `uni_modules` 内部类型） |
| 插件 4 处 | 新增 `smartCategories()`（场景分类）与 `userInfo()`（token 判活）：`interface.uts` / `FnOsLogin.kt` / `app-android/index.uts` / `web/index.uts` |
| `pages.json` | 注册 `pages/home/home`、`pages/settings/settings` |
| 图库 / 相册 / 首页 | 删掉各自那份 navbar 模板与约 150 行样式，改用 NavBar + `.nav-spacer`；「设置」改 `navigateTo` 进设置页；返回键回首页 |

#### 5.16.2 踩坑汇总（现象 → 原因 → 解决）

| 现象 | 原因 | 解决 |
| --- | --- | --- |
| 首页卡片占掉半屏、标题顶到导航栏 | 按竖屏直觉写了 150×200rpx，但 16:9 电视可视高度只有约 422rpx | 卡片改**正方形 76rpx**、托盘压到约 100rpx、hero `bottom:140rpx`（5.14） |
| 自动切换了但卡片条不动、选中项滚出屏幕 | `scroll-view` 内的 flex row 容器被拉成 100% 宽，`scrollWidth == 视口宽`，`scroll-left` 被钳到 0 | 用 computed 给容器算显式 `width: N rpx`（5.14） |
| 选中卡片不居中、偏右约 40px | 计算视口时漏了 `.tray` 的左右外边距 | `TRAY_MARGIN_RPX` + `TRAY_PAD_RPX` 一起从视口扣掉（5.14） |
| UTS 编译报 `select` / `startAuto` 找不到或循环引用 | 两个局部函数互相调用（Kotlin 局部函数必须先定义后调用） | `select` 不再管计时，由调用点 `startAuto()`（5.14） |
| 照片发灰 / 底部有一条矩形灰块 | 整屏 `.bg-dim` 压暗 + 200rpx 底部暗带（两次都被用户否掉） | 背景**不压暗**，只留 `.brand` / `.hero` 局部胶囊底衬；`text-shadow` 不支持，不要用它兜底（5.14） |
| 进图库 / 相册后导航栏点不动 | 紧跟其后的 `.nav-spacer` 在文档流里盖住了绝对定位的导航栏 | `.navbar` 加 `z-index: 10`（5.4） |
| 首页没有搜索 / 设置入口 | 三页 props 传得不一致（`showSearch` / `showSettings` 只开了一部分） | 三页统一 `true`（5.4） |
| 进设置页后「设置」按钮没高亮 | 组件只有「是否显示」没有「是否选中」 | 新增 `settingsActive` prop，为 true 时 `.act-on` 蓝底白字（5.4） |
| 「退出登录」按钮偏左 | `.panel` 是 column，固定宽按钮靠左；`align-self` 在 uvue 上不稳 | 外层 `.danger-wrap` 用 `flex-direction: row` + `justify-content: center`（5.15） |
| 设置页「当前状态」显示错误 | 用 `getToken()` 非空判断：web 端 token 不在浏览器、跨夜 token 可能是失效串 | 新增 `userInfo()` 打 `/p/api/v1/user/info`，`ok=false` 即未登录；账号用接口 `name`（5.15 + §3） |
| NavBar 抽组件后页面编译报缺 prop | `defineProps` 里全是必填字段 | 五个页面都显式传 7 个 props（`active` / `focusIndex` / `showSearch` / `showSettings` / `showClock` / `settingsActive` / `searchActive`） |

#### 5.16.3 本轮验证手段（浏览器，可复用）

1. **编译校验**：`http://localhost:5173/<path>.uvue?import`（模板）与
   `?import&vue&type=script&setup=true&lang.uts`（脚本），200 即通过、500 会带错误。
2. **接口探测**：`http://localhost:5173/nas/p/api/v1/...`（dev 代理自动补 `AccessToken` 与 `authx`），
   本轮用它确认了 `ai-smart-rec/categories`、`user/info` 的字段。
3. **无头截图**：Edge `--headless=new --window-size=1280,720 --virtual-time-budget=<ms> --screenshot=...`；
   `virtual-time-budget` 会快进 `setInterval`，所以能用 25000 / 105000 验证「10 秒自动轮播、卡片条跟随滚动」。
4. **真实点击 + 命中测试**（「导航栏点不动」就是靠它定位的）：CDP `Runtime.evaluate` 取
   `getBoundingClientRect()`，先用 `document.elementFromPoint(cx, cy)` 看命中的是谁，
   再用 `Input.dispatchMouseEvent` 发 mousePressed / mouseReleased。
   **不要只用 `element.click()`** —— DOM click 绕过命中测试，会漏掉「被上层元素挡住」这一类问题。
5. `pages.json` 新增页面在本机 dev server 上**没重启就生效了**（HBuilderX 会自行重载路由）；
   若页面打不开再按 5.1 第 7 条重启。

### 5.17 搜索页（T4.4，2026-11，已按用户反馈迭代 3 轮；接口返回结构仍待确认）

**用户需求（原话摘要）**：点导航栏「搜索」后按钮变成一条搜索框（截图里左「AI 星标」、右「AI 搜索胶囊 + 筛选」
明确不要）；`×` 有内容先删内容、没内容才关闭；**返回键无视有没有内容直接关闭**；搜索后结果是一排 8 个的
正方形缩略图，缩略图要能识别照片 / 视频（带时长）/ 实况，点开分别用图片 / 视频 / 实况组件。
后续追加：① 顶部导航栏仍要显示、且「搜索」是选中态；② 几千条结果要分批渲染，不能一次全挂；③ 搜索页 UI 要和首页一致（不要白底）。

入口：首页 / 图库 / 相册 / 设置四页导航栏的「搜索」按钮 → `navigateTo('/pages/search/search')`
（各页 `onOpenSearch` 已不再是空实现，跳转前先 `stopTvKeyHook`，与「设置」同一套）。

| 要点 | 说明 |
| --- | --- |
| 视觉 | **导航栏照常显示**（`NavBar`，`active=-1` 不高亮任何分区，`search-active=true` 让「搜索」按钮蓝底白字 + 图标变白），搜索框是导航栏下方一条**深色半透明胶囊**（`rgba(255,255,255,.08)` 底 + `.14` 描边，高 34rpx / 圆角 17rpx，`.bar-wrap` 通高 60rpx + 8rpx），配色与导航栏 `.brand` / `.act` 完全一致；**不要白底**（第一版用了 `#F5F6F8`，用户反馈「和首页这些页面 UI 不匹配」）。输入框持有焦点时**只有搜索框描边**变 `#3388FF`（与分区选中同一套强调色），框内放大镜始终灰描边不变色（选中态图标踩坑见 5.4 的「搜索图标的选中态」）。框内**只有放大镜 + 输入框 + ×**：截图里左「AI 星标」、右「AI 搜索胶囊 + 筛选图标」是用户明确不要的，别再补回来 |
| 空白态 | 刚进页面**中间不放提示**（状态行已有一句「输入关键词，按 OK / 回车搜索」，中间再放一句是重复）；只有「搜了 0 条 / 出错」才在网格区显示一行灰字 |
| 视口估算 | 搜索页比图库多一条搜索带：`viewportPx = windowHeight − 186rpx`（导航栏 60 + 8 + 搜索条 60 + 8 + 状态行 22 + 8 + 页面内边距 12 + 网格底部 8）。改任一高度必须同步 `onLoad` 里的 186 |
| 导航栏交互 | 分区：首页 `reLaunch`、图库 / 相册 `redirectTo`（与图库页同规则）；「搜索」= 重新聚焦输入框；「设置」`navigateTo`，返回后关键字与结果还在 |
| × 的行为 | 有内容 → 只清空（留在搜索框、结果清掉）；没内容 → 关闭搜索框（`navigateBack`）。返回键**无视有没有内容**直接关闭 |
| 触发搜索 | `@confirm`（软键盘的「搜索」键 / 浏览器回车）或输入框上按遥控器 OK（`handleKey` 里 KEY_OK 等价 `runSearch`）。**不做输入即搜**，避免每敲一个字就 POST 一次 |
| 结果网格 | 固定 **8 列**正方形（`GRID_WIDTH_RPX = 738`，格子 88rpx），角标与图库同一套：视频 `▶ + videoTagText()`（带时长）、实况同心圆 + 「实况」 |
| 增量渲染（**必须保留**） | 搜索结果可能几千条（实测「上海」**3053** 张）。一次性挂 3000 多个格子会卡几秒，而且每个 `ThumbImage` 一挂上就下载缩略图。所以 `results` 存全量、`visible` 只取前 `shownCount` 张（`RENDER_CHUNK = 80` = 8 列 × 10 行）：`@scrolltolower` → `appendChunk()` 追加一批；遥控器在最后一行按下键也会先 `appendChunk()` 再移动焦点；底部 `已显示 x / y 张` 的提示行就是追加触发点。**查看器仍然吃 `results` 全量**，左右翻页不受渲染批次限制 |
| 打开 | 点击 / OK → `PhotoViewer`（图片 / 视频 / 实况都在组件里，靠 `PhotoItem.cat` 与 `live` 分流），进入前 `focusVisible` 收起，关闭后 `armKeyHook()` 收回按键通道 |
| 遥控器 | 输入框持有焦点时：DOWN 进网格、OK/ENTER 搜索；网格内 LEFT/RIGHT/UP/DOWN 移动（第一行再 UP 回输入框，`:focus` 重新置 true 就会重新聚焦）、OK 打开、BACK 关闭 |
| 数据 | `POST /p/api/v1/magic-search/do`，见 §3 的 `magic-search/do` 行。解析做了容错：`data` 是数组或带 `list/items/photos...` 的对象都能吃，元素不像照片时按分组再钻一层（深度 2），缺 `additional.thumbnail` 时用 `id + photoUUID` 拼 s 尺寸流地址 |
| web 调试 | GET 代理的 `authx` 只签 query，搜索是 POST 且**签请求体**，所以 `vite.config.js` 给 `/nas` 加了 POST 专用转发中间件（`forwardPost`：读 body → 签名 → fetch → 401 静默续期 → 5000 换 ENCODED 再试）。**改完必须重启 dev server** |
| 验证 | 浏览器：登录后点搜索 → 输关键词 → 回车；真机：`adb logcat -s FnOsTvKeys` 看按键是否进来。接口结构确认用 `tools/fnos-search.mjs`（见 §7 T4.4） |

#### 5.17.1 改动清单（本轮搜索功能）

| 文件 | 改了什么 |
| --- | --- |
| `pages/search/search.uvue`（**新增**） | 整页：`NavBar`（`search-active=true`）+ 深色搜索框（放大镜 + 输入 + ×）+ 状态行 + 8 列增量渲染网格 + 类型角标 + 复用 `PhotoViewer`；自带按键通道（`onLoad` / `onShow` 订阅、`onUnload` 退订、查看器关闭后 `armKeyHook()` 收回） |
| `components/NavBar.uvue` | 新增**必填** prop `searchActive`：为 true 时「搜索」按钮蓝底 + 白字 + **白色空心**放大镜；选中态拆成 `.ico-ring-on`（只改描边）/ `.ico-handle-on`（只改填充）两个类 |
| `pages/home` / `library` / `album` / `settings` | ① `onOpenSearch` 从空实现改为 `stopTvKeyHook` + `navigateTo('/pages/search/search')`；② 四页都补传 `:search-active="false"`（props 必填，漏传编译报警）；③ **home / settings 补 `armKeyHook()` + `onShow` 重新订阅**（原来只有图库 / 相册 / 相册内页这么做） |
| `utssdk/interface.uts` | 新增 `FnOsSearchResult { ok, items, total, error }`（列表项仍复用 `FnOsPhoto`） |
| `utssdk/app-android/FnOsLogin.kt` | 新增 `search(keyword)`（`apiPost` + 401/5001 重登一次）+ `searchJsonFrom` / `searchPhotoArray` / `collectSearchPhotos` / `searchItem` 容错解析；`ApiReply` 增加 `root`（完整响应体，因为搜索的 `data` 可能是数组） |
| `utssdk/app-android/index.uts` | 新增 `search(keyword): Promise<FnOsSearchResult>`，字段解析与 `page()` 逐字段同构 |
| `utssdk/web/index.uts` | 新增 `search()`（`uni.request` POST）+ `collectSearchItemsWeb` / `searchPhotoFromWeb`（web 侧同容忍错，缺缩略图时用 `id + photoUUID` 拼） |
| `vite.config.js` | `/nas` 增加 **POST 专用**转发中间件 `forwardPost`（读 body → `authx(path, body)` → `fetch` → 401 静默续期 → `code=5000` 换 ENCODED 重试）；GET 仍走原 proxy |
| `pages.json` | 注册 `pages/search/search`（custom navigationStyle + hideStatusBar，横屏随全局） |
| `tools/fnos-search.mjs`（**新增**） | 登录 → POST → 打印返回各层数组/对象的键结构，用来确认字段；不打印 token |
| `AGENTS.md` | §3 接口事实、§4 代码地图、5.4 图标踩坑、5.17 本节、§6 当前状态、§7 T4.4 |

#### 5.17.2 问题 → 现象 → 解决办法（本轮真发生过的，按发生顺序）

| 问题 | 现象 | 解决办法 |
| --- | --- | --- |
| 搜索结果一次渲染几千条 | 搜「上海」返回 **3053** 条，页面卡好几秒才出第一屏，而且所有缩略图同时开始下载 | `results` 存全量、`visible` 只取前 `shownCount`（`RENDER_CHUNK = 80` = 8 列 × 10 行）：`@scrolltolower` → `appendChunk()`；遥控器在最后一行按下键也先 `appendChunk()` 再移焦点；未挂载的格子不会下载缩略图 |
| 搜索页 UI 和全站不匹配 | 第一版是白底 `#F5F6F8`、44rpx 高的整条搜索框，全页唯一白色大平面；用户：「搜索这个页面太丑了，和首页这些页面 ui 不匹配」 | 改深色半透明胶囊：底 `rgba(255,255,255,.08)` + 描边 `rgba(255,255,255,.14)`、高 34rpx / 圆角 17rpx，配色对齐导航栏 `.brand` / `.act`；焦点只让**描边**变 `#3388FF` |
| 选中态搜索图标变成「圆球」 | 导航栏选中胶囊里的放大镜是白球、搜索框里是蓝球；用户：「这两个选中后的搜索图标不对，重新选一个」 | 根因：一个状态类同时写了 `border-color` 与 `background-color`，**圆环吃到了背景色**就成实心块。拆成 `.ico-ring-on`（只描边）/ `.ico-handle-on`（只填充）；搜索页放大镜干脆不跟随焦点变色（见 5.4 的踩坑行） |
| web 端搜不出结果 | 浏览器里 401 / `code=5000`：GET 代理的 `authx` 只签 query，而 POST 的签名 payload 是**请求体原文** | `vite.config.js` 给 `/nas` 加 POST 专用转发（先读 body 再签名再 fetch），GET 保持原 proxy；改完**必须重启 dev server** |
| 从搜索页返回后首页 / 设置遥控器全失效 | 搜索页 `onUnload` 的 `stop(id)` 清掉通道后，home / settings 只在 `onLoad` 订阅过，回来就没人接了 | 两页抽 `armKeyHook()`，`onLoad` + `onShow` 各订阅一次（与图库 / 相册一致，见 5.12） |
| 进页面时提示重复 | 状态行与屏幕中间各有一句「输入关键词…」 | `emptyText` 在「还没输入」时返回空串，只在「0 条 / 出错」时显示；模板条件加 `emptyText.length > 0` |
| 分批渲染会不会影响查看器翻页 | 担心查看器只能翻到已挂载的那几张 | 查看器吃 `:photos="results"` **全量**，`openViewer` / `onViewerNav` 都用 `results` 下标；渲染批次只影响网格 |
| 「×」与返回键语义不同 | 用户要求「× 有内容先删除、没内容才关闭；返回键无视内容直接关闭」 | 拆成两个函数：`onClearOrClose()`（先清空，空了才 `closeSearch()`）与 `handleKey(KEY_BACK)`（无条件 `closeSearch()`），**不要合并** |

#### 5.17.3 接口解析策略（结构确认前不要改回「只认 data.list」）

`magic-search/do` 的真实返回还没核对过（`tools/fnos-search.mjs` 待跑），所以两端用同一套容错：

1. `data` 是数组 → 直接当结果数组；是对象 → 先在 `list/items/photos/photoList/records/files/result/data/hits` 找
   「第一个元素像照片」的数组，找不到再扫其余键；
2. 元素不像照片（可能是场景 / 分组对象）→ 往下一层按同样规则再找，**最多 2 层**（`depth`）；
3. 「像照片」的判定：有 `photoUUID`，或 `id/photoId/upId > 0` 且带 `dateTime` / `category` / `additional` / `isLive`；
4. 缩略图取 `additional.thumbnail` 的 `sUrl → xsUrl → mUrl`；都取不到就用 `id + photoUUID` 拼
   `/p/api/v1/stream/p/t/{id}/s/{uuid}`（原图拼 `m`）。**不要退到 `originalUrl`**：原图几 MB，格子才 88rpx；
5. 视频 / 实况片段仍只认 `videoUrl` 字段，**不猜流路径**；时长走 `mediaDuration`（>86400 视为毫秒）。

拿到真实结构后：把键结构补进 §3 的 `magic-search/do` 行，再回来精简 `searchItem` / `searchPhotoFromWeb`
里那些「猜的」候选键。

#### 5.17.4 验证手段

- **浏览器**：登录后任一页点「搜索」→ 输关键词 → 回车。DevTools 看 `POST /nas/p/api/v1/magic-search/do`
  的状态与返回 `code`；然后检查：首屏 80 格、滚到底自动续、点格子进查看器、视频能播、× 先清空再关闭、
  返回键直接关闭、回到原页面后遥控器 / 鼠标仍正常。
- **真机**：`adb logcat -s FnOsTvKeys` 看 `key=<id>/<code>` 是否进来（区分「Hook 没装上」与「页面没处理」，见 5.12）；
  顺带看软键盘是否自动弹出（`:focus` 初值 true）。
- **接口结构**：`FNOS_USER=xxx FNOS_PASS=yyy node tools/fnos-search.mjs 海边`（打印登录 → POST → 各层键）。
- **改动提醒**：改了 `FnOsLogin.kt` **必须重跑基座**；改了 `vite.config.js` **必须重启 dev server**；
  只改 `.uvue` / `.uts`（web 侧）热刷新即可。

#### 5.17.5 已知未做 / 待确认

- 搜索**没有分页**：一次 POST 拉全量（3053 条约等于一次大响应）。接口是否支持 `offset/limit` 未知，
  没敢加参数（乱加参数可能直接 `code=5000`）；确认真支持后再做分页 + 底部「加载更多」。
- 结果网格**没有时间轴 / 日期分组**（用户本轮不要求），按接口返回顺序平铺。
- 进页面焦点默认在输入框；上键 / 返回键会把焦点交给导航栏（`navIndex`，层 `search.nav`，
  见 5.25），导航栏里再按返回才关掉搜索 —— 这条已在 T5 收口时补上（原「无法聚焦导航栏」已过时）。
- 真机未验：软键盘自动弹出、`onShow` 重订阅后的按键路径、视频 / 实况在搜索结果里的播放。

### 5.18 视频清晰度（T4.6，2026-11，**真机已验：三档均生效**；2026-11-24 已扩到五档，见 5.35.2）

**用户口径**：三档做进**设置页**，设置后**全局视频有效**，**默认标清**（与 §7 T4.6 原稿「默认原图」不同，以用户为准）。

**最终形态一句话**：设置页选档 → 查看器**每次打开视频读一次** → 原图走直链、标清 / 低清向 NAS 申请转码 HLS →
**一律经本机回环代理**播放（`<video :header>` 真机无效，见 5.18.4 / 5.19）→ 状态行显示**实际档位 + 码率**。

| 项 | 实现 |
| --- | --- |
| 存储 | `fnos.video.quality` = `original` / `sd` / `low`（字符串）；缺省 / 非法值回落 **`sd`**（见 5.2 的 storage 铁律） |
| 新增 `common/video.uts` | 常量 + `videoQualityOptions()`（三档 label/desc）、`normalizeVideoQuality()`、`videoQualityLabel()`、`videoQualityTargetHeight()`（标清 720 / 低清 480，后者只当兜底目标）。**刻意不调 `uni.getStorageSync`**：读写放在页面 / 组件里，common 模块只放纯函数 |
| 设置页右栏 | 三行单选：蓝勾 + 蓝框 = 已选，白框 = 遥控器焦点；OK / 点击立刻写 storage。`focusRow` 记右栏焦点行（登录状态仍只有 1 行 = 退出登录，右栏 UP/DOWN 只在栏内移动、不再切左栏选项）；`RIGHT` 进右栏时焦点落在**当前档位**，避免一按 OK 误改。底部说明按 `#ifdef WEB` 区分（浏览器播不了 HLS，只存值） |
| 查看器取档 | `playVideo()` **每次打开视频读一次**档位 → 改设置后对下一张视频生效，正在播的不打断 |
| 原图档 | 沿用 `photo.video` 直链原画，**一条转码请求都不发**（T4.6 验收项） |
| 标清 | **按 OK 时才申请**（懒申请，左右浏览不给 NAS 排任务）：`videoInfo(id)` → 找**最接近 720 的分辨率** → 该分辨率下取**最小码率**（≈`720p/1000k`）→ `videoPlay(id, bitrate, resolution)` → `/p/api/v1/video/decode/stream` + `playLink` → `mediaUrl()` 代理 |
| 低清 | 同上，但取 **`quality[]` 里全局最小码率**（≈`360p/200k`）。两档都跳过 `bitrate<=0` 的「原始画质」项；若最低档和标清撞成同一档，弹 6 秒提示「NAS 只有 Np 这一档可转」 |
| 状态行 | 申请中「转码中…（标清）」；就绪且用户已按过 OK → 自动开播（`videoPendingStart`），不用再按一次；播放中显示 `播放中 · 原画` / `· 标清 720p/1000k` / `· 低清 360p/200k` |
| 失败 / 超时 | 9 秒超时、接口失败、没有可用档位都走 `fallbackOriginal()`：换回原画 + **6 秒**提示（带接口原话），**不报错、不转圈**；转码流播放中 `@error` 也先回原画，再失败才走「下载到本地再播」，OK 仍能关查看器 |
| 序号保护 | `videoTranscodeSeq`（`stopVideo()` / `fallbackOriginal()` 里 +1）丢弃迟到回调，避免「超时回退之后转码地址又盖回来」 |
| web | `playVideo()` 用 `#ifdef WEB` 直接关掉转码（`utssdk/web` 的 `videoInfo` / `videoPlay` 仍是返回错误的桩），标签如实写「原画」，非原图档额外给 3 秒提示「浏览器只播原画」 |
| 不改的东西 | 播放器面板**仍然只有倍速**（清晰度不许塞回查看器）；`FnOsLogin.kt` 的 `videoInfo` / `videoPlay` 与参数（h264 / aac / 双声道）没动（后来只在 5.19 加了 `mediaUrl` / `openAuthed`） |

- **没有做「播到一半切档」**：档位只在打开视频时读一次，所以不需要给 `decode/play` 传 `startPosition`（Kotlin 侧签名也没改）。
  查看器里没有切档入口，用户要换画质就回设置页改、再重新打开视频 —— 这正是「改完对下一张视频生效」的落地方式。
- **默认档位与 §7 原稿不同**：原稿写「默认原图」，用户 2026-11 明确要求**默认标清**，故 `VIDEO_QUALITY_DEFAULT = 'sd'`；
  老用户没存过 `fnos.video.quality` 时也按标清走（首次播放视频会看到一次「转码中…」）。

**真机已验证（2026-11-15）**：代理上线后 logcat 里 **401 / Playback error 全消失**（只剩正常 Release）；
原画 ≫ 标清（720p/1000k）> 低清（360p/200k），三档肉眼可辨；拖进度走 `Range` 206；改档不用重启基座；
「原图」档播 h264 时日志里没有 `decode/play` 请求。

**仍待补**：长时间播放大视频的稳定性、杀掉 App 后代理端口释放、
HEVC 源（T4.8）走代理的兜底 —— 已实现（见 5.21），但测试机支持 HEVC 硬解，**这条兜底链至今没被触发过**（5.21.1）。

#### 5.18.0 时间线：需求 → 踩到的坑 → 怎么修的

| # | 现象 | 根因 | 修法 |
| --- | --- | --- | --- |
| 1 | 用户「几个档位没啥区别」 | 没有任何可见证据区分「转码成功」与「悄悄回退成原画」，回退提示只有 900ms | 状态行加档位后缀；回退提示 6s 并带接口原话；新增 `tools/fnos-video.mjs` 直接问 NAS |
| 2 | 同上（其实是在**浏览器**里测的） | web 端本来就不转码（浏览器放不了 NAS 的 HLS，分片只认 `AccessToken` 头），但标签按设置写「低清」→ 界面撒谎 | `playVideo()` 先按平台算 `transcode`，**标签取 transcode 的结果**；web 非原图档给 3 秒提示 |
| 3 | 首次 Android 编译 4 类报错 | 工程此前**只在浏览器跑**，这些历史代码没经过 Kotlin 编译 | `watch` 显式类型、3 处「先用后定义」重排、模板不再调用导入函数（改预计算字段），见 5.2 |
| 4 | 真机黑屏 0:00，`Source error`（`头=有`） | **`<video :header>` 在 uni-app x 真机不生效** → ExoPlayer 请求 401；NAS 只认 `AccessToken` 头 | 插件内**本机回环代理** `FnOsMediaProxy`（见 5.19） |
| 5 | 代理后标清生效、**低清仍没区别** | 挑档按「最低分辨率下第一个命中项」= `360p/500k`；同分辨率有 500k/400k/200k，先命中的是高码率 | 低清改为**按码率取最小**（360p/200k） |
| 6 | 「标清和原画太接近」 | 标清取的是 720 的第一个命中项 = `5000k`，和 1080p 原画几乎看不出差别 | 标清改为「目标分辨率内**最小**码率」= `720p/1000k`（两遍扫描：先定分辨率、再挑最小码率） |

#### 5.18.1 用户反馈「几个档位没啥区别」→ 让播放在播的那一档看得见（2026-11）

用户真机试过三档后反馈「几个档位没啥区别」。**当时没有任何可见证据区分「转码成功」与「悄悄回退成原画」**，
所以第一件事是让状态行说实话，第二件事是提供一个能直接问 NAS 的脚本。改动：

| 改动 | 说明 |
| --- | --- |
| 状态行显示**实际在播的流** | `videoQualitySuffix()` → 「播放中 · 原画」/「播放中 · 标清 720p/1000k」/「播放中 · 低清 360p/200k」。原图档写「原画」（设置项仍叫「原图」）。**档位必须带分辨率与码率**：只写「低清」时用户无法判断到底多低，也看不出和标清是不是同一档 |
| 回退提示 900ms → **6s** | `noticeVideoHint()`（6s）与 `flashVideoHint()`（900ms）共用 `setVideoHint(text, ms)`；回退类消息一律走 6s，避免一闪而过被当成「没生效」 |
| 回退原因带接口原话 | `无法转码（<decode/info 的 error 或「NAS 只给了原始画质」>），已用原画`、`转码失败（<decode/play 的 error>），已用原画`、`转码流播不出来（<ExoPlayer 的 errMsg，见 5.18.2>），已用原画` |
| 低清改为**按码率取最小** | 第一版按「最低分辨率」挑，同一分辨率下先命中的是**最高码率**那档（360p 有 500k/400k/200k，拿到 500k），实测「低清看着没变化」。现在先比 `bitrate` 再比分辨率，取真·最低档（360p/200k）；若最低档和标清是同一档，弹 6 秒提示「NAS 只有 Np 这一档可转」 |
| 标清也降一档（用户反馈「和原画太接近」） | 标清＝「最接近 720 的分辨率」里**码率最小**的那档（720p/1000k 左右）。早期取的是该分辨率第一个命中项＝5000k，跟原画几乎看不出差别。实现是两遍扫描：第一遍定分辨率、第二遍在该分辨率里挑最小码率 |
| 新脚本 `tools/fnos-video.mjs` | 登录 → 图库前 5 页找视频（或直接传 photoId）→ 打印 `decode/info` 各档 → 逐档 `decode/play` → 对 playLink 的流试 **①只带 AccessToken 头 / ②再加 authx 头 / ③签名放 query**，直接回答「转码流需要哪种鉴权」 |

**按「最可能」排序的原因（下次接着查就按这个顺序）**：

1. `decode/play`（POST）签名被拒 → 一档都拿不到 playLink → 静默回退原画，三档自然一样；
2. `decode/stream`（m3u8）**还要 authx**，而 `<video :header>` 只发了 `AccessToken` → 播放立刻 `@error` → 回退原画；
3. `decode/info` 只给「原始画质」一档（短视频 / 源本身低分辨率）→ 没有可转的档；
4. NAS 给的转码档里没有差异明显的分辨率（例如只有 1080 和 720 两档）→ 只能靠状态行的 `720p` / `1080p` 区分。

**判定手段**：状态行文字（不看日志也能判断）+ `adb logcat` 无关；要协议真相就跑
`FNOS_USER=xxx FNOS_PASS=yyy node tools/fnos-video.mjs`（凭据只走环境变量，不进仓库）。

##### 5.18.2 实测结论（NAS 侧全部正常，问题只剩设备端的 HLS 播放）

用真机同账号跑 `tools/fnos-video.mjs`（`FNOS_USER/FNOS_PASS` 走环境变量）拿到的事实，**这些不用再猜**：

| 结论 | 证据 |
| --- | --- |
| `decode/info` **正常**，档位极多 | 一个 1080×1920 / 65s 的 `VID_20261006_201640.mp4` 返回 **25 档**：1080（16066k/15000k/10000k/8000k/5000k/4000k/3000k）、720（5000k/4000k/3000k/2000k/1000k）、540（4000k/3000k/2000k/1000k/800k）、480（3000k/2000k/1000k/800k/500k）、360（500k/400k/200k），`progressive` 全 true |
| `decode/play`（POST，请求体原文签名）**被接受** | 每档都 `HTTP 200 code=0`，`data.playLink` = **`/media/<32位hash>/preset.m3u8`（是路径，不是查询串！）** |
| 正确播放地址 = `STREAM_PATH + playLink` **直拼**（无 `?`） | `/p/api/v1/video/decode/stream/media/<hash>/preset.m3u8` → `200 application/vnd.apple.mpegurl`，内容是真的 `#EXTM3U … #EXT-X-PLAYLIST-TYPE:VOD` + `00000.ts` 分片 |
| 转码流**只认 `AccessToken` 头**（不需要 authx、不需要 query） | ① 只带 AccessToken：m3u8 200、分片 200 `video/mp2t`；② 再加 authx：同样 200；⑥ 不带鉴权：分片 **401** |
| **NAS 真的按档位出不同码率** | 720p@5000k 首片（2s）1,536,336 B ≈ 6145kbps；360p@200k 首片 233,872 B ≈ 935kbps —— 差 6 倍多，肉眼必然可辨 |
| 基座里**有 ExoPlayer 的 HLS 模块** | 拉 `com.nasphoto.tv` 的 base.apk 解包查 dex：`classes10.dex` 含 `HlsMediaSource` / `DefaultHttpDataSource` / `setDefaultRequestProperties` / `application/x-mpegURL` / `ProgressiveMediaSource` |
| `playLink` 是「一次会话一个目录」，各档 hash 不同 | 每调一次 `decode/play` 生成新的 `/media/<hash>/`，旧的不复用 |

**因此「低清却出原画」只剩设备端原因**：`<video>` 拿 HLS 时 `@error` → 旧代码回退「下载原文件再播」
（画面=原画），而**旧代码没把标签改回原画**，于是状态行一直骗人写着「低清」。现在已在 `@error` 里：

1. 把 `e.detail.errMsg`（ExoPlayer 的真实原因）截断后显示 6 秒；
2. 先回退**原画**（`mediaUrl(photo.video)`，同样经本机代理），再失败才下载本地；
3. 标签立刻改成「原画」，不会再出现「写着低清播着原画」。

**调试提示**：`adb shell input` 在这台 MIUI 上被拦（`INJECT_EVENTS` 权限），别指望用 adb 点 UI；
要么让用户手动复现 + 后台 `adb logcat -v time > %TEMP%\fnos-play.log` 抓日志，
要么直接看状态行（新版本会写 errMsg）。`adb.exe` 在 `E:\HBuilderX\plugins\launcher-tools\tools\adbs\1.0.36\adb.exe`。

##### 5.18.3 「浏览器里测三档完全没区别」= 设计如此，不是 bug（2026-11）

用户其实是在**浏览器**里验的（`PhotoViewer` 挂在 web dev server 上），三档画面完全一样，且状态行写着「低清」。
两个结论：

1. **web 端永远播原画**：浏览器无法播放 NAS 的转码流（HLS，需要 hls.js；而且分片只认 `AccessToken` 头，
   浏览器 `<video>` 加不了自定义 header），`utssdk/web` 的 `videoInfo` / `videoPlay` 本来就是返回错误的桩。
   所以**在浏览器里对比清晰度永远没有区别**，要验必须上 App（TV / 手机基座）。
   设置页底部那行「仅 App 生效」的提示就是为此写的。
2. **状态行误导**：旧写法在 web 上仍按「设置档位」写标签 → 写着「低清」却在播原画，看起来像功能坏了。
   现在 `playVideo()` 里先按平台算 `transcode`（web 恒 false），**标签取 `transcode` 的结果**：
   不转码一律「原画」；web 上若档位不是原图，还会先给 3 秒提示「浏览器只播原画（清晰度仅 App 生效）」。

> 想验证 T4.6 只能这样做：HBuilderX 运行到 Android App 基座（TV 盒子 / 手机），
> 设置页选标清或低清 → 进视频按 OK → 状态行应显示「播放中 · 标清 720p/1000k」/「播放中 · 低清 360p/200k」。
> 若显示「原画」，6 秒提示里会带 ExoPlayer 的 `errMsg`（2026-11-15 真机已按此流程验通）。

##### 5.18.4 真机实测：`<video :header>` 根本不生效（2026-11-15，已改本机代理，见 5.19）

第一次在真机跑 App，状态行给出 `转码流播不出来（Source error｜头=有｜preset.m3u8）`，
拉 logcat（`adb logcat -s ExoPlayerImplInternal`）拿到根因：

```
androidx.media3.datasource.HttpDataSource$InvalidResponseCodeException: Response code: 401
  at DefaultHttpDataSource.open(DefaultHttpDataSource.java:400)
  at ProgressiveMediaPeriod$ExtractingLoadable.load(ProgressiveMediaPeriod.java:1109)   ← 连原画直链也是 401
```

结论（**推翻了 5.11 里「`:header` 能注入 ExoPlayer」的判断**）：

- `<video :header="{AccessToken: ...}">` 在 **uni-app x** 真机上没有把 header 交给 ExoPlayer，
  直链 MP4 与 HLS 都是 401；
- NAS 侧只认 `AccessToken` **请求头**：`?AccessToken=` 401、cookie 401、Range 正常（206，实测 134MB 视频分块可读）。

所以「浏览器能播、真机全黑」的本质是：播放器拿不到鉴权头。修法见 **5.19**（插件内的本机回环代理）。

##### 5.18.5 本轮改动文件清单（T4.6：从需求到真机验收）

| 文件 | 改动 |
| --- | --- |
| `common/video.uts`（**新增**） | 档位常量（`original`/`sd`/`low`，默认 `sd`）+ `videoQualityOptions` / `normalizeVideoQuality` / `videoQualityLabel` / `videoQualityTargetHeight`；纯函数，不碰 storage |
| `pages/settings/settings.uvue` | 「视频清晰度」从占位变三档单选；新增 `focusRow` / `rightCount()` / `selectedQualityIndex()`；右栏上下只在栏内移动；web 说明「仅 App 生效」 |
| `components/PhotoViewer.uvue` | 取档（`videoInfo` → 挑档：标清=720 内最小码率、低清=全局最小码率）→ `videoPlay` → 播放；懒申请、9s 超时、序号保护、回退链（代理流 → 原画 → 下载本地）；状态行显示实际档位+码率；所有 src 改 `mediaUrl()`；`@error` 带 `errMsg` |
| `pages/library/library.uvue` | 角标文案预计算（`CellItem.tag` / `.live`），顺手修历史编译错误（`onViewerClose` 顺序） |
| `pages/album/detail.uvue` | 同上（`Cell.tag` / `.live`），并重排 `armKeyHook` 顺序 |
| `pages/search/search.uvue` | `visible` 改为 `Array<CellView>`（`photo` + `tag` + `live`），重排 `closeSearch` / `armKeyHook` / `handleKey` |
| `uni_modules/fnos-auth/utssdk/app-android/FnOsMediaProxy.kt`（**新增**） | 127.0.0.1 回环代理：透传 Range、补 AccessToken、重写 m3u8 |
| `uni_modules/fnos-auth/utssdk/app-android/FnOsHttp.kt` | `open()` 由 private 放开（代理要自己接管字节流） |
| `uni_modules/fnos-auth/utssdk/app-android/FnOsLogin.kt` | 新增 `mediaUrl()` / `openAuthed()`；`logout()` 里 `FnOsMediaProxy.stop()` |
| `uni_modules/fnos-auth/utssdk/app-android/index.uts` | 新增 `mediaUrl()` 导出；`streamUrl()` / `videoHeader()` 标注「真机无效，勿用于播放」 |
| `uni_modules/fnos-auth/utssdk/web/index.uts` | 新增 `mediaUrl()`（= `streamUrl`，同源 `/nas`） |
| `tools/fnos-video.mjs`（**新增**） | 视频清晰度自测：登录 → 找视频（图库扫不到就翻相册）→ 打印 `decode/info` 各档 → 按 App 规则逐档 `decode/play` → 分片字节数反推码率 → 转码流三种鉴权方式各试一次 |

### 5.19 本机回环媒体代理 `FnOsMediaProxy`（2026-11-15，真机 401 的解法，已验收）

**问题**（见 5.18.4）：NAS 媒体流只认 `AccessToken` 头，而 uni-app x 的 `<video :header>` 没把 header
交给 ExoPlayer → 直链与 HLS 全 401，`<video>` 根本播不出任何视频。

**解法**：在 App 进程里起一个只监听 `127.0.0.1` 的极简 HTTP 服务，由**我们**去 NAS 取字节并补鉴权头，
`<video>` 只跟本机回环打交道。

| 位置 | 内容 |
| --- | --- |
| 新增 `utssdk/app-android/FnOsMediaProxy.kt` | `ServerSocket(0, 8, 127.0.0.1)` + 每连接一线程（≤8 并发）。路径约定 `/v/<base64url(NAS 绝对地址)>`；**m3u8 的包装地址额外补 `.m3u8` 后缀**（ExoPlayer 靠末段扩展名判断 HLS，否则会走 ProgressiveMediaSource 直接报错），解析时再摘掉 |
| 普通文件（mp4 / ts / 图片） | 透传 `Range`（NAS 实测支持 206），回 `206/200 + Content-Type + Content-Length + Content-Range + Accept-Ranges`，字节流 64KB 分块转发；`Accept-Encoding: identity` 防 gzip 打乱长度 |
| HLS | 先取 m3u8 原文（带鉴权），把每个**分片行**按播放列表位置解析成绝对地址再包成本代理地址；`#EXT...` 注释行原样保留；返回 `application/vnd.apple.mpegurl` |
| `FnOsHttp.open()` | 由 `private` 改为 `public`：代理要自己接管 Range 与字节流，不能用会把响应读成 String/ByteArray 的封装 |
| `FnOsLogin.mediaUrl(path)` | `urlFor(path)` → `FnOsMediaProxy.start { target, range -> openAuthed(...) }` → `wrap()`；代理起不来时退回直链 |
| `FnOsLogin.openAuthed(target, range)` | 补 `AccessToken`（`ensureToken()`）+ `User-Agent` + `Range`，交给 `FnOsHttp.open` |
| UTS 两端 | `mediaUrl(path)`：App 端 `FnOsLogin.mediaUrl`；web 端直接等于 `streamUrl`（同源 `/nas`，浏览器没这个问题） |
| `PhotoViewer.uvue` | 所有播放地址（原画 / 转码流 / 回退原画 / 回退直链）改用 `mediaUrl()`；`streamUrl` 保留给兜底下载等场景 |
| `FnOsLogin.logout()` | 顺手 `FnOsMediaProxy.stop()`：退出登录后不该留着能代取字节的本机服务 |

**边界（别改坏）**：

- 只绑 `127.0.0.1`，不对外；代理 URL 里**只有 NAS 地址的 base64，不含 token**（token 只在进程内）；
- 一次请求一个线程 + `Connection: close`，够 ExoPlayer 用（它最多并发几条分片）；
- 播不了时的兜底链仍是：代理流失败 → 原画直链 → **下载到本地再播**（`video()`，插件自己带 token，必定能播）；
- `videoHeader()` 保留但**已验证在真机无效**，只是诊断 / 兼容用，别再把播放依赖它。

**真机结果（2026-11-15，已验收）**：代理上线后 logcat 里 **401 / Playback error 全部消失**（只剩正常的
Release 记录）；视频出画面、能拖进度（走 `Range` 206）、连播不残留声音；三档肉眼可辨。
中途低清曾「看着没区别」，是挑档问题（拿到 360p/500k），改按码率取最小后解决（见 5.18 时间线）。

**仍待补**：杀掉 App 后代理端口是否释放（`adb shell "cat /proc/net/tcp"` 看 `0100007F`）、
超长视频长时间播放的稳定性、HEVC 源（T4.8）在本代理下是否需要额外兜底
（H.264 转码流经代理播 = 与清晰度档位同一条路，已在 5.18 验过；差别只在「谁触发」，而那个触发点还没跑到，见 5.21.1）。

### 5.20 图库向上补页 / 跳转定位专项（2026-11-15 ~ 11-19，多轮真机迭代，**体验仍未达用户预期**）

这是本项目耗时最长的一次排查：用户从「跳转后直接上滑加载不出更早照片」开始反馈，
前后 8 轮真机复测。**先把结论放前面**：滚动链路（数据、补偿、高亮）全部是通的，
剩下的分歧在「补页后视口该停在哪」这个交互口径上，用户最后明确「先这样」，
所以这一节的价值主要是**别再走一遍已经被否掉的方案**。

#### 5.20.1 用户要的效果（原话与截图）

1. 「图库跳转到指定时间后直接往上滑动无法加载上一个时间的图片，需要往下翻一下再往上翻才能加载」
   —— 补页要能被上滑直接触发；
2. 「其实上面还有照片但已经显示年月了」—— 时间轴高亮/年月标题必须和视口实际内容一致；
3. 「往上滑到顶部后……出现了年月日，实际上应该是（满屏照片），并没有到这个年月日的第一张图片」
   —— 滑到顶时应当继续看到照片，而不是 `prev-gap` 空白 + 月份标题；
4. 「偶尔慢慢往上滑还会卡住」—— 任何「把视图推回模型位置」的做法都会造成这个观感。

#### 5.20.2 真机日志给出的事实（22:53 / 22:57 两轮，`[lib]` 前缀）

| 事实 | 证据 |
| --- | --- |
| `onScroll` **正常回传**，不是「事件不来」 | `onScroll top=1553.818 model=1553.94`，误差 <1px |
| 程序化 `scroll-top` **确实生效** | 补偿后立刻收到 `top=1553.818`，与 `next=1553.94` 一致 |
| 像素模型**很准**（`cellPx / dayHeaderPx / monthHeaderPx / groupGapPx` 与 CSS 一一对应） | 多轮 `top` 与 `model` 差 0~40px |
| 补页、`prevPending`、`applyScrollTop` 重申都在工作 | `loadPrev items=60 delta=1292/1470/1553` 反复出现 |
| 跳转落点正确 | `jumpTo offset=36303 top=27.936 anchor=g2025_12_31` → `active dt=2025:12:31` |

**所以「加载不出来」其实早就不存在了**，剩下的是观感问题。

#### 5.20.3 现在代码里保留的做法（改之前先看每条的理由）

| 做法 | 位置 | 为什么 |
| --- | --- | --- |
| 跳转落点用**原生锚点** `scroll-into-view`（`scrollIntoId = groupId(目标那天的 key)`，50ms 后设、450ms 后清空），`scrollTop` 作兜底 | `jumpTo` | 只认元素 id，不依赖像素估算和 `scroll-top` 时序；清空是为了避免后续插入内容时框架重放锚点把用户拽回跳转目标 |
| 补偿走 `applyScrollTop()`：同步设一次 + `resendScrollTop` 在 30/80/130/200/280ms 重申，每轮先设 `target+1` 再设 `target`（值相同 uvue 不下发该属性）；`scrollCmd` 序号让新的定位 / 用户滚动作废旧重申 | `applyScrollTop` / `resendScrollTop` | 真机上内容长高与 `scroll-top` 生效不同帧，单次设置会被丢弃或按旧高度钳制 |
| **距顶一屏就预加载**（`top < viewportPx`，`maybeLoadPrev` 与 `scrollBy` 同一口径） | `maybeLoadPrev` / `scrollBy` | 原来几乎贴到顶（`gapPx()+120`）才补，补页总在用户看到顶部之后才回来，于是他必然停在空白 + 年月标题处 |
| 视口已在顶部（`currentScroll < gapPx() + 2×cellPx`）时，补偿改成「让新照片填满视口」：`next = gapPx() + delta − viewportPx`；否则才用「保持原位」`currentScroll + delta` | `loadPrev` | 用户要的是「继续看到照片」，把他原样按在原位 = 滑了没反应 / 卡住 |
| 高亮取样点 `top + cellPx * 0.7`（`probe`） | `updateActive` | 补页补偿的余数会让视口顶部只露出上一行照片二三十像素，按「顶部那 1px」取样会提前跳到下个月（真机：`top=1553.8` 判成 2026-01-01，屏幕主体还是 2025-12-31）。`openViewer` 仍用 `currentScroll`，保持「打开最上面那张」 |
| `!p.ok` 与「返回 0 条」必须分开 | `loadPrev` | 一次超时就把 `atNewest` 置真、`startOffset` 归零，之后再也不重试（「有时能加载，有时按了没反应」） |

#### 5.20.4 已经被否掉、**不要再写回去**的方案

| 方案 | 为什么否掉 |
| --- | --- |
| 新插入分组 `opacity: 0` 占位（`freshGroups` / `.group-fresh` / `groupClass(gi)`） | 用户滑到顶时看到的是一整片**空白**（截图确认），比闪一下更糟。已全部删除 |
| 补偿有效期 + `pushCurrent()` 回推视图（`compensateUntil` / `compensatePushes`，`onScroll` 里发现 `\|top − model\| > 60` 就重推，最多 4 次） | 表现成「慢慢往上滑突然推不动」；模型与实际一旦差开就互相拽。已全部删除，`onScroll` 现在一律以实际位置为准 |
| `pinnedTop` 用「位置差 ≤48px」判断锁定 | 跳转落点紧贴留白，用户上滑整段位移都到不了 48px，锁定永远解不开，高亮卡住。现在只按 `pinGuard` 时间窗判断 |
| 只在 `onScroll` 里更新高亮 | 遥控器上下键走 `scrollBy`（程序化滚动），高亮完全不跟随。现在 `scrollBy` 自己调 `updateActive` |
| 跳转前预载上一页再滚过去 | 会先闪上个月；末尾月份还会因 `atEnd` 跳过补页导致定位失效（5.2 已记） |

#### 5.20.5 仍未解决 / 下次接手先看这里

- **观感仍未达到用户预期**（2026-11-19 用户确认「还是没改到我想要的效果，但就先这样了」）。
  用户期望的最终形态就是「滑到顶 → 满屏新照片，衔接自然」，现在的实现已经朝这个方向改，
  但到顶瞬间会不会仍闪一下、`prev-gap` 那 24rpx 留白是否多余，都没有再复测。
- 如果还要继续做，建议方向（按代价从小到大）：
  1. 把 `prev-gap` 整个去掉，改为「内容不足一屏时才补留白」——留白本身就是为了造可滚余量，
     现在有「提前一屏预加载」，它的必要性已经下降；
  2. 遥控器上下键完全改用 `scroll-into-view`（按组/按行设锚点），彻底不依赖 `scroll-top`；
  3. 换 `list-view`（原生 RecyclerView）承载网格，让原生侧负责插头部时的锚定，不再手工算像素。
- 调试开关：`[lib]` 前缀的日志（`jumpTo` / `onScroll` / `active` / `loadPrev` / `atTop` 等）
  仍留在 `library.uvue` 里，节流 300~400ms；收尾时别忘了删，或者统一挂到一个 `LIB_DEBUG` 常量上。

### 5.21 HEVC（H.265）转 H.264 兼容开关（T4.8，2026-11-19，**已实现；兜底链未在真机触发过，见 5.21.1**）

**用户口径**：做进**设置页**、**开启后全局有效**、**默认关闭**（关闭时行为与本轮之前完全一致）。

**最终形态一句话**：这个开关只决定「原画放不出来之后怎么办」——
`@error` 时若开关开着，就请 NAS 用 `videoEncoder=h264` 转一份 HLS，并带 `startPosition` 从当前进度续播；
开关关着就走原来的「下载同一个文件到本地再播」（编码没变，HEVC 照样没画面）。

| 项 | 实现 |
| --- | --- |
| 存储 | `fnos.video.hevc` = `on` / `off`；缺省 / 非法值一律 `off`（`common/video.uts` 的 `normalizeHevcCompat` / `isHevcCompatOn`） |
| 设置页 | 「视频清晰度」栏的**第 4 行**（三档之后）：标题「HEVC 转 H.264（兼容模式）」+ 一行说明 + 右侧开关胶囊（已开启蓝底白字 / 已关闭灰底）。`focusRow == qualityOptions.length` 即开关行，OK / 点击都切换并立刻写 storage；`rightCount()` 已 +1 |
| 全局生效 | `PhotoViewer.playVideo()` **每次打开视频读一次**（与清晰度档位同一时机）→ 改完对下一张视频生效，正在播的不打断 |
| 降级链（每级只试一次） | ① 转码流失败 → 回原画直链；② 原画失败 **且** 开关开 → `prepareTranscode(p, original, true)` 申请 H.264 HLS；③ 还不行 → 下载到本地再播；④ 再不行才报错。开关关着就只有 ①③④ |
| 挑档 | HEVC 兜底的目标分辨率取**源视频短边**（`PhotoItem.w/h` 的较小值，缺省 720），再按「同一分辨率取最小码率」挑 → 尽量接近原分辨率而码率不爆（1080 源一般落在 `1080p/3000k`） |
| 续播 | `videoPlay(id, bitrate, resolution, startPosition)` 新增第 4 参；Kotlin **只在 > 0 时**才把该字段放进请求体（未确认字段，不能污染原有三档链路）。就绪后 `mountVideoStream(url, 0, true)` 直接开播，**不传 initial-time** |
| 失败提示 | 超时 / 没有可转档位 / 接口拒绝 → `hevcFallbackFailed()`：状态行 6 秒「该视频编码不支持…」+ 直接去下载原文件，**不再回原画**（原画刚证明放不出来） |
| 状态行 | 转码中「转码中…（H.264兼容）」；播放中「播放中 · H.264兼容 1080p/3000k」；下载兜底仍如实写「原画」 |
| web | 开关照样存，但 `playVideo()` 里 web 恒 `transcode = false`，兜底不会触发；设置页说明按 `#ifdef WEB` 换成「仅 App 生效」 |
| 不改的东西 | 播放器面板仍然只有倍速；`FnOsMediaProxy` 不用动（m3u8 分片重写本来就是通用逻辑）；转码流地址照样**不进** `video()` 磁盘缓存 |

**为什么判断不了「源是不是 HEVC」**：`decode/info` 的 `quality[]` 只有 `resolution/bitrate/progressive`，
`gallery/getList` 项与 `photoDetail` 里也**没有 codec 字段**（全仓库 grep `codec|h265|hevc` 零命中）。
所以按 §7 T4.8 的原话走「**播放失败即尝试转码**」的兜底，不做启发式猜测，**也不主动转码**
（h264 源仍然一条转码请求都不发）。

**已知兜不住的情况**：老盒子遇到 HEVC 有时**不报错**，而是「有声音没画面」。这种不触发 `@error`，
本开关帮不上；解法是把清晰度设成标清 / 低清（那本来就是 NAS 转的 H.264）。

**真机验证步骤**（浏览器里**验不出来**：web 只播原画，`videoInfo/videoPlay` 是返回错误的桩）。
⚠️ **第 2 步的前提是「这台机器硬解不了 HEVC」**：现有测试机支持 HEVC 硬解，第 2 步的失败现象不会出现，
兜底链根本不会触发 —— 那种情况改走 **5.21.1** 的「故意让直链失败」或换设备。

1. `node tools/fnos-video.mjs` → 看新增的「带 `startPosition=30`」那一行：拿得到 `playLink` 说明 NAS 认这个字段，
   续播才会真从当前进度开始（拿不到就是字段被忽略 → HEVC 兜底从头播，但**照样能看**）；
2. 找一个 HEVC 视频（手机拍的 `VID_*.mp4` 多半是 H.265）→ 清晰度设成**原图**、开关**关** → 按 OK：应出现播放失败 / 只有声音；
3. 打开开关 → 重进该视频按 OK：状态行「转码中…（H.264兼容）」→ 出画面 →「播放中 · H.264兼容 1080p/3000k」，能拖进度；
4. 同一个 h264 视频：开关开着也应**一直播原画**（logcat 里不出现 `decode/play`）；
5. 连续切 HEVC / h264 各 5 次：不卡死、不残留声音（`stopVideo()` 在换片 / 关闭 / 卸载里都调）。

**本轮改动文件**：`common/video.uts`（开关常量 + 规范化）、`pages/settings/settings.uvue`（第 4 行开关 + 焦点/OK）、
`components/PhotoViewer.uvue`（读开关、`prepareTranscode(..., hevcFallback)`、`hevcFallbackFailed`、
`fallbackDownload` / `videoFail` 抽成函数、`shortText` / `videoDebugSuffix` 前移以满足 UTS「先定义后调用」）、
`FnOsLogin.kt`（`videoPlay` 加 `startPosition`）、`utssdk/app-android/index.uts` + `utssdk/web/index.uts`（同步签名）、
`tools/fnos-video.mjs`（探测 `startPosition` 是否被接受）。

#### 5.21.1 真机验证记录（2026-11-19）：测试机支持 HEVC 硬解，**兜底链没能触发**

- 现象：装到真机后，清晰度设成**原图**、开关**打开**，播 HEVC 视频 → **直接出画面**，
  `@error` 一次都没来，状态行始终「播放中 · 原画」。
- 原因：这台机器（TV 盒子 / 手机）**支持 HEVC 硬解**，「原画播不出来」这个前提不成立。
  T4.8 本质是**失败兜底**，没有失败就什么都不做（也不该主动转码，见上表「不改的东西」），
  所以在这台机器上开关开 / 关观感**完全一样** —— 这是预期行为。代价是
  **②「原画失败 → 申请 H.264 转码」这一级从未被真正触发过**（代码可走查，但未运行验证）；
  ③ 下载兜底 / ④ 报错是既有逻辑，本轮只是从 `onVideoError` 里抽成 `fallbackDownload()` / `videoFail()`，
  行为没变，但同样没在这台机器上重新跑一次。
- 已经能确认的：设置页第 4 行的 UI / 开关持久化 /「默认关闭」（浏览器验过，真机上开关本身能点）；
  真机装基座后**开关开着不影响正常播放**（能硬解的 HEVC 直接出画面、状态行「原画」、不发 `decode/play`）。
  遥控器焦点路径本身仍是「真机遥控器有异常、暂缓」（见 5.12），不算验过。
- **仍未确认**：`startPosition` 是否被 NAS 接受（跑 `node tools/fnos-video.mjs` 看「带 `startPosition=30`」那行）、
  兜底转码在真机上能否出画面 / 拖进度、连续切 HEVC 与 h264 不残留声音。

**没有「不支持 HEVC 的设备」时怎么验兜底链**（下次接手照这个来，验完务必还原）：

1. **最省事：故意让原画直链失败**（不改协议、不换设备）——
   `PhotoViewer.playVideo()` 里 `!transcode` 那个分支，临时把
   `videoSrc.value = mediaUrl(p.video)` 改成 `videoSrc.value = mediaUrl('/p/api/v1/stream/p/t/0/s/broken')`
   （必然失败）→ 进视频按 OK 必进 `onVideoError`：
   - 开关**关**：应走到 `fallbackDownload()`，最后报「播放失败…」（证明旧兜底链没坏）；
   - 开关**开**：应「原画放不出来，正在转码为 H.264…」→「转码中…（H.264兼容）」→
     出画面「播放中 · H.264兼容 xxxp/xxxk」→ 左右能拖进度。
     这条能通就说明整条 HEVC 链路是好的 —— `decode/play` 只认 `id + 档位`，跟那条被改坏的直链无关。
   ⚠️ 这是**临时调试**，验完必须改回去，不许进仓库。
2. **换设备 / 换样本**：Android 7/8 的 4K 盒子对 HEVC 10bit 硬解常不全；也可以用 ffmpeg 转一个
   **HEVC 10bit 或 4:4:4** 的样本放进 NAS 相册（比 8bit 更容易 `Decoder init failed`）。
3. **抓日志**：`adb logcat -s ExoPlayerImplInternal FnOsMediaProxy` ——
   `Decoder init failed` / `Unsupported` = 编码原因（正是要救的场景）；`401` = 鉴权问题（与本功能无关）。

**实现时踩到的点（下次改这块别再犯）**：

- **UTS 局部函数必须「先定义后调用」**：`prepareTranscode` 调 `hevcFallbackFailed` → 后者调 `fallbackDownload`
  → 后者调 `videoFail` → 后者调 `videoDebugSuffix`，于是一整串都得排在 `prepareTranscode` 前面；
  `shortText` / `videoDebugSuffix` 因此从「`onVideoTime` 后面」**整体前移**到「清晰度段之前」。
  往这块加函数，先想清楚顺序，否则 Android 编译直接「找不到名称」。
- **HEVC 兜底路径故意不传 `initial-time`**：如果 NAS 认 `startPosition`（playlist 从中途开始），
  又叠一个 `initial-time = 当前进度`，会 seek 到 playlist 之外（大概率直接跳片尾）。
  两种时间轴只能留一种：现在选「发 `startPosition`、不给 `initial-time`」，最坏是从头播，不会跳尾。
- **`startPosition` 只在 > 0 时才进请求体**：未确认的字段不能顺手加进清晰度三档的报文里 ——
  5.18 验过的 `{id,bitrate,resolution,audioIndex,audioEncoder,videoEncoder,channels}` 保持原样。
- **设置页右栏多一行要同步 `rightCount()`**；模板里**不能写后代选择器**
  （`.q-switch-on .q-switch-text` 这种），开关文字状态得挂在自己的 class（`.q-switch-text-on`）上。

### 5.22 遥控器按键分发层（T5，2026-11-21 完成，**✅ 2026-11-25 真机验收通过**）

**做了什么**：把「每个页面自己写一串 `if (code == 19)`」收敛成一套分发层，
新增 `common/keys.uts`（全工程唯一的按键码定义处 + 分发器 + 去抖工具）。
**收键通道（`FnOsTvKeys` / `startTvKeyHook` / `stopTvKeyHook(id)`）一个字没动**，见 5.12。

#### 5.22.1 `common/keys.uts` 有什么

| 导出 | 作用 |
| --- | --- |
| `KEY_UP=19` / `KEY_DOWN=20` / `KEY_LEFT=21` / `KEY_RIGHT=22` / `KEY_OK=23` / `KEY_ENTER=66` / `KEY_BACK=4` | 页面**不许再写字面量**，一律 import |
| `KEY_REPEAT_MS = 180` | 连按 / 长按去抖窗口（与查看器快进那条原有 180ms 一致） |
| `keyName(code)` | 日志用（`key=23 OK`） |
| `dispatchKey(code, stack, run)` | 按栈分发：**从栈顶（数组末尾）往下问，第一个返回 `true` 的层消费掉**；没人消费记 `→ none`；返回命中的层名 |
| `class KeyDebounce(gapMs)` | 连按去抖：`schedule(task)` 覆盖上一个未触发的定时器，`cancel()` 清掉（切图 / 关闭 / 卸载必须调） |

#### 5.22.2 页面侧固定写法（新增页面照抄，不改公共代码）

```ts
// 1. 每层一个函数，返回 boolean
function onGridKey(code: number): boolean {
  if (code == KEY_DOWN) { scrollBy(step) } else { return false }   // 只认自己的键
  return true
}
// 2. 层名查表（UTS 不能把函数塞进容器）
function runKeyLayer(layer: string, code: number): boolean {
  if (layer == 'xxx.grid') { return onGridKey(code) }
  return false
}
// 3. 按当前状态排栈：底层在前、顶层在后
function handleKey(code: number) {
  const stack: Array<string> = []
  stack.push('xxx.grid')
  if (overlayVisible.value) { stack.push('xxx.overlay') }
  dispatchKey(code, stack, (layer: string, c: number): boolean => { return runKeyLayer(layer, c) })
}
```

**两种层的约定**（写错就会「焦点在覆盖层上却把网格滚走」）：

- **覆盖层（模态）一律 `return true`** 吞掉所有键：弹窗、时间轴、布局切换条、导航栏焦点、搜索输入框；
- **基础层只对自己认识的键 `return true`，其余 `return false`**：网格、卡片托盘、查看器本身；
  这样上层让位的键还能落到基础层（视频层不认识「下键」→ 交给查看器开详情）。
- 返回键由**最上层**决定：能关覆盖层就关覆盖层，关不掉再往下传（不许在基础层抢着处理）。

#### 5.22.3 各页现在的层（改行为先看这张表）

| 文件 | 层（底 → 顶） | 备注 |
| --- | --- | --- |
| `pages/index/index.uvue` | `login.form` → `login.edit`（编辑弹窗）→ `login.2fa`（两步验证） | 两个弹窗都是模态，弹出后方向键不再跑到表单 |
| `pages/home/home.uvue` | `home.tray` → `home.nav`（`navIndex >= 0` 时） | 导航栏焦点左右**循环**五个入口 |
| `pages/library/library.uvue` | `library.grid` → `library.rail`（`railFocus`）→ `library.layout`（`layoutFocus`） | 时间轴 / 布局条各自独立成层；层级与重构前「先判布局、再判时间轴、最后网格」一致 |
| `pages/album/album.uvue` | `album.grid` | 单层 |
| `pages/album/detail.uvue` | `detail.grid` | 单层；查看器打开期间由组件独占通道 |
| `pages/search/search.uvue` | `search.input` 或 `search.grid` → `search.back` | `search.back` 永远在栈顶：返回键无视内容直接关搜索 |
| `pages/settings/settings.uvue` | `settings.left` → `settings.right`（`focusArea == 1`） | 右栏返回键仍先退回左栏（`onBack()`），与重构前一致 |
| `components/PhotoViewer.uvue` | `viewer.base` → `viewer.video` 或 `viewer.video-dead` → `viewer.detail`（详情展开时） | 视频能力按 `videoSeekable()` 决定压哪层；详情面板只吃上/下键，左右切图与返回照旧 |

#### 5.22.4 日志

分发层对**每一次按键**打一行（`console.log`，TAG 前缀 `[keys]`）：

```
[keys] key=23 OK → library.rail (stack=library.grid>library.rail)
[keys] key=20 DOWN → none (stack=home.tray)
```

- 有 `→ 层名`：说明通道正常、这层吃了这个键 → 行为不对就是**这层的分支**写得不对；
- `→ none`：通道正常但没人认领（多半该层 `return false` 了，或栈里根本没压那层）；
- **一行都没有**：事件没走到 Window（视图树里被吃掉）或通道被摘 → 回 5.12 排查表看 `FnOsTvKeys` 的行。

#### 5.22.5 改这块的坑

- **不要为了「统一」去动 Kotlin 收键那一层**：包装器常驻、退订必须带 id（5.12），
  这次重构只改「收到按键之后怎么分发」；
- `dispatchKey` 的第三个参数必须是**就地写的 lambda**（`(layer: string, c: number): boolean => ...`），
  工程里没有「函数类型 type 别名 + 函数数组」的先例，别拿 UTS 当 TS 用；
- 层函数**定义顺序**仍受 5.2 第一条约束：层函数 → `runKeyLayer` → `handleKey` → `armKeyHook`，
  被调用者必须在前；
- 模态层忘了 `return true` 就会穿透（现象：焦点在时间轴上，按上下却把网格滚走）；
- 去抖实例（`KeyDebounce`）要跟持有它的页面/组件同生命周期，
  `stopVideo()` / `onUnmounted` 里必须 `cancel()`，否则 T5 测试项 3 会挂（残留定时器）。

#### 5.22.6 验证状态

- ✅ 代码层核对：`pages/**` 与 `components/**` 里**已无任何 `code == 19` 之类字面量**，
  也没有本地 `const KEY_*`（只剩 `common/keys.uts` 一处定义）。
- ✅ **真机已验（2026-11-25 用户确认 T5 完成）**：§7 T5 的 5 项测试项通过。
  定位过程中修掉的真机问题：订阅 id 撞车导致按键全失效（**5.29**）、返回键被系统处理两遍（**5.23**）、
  电视正式包看不到 logcat（**5.28**，改为屏幕日志）。
- ✅ 原「不在本轮范围」的**导航栏焦点**也已补上（**5.25**）：图库 / 相册 / 设置 / 搜索的上键到顶与返回键
  都会先把焦点交给 `NavBar`（`focus-index`），再按返回才离开本页 —— 这是 T5 的最后一环，
  不再有「导航栏只能鼠标点」的页面。

### 5.23 遥控器焦点 / 返回键专项修复（2026-11-21，**✅ 2026-11-25 真机验收通过（随 T5 收口）**）

用户一次性列了 8 条「智能电视 + 遥控器」的问题，本轮逐条处理。**根因往往只有两个**：

#### 5.23.1 根因 A：返回键被处理了两遍（最重要）

`FnOsTvKeys` 的包装器以前始终 `return base.dispatchKeyEvent(event)`，所以一次 BACK 会：
App 的 `handleKey` 处理一次（关弹窗 / 关查看器），**系统再按默认行为弹一次页面**。
真机现象就是用户报的那几条：

- 相册内看照片按返回 → 查看器关了，**同时**页面被弹回相册列表；
- 搜索结果里看照片按返回 → 查看器关了，**同时**退出搜索页；
- 首页按返回 → 一边 `reLaunch` 回登录页、一边系统弹栈，**页面卡死在中间态**。

**现在的做法**（`FnOsTvKeys.dispatchKeyEvent`）：只要当前有页面订阅，
`KEYCODE_BACK` 上报之后**直接 `return true` 吃掉**，不再交给系统；没有订阅（导航过渡期）
时仍然转发。副作用是**所有页面必须自己给返回键一个语义**，现在分别是：

| 页面 | 返回键 |
| --- | --- |
| `pages/index`（登录页，根页面） | 第一次提示「再按一次返回键退出应用」，2.5 秒内再按一次 `quitApp()` |
| `pages/home` | 弹出退出确认弹窗（取消 / 退出），再按返回关弹窗 |
| `pages/library` | 回首页（时间轴 / 布局条有焦点时先退那一层） |
| `pages/album`、`pages/album/detail` | 回首页 / 回相册列表 |
| `pages/settings` | 右栏 → 左栏 → 上一页 |
| `pages/search` | 直接关搜索（回进入前的页面） |
| `PhotoViewer` | 详情展开时**只关详情**，否则关查看器 |

⚠️ 不要改回「BACK 也转发给系统」，那会立刻复活上面三条现象。

#### 5.23.2 根因 B：焦点状态不是响应式的

`pages/album/album.uvue` 的 `focusIndex` 以前是普通 `let`：模板里的 `cardClass(i)` 读得到新值，
但**不会触发重渲染**。按上下键时因为顺带改了 `scrollTop` 才刷新，按左右键不改滚动 →
界面停在旧焦点上。真机表现就是用户报的「光标只能上下动、左右移动后上下错乱」。
**焦点下标一律用 `ref`**（图库的 `cursorIdx`、搜索的 `gridIndex` 都是 ref）。

#### 5.23.3 各页改动

| 页面 | 改了什么 |
| --- | --- |
| `pages/home` | 新增退出确认弹窗（层 `home.exit`，左右选 / OK 确认 / 返回关闭，确认走新增的 `quitApp()`）；托盘改为**「选中项在轨道内移动、快出视口才滚」**（`trayLeftFor` 用「保持可见 + 1.2 张余量」，不再永远居中）；托盘 OK 进入场景（以前 OK 根本没分支）；上键进导航栏不变 |
| `pages/index` | 返回键二次确认退出（新增 `quitApp()`） |
| `pages/library` | 新增 `cursorIdx` + `.cell-cursor` 选中框：**与 OK 打开的那张、时间轴高亮同一个 `indexAtScroll` 口径**；焦点跑到时间轴 / 布局条时选中框变灰（`.cell-cursor-idle`），避免两处都像「当前焦点」 |
| `pages/album` | `focusIndex` 改 `ref`；焦点框默认可见（电视端必须）；下键在最后一行不满时落到该行最后一格；`cellPx` 补上一个 GAP（滚动跟随不再累计偏差） |
| `pages/settings` | 左栏新增 `.opt-focus` 白描边；**左栏 OK 也能进右栏**（以前 OK 什么都不做 → 看起来像「设置页坏了」） |
| `pages/search` | OK 改为 `showKeyboard()`（先 `focus=false` 再下一帧 `true`，已有关键字也能弹键盘）；`inputFocus` 初值改 false、`onLoad` 主动唤键盘；关闭查看器后恢复网格焦点框；支持 `?from=scene` 自动搜索（见 5.23.4） |
| `components/PhotoViewer` | ① 详情层：BACK / MENU / UP 都**只关详情**；② 新增 `KEY_MENU(82)` 开关详情；③ 图片改**双缓冲**（两层 `<image>`，新图先进后层 `opacity:0`，`@load` 后只切 z-index）→ 换原图 / 换下一张不再黑一帧；④ 视频**未开播时左右键仍切上一张/下一张**（已开播才是 ±10s 快进）；⑤ 实况 / 视频层补 `z-index:3`、左右热区 `z-index:4`（否则被图片盖住） |

#### 5.23.4 `quitApp()` 与「进入场景」

- `quitApp()` 是这一轮新增的插件 API：`FnOsLogin.quitApp()` → `activity.finishAffinity()`，
  回电视桌面。**不要**去找 `plus.runtime.quit` / `uni.exit`，uni-app x 里没有。
  web 端是空实现（浏览器关不掉应用）。
- 首页 OK「进入场景」目前**没有真接口**：`ai-smart-rec/categories` 只返回分类
  （name / itemCount / poster），既没有分类 id 也没有 items 端点。所以现在的做法是
  「把场景名写进 storage（`fnos.scene.keyword`）→ `navigateTo('/pages/search/search?from=scene')`
  → 搜索页读走即删并自动用 `magic-search/do` 搜一次」。
  拿到真接口后**只改 `pages/home/home.uvue` 的 `enterScene()` 一处**，
  搜索页的 `consumeSceneKeyword()` 可以一起删。

#### 5.23.5 改这轮的坑

- **模态层必须 `return true`**，且权限最高；`home.exit` 压在 `home.nav` 之上（见 5.22.2）；
- `inputFocus` / `:focus` 是**单向 prop**：已经是 `true` 再赋 `true` 不会重新弹键盘，必须 false→true；
- 双缓冲的两层图**不能靠改 src 切换**（那还是会重新解码、黑一帧），只能切 z-index / opacity；
- 加 `z-index` 会重新排层：给图片加了 1/2 之后，`vw-live` / `vw-video` / `vw-side` 都必须显式抬到 3/4；
- 返回键现在由 App 独占，**新增页面必须注册返回语义**，否则按键会「没反应」；
- 焦点下标忘了用 `ref` = 界面不刷新（5.23.2），这类 bug 在浏览器用鼠标点时不容易暴露。

### 5.24 图库改「光标模型」+ 真机卡顿/焦点专项（2026-11-21 第二轮，**✅ 2026-11-25 真机验收通过（随 T5 收口）**）

用户第二轮反馈（逐条对应见下），**核心是把图库从「按键 = 整页滚动」改成真正的光标**，
并把两处高频日志关掉。

#### 5.24.1 图库：按键 = 光标移动（不是翻页）

| 项 | 现在的行为 |
| --- | --- |
| `cursorIdx` | **唯一权威**：网格上的选中框、OK 打开的那张、时间轴高亮，三者都以它为准 |
| 上 / 下 | 光标 ±`cols`（一行）；**只有光标要出视口时才滚动**（`moveCursorIntoView`） |
| 下（在最后一行） | 先 `loadMore()` 补更晚一页，光标留在原地 |
| 上（在第一行） | `loadPrev()` 补更早一页：先按插入张数把光标下移（保持同一张），再由 `pendingCursorUp` 把它上移一行落进新照片 |
| 右 | 进时间轴（不变） |
| OK | `openViewer(cursorIdx.value)` —— **看到的框 = 打开的那张**（以前按 `indexAtScroll` 重算，和框不一致） |
| 查看器关闭 | 光标落在**刚才看的那张**上（`viewerOffset - startOffset`），并把页面滚到它可见 |
| 用户自己滚（web 滚轮 / 拖动） | 才用 `indexAtScroll(视口顶部 + 0.7 格)` 反推光标 |

关键实现：

- `topOfIndex(idx)`：`indexAtScroll` 的逆运算（下标 → 顶部 px），逐组累加，O(组数)；
- `moveCursorIntoView()`：光标出视口才滚，且**直接设 `scrollTop`**（不用 `applyScrollTop` 那套 5 轮重申）；
- `cursorLockUntil`：自己滚出来的 `onScroll` 回调在 300ms 内**不反推光标**（否则长按时光标被反复拽回视口顶部，真机表现为「光标乱跳/消失」）；
- **`scrollBy()` 已删除**（旧的「一次按键滚 2 行」）；`setCols` 的锚点也从 `indexAtScroll` 改成 `cursorIdx`。

#### 5.24.2 卡顿：真机路径不许 `console.log`

- `common/keys.uts` 的 `KEY_DEBUG`、`pages/library` 的 `LIB_DEBUG` 都是 **false**；
  每按一次键就写 logcat 是同步写，长按连发时会把页面拖卡（用户反馈「长按后整个 app 很卡」）。
  排查时临时改 true，验完改回。
- 顺带把「每次按键排队 10 个 `setTimeout` 重申 scrollTop」从按键路径上去掉了（只有跳转/列数切换还用它）。

#### 5.24.3 首页

- 托盘：`trayLeftFor` 的余量从 1.2 张改成 **0** —— 光标先在一屏里逐格移动，**贴边才推轨道**。
  余量 1.2 时轨道在光标离边还有一段就开始跟着走，观感就是「整条轨道一直在平移」。
- 返回键：**焦点在导航栏时也弹退出确认**（以前只把焦点退回卡片条，用户按返回「什么都没发生」）。
- `navIndex >= 0` 时托盘 `.tray-idle` 压暗到 0.45，配合更亮的导航栏焦点（`.entry-focus` 蓝底 + 2rpx 描边），
  让「焦点上去了」一眼可见。
- 导航栏边框统一 **2rpx**：焦点只改颜色不改宽度，避免整条导航栏位移。

#### 5.24.4 时间轴焦点

- `.rail-item` **恒留 2rpx 透明边框 + `box-sizing: border-box`**：焦点只改颜色，
  否则焦点行会把后面所有行往下推 4rpx，`scrollRailTo` 的落点全偏（与相册卡片同一个坑）；
- 焦点行：蓝底胶囊（`rgba(51,136,255,.34)`）+ 蓝描边 + **文字反白加粗**（`rail-label-focus`）。
  以前只有一圈 2rpx 描边，TV 上看不出光标在哪（用户反馈「到了时间轴光标就没了」）。

#### 5.24.5 还没解决的问题（下次先看这里）

- 用户报了「首页按上/返回无反应」，代码走查是通的（层表 + 日志都对）。
  本轮已把反馈做强（托盘压暗 + 导航栏亮焦点 + 返回必弹窗）。**如果真机仍无反应**，
  说明按键没进到页面：把 `KEY_DEBUG` 改成 `true` 重跑，看有没有 `[keys]` 行 ——
  没有就是通道/焦点问题（5.12），有 `→ home.nav` 就是渲染问题。

### 5.25 全站焦点模型收敛：导航栏也属于焦点链（2026-11-22，web 键盘实测反馈）

用户第三轮反馈（**web + 键盘模拟**），核心是一条统一的焦点链：

```
网格/托盘  ⇅ 光标   →  上到第一行再按上 → 导航栏 → 再按返回 → 离开本页
返回键：先交导航栏，导航栏里再按返回才离开（不再"一按就回首页"）
```

#### 5.25.1 各页现在的焦点链

| 页面 | 上到顶 | 返回键（不在导航栏） | 返回键（在导航栏） |
| --- | --- | --- | --- |
| 首页 | 进导航栏（已有） | 退出确认弹窗（已有） | 退出确认弹窗（本轮补） |
| 图库 `library` | **导航栏**（还有更晚的照片时先补页） | **焦点交导航栏** | 回首页 |
| 相册 `album` | **导航栏** | **焦点交导航栏** | 回首页 |
| 相册内页 `detail` | 无导航栏：停在第一行 | 回相册列表 | — |
| 搜索 `search` | 网格→输入框→导航栏 | **焦点交导航栏** | 关搜索、回进入前的页面 |

实现要点（三个内容页同一套）：

- 每页新增 `const navIndex = ref(-1)` + `NAV_COUNT = 5`，模板 `:focus-index="navIndex"`；
- 层名 `<page>.nav` **永远压在栈顶**（`handleKey` 里最后 push），进导航栏后方向键不再动网格；
- `gotoNav(i)` 统一跳转：0 首页 `reLaunch` / 1 图库 / 2 相册（`redirectTo`）/ 3 搜索 / 4 设置（`navigateTo`）；
- 焦点跑进导航栏时，网格选中框 **变灰**（`cell-cursor-idle` / `card-focus-idle` / 搜索框不高亮），
  否则两处都像"当前焦点"；
- `onShow` 里 `navIndex = -1`：从搜索/设置/内页回来，焦点回到网格。

#### 5.25.2 图库左右键改成"同一行走动"

- 左/右 = 同一行内 ±1 张；**到行末再按右才进时间轴**（以前一按右就跳时间轴，用户以为"没走到行末"）；
- 但网格是**按天分块**的，`idx ± cols` 会跨块错位，所以新增 `buildRows()` / `moveRowIndex()` /
  `moveColIndex()` 三个纯函数：把 `[每行首张下标, 该行张数]` 拉平，按真实行列走。
  相册内页用 `cellTops` 生成同一份行表（同一行的 `cellTops` 相同）。

#### 5.25.3 相册内页 `detail` 加光标

以前只有"视口顶部那张"没有框（用户："进入相册里面后没有光标标识"）。现在：
`cursorIdx` + `.cell-cursor`，四向移动按行表、出视口才滚（`ensureCursorVisible`），
OK 打开光标那张，关闭查看器后光标落回刚看的那张。

#### 5.25.4 搜索页

- 搜索条内部两个焦点：输入框（`barFocus=0`）/「✕」（`barFocus=1`）。输入框里**按右键**选中 ✕，
  OK 清空 / 关闭（以前 ✕ 只能用鼠标点，遥控器根本删不了字）；
- 输入框上键 → 导航栏；网格上键 → 输入框；导航栏下键 → 输入框；
- `search.input` / `search.clear` / `search.grid` 三层对 BACK **必须 `return false`**，
  让 `search.back` 拿到键（不然返回被吞、什么都发生不了）；
- 视频：BACK 先退播放态（见 5.25.5），左右可切图。

#### 5.25.5 相册内页顶栏（2026-11-22 第二版）

内页原本只有网格能吃键，「拍摄时间」排序根本进不去。现在：
- `barFocus = -1 / 0 / 1`（网格 / 返回 / 排序），层 `detail.bar` 压在 `detail.grid` 上；
- **网格第一行再按上 → 焦点进顶栏（落在「排序」上）**，左右在「返回 / 排序」间切，OK 生效，下键回网格；
- 焦点在顶栏时光标框变灰（`.cell-cursor-idle`）。

#### 5.25.6 视频：只有"真在播"才把左右当快进

`onVideoKey` 的门槛从 `videoStarted` 改成 **`videoPlaying`**：
暂停 / 结束 / 出错 / 还没播 / 准备中，左右一律交回基础层切图上下一张。
之前只看 `videoStarted`，「播过一帧但已经停住」（播放失败、暂停）时左右还在走快进分支，
用户看到的就是「视频里翻不动，按多了像卡死」（第三轮反馈）。

#### 5.25.7 查看器：视频返回先退播放

用户口径：**视频播放中按返回，先回到「按 OK 开始播放」**（这时左右键能切图），再按一次才关查看器。
实现：`onViewerKey` 的 BACK 分支里 `viewerIsVideo() && videoStarted` → `stopVideo()` + 提示，不关查看器。
另外 `onVideoKey` 的"左右不算快进"扩到「未开始 / 正在准备 / 正在转码」三种状态，
避免"进了视频既不能快进也不能翻页，看着像卡死"。

### 5.26 查看器换图：web 端不要双缓冲（2026-11-22，用户实测定位）

**现象**：查看器里右键翻页，诊断行 `i=12/227` **已经变了**，但**画面一动不动**；
继续按就像"卡死"（其实 index 一直在涨，只是什么都看不见）。

**根因**：双缓冲靠 `<image>` 的 `@load` 把「后层」切到前台。
uni-app x 的 **web** 端不保证回调 `@load`（App 端正常），于是：
第一张图因为是 A 层默认就在前台 → 看得见；之后每张都进后层（`opacity:0`）→ **永远切不上来**。

**现在的做法**：

| 平台 | 换图策略 |
| --- | --- |
| **web** | `#ifdef WEB` 里**单图直替**（`imgA = src`、清空 B、`frontIsB=false`）。浏览器自己保留上一帧，不会黑一帧 |
| **App** | 保留双缓冲（`@load` 切层），**再加 700ms 兜底定时器**（`armPromoteFallback`）：`@load` 不来也强制切前台 |

**web 端切窗口后「像卡死」**（2026-11-22，两种都要防）：

1. **正在播**时切走再回来：`<video>` 会冻住（`play()` 无效）→ `onWebHidden()` 里直接停播
   （`stopVideo` / `stopLive`，回到「按 OK 开始播放」）；
2. **没播放**时切走再回来：页面本身没挂 `<video>`（`v-if="videoStarted && videoSrc.length>0"`），
   但浏览器常把键盘焦点交给别的窗口/地址栏，`keydown` 到不了页面 → 用户看到「按键全没用、只能刷新」
   → `onWebShown()` 里**重挂一次按键订阅**（`armKeyHook()`）并提示「点一下画面」。

入口：`installWebWatch()`（`#ifdef WEB`，在 `onMounted` 里装）监听
`visibilitychange` + `window.blur/focus`。App 端不需要这段。
> 这是 web 调试环境的补丁；真机没有这个问题，也不要把它当成播放逻辑的一部分。

改这块的注意：

- 不要把 web 的 `#ifdef` 删掉改回双缓冲 —— 会立刻复现"翻页没反应"；
- App 端兜底定时器要在 `clearViewerImage` / `onUnmounted` 里 clear，否则残留；
- 排查这类"index 动了但画面不动"的问题，先看 `VIEWER_DEBUG` 那行诊断（`i=`/`idx=`/`vs=`/`vp=`/`su=`）。

### 5.27 收尾：web 遥控器全流程验完（2026-11-22），准备上真机

**本轮 web（键盘模拟遥控器）已经逐页验过并通过**：首页、图库（光标/时间轴/每行张数/返回导航栏）、
相册列表、相册内页（光标/排序/OK 打开）、搜索（✕ 选中/返回导航栏/视频翻页）、设置、登录页。

收尾做了这些：

1. **四个诊断开关全部关闭**：`common/keys.uts` 的 `KEY_DEBUG`、`pages/library` 的 `LIB_DEBUG`、
   `pages/album/detail` 的 `DETAIL_DEBUG`（都在文件里，需要时改 `true`）；
2. **删掉两处屏上诊断**：相册内页 `.dbg` / 查看器 `.vw-debug`（连模板节点、`dbgText`、`debugLine` 一起删）；
3. 保留的东西（不是临时调试，别删）：
   - `utssdk/web` 的键盘模拟（`startTvKeyHook` → keydown，见 5.11.1）；
   - 查看器的 `installWebWatch()`（切窗口停播 + 回窗重挂按键，见 5.26）；
   - `libLog()` / `detailLog()` 两个受开关控制的日志函数（排查时改开关即可，不用重写）。

**上真机前要知道的差异**（web 验证覆盖不到的部分）：

| 项 | web 已验 | 真机必须重验 |
| --- | --- | --- |
| 按键通道 | 键盘模拟 | `FnOsTvKeys` 真实 D-pad；`hasCallback=true`、`[keys]` 是否安静（默认无日志） |
| 返回键 | 页面语义 | **BACK 由 App 独占**（5.23.1）：不弹系统页面、不越级 |
| 视频 | 原画 mp4 | 本机代理 + 清晰度三档 + HEVC 兜底（5.19 / 5.21） |
| 换图 | 单图直替（web） | **双缓冲 + 700ms 兜底**（5.26）是否真的不黑屏 |
| 相册内页/图库光标 | 已验 | 长按连发时的流畅度（`LIB_DEBUG` 关着，先看体感） |
| 输入框 | 不弹键盘 | 软键盘唤起、键盘期间按键归 IME |

### 5.28 电视正式包看不到 logcat → 按键轨迹搬到屏幕上（2026-11-22）

**约束**：用户测试机只能装**正式包**，连不上 adb，`console.log` / `adb logcat` 全用不了。
于是把「按键进了哪一层」这件事从日志搬到界面：

| 位置 | 内容 |
| --- | --- |
| `common/keys.uts` | 新增 `KeyTrace` + 环形缓冲（最近 **40** 条）+ `keyTraces()` / `clearKeyTraces()`；`dispatchKey` 每分发一次就压一条（**不写 console**，只做一次字符串拼接） |
| `pages/settings/settings.uvue` | 左栏第 5 项「**按键诊断**」：右栏列出 `hh:mm:ss 页面 按键 → 命中层`，最新在最上面；停在诊断栏时**每按一次键就刷新**；右栏唯一可聚焦行是「清空记录」（OK 清空） |

读法（和原来的日志一模一样）：

```
12:31:07  library  RIGHT(22) → library.grid          ← 这层吃掉了
12:31:09  home     OK(23) → none                     ← 没有任何层认领（漏写 return false）
```

- 第三列是**命中的层名**（`→ none` = 没人认领），第二列是层名前缀（页面）；
- 第一列是时间，用来判断「连按时是不是每个键都到了」；
- 缓冲只在内存里、40 条循环覆盖，**重进应用即清空**，对性能无影响（按键 < 10 次/秒）。

> 打正式包前的检查清单：`KEY_DEBUG` / `LIB_DEBUG` / `DETAIL_DEBUG` 都是 `false`（5.27），
> 屏上临时诊断（`.dbg` / `.vw-debug`）已删除，**只保留**设置页这个「按键诊断」（对用户无害、出问题可自救）。
> 如果哪天能连上 adb，`adb connect <电视IP>:5555` 后照样可以 `logcat`，两者不冲突。

### 5.29 电视首次实测（2026-11-23，小米蓝牙遥控器）：根因是「订阅 id 撞车」

#### 5.29.1 根因：`startTvKeyHook` 的 id 用了 `Date.now()`

真机现象（一条都别丢，全是同一个根因的不同表现）：

- 首页左右变成「整条轨道平滑滚动」、上下无效；
- 图库 / 相册**刚进来时**方向键乱、焦点框不出现，**用鼠标点一下照片之后就正常了**；
- 设置页按键**完全没反应**（点鼠标也没用）；
- 查看器、视频反而一直正常。

原因：`utssdk/app-android/index.uts` 里订阅 id 是 `Date.now()`（**毫秒**）。
`onLoad` 和 `onShow` 往往在同一毫秒各调一次 `startTvKeyHook`，再叠加「上一页退订 / 查看器关闭」
的 `stopTvKeyHook(id)` 也落在同一毫秒 → **旧页面的退订把新页面的通道清掉了**，
于是那个页面收不到任何键：D-pad 落到系统默认导航（滚动 scroll-view、移动原生焦点），
就出现「整条轨道平移 / 上下无效」；而「点开一次照片再回来」会走 `onViewerClose → armKeyHook()`，
通道被重新装上，于是「点一下就正常了」。

**修法**：id 改成插件内的**自增计数器**（`tvKeySeq += 1`），永不重复。
> 现在幂等：重复 `start` 只换回调；`stop` 只在 id 相等时生效。

#### 5.29.2 其它一并修掉的

| 问题 | 修法 |
| --- | --- |
| 图库「焦点框看不到」 | 选中框**从绝对定位的子层改成格子自身的边框**（`.cell` 恒留 4rpx 透明边框 + `border-box`，`.cell-focus` / `.cell-focus-idle` 只改颜色）。原生视图里 `position:absolute` 的子层不保证画得出来 |
| 图库「照片还在最左边，一按右就进时间轴」 | 新 `rightTarget()`：行内右移；**只有「排满一整行」的行末才进时间轴**，天分组造成的残行（当天只有 1~2 张）直接顺延下一张 |
| 首页「按上没反应」 | 焦点进导航栏时**底部提示文案也换掉**（`← → 选入口 · OK 进入 · ↓ 回到场景条`）。托盘压暗在个别机型上不明显，文字一定看得见 |
| 设置页按键全无 | 订阅 id 修好后再加一道保险：`onShow` 里 260ms 后**再挂一次**（`pageVisible` 守卫，`onHide` 置 false），`start` 幂等 |
| 视频「丢帧 / 卡顿」（原片不卡） | `onVideoTime` 以前每 250ms 写一次 `videoPos` → 进度条 + 两个时间文本每秒重渲染 4 次，和硬解抢主线程。改成**1 秒粒度**更新 |
| 查看器「原图出来得慢」 | 原图替换延迟 300ms → **120ms**（快速连切仍会跳过） |

#### 5.29.3b 真机第二轮：**「按一下遥控器主页键回到桌面再进 App 就好了」** → 安装自愈

用户实测（小米蓝牙遥控器）：冷启动后**首页按上不去导航栏、左右只是整条场景滚动、图库/相册/搜索都要先用鼠标点一下才正常、登录页完全没反应、设置页反而正常**；
但**按遥控器「主页」回桌面、再切回 App，遥控器立刻就正常了**。

结论：这不是分发层的 bug（按键诊断里能看到 `→ settings.left` 这类正确记录），而是
**`window.callback` 在页面 onLoad 之后才被框架/AppCompat 换掉**，我们早先包上去的 wrapper 被顶掉 →
`dispatchKeyEvent` 再也到不了 UTS；「回桌面再回来」触发 onShow → 再 `start()` → 检测到 base 变了重新包装，于是就恢复了。

修法：`FnOsTvKeys.ensureWatchdog()` —— 有订阅期间每 **2 秒** 调一次 `ensureInstalled()`
（已装好时只比较一次引用就 return），框架任何时候换掉 callback 都能在 2 秒内自愈，用户不必再按主页键。
> 这条是**真机专属**的坑：web 端和「一直退到后台再回来」的手动操作都会掩盖它。

同一轮还修了：

- **时间轴滚动跳一大截**：`scrollRailTo` 从「固定留 10 行」改成「要出视口才滚、只滚刚好可见」（按 `viewportPx` 估可视高度）；
- **视频高码率丢帧**（1x 卡、0.5x 不卡）：本机代理 `FnOsMediaProxy` 的读缓冲 64KB → **256KB**，并套一层 `BufferedOutputStream`（每包 flush，不能攒）。

#### 5.29.3c 无线 adb 实测结论（2026-11-23 19:36，电视 MiTV-MFTP0 / Android 9 / 1920×1080）

电视上应用包名 **`com.grfntv`**（启动 Activity `com.grfntv/io.dcloud.uniapp.UniAppActivity`），
可以无线调试：`adb connect 192.168.31.182:5555`（首次要在电视上点「允许」）。抓日志：
`adb logcat -s FnOsTvKeys:*`，截图：`adb shell screencap -p /sdcard/a.png` + `adb pull`。

实测到的事实（**这条比任何猜测都硬**）：

1. 冷启动后日志只有一行 `key hook installed for id=1`，之后按方向键**完全没有** `key=…` 行；
   同一时刻界面按不动 → **wrapper 在首帧之后被框架换掉了**，`dispatchKeyEvent` 不再经过我们；
2. 按遥控器「主页」回桌面、再切回 App，日志立刻出现 `key hook installed for id=7`，
   随后按键正常（`key=7/22 hasCallback=true`）→ **只有重新 `start()` 才会检测并重新包装**；
3. **页面切换（首页 → 图库）之后同样失效**：进图库后按右键没有日志、界面不动。

所以：`window.callback` 在「冷启动首帧后」和「每次页面切换」都会被框架替换，
靠 `onLoad`/`onShow` 各 arm 一次根本盖不住 —— **必须轮询自愈**（`ensureWatchdog`，200ms 起步、之后 800ms）。

#### 5.29.3d 新包实测（21:06 包）：自愈生效；**方向键必须由 App 独占**

装 `__UNI__1B208D1_1123194833.apk`（21:06 打）后，用无线 adb 连电视实测：

1. **自愈生效** ✅ 冷启动后日志出现 `installed for id=1` → 约 1.4s 后 `installed for id=3` → 直接按键
   就有 `key=3/19 hasCallback=true`，**不再需要按遥控器主页键**。截图确认：按 ↑ 后导航栏「首页」白框、
   托盘压暗、底部文案切成 `← → 选入口 · OK 进入 · ↓ 回到场景条 · 返回 退出`。
2. 页面切换也会自愈（进图库后日志出现 `installed for id=5`，随后按键正常）。
3. 图库「残行右键」修正生效：第一行只有 1 张时按 → 会移到下一张（不再直接跳时间轴）；
   排满一行的行末再按 → 进时间轴，时间轴出现**蓝底胶囊**焦点，网格焦点框转灰。
4. ❗**新发现的第二个根因：方向键被处理了两遍**。按键诊断截图证据：
   `14:45:02 library DOWN(20) → library.rail`、`14:45:03 → library.rail`，
   我们只把 `railIndex` 加了 2，但时间轴**整块跳了两年** —— 因为除 BACK 外的键仍 `return base.dispatchKeyEvent(event)`，
   系统把可滚动的 `scroll-view` 当原生控件又滚了一页（一页≈屏高，正好是两年的行数）。
   同理：首页「整条卡片条平滑滚动」、网格偶尔被带偏，都是系统原生滚动造成的。

**结论（别再改回去）**：只要有页面订阅，`KEYCODE_DPAD_*` / `DPAD_CENTER` / `ENTER` / `NUMPAD_ENTER` /
`BACK` / `MENU` 的 **ACTION_DOWN 和 ACTION_UP 全部 `return true` 吃掉**（`isOwnedKey`），
只有音量、媒体键、输入法等其它键才交给系统。软键盘是独立窗口，不受影响。

#### 5.29.3e 「左右按一下跳两格」的唯一原因：系统 D-pad 连发

Android 的 D-pad（遥控器）**按住会自动连发**，间隔约 **50ms**（首次约 400~500ms 后开始）。
以前我们原样把每个 `ACTION_DOWN` 都转给页面，所以「轻轻一按、手指停留久一点」就会收到 2 次 → 跳一格。
（另一条叠加因素：方向键转发给系统后，`scroll-view` 还会被原生再滚一页，见 5.29.3d。）

修法：**同键 100ms 去抖**，两端各一份、行为一致：

| 端 | 位置 | 说明 |
| --- | --- | --- |
| App | `FnOsTvKeys.dispatchKeyEvent`（`lastDownCode` / `lastDownAt`） | 同键 100ms 内的重复 DOWN 直接 `return true` 丢掉；按住仍能持续移动（约 10 次/秒） |
| web（键盘调试） | `utssdk/web/index.uts` 的 keydown 监听（`tvLastCode` / `tvLastAt`） | 浏览器按住方向键同样以 ~30ms 连发，不去抖时在浏览器里也会「跳一格」 |

> 为什么不是「只认 repeatCount==0」：那会让长按彻底不能连续移动。
> 100ms 既能滤掉连发的前两次误触，又保留了「按住快速浏览」的手感。

#### 5.29.3 排查纪律（正式包、无 logcat）

`设置 → 按键诊断`：任何「按键没反应」先看这里。**没有新行 = 通道没装上**（先怀疑订阅 id / 页面没 arm），
`→ none` = 层没认领，`→ 层名` = 逻辑或渲染问题。

### 5.30 电视第三轮反馈（2026-11-24，六条）

| # | 现象 | 根因 | 修法 |
| --- | --- | --- | --- |
| 1 | 图库：单张一行的照片按右**进不了时间轴**（跑到下一张） | 上一版把「残行顺延下一张」写成规则 | `rightTarget` 改回 **「本行最后一张 → 进时间轴」**，不看这行是否排满（用户口径，最终版） |
| 2 | 视频 **1x 丢帧**、0.5x/0.75x 不丢 | 单位时间吞吐不够（本机代理转发），不是 UI（UI 早已降到 1s/次） | `FnOsMediaProxy` 读缓冲 **256KB → 512KB**，并**去掉 BufferedOutputStream**（少一次内存拷贝）；片源是 4K/H.265 时仍建议用「标清」 |
| 3 | 搜索页删除按钮**没有图标** | 用了 `✕`(U+2715)，小米电视字体缺字形 → 圆圈是空的 | 换成 `×`(U+00D7)，字号 11→16rpx、加粗 |
| 4 | 设置「按键诊断」按多次后**看不到清空按钮** | 列表是 `flex:1` 的 `scroll-view`，条目一多把它顶出屏幕 | 改成**固定高度 196rpx 的普通 view**，只显示最近 **12** 条（缓冲仍是 40 条） |
| 5 | 首页按返回退出到桌面、重新进 App 后**显示 401**，点其它页会**闪一下登录页** | **不是竞态，是真的过期**：退出到桌面后服务端那条 AccessToken 已作废，冷启动第一次请求必然 `401 访问码已失效`（adb 实测：首页和图库都 401）。**在首页原地 `login()` 静默重登实测无效**（试过，仍 401） | 首页 `loadScenes` 遇 401 → **`reLaunch('/pages/index/index')`** 交给登录页自动重登；**登录页新增 `autoBusy` 遮罩**（整页 logo +「正在恢复登录…」），自动登录期间不显示表单 → 不再「闪一下」。内容页保留「首次 401 不跳转」抑制误跳 |
| 6 | 首页 → 场景（搜索）→ 设置，**返回退回搜索页**（期望直接回首页） | 设置页用的是 `navigateBack()` | 搜索页场景入口写标记 `fnos.scene.ret='home'`；设置页新增 `leavePage()`：有标记就 `reLaunch` 首页并清标记，否则 `navigateBack()`。搜索页 `closeSearch()` 里清标记 |

> 5 的取舍：真过期（token 失效）时用户第一次操作只看到错误提示，再操作一次才跳登录页。
> 这是为了消灭「闪一下登录页」——冷启动竞态远比真过期常见。

### 5.31 401 之后的连锁坑（2026-11-24 下午，adb 实测）

现象链：首页「退出」→ 回桌面 → 重新启动 → 首页 401 → 跳登录页 → **登录页遥控器彻底失效** + 一直显示「自动登录已暂停」。

实测到三件事：

1. **401 是真的**：`getToken()` 在冷启动时是空的（token 只在内存 + storage 里，退出后服务端那条已作废），
   首页/图库第一次请求必然 401。**在首页原地 `login()` 静默重登实测无效**，必须回登录页自动重登。
2. **首页 → 登录页的跳转会无限循环**：`home` 被 `reLaunch` 后重建，页面内的 `authRedirected` 布尔量被重置，
   于是「401 → 登录页 → 成功 → 回首页 → 又 401」把登录页的自动登录次数（60 秒 3 次）耗光 → 暂停。
   **限流必须存 storage**（`fnos.authRedirectAt`，8 秒内只跳一次）。
3. **reLaunch 到登录页后按键全失效**（keys 一条 `key=` 都没有，但窗口有焦点）：
   uni-app x 换页会新建 `UniLandscapePageActivity`，新窗口的 `window.callback` **有可能还是我们那个旧包装器对象**，
   `ensureInstalled()` 只比 `base === current` 就误判「已装好」→ 新页面永远收不到键。
   **必须连同 Activity 一起比较**（`activityRef.get() === activity`），换了 Activity 就重装；
   另外 `stop()` **不再销毁 watchdog**，否则「某页 stop、下一页还没 arm」的空档会让应用永久收不到按键。

顺带：登录页「自动登录已暂停」现在会把**真实失败原因**带上（`lastAutoError`），不再只有一句笼统提示。

### 5.32 401 现状（2026-11-24 晚，实测边界）

**已验证修好**：`FnOsTvKeys` 的「换 Activity 就重装」生效 —— adb 驱动登录页的 DOWN/OK 全部有 `key=` 日志，
可以在登录页手动点「登录 / 测试连接」，登录成功、首页场景正常加载。

**仍未解决**：首页「退出」→ 回桌面 → 重新启动 → 首页 401（1.5s / 3.5s / 9s 三帧截图都是「登录状态已失效，请重新登录」）。

> ## ⚠️ 401 的**最终根因**（2026-11-24 17:34，屏上诊断 + 代码对照确认）
>
> `FnOsLogin.login()`（UI 登录入口）调完 `doLogin()` **只返回结果，没有把 token 写进内存字段**：
>
> ```kotlin
> val outcome = doLogin(...)   // doLogin 内部不碰 token
> outcomeJson(outcome)         // ← 少了 token = outcome.token
> ```
>
> 后果（和所有现象一一对上）：
>
> 1. UI 登录「成功」了，但 `getToken()` 仍是空串（登录页一直显示 `AccessToken —` 就是这个）；
> 2. 于是每个业务接口都走 `ensureToken()` → **再登录一次** → 服务端把上一次的 AccessToken 作废 →
>    多个接口并发时互相顶掉 → `401 访问码已失效`；
> 3. 而缩略图/原图/视频那条链路（`downloadTo`）自带「401 → 清 token → 重登 → 重试」，所以**图能出来、列表 401**；
> 4. 首页「有没有 token」的判断永远为假 → 反复 reLaunch 登录页（用户看到**循环闪登录 3 次**），
>    最后把自动登录次数耗光 → 「自动登录已暂停」+ 页面卡死。
>
> 修法：`login()` 成功后 `if (outcome.ok) { token = outcome.token; secret = outcome.secret }`
> （两步验证那条路 `verifyTwoFactor` 本来就写了 `token = newToken`，所以只有普通登录踩坑）。
> 另外 `home` 的「无 token 就回登录页」加了 **10 秒限流**，万一以后再有异常也不会来回跳。

> **2026-11-24 17:16 包复测仍 401 —— 当时以为根因是 `apiGet` / `apiPost` 收到 401 时不会重登。**
>
> `apiGet` 的 401 分支以前只做「校验访问码」（`currentAccessCode` 非空才做），
> 用户没填访问码 → 直接 `throw FnOsException("访问码已失效…")`，**永远不会用保存的账号重新登录**。
> 而 `downloadTo` / `downloadVideoTo`（缩略图、原图、视频）早就写了「清 token → ensureToken → 重试」，
> 所以**图片能出来、JSON 接口却一直 401** —— 这解释了全部现象。
>
> 修法：`apiGet` / `apiPost` 的 401 分支追加一次
> `synchronized(lock) { token = ""; ensureToken() }` 后再重试（`tokenRefreshed` 保证只重登一次）。

> **2026-11-24 17:06 包复测仍 401。**（次要判断：冷启动可能同时发生两次登录）
> 首页 `loadScenes()` → 插件 `ensureToken()` 在后台登录一次（token A）；
> 与此同时登录页的自动登录也在跑（token B）→ 服务端单会话，A 被 B 顶掉 →
> 首页拿 A 请求 → `401 访问码已失效`，而且重试也还是它。
> 而**手动只登录一次就完全正常**（adb 实测过两次），正好印证。
>
> 修法：`pages/home` 的 `onLoad` **先看 `getToken()`**，为空就 `reLaunch` 登录页（自己不触发登录），
> 保证冷启动全程只有**一次**登录。若下一版仍 401，再查 `FnOsLogin` 的签名/did。
关键实测事实：

- `finishAffinity()` **不杀进程**（退出前后 pid 相同），所以内存里的 token 还在；
- 与此同时 `图库` 也是 401，说明是**服务端拒绝了这条 token**，而不是"没带 token"；
- 同一台机器上**手动登录一次就完全正常**（说明账号/密码/访问码没问题，也不是网络）。

下一步靠**屏上诊断**定位（已加进代码，待下一版）：

- 登录页底部新增诊断串：`cfg saved=? host=? user=? pwd=<长度> acc=<长度> token=<长度>`；
- 若是"token=0 且自动登录失败"，问题在自动登录；若是"token=8 仍 401"，则是服务端会话/签名问题，
  再往 `FnOsLogin` 的签名与 `did`（设备标识，登录页显示"未注册"）上查。

### 5.33 视频黑屏 / 越滑越卡（2026-11-24 晚，真机 adb 实测）

**视频「无法播放」**（原画）：状态行写着「播放中 · 原画」，进度 `0:00 / 0:00`、画面全黑、**不报错**。
logcat 里有明确异常：

```
ClassCastException: io.dcloud.uniapp.runtime.UniElementImpl
  cannot be cast to uts.sdk.modules.DCloudUniVideo.UniVideoElement
    at DCloudUniVideo.createVideoContext$lambda
    at GenComponentsPhotoViewer.setup$lambda$28$gen_videoContext_fn  ← 我们的 videoContext()
```

即**在组件内部** `uni.createVideoContext('pw-video')` 拿到的不是 video 元素（uni-app x 的已知坑）。
修法：

1. `videoContext()` 里 **try/catch 兜住**（上下文只用于 play/pause/seek，拿不到也能靠 `:autoplay` 播）；
2. 新增 `armVideoWatch()`（`onVideoPlay` 里启动）：播放器说「开始了」但 **8 秒内没有进度/时长**
   → 判定流打不开 → 走已有的 `fallbackDownload()`（下载到本地再播）→ 仍失败才 `videoFail()` 显示原因。
   这条把「黑屏且无提示」变成「能播或明确报错」。

**越滑越卡、封面渲染不出来**：图库是滑动窗口，但 `loadMore` 只追加、`loadPrev` 只前插，**从不丢弃**，
翻十几页就上千个缩略图节点 → 内存吃紧、GC 抖动、后续图片渲染失败。
修法：`WINDOW_MAX = 240`（4 页）上限 ——
`loadMore` 超了从**头部**丢（用 `topOfIndex(over)` 补偿滚动位置），
`loadPrev` 超了从**尾部**丢（放在滚动补偿之后做，否则 `delta` 偏小会跳）。

> 实况照片的「实况」角标依赖接口返回的 `live` 字段；真机上没看到角标，需要拿到一条实况的接口原始 JSON
> 才能确认是字段没返回还是名字不同（待查）。

## 6. 当前状态

**2026-11-22 最新一轮（遥控器焦点 / 返回键专项）已在 web 上逐页验过并通过**，见 **5.22 ~ 5.27**。
统一口径：`网格光标 → 上到第一行再按上 → 导航栏 → 再按返回才离开本页`；
查看器里 `详情层 → 视频播放态 → 查看器` 逐层退。

**已跑通**（真机 + 浏览器）：WS 登录 / 2FA、图库日期分组网格、缩略图下载缓存（s 级）、
8/6/4 列切换、年月两级时间轴跳转（含末尾月份：能向上补页、高亮不错位）、向上分页
（⚠️ 补页后的**观感**仍不达用户预期：到顶时衔接、卡顿感，详见 **5.20**）、
401 自动退出重登、web 端登录态持久化（刷新 / 隔夜 / dev server 重启都自动登录，见 5.6）、
图片查看器（T1，见 7）、网格类型角标 + 实况照片自动播放（见 5.10）。
实况在**浏览器已实测**：自动播放有声、播完回图片、`▶` / OK 重播、进查看器不再黑屏也不闪播放按钮；
**实况片段在 App 端仍未真机验证**（它走 `video()` 下载到本地再播，插件自带 token，风险低但不等于验过）。

**视频播放器（T2）已真机跑通**（2026-11-15，见 5.11 / 5.18 / 5.19）：进入不自动播放（按 OK 才开始）、
OK 播放/暂停、左右 ±10 秒、进度条与状态文案、连按累积、开头/结尾边界；倍速用 chip（TV 按「上」）循环切档、
**每个视频默认 1.0x**；清晰度五档在设置页选、真机各档肉眼可辨；
**播到片尾会自动收尾（「已结束 · OK 重播」），按 OK 从 0:00 重播**（2026-11-25 真机验证，见 5.38）。
❗App 端播放**必须**走 `mediaUrl()`（插件内本机回环代理）——`<video :header>` 真机不生效（401），
这条结论推翻了早期「`:header` 能注入 ExoPlayer」的判断。

**已跑通（浏览器，见 5.13）**：相册列表（`album/list`，宝宝相册置顶带角标）、相册内页
（`album/item` + `album/photos`，按年月/日期分组、拍摄时间升降序、分页、实况/视频角标）、
照片查看器**抽成 `components/PhotoViewer.uvue`**（图库与相册内页共用同一套图片/视频/实况/详情），
共享类型与文案在 `common/photo.uts`。

**已跑通（浏览器，见 5.14）**：首页大屏（`pages/home/home.uvue`，登录后落地页）——
智能场景全屏海报、右上实时时钟、底部 54 个场景的横向卡片托盘、10 秒自动轮播 + 手动切换重置计时、
图库 / 相册入口；卡片点按只做选中（详情按要求本轮不做）。真机未验（依赖 `image()` 下载 m 尺寸海报）。

**已跑通（浏览器，见 5.15）**：设置页（`pages/settings/settings.uvue`）—— 左右 3:7 布局，
「登录状态」用 `user/info` 判活（不是 token 非空）+ IP / 端口 / 账号 / 密码脱敏 / HTTPS / 退出登录（带二次确认），
首页展示 / 视频清晰度 / 主题三项按需求只做占位；三页导航栏「设置」改为 `navigateTo` 进本页并高亮。

**导航栏已组件化**（`components/NavBar.uvue`，见 5.4）：首页 / 图库 / 相册 / 设置四页共用一份，
悬浮顶部、不占布局高度；四页右侧统一「搜索 + 设置 + 时间」，设置页「设置」按钮高亮。
三块功能（首页大屏 / 导航栏 / 设置页）的改动清单、踩坑与验证手段汇总在 **5.16**。

**搜索（T4.4）已实现并跑通浏览器功能链路，真机待验**（见 **5.17**）：`pages/search/search.uvue` +
插件 `search()`（`POST /p/api/v1/magic-search/do`）+ web 的 POST 转发中间件 +
`tools/fnos-search.mjs`（确认返回结构用）。功能上：导航栏照常显示且「搜索」为选中态、深色搜索框、
一排 8 个正方形缩略图（视频带时长 / 实况角标）、点击进共用查看器、几千条结果分批渲染。
**接口返回的字段结构仍未确认**，插件做了容错解析（策略见 5.17.3）。
本轮已按用户反馈迭代 3 次：① 导航栏保留 + 搜索选中态；② 3000+ 结果改分批渲染；③ 白底搜索框改深色胶囊、
修掉选中态图标变实心圆球的 bug。问题与解决办法汇总在 **5.17.2**。

**视频清晰度（T4.6）已实现并在真机验收通过**（见 **5.18** / **5.19**；2026-11-24 扩到**五档**，见 5.35.2）：设置页五档单选
（原图 / NAS转码原画 / 1080P / **默认标清** / 低清），存 `fnos.video.quality`；查看器每次打开视频读一次档位 →
原图直链（不发转码）、NAS转码原画取源短边且码率封顶 20M、1080P 封顶 6M、标清取「720 档最小码率」、低清取「全局最小码率」，
按 OK 后懒申请 `video/decode/*` 的 HLS 流，9 秒超时或失败自动回退原画并提示；
状态行显示**实际档位 + 码率**（`原画` / `标清 720p/1000k` / `低清 360p/200k`）。
播放器面板仍然只有倍速。**浏览器里验这条一定「没区别」**（web 只播原画，见 5.18.3），必须上 App 端。

**视频播放的根问题（2026-11-15 解决）**：`<video :header>` 在 uni-app x 真机不生效 → ExoPlayer 请求 401，
以前「只在浏览器验」把这条完全遮住了。现在播放地址统一走插件内的**本机回环代理** `mediaUrl()`
（`127.0.0.1`，插件补 `AccessToken` + 透传 `Range` + 重写 m3u8），见 **5.19**。

**HEVC 转 H.264（T4.8）已实现，但兜底链未验证**（见 **5.21** / **5.21.1**）：设置页「视频清晰度」栏第 4 行加开关
（`fnos.video.hevc`，**默认关闭**，全局生效）；开着时原画 `@error` 会请 NAS 转 H.264 HLS 并从当前进度续播，
关着则完全保持原行为。真机上装过、开关与正常播放都验过，但**测试机支持 HEVC 硬解**，
`@error` 不触发 → 「原画失败 → 转 H.264」这条链**一次都没跑过**（未验证）。
复验方法见 5.21.1（临时把原画直链改成必失败地址，验完还原）。**浏览器验不出来**（web 只播原画、转码接口是桩）。

**登录设备展示（T4.9）已按网页端真实帧修好**（需求、排查路径、踩坑与复验步骤全写在 T4.9 章节）：
`deviceName` 必须是 `<类型>-<客户端>`（本端固定 `TV-NAS Photo TV`，`deviceType` 用 `Mobile`），
NAS 设备管理页据此拆出「NAS Photo TV」大标题 + 「TV 登录于 …」小标题；web dev server 同名、无 `(web)` 后缀。
图标仍是通用设备图标（fnOS 前端只有浏览器 / PC / 手机三种，没有电视图标）。
**同一 `did` 的旧记录不会刷新 `deviceName`**：改完必须先在设备管理里删掉旧记录，再重新登录。

**2026-11-15 首次真正做 Android 编译**（此前所有功能都只在浏览器验过）：
一次性暴露 5 类历史编译错误（`watch` 缺显式类型、3 处「先用后定义」、`detail.uvue` 模板里调导入函数），
已全部修掉（见 5.2 前三条）。以后再改 `.uvue`，**至少跑一次「运行到 Android App 基座」**，别只靠浏览器热刷新。

**待做（§7，2026-11-20 起按版本划分）**：

- **1.0**：**已全部完成** —— T1 / T2 / T3 / T4.1~T4.9 早已实现，
  **T5 遥控器操作方式重构与测试于 2026-11-25 完成并通过真机验收**（分发层 `common/keys.uts` 见 5.22，
  真机问题见 5.23 / 5.25 / 5.29 / 5.30）。1.0 无剩余开发项，只剩 T2 / T4.4 / T4.8 的验证尾巴。
- **2.0**：首页展示内容（T4.5）、主题设置（T4.7）、相册页顶部功能栏——人物 / 地点 / 标签 / 收藏 / 视频
  （T4.10，五个独立需求）、首页自动轮播时托盘缩小 50%（T4.11）、健壮性（T6）。
- **3.0**：AI 评价照片（T7，设置页可开关）。

原「屏保/待机」需求已删除。
**真机遥控器问题已解决（2026-11-25）**：T5 收口后遥控器在电视上全流程可用（上下滚动 / 进时间轴 /
返回逐级 / 查看器独占通道 / 导航栏焦点），根因与修法见 5.12 / 5.23 / 5.25 / 5.29。

**遥控器按键通道（5.12）**：历史上「登录页遥控器正常、进图库后所有按键失效」已按
「包装器常驻 + 退订带 id + 页面 `onLoad`/`onShow` 各订阅一次」修过；
但 **2026-11 当前真机上遥控器仍有异常**（用户明确「先不管」），浏览器鼠标路径正常。
复验时看 `FnOsTvKeys` 的日志，判定方法见 **5.12** 的排查表；组件化后查看器期间由
`PhotoViewer` 独占通道，排查时也要看它的 `onMounted` / `onUnmounted`。

接手时如果遇到编译错误，先看 5.2 的踩坑表；动顶部导航栏则先看 5.4 + 5.5，
动 web 登录 / 代理 / 401 处理则先看 5.6，
动查看器（图片 / 视频 / 实况 / 详情）则先看 **5.13**（组件契约、props/emits、按键接管）与 5.7（行为要求），
动相册列表 / 相册内页 / 相册接口则先看 **5.13**（含「为什么不能用 album/normal/getList」），
动详情面板 / `getOne` 则先看 5.8（触发条件、参数校验、字段兼容、失败处理都写死了），
动详情排版 / 人物头像 / 相册封面则再看 5.9（真实 URL 规则、web 前端怎么拼的、踩坑），
动网格角标 / 实况照片播放（含 `videoUrl` 未验证这一点）则先看 5.10，
那里的坑都实际发生过（含尺寸换算、强调色、图标旋转与 `overflow: hidden` 裁切、
查看器跨页翻页的索引重算、`<video>` 重播必须换 key）；
动视频播放器（按键映射、状态条位置、进度/边界、倍速循环、清晰度为什么不在这里）则先看 5.11，
动首页大屏（尺寸、轮播、横向托盘滚动、背景图下载、为什么不压暗）则先看 **5.14**
（含「`scroll-view` 内容必须显式给宽」这个坑），
动导航栏（组件 props、悬浮层级 `z-index`、分区 / 设置跳转、为什么不能各页重写）则先看 **5.4**，
动设置页（3:7 布局、登录状态判定、退出登录二次确认）则先看 **5.15**
（**登录状态必须用 `user/info`，不要用 `getToken()` 非空**），
动搜索页 / 搜索接口 / `magic-search` 的解析 / 结果分批渲染则**必须先看 5.17**
（`5.17.2` 是本轮真实踩过的 8 个坑，`5.17.3` 是「返回结构未确认」时的容错策略，
尤其是「白底搜索框」「选中态图标变圆球」「3000 条一次性渲染」三条，重复犯错成本很高），
要一份「本轮做了什么 / 踩了什么 / 怎么验的」总览则看 **5.16**（首页大屏 / 导航栏 / 设置页那一轮），
动遥控器按键通道（`FnOsTvKeys` / `startTvKeyHook` / `stopTvKeyHook`）则**必须先看 5.12**
（那里写死了包装器为什么不许摘、退订为什么必须带 id）。

## 7. 版本规划与任务（按 1.0 / 2.0 / 3.0 划分，含验收标准）

> **版本口径（2026-11-20 用户确定）**：已经实现的功能一律归 **1.0**；**T5 遥控器操作方式重构与测试也归 1.0**
> （T5 曾是 1.0 的唯一剩余开发任务，**已于 2026-11-25 完成**）；除 T7 之外尚未实现的都归 **2.0**；**T7 单独归 3.0**。
> 下面每个标题后的 `v1.0` / `v2.0` / `v3.0` 就是该需求所属版本，迁移需求时**同时改表和标题**。

| 版本 | 包含需求 | 状态 |
| --- | --- | --- |
| **1.0** | T1 图片查看器、T2 视频与实况照片、T3 月份级时间轴跳转、T4.1 首页大屏展示、T4.2 相册功能、T4.3 设置页面（第一版）、T4.4 搜索功能、T4.6 视频清晰度、T4.8 HEVC 转 H.264、T4.9 登录设备展示、**T5 遥控器操作方式重构与测试** | ✅ **全部完成**（T5 于 2026-11-25 通过真机遥控器验收）；仅剩 T2 / T4.4 / T4.8 的验证尾巴 |
| **2.0** | T4.5 首页展示内容、T4.7 主题设置、T4.10 相册页顶部功能栏（人物 / 地点 / 标签 / 收藏 / 视频）、T4.11 首页轮播时托盘缩小 50%、T6 健壮性 | 未做（T6 部分完成） |
| **3.0** | T7 AI 评价照片 | 未做 |

### T1 图片查看器 ✅ 已完成（v1.0）
- 实现方式：**不新建页面**，`position: absolute` 的整页覆盖层（`.viewer`）；
  grid 的 `scroll-view` 不重建，关闭后滚动位置天然不丢。具体踩坑见 **5.7**。
  **2026-11 已迁入 `components/PhotoViewer.uvue`**（图库 / 相册内页共用，契约与导航分工见 5.13.4），
  本节与 5.7 保留「行为要求 + 踩坑」，函数名与常量名都没变（`<video id>` 改成 `pw-video`）。
- 入口：鼠标点任意格子（`onCellClick`）或遥控器 OK（打开 `indexAtScroll(currentScroll)` 那张，
  与时间轴高亮同一口径）。左右键切换，返回键关闭（OK 在普通照片上也是关闭，
  在实况照片上改为重播，见 5.10）。
- 左右切换以**全库下标** `viewerOffset` 为准（`photos` 只是分页窗口，`viewerIndex` 是窗口内下标）：
  翻到窗口边缘自动 `ensureLoaded()` → 更早用 `loadPrev`（插入头部，之后必须用 `target - startOffset`
  重算索引）、更新用 `loadMore`（追加，已有索引不变），因此可以从第一张连续翻到最后一张。
  连按只追最终目标（`viewerTarget / viewerMoving`），打开/关闭用 `viewerSession` 让在途循环失效。
- 界面没有顶栏/底栏，信息统一放「详情面板」：文件名、时间、分辨率、类型、ID。
  面板**在图片下方**（流式，宽 520rpx 居中），展开时 `.vw-stage` 让出高度、图片自动缩小，**不遮挡图片**；
  面板与图片之间只留 8rpx，展开/收起时图片尺寸平滑变化。
  TV 端按**下键**打开详情、详情展开时按**上键**收起（上键在收起状态下不动作），返回键始终关闭查看器；
  OK 键在普通照片上是关闭，在实况照片上改为「重播片段」——见 5.10。
  浏览器没有按键通道：右上角显示 `!`（切换详情）、`▶`（实况重播，仅实况显示）和 `✕`（关闭），
  整块用 `#ifdef WEB` 包裹。
- 出图顺序：先 `image(medium)`（与网格同一个 sUrl，通常已在本地缓存，近乎瞬开）→ 再异步
  `image(original)` 换原图；切换时**不清空 src**，新图就绪才替换，避免黑屏；预取左右相邻的 medium。
  用 `viewerSeq` 丢弃切换后迟到的回调。
- 新增 API：`FnOsLogin.image(path)` / UTS 的 `image(path)`（与 `thumb` 共用线程池与磁盘缓存，
  缓存文件名保留原图扩展名，避免 png/heic 被当成 .jpg）；`FnOsPhoto` 新增 `original` / `name`。
  原图字段按 `originalUrl → lUrl → mUrl` 退化（不同固件字段名不一致）。
- 详情 API：`photoDetail(id)` → `/p/api/v1/gallery/getOne?id=`，返回 `FnOsPhotoDetail`
  （文件名、路径、大小、分辨率、拍摄时间、相机、光圈/快门/ISO/焦距、经纬度、描述）。
  插件已把数值/字符串两种格式统一成展示文本；页面只按 `InfoRow[]` 渲染，空字段整行隐藏。
- 验收：从图库任意一张进入，左右各切 5 张不卡顿、无黑屏；返回后图库滚动位置不丢。

### T2 视频与实况照片（✅ 已实现，视频 2026-11-25 真机复验片尾/重播通过）（v1.0）
- ✅ 网格角标：`cat == "video"` → 「▶ 视频」；`live` 为真 → 「实况」（见 5.10）。
- ✅ 实况播放：查看器打开即自动播一遍（带声音，浏览器拦截则回退静音），播完回图片；TV 用 **OK** 重播、返回键关闭；
  浏览器右上角 `▶` 重播。片段地址取 `photo.video`（`additional.thumbnail.videoUrl`），
  App 端由 `FnOsLogin.video()` 下载到本地缓存，web 端走 `/nas` 同源播放。
- ✅ 视频文件播放器（细节与坑见 **5.11**）：**进入不自动播放**（按 OK 才挂载 `<video>`）、
  OK 播放/暂停（播完从 0 重播）、左右 ±10 秒、画面下方状态区（状态 + 图标 + 进度条 + `mm:ss / mm:ss`），
  连按累积 + 180ms 去抖，到开头/结尾只提示不越界；换片 / 关闭查看器都会卸载 `<video>`，不留后台声音。
- ❗改成**本机回环代理**（5.19）：App 端 `<video :header>` 真机不生效（实测 401，见 5.18.4），
  现在播放地址走 `mediaUrl()` → `http://127.0.0.1:<port>/v/<base64>`，由插件补 `AccessToken`、透传 `Range`；
  `@error` 时仍回退一次「下载到本地再播」。
- ✅ 倍速：`[2, 1.5, 1, 0.75, 0.5]`，与 fnOS web 端一致（`VideoContext.playbackRate`）。
- ✅ 清晰度：设置页五档（5.18 / 5.35.2），转码流同样经本机代理播（HLS 的 m3u8 与分片都会被代理重写）。
- ✅ 片尾与重播（5.38）：播到结尾由看门狗收尾 → 状态行「已结束 · OK 重播」，OK 从 0:00 重播
  （必须卸载 + 换 `<video>` 的 id + 重建，见 5.38.3）；2026-11-25 release 包连续三次重播验证通过。
- 验收：进入查看器**不自动播**、状态行显示「按 OK 开始播放」；OK 开始/暂停/重播；
  左右各按 1 次跳约 10 秒、连按 3 次跳约 30 秒且进度条跟手；开头按左、结尾按右只出提示；
  状态行点 1.0x（TV 按「上」）能循环换倍速（1.0 → 1.5 → 2 → 0.5 → 0.75 → 1.0，声音/进度明显变快变慢）；退出后不残留声音。

### T3 月份级时间轴跳转 ✅ 已完成（v1.0）
- `timeline()` 已同时回传 `years` 和 `months`（同一套累计逻辑，Kotlin 与 web 实现都要同步）。
- 右侧时间轴年/月全部平铺，`scroll-view` 可滚，高亮跟随并在切换时自动居中（样式与尺寸见 5.3）。
- 跳转策略：列表**直接从目标 offset 开始**（不预载）→ `fillViewport()` 补满一屏 →
  按 `head.dt` 设高亮 → 落点按 `maxTop` 钳制并用 `pinnedTop / pinGuard` 锁住高亮；
  向上浏览靠 `loadPrev`（顶部留白 `prev-gap` 提供可滚余量，补偿用 microtask）。

### T4 功能需求（v1.0 已实现 / v2.0 待做）

> 原「屏保/待机」需求已删除，不再做。实现前先按 §5 第 2 条的
> 「Kotlin → `interface.uts` → `app-android/index.uts` → 页面」顺序加接口，
> 页面尺寸/按键/滚动一律沿用现有的 5.2 / 5.3 / 5.12 约定。
>
> **编号变更（2026-11）**：原 T4.5「遥控器操作方式重构和测试」→ **T5**；
> 原 T5「健壮性」→ **T6**；原 T4.6「HEVC 转 H.264」→ **T4.8**（T4.5~T4.7 让给三项新需求）；
> 新增 **T7「AI 评价照片」**；2026-11-19 追加 **T4.11「首页自动轮播时底部图片列表缩小 50%」**。
>
> **版本归属（2026-11-20）**：T4.1 / T4.2 / T4.3 / T4.4 / T4.6 / T4.8 / T4.9 已实现 → **v1.0**；
> T4.5 / T4.7 / T4.10 / T4.11 待做 → **v2.0**（T4.10 的五个子需求各自独立交付，但都属 v2.0）。

#### T4.1 首页大屏展示 ✅ 已完成（v1.0；2026-11，浏览器已验，详见 5.14）
- 与原设想的差异：数据源**不是** `timeline()` + `gallery/getList`，而是用户指定的
  `ai-smart-rec/categories?mainCategory=scene`（54 个智能场景，海报由 `poster` 拼流地址），
  新增插件 API `smartCategories()`（§3 有接口事实，§5.14 有实现要点与踩坑）。
- 落地方式：`pages/home/home.uvue`，登录成功后 `reLaunch` 进来；全屏场景海报 + 右上实时时钟 +
  底部横向卡片托盘（`scroll-view`，10 秒自动切下一个，`fnos.home.interval` 可覆盖间隔）。
- 按键：左右切场景并重置计时、上键进「图库 / 相册」入口焦点、返回回连接页；
  首页 → 图库 / 相册用 `redirectTo`，图库 / 相册列表的返回键已改为回首页。
- **未做**：卡片点击查看该场景的照片列表 / 详情（用户明确本轮不做，点击只做选中）。

#### T4.2 相册功能 ✅ 已完成（v1.0；2026-11，浏览器已验，详见 5.13）
- 落地方式：`pages/album/album.uvue`（列表）+ `pages/album/detail.uvue`（内页），
  导航栏「相册」标签由占位改为 `redirectTo` 真实页面；入口、尺寸、排序、角标、踩坑全部写在 **5.13**。
- 与最初设想的差异：内页**没有**复用图库的右侧时间轴（用户未要求），但查看器抽成了
  `components/PhotoViewer.uvue`，图库与相册内页现在是同一份实现（原 `library.uvue` 内联的
  约 1400 行查看器代码已删除）。
- 接口：`album/list` / `album/item` / `album/photos`（**相册内页不能用 `album/normal/getList`**，
  那个接口没有缩略图与 `isLive`，见 §3 与 5.13.5）。
- 验收现状：浏览器全流程通过；返回键逐级退回（内页 → 列表 → 图库/设置）与遥控器路径待真机复验
  （真机遥控器当前异常，用户暂缓）。

#### T4.3 设置页面 ✅ 第一版已完成（v1.0；2026-11，浏览器已验，详见 5.15）
- 落地方式：`pages/settings/settings.uvue`，左右 3:7（左选项 / 右内容），顶部沿用 NavBar。
- 导航栏「设置」三页都改为 `navigateTo` 进本页（不再回连接页）；`skipAuto` 只在「退出登录」时置，
  退出后 `reLaunch` 回连接页且不会自动重登。返回键逐级退回（右栏 → 左栏 → 上一页）。
- 已做：登录状态（服务器 IP / 端口 / 账号 / 密码脱敏 / HTTPS / 当前状态 / 退出登录，退出带二次确认）。
- **第一版只做占位**：首页展示设置（自动切换间隔）、视频清晰度（`video/decode/*`）、主题设置，
  右栏只显示「暂未开放 + 说明」，不做假控件。**视频清晰度已在 T4.6 落地（见 5.18）**，只剩两项占位。
- 后续：占位项落地时，storage 只存字符串、读端按枚举比较（见 5.2）；
  清晰度切换要真机验证 `video/decode/*` 的 POST 签名（同 5.11 待验事项）。

#### T4.4 搜索功能 ✅ 已实现（v1.0）
- ✅ **已实现**（2026-11，用户已确认「功能没问题了」）：`pages/search/search.uvue` + 插件 `search()`，
  改动清单 / 坑 / 解析策略 / 验证手段全在 **5.17**（`5.17.1` 改动清单、`5.17.2` 八个坑、`5.17.3` 容错解析）。
- 入口：四页导航栏的「搜索」→ `navigateTo('/pages/search/search')`（不再是空实现）。
- 接口：`POST /p/api/v1/magic-search/do`，请求体 `{"keyword":"海边","antiFilters":[]}`（§3）。
  ⚠️ **返回结构还没在真机确认**（插件按容错解析，见 5.17.3 与 §3）。确认方法：
  `FNOS_USER=xxx FNOS_PASS=yyy node tools/fnos-search.mjs 海边`，
  脚本会打印登录 → POST → 返回里各层数组/对象的键；把真实键结构补进 §3 的 `magic-search/do` 行，
  再核对 `FnOsLogin.searchItem` 里的字段名容错是否需要补。
- UI：导航栏照常显示 + 「搜索」选中态；深色搜索框（放大镜 / 输入 / ×）；结果一排 8 个正方形缩略图，
  角标区分 视频（带时长）/ 实况；点结果进 `PhotoViewer`。
- 交互：× 有内容先清空、没内容关闭；**返回键无视内容直接关闭**；清空回到初始态（不自动跳回）。
- 性能：3000+ 结果**必须**分批渲染（`RENDER_CHUNK = 80` + `@scrolltolower` 追加），别改回一次性 `v-for` 全量。
- 已知未做：搜索结果分页（接口是否支持 `offset/limit` 未确认）、结果网格没有时间轴。
  （原「导航栏入口不能用遥控器聚焦」已随 T5 收口补上，见 5.25 与 5.17.5。）
- 待验：真机软键盘（`<input :focus>` 是否自动弹）、遥控器在「输入框 ↔ 网格」之间的焦点切换、
  搜索结果里视频 / 实况的播放。
- 验收：输入关键字能出结果、结果网格可滚动可进查看器；清空/返回键回到进入前的页面。

#### T4.5 首页展示内容（数据源 / 循环 / 展示方式 / 展示时间 / 休眠）— v2.0 未做

现状（5.14）：首页只轮播 `smartCategories()` 的 54 个智能场景，数据源写死，间隔读 `fnos.home.interval`。
本轮把它做成**可切换的数据源**，并加循环方式、照片展示方式、展示时间、是否允许电视休眠四项选项。

- **四种数据源**（首页 hero + 底部托盘 + 背景海报一起跟着变）：
  1. **推荐场景** —— 已实现，`ai-smart-rec/categories?mainCategory=scene`（§3），保持原样；
  2. **相册** —— 在设置页勾选若干相册（数据来自 `album/list`，见 §3），首页轮播这些相册里的照片：
     数据用 `album/photos`（`album_id` + 分页），海报取列表项的 `additional.thumbnail.mUrl`（或相册
     `posterUrl` 换 `m` 尺寸）；**不要**用 `album/normal/getList`（没有缩略图，见 5.13.5）；
  3. **标签** —— 输入标签后轮播该标签下的照片。**接口未确认，不许猜**：先确认 `ai-smart-rec/*`
     能不能按 label / tag 拉照片，没有再找别的端点；确认后按标准步骤加 API（不要全量拉回前端过滤）；
  4. **搜索** —— 输入关键词后轮播搜索结果，**与 T4.4 共用同一个搜索接口**，不要另写一套。
- **循环方式**：`顺序循环` / `随机循环`。两者都只在**前面筛出来的照片集合内部**循环，
  不重新请求、不重新筛选（用户原话：「循环方式是在前面筛选出来的照片里面循环」）。
  随机用「洗牌后按序播、播完再洗」，不要直接 `Math.random` —— 连续抽到同一张看起来像卡住。
- **照片展示方式**（三选一，**只作用于首页背景 / hero 那张大图**，底部托盘卡片永远是正方形
  `aspectFill`（5.14），不要跟着变）：
  1. **沉浸模式** —— 同一张图两层：底层 `aspectFill` 放大铺满当背景（两侧自然被填满），
     上层 `aspectFit` 居中完整显示主体；「模糊」在 uvue 里**没有 `backdrop-filter` / `filter: blur`**，
     不要写滤镜（同 5.14 的 `text-shadow` 结论），用「放大铺满 + 半透明深色遮罩」近似，别指望真模糊；
  2. **完整模式** —— `aspectFit` 等比缩放到屏幕内，**不裁切**，两侧（或上下）留主题底色；
     竖图/超宽图也必须在屏内看全，不许偷偷改成 `aspectFill`；
  3. **裁剪模式** —— `aspectFill` 铺满全屏，可能裁掉边缘（现在的首页就是这个效果，可作为默认）。
  三种都用「同一个 `<image>` + `resizeMode` 切换」实现，不要为每种模式各写一份海报模板。
- **展示时间**（每张停留多久）：只给 **5 秒 / 10 秒 / 30 秒** 三档（用户明确只要这三个，不要做自定义输入）。
  沿用已有的 `fnos.home.interval`（秒的字符串，缺省 10，见 5.14），读到 5 / 10 / 30 以外的值一律回落 10。
  改完必须**立刻生效**：`home.uvue` 现在只在 `onLoad` 读一次 `intervalSec`，
  要改成 `onShow` 也重新读一遍并 `startAuto()`，否则从设置页返回后还是旧间隔。
- **是否允许电视休眠**：设置项是一个开关「允许电视休眠」，**默认关闭（= 阻止休眠，照片墙常亮不黑）**，
  存 `fnos.home.allowSleep`（字符串 `'true'` / `'false'`，见 5.2）。
  实现：关闭休眠用的就是 Android 的 `FLAG_KEEP_SCREEN_ON` —— 当前 HBuilderX 的 app-android 类型里
  **搜不到 `uni.setKeepScreenOn`**，不要凭记忆硬写这个 API；按 §5 第 2 条的步骤在插件里加一个
  `setKeepScreenOn(on)`（放 `FnOsTvKeys.kt` 同层，例如 `FnOsScreen.kt`，用
  `UTSAndroid.getUniActivity()` 拿 Activity：`window.addFlags(FLAG_KEEP_SCREEN_ON)` / `clearFlags`）。
  **只在首页生效**：`onShow` 按开关加 flag、`onHide` / `onUnload` 无条件清除，
  别让图库 / 设置页也一直常亮；Activity 重建（`reLaunch` / 旋转）后要重新加。
  web 端不实现，开关只存值并在说明里注明「仅 App 生效」（`FLAG_KEEP_SCREEN_ON` 与浏览器无关）。
  注意 `FLAG_KEEP_SCREEN_ON` 只能挡住系统休眠 / 锁屏，挡不住电视盒子自己的屏保或 HDMI-CEC 关机，
  文案别承诺「永不待机」。
- **存储**（一律字符串，读端按枚举字面量比较，见 5.2）：
  `fnos.home.source`（`scene` / `album` / `tag` / `search`）、`fnos.home.loop`（`seq` / `random`）、
  `fnos.home.fit`（`immersive` / `contain` / `cover`）、`fnos.home.albumIds`（逗号分隔）、
  `fnos.home.tag`、`fnos.home.keyword`、`fnos.home.interval`（`5` / `10` / `30`）、
  `fnos.home.allowSleep`（`true` / `false`，缺省 `false` = 阻止休眠）。
  非法值一律回落默认（`scene` / `seq` / `cover` / `10` / `false`）。
- **设置页落地**：把 T4.3 的「首页展示设置」占位换成真控件（数据源选择 + 相册多选 + 标签/关键词输入 +
  循环方式 + 展示方式 + 展示时间 5/10/30 秒 + 「允许电视休眠」开关）。**不要留假控件**，
  没做完的那一项只显示文案。
- **首页侧**：切数据源要重置计时并重新预取下一张，`bgToken` 丢弃迟到回调的机制照旧（5.14）；
  托盘卡片数量变化后必须重算 `innerStyle` 的显式宽度，否则 `scroll-left` 又会被钳到 0。
- **展示方式只切 hero 层**：`immersive` 的底层背景 + 前景图要复用同一份 `bgSrc`（只下载一次，
  别为了两层各下一次海报）；切模式**不要重新拉图、不要重置轮播计时**；
  `contain` 下两侧露出的底色取主题 token（T4.7），不要写死 `#000`，否则白昼主题下会一条黑边。
- **常亮的生命周期**：`FLAG_KEEP_SCREEN_ON` 只在首页 `onShow` 加、`onHide` / `onUnload` 清，
  并且 `onShow` 里要重新读一次 `fnos.home.allowSleep`（用户在设置页改完返回就生效）；
  不要在 `App.uvue` 或插件初始化时全局加，否则图库 / 设置页也会一直亮着耗电/烧屏。
- **TV 输入**：标签 / 关键词要呼出软键盘，遥控器负责移动与确认（同 T4.4）；输入框清空即视为回推荐场景。
- **空数据 / 失败**：相册没选、标签无匹配、搜索 0 条、接口失败时 hero 显示一行提示，**不黑屏、不崩**；
  也不要偷偷切回推荐场景（用户会以为设置没生效）。
- 验收：四种数据源各切一次，hero / 托盘 / 海报都跟着变；顺序循环一轮播完才回绕；
  随机循环连播 20 次无连续重复、一轮播完前不重复同一张；设置页改完回首页立即生效（不用重登、不用重跑基座）；
  三种展示方式各切一次，用**横图 / 竖图各一张**验：沉浸模式两侧有内容不是黑边、完整模式整张图看全
  （不裁切）、裁剪模式铺满全屏边缘可被裁；切模式不重新下载海报、不重置轮播计时，托盘卡片始终是正方形；
  展示时间 5 / 10 / 30 秒各验一次（切页间隔确实变了，且**从设置页返回立即生效**，不用重进首页）；
  「允许电视休眠」关闭时首页挂 30 分钟不黑屏不休眠、切到图库 / 设置后屏幕恢复系统休眠策略
  （`adb shell dumpsys window | Select-String KEEP_SCREEN_ON` 可查 flag 是否还在），
  打开开关后首页正常黑屏、flag 已清除；
  数据源为空或断网时有提示且仍能返回设置页。

#### T4.6 视频清晰度（原图 / NAS转码原画 / 1080P / 标清 / 低清）✅ 已完成并真机验收（v1.0；2026-11-15 三档 → 2026-11-24 五档，详见 5.18 + 5.19 + 5.35.2）

现状（5.11）：播放器里的清晰度控件已撤掉，视频固定播原画；`video/decode/info|play|stream` 的
插件接口已实现并保留。本轮把它做进**设置页**。

**落地结果**：`common/video.uts`（档位定义）+ 设置页右栏三档单选（`focusRow` 支持遥控器上下选）
+ 查看器按档位取流（原图直链 / 标清＝720 分辨率内最小码率 / 低清＝全局最小码率，懒申请、9 秒超时、
失败回退原画）+ **所有播放地址经本机回环代理**（`<video :header>` 真机无效，见 5.18.4 / 5.19）。
**默认档位按用户要求改成「标清」**（原稿写的是原图）。真机三档肉眼可辨、可拖进度。
⚠️ **浏览器里三档永远一样**（web 端只播原画，见 5.18.3），验这条必须上 App 端。

- 三档：**原图** / **标清** / **低清**（走服务端转码 HLS）；默认 `sd`（标清）。
  设置页右栏是单选行，存储 `fnos.video.quality`（`original` / `sd` / `low`，字符串，见 5.2）。
- 映射：非「原图」时按 OK 懒申请 `video/decode/play`（`videoEncoder = h264`）→ `video/decode/stream` +
  `playLink`（m3u8；**是路径不是查询串**，直拼在 `decode/stream` 后面），再经 `mediaUrl()` 代理播放。
  `videoEncoder` / `audioEncoder` / `channels` 的合法取值**照抄 NAS 前端 bundle 实参，不许猜**（同 T4.8）。
- 挑档规则（两次用户反馈迭代出来的，别改回去）：**同一分辨率优先取最小码率**——低清取全局最小
  （360p/200k），标清取 720 内最小（720p/1000k）。取「第一个命中项」会拿到最高码率，看着跟原画一样。
- 与 HEVC 的关系：档位是「用户想要的画质」，T4.8 是「播不了时的兜底」。档位设成**原图**但源是 HEVC、
  直链解码失败时，仍按 T4.8 的顺序降级一次，不要因为档位是原图就直接报错。
- 生效时机：改完对**下一张视频**生效；正在播的不要中途重启（状态行提示「重进生效」即可）。
- 失败与超时：转码准备期间状态行「转码中…」，超时（8~10s）放弃并提示，**不要一直转圈**；
  关查看器 / 换片要 `stopVideo()` 卸载，不留后台声音（5.11）。
- 缓存：转码流地址是临时的，**不要**当成本地缓存 key；只有下载回退的文件才进 `video()` 磁盘缓存。
- web 端不做：浏览器要 hls.js，而 web 端只调 UI —— `utssdk/web` 的 `videoInfo` / `videoPlay`
  继续返回明确错误；设置页可以显示三档，但要注明「仅 App 生效」。
- 验收（2026-11-15 真机通过）：同一视频三档各播一次，清晰度肉眼可辨（原画 ≫ 720p/1000k > 360p/200k）
  且都能拖进度；改档不需重启基座；重启 App 后档位保持；「原图」档播 h264 时日志里**没有**转码请求；
  NAS 拒绝转码 / 签名 5000 时提示明确、OK 仍能关掉查看器；查看器面板里**仍然只有倍速**。

#### T4.7 主题设置（暗夜 / 白昼 / 飞牛）— v2.0 未做

现状：所有页面的颜色都写死在 `.uvue` 样式里（深色底 + `#3388FF` 蓝），没有主题这一层。

- 三套：**暗夜主题**（黑，即当前现状，默认）/ **白昼主题**（白底深字，用户原话「摆设」，优先级最低，
  先把底色 / 文字 / 导航栏换过来即可，细节不强求）/ **飞牛主题**（蓝）。
  存储 `fnos.theme`（`dark` / `light` / `fnos`），非法值一律回落 `dark`。
- 落地方式：新建 `common/theme.uts`，导出主题 token（`bg` / `surface` / `surfaceAlt` / `text` / `textSub` /
  `accent` / `accentText` / `border` / `scrim`）与 `getTheme(name)`；页面**不再写字面色值**，
  统一从 token 取值（`:style` 内联或 computed class）。
  **不要依赖 CSS 变量 `var(--x)`** —— uvue 样式是 CSS 子集，先在 web 端验一遍；用不了就退回
  「UTS 常量 + 内联 style」，别为了优雅写一堆编译不过的样式。
- 覆盖范围：`pages/index`（连接页）/ `home` / `library` / `album` / `album/detail` / `settings` +
  `components/NavBar` / `PhotoViewer`。**查看器遮罩保持黑底**（照片要看清），只换面板与文字颜色。
- 生效时机：设置页点一下立刻生效，返回其它页也是新主题；只存字符串，读端按枚举比较（5.2）。
- 与首页的关系：白昼 / 飞牛主题下首页背景仍是全屏照片，`bg-dim` 继续不要加（5.14），
  只调 `.brand` / `.hero` / `.tray` 的底衬透明度与文字色，保证压在白照片上也看得清。
- 验收：三套主题下逐个看一遍首页 / 图库 / 相册 / 相册内页 / 设置 / 连接页 / 查看器，
  不出现「白底白字」「黑底黑字」这类看不清的组合；切换不用重登、不用重启；重启 App 后保持。

#### T4.8 HEVC（H.265）转 H.264 ✅ 已实现（v1.0；2026-11-19，**兜底链未在真机触发**，详见 5.21 + 5.21.1）

> 状态：功能与设置项都已落地并在真机装上跑过，但测试机**支持 HEVC 硬解**，原图档直接出画面、
> `@error` 不触发，所以「原画失败 → 转 H.264」这条链**一次都没跑过**（未验证，不是已验证）。
> 复验方法见 **5.21.1**（最省事：临时把原画直链改成一个必失败的地址，验完还原）。

**落地结果**（用户口径：做进设置页、开启后全局有效、**默认关闭**）：
`common/video.uts` 加开关（`fnos.video.hevc` = `on`/`off`，缺省 `off`）+ 设置页「视频清晰度」栏第 4 行开关
（遥控器 OK / 点击都能切，立刻写 storage）+ 查看器每次打开视频读一次。
开着时原画 `@error` 走 `video/decode/play`（`videoEncoder=h264`、`startPosition=当前进度`）→ `decode/stream` HLS
→ `mediaUrl()` 代理播放；失败就「该视频编码不支持」+ 下载原文件兜底；关着时行为与以前完全一致。
**无法从接口判断源编码**（`decode/info`、`getList`、`photoDetail` 都没有 codec 字段），所以只做「播放失败即尝试转码」，
h264 源仍然不发任何转码请求。

- 背景：现在的播放链路是「`mediaUrl()` 本机代理 → 原画 / 转码 HLS」（见 5.19），原画若是 **HEVC/H.265**，
  TV 盒子/老设备不支持硬解时只有声音没有画面（或直接 `@error`）；而当前的失败回退只是
  「下载同一个文件到本地再播」，编码没变，照样播不了。
- 方案优先级（同一条链路里按序降级，每级只试一次）：
  1. **原画**（现有路径，h264 源走这条，不触发转码；同样经本机代理）；
  2. **服务端转码流**：`video/decode/play`（body 里 `videoEncoder` 指定 **h264**）
     → `video/decode/stream` + `playLink`（HLS / m3u8，**经本机代理**播，代理会重写分片行），
     用 `startPosition` 从当前进度续播；
  3. **下载到本地再播**（仅作为网络/直链问题的兜底，不再当作 HEVC 的解法）。
- 需先确认、**不许猜**：`videoEncoder` / `audioEncoder` / `channels` 的合法取值（照抄 NAS 前端 bundle 的
  实际传参）、`decode/info` 里能否看出源编码、`gallery/getList` 项或 `photoDetail` 里有没有 codec 字段
  （没有就走「播放失败即尝试转码」的兜底，不做启发式猜测）。
- 判定与提示：转码准备期间状态行显示「转码中…」，超时（建议 8~10s）放弃并提示
  「该视频编码不支持」，不要一直转圈；转码流播完/切片/关查看器都要 `stopVideo()` 卸载，不留后台声音。
- 缓存：转码流的 m3u8 地址可能是临时的，**不要**把 playLink 当成本地缓存 key；
  只有本地下载的回退文件才进 `video()` 的磁盘缓存。
- web 端不做：浏览器播 HLS 需要 hls.js，而 web 端只用来调 UI；`utssdk/web` 的
  `videoInfo` / `videoPlay` 继续返回明确错误，不要假装可用。
- 验收：真机点开一个 HEVC 视频能出画面并能拖动进度；h264 视频仍走原画、日志里不出现转码请求；
  连续切换 HEVC / h264 各 5 次不卡死、不残留声音；无转码能力（NAS 拒绝 / 签名 5000）时提示明确且
  OK 仍能关闭查看器；`video/decode/*` 的 POST 签名在真机确认可用（这一条同时关掉 5.11 的待验项）。

#### T4.9 飞牛「登录设备管理」显示正常（设备名 / 系统 / 图标）— 已实现（v1.0；2026-11-19）

**需求（用户原话逐轮收敛）**：
1. 设备管理里不要再是「未知设备」；
2. 设备应该是「安卓电视 / TV」；
3. 参考飞牛手机 App 的展示（`Xiaomi 24031PN0DC` + `Android 16`）：本客户端要显示
   **大标题「NAS Photo TV」+ 小标题「TV」**；
4. web dev server 那条与真机完全同名，**不要** `(web)` 后缀。

**问题**：原记录显示「未知设备」，副行只有「NAS Photo TV 登录于 …」，图标是通用设备图标。

**排查过程（可复现，不要重新猜）**：
1. `curl http://<nas>:5666/` 拿 HTML → 主 bundle `/assets/index-D1auxRov.js`；
2. 登录帧在主 bundle：`OX()` 返回 `{deviceType: 'Browser', deviceName: '<system>-<browserName>'}`，
   `user.login` / `user.tokenLogin` 只带 `deviceType` + `deviceName` + `did` —— **没有** `osName` / `deviceModel` / `clientName`；
3. 设备管理页是懒加载 chunk `MyAccount-BoSc7aKC.js`：
   - `It({equipment})` 渲染一条记录：`{name, type} = Pe(deviceName)`，标题 = `name`（**第二段**），
     副行 = `type`（**第一段**）+ 「登录于 …」；
   - `Pe`（= 主 bundle 的 `aKe`）：`deviceName.split('-')`，第二段为空就返回 i18n `device.unknownDevice` →
     这就是「未知设备」的唯一成因；
   - 图标只有三档：`deviceType === 'Browser' ? 浏览器图标 : deviceType === 'PC' ? 电脑图标 : 手机图标(pCe)`，
     手机图标的 SVG 是竖屏圆角矩形 + 底部圆点 —— **NAS 前端没有电视图标**，传 `TV` / `AndroidTV` 都一样；
4. 「授权设备」列表（另一套 UI）用正则从 `client_type/client_name/device_name/device_os` 推断
   `Agent / Browser / PC / Mobile`，同样没有 TV。

**结论**：`deviceName` 是唯一能表达身份的地方，格式必须 `<类型>-<客户端>`：
第一段进副行（本端写 `TV`），第二段进大标题（`NAS Photo TV`）。`deviceType` 只影响图标，`Mobile` 是合法枚举。

**修改（只动展示字段；`SALT` / `SECRET` / 信封 / 签名顺序 / `stay` / `isTrustedDevice` / `did` 全未动）**：
- `FnOsLogin.kt`：`CLIENT_NAME = "NAS Photo TV"`、`DEVICE_TYPE = "Mobile"`、
  `clientDeviceName() = "TV-" + CLIENT_NAME`；`user.login` 与 `user.2fa.loginVerify` 两处都用它
  （原来是 `"AndroidTV"` + `"NAS Photo TV"`）。
- `tools/fnos-login.mjs`：`deviceType` 改 `Browser`，默认 `deviceName = 'TV-NAS Photo TV'`（与真机同名、无后缀）。
- `tools/fnos-probe.mjs`：同步 `TV-NAS Photo TV (probe)`（脚本专用后缀，方便和真机记录区分）。
- 页面 / 组件不用改：展示完全由 NAS 前端根据登录帧字段渲染。

**踩坑（都实测过）**：

| 坑 | 现象 | 正确做法 |
| --- | --- | --- |
| 同一 `did` 的旧记录不刷新 `deviceName` | 改完重新登录，页面仍是「未知设备」+ 旧名字 | 先在设备管理里**删掉旧记录**再登录；`did` 不用换，2FA 不会重新要动态码 |
| dev server 那条看起来「自动正常」 | web 记录改名立刻生效，真机不变 | `webDid` 每个 dev server 进程重新随机 → 每次都是新记录；真机 `did` 持久化 |
| `deviceName` 里带 `-` | 第二段被截断，只剩前半截 | 客户端名里不要出现 `-`（拆包只取前两段） |
| 分不清「旧包」还是「NAS 不刷新」 | 改完没变化，怀疑没编译 | 看 `unpackage/dist/dev/app-android/uni_modules/fnos-auth/utssdk/app-android/FnOsLogin.kt` 的内容/时间；改 Kotlin 必须重新运行基座 |
| 想程序化读设备列表 | `user.listLoginDevice` / `user.info` 在 main WS 恒 `errno 65534`（明文 / HMAC / 加密 / `tokenLogin` / `longToken` 全试过），HTTP 也没有该路由，只有 `user.active` 返 succ | 只能人工在网页端核对；不要再为读这个接口折腾帧格式 |
| 想要电视图标 | `deviceType: 'TV'` 仍是手机图标 | NAS 前端硬编码三档，无解；身份靠 `deviceName` 第一段 `TV` 表达 |

**复验**：
1. `FNOS_USER=xxx FNOS_PASS=yyy node tools/fnos-probe.mjs` → 登录 / stat / timeline / getList / album / 缩略图鉴权与 §2 基线一致；
2. 真机：重新「运行到 Android App 基座」→ 删除旧记录 → 重新登录 →
   记录显示「**NAS Photo TV**」+「**TV 登录于 …**」；
3. web：改了 `fnos-login.mjs` 必须**重启 dev server** → 浏览器重新登录 → 同名记录（也要删掉旧的 `(web)` 记录）；
4. 不要在代码里偷偷换 `did`：那会让 2FA 重新进入「信任设备」流程，属于产品取舍。

#### T4.10 相册页顶部功能栏（人物 / 地点 / 标签 / 收藏 / 视频）— v2.0 未做

> 用户明确：这 5 个是**五个独立需求**，各自确认接口、各自验收，可以分批交付；但顶上那排入口一次做齐。

现状：`pages/album/album.uvue` 的结构是 `NavBar` → `.nav-spacer`（60 + 8rpx）→ `.topbar`
（`totalText` =「共 54 个相册」+ 错误提示）→ 封面网格。用户截图里的红框就是 `.nav-spacer` 与 `.topbar` 之间那块空白。

- **位置与视觉**：在 `.nav-spacer` 之后、`.topbar` 之前插入一行横向胶囊（人物 / 地点 / 标签 / 收藏 / 视频），
  风格沿用 NavBar 的胶囊（半透明底、选中 `#3388FF` 蓝底白字），整行高度 **≤ 44rpx**，
  别把首屏卡片行挤掉。**不要**把它塞进 `components/NavBar.uvue`（那是四页共用组件，别为一页加专用入口）。
- **交互**：默认高亮「全部相册」= 现状；遥控器左/右在这排标签间移焦点、OK 进入对应功能；
  这排的 `focusIndex` 与网格焦点是两套状态，进分类内容后焦点落在内容区；
  返回键**先回这排标签（回到全部相册）再退出页面**，层级不能乱。鼠标点击也走同一套入口函数。
- 五个功能各自独立（未做的显示「暂未开放」文案，**不要**做假页面）。下面每条都要**先抓真实请求确认接口，
  不许猜**；确认后按 §3 的表格格式把接口事实补进 §3，别让后来的人再猜一遍。

##### T4.10.1 人物
- 期望：列出 NAS 的人物聚类（头像 + 名字 + 照片数），点进去看这个人的照片网格。
- 待确认：§3 已注明 `ai-smart-rec/categories` 的 `mainCategory` 换成别的值可拿「人物 / 地点」等，
  先确认人物的**确切枚举值与返回结构**（是否带封面 `poster`、是否有人名/人物 id）；人物详情与照片列表用哪个端点。

##### T4.10.2 地点
- 期望：地点列表（城市 / 景区），点进去看该地点的照片。
- 待确认：`mainCategory` 的地点枚举与返回结构；是否需要经纬度。**地图不做**（TV 端先做列表）。

##### T4.10.3 标签
- 期望：标签列表 + 每个标签下的照片网格。
- 待确认：标签列表端点、按标签拉照片的参数（`label` / `tag` / `labelId`？），以及标签是 NAS 自动生成
  还是用户手动打的。**与 T4.5 的「标签」数据源、T4.4 搜索可能是同一套后端能力**，先确认再决定共用哪个 API，
  不要写两份。

##### T4.10.4 收藏
- 期望：收藏的照片（或相册）列表，能直接进查看器。
- 待确认：收藏状态字段（`gallery/getList` 项里有没有 `isFavorite`？`album/list` 没有收藏字段）、
  收藏列表端点（`/favorite/*`？）。**电视端先只读**，加/取消收藏用户没提，别顺手做。

##### T4.10.5 视频
- 期望：全库视频列表（封面 + 时长角标 + 「▶ 视频」），点开进 `PhotoViewer` 播放（复用 5.11 的播放链路）。
- 待确认：`gallery/getList` 有没有类型过滤参数（`mode` / `type` / `category`？）；
  若**只能**全量拉回来前端过滤，先问用户 —— 7 万多条不能全量（§3 数据规模）。

##### 共用约束与验收
- 新接口一律按 §5 第 2 条的步骤（`FnOsLogin.kt` → `interface.uts` → `app-android/index.uts` → 页面），
  `utssdk/web` 同步实现或返回明确错误（不要假装可用）。
- **不要新建第二套网格**：分类内容复用图库网格 / `PhotoViewer`，或把网格抽成公共件；
  分页沿用「本页条数 < limit 即结束」（§3），别依赖 `hasNext`。
- 空结果、接口 404 / 未实现、断网都要有明确文案（口径同 5.15 的「暂未开放」），不许白屏。
- 验收：五个 tab 都能聚焦、能进入、能返回且层级正确（分类内容 → 相册列表 → 上一页）；
  已交付的功能能出图、能连续分页、能进查看器（视频能播）；
  未交付的显示占位文案而不是空页；这排左右切焦点时**网格不跟着滚**；真机 + 浏览器各过一遍。

#### T4.11 首页自动轮播时底部图片列表缩小 50% — v2.0 未做（新需求，2026-11-19）

用户原话：「首页轮播图在进入自动轮播后下面图片列表缩小 50%」。
即首页进入**自动轮播状态**（5.14 的 `startAuto()` 在跑）后，底部横向卡片托盘（图片列表）
按当前尺寸的 **50%** 显示，把视觉焦点让给 hero 大图。

- **动手前先问用户**（不要自己拍板）：① 是「一进入自动轮播就缩小并一直保持」，还是「每次换图时缩小、换完恢复」；
  ② 遥控器左右 / 点击卡片这类手动操作算不算「退出自动轮播」，要不要临时恢复原尺寸；
  ③ 恢复原尺寸的触发点（手动操作 / 停止轮播 / 离开页面）。
- 实现约束：
  - 托盘尺寸全部由脚本常量 + 三处样式决定（见 5.14 与 §5 第 4 条）。缩小要同步改
    `CARD_RPX` / `GAP_RPX` / `TRAY_PAD_RPX`（必要时含 `TRAY_MARGIN_RPX`）**以及** `.card` / `.tray-scroll` /
    `.tray-inner` 的 rpx 值；只改样式不改常量会让居中偏移、命中区、`scroll-left` 一起错位。
  - 卡片宽度变了就必须重算 `innerStyle` 的显式宽度、居中偏移与 `scroll-left`（5.14「横向滚动必须给内容显式宽度」）。
  - 用「两套尺寸常量 + 一个自动轮播状态位」切换，**不要用 `transform: scale` 缩**：
    scale 不改变布局尺寸，`scroll-view` 的滚动宽度与居中计算会和视觉不一致，图片还会发糊。
  - 缩小 / 恢复**不重新下载海报、不重置轮播计时**（`bgToken` 机制照旧），托盘卡片仍是正方形 `aspectFill`。
- 验收：自动轮播开始后托盘卡片明显变为一半且仍可横向滚动、选中项居中；恢复后回到原尺寸；
  遥控器左右切卡片不跳位、不闪；真机 + 浏览器各过一遍（真机优先，缩放与滚动最容易在这里露问题）。

### T5 遥控器操作方式重构和测试（原 T4.5）— ✅ 已完成（v1.0，2026-11-25 用户确认）

> **状态**：需求范围全部落地，并已在真机遥控器上通过验收（用户 2026-11-25：「T5 的需求已经做完了」）。
> 分 6 步做完，每一步的现场证据都在对应小节：统一按键定义 + `dispatchKey` 层级栈（**5.22**）
> → 返回键被处理两遍（**5.23**）→ 光标模型（**5.24**）→ 导航栏纳入焦点链（**5.25**）
> → 电视首次实测「订阅 id 撞车」（**5.29**）→ 电视第三轮六条反馈（**5.30**）。
>
> 「真机遥控器异常」曾经是 1.0 的发布阻塞项（用户当时说「先不管」），**现在已解决**：
> 根因是 `stopTvKeyHook` 的订阅 id 撞车 + `FnOsTvKeys` 包装器被摘掉，见 5.29 / 5.12。

- **历史现状（2026-11，仅作背景）**：真机按键曾全线失效，只能在浏览器用鼠标验；
  查看器打开期间按键由 `components/PhotoViewer.uvue` **独占**（见 5.13.4 与 5.12 调用点表），
  重构时把「组件内 `handleKey`」一起纳入了分发层，不只是页面。
- ✅ 目标（已达成）：把「每个页面各自写 `handleKey` 分支」收敛成**一套可复用的按键分发层**：
  统一 `handleKey(code)` 入口 + 焦点/层级栈（页面 → 覆盖层 → 查看器 → 详情面板），
  按键按栈顶优先消费，`false` 时再向下传递。
- ✅ 约束（已遵守）：**保留 5.12 的通道机制**（包装器常驻、`stopTvKeyHook(id)` 带 id、
  `onLoad` / `onShow` 各订阅一次）；重构只动「收到按键之后怎么分发」，没动 Kotlin 收键那一层。
- ✅ 统一按键语义：返回键永远「关掉最上层」；`OK` 在当前上下文里只有一个含义；
  连按 / 长按去抖统一用 `KeyDebounce`（替换掉各页面自己写的 180ms 去抖）。
- ✅ 日志：保留 `FnOsTvKeys` 的 `Log.d`，分发层另加 `key=<code> stack=<层>`；
  电视正式包取不到 logcat 的问题用屏幕日志兜住（**5.28**）。
- ✅ 测试（真机，全部通过）：
  1. ✅ 登录页 → 图库 → 查看器 → 详情 → 相册 → 设置 → 搜索，逐层进入后返回键各退一层，不越级、不黑屏；
  2. ✅ 每层内上/下/左/右/OK 的行为与重构前逐条对齐（见各页现有验收项），无回归；
  3. ✅ 连按 / 长按 / 快速跨层（如查看器里连按返回）不残留定时器、不误触下层；
  4. ✅ 快速反复进出页面（`reLaunch` / 返回）后按键仍有效 —— 5.29 的 id 撞车修掉后不再复发；
  5. ✅ `adb logcat -s FnOsTvKeys` 无异常，按键日志与预期分发一致。
- ✅ 验收结论：5 项全过；页面里已无散落的 `if (code == 19)` 硬编码（统一走 `common/keys.uts` 常量与分发层）；
  新增页面接入按键只需注册一层，不改公共代码。

### T6 健壮性（部分完成）— v2.0
- ✅ 401/token 失效：`isAuthError()` 命中后 `logout()` + `reLaunch` 回登录页（多个请求同时失败只跳一次）；
  **不再置 `skipAuto`** —— token 过期只是登录态过期，账号密码还在，让登录页用保存的凭据自动重登
  （web 端代理还会先静默续期），用户不用重新手输；防抖由登录页 `allowAutoLogin()` 兜住。
  `skipAuto` 现在只用于「用户主动进设置页」（图库返回键 / 右上角设置）；
- ✅ 图库加载失败有提示（顶栏 errorText + 空态文案）；
- 待做：断网重试、缩略图失败占位优化。
- 待做（优先级高于上面两项）：**图库向上补页的观感**（2026-11-15~19 已迭代 8 轮仍未达用户预期，
  用户明确「先这样」）。现状、真机日志结论、被否掉的方案、可选新方向全部写在 **5.20**，
  动手前必须先读完那一节，不要重新发明一遍「隐藏新内容」「回推视图」这类已被否掉的解法。

### T7 AI 评价照片（新增，设置页可开关）— v3.0

- 需求：查看器里能看到这张照片的 **AI 评价**（评分 + 一句话点评 / 标签），
  设置页新增**第 5 项**「AI 照片评价」开关，**默认关闭**；关闭时既不请求也不显示。
- **接口未确认，不许猜**：先确认 NAS 侧有没有现成能力（`ai-smart-rec/*` 下是否有评分 / 描述类端点）。
  若 NAS 没有、需要接外部模型，**必须先问项目所有者**（涉及把照片信息发到第三方）；
  插件仍是零三方依赖，即使接也只允许 `HttpURLConnection` 自己发请求，不要加 SDK。
- 落地顺序照 §5 第 2 条：`FnOsLogin.kt` 加方法（入参 `photoId` / `photoUUID`）→ `interface.uts` 加类型
  （`Score` / `Comment` / `Tags`，字段缺失按空处理）→ `app-android/index.uts` 包 Promise → 查看器调用。
- 设置项与隐私：开关值存 `fnos.aiComment.enabled`（字符串 `'true'` / `'false'`，见 5.2，**默认 false**）；
  右栏必须有一行说明「评价会把照片信息发到 XX 分析」，不能只给开关不解释。
- 缓存：结果按 `photoUUID` 落本地（与 `thumb` / `image` 同一套缓存目录，存 JSON），命中不再请求；
  开关关闭时清空这份缓存并停止后续请求。
- 交互与容错：只在开关开启且查看器打开时请求；请求中详情面板显示「分析中…」，
  超时（≤8s）显示「暂无评价」；**失败绝不弹窗、不影响看图**（图片 / 视频 / 实况 / 详情一律照常）。
- web 端：`utssdk/web` 若不实现就返回明确错误（同 `videoInfo` 的处理），页面在 web 下显示
  「仅 App 支持」，别让浏览器调试看起来像坏了。
- 验收：开关关闭时抓不到任何评价请求；开启后打开一张照片 3~8 秒内出评价；连续翻 10 张不卡、
  同一张不重复请求；断网 / 接口报错时只有一行「暂无评价」且查看器其它功能正常；
  重启 App 后开关与缓存行为保持；设置页能看到并切换这个开关（不要留「暂未开放」占位）。

## 8. 提交前自检

1. `node tools/fnos-probe.mjs` 输出与第 2 节基线一致；
2. HBuilderX 真机运行：登录 → 图库首屏缩略图全部出现；
3. 遥控器：上下滚动、右键进时间轴、选年份/月份跳转、返回键回设置；
4. 查看器（见 5.7 / T1）：点任意一张进入 → 左右各翻 10 张（**跨过 60 张分页边界仍能继续**）
   → 下键看详情、上键收起 → 返回后图库滚动位置不变、无黑屏；
   浏览器额外验证 `!` 切换详情、`✕` 关闭，详情展开时图片缩小且不被遮挡；
   详情字段（大小/设备/曝光/路径）与 Web 版一致，缺失字段不出现空行（见 5.8.7）；
   人物头像、相册封面都能出来，多人不挤、加载失败回退首字/「册」块（见 5.9）；
   实况照片（见 5.10）：网格里有「▶ 视频 / 实况」角标，进查看器**首次就有声**地播一遍、播完回图片，
   进入瞬间**不黑屏、不闪播放按钮**，TV 按 OK 重播、返回键关闭、左右切不残留声音，浏览器 `▶` 可重播；
   视频文件（见 5.11）：点视频格进入 → **不自动播放**，状态行显示「按 OK 开始播放」；
   OK 才开始播，再按 OK 暂停/继续（图标与文案同步）、播完「已结束 · OK 重播」；
   左/右各按 1 次约跳 10 秒、连按 3 次约跳 30 秒且进度条跟手；在开头按左、在结尾按右只出提示不越界；
   点状态行 1.0x（TV 按「上」）循环切倍速（0.5 → 0.75 → 1 → 1.5 → 2，声音与进度明显变快变慢），
   面板里只有倍速一项（清晰度在设置页，三档经本机代理播放，状态行显示档位+码率）；返回后不残留声音；
   地址缺失/解码失败时状态行有提示且 OK 仍能关闭查看器；
5. 图库滚动（**先读 5.20**）：点任意年月**一步到位不闪**；向上滚能连续补页、视口不跳、不会「推不动」；
   时间轴高亮与视口顶部照片的月份一致（取样点已下移 0.7 格）；8/6/4 切换后位置不乱；
   **点最底部（最早）的月份后，高亮仍停在被点的那一项，且能继续向上补页**；
   **滚到全库最前（最新照片）时，顶部不应残留一条空白带，月份标题直接贴住顶栏**；
   真机上用 `[lib]` 日志核对 `onScroll` / `active` / `loadPrev`（日志收尾时要删，见 5.20.5）；
6. web 登录态（见 5.6）：手输登录一次后，**F5 刷新直接回图库**（不出现空的账号密码框）；
   重启 dev server 后再刷新，仍能自动登录；清掉浏览器 `fnos.web.cfg` 后回到需要手输的状态；
7. `git diff` 里没有任何账号、密码、token（`.env.local` 必须在 `.gitignore` 里）；
   `fnos.web.cfg` 只存在于浏览器 `localStorage`，任何情况下都不该出现在仓库 / 前端产物里；
8. 新增的 API 已在 `interface.uts` 声明类型，页面没有裸 `any`；
9. **跑过一次「运行到 Android App 基座」**（浏览器绿 ≠ 能编译：`watch` 类型、函数顺序、模板调导入函数
   这三类只在 Kotlin 编译时才报，见 5.2）；改过 Kotlin 的必须重跑基座；
10. 视频相关改动后真机验一遍：三档状态行显示 `原画` / `标清 720p/1000k` / `低清 360p/200k`，
    拖进度可用、没有残留声音，且**没有任何报错回退**（回退会在状态行写 6 秒原因 + `｜代理｜头=…`）。

### 5.34 正式包视频黑屏根因：离线打包漏了 ext-api 组件注册（2026-11-24，**已修复并真机验证**）

现象（只在本地打包的正式包出现，HBuilderX 基座里正常）：进视频查看器后画面全黑，状态行停在
「按 OK 开始播放（标清）」，`FnOsMediaProxy` 收不到任何请求；日志 `[v] el=found ctx=ok/ctx=null` 变化不定，
`createVideoContext('pw-video')` 抛 `UniElementImpl cannot be cast to UniVideoElement`；
logcat 里能看到 `getPositionType:ABSOLUTE io.dcloud.uniapp.runtime.UniElementImpl@…`。

**根因**：uni-app x 的 `<video>` 是 ext-api 组件（`uts.sdk.modules.DCloudUniVideo`），
编译产物里是 `resolveComponent("video")`，运行时只查一张注册表。这张表由
`BuildConfig.UTSEasyCom` 提供 —— 基座 APK 内置，**离线打包工程必须自己在 `app/build.gradle` 里声明**：

```gradle
buildConfigField 'String', 'UTSEasyCom', '\"[{\\\"class\\\":\\\"uts.sdk.modules.DCloudUniVideo.UniVideoExtApiComponentsRegister\\\",\\\"method\\\":\\\"register\\\"}]\"'
```

链路：`UniSDKEngine.initUTSEasyCom()` 读该字段 → 反射调 `UniVideoExtApiComponentsRegister.register()`
→ `registerExtApiComponent("video", UniVideoComponent)`。**查不到就退化成裸 `UniElementImpl`**（没有任何原生视图），
于是黑屏 + cast 失败 + 代理无请求，全都由这一个原因引起。

- 改的是 `打包脚本/templates/vdom/app/build.gradle` 和 `打包脚本/native/vdom/app/build.gradle`（两份要同步；
  `setup.ps1 -Force` 会用 templates 覆盖 native）。
- 生成结果自查：`app/build/generated/.../BuildConfig.java` 里 `UTSEasyCom` 的值必须是
  `"[{\"class\":\"…\",\"method\":\"register\"}]"`（反斜杠转义不能丢）。
- 官方对照：`打包脚本/tools/uniappx-sdk/<版本>/…/uniappxnativepackage/app/build.gradle` 的 `buildConfigField` 块
  （另含 `UTSRegisterComponents` / `UTSRegisterProviders` / `UTSHooksClassArray`；只有带 `node` 的原生视图组件
  如 canvas / ad 才需要进 `UTSRegisterComponents`，video 不需要）。
- **以后再新增任何 ext-api 组件（canvas / ad / live-player …）都要来这里加注册**，否则又是「编译成功但用不了」。
- 修复后实测：`[v] el=found ctx=ok`，分片 `00001.ts…` 连续 200，状态行进度正常推进，硬解 mediacodec 出帧。

### 5.35 视频清晰度扩到六档 + 竖屏转码补旋转（2026-11-24，真机已验证）

**起因**（用户真机反馈）：① 原画「一卡一卡」；② 标清/低清下竖屏视频变成横屏。

#### 5.35.1 原画卡顿不是代码问题，是带宽

`decode/info` 的 `data.videoStreamInfo[0]` 实测（id=155405）：

```json
"codecName":"h264","width":3840,"height":2160,"bitrate":61724059,
"framerate":"30.04 fps","rotation":-90
```

61.7 Mbps ≈ 7.7 MB/s；`adb forward` 到 `FnOsMediaProxy` 实测这条链路只有 **8.3 MB/s（≈66 Mbps）**，
余量 8% → 任何抖动都卡。原画直链对 4K 高码率视频**在 Wi-Fi 下必然卡**，不是缓冲参数能救的。

#### 5.35.2 五档（`common/video.uts`，设置页数据驱动）

| 档位 | 值 | 目标短边 | 码率策略 |
| --- | --- | --- | --- |
| 原图 | `original` | — | 直链原文件，不转码（4K 高码率需有线，Wi-Fi 下必然卡） |
| NAS转码原画 | `native` | 源短边（`videoStreamInfo` 的 min(w,h)） | 该分辨率里最大但 **≤20M** |
| 1080P | `1080` | 1080 | **≤6M** |
| 标清 | `sd` | 720 | 该分辨率里**最小**（≈1000k），默认档 |
| 低清 | `low` | 全局最小 | 直接取全局最低码率（≈200k） |

- **2.5K（`2k`）做过又按用户要求删掉了**：NAS 一般没有 1440 档，实际总是落到 1080，
  和「1080P」几乎重复，只会让人困惑。老机器里存的 `2k` 会按非法值回落默认档（`sd`）。
- 必须封顶：不封顶时「NAS转码原画」会选中 `4k/61724059` 那一档 = 换个方式播原画，照样卡。
- **历史 bug 已修**：以前用 `parseInt(q.resolution)` 折算高度，而 NAS 返回的是 `"4k"` → `parseInt("4k")=4`，
  4K 档永远选不中。现在统一走 `resolutionHeight()`（`8k→4320`、`4k→2160`、`2.5k/2k→1440`、数字原样），
  展示走 `resolutionDisplay()`（`4k`→`4K`、`1080`→`1080p`）。**任何地方都不要再直接 parseInt 分辨率**。
- 设置页右栏现在是 5 档 + HEVC 开关 + 说明 = 6 行，`.q-row` 高 **40rpx**（曾压到 32rpx）。
  16:9 电视右栏可用高度只有约 300rpx 量级，**再加档位必须重算行高**，
  否则最后几行会被裁掉（用 `uiautomator dump` 看各行 `bounds` 的 y 是否超过屏高）。

#### 5.35.3 竖屏变横屏：NAS 转码流丢了旋转信息

- 源视频是「横向帧 + 旋转标记」：`3840×2160` + `rotation: -90`（手机竖屏视频的典型形态）。
- 原画走 mp4 容器 → ExoPlayer 自己按矩阵转正（`media.metrics` 里是 `rotation-degrees=90`）。
- 转码走 HLS/TS → **TS 里没有旋转信息**，NAS 只是缩成 `1280×720`（`rotation-degrees=0`）→ 画面躺倒。
- 修法（客户端补）：`videoInfo()` 现在多返回 `sourceWidth/sourceHeight/rotation`
  （Kotlin 读 `videoStreamInfo[0]`）；`PhotoViewer` 在「转码 + rotate 90/270」时把 `<video>` 换成
  「旋转前的横盒」再整体转 90°：
  `boxW = 屏高`、`boxH = 屏高 × 源高/源宽`、居中摆放、`transform: rotate(deg)` —— 转完正好是 607×1080 的竖屏画面。
- 角度换算：NAS 用 FFmpeg 约定（竖屏 `-90`），mp4 矩阵/ExoPlayer 是正向（`90`），所以 **取反再归一化**。
- 原画 / 下载本地 / 出错回退都要 `resetVideoRotation()`，否则会把不该转的画面转掉。
- 真机验证手段（不靠肉眼）：截图后算「非黑像素外接框」，竖屏正确时应为
  `x≈657..1263`（宽 606 ≈ 1080×9/16）、中心 ≈960。PowerShell 用 `System.Drawing` 即可。
  注意 `adb exec-out screencap > file` 在 PowerShell 里会把二进制弄坏（`Bitmap.FromFile` 报「内存不足」），
  要用 `adb shell screencap -p /sdcard/x.png` + `adb pull`。

#### 5.35.4 排障利器：`FnOsMediaProxy` 就是一个「带 token 的 GET 客户端」

真机上 `FnOsMediaProxy` 只补 `AccessToken` 不签 `authx`，所以：

```powershell
# 1) 找代理端口：tcp6 表里 uid = 应用 uid 的那条 127.0.0.1 监听
adb shell cat /proc/net/tcp6       # 看 uid=10xxx、addr 结尾 0100007F 的端口
adb forward tcp:19199 tcp:<该端口>
# 2) 任意 NAS 路径 → base64url（去掉 =）→ /v/<b64>
Invoke-WebRequest "http://127.0.0.1:19199/v/<b64>"   # b64 = base64url("http://<nas>/p/api/v1/xxx?y=z")
```

- **能通**：`video/decode/info`、`stream/...`（流类接口只认 AccessToken）→ 查源视频宽高/旋转/码率最方便。
- **不能通**：需要 `authx` 的接口（`gallery/getList`、`decode/play` 等）→ 返回 `code:5000 invalid sign`。
  `authx` 是**请求头**，代理不会转发自定义头，把签名塞进查询串也无效（已试）。
  要测这类接口只能用 `tools/*.mjs`（需凭据）或临时在 Kotlin 侧打日志。

#### 5.35.5 2026-11-24 第二轮真机反馈（三条，均已改，待导出后验证）

1. **删掉「2.5K」档**（用户要求）：NAS 一般没有 1440 档，实际只能落到 1080，和「1080P」几乎重复。
   现在只有五档：原图 / NAS转码原画 / 1080P / 标清 / 低清。老存储值 `2k` 按非法值回落默认档。
   设置页行高随之从 32rpx 回到 **40rpx**（5 行 + 开关 + 说明正好放得下）。
   ⚠️ 再加档位必须重算行高，否则最后几行被裁（用 `uiautomator dump` 看各行 y 是否超过屏高）。

2. **竖屏转码时「播放三角朝下」**：`:controls="false"` 关不掉 uni video 的**居中播放按钮**
   （独立属性 `show-center-play-btn`，默认 true）。竖屏补旋转把整个元素转了 90°，这个内置按钮
   跟着转就成了「朝下」。修法：显式加 `:show-center-play-btn="false"`、`:show-play-btn="false"`、
   `:show-progress="false"`、`:show-fullscreen-btn="false"`、`:enable-progress-gesture="false"`。
   **以后凡是给 `<video>` 加 transform，都要先确认内置控件全关**。

3. **相册内页向下翻久了光标消失 + 卡顿**（`pages/album/detail.uvue`）：
   原来光标是格子里塞一个 `v-if="c.idx == cursorIdx"` 的绝对定位子视图，而且 `onScroll` 在两端都会
   「按滚动位置反推光标」。翻得深了就出现：光标框不再创建（只剩格子上别的效果）、每次按键都要
   把上千个格子 patch 一遍（卡顿）、按键滚动与滚动回调互相抢 `cursorIdx`。
   改成与图库页同一套（那套是稳的）：
   - 光标画在**格子自身**：`:class="cursorClass(c.idx)"` + `.cell-focus` / `.cell-focus-idle`
     （`.cell` 加常驻透明边框 + `box-sizing: border-box`，保证加边框不改尺寸——`cellPx` 依赖它）。
   - `onScroll` 的「反推光标」只在 **web** 生效（`// #ifdef WEB`），电视上滚动永远由光标驱动。
   - 行表 `rowTable` 改为 `rebuildTops()` 后算一次，按键时只读（以前每次按键 O(n) 重建）。
   - `rebuildTops()` 末尾把 `cursorIdx` 夹到 `[0, n-1]`，兜底避免「谁都不匹配 = 光标消失」。

#### 5.35.6 踩坑：uvue 里函数必须「先定义后调用」（导出的 Kotlin 会报 Unresolved reference）

`<script setup>` 里的函数会被导出成 Kotlin 的「局部 fun + val 引用」：

```kotlin
fun gen_rebuildTops_fn() { rebuildRows() }   // 266 行
val rebuildTops = ::gen_rebuildTops_fn
fun gen_rebuildRows_fn() { ... }
val rebuildRows = ::gen_rebuildRows_fn       // 374 行 ← 用的时候还没声明
```

→ `Unresolved reference 'rebuildRows'`，**只在 Gradle 编译时才现形**（HBuilderX 导出不报错）。
规则：**任何被调用的函数都要写在调用点之前**（包括 `rebuildTops` 调 `rebuildRows` 这种）。
补救：改完源码必须重新导出；不想再导出一次的话，也可以临时把导出产物
`unpackage/resources/app-android/uniappx/app-android/src/**/*.kt` 里那两个块换个位置再打包
（下次导出会重新生成，别指望它留着）。

#### 5.35.7 缩略图窗口化（2026-11-24 第三轮）：翻久了卡 / 返回相册封面变空 的根因

真机 `dumpsys meminfo com.grfntv` 实测（相册内页「黄钦安」2147 张）：

| 状态 | Native Heap |
| --- | --- |
| 刚进相册列表（55 个封面） | 87 MB |
| 内页翻 60 行（约 360 张已挂载） | **221 MB** |

即每张缩略图 ≈ **360KB 常驻**。网格页没有虚拟列表，`ThumbImage` 只在 `onMounted`
下载一次、之后位图一直留在原生 ImageView 里 → 2400 张要 ~880MB。
后果有三个，都是同一个原因：翻久了越来越卡（大视图树 + 内存压力）、
从内页返回相册列表时封面被系统回收（`<image>` 的 src 还在但位图没了，不会自动重挂）、
搜索页一直 `appendChunk` 越挂越多同样会卡。

**修法：新增 `components/LazyThumb.uvue`**（`path` + `active`）——
`active=false` 就把 `src` 清空（`v-if` 卸载 `<image>`，位图随之释放），
重新进入窗口时按需再 `thumb()`（命中磁盘缓存，几十毫秒）。
各页用 **computed 窗口**跟随光标，**不改变布局与滚动高度**：

```ts
const WIN_ROWS_ABOVE = 8
const WIN_ROWS_BELOW = 10
const winLo = computed((): number => { const lo = cursorIdx.value - WIN_ROWS_ABOVE * COLS; return lo < 0 ? 0 : lo })
const winHi = computed((): number => cursorIdx.value + WIN_ROWS_BELOW * COLS)
```

- 模板：`<LazyThumb :path="c.photo.thumb" :active="c.idx >= winLo && c.idx <= winHi" />`
- 已接入：`pages/album/detail.uvue`、`pages/search/search.uvue`（用 `gridIndex`）、
  `pages/library/library.uvue`（用 `cols.value`）。
- `pages/album/album.uvue` 的封面、相册内页头部封面仍是 `ThumbImage`（单张，不需要窗口）。
- 窗口只影响「谁持有位图」，不动 `cellTops` / `cellPx` / 补页逻辑 —— 这是它安全的原因；
  **不要**改成分批删除 DOM 节点（那会改滚动高度，图库 5.20 的坑就是这么来的）。

另：**桌面图标显示成 DCloud 默认绿 U 不是代码问题**。从设备拉已安装 APK 解出来看，
`res/<混淆名>.png` 144×144 平均 RGB (198,196,195)，与 `static/logo.png` (197,194,193) 一致，
是电视桌面的图标缓存；重启电视（或换 versionCode 让它当新版本）即刷新。
**窗口化后的实测（2026-11-24，同一台机器、同样「黄钦安」相册、同样 60 行）**：

| 指标 | 窗口化前 | 窗口化后 |
| --- | --- | --- |
| 相册列表初始 Native Heap | 87 MB | 64 MB |
| 内页翻 60 行 | 221 MB | **117 MB** |
| 内页翻 180 行 | （线性涨到 ~500MB） | 180 MB（仍在缓涨：malloc 归还 OS 不积极，但幅度小一个量级） |
| 返回相册列表的封面 | 全空（位图被回收） | 完好（封面区亮像素占比 18.1%，与初始一致） |

**但按键滚动仍然掉帧**（`dumpsys gfxinfo` 30 次下键：Janky frames 50.6%、p90 109ms、
Slow UI thread 50）。剩下的大头是**每次移动光标都要把整张网格 patch 一遍**
（`cursorClass(c.idx)` 让渲染函数依赖 `cursorIdx`，上千个格子逐个 diff），
以及换入窗口时那 6 张缩略图的解码。要真正顺，下一步得把光标换成
**一个绝对定位的浮动框**（整列表不再依赖 `cursorIdx`）+ 窗口按「行块」跳变（每 4 行才换一次）。
**2026-11-24 收尾改动（相册内页 jank 专项）**：
- 光标从「格子上的 class」改成**整页唯一一个绝对定位浮动框**（`.cell-cursor` + `cursorBoxStyle`，
  位置由 `cellTops` + 列号算，`topsVersion` 当依赖桩通知重算）。这样 `cursorIdx` 不再被
  列表渲染读取 → 按键只重渲染那一个节点，不再 diff 上千个格子。
- 缩略图窗口改成**按行块跳变**（`WIN_BLOCK_ROWS = 4`，上 2 块 / 下 3 块）：窗口值 4 行才变一次，
  避免每按一下都换入换出 6 张图。
- 滚动内容外面包了一层 `<view class="inner">`（`position: relative`），浮动框以它为坐标原点
  随内容滚动；**这个包裹层不能去掉**，否则绝对定位会跑到 scroll-view 视口上（不跟内容滚）。
- 搜索页暂未改浮动光标：它的卡主要来自「分批挂 80 个格子」，`LazyThumb` 已经让那次挂载不再
  同时解码 80 张图；如果实测还卡，再照搬这套浮动框。

#### 5.35.8 相册内页改「虚拟列表」（2026-11-24 第四轮，真机已验）

相册内页 2147 张照片全部建原生视图 → 内存 221MB、按键 `Slow UI thread` 50 次/30 键。
这是在**不动坐标体系**的前提下做的窗口化渲染：

- 只渲染切片 `[sliceStart, sliceEnd]`（行对齐，光标上下各 8 / 10 行，约 108 张）。
- 上下两个 `.spacer` 撑起没渲染的部分：
  `spacerTop = cellTops[sliceStart] − headPx`（切片正好落在某天第一行时再减 `dayRowPx`(+`monthRowPx`)）、
  `spacerBottom = cellTops[n−1] + cellPx − (cellTops[sliceEnd] + cellPx)`。
  **总高度恒等于真实内容高度**，所以 `cellTops` / `cellPx` / 补页 / `ensureCursorVisible` 一行没改。
- 切片在**行边界**上切（`rowStartOf` / `rowEndOf`），否则 `flex-wrap` 会错行；
  切片从某天中间开始时**不画**该天日头（高度已算进 spacer），`SliceGroup.showHeader` 控制。
- 重切片有滞回（`SLICE_GUARD_ROWS = 3` 行）：光标贴近切片边缘才重算，否则每个方向键都换 DOM。
- 切片内组件随切片卸载，`ThumbImage` 卸载即释放位图 →
  已加载的 `LazyThumb` 窗口逻辑在这页**不再需要**（图库 / 搜索页仍在用 `LazyThumb`）。

实测（同机、同相册、同样 30 次下键）：

| 指标 | 原始 | 窗口化(LazyThumb) | 虚拟列表 |
| --- | --- | --- | --- |
| 内页 Native Heap（约 60 行） | 221 MB | 117 MB | **91 MB** |
| Slow UI thread / 30 键 | 50 | 38 | **35** |
| p50 每帧 | 17 ms | 22 ms | 24 ms |
| p90 每帧 | 109 ms | 101 ms | **77 ms** |

**重要基线**（别再被"Janky frames %"误导）：同一台盒子——
图库页 p50 **38ms** / p90 109ms（`Slow UI thread = 0`，用户认为流畅）、
相册列表 p50 **57ms**。也就是说这台 TV 盒子本身单帧就要 24~40ms，
相册内页（p50 24 / p90 77）已经**比图库和相册列表都快**。
以后评估"卡不卡"要拿图库页当基线，不要只看绝对帧时间。
**2026-11-24 第五轮两条真机反馈（已改，待验证）**：

1. **「已经到底了」被裁一半**：`contentHeight()` 只给了 `8rpx` 尾部余量，而底部提示块
   `.loading` 是 **40rpx** → 滚到底时提示文字被裁。现在 `contentHeight()` 用
   `TAIL_RPX = 48.0`（40 提示 + 8 余量）。**以后改底部块高度必须同步这个常量。**
2. **实况照片「只有声音、画面不动」**：`<video>` 要等 `@play` 才从 `opacity:0` 露出来
   （`vw-live-idle`），而有些盒子**只回 `@timeupdate` 不回 `@play`** → 永远不显形，
   于是只剩声音 + 那张静态图。现在补了 `@timeupdate="onLiveTime"`：一开始出时间戳就显形，
   并加了 `[live] event play` / `[live] first timeupdate` / `[live] event error` 三条日志。
   如果实测还是黑屏（而不是不动），那就是实况片段的 HEVC 解码问题，看 `[live] event error`。

#### 5.35.9 实况照片（isLive）真实编码：**枚举码 0/2，不是布尔**

真机日志（2026-11-24，`gallery/getList` 前 3 条）：

```
[raw] live isLive=0 videoUrl=/p/api/v1/stream/v/155405 cat=video   ← 普通视频
[raw] live isLive=2 videoUrl=/p/api/v1/stream/v/155404 cat=photo   ← 实况照片
[raw] live isLive=0 videoUrl=/p/api/v1/stream/v/155392 cat=video
```

以前写的是 `raw.optInt("isLive", 0) == 1` → 实况永远解析成 false，表现为
**网页有实况同心圆角标、TV 端既没角标也不会自动播**（`playLive` 的前提是 `p.live`）。
现在统一走 `FnOsLogin.liveFlag(raw, thumb)`：数字/布尔/字符串都按「非 0 即实况」判，
再补 `thumbnail.liveVideoUrl` 非空兜底。库页（`gallery/getList`）、相册内页（共用 `pageJson`）、
搜索（`searchItem`）三条路径都用这一个函数，**不要再自己写 `== 1`**。
实况片段的地址仍在条目/缩略图的 `videoUrl` 上（实测实况照片也有），所以 `p.video` 非空、能直接播。

（排查时临时加过 `[raw] live ...` 日志（每页前 3 条），**收尾已从源码删除**；
当前电视上装的 APK 里还带着它，下次导出打包后就没有了 —— 不影响功能。）


### 5.36 本轮总表（2026-11-24 ~ 11-25，全部真机验证过）

| # | 现象 | 根因 | 解决 | 详见 |
| --- | --- | --- | --- | --- |
| 1 | 本地打包的正式包**视频全黑**，浏览器/基座正常；`createVideoContext` 抛 ClassCast | 离线工程 `app/build.gradle` 没有 `buildConfigField UTSEasyCom` → `resolveComponent("video")` 为 null，`<video>` 变成裸 `UniElementImpl` | 补 `UTSEasyCom`（templates 与 native 两份同步） | 5.34 |
| 2 | 原画「一卡一卡」 | 源视频 4K / H.264 / **61.7 Mbps**，实测链路只有 ~66 Mbps（余量 8%），物理带宽 | 新增「NAS转码原画」档：源分辨率 + 码率封顶 20M | 5.35.1 / 5.35.2 |
| 3 | 清晰度档位不够用 | 只有 原图/标清/低清 | 扩成五档：原图 / NAS转码原画 / 1080P / 标清 / 低清；**2.5K 档后来按用户要求删掉**（NAS 没 1440，实际落 1080，和 1080P 重复） | 5.35.2 / 5.35.5 |
| 4 | 4K 档永远选不中 | `parseInt("4k")` = 4（NAS 的 resolution 是字符串） | 统一走 `resolutionHeight()` / `resolutionDisplay()`，**别再 parseInt** | 5.35.2 |
| 5 | 标清/低清下竖屏视频变横屏（画面躺倒） | NAS 转码流（TS）不带旋转信息；源是「横向帧 + rotation=-90」 | `videoInfo()` 多返回 `sourceWidth/sourceHeight/rotation`；转码时把 `<video>` 换成横盒再 `rotate(90deg)` | 5.35.3 |
| 6 | 同上场景里「播放三角朝下」 | `:controls="false"` 关不掉 uni video 的**居中播放按钮**（独立属性 `show-center-play-btn`），跟着元素转了 90° | 显式关掉 center-play / play / progress / fullscreen / 手势 | 5.35.5 |
| 7 | 相册内页翻久了光标消失 | 光标是格子里 `v-if` 的绝对定位子视图，翻深了不再创建 | 光标画在格子 class 上（后升级为浮动框） | 5.35.5 |
| 8 | 相册内页翻久了越来越卡 | 2147 张全部建原生视图：翻 60 行 Native Heap 221MB（每张 ~360KB）+ 视图树过大 | 三步走：缩略图窗口化 → 浮动光标 → **虚拟列表**（只渲染约 108 张，spacer 撑高度） | 5.35.7 / 5.35.8 |
| 9 | 从内页返回相册列表，封面全空 | 内存压力下系统回收了封面位图，`<image>` 的 src 还在但不会自动重挂 | 上面第 8 条修好后自然消失（实测返回后封面区亮像素占比与初始一致） | 5.35.7 |
| 10 | 相册内页翻到底，「已经到底了」只显示一半 | `contentHeight()` 只留 8rpx 尾部余量，而底部提示块是 40rpx | `TAIL_RPX = 48.0`（改底部块高度必须同步） | 5.35.9 之前一条 |
| 11 | 实况照片没角标、点进去也不播、只剩声音 | `isLive` 是**枚举码 0/2**，代码写的是 `== 1` → 永远 false | 抽出 `liveFlag()`（非 0 即实况 + `liveVideoUrl` 兜底），三条数据路径统一 | 5.35.9 |
| 12 | 实况照片「只有声音、画面不动」 | `<video>` 要等 `@play` 才从 `opacity:0` 露出来，有些盒子只回 `@timeupdate` | 加 `@timeupdate` 兜底显形 + `[live]` 日志 | 5.35.9 |
| 13 | 桌面图标是 DCloud 默认绿色 U | APK 图标其实是对的（解包核对过 RGB），是电视桌面图标缓存 | 重启电视 / 换 versionCode | 5.35.7 |
| 14 | 视频停在片尾不动、转圈跑到 125s | ExoPlayer 对 moov 在尾部的渐进式 mp4 **不发 `@ended`**（文件与 Range 响应都已证明正常） | 看门狗兜底：贴片尾 + 停 >2.5s = 播完，状态行「已结束 · OK 重播」 | 5.38 |
| 15 | 「已结束 · OK 重播」按 OK 播不动 | ① 同一 src 不重载 ② `ctx.play()` 立刻又结束 ③ `seek(0)+play()` 无效 ④ **uni 原生播放器按元素 id 复用 ExoPlayer 实例** | 卸载 → **换 `<video>` 的 id**（`pw-video-<n>`）→ 重建 → 延时 `play()`；连续三次重播真机验证通过 | 5.38.1 ~ 5.38.3 |

#### 5.36.1 本轮踩到并已写进文档的坑（别再踩）

1. **UTS/uvue 里函数必须先定义后调用**：导出的 Kotlin 是「局部 fun + val 引用」，先用后声明只在
   Gradle 编译时报 `Unresolved reference`（HBuilderX 导出不报错）→ 5.35.6。
2. **任何 Kotlin / `.uvue` 改动都要重新导出**才能进包（否则打出来还是旧逻辑，白折腾一轮）。
   只有 `app/build.gradle` 这类打包工程配置可以直接重打。
3. **`parseInt` 不能用于 NAS 的 `resolution`**（`"4k"` → 4）；DID/码率这类数值字段才用。
4. **给 `<video>` 加 transform 前，先确认内置控件全关**（center-play / play / progress / fullscreen / 手势）。
5. **`isLive` 不是布尔**，是枚举码；`optInt`/`optBoolean` 单用都可能丢标记。
6. **虚拟列表的 spacer 必须让总高度恒等于内容高度**，否则滚动落点会漂（图库 5.20 那类坑）；
   切片必须在**行边界**上切（`rowStartOf/rowEndOf`），否则 `flex-wrap` 错行。
7. **评估卡顿不要只看 `Janky frames %`**：这台 TV 盒子单帧就要 24~40ms，
   图库页 p50 **38ms** / 相册列表 p50 **57ms** —— 拿图库当基线比绝对值有意义（5.35.8）。
8. **排障利器**：`FnOsMediaProxy` 就是「带 token 的 GET 客户端」（`adb forward` + `/v/<base64url>`），
   能直接查 `decode/info`、能分析 m3u8/TS；需要 `authx` 头的接口（`getList` / `decode/play`）过不去 → 5.35.4。
9. 截图在 PowerShell 里要用 `adb shell screencap -p /sdcard/x.png` + `adb pull`，
   用 `exec-out ... > file` 会破坏二进制（`Bitmap.FromFile` 报「内存不足」）。
10. **uni 的原生 `<video>` 按元素 id 复用播放器实例**：想「重构」它（重播 / 换清晰度 / 换源），
   光清 `src`、`v-if` 关开、`seek(0)` 都不算——**必须换 id**，否则还是那个到过 ended 的 ExoPlayer（5.38.3）。
11. **判断「重播到底有没有从 0 开始」，看耗时不如看日志时间线**：`[v] ok` → `[v] settle at end`
   的间隔若 ≈ 片长，说明真的从头播到底；若 2~3 秒就再来一次 settle，说明它一直停在片尾（5.38.3）。
12. **Wi-Fi adb 在播放视频时会掉线**（`adb.exe: device offline`，播放/拉流占满电视无线）：
   关键结论尽量用**一条 `adb shell "input keyevent 23; sleep 2; uiautomator dump ..."`** 取，
   少来回；掉线后 `adb connect` 即可，不必重装。

#### 5.36.2 还没做 / 下一轮候选

- **实况片段的旋转补偿**：主视频已补，实况这条路径还没补。如果真机上实况片段是「躺倒的」，
  照 5.35.3 的思路补（需要先确认能否拿到该片段的旋转角）。
- **搜索页的浮动光标**：搜索页仍是「格子 class 依赖 `gridIndex`」，每次按键 patch 全部已挂格子；
  目前靠 `LazyThumb` + 分批渲染兜住，若实测仍卡再照搬 5.35.5 的浮动光标。
- **图库页虚拟列表**：图库仍是全量渲染（有 `LazyThumb` 窗口，内存可控），翻很深时理论上也会变慢；
  需要时照抄相册内页的 spacer 方案。
- **残留诊断日志**：`[v]`（查看器）、`[lib]`（图库）、`[keys]`、`[live]` 都在，
  都是「只在对应场景打几条」的量级，暂留；要彻底清时成组删除即可。
- **原画档的产品策略**：现在仍是「直链原文件」，4K 高码率在 Wi-Fi 下必然卡；
  设置页说明文案已注明「需要有线网络」，如果想更友好可以再加一层「码率过高提示」。

### 5.37 视频查看器：缓冲（网络）状态提示（2026-11-25）

**需求（用户原话）**：视频查看器遇到网络加载时给个加载动画 —— 现在分不清是 App 卡了、网络卡、还是视频本来就卡。

**做法**（`components/PhotoViewer.uvue`）：

- 状态：`videoBuffering` / `bufferSeconds` / `spinAngle`，配一个 100ms 的 `setInterval`。
- 转圈：uvue **不支持 CSS keyframes**，所以用定时器每 100ms 把 `transform: rotate(Ndeg)` 写到
  `.vw-buffer-ring`（24rpx 圆 + 4rpx 白边、`border-top-color` 透明 → 看起来在转）。
- 显隐：`@waiting` → 显示并开始计时；`@play` / `@timeupdate` / `@pause` / `stopVideo` /
  `videoFreshOpen` → `stopBufferTimer()`。**任何一个出口都要收，否则定时器会空转。**
- 文案按已缓冲秒数升级，让用户能区分「有反应但网慢」和「真死了」：
  `0~2s → 缓冲中…`、`3~9s → 网络较慢，缓冲中 Ns`、`>=10s → 网络很慢，正在缓冲 Ns`。
- 只覆盖在 `.vw-stage` 里（`z-index: 6`，背景透明、只居中一个半透明胶囊），不挡底部控制条。

**注意**：这个转圈只管「等数据」；「8 秒完全没进度」那类死局仍由 `armVideoWatch()`（5.18）兜底走下载。

#### 5.37.1 只靠 `@waiting` 不够：加「首帧等待 + 进度空档看门狗」（改后待验证）

真机实测（`NasPhotoTV-release-20261125-0031.apk`，原图 4K 61.7Mbps）：

```
[v] startVideo srcLen=0 transcode=false
[v] watch arm
……8 秒界面全黑、无任何提示……
[v] watch fired pos=0 dur=0 tried=false
[v] download fallback: 加载超时（8 秒无进度）
```

这 8 秒里 `@waiting` **一次都没来**，只有 `armVideoWatch` 的 8 秒兜底在最后救了场。
用户的原话就是「不知道是卡了还是网络卡了」——所以提示不能只挂在 waiting 上。

最终是三条触发：

| 触发 | 时机 | 文案 |
| --- | --- | --- |
| `videoFirstFrame` | `startVideo()` / `deliverSource()` 之后，到 `@play` 之前（原画 4K 实测能空 8 秒） | `正在加载…`（3 秒后带秒数） |
| `videoBuffering`（`@waiting`） | 播放器自己报缓冲 | `缓冲中…` → `网络较慢，缓冲中 Ns` → `网络很慢，正在缓冲 Ns` |
| **进度空档看门狗** | 播放中每 500ms 检查 `Date.now() - lastProgressAt`，>1500ms 判定卡住 | 同上 |

出口统一收：`@play` / `@pause` / `@ended` / `videoFail` / `stopVideo` / `videoFreshOpen`
都要 `videoFirstFrame=false`（+ 播放中才 `startStallWatch`、非播放态 `stopStallWatch`），
否则转圈会一直挂着或定时器空转。

排查要点：**改完 `.uvue` 必须重新导出再打包** —— 打包脚本只吃 `unpackage/resources` 里的导出产物，
源码改了但没导出，打出来的还是旧逻辑（这轮踩过一次：打出来的 0031 里根本没有 firstFrame 代码）。

**5.37.1 补充（2026-11-25 真机实测到的坑）**：首帧等待期间 `@waiting` 会来几次，
`onVideoTime` 也在按固定频率刷（但 `currentTime` 恒为 0）。原来的
「收到 timeupdate 就 `stopBufferTimer()`」会和转圈打架：秒数被反复清零，
界面表现是 **圈在转、但文案永远停在「正在加载…」没有秒数**（截图比对：中心区两帧有 874 像素在变
= 圈确实在转，说明定时器活着，只是 `bufferSeconds` 一直被重置）。

修法两条：
1. `onVideoTime` 只在 **`currentTime > 0.2`（真往前走了）** 时才算「出画面」，才清 `videoFirstFrame`；
2. 看门狗判定「进度恢复」时，只有 `!videoFirstFrame` 才 `stopBufferTimer()` —— 首帧阶段计时器必须一直跑。

诊断手段：`[v] buffering Ns firstFrame=true|false` 每 5 秒一条，能直接确认秒数在累计。


**5.37.2 定时器归属（2026-11-25 真机第二轮）**：日志里出现过 `[v] buffering 170s firstFrame=false`
—— 秒数累计到 170 秒还在跑，说明有路径在「`videoBuffering=false`」之后没把 `bufferTimer` 停掉
（下一轮缓冲会直接显示这个几百秒的旧值）。三条硬化：

1. `markFirstFrameWait()` 开头判 `alive`：异步回填（下载兜底 / 转码回调）可能在**组件销毁之后**
   才走到这里，不能给死实例起定时器；
2. 两个 `setInterval` 回调内部判 `alive`，为假就自停自清（任何「销毁后才起来」的路径都不留活口）；
3. `startBufferTimer()` 里若发现「定时器在跑、但首帧/缓冲都不成立」，先 `stopBufferTimer()` 再重启，
   保证秒数从 0 开始。

验证方式：`adb logcat | grep 'buffering '` —— 正常只在缓冲/等首帧期间每 5 秒一条，
关闭查看器后必须立刻安静。

**5.37.3 又一条 uvue 铁律（2026-11-25）**：**函数不能在函数体里调用自己**。
导出的 Kotlin 是「`fun gen_x_fn()` + `val x = ::gen_x_fn`」，在 `gen_x_fn` 里写 `x()` 就是
「先用后声明」→ `Unresolved reference: x`。这次的现场：

- `startBufferTimer()` 里为了兜底调了 `stopBufferTimer()`
- 两个 `setInterval` 回调里为「自愈」调了 `stopBufferTimer()` / `stopStallWatch()`

全部改成**内联清理**（直接 `clearInterval(x) ; x = 0` + 把相关 ref 归零），编译即过。
以后再要写「自愈」逻辑，只能内联，不能回头调自己。

（打包脚本现在能识别这类错误并直接点名符号，见 `打包脚本/打包.ps1` 里的 Unresolved reference 分支。）

**5.37.4 最终设计：常驻 tick，不再有「谁负责停定时器」（2026-11-25）**

两次泄漏（秒数跑到 170s / 190s 还在跑）说明「start/stop 配对」这种记账方式在
VDOM + 异步回填（下载兜底、转码回调、组件销毁竞态）下必然漏。改成**状态驱动**：

```ts
// 打开查看器时 armSpinnerTick()，每 500ms 一次；onUnmounted 里 stopSpinnerTick()
function spinnerNeeded(): boolean {
    return videoStarted.value && videoError.value.length == 0 &&
        (videoFirstFrame.value || videoBuffering.value)
}
function spinnerTick() {
    if (!spinnerNeeded()) { 归零秒数/角度; return }
    转 60°; 秒数 = floor((now - spinnerSince) / 1000)
}
```

- **没有任何业务路径需要 start/stop 它** → 结构上不可能泄漏；
- `stopSpinner()` 只把两个布尔状态置 false（出画面 / 暂停 / 播完 / 失败 / 换片都调它）；
- 看门狗简化为 `videoBuffering.value = gap > 1500`，不再自己启停计时器；
- 诊断日志改成 `[v] spinner Ns firstFrame=… buffering=…`（每 5 秒一条）。

教训：**这种「UI 计时」不要用「谁来起、谁来停」的记账模型**，用一个常驻 tick 读状态即可；
记账模型一旦漏一条路径，表现就是秒数越滚越大还不停。

**5.37.5 真机反馈「卡的时候不出现，播完才出现」—— 两个判断条件都写错了（2026-11-25）**

| 现象 | 根因 | 修法 |
| --- | --- | --- |
| 卡住时**不**显示转圈 | 看门狗用「`currentTime > 0.05` 就刷新 `lastProgressAt`」。卡住时 timeupdate 仍在按固定频率回调，只是位置不再前进 → 每来一次就刷新时间戳 → `gap` 永远 < 1.5s → 永远判定「有进度」 | 改成比**位置有没有变**：`if (Math.abs(t - lastPosSeen) > 0.01) { lastPosSeen = t; lastProgressAt = now }`；换片/起播时 `resetProgressBase()` |
| 播完后**才**显示转圈 | 片尾也会来 `waiting`（把 `videoBuffering` 置真），而结束后不再有 timeupdate 去清它 | `spinnerNeeded()` 再加两道闸：`videoEnded` → false；`!videoPlaying && !videoFirstFrame` → false |

教训：**「有没有进度」永远不要用「位置 > 0」判断**，要比「位置变了」；
播放器在卡住、片尾都会继续发回调，位置字段本身是唯一可信的信号。

**5.37.6 真机实测（NasPhotoTV-release-20261125-1359）：卡顿提示已生效，但「播完」不会发 @ended（2026-11-25）**

- ✅ 卡住时**能**出现转圈了：帧间差异定位到胶囊 `x=713..1206 y=422..534`（宽 493 = 圈+文案），
  日志 `[v] spinner Ns firstFrame=false buffering=true` 连续 125s —— 看门狗这条路走通了。
- ❌ 但「播完」这条链和预想不同：**这支 4K 原画到结尾时播放器不发 `@ended`**，
  而是停在 `0:30/0:31` 一直报 buffering（日志 `spinner 125s buffering=true`）。
  所以只拦 `videoEnded` 没用，转圈会永远挂着。
- 补的闸：`spinnerNeeded()` 里 `videoDur > 0 && videoPos >= videoDur - 0.6` 也直接返回 false
  （位置到头就当播完）。这条不依赖播放器事件，最可靠。
- 顺带记一个测试坑：转圈每 500ms 动一次会让 `uiautomator dump` 报
  `ERROR: could not get idle state`（拿不到 a11y 快照）。要验文案只能用
  **截图 + 帧间差异**：视频停住时只有圈在变，把两张截图的差异取外接框就是胶囊位置与尺寸。

**5.37.7 视频缓冲提示 —— 已真机验证通过（2026-11-25，`NasPhotoTV-release-20261125-1415.apk`）**

| 场景 | 结果 | 证据 |
| --- | --- | --- |
| 播放中卡住（4K 原画快退回开头触发 re-buffer） | ✅ 出现转圈 | 帧间差异 bbox `x=715..1204 y=422..534`（489×112，居中）；**文字区域 4 秒内变化 2364 像素 = 秒数在跳**；日志 `[v] spinner 5s…60s buffering=true` |
| 播到结尾（播放器不发 `@ended`，停在 0:30/0:31） | ✅ **不**出现转圈 | 两帧像素完全一致（diff=0）、`uiautomator dump` 能取到（说明无动画）、spinner 日志安静 |
| 正常播放 | ✅ 不出现 | 25 秒内零 `[v] spinner` 日志 |
| 关闭查看器 | ✅ 无泄漏 | 退出后 5 秒内 0 条日志（常驻 tick 在 onUnmounted 收掉） |
| 首帧等待（`startVideo` 时 src 还没就绪，原画 4K 能空 8 秒） | ✅ 出现 `正在加载…` | 更早一版实测（dump 里连续出现该文案）；这台机器后续起步变快，未再复现 |

**最终结构**（再看这块代码的人先读这段）：

- 唯一计时器 = `spinnerTick`（`armSpinnerTick` 在 `onMounted` 起、`stopSpinnerTick` 在 `onUnmounted` 收），
  每 500ms 只读状态：`videoStarted && 无错误 && !videoEnded && 位置未到片尾 && (等首帧 || 缓冲中)`；
- 状态来源只有三处：`markFirstFrameWait()`（起播）、`onVideoWaiting`（播放器报缓冲）、
  看门狗（`Date.now() - lastProgressAt > 1500`，`lastProgressAt` 只在**位置变化**时更新）；
- **不要再引入「谁 start/stop 计时器」的配对逻辑** —— 这正是前两版泄漏（秒数跑到 190s 还在跑）的根源。

**5.38 视频停在片尾（31s 的片子走到 30s 就停）—— 播放器不收尾，我们兜（2026-11-25）**

真机现象：31 秒的视频走到 `0:30` 就不动了，界面显示「播放中」但画面静止，日志
`[v] spinner …s buffering=true` 一路跑到 125s 还不停。

排查结论（都是实测，不是猜）：
1. **文件是完整的**：`FnOsMediaProxy` 的 Range 响应一字节不缺 ——
   `Range: bytes=242963871-` → `206`、`Content-Range: bytes 242963871-244120353/244120354`、
   `Content-Length=1156483`、实收 1156483；整文件 244120354 字节，NAS 报 `duration=31`。
2. 播放器（ExoPlayer）把整段拉完后（日志 `sent 238398KB`）**不再发任何请求**，也不发 `@ended`，
   而是停在 `currentTime≈30.4 / duration=31` 一直报 buffering。
3. 所以既不是网络问题，也不是我们代理的 Range 问题 —— 是 ExoPlayer 对渐进式源（moov 在尾部的 mp4）
   的收尾处理问题，客户端 API 层改不了它。

兜底（`PhotoViewer` 的看门狗里）：**位置贴到片尾且停住超过 2.5 秒 → 直接当播完**

```ts
if (gap > 2500 && videoDur > 0 && (videoDur - videoPos) < 1.5) {
    videoEnded = true; videoPos = videoDur; stopSpinner()…
    清掉 stall 定时器（内联 clearInterval，不能调 stopStallWatch —— 自引用编译不过）
}
```

效果：状态行变成 **「已结束 · OK 重播」**（`videoStateText` 本来就有这个分支，
`toggleVideoPlay` 里 `videoEnded` → `replayVideo()`），转圈也不再挂住。

注意：`spinnerNeeded()` 里还有一道独立的片尾闸 `videoPos >= videoDur - 1.0`，
即使没有看门狗参与（例如暂停在片尾），也不会误显示「缓冲中」。

**5.38.1 顺手修：「已结束 · OK 重播」按 OK 播不动（2026-11-25）**

兜底把状态置成 `videoEnded=true` 之后暴露出来的老问题：
`replayVideo()` 原来只是 `mountVideoStream(同一个 src, 0, true)` —— 但**同一个地址不会让
`<video>` 重新加载**，ExoPlayer 一旦到过 ended，`play()` 会立刻又结束（真机实测：状态行一直
停在「已结束 · OK 重播 0:31」，日志里只有一次 `[v] mount … pos=0`）。

改法：先 `videoEnded=false` / `videoPos=0` / `resetProgressBase()`，然后
**`ctx.seek(0)` + `ctx.play()`**；只有拿不到 `videoContext`（元素还没建好）时才回退到重新下发 src。

**5.38.2 重播的三种「轻做法」都不行，只能重建 `<video>`（2026-11-25 实测）**

| 做法 | 结果 |
| --- | --- |
| 重新下发同一个 src | 地址没变，元素不重新加载 |
| 只 `ctx.play()` | ExoPlayer 到过 ended 后 `play()` 立刻又结束 |
| `ctx.seek(0)` + `play()` | **无效**：按 OK 后 65 秒仍停在 `0:30`、`buffering=true`（真机日志 `spinner 5s…20s`） |

最终：`replayVideo()` 里先 `videoStarted=false` + `videoSrc=''`，**下一拍（`setTimeout 80ms`）
再把 src 打回去并 `videoStarted=true`**，让原生播放器彻底重建；重建后 300ms 再 `ctx.play()`。
同时 `markFirstFrameWait()` 让状态行显示「正在加载…」。

**5.38.3 重播必须换 `<video>` 的 id（2026-11-25 实测关键点）**

按 5.38.2 做了「v-if 关掉再开」，代理日志显示确实重新拉了流（`req … range=bytes=40-` → `sent 238398KB`），
但状态行很快又回到「已结束」—— 因为 **uni 的原生播放器是按元素 id 复用的**：
id 还是 `pw-video`，重建后沿用同一个 ExoPlayer 实例，位置仍停在片尾。

所以重播路径改成三件事一起做：

1. `videoStarted=false` + `videoSrc=''`（先卸载）；
2. 下一拍把 **id 换成新值** `'pw-video-' + (++videoRebuildSeq)`，再 `videoSrc=url` + `videoStarted=true`；
3. `videoContext()` 里的 `createVideoContext` / `getElementById` 都改成读 `videoElId.value`，不再写死 `'pw-video'`。

**以后凡是需要「让 `<video>` 彻底重来」的场景（换清晰度、换源），都要连带换 id**，
否则就是「看起来重建了、其实还是那个播放器」。

**真机验证通过（2026-11-25，release 包 `NasPhotoTV-release-20261125-1714.apk`）**：连续三次
「播到片尾 → 按 OK」全部正常重播，状态行 `已结束 · OK 重播 0:31/0:31` → `播放中 · 原画`，
中途取样 `0:27/0:31`，进度正常。日志时间线（每次按 OK 后代理都重新收到 `range=-` 再 `bytes=40-`，
即确实从 0 重新拉流）：

```
17:19:15.923 [v] ok … → 17:19:52.141 [v] settle at end pos=31 dur=31   (36.2s)
17:20:10.757 [v] ok … → 17:20:46.951 [v] settle at end pos=31 dur=31   (36.2s)
17:21:26.899 [v] ok … → 17:22:03.114 [v] settle at end pos=31 dur=31   (36.2s)
```

每次耗时 ≈36s ≈ 31s 正片 + 启动 2s + 看门狗判停 2.5s —— **如果重播没从 0 开始，
settle 会在 2.5s 内立刻再来一次**，所以这个时长本身就是「从零播到底」的判据。
