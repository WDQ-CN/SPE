import type { WriteResult } from '@/shared/types/dto'

/**
 * 写端点的响应怎么用（12 §E5 / §R-5 的前端一侧）。
 *
 * <p>抽成纯函数而不是写在组件的 `runWrite` 里：这里的判定只有两条分支（原地更新 / 换版本），
 * 而分支错了的后果是**后续每一次改删都被 3001 拒**——用户看到的现象是"保存后随便改点什么都说过期了"，
 * 很难联想到这里。纯函数可以直接单测，不必挂载组件。
 *
 * <p>背景：对**已发布**版本发起改动时，服务端会在同一事务里复制出新草稿再改。复制出的行**全是新行**
 * （id 变了），所以前端手里的 `dimensions[].id` 与 `indicators[].id` 全部作废——只能按新版本
 * **重新拉整棵树**，不能拿响应去就地改数组。
 *
 * <p>⚠️ **当前实现是"每次写成功都整树重取"**（`TemplateEditorView.runWrite` → `loadTree`），因此
 * 响应里的 `effectiveVersion` **暂时没有被消费**：只有 `effectiveVersionId` 用来判断要不要 `replace`
 * 到新版本。它仍然是指令值的一部分（12 §R-5 要求两个都回，前端将来若改成"就地更新手里那份树、
 * 省掉一次重取"就需要它）。这是**有意保留**，不是忘了用（03 §30 的 P2-1）。
 */
export interface WriteOutcome {
  /**
   * 换了版本：需要 `replace` 到新版本并重新拉树。
   *
   * <p>⚠️ **这个布尔不是独立状态，它就是下面那个比较的别名**（`copied ⇔ effectiveVersionId ≠ requestedVersionId`），
   * 所以不存在第三个分支、也不可能出现"`copied` 与两个 id 的比较结果不一致"的取值（03 §30 的 P2-3）。
   * 之所以不删掉它、把比较留给调用方：判断"要不要 `replace`"只该有一个落点，散到调用方就是
   * 下一处漂移；保留它反而让"分支写错"这件事有唯一的、被测过的位置（`effective-version.spec.ts`）。
   *
   * <p>⚠️ 也别把它当成后端 `EffectiveVersion.copied` 的同名物：后端那个的含义是**本次真的新建了草稿**
   * （复用一个已存在的同源草稿时为 `false`），而 `WriteResult` 根本没把它回给前端——这里只知道
   * **"生效版本 ≠ 请求版本"**。两种情况对前端是同一个动作（跳新版本 + 重取整树），但不该被读成"服务端一定复制了"。
   */
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
  // 两个返回值对 `effectiveVersionId === requestedVersionId` 是**穷尽且互斥**的，所以没有第三种结果
  // （03 §30 的 P2-3 说的"不可达分支"：不存在"`copied` 为真而两个 id 相等"的取值，反之亦然）。
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
