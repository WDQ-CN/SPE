#!/usr/bin/env node
/**
 * 用 OSV 核对镜像 OS 包的漏洞（03 §20.5）。
 *
 * 分工是刻意的，也与依赖扫描保持一致：**PowerShell 进容器取事实**
 * （`scripts/export-image-packages.ps1` 导出 os-release 与已安装包清单），**Node 拿清单查 OSV**。
 * 把「取数」与「查询」分成两步，失败时一眼能看出是哪一段出的问题——本机 Docker 引擎在连续重活后
 * 偶发停止（04-043），这种时候最容易把「引擎没起来」误判成脚本本身有问题。
 *
 * 为什么不用 trivy 扫镜像：trivy 要拉漏洞库（OCI artifact），本机两个源都不通
 * （mirror.gcr.io 超时、ghcr.io 匿名 token 被 DENIED，见 03 §20.1）。OSV 有 HTTP API、
 * 覆盖 Alpine 这类发行版生态，不需要下载任何数据库。
 *
 * 覆盖范围**据实报告**：Alpine 走 OSV；**Oracle Linux（mysql:8.4）OSV 不覆盖**，会明确标成
 * 「未核对」——不会因为"查不到"就变成"没问题"。
 *
 * 用法：
 *   pwsh scripts/export-image-packages.ps1
 *   node scripts/osv-image-scan.mjs [清单目录]
 */

import { readFile, readdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { countSeverities, fixedVersions, queryOsv, severityOf, SEVERITY_RANK } from './osv-lib.mjs'

const inputDir = process.argv[2] ?? join(tmpdir(), 'spe-image-packages')
const files = (await readdir(inputDir).catch(() => [])).filter((name) => name.endsWith('.json'))
if (files.length === 0) {
  console.error(`没有在 ${inputDir} 找到清单——请先跑 scripts/export-image-packages.ps1`)
  process.exit(2)
}

/** Alpine 的 OSV 生态名只到「主.次」（如 Alpine:v3.24），带补丁位会查不到。 */
function alpineEcosystem(versionId) {
  const parts = String(versionId).split('.')
  return `Alpine:v${parts[0]}.${parts[1] ?? '0'}`
}

const report = {
  generatedAt: new Date().toISOString(),
  dataSource: 'OSV (api.osv.dev)',
  note: 'OSV 不覆盖 Oracle Linux 等发行版；未覆盖的镜像标为 unsupported-ecosystem，不代表没有问题',
  images: [],
}

console.log('=== 镜像 OS 包核对（数据源：OSV；口径与 trivy/NVD 不同）===')

let blocking = 0
let unsupported = 0

for (const file of files.sort()) {
  const record = JSON.parse(await readFile(join(inputDir, file), 'utf8'))
  const { packages, ...meta } = record

  console.log('')
  console.log(`--- ${record.image} ---`)
  console.log(`  OS: ${record.prettyName} (ID=${record.id}, VERSION_ID=${record.versionId})`)

  if (record.id !== 'alpine') {
    unsupported += 1
    console.log(`  ** 未核对 **：OSV 不覆盖 ${record.id} 生态（那类需查厂商 errata），此处不假装扫过`)
    report.images.push({ ...meta, status: 'unsupported-ecosystem' })
    continue
  }

  const ecosystem = alpineEcosystem(record.versionId)
  const list = packages ?? []
  console.log(`  包数量: ${list.length}，生态: ${ecosystem}`)

  const findings = await queryOsv(
    list.map((pkg) => ({
      subject: pkg,
      query: { package: { ecosystem, name: pkg.name }, version: pkg.version },
    })),
  )
  const { counts, total } = countSeverities(findings)
  console.log(`  受影响包: ${findings.length} 个，漏洞条目: ${total} 条，严重度: ${JSON.stringify(counts)}`)

  for (const finding of findings.sort(
    (a, b) =>
      Math.max(...b.vulns.map((v) => SEVERITY_RANK[severityOf(v)] ?? 0)) -
      Math.max(...a.vulns.map((v) => SEVERITY_RANK[severityOf(v)] ?? 0)),
  )) {
    console.log(`    ${finding.subject.name}-${finding.subject.version}`)
    for (const vuln of finding.vulns) {
      const fixed = fixedVersions(vuln)
      console.log(
        `      - [${severityOf(vuln)}] ${vuln.id}${fixed.length ? ` → 修复版本 ${fixed.join('/')}` : '（暂无修复版本）'}`,
      )
      if (vuln.summary) console.log(`        ${vuln.summary}`)
    }
  }

  blocking += (counts.CRITICAL ?? 0) + (counts.HIGH ?? 0)
  report.images.push({
    ...meta,
    ecosystem,
    affectedCount: findings.length,
    vulnerabilityCount: total,
    severityCounts: counts,
    status: 'scanned',
    findings,
  })
}

const reportPath = join(tmpdir(), 'osv-image-report.json')
await writeFile(reportPath, JSON.stringify(report, null, 2), 'utf8')
console.log('')
console.log(`完整原始结果：${reportPath}`)
if (unsupported > 0) {
  console.log(`注意：${unsupported} 个镜像因 OSV 不覆盖其生态而**未核对**（上面已逐个标明）`)
}
process.exit(blocking > 0 ? 1 : 0)
