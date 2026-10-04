import { describe, expect, it } from 'vitest'

import type { Role } from '@/shared/types/enums'

import { mySheetsEmptyHint, sheetPermissions } from './sheet-status'

const EVALUATOR: Role[] = ['EVALUATOR']
const MANAGER: Role[] = ['MANAGER']

describe('sheetPermissions', () => {
  it('评分中 + 已加载 + 是我的单子 ⇒ 可编辑可提交', () => {
    const permissions = sheetPermissions('SCORING', true, true, EVALUATOR)
    expect(permissions.canEdit).toBe(true)
    expect(permissions.canSubmit).toBe(true)
    expect(permissions.readOnlyHint).toBe('')
  })

  it('⚠️ 数据还没加载时**写入口必须关闭**（切单窗口期，03 §31.7.16 的 P2-1）', () => {
    const permissions = sheetPermissions('SCORING', false, true, EVALUATOR)
    expect(permissions.canEdit).toBe(false)
    expect(permissions.canSubmit).toBe(false)
    expect(permissions.readOnlyHint).toBe('')
  })

  it('已提交 ⇒ 只读，并说明原因', () => {
    const permissions = sheetPermissions('SUBMITTED', true, true, EVALUATOR)
    expect(permissions.canEdit).toBe(false)
    expect(permissions.readOnlyHint).toContain('已提交')
  })

  it('待指派 ⇒ 只读，并提示等采购经理指派', () => {
    const permissions = sheetPermissions('UNASSIGNED', true, false, EVALUATOR)
    expect(permissions.canEdit).toBe(false)
    expect(permissions.readOnlyHint).toContain('指派')
  })

  it('评分中但不是我的单子 ⇒ 只读（横向越权的界面侧；后端仍会 4002）', () => {
    const permissions = sheetPermissions('SCORING', true, false, EVALUATOR)
    expect(permissions.canEdit).toBe(false)
    expect(permissions.readOnlyHint).toContain('不是指派给你')
  })

  it('改派入口：采购经理在「待指派/评分中」可用，已提交不可用；评估人一律不可用', () => {
    expect(sheetPermissions('UNASSIGNED', true, false, MANAGER).canReassign).toBe(true)
    expect(sheetPermissions('SCORING', true, true, MANAGER).canReassign).toBe(true)
    expect(sheetPermissions('SUBMITTED', true, true, MANAGER).canReassign).toBe(false)
    expect(sheetPermissions('UNASSIGNED', true, false, EVALUATOR).canReassign).toBe(false)
  })

  it('待办空态文案按角色区分（下一步动作不同）', () => {
    expect(mySheetsEmptyHint(MANAGER)).toContain('发布任务')
    expect(mySheetsEmptyHint(EVALUATOR)).toContain('指派')
  })
})
