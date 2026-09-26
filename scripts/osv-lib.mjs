/**
 * OSV 查询的公共部分（03 §20）。
 *
 * 两个扫描脚本（依赖扫描与镜像 OS 包扫描）都要做同样三件事：批量查询、逐条取详情、
 * 归纳严重度与修复版本。抽出来一份，避免"改了 A 忘了改 B"。
 */

const OSV_BATCH_URL = 'https://api.osv.dev/v1/querybatch'
const OSV_VULN_URL = 'https://api.osv.dev/v1/vulns/'
const BATCH_LIMIT = 1000

/** 详情缓存：同一个漏洞可能命中多个包，避免重复请求。 */
const detailCache = new Map()

/**
 * 取单条漏洞的详情。
 *
 * `/v1/querybatch` 返回的是**精简对象**——没有 severity，也没有 affected/ranges，所以只看它的话
 * 报告里只有一串 ID，「严重度」与「修复版本」都是空的，判断不了该不该处置。逐条取
 * `/v1/vulns/{id}` 才有完整记录。取不到就退回精简对象：宁可信息少一点，也不要让整个扫描失败。
 */
export async function loadDetail(vuln) {
  if (detailCache.has(vuln.id)) return detailCache.get(vuln.id)
  let detail = vuln
  try {
    const response = await fetch(`${OSV_VULN_URL}${vuln.id}`, { signal: AbortSignal.timeout(20000) })
    if (response.ok) detail = await response.json()
  } catch {
    // 网络抖动或超时：沿用精简对象
  }
  detailCache.set(vuln.id, detail)
  return detail
}

/** OSV 的严重度有两处来源：GHSA 会放在 database_specific.severity，其它可能只有 CVSS 向量。 */
export function severityOf(vuln) {
  const fromGhsa = vuln.database_specific?.severity
  if (fromGhsa) return String(fromGhsa).toUpperCase()
  if (Array.isArray(vuln.severity) && vuln.severity.length > 0) return 'SEE-CVSS'
  return 'UNKNOWN'
}

export const SEVERITY_RANK = {
  CRITICAL: 4,
  HIGH: 3,
  MODERATE: 2,
  MEDIUM: 2,
  LOW: 1,
  UNKNOWN: 0,
  'SEE-CVSS': 0,
}

/** 修复版本：取受影响区间里的 fixed 事件。 */
export function fixedVersions(vuln) {
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

/**
 * 批量查询：返回 `[{ subject, vulns }]`，只保留命中的项。
 *
 * @param items 形如 `{ subject: {name, version}, query: {package:{ecosystem,name}, version} }`
 */
export async function queryOsv(items) {
  const findings = []
  for (let start = 0; start < items.length; start += BATCH_LIMIT) {
    const slice = items.slice(start, start + BATCH_LIMIT)
    const response = await fetch(OSV_BATCH_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ queries: slice.map((item) => item.query) }),
    })
    if (!response.ok) {
      throw new Error(`OSV 查询失败：HTTP ${response.status} ${await response.text()}`)
    }
    const body = await response.json()
    for (let index = 0; index < slice.length; index += 1) {
      const vulns = body.results?.[index]?.vulns ?? []
      if (vulns.length === 0) continue
      const detailed = []
      for (const vuln of vulns) {
        detailed.push(await loadDetail(vuln))
      }
      findings.push({ subject: slice[index].subject, vulns: detailed })
    }
  }
  return findings
}

/** 统计严重度分布。 */
export function countSeverities(findings) {
  const counts = {}
  let total = 0
  for (const finding of findings) {
    for (const vuln of finding.vulns) {
      total += 1
      const severity = severityOf(vuln)
      counts[severity] = (counts[severity] ?? 0) + 1
    }
  }
  return { counts, total }
}
