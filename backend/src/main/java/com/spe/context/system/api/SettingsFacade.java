package com.spe.context.system.api;

/**
 * 系统设置门面（05 §4.3）：供其他上下文读取设置项。
 *
 * <p>P1 只开等级阈值（P4 定级要用）；预警规则、默认审批链随各自阶段在同一门面上追加方法。
 */
public interface SettingsFacade {

  /**
   * 等级阈值（01 §6.3）。设置键缺失时返回代码持有的默认值、**不落库**（10 §E9），
   * 因此调用方永远拿到一个可用的阈值对象，无需自己兜底。
   */
  GradeThresholds gradeThresholds();

  /**
   * 预警规则（01 W1 / SM3；M5 W2）。同阈值：键缺失返回代码默认值、不落库。
   *
   * <p>消费方是 alert 的定稿监听器（规则判定在定稿事务内同步执行）。
   */
  AlertRules alertRules();
}
