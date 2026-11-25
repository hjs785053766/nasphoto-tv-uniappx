# 给接手 AI 的启动提示词

把下面整段复制到 Cursor / Trae / Claude Code / Codex 的对话里（打开的是 `nasphoto-tv-uniappx` 项目根目录）。

---

你是这个项目的接手工程师。项目是飞牛 fnOS 相册的 Android TV 客户端（uni-app x + UTS/Kotlin 插件）。

**第一步：读 `AGENTS.md`（项目根目录）**。里面有实测过的协议事实、硬约束、代码地图和任务清单，
不要跳过，也不要重新猜接口字段。

## 第一阶段：先让工程在 HBuilderX 真机跑起来（不要跳过）

- 用 HBuilderX 4.25+ 打开项目，`manifest.json` 重新获取 AppID，运行到 Android 电视/盒子（adb connect）。
- 只修编译错误，保持现有结构；每个报错优先按 `AGENTS.md` 第 6 节的建议处理。
- 修完逐项执行第 8 节自检，并把「实际输出 / 截图 / 日志」贴出来。
- 如果登录失败，先跑 `node tools/fnos-probe.mjs`（凭据用环境变量）确认是 App 问题还是协议/网络问题。

## 第二阶段：按 T1 → T2 → T3 顺序开发

任务定义和验收标准见 `AGENTS.md` 第 7 节。每完成一个任务：

1. 跑 `node tools/fnos-probe.mjs` 回归，输出必须与 `AGENTS.md` 第 2 节基线一致；
2. 说明改了哪些文件、验收怎么做的、还剩什么风险；
3. 新增 API 必须同时改 `FnOsLogin.kt` → `interface.uts` → `index.uts`，页面不写裸 `any`。

## 硬约束（违反就要回退）

- 不给插件加三方依赖（okhttp / Coil / ExoPlayer 等都不行，先讨论）。
- 不改 `SALT` / `SECRET` / 加密信封 / 签名顺序。
- 不把 AccessToken 拼进 URL（服务端只认请求头，实测 401）。
- 不把账号、密码、token 写进任何提交文件；脚本读环境变量。
- 图片一律走插件下载到本地缓存后交给 `<image>`，不要直接绑远程 URL。

## 工作方式

- 协议相关的问题，先用 `tools/fnos-probe.mjs` 抓真实响应，再改代码；
- 每改一处先说明「为什么要改 / 影响哪些文件」，再动手；
- 拿不准的取舍列出来让我选，不要默默换成另一种方案。
