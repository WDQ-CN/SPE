import type { WriteResult } from '@/shared/types/dto'

/**
 * 写端点的响应怎么用（12 §E5 / §R-5 的前端一侧）。
 *
 * <p>抽成纯函数而不是写在组件的 `applyWriteResult` 里：这段判定有三个分支（原地更新 / 自动复制 /
 * 复制但编号未变），而分支错了的后果是**后续每一次改删都被 3001 拒**——用户看到的现象是
 * "保存后随便改点什么都说过期了"，很难联想到这里。纯函数可以直接单测，不必挂载组件。
 *
 * <p>背景：对**已发布**版本发起改动时，服务端会在同一事务里复制出新草稿再改。复制出的行**全是新行**
 * （id 变了），所以前端手里的 `dimensions[].id` 与 `indicators[].id` 全部作废——只能按新版本
 * **重新拉整棵树**，不能拿响应去就地改数组。
 */
export interface WriteOutcome {
  /** 发生自动复制：需要 replace 到新版本并重新拉树 */
  copied: boolean
  /** 目标版本 id（原地更新时等于请求的版本） */
  effectiveVersionId: number
  /** 用户可见的提示；无需提示时为空串 */
  message: string
}

/**
 * @param result 写端点返回的 {@link WriteResult}
 * @param requestedVersionId 发起写操作时前端持有的版本 id
 * @param requestedVersionNo 该版本的版本号（仅用于文案）
 */
export function interpretWriteResult(
  result: WriteResult,
  requestedVersionId: number,
  requestedVersionNo: number,
): WriteOutcome {
  if (result.effectiveVersionId === requestedVersionId) {
    return { copied: false, effectiveVersionId: requestedVersionId, message: '' }
  }
  return {
    copied: true,
    effectiveVersionId: result.effectiveVersionId,
    message:
      `v${requestedVersionNo} 已发布且不可修改，系统已基于它复制出新草稿，` +
      `本次改动落在新草稿上（后续编辑请在这个草稿里继续）。`,
  }
}
