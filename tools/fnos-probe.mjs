#!/usr/bin/env node
/**
 * fnOS 协议自测脚本（无需依赖，Node 20+）
 *
 * 作用：不启动 App，直接验证「登录 → 时间轴 → 图库分页 → 缩略图鉴权」这条链路，
 * 方便改协议代码前后做回归。输出只打摘要，不打 token 全文。
 *
 * 用法（凭据不要写进仓库，用环境变量）：
 *   Windows PowerShell:
 *     $env:FNOS_USER='你的账号'; $env:FNOS_PASS='你的密码'; node tools/fnos-probe.mjs
 *   bash:
 *     FNOS_USER=xxx FNOS_PASS=yyy node tools/fnos-probe.mjs
 *
 * 可选环境变量：
 *   FNOS_HOST   默认 192.168.31.216
 *   FNOS_PORT   默认 5666（HTTP）；HTTPS 用 5667
 *   FNOS_HTTPS  '1' 表示走 https/wss（自签证书，脚本会关闭证书校验，仅本地调试用）
 *   FNOS_ACCESS_CODE  开启「访问码」时填写
 */

import { createHash, publicEncrypt, randomBytes, constants, createCipheriv } from 'node:crypto'

const HOST = process.env.FNOS_HOST || '192.168.31.216'
const PORT = Number(process.env.FNOS_PORT || 5666)
const USE_TLS = process.env.FNOS_HTTPS === '1'
const USER = process.env.FNOS_USER || ''
const PASS = process.env.FNOS_PASS || ''
const ACCESS_CODE = process.env.FNOS_ACCESS_CODE || ''

const BASE = `${USE_TLS ? 'https' : 'http'}://${HOST}:${PORT}`
const WS = `${USE_TLS ? 'wss' : 'ws'}://${HOST}:${PORT}/websocket?type=main`

const SALT = 'NDzZTVxnRKP8Z0jXg1VAMonaG8akvh'
const SECRET = 'EAECCF25-80A6-4666-A7C2-A76904A74AB6'

const md5 = (s) => createHash('md5').update(s, 'utf8').digest('hex')
const hex = (b) => b.toString('hex')

function randomAscii(n) {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
  let s = ''
  for (let i = 0; i < n; i++) s += chars[Math.floor(Math.random() * chars.length)]
  return s
}

/** fnOS 的 authx 签名：md5(SALT_path_nonce_ts_md5(payload)_SECRET)，payload 用 RAW 值 */
function authx(path, payload) {
  const nonce = String(100000 + Math.floor(Math.random() * 900000))
  const ts = String(Date.now())
  const sign = md5([SALT, path, nonce, ts, md5(payload), SECRET].join('_'))
  return `nonce=${nonce}&timestamp=${ts}&sign=${sign}`
}

/** 排序后的原始 key=value（参与签名） */
const payloadRaw = (o) => Object.keys(o).sort().map((k) => `${k}=${o[k]}`).join('&')

/** 排序 + URL 编码（拼到 URL 上，空格会变成 +） */
function queryOf(o) {
  const usp = new URLSearchParams()
  Object.keys(o).sort().forEach((k) => usp.append(k, String(o[k])))
  return usp.toString()
}

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

async function api(path, params, token, accessCode) {
  const headers = {
    AccessToken: token,
    authx: authx(path, payloadRaw(params)),
    'User-Agent': 'fnos-probe/1.0'
  }
  if (accessCode) headers['x-access-code'] = Buffer.from(accessCode, 'utf8').toString('base64')
  const res = await fetch(`${BASE}${path}?${queryOf(params)}`, { headers })
  const text = await res.text()
  let json = null
  try { json = JSON.parse(text) } catch { /* 非 JSON 时下面打印原文 */ }
  return { status: res.status, json, text }
}

async function main() {
  if (!USER || !PASS) {
    console.error('缺少凭据：请设置 FNOS_USER / FNOS_PASS 环境变量（不要写进仓库）')
    process.exit(2)
  }
  if (USE_TLS) process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0'

  console.log(`[1/5] 连接 ${WS}`)
  const ws = new WebSocket(WS)
  const inbox = []
  ws.addEventListener('message', (e) => inbox.push(e.data))
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve)
    ws.addEventListener('error', (e) => reject(new Error('WebSocket 连接失败：' + (e.message || 'unknown'))))
    setTimeout(() => reject(new Error('WebSocket 连接超时')), 8000)
  })

  const next = async () => {
    const started = Date.now()
    while (inbox.length === 0) {
      if (Date.now() - started > 12000) throw new Error('等待 NAS 响应超时')
      await new Promise((r) => setTimeout(r, 20))
    }
    return JSON.parse(inbox.shift())
  }
  const reqid = () => hex(randomBytes(4)) + '0000000000000000' + hex(randomBytes(2))

  ws.send(JSON.stringify({ req: 'util.crypto.getRSAPub', reqid: reqid() }))
  const hello = await next()
  if (!hello.pub || !hello.si) throw new Error('NAS 没有返回 RSA 公钥 / si')

  // 设备管理展示字段（T4.9）：deviceName 必须「<类型>-<客户端>」；deviceType 只认 Browser / PC
  ws.send(JSON.stringify(envelope(hello.pub, {
    req: 'user.login',
    reqid: reqid(),
    si: hello.si,
    user: USER,
    password: PASS,
    stay: true,
    deviceType: 'Browser',
    deviceName: 'TV-NAS Photo TV (probe)',
    did: hex(randomBytes(12))
  })))
  const login = await next()
  ws.close()

  if (!login.token) {
    console.error('登录失败：', JSON.stringify(login).slice(0, 400))
    process.exit(1)
  }
  const token = login.token
  console.log(`[2/5] 登录成功，token 长度 ${token.length}`)

  // 运行到浏览器调试时，把 token 写进 .env.local（vite 代理用它代持鉴权）
  if (process.argv.includes('--token')) {
    console.log('')
    console.log('===== 复制下一行到 .env.local 的 FNOS_TOKEN= 后面 =====')
    console.log(token)
    console.log('======================================================')
    console.log('')
  }

  const stat = await api('/p/api/v1/user_photo/stat', {}, token, ACCESS_CODE)
  console.log(`[3/5] stat: code=${stat.json?.code} photo=${stat.json?.data?.photoCount} video=${stat.json?.data?.videoCount}`)

  const tl = await api('/p/api/v1/gallery/timeline', {}, token, ACCESS_CODE)
  const days = tl.json?.data?.list || []
  const total = days.reduce((s, d) => s + (d.itemCount || 0), 0)
  console.log(`[4/5] timeline: ${days.length} 天, 合计 ${total} 条, 最近 ${days[0]?.year}-${days[0]?.month}-${days[0]?.day}`)

  const params = {
    start_time: '1970:01:01 00:00:00',
    end_time: '2099:12:31 23:59:59',
    mode: 'index',
    limit: '3',
    offset: '0'
  }
  const page = await api('/p/api/v1/gallery/getList', params, token, ACCESS_CODE)
  const items = page.json?.data?.list || []
  console.log(`[5/5] getList: code=${page.json?.code} 返回 ${items.length} 条`)
  for (const it of items) {
    console.log(`      #${it.id} ${it.dateTime} ${it.category} live=${it.isLive}`)
  }

  // 相册列表：相册页（pages/album/album.uvue）依赖这几个字段，
  // 换固件后字段名有出入时先看这里
  const albumParams = {
    sort_direction: 'desc',
    sort_by: 'album_name',
    offset: '0',
    limit: '5'
  }
  const albumReply = await api('/p/api/v1/album/list', albumParams, token, ACCESS_CODE)
  const albumList = albumReply.json?.data?.list || []
  console.log(`[+] album/list: code=${albumReply.json?.code} total=${albumReply.json?.data?.total} 本页 ${albumList.length} 条`)
  for (const a of albumList) {
    console.log(`      ${a.albumName} 照片=${a.photoCount} 视频=${a.videoCount} 共享=${a.shared} 封面=${a.posterUpId ?? ''}/${a.posterUUID ?? ''}`)
  }

  // 相册内页：album/item（头部）+ album/photos（照片）。
  // 注意用 album/photos 而不是 album/normal/getList：后者是「按天视图」的接口，
  // 列表项不带 additional.thumbnail、也没有 isLive，实况标识就没了。
  const albumFirst = albumList[0]
  if (albumFirst) {
    const aid = String(albumFirst.albumId)
    const one = await api('/p/api/v1/album/item', { album_id: aid }, token, ACCESS_CODE)
    console.log(`[+] album/item: code=${one.json?.code} name=${one.json?.data?.albumName} 照片=${one.json?.data?.photoCount} 视频=${one.json?.data?.videoCount}`)

    const photoParams = {
      album_id: aid,
      sort_by: 'date_time',
      sort_direction: 'desc',
      offset: '0',
      limit: '2'
    }
    const photoReply = await api('/p/api/v1/album/photos', photoParams, token, ACCESS_CODE)
    const photoList = photoReply.json?.data?.list || []
    console.log(`[+] album/photos: code=${photoReply.json?.code} 返回 ${photoList.length} 条`)
    const p0 = photoList[0]
    if (p0) {
      const sUrl = p0.additional?.thumbnail?.sUrl || ''
      const streamRes = await fetch(BASE + sUrl, { headers: { AccessToken: token } })
      console.log(`      ${p0.dateTime} ${p0.category} isLive=${p0.isLive} 缩略图 -> ${streamRes.status}`)
    }
  }

  const first = items[0]
  if (first) {
    const thumbPath = first.additional?.thumbnail?.xsUrl
    const noAuth = await fetch(BASE + thumbPath)
    const withAuth = await fetch(BASE + thumbPath, { headers: { AccessToken: token } })
    const withQuery = await fetch(BASE + thumbPath + '?AccessToken=' + encodeURIComponent(token))
    console.log(`      缩略图鉴权: 无头=${noAuth.status} 头=${withAuth.status} query=${withQuery.status}（后两个应为 200/401）`)
  }

  console.log('OK')
}

main().catch((e) => {
  console.error('FAILED:', e?.message || e)
  process.exit(1)
})
