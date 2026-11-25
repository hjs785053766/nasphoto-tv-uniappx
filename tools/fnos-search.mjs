#!/usr/bin/env node
/**
 * AI 搜索接口自测（`POST /p/api/v1/magic-search/do`），无需依赖，Node 20+。
 *
 * 目的：不启动 App 就能确认「请求体 / 签名 / 返回结构」是否与插件里的解析对得上。
 * 输出只打结构摘要与第一条的关键字段，不打 token。
 *
 * 用法（凭据用环境变量，不要写进仓库）：
 *   Windows PowerShell:
 *     $env:FNOS_USER='你的账号'; $env:FNOS_PASS='你的密码'; node tools/fnos-search.mjs 海边
 *   bash:
 *     FNOS_USER=xxx FNOS_PASS=yyy node tools/fnos-search.mjs 海边
 *
 * 可选环境变量：FNOS_HOST / FNOS_PORT / FNOS_HTTPS=1 / FNOS_ACCESS_CODE
 */

import { login, authx } from './fnos-login.mjs'

const HOST = process.env.FNOS_HOST || '192.168.31.216'
const PORT = Number(process.env.FNOS_PORT || 5666)
const USE_TLS = process.env.FNOS_HTTPS === '1'
const USER = process.env.FNOS_USER || ''
const PASS = process.env.FNOS_PASS || ''
const ACCESS_CODE = process.env.FNOS_ACCESS_CODE || ''
const KEYWORD = (process.argv[2] || '海边').trim()

const BASE = `${USE_TLS ? 'https' : 'http'}://${HOST}:${PORT}`
const PATH = '/p/api/v1/magic-search/do'

/** 把 JSON 请求体转成排序后的 k=v（code 5000 时的兜底签名，对应 Kotlin bodyPayload） */
function bodyPayloadEncoded(body) {
  try {
    const o = JSON.parse(body)
    return Object.keys(o)
      .sort()
      .map((k) => `${encodeURIComponent(k)}=${encodeURIComponent(String(o[k]))}`)
      .join('&')
  } catch {
    return body
  }
}

async function postSearch(token, body, payload) {
  const headers = {
    'Content-Type': 'application/json; charset=utf-8',
    AccessToken: token,
    authx: authx(PATH, payload),
    'User-Agent': 'fnos-search-probe/1.0'
  }
  if (ACCESS_CODE) headers['x-access-code'] = Buffer.from(ACCESS_CODE, 'utf8').toString('base64')
  const res = await fetch(BASE + PATH, { method: 'POST', headers, body })
  return { status: res.status, text: await res.text() }
}

/** 递归找第一个「元素像照片」的数组，打印它的键结构 */
function summarize(node, depth = 0) {
  if (node == null || depth > 3) return
  if (Array.isArray(node)) {
    console.log('  '.repeat(depth) + `[array] 长度 ${node.length}`)
    if (node.length > 0) summarize(node[0], depth + 1)
    return
  }
  if (typeof node === 'object') {
    const keys = Object.keys(node)
    console.log('  '.repeat(depth) + `{object} 键：${keys.join(', ')}`)
    for (const k of keys) {
      if (Array.isArray(node[k])) {
        console.log('  '.repeat(depth) + `  .${k} →`)
        summarize(node[k], depth + 2)
      }
    }
  } else {
    console.log('  '.repeat(depth) + `[${typeof node}] ${String(node).slice(0, 80)}`)
  }
}

async function main() {
  if (!USER || !PASS) {
    console.error('缺少凭据：请设置 FNOS_USER / FNOS_PASS 环境变量（不要写进仓库）')
    process.exit(2)
  }
  if (USE_TLS) process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0'

  console.log(`[1/3] 登录 ${BASE}`)
  const { token } = await login({ host: HOST, port: PORT, https: USE_TLS, username: USER, password: PASS })
  console.log(`      登录成功，token 长度 ${token.length}`)

  const body = JSON.stringify({ keyword: KEYWORD, antiFilters: [] })
  console.log(`[2/3] POST ${PATH} body=${body}`)
  let res = await postSearch(token, body, body)
  let json = null
  try {
    json = JSON.parse(res.text)
  } catch {
    /* 非 JSON 时下面打印原文 */
  }

  // code 5000 = 签名规则不匹配：换 ENCODED 再试一次（与插件 apiPost 一致）
  if (json && json.code === 5000) {
    console.log('      code=5000，改用 ENCODED payload 重试')
    res = await postSearch(token, body, bodyPayloadEncoded(body))
    try {
      json = JSON.parse(res.text)
    } catch {
      json = null
    }
  }

  console.log(`[3/3] HTTP ${res.status} code=${json ? json.code : 'n/a'}`)
  if (!json) {
    console.log(`      返回不是 JSON，前 400 字符：\n${res.text.slice(0, 400)}`)
    process.exit(1)
  }
  if (json.code !== 0) {
    console.log(`      msg=${json.msg || json.result || ''}`)
    process.exit(1)
  }
  summarize(json)
  console.log('\n[+] 提示：若上面照片数组的键里没有 additional.thumbnail，插件会用')
  console.log('    id + photoUUID 拼 /p/api/v1/stream/p/t/{id}/s/{uuid} 作为缩略图。')
}

main().catch((e) => {
  console.error('失败：' + (e && e.message ? e.message : String(e)))
  process.exit(1)
})
