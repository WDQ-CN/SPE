import type { Page } from '@playwright/test'

/**
 * 视觉回归的固定数据源：所有页面只依赖这些响应，不连后端、不读时钟。
 * 截图里出现的日期、编号、分数都必须来自这里，否则基线会每天漂移。
 */

const pageResult = (list: unknown[], total = list.length) => ({ list, total, page: 1, size: 20 })

const ok = (data: unknown) =>
  JSON.stringify({ code: 0, message: 'ok', data })

const rankingRows = [
  { supplierId: 1, supplierCode: 'DEMO-001', supplierName: '华东精密制造', category: '结构件', totalScore: 88.65, totalGrade: 'B', previousScore: 83.4, previousYear: 2026, previousQuarter: 2, delta: 5.25 },
  { supplierId: 2, supplierCode: 'DEMO-002', supplierName: '南方电子科技', category: '电子件', totalScore: 91.2, totalGrade: 'A', previousScore: 90.1, previousYear: 2026, previousQuarter: 2, delta: 1.1 },
  { supplierId: 3, supplierCode: 'DEMO-003', supplierName: '西部新材料', category: '原材料', totalScore: 76.4, totalGrade: 'C', previousScore: 79.8, previousYear: 2026, previousQuarter: 2, delta: -3.4 },
  { supplierId: 4, supplierCode: 'DEMO-004', supplierName: '北方物流服务', category: '物流', totalScore: 68.9, totalGrade: 'D', previousScore: null, previousYear: null, previousQuarter: null, delta: null },
]

const dimensions = [
  {
    dimensionId: 11, dimensionName: '质量', weight: 30, score: 92.6, grade: 'A', complete: true,
    indicators: [
      { indicatorId: 111, code: 'IQC_PASS_RATE', name: '来料合格率', weight: 60, type: 'QUANT', scoreMode: 'RATIO', rawValue: 96.5, scoreValue: 92.5, unit: '%', source: 'MANUAL' },
      { indicatorId: 112, code: 'CUSTOMER_CLAIMS', name: '客诉次数', weight: 40, type: 'QUANT', scoreMode: 'DIRECT', rawValue: 2, scoreValue: 93, unit: '次', source: 'MANUAL' },
    ],
  },
  {
    dimensionId: 12, dimensionName: '交付', weight: 25, score: 82, grade: 'B', complete: true,
    indicators: [
      { indicatorId: 121, code: 'OTD_RATE', name: '准时交付率', weight: 100, type: 'QUANT', scoreMode: 'RATIO', rawValue: 94, scoreValue: 82, unit: '%', source: 'IMPORT' },
    ],
  },
  {
    dimensionId: 13, dimensionName: '成本', weight: 25, score: null, grade: null, complete: false,
    indicators: [
      { indicatorId: 131, code: 'COST_SAVING', name: '年度降本率', weight: 100, type: 'QUANT', scoreMode: 'TARGET', rawValue: null, scoreValue: null, unit: '%', source: null },
    ],
  },
  {
    dimensionId: 14, dimensionName: '服务', weight: 20, score: 88, grade: 'B', complete: true,
    indicators: [
      { indicatorId: 141, code: 'SERVICE_SCORE', name: '服务响应评分', weight: 100, type: 'QUAL', scoreMode: null, rawValue: 88, scoreValue: 88, unit: null, source: 'MANUAL' },
    ],
  },
]

export async function installAuth(page: Page, roles: string[] = ['ADMIN']): Promise<void> {
  await page.addInitScript((sessionRoles) => {
    localStorage.setItem('spe.token', 'visual-token')
    localStorage.setItem(
      'spe.auth.profile',
      JSON.stringify({ name: '视觉核对', roles: sessionRoles, department: 'PURCHASE' }),
    )
  }, roles)
}

export async function installVisualMocks(page: Page): Promise<void> {
  await page.route('**/api/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname
    const reply = (data: unknown) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: ok(data) })

    if (pathname === '/api/notifications/unread-count') return reply(2)
    if (pathname === '/api/notifications') return reply(pageResult([]))

    if (pathname === '/api/suppliers') {
      return reply(pageResult([
        { id: 1, code: 'DEMO-001', name: '华东精密制造', category: '结构件', status: 'ENABLED', version: 1 },
        { id: 2, code: 'DEMO-002', name: '南方电子科技', category: '电子件', status: 'ENABLED', version: 1 },
        { id: 3, code: 'DEMO-003', name: '西部新材料', category: '原材料', status: 'DISABLED', version: 2 },
        { id: 4, code: 'DEMO-004', name: '北方物流服务', category: '物流', status: 'ENABLED', version: 1 },
      ]))
    }

    if (pathname === '/api/tasks/9') {
      return reply({
        id: 9, name: '2026 年 Q3 供应商绩效评估', year: 2026, quarter: 3,
        templateId: 3, templateVersionId: 31, deadline: '2026-09-30', status: 'IN_PROGRESS', version: 2,
        suppliers: [
          { id: 1, code: 'DEMO-001', name: '华东精密制造' },
          { id: 2, code: 'DEMO-002', name: '南方电子科技' },
          { id: 3, code: 'DEMO-003', name: '西部新材料' },
        ],
        approvers: [
          { userId: 2, name: '张岚', seq: 1 },
          { userId: 3, name: '周启', seq: 2 },
        ],
        sheetCount: 12,
        unsubmittedSheetCount: 7,
      })
    }
    if (pathname === '/api/tasks/9/progress') {
      return reply({
        taskId: 9,
        rows: [
          { taskSupplierId: 21, supplierId: 1, supplierCode: 'DEMO-001', supplierName: '华东精密制造', status: 'APPROVING', currentNode: 2, trialScore: 88.65, trialGrade: 'B', complete: true, unassignedSheets: 0, unsubmittedSheets: 0 },
          { taskSupplierId: 22, supplierId: 2, supplierCode: 'DEMO-002', supplierName: '南方电子科技', status: 'SCORING', currentNode: 0, trialScore: null, trialGrade: null, complete: false, unassignedSheets: 1, unsubmittedSheets: 2 },
          { taskSupplierId: 23, supplierId: 3, supplierCode: 'DEMO-003', supplierName: '西部新材料', status: 'SCORING', currentNode: 0, trialScore: null, trialGrade: null, complete: false, unassignedSheets: 0, unsubmittedSheets: 4 },
        ],
      })
    }
    if (pathname === '/api/tasks/9/sheets') {
      const sheet = (sheetId: number, supplierId: number, supplierCode: string, supplierName: string, dimensionId: number, dimensionName: string, assigneeId: number | null, assigneeName: string | null, status: string, version: number) =>
        ({ sheetId, taskSupplierId: supplierId + 20, supplierId, supplierCode, supplierName, dimensionId, dimensionName, assigneeId, assigneeName, status, version })
      return reply([
        sheet(501, 1, 'DEMO-001', '华东精密制造', 11, '质量', 5, '李衡', 'SCORING', 3),
        sheet(502, 1, 'DEMO-001', '华东精密制造', 12, '交付', 6, '王速', 'SUBMITTED', 2),
        sheet(503, 1, 'DEMO-001', '华东精密制造', 13, '成本', null, null, 'UNASSIGNED', 1),
        sheet(504, 2, 'DEMO-002', '南方电子科技', 11, '质量', 5, '李衡', 'SCORING', 1),
        sheet(505, 2, 'DEMO-002', '南方电子科技', 12, '交付', null, null, 'UNASSIGNED', 1),
        sheet(506, 2, 'DEMO-002', '南方电子科技', 14, '服务', 6, '王速', 'SCORING', 1),
      ])
    }

    if (pathname === '/api/sheets/501/scores') {
      return reply({
        sheetId: 501, taskId: 9, taskSupplierId: 21, dimensionId: 11, dimensionName: '质量',
        status: 'SCORING', version: 3, assigneeId: 5,
        indicators: [
          { indicatorId: 111, code: 'IQC_PASS_RATE', name: '来料合格率', unit: '%', type: 'QUANT', scoreMode: 'RATIO', weight: 60, targetValue: null, standard: '≥98% 满分，每降 1% 扣 5 分', rawValue: 96.5, scoreValue: 92.5 },
          { indicatorId: 112, code: 'CUSTOMER_CLAIMS', name: '客诉次数', unit: '次', type: 'QUANT', scoreMode: 'DIRECT', weight: 20, targetValue: null, standard: '0 次满分', rawValue: 2, scoreValue: 93 },
          { indicatorId: 113, code: 'AUDIT_CLOSURE', name: '审核整改关闭率', unit: '%', type: 'QUANT', scoreMode: 'RATIO', weight: 10, targetValue: null, standard: '100% 满分', rawValue: null, scoreValue: null },
          { indicatorId: 114, code: 'QUAL_RESPONSE', name: '质量响应评分', unit: null, type: 'QUAL', scoreMode: null, weight: 10, targetValue: null, standard: '按响应及时性评分', rawValue: null, scoreValue: null },
        ],
      })
    }
    if (pathname === '/api/sheets/501/preview') {
      return reply({
        sheetId: 501, dimensionId: 11,
        taskSupplier: {
          totalScore: null, totalGrade: null, complete: false,
          sheets: [
            { dimensionId: 11, dimensionName: '质量', weight: 30, indicators: [], score: 92.6, grade: 'A', complete: true },
            { dimensionId: 12, dimensionName: '交付', weight: 25, indicators: [], score: 82, grade: 'B', complete: true },
            { dimensionId: 13, dimensionName: '成本', weight: 25, indicators: [], score: null, grade: null, complete: false },
          ],
        },
      })
    }

    if (pathname === '/api/analytics/periods') return reply([{ year: 2026, quarter: 3 }, { year: 2026, quarter: 2 }])
    if (pathname === '/api/analytics/suppliers') {
      return reply(rankingRows.map((row) => ({ id: row.supplierId, code: row.supplierCode, name: row.supplierName, category: row.category })))
    }
    if (pathname === '/api/analytics/ranking') return reply({ year: 2026, quarter: 3, rows: rankingRows })
    if (pathname === '/api/analytics/grade-distribution') {
      return reply([{ grade: 'A', count: 1 }, { grade: 'B', count: 2 }, { grade: 'C', count: 1 }, { grade: 'D', count: 0 }])
    }
    if (pathname === '/api/analytics/trend') {
      return reply({
        supplierId: 1, supplierCode: 'DEMO-001', supplierName: '华东精密制造',
        periods: [
          { year: 2026, quarter: 1, totalScore: 80.2, totalGrade: 'B', dimensions: [{ dimensionName: '质量', score: 84, grade: 'B' }, { dimensionName: '交付', score: 78, grade: 'C' }] },
          { year: 2026, quarter: 2, totalScore: 83.4, totalGrade: 'B', dimensions: [{ dimensionName: '质量', score: 88, grade: 'B' }, { dimensionName: '交付', score: 80, grade: 'B' }] },
          { year: 2026, quarter: 3, totalScore: 88.65, totalGrade: 'B', dimensions: [{ dimensionName: '质量', score: 92.6, grade: 'A' }, { dimensionName: '交付', score: 82, grade: 'B' }, { dimensionName: '成本', score: 86, grade: 'B' }, { dimensionName: '服务', score: 88, grade: 'B' }] },
        ],
      })
    }
    if (pathname === '/api/analytics/compare') {
      return reply({
        year: 2026, quarter: 3,
        rows: rankingRows.slice(0, 2).map((row) => ({
          supplierId: row.supplierId, supplierCode: row.supplierCode, supplierName: row.supplierName,
          totalScore: row.totalScore, totalGrade: row.totalGrade,
          dimensions: [
            { dimensionName: '质量', score: row.supplierId === 1 ? 92.6 : 95 },
            { dimensionName: '交付', score: row.supplierId === 1 ? 82 : 88 },
            { dimensionName: '成本', score: 86 },
            { dimensionName: '服务', score: row.supplierId === 1 ? 88 : 84 },
          ],
        })),
      })
    }

    if (pathname === '/api/task-suppliers/21/report') {
      return reply({
        taskSupplierId: 21, taskId: 9, taskName: '2026 年 Q3 供应商绩效评估', year: 2026, quarter: 3,
        deadline: '2026-09-30', supplierId: 1, supplierCode: 'DEMO-001', supplierName: '华东精密制造',
        status: 'EFFECTIVE', currentNode: 0, round: 1, version: 6, scoreMode: 'SNAPSHOT',
        totalScore: 88.65, totalGrade: 'B', complete: true, actableNow: false,
        dimensions,
        timeline: [
          { round: 1, seq: 1, approverId: 2, approverName: '采购经理 张岚', action: 'APPROVE', comment: '评分完整，同意生效。', createdAt: '2026-10-02T02:10:00Z' },
        ],
      })
    }

    if (pathname === '/api/template-versions/31') {
      return reply({
        templateId: 3, templateName: '制造类供应商评估', versionId: 31, versionNo: 3, status: 'DRAFT', version: 4,
        dimensions: [
          {
            id: 11, name: '质量', weight: 40, sortNo: 1, suggestedDept: 'QUALITY', isKey: true, version: 2,
            indicators: [
              { id: 111, name: '来料合格率', code: 'IQC_PASS_RATE', weight: 60, type: 'QUANT', scoreMode: 'RATIO', unit: '%', targetValue: null, standard: '≥98% 满分，每降 1% 扣 5 分', sortNo: 1, version: 1 },
              { id: 112, name: '客诉次数', code: 'CUSTOMER_CLAIMS', weight: 40, type: 'QUANT', scoreMode: 'DIRECT', unit: '次', targetValue: null, standard: '0 次满分', sortNo: 2, version: 1 },
            ],
          },
          {
            id: 12, name: '交付', weight: 35, sortNo: 2, suggestedDept: 'DELIVERY', isKey: false, version: 1,
            indicators: [
              { id: 121, name: '准时交付率', code: 'OTD_RATE', weight: 100, type: 'QUANT', scoreMode: 'RATIO', unit: '%', targetValue: null, standard: '≥95% 满分', sortNo: 1, version: 1 },
            ],
          },
          {
            id: 13, name: '成本', weight: 25, sortNo: 3, suggestedDept: 'PURCHASE', isKey: false, version: 1,
            indicators: [
              { id: 131, name: '年度降本率', code: 'COST_SAVING', weight: 100, type: 'QUANT', scoreMode: 'TARGET', unit: '%', targetValue: 5, standard: null, sortNo: 1, version: 1 },
            ],
          },
        ],
      })
    }

    if (pathname === '/api/settings/grade-thresholds') return reply({ a: 90, b: 80, c: 70, d: 60 })
    if (pathname === '/api/settings/alert-rules') {
      return reply({ enabled: true, totalThreshold: 70, keyDimensionThreshold: 65, trendPeriods: 3, trendDrop: 10 })
    }

    return route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({ code: 9999, message: `visual mock missing: ${pathname}`, data: null }),
    })
  })
}
