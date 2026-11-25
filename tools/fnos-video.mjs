#!/usr/bin/env node
/**
 * 视频清晰度接口自测（T4.6），无需依赖，Node 20+。
 *
 * 目的：不启动 App，直接确认三件事 ——
 *   1. `GET /p/api/v1/video/decode/info` 到底给了几档（是不是只有原始画质一档）；
 *   2. `POST /p/api/v1/video/decode/play` 能不能拿到 `playLink`（POST 签名是否被接受）；
 *   3. 转码流 `/p/api/v1/video/decode/stream`（m3u8）需要哪种鉴权 ——
 *      只带 `AccessToken` 头（App 的 `<video :header>` 就只发这个）、再加 `authx` 头、或把签名放 query。
 *
 * 真机上「几个档位看起来一样」时先跑这个脚本：能一眼看出是「转码没成功、悄悄回退成原画」，
 * 还是「NAS 本来就只有一档转码」。
 *
 * 用法（凭据用环境变量，不要写进仓库）：
 *   Windows PowerShell:
 *     $env:FNOS_USER='你的账号'; $env:FNOS_PASS='你的密码'; node tools/fnos-video.mjs
 *   bash:
 *     FNOS_USER=xxx FNOS_PASS=yyy node tools/fnos-video.mjs [photoId]
 *
 * 可选环境变量：FNOS_HOST / FNOS_PORT / FNOS_HTTPS=1 / FNOS_ACCESS_CODE
 * 可选参数：photoId（不给就从图库前几页里挑第一个视频）
 */

import { login, authx, payloadRaw } from './fnos-login.mjs'

const HOST = process.env.FNOS_HOST || '192.168.31.216'
const PORT = Number(process.env.FNOS_PORT || 5666)
const USE_TLS = process.env.FNOS_HTTPS === '1'
const USER = process.env.FNOS_USER || ''
const PASS = process.env.FNOS_PASS || ''
const ACCESS_CODE = process.env.FNOS_ACCESS_CODE || ''
const ARG_ID = (process.argv[2] || '').trim()

const BASE = `${USE_TLS ? 'https' : 'http'}://${HOST}:${PORT}`
const INFO_PATH = '/p/api/v1/video/decode/info'
const PLAY_PATH = '/p/api/v1/video/decode/play'
const STREAM_PATH = '/p/api/v1/video/decode/stream'

/** 与插件 queryOf 一致：key 排序 + URL 编码 */
function queryOf(o) {
  const usp = new URLSearchParams()
  Object.keys(o)
    .sort()
    .forEach((k) => usp.append(k, String(o[k])))
  return usp.toString()
}

function baseHeaders(token, path, payload) {
  const headers = {
    AccessToken: token,
    authx: authx(path, payload),
    'User-Agent': 'fnos-video-probe/1.0'
  }
  if (ACCESS_CODE) headers['x-access-code'] = Buffer.from(ACCESS_CODE, 'utf8').toString('base64')
  return headers
}

async function apiGet(token, path, params) {
  const res = await fetch(`${BASE}${path}?${queryOf(params)}`, {
    headers: baseHeaders(token, path, payloadRaw(params))
  })
  const text = await res.text()
  let json = null
  try {
    json = JSON.parse(text)
  } catch {
    /* 非 JSON 时由调用方打印原文 */
  }
  return { status: res.status, json, text }
}

/** code 5000 = 签名规则不匹配：换 ENCODED payload 再试（与插件 apiPost 一致） */
async function apiPost(token, path, body) {
  const send = async (payload) => {
    const res = await fetch(BASE + path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8', ...baseHeaders(token, path, payload) },
      body
    })
    const text = await res.text()
    let json = null
    try {
      json = JSON.parse(text)
    } catch {
      /* 忽略 */
    }
    return { status: res.status, json, text }
  }
  let out = await send(body)
  if (out.json && out.json.code === 5000) {
    const encoded = Object.keys(JSON.parse(body))
      .sort()
      .map((k) => `${encodeURIComponent(k)}=${encodeURIComponent(String(JSON.parse(body)[k]))}`)
      .join('&')
    out = await send(encoded)
    out.retried = true
  }
  return out
}

const short = (s, n = 200) => (s.length > n ? s.slice(0, n) + '…' : s)

/** 图库里翻不到视频时，改用相册找：album/photos 的列表项与 getList 同构 */
async function findVideoInAlbums(token) {
  const r = await apiGet(token, '/p/api/v1/album/list', {
    sort_by: 'album_name',
    sort_direction: 'desc',
    offset: '0',
    limit: '1000'
  })
  if (!r.json || r.json.code !== 0) {
    console.log(`      album/list 失败：HTTP ${r.status} code=${r.json ? r.json.code : 'n/a'}`)
    return null
  }
  const albums = (r.json.data && r.json.data.list) || []
  const withVideo = albums.filter((a) => Number(a.videoCount) > 0)
  console.log(`      相册 ${albums.length} 个，其中带视频 ${withVideo.length} 个`)
  for (const a of withVideo.slice(0, 8)) {
    const p = await apiGet(token, '/p/api/v1/album/photos', {
      album_id: String(a.albumId),
      sort_by: 'date_time',
      sort_direction: 'desc',
      offset: '0',
      limit: '100'
    })
    if (!p.json || p.json.code !== 0) continue
    const list = (p.json.data && p.json.data.list) || []
    for (let i = 0; i < list.length; i++) {
      const it = list[i]
      const thumb = it.additional && it.additional.thumbnail
      const looksVideo =
        String(it.category).toLowerCase().includes('video') ||
        Number(it.mediaDuration) > 0 ||
        !!(thumb && (thumb.videoUrl || thumb.liveVideoUrl))
      if (looksVideo && Number(it.mediaDuration) > 0) {
        console.log(`      命中相册「${a.albumName}」第 ${i} 条`)
        return { item: it, offset: -1 }
      }
    }
  }
  return null
}
async function findVideo(token, maxPages = 40) {
  const seen = {}
  let scanned = 0
  for (let page = 0; page < maxPages; page++) {
    const offset = page * 100
    const r = await apiGet(token, '/p/api/v1/gallery/getList', {
      start_time: '1970:01:01 00:00:00',
      end_time: '2099:12:31 23:59:59',
      mode: 'index',
      limit: '100',
      offset: String(offset)
    })
    if (!r.json || r.json.code !== 0) {
      console.log(`      getList offset=${offset} 失败：HTTP ${r.status} code=${r.json ? r.json.code : 'n/a'}`)
      return null
    }
    const list = (r.json.data && r.json.data.list) || []
    if (list.length === 0) break
    scanned += list.length
    for (let i = 0; i < list.length; i++) {
      const it = list[i]
      const cat = String(it.category)
      seen[cat] = (seen[cat] || 0) + 1
      const thumb = it.additional && it.additional.thumbnail
      const looksVideo =
        cat.toLowerCase().includes('video') ||
        Number(it.mediaDuration) > 0 ||
        !!(thumb && (thumb.videoUrl || thumb.liveVideoUrl))
      if (looksVideo) {
        console.log(`      扫描 ${scanned} 条，category 分布：${JSON.stringify(seen)}`)
        return { item: it, offset: offset + i }
      }
    }
    if (page === 0 || (page + 1) % 10 === 0) {
      console.log(`      已扫 ${scanned} 条，category 分布：${JSON.stringify(seen)}`)
    }
  }
  console.log(`      扫描 ${scanned} 条，category 分布：${JSON.stringify(seen)}`)
  return null
}

async function main() {
  if (!USER || !PASS) {
    console.error('缺少凭据：请设置 FNOS_USER / FNOS_PASS 环境变量（不要写进仓库）')
    process.exit(2)
  }
  if (USE_TLS) process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0'

  console.log(`[1/6] 登录 ${BASE}`)
  const { token } = await login({ host: HOST, port: PORT, https: USE_TLS, username: USER, password: PASS })
  console.log(`      token 长度 ${token.length}`)

  // ---------------- 找一个视频 ----------------
  let photoId = ARG_ID
  if (!photoId) {
    console.log('[2/6] 找视频（先翻图库，再翻相册）…')
    let found = await findVideo(token)
    if (!found) {
      console.log('      图库里没翻到，改从相册里找…')
      found = await findVideoInAlbums(token)
    }
    if (!found) {
      console.log('      没找到视频，请直接传 photoId：node tools/fnos-video.mjs 12345')
      process.exit(1)
    }
    photoId = String(found.item.id)
    const it = found.item
    console.log(`      命中 offset=${found.offset} id=${photoId} ${it.width}x${it.height} 时长=${it.mediaDuration ?? '?'} 文件名=${it.fileName || '?'}`)
    console.log(`      条目键：${Object.keys(it).join(', ')}`)
    const thumb = it.additional && it.additional.thumbnail
    console.log(`      thumbnail 键：${thumb ? Object.keys(thumb).join(', ') : '(没有 additional.thumbnail)'}`)
    if (thumb && thumb.videoUrl) {
      console.log(`      thumbnail.videoUrl 前 100 字符：${short(String(thumb.videoUrl), 100)}`)
    }
  } else {
    console.log(`[2/6] 使用指定 photoId=${photoId}`)
  }

  // ---------------- decode/info ----------------
  console.log(`\n[3/6] GET ${INFO_PATH}?id=${photoId}`)
  const info = await apiGet(token, INFO_PATH, { id: photoId })
  console.log(`      HTTP ${info.status} code=${info.json ? info.json.code : 'n/a'} ${info.json ? info.json.msg || '' : ''}`)
  if (!info.json) {
    console.log(`      返回不是 JSON，前 300 字符：\n${short(info.text, 300)}`)
    process.exit(1)
  }
  const qualities = (info.json.data && info.json.data.quality) || []
  console.log(`      data.quality 共 ${qualities.length} 档：`)
  for (const q of qualities) {
    console.log(`        resolution=${q.resolution ?? '(空)'} bitrate=${q.bitrate ?? '(无)'} progressive=${q.progressive ?? '?'}`)
  }
  const tiers = qualities.filter((q) => Number(q.bitrate) > 0)
  if (tiers.length === 0) {
    console.log('      ⚠️ 没有任何「带 bitrate 的转码档」→ 插件会直接回退原画（这就是「档位没区别」）')
  }

  // ---------------- decode/play：每档都试一次 ----------------
  // ---------------- decode/play：按 App 的选择规则各申请一次 ----------------
  const pickNearest = (target) => {
    let best = null
    let bestDiff = 0
    for (const t of tiers) {
      const h = parseInt(String(t.resolution), 10)
      if (!(h > 0)) continue
      const diff = Math.abs(h - target)
      if (!best || diff < bestDiff || (diff === bestDiff && h > parseInt(String(best.resolution), 10))) {
        best = t
        bestDiff = diff
      }
    }
    return best
  }
  const sortedTiers = tiers
    .slice()
    .sort((a, b) => parseInt(String(a.resolution), 10) - parseInt(String(b.resolution), 10))
  const sdPick = pickNearest(720)
  const lowPick = sortedTiers[0] || null

  console.log(`\n[4/6] POST ${PLAY_PATH}（按 App 的规则：标清取最接近 720、低清取最低档）`)

  /** 取 playLink 的第一段分片，用字节数反推码率（同一时长下，码率越高分片越大） */
  const segmentSize = async (link) => {
    try {
      const pl = await fetch(BASE + STREAM_PATH + link, { headers: { AccessToken: token } })
      const text = await pl.text()
      const seg = text
        .split('\n')
        .map((l) => l.trim())
        .find((l) => l.length > 0 && !l.startsWith('#'))
      if (!seg) return -1
      const baseDir = (BASE + STREAM_PATH + link).replace(/[^/]*$/, '')
      const res = await fetch(baseDir + seg, { headers: { AccessToken: token } })
      const buf = await res.arrayBuffer()
      return buf.byteLength
    } catch {
      return -1
    }
  }

  const playLinks = []
  for (const t of [sdPick, lowPick]) {
    if (!t) continue
    const body = JSON.stringify({
      id: Number(photoId),
      bitrate: Number(t.bitrate),
      resolution: String(t.resolution),
      audioIndex: -2,
      audioEncoder: 'aac',
      videoEncoder: 'h264',
      channels: 2
    })
    const r = await apiPost(token, PLAY_PATH, body)
    const data = r.json && r.json.data ? r.json.data : null
    const link = data && data.playLink ? String(data.playLink) : ''
    console.log(
      `      ${t.resolution}p@${t.bitrate}：HTTP ${r.status} code=${r.json ? r.json.code : 'n/a'}` +
        `${r.retried ? '（ENCODED 重试过）' : ''} ${r.json && r.json.msg ? 'msg=' + r.json.msg : ''}`
    )
    console.log(`        playLink：${link ? short(link, 140) : '(空)'}`)
    if (link) {
      const size = await segmentSize(link)
      console.log(`        第一段分片字节数：${size > 0 ? size + ' B（2 秒，约 ' + Math.round((size * 8) / 2 / 1000) + ' kbps）' : '取不到'}`)
      playLinks.push({ tier: t, link, size })
    }
  }
  if (playLinks.length === 0) {
    console.log('      ⚠️ 一档都没拿到 playLink → 插件会回退原画；把上面的 code/msg 发我就能定位（多半是 POST 签名）')
    return
  }

  // ---------------- T4.8：`startPosition` 到底被不被接受 ----------------
  // 「HEVC 转 H.264」兜底要从当前进度续播，插件会往 decode/play 里加 startPosition（只在 >0 时发）。
  // 这个字段没在 NAS 前端 bundle 里核实过，所以单独打一次：拿得到 playLink 就说明被接受。
  if (sdPick) {
    const startBody = JSON.stringify({
      id: Number(photoId),
      bitrate: Number(sdPick.bitrate),
      resolution: String(sdPick.resolution),
      audioIndex: -2,
      audioEncoder: 'aac',
      videoEncoder: 'h264',
      channels: 2,
      startPosition: 30
    })
    const r = await apiPost(token, PLAY_PATH, startBody)
    const link = r.json && r.json.data && r.json.data.playLink ? String(r.json.data.playLink) : ''
    console.log(
      `      带 startPosition=30：HTTP ${r.status} code=${r.json ? r.json.code : 'n/a'}` +
        `${r.json && r.json.msg ? ' msg=' + r.json.msg : ''} playLink=${link ? short(link, 80) : '(空)'}`
    )
    if (!link) {
      console.log('      ⚠️ startPosition 可能不被支持 → HEVC 兜底会从头播（不影响能不能看，只是不续播）')
    }
  }

  // ---------------- 转码流地址的正确拼法 + 鉴权方式 ----------------
  const first = playLinks[0]
  console.log(`\n[5/6] 转码流取流测试（${first.tier.resolution}p，playLink=${short(first.link, 80)}）`)

  const probe = async (name, url, headers) => {
    try {
      const res = await fetch(url, { headers })
      const text = await res.text()
      const head = text.replace(/\s+/g, ' ').slice(0, 120)
      console.log(`      ${name}\n        ${short(url, 120)}`)
      console.log(`        → HTTP ${res.status} type=${res.headers.get('content-type') || '?'} 长度=${text.length}  ${head}`)
      return text.startsWith('#EXTM3U')
    } catch (e) {
      console.log(`      ${name}：请求失败 ${e && e.message ? e.message : e}`)
      return false
    }
  }

  const tokenOnly = { AccessToken: token, 'User-Agent': 'fnos-video-probe/1.0' }
  const linkQuery = first.link.startsWith('?') ? first.link.slice(1) : first.link
  const usp = new URLSearchParams(linkQuery)
  const params = {}
  usp.forEach((v, k) => {
    params[k] = v
  })

  // 插件现在的拼法：`/decode/stream` + playLink（playLink 是 /media/... 路径，所以是无分隔符直拼）
  const playlistUrl = BASE + STREAM_PATH + first.link
  let ok = await probe('① 插件拼法 stream + playLink（只带 AccessToken）', playlistUrl, tokenOnly)
  if (!ok) {
    await probe('② stream + playLink + authx 头', playlistUrl, baseHeaders(token, STREAM_PATH, payloadRaw(params)))
    await probe('③ playLink 直接当绝对路径（只带 AccessToken）', BASE + first.link, tokenOnly)
    await probe('④ playLink 直接当绝对路径 + authx 头', BASE + first.link, baseHeaders(token, first.link.split('?')[0], payloadRaw(params)))
  }

  // m3u8 的分片能不能只靠 AccessToken 取到（ExoPlayer 取分片也是同一套 header）
  if (ok) {
    try {
      const res = await fetch(playlistUrl, { headers: tokenOnly })
      const text = await res.text()
      console.log('      m3u8 内容：')
      console.log(
        text
          .split('\n')
          .slice(0, 14)
          .map((l) => '        ' + l.trim())
          .join('\n')
      )
      const segs = text
        .split('\n')
        .map((l) => l.trim())
        .filter((l) => l.length > 0 && !l.startsWith('#'))
      if (segs.length > 0) {
        const baseDir = playlistUrl.substring(0, playlistUrl.lastIndexOf('/') + 1)
        const segUrl = segs[0].startsWith('http') ? segs[0] : baseDir + segs[0]
        await probe('⑤ 第一个分片（只带 AccessToken）', segUrl, tokenOnly)
        await probe('⑥ 第一个分片（不带任何鉴权）', segUrl, { 'User-Agent': 'fnos-video-probe/1.0' })
      }
    } catch (e) {
      console.log(`      解析 m3u8 失败：${e && e.message ? e.message : e}`)
    }
  }

  // ---------------- 结论提示 ----------------
  console.log('\n[6/6] 怎么看结果：')
  console.log('      · ① 返回 #EXTM3U → 插件的取流地址是对的，问题在播放器（ExoPlayer 的 header / HLS）')
  console.log('      · ① 只回「TR Media Server」这类纯文本 → 地址拼错或没被授权，看 ②③④ 哪个能出 #EXTM3U')
  console.log('      · 每档 playLink 里的 hash 不一样是正常的（一次会话一个目录）')
  console.log('      · 第 3 步一档都没有 → 该视频不转码（短视频 / 源本身低清），插件只能播原画')
}

main().catch((e) => {
  console.error('失败：' + (e && e.message ? e.message : String(e)))
  process.exit(1)
})
