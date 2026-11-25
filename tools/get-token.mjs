#!/usr/bin/env node
/**
 * 取一次 AccessToken 并写进 .env.local（浏览器调试用）。
 *
 *   node tools/get-token.mjs
 *
 * 账号密码只在本次进程内存里使用，不落盘、不写日志；
 * 只有 AccessToken 会被写入 .env.local（该文件已在 .gitignore 中）。
 *
 * 也支持免交互：
 *   FNOS_USER=xxx FNOS_PASS=yyy node tools/get-token.mjs
 */

import { execFile } from 'node:child_process'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { createInterface } from 'node:readline'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const envPath = join(root, '.env.local')

function readPassword(prompt) {
  return new Promise((resolveValue) => {
    const rl = createInterface({ input: process.stdin, output: process.stdout, terminal: true })
    // 输入的所有字符都不回显，只保留我们主动打印的提示
    rl._writeToOutput = function () {}
    process.stdout.write(prompt)
    rl.question('', (answer) => {
      rl.close()
      process.stdout.write('\n')
      resolveValue(answer)
    })
  })
}

async function askCredential() {
  if (process.env.FNOS_USER && process.env.FNOS_PASS) {
    return { user: process.env.FNOS_USER, pass: process.env.FNOS_PASS }
  }
  const rl = createInterface({ input: process.stdin, output: process.stdout })
  const user =
    process.env.FNOS_USER ||
    (await new Promise((r) => rl.question('飞牛账号: ', r)))
  rl.close()
  const pass = process.env.FNOS_PASS || (await readPassword('飞牛密码（输入不回显）: '))
  return { user, pass }
}

function runProbe(user, pass) {
  return new Promise((resolveResult) => {
    execFile(
      process.execPath,
      [join(root, 'tools', 'fnos-probe.mjs'), '--token'],
      {
        cwd: root,
        env: { ...process.env, FNOS_USER: user, FNOS_PASS: pass },
        maxBuffer: 8 * 1024 * 1024
      },
      (error, stdout, stderr) => resolveResult({ error, stdout, stderr })
    )
  })
}

function saveToken(token) {
  const line = 'FNOS_TOKEN=' + token
  let content = existsSync(envPath) ? readFileSync(envPath, 'utf8') : ''
  if (/^FNOS_TOKEN=.*$/m.test(content)) {
    content = content.replace(/^FNOS_TOKEN=.*$/m, line)
  } else {
    content = content.length > 0 && !content.endsWith('\n') ? content + '\n' + line + '\n' : content + line + '\n'
  }
  writeFileSync(envPath, content, 'utf8')
}

const { user, pass } = await askCredential()
if (!user || !pass) {
  console.error('账号或密码为空，已退出')
  process.exit(2)
}

console.log('正在登录 NAS 并取 token ...')
const { error, stdout, stderr } = await runProbe(user, pass)
if (stdout) console.log(stdout.trim())
if (stderr) console.error(stderr.trim())

const token = stdout
  .split(/\r?\n/)
  .map((l) => l.trim())
  .find((l) => /^[A-Za-z0-9+/=_-]{30,}$/.test(l))

if (error || !token) {
  console.error('\n没有拿到 token。常见原因：')
  console.error('  - 账号或密码不对')
  console.error('  - 账号开启了两步验证（probe 脚本未实现 2FA）')
  console.error('  - NAS 地址/端口不对（可用 FNOS_HOST / FNOS_PORT 覆盖）')
  process.exit(1)
}

saveToken(token)
console.log('\n已写入 ' + envPath)
console.log('下一步：停掉「运行到浏览器」再重新运行（.env.local 只在启动时读取）。')
