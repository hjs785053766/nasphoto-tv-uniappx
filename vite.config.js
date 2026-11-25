import { defineConfig, loadEnv } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'
import { randomBytes } from 'node:crypto'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { login as nasLogin, authx, payloadRaw } from './tools/fnos-login.mjs'

/**
 * 仅在「运行到浏览器」时生效（dev server 专属），真机编译完全不读这个文件。
 *
 * 1) HBuilderX 在项目根存在 vite.config.js 时不会自动注入 uni 插件链，这里显式引入；
 * 2) 浏览器里做不了 WS + RSA 信封登录，所以登录页把账号密码 POST 给 /nas-login，
 *    由本进程完成加密登录并持有 token；
 * 3) /nas/* 反向代理到 NAS，转发时补 AccessToken + authx 签名；
 * 4) 账号密码在进程内存里留一份：NAS 的 AccessToken 有过期时间，过期后代理收到 401
 *    就用它静默续期一次。这样隔夜再来刷新页面不用重新输账号密码
 *    （凭据只在本进程内存，不落盘、不进前端产物）。
 *
 * token 只存在于 Node 进程内存（或 .env.local），不进前端产物。
 */

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  let accessCode = env.FNOS_ACCESS_CODE || ''

  // 优先用登录页换来的 token；没有则回落到 .env.local（tools/get-token.mjs 写入）
  let nasToken = env.FNOS_TOKEN || ''
  let nasBase = env.FNOS_BASE || 'http://192.168.31.216:5666'

  /** 最近一次登录的凭据，仅存在于本进程内存，用于 token 过期后自动续期 */
  let creds = null
  /** 正在进行的续期（多个请求同时 401 时只续一次） */
  let renewing = null
  /** 稳定设备标识：每个 dev server 进程一个，续期时 NAS 会复用同一条设备记录 */
  const webDid = randomBytes(12).toString('hex')

  const json = (res, status, payload) => {
    res.statusCode = status
    res.setHeader('Content-Type', 'application/json; charset=utf-8')
    res.end(JSON.stringify(payload))
  }

  /** 走 WS 登录换 token，并记住凭据 */
  async function loginNas(input) {
    const result = await nasLogin({ ...input, did: webDid })
    nasToken = result.token
    nasBase = (input.https ? 'https://' : 'http://') + input.host + ':' + input.port
    creds = {
      host: input.host,
      port: input.port,
      https: input.https,
      username: input.username,
      password: input.password
    }
    if (input.accessCode && input.accessCode.length > 0) {
      accessCode = input.accessCode
    }
    console.log(`[fnos] 登录成功 ${nasBase}，token 长度 ${result.token.length}`)
    return result
  }

  /**
   * token 失效后的静默续期。
   * 成功返回 true，失败（没凭据 / 密码已改 / NAS 不通）返回 false，交给页面提示用户手动登录。
   */
  function renewSession() {
    if (creds == null) {
      return Promise.resolve(false)
    }
    if (renewing != null) {
      return renewing
    }
    renewing = loginNas(creds)
      .then(() => true)
      .catch((e) => {
        console.log('[fnos] 自动续期失败：' + (e && e.message ? e.message : String(e)))
        return false
      })
      .finally(() => {
        renewing = null
      })
    return renewing
  }

  /**
   * POST 请求体的签名串（code 5000 时的兜底尝试，对应 Kotlin 侧 bodyPayload(encoded=true)）。
   * 注意 Node 的 encodeURIComponent 把空格编成 %20，Android 的 URLEncoder 编成 '+'，
   * 只有极端关键字（含空格且服务端要求 ENCODED）才会走到这里，先按 URL 编码处理。
   */
  function postPayloadEncoded(bodyText) {
    try {
      const obj = JSON.parse(bodyText)
      return Object.keys(obj)
        .sort()
        .map((k) => `${encodeURIComponent(k)}=${encodeURIComponent(String(obj[k]))}`)
        .join('&')
    } catch (e) {
      return bodyText
    }
  }

  /** POST 转发到 NAS（/nas/* 只给搜索这类「签名看请求体」的接口用，GET 仍走下面的 proxy） */
  async function forwardPost(req, res) {
    const chunks = []
    for await (const chunk of req) {
      chunks.push(chunk)
    }
    const body = Buffer.concat(chunks).toString('utf8')
    const path = (req.url || '').split('?')[0]
    const target = nasBase + (req.url || '')

    const send = async (payload) => {
      const headers = {
        'Content-Type': 'application/json; charset=utf-8',
        authx: authx(path, payload),
        'User-Agent': 'nasphoto-tv-web/1.0'
      }
      if (nasToken) {
        headers['AccessToken'] = nasToken
      }
      if (accessCode) {
        headers['x-access-code'] = Buffer.from(accessCode, 'utf8').toString('base64')
      }
      return fetch(target, { method: 'POST', headers, body })
    }

    try {
      let resp = await send(body)
      let text = await resp.text()
      if (resp.status === 401 && creds != null) {
        // token 过期：静默续期一次再重试（与 GET 代理同一套逻辑）
        const ok = await renewSession()
        if (ok) {
          resp = await send(body)
          text = await resp.text()
        }
      }
      if (resp.ok && /"code"\s*:\s*5000/.test(text)) {
        const retry = await send(postPayloadEncoded(body))
        if (retry.ok) {
          text = await retry.text()
          resp = retry
        }
      }
      res.statusCode = resp.status
      res.setHeader('Content-Type', 'application/json; charset=utf-8')
      res.end(text)
    } catch (e) {
      json(res, 502, { code: -1, msg: e && e.message ? e.message : String(e) })
    }
  }

  return {
    plugins: [
      uni(),
      {
        name: 'fnos-login-endpoint',
        configureServer(server) {
          // POST 走自己的转发（签名 payload 是请求体，反向代理拿不到原文）
          server.middlewares.use('/nas', (req, res, next) => {
            if (req.method !== 'POST') {
              next()
              return
            }
            forwardPost(req, res)
          })

          server.middlewares.use('/nas-login', (req, res) => {
            if (req.method !== 'POST') {
              json(res, 405, { ok: false, error: '仅支持 POST' })
              return
            }
            let raw = ''
            req.on('data', (chunk) => {
              raw += chunk
            })
            req.on('end', async () => {
              let body = {}
              try {
                body = JSON.parse(raw)
              } catch (e) {
                json(res, 400, { ok: false, error: '请求体不是合法 JSON' })
                return
              }

              const host = String(body.host || '192.168.31.216').trim()
              const port = Number(body.port || 5666)
              const useHttps = body.https === true
              const username = String(body.username || '')
              const password = String(body.password || '')
              const code = String(body.accessCode || '')

              if (username.length === 0 || password.length === 0) {
                json(res, 400, { ok: false, error: '账号或密码为空' })
                return
              }

              try {
                const result = await loginNas({
                  host,
                  port,
                  https: useHttps,
                  username,
                  password,
                  accessCode: code
                })
                // 不把完整 token 回传浏览器，前端不需要
                json(res, 200, { ok: true, tokenLength: result.token.length })
              } catch (e) {
                const message = e && e.message ? e.message : String(e)
                console.log('[fnos] 登录失败：' + message)
                json(res, 200, { ok: false, error: message })
              }
            })
          })
        }
      }
    ],
    // vite 默认扩展名列表不含 .uvue/.uts，`import App from './App'` 会解析失败。
    // 注意 extensions 是整体覆盖，必须把 uni-app x 用到的都列全。
    resolve: {
      extensions: ['.uvue', '.uvx', '.uts', '.mjs', '.js', '.mts', '.ts', '.jsx', '.tsx', '.json']
    },
    // 依赖预构建缓存挪到系统临时目录，避免映射盘/权限问题导致 EPERM rename
    cacheDir: join(tmpdir(), 'nasphoto-tv-vite'),
    server: {
      proxy: {
        '/nas': {
          target: nasBase,
          // 登录页里填的地址可能和默认值不同，用 router 动态取
          router: () => nasBase,
          changeOrigin: true,
          rewrite: (p) => p.replace(/^\/nas/, ''),
          configure: (proxy) => {
            proxy.on('proxyReq', (proxyReq, req) => {
              const url = new URL(req.url, 'http://localhost')
              const path = url.pathname
              const params = {}
              url.searchParams.forEach((value, key) => {
                params[key] = value
              })

              if (nasToken) {
                proxyReq.setHeader('AccessToken', nasToken)
              }
              // 图片流不需要签名，但多带一个无害；接口必须带
              proxyReq.setHeader('authx', authx(path, payloadRaw(params)))
              if (accessCode) {
                proxyReq.setHeader('x-access-code', Buffer.from(accessCode, 'utf8').toString('base64'))
              }
            })

            // AccessToken 过期时 NAS 返回 401：用内存里的凭据静默续期一次，
            // 这样下一次请求（刷新后的图库、重试的缩略图）就是有效的。
            proxy.on('proxyRes', (proxyRes) => {
              if (proxyRes.statusCode !== 401 || renewing != null) {
                return
              }
              if (creds == null) {
                console.log('[fnos] 收到 401，但本进程没有登录凭据（dev server 重启过？），请在页面登录一次')
                return
              }
              console.log('[fnos] 收到 401，token 可能已过期，正在自动续期…')
              renewSession()
            })
          }
        }
      }
    }
  }
})
