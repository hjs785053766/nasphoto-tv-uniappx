/**
 * fnOS 登录（Node 侧），供 probe 脚本和 vite dev 代理复用。
 *
 * 浏览器无法完成这套握手（RSA PKCS#1 v1.5 加密 + AES-256-CBC + 自研信封），
 * 所以统一在 Node 里做，token 只留在服务端进程内，不进前端产物。
 */

import { createHash, publicEncrypt, randomBytes, constants, createCipheriv } from 'node:crypto'

const SALT = 'NDzZTVxnRKP8Z0jXg1VAMonaG8akvh'
const SECRET = 'EAECCF25-80A6-4666-A7C2-A76904A74AB6'

export const md5 = (s) => createHash('md5').update(s, 'utf8').digest('hex')
const hex = (b) => b.toString('hex')

function randomAscii(n) {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
  let s = ''
  for (let i = 0; i < n; i++) s += chars[Math.floor(Math.random() * chars.length)]
  return s
}

/** fnOS 的 authx 签名：md5(SALT_path_nonce_ts_md5(payload)_SECRET)，payload 用 RAW 值 */
export function authx(path, payload) {
  const nonce = String(100000 + Math.floor(Math.random() * 900000))
  const timestamp = String(Date.now())
  const sign = md5([SALT, path, nonce, timestamp, md5(payload), SECRET].join('_'))
  return `nonce=${nonce}&timestamp=${timestamp}&sign=${sign}`
}

/** 排序后的原始 key=value（参与签名） */
export const payloadRaw = (o) =>
  Object.keys(o)
    .sort()
    .map((k) => `${k}=${o[k]}`)
    .join('&')

function envelope(pub, obj) {
  const key = randomAscii(32)
  const iv = randomBytes(16)
  const cipher = createCipheriv('aes-256-cbc', Buffer.from(key, 'utf8'), iv)
  const aes = Buffer.concat([cipher.update(JSON.stringify(obj), 'utf8'), cipher.final()])
  const rsa = publicEncrypt({ key: pub, padding: constants.RSA_PKCS1_PADDING }, Buffer.from(key, 'utf8'))
  return {
    req: 'encrypted',
    iv: iv.toString('base64'),
    rsa: rsa.toString('base64'),
    aes: aes.toString('base64')
  }
}

const reqid = () => hex(randomBytes(4)) + '0000000000000000' + hex(randomBytes(2))

/**
 * 「登录设备管理」展示用（T4.9，与 fnOS Web 端实测一致）：
 * NAS 把 deviceName 按 `-` 拆成两段 —— 第一段是副行小标题、第二段是标题；
 * deviceType 只认 Browser / PC（前端按它选图标）。
 * 客户端名里不要再出现 `-`，否则第二段会被截断。
 * 与真机完全一致（大标题 NAS Photo TV / 小标题 TV），不加任何后缀。
 */
const DEFAULT_DEVICE_NAME = 'TV-NAS Photo TV'

/**
 * @returns {Promise<{token: string}>}
 */
export function login({
  host,
  port,
  https = false,
  username,
  password,
  deviceName = DEFAULT_DEVICE_NAME,
  /** 设备标识：同一个 did 会让 NAS 复用设备记录（续期时不要每次都换新的） */
  did = '',
  timeout = 15000
}) {
  return new Promise((resolvePromise, rejectPromise) => {
    if (https) {
      process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0'
    }
    const scheme = https ? 'wss' : 'ws'
    let settled = false
    let ws = null

    const finish = (fn, value) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      try {
        if (ws) ws.close()
      } catch (e) {
        /* 忽略 */
      }
      fn(value)
    }

    const timer = setTimeout(() => finish(rejectPromise, new Error('登录超时：NAS 的 WebSocket 没有响应')), timeout)

    try {
      ws = new WebSocket(`${scheme}://${host}:${port}/websocket?type=main`)
    } catch (e) {
      finish(rejectPromise, e)
      return
    }

    ws.addEventListener('error', () => finish(rejectPromise, new Error('WebSocket 连接失败，检查地址/端口/协议')))

    ws.addEventListener('open', () => {
      ws.send(JSON.stringify({ req: 'util.crypto.getRSAPub', reqid: reqid() }))
    })

    ws.addEventListener('message', (event) => {
      let msg = null
      try {
        msg = JSON.parse(String(event.data))
      } catch (e) {
        return
      }
      if (!msg) return

      if (msg.pub && msg.si) {
        ws.send(
          JSON.stringify(
            envelope(msg.pub, {
              req: 'user.login',
              reqid: reqid(),
              si: msg.si,
              user: username,
              password,
              stay: true,
              deviceType: 'Browser',
              deviceName,
              did: did.length > 0 ? did : hex(randomBytes(12))
            })
          )
        )
        return
      }

      if (typeof msg.token === 'string' && msg.token.length > 0) {
        finish(resolvePromise, { token: msg.token })
        return
      }

      // 两步验证 / 失败
      if (msg.code != null || msg.result != null || msg.msg != null) {
        const message = msg.msg || msg.result || ('登录失败（code=' + String(msg.code) + '）')
        finish(
          rejectPromise,
          new Error(msg.code == 2 ? '该账号开启了两步验证，浏览器调试暂不支持' : String(message))
        )
      }
    })
  })
}
