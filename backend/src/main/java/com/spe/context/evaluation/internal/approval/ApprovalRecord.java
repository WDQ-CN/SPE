package com.spe.context.evaluation.internal.approval;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 审批记录（V13 / 02 §3.3 / 15 §E2）：**只增不改** —— 一轮内一个节点只能有一条终局记录，
 * 由 {@code uk_approval_record(task_supplier_id, round, seq)} 在数据库层兜底（17 §4.1 D1-7 实测会拦）。
 *
 * <p>{@code round} 是记录身份的一部分：驳回重提后同一 {@code seq} 会再次产生记录（15 §E11），
 * 时间线按 {@code (round, seq)} 排序而不是 {@code created_at}（同一毫秒内顺序不稳）。
 * 本表没有状态列——状态在 {@code task_supplier} 上，迁移一律守卫 UPDATE（06 §2.4）；
 * 本表只被追加，从不被更新，故 {@code updated_at} 恒等于 {@code createdAt}（03 §37 P2-2 的已登记冗余）。
 */
@Data
@TableName("approval_record")
public class ApprovalRecord {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long taskSupplierId;

  /** 轮次（V13 加列）：插入时**显式写 1**，不依赖列默认值（17 §3 W1.7 / 15 §E11）。 */
  private Integer round;

  /** 节点序号，与 approval_node.seq 同口径。 */
  private Integer seq;

  /** 审批人（具体用户，限 MANAGER/ADMIN：01 §3 经 2026-10-25 裁决收窄）。 */
  private Long approverId;

  /** {@code APPROVE} / {@code REJECT}（15 §E2）。 */
  private String action;

  /** 审批意见；驳回必填由服务层判（01 FR-P1），不靠列约束。 */
  private String comment;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}
