#!/usr/bin/env node
/**
 * SPE 后端依赖漏洞核对（OSV 路径，03 §20）。
 *
 * 为什么是 OSV 而不是 trivy / dependency-check——这是**本机网络条件下的结论**，不是偏好：
 *
 *   - trivy 需要先拉漏洞库（OCI artifact）。实测两种源都失败：
 *       mirror.gcr.io/aquasec/trivy-db:2  → 连接超时
 *       ghcr.io/aquasec/trivy-db:2        → token 请求 DENIED（匿名拉取被拒）
 *     本机 Docker Hub 同样不通（镜像走 registry-mirrors，但 trivy 的库是直连 OCI registry，不走 daemon）。
 *   - OWASP dependency-check 首次要下载 NVD 全量数据，无 API key 时限速 5 请求/30 秒，
 *     叠加实测 NVD 单请求 ~12s 的延迟，一次扫描不现实。
 *   - OSV（api.osv.dev）是 Google 维护的聚合漏洞库（收录 GitHub Advisory 等），有 HTTP API、
 *     **不需要下载数据库**，覆盖 Maven 生态，本机实测可达。代价是数据源与 NVD 不同——
 *     报告里必须写明口径，不能拿它宣称"绝对没有漏洞"。
 *
 * 用法：
 *   node scripts/osv-scan.mjs <依赖清单文件>
 * 依赖清单文件由 Maven 产出：
 *   mvnw dependency:list -DincludeScope=runtime -DoutputFile=deps.txt
 *
 * 输出：控制台汇总 + 同目录的 osv-report.json（完整原始结果）。
 */

import { readFile, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'

const OSV_BATCH_URL = 'https://api.osv.dev/v1/querybatch'
const BATCH_LIMIT = 1000

const inputPath = process.argv[2]
if (!inputPath) {
  console.error('用法: node scripts/osv-scan.mjs <mvn dependency:list 的输出文件>')
  process.exit(2)
}

/** 依赖清单的一行形如：`   com.example:artifact:jar:1.2.3:compile`（缩进不定，冒号分隔）。 */
const skippedLines = []

function parseDependencies(text) {
  const deps = []
  const seen = new Set()
  for (const rawLine of text.split(/\r?\n/)) {
    // 不锚定行尾：实际清单里可能出现 `(optional)`、` -- module x.y.z` 之类的尾巴
    const match = rawLine.match(/^\s*([\w.\-]+):([\w.\-]+):(\w+):([\w.\-]+):([\w.\-]+)/)
    if (!match) {
      if (rawLine.trim()) skippedLines.push(rawLine.trim())
      continue
    }
    const [, groupId, artifactId, , version, scope] = match
    const name = `${groupId}:${artifactId}`
    const key = `${name}@${version}`
    if (seen.has(key)) continue
    seen.add(key)
    deps.push({ name, version, scope })
  }
  return deps
}

/** 详情缓存：同一个漏洞可能命中多个依赖，避免重复请求。 */
const detailCache = new Map()

/**
 * 取单条漏洞的详情。
 *
 * `/v1/querybatch` 返回的是**精简对象**——没有 severity，也没有 affected/ranges，所以只看它的话
 * 报告里只有一串 ID，「严重度」与「修复版本」都是空的，判断不了该不该处置。逐条取 `/v1/vulns/{id}`
 * 才有完整记录。取不到就退回精简对象：宁可信息少一点，也不要让整个扫描失败。
 */
async function loadDetail(vuln) {
  if (detailCache.has(vuln.id)) return detailCache.get(vuln.id)
  let detail = vuln
  try {
    const response = await fetch(`https://api.osv.dev/v1/vulns/${vuln.id}`, {
      signal: AbortSignal.timeout(20000),
    })
    if (response.ok) detail = await response.json()
  } catch {
    // 网络抖动或超时：沿用精简对象
  }
  detailCache.set(vuln.id, detail)
  return detail
}

/** OSV 的严重度有两处来源：GHSA 会放在 database_specific.severity，其它可能只有 CVSS 向量。 */
function severityOf(vuln) {
  const fromGhsa = vuln.database_specific?.severity
  if (fromGhsa) return String(fromGhsa).toUpperCase()
  if (Array.isArray(vuln.severity) && vuln.severity.length > 0) return 'SEE-CVSS'
  return 'UNKNOWN'
}

/** 修复版本：优先取受影响区间里的 fixed 事件。 */
function fixedVersions(vuln) {
  const fixed = new Set()
  for (const affected of vuln.affected ?? []) {
    for (const range of affected.ranges ?? []) {
      for (const event of range.events ?? []) {
        if (event.fixed) fixed.add(event.fixed)
      }
    }
  }
  return [...fixed]
}

const text = await readFile(inputPath, 'utf8')
const deps = parseDependencies(text)
if (deps.length === 0) {
  console.error(`没有从 ${inputPath} 解析出任何依赖——先确认 dependency:list 是否成功产出内容。`)
  process.exit(2)
}
console.log(`依赖数量：${deps.length}（来自 ${inputPath}）`)
if (skippedLines.length > 0) {
  // 打印出来而不是静默跳过：清单格式变了要能立刻发现，否则会悄悄漏扫
  console.log(`未被识别的行：${skippedLines.length} 行，前几行如下（若含依赖，说明解析规则要更新）：`)
  skippedLines.slice(0, 5).forEach((line) => console.log(`    ${line}`))
}

const queries = deps.map((dep) => ({
  package: { ecosystem: 'Maven', name: dep.name },
  version: dep.version,
}))

// 分批（OSV 单次上限 1000 个查询），并保持与 deps 同序以便回填
const findings = []
for (let start = 0; start < queries.length; start += BATCH_LIMIT) {
  const slice = queries.slice(start, start + BATCH_LIMIT)
  const response = await fetch(OSV_BATCH_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ queries: slice }),
  })
  if (!response.ok) {
    console.error(`OSV 查询失败：HTTP ${response.status} ${await response.text()}`)
    process.exit(2)
  }
  const body = await response.json()
  for (let index = 0; index < slice.length; index += 1) {
    const vulns = body.results?.[index]?.vulns ?? []
    if (vulns.length === 0) continue
    const detailed = []
    for (const vuln of vulns) {
      detailed.push(await loadDetail(vuln))
    }
    findings.push({ dependency: deps[start + index], vulns: detailed })
  }
}

const severityRank = { CRITICAL: 4, HIGH: 3, MODERATE: 2, MEDIUM: 2, LOW: 1, UNKNOWN: 0, 'SEE-CVSS': 0 }
const counts = {}
let total = 0
for (const finding of findings) {
  for (const vuln of finding.vulns) {
    total += 1
    const severity = severityOf(vuln)
    counts[severity] = (counts[severity] ?? 0) + 1
  }
}

console.log('')
console.log('=== 结果（数据源：OSV；口径与 NVD 不同，见 03 §20）===')
console.log(`  受影响依赖：${findings.length} 个，漏洞条目：${total} 条`)
console.log(`  严重度分布：${JSON.stringify(counts)}`)

for (const finding of findings.sort(
  (a, b) =>
    Math.max(...b.vulns.map((v) => severityRank[severityOf(v)] ?? 0)) -
    Math.max(...a.vulns.map((v) => severityRank[severityOf(v)] ?? 0)),
)) {
  const { name, version, scope } = finding.dependency
  console.log('')
  console.log(`  ${name}:${version}  (${scope})`)
  for (const vuln of finding.vulns) {
    const fixed = fixedVersions(vuln)
    console.log(
      `    - [${severityOf(vuln)}] ${vuln.id}${fixed.length ? ` → 修复版本 ${fixed.join('/')}` : '（暂无修复版本）'}`,
    )
    if (vuln.summary) console.log(`      ${vuln.summary}`)
  }
}

const reportPath = join(dirname(inputPath), 'osv-report.json')
await writeFile(
  reportPath,
  JSON.stringify(
    {
      generatedAt: new Date().toISOString(),
      dataSource: 'OSV (api.osv.dev)',
      dependencyCount: deps.length,
      affectedCount: findings.length,
      vulnerabilityCount: total,
      severityCounts: counts,
      findings,
    },
    null,
    2,
  ),
  'utf8',
)
console.log('')
console.log(`完整原始结果：${reportPath}`)

// 有高危/严重时用非零退出码，方便调用方据此判断是否必须处置
const blocking = (counts.CRITICAL ?? 0) + (counts.HIGH ?? 0)
process.exit(blocking > 0 ? 1 : 0)
