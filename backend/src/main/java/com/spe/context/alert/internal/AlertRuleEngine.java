package com.spe.context.alert.internal;

import com.spe.context.system.api.AlertRules;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 预警规则的**纯函数**判定（01 W1；22 §2 D2/D3）。
 *
 * <p>三条规则全部是简单谓词，不引规则框架。输入是本次定稿结果 + 关键维度集合 + 该供应商最近的有效周期；
 * 输出是待落库的草稿（type/title/content）。跨期口径：
 * <ul>
 *   <li>① 总分 &lt; 阈值；
 *   <li>② **每个**关键维度 &lt; 阈值（一个维度一条）；
 *   <li>③ 连续 N 次下降：需要 N+1 个**日历相邻**季度（跨年 Q1→上一年 Q4），累计降幅 = 最早 − 本期 ≥ X；
 *       任一相邻季度缺数据 ⇒ 重置（不触发）。
 * </ul>
 */
public final class AlertRuleEngine {

  private AlertRuleEngine() {}

  /** 维度输入（key = 该维度在**本评估单**所属模板版本里是否关键）。 */
  public record DimensionInput(
      long dimensionId, String dimensionName, BigDecimal score, boolean key) {}

  /** 周期分数输入。 */
  public record PeriodInput(int year, int quarter, BigDecimal totalScore) {}

  /** 一次定稿的完整输入。 */
  public record Evaluation(
      long taskSupplierId,
      long supplierId,
      String supplierName,
      int year,
      int quarter,
      BigDecimal totalScore,
      List<DimensionInput> dimensions,
      List<PeriodInput> history,
      boolean firstEffective,
      AlertRules rules) {}

  /** 待落库的草稿。 */
  public record Draft(String type, String title, String content) {}

  public static List<Draft> evaluate(Evaluation evaluation) {
    List<Draft> drafts = new ArrayList<>();
    AlertRules rules = evaluation.rules();
    if (rules == null || !rules.enabled()) {
      return drafts;
    }
    String period = period(evaluation.year(), evaluation.quarter());

    BigDecimal total = evaluation.totalScore();
    if (total != null && total.compareTo(BigDecimal.valueOf(rules.totalThreshold())) < 0) {
      drafts.add(
          new Draft(
              AlertTypes.TOTAL_LOW,
              "总分低于阈值",
              "供应商 "
                  + evaluation.supplierName()
                  + " 的 "
                  + period
                  + " 总分 "
                  + plain(total)
                  + " 低于阈值 "
                  + rules.totalThreshold()));
    }

    for (DimensionInput dimension : evaluation.dimensions()) {
      if (!dimension.key() || dimension.score() == null) {
        continue;
      }
      if (dimension.score().compareTo(BigDecimal.valueOf(rules.keyDimensionThreshold())) < 0) {
        drafts.add(
            new Draft(
                AlertTypes.KEY_DIMENSION_LOW,
                "关键维度低于阈值",
                "供应商 "
                    + evaluation.supplierName()
                    + " 的 "
                    + period
                    + " 关键维度「"
                    + dimension.dimensionName()
                    + "」得分 "
                    + plain(dimension.score())
                    + " 低于阈值 "
                    + rules.keyDimensionThreshold()));
      }
    }

    // 趋势类只在「本期首次生效」时触发（01 W2）：解锁重评再定稿仍会重算总分/关键维度，
    // 但不得重复提醒「连续下降」。判定由定稿方按审批轮次给出，引擎只消费，不反查状态。
    if (evaluation.firstEffective()) {
      Draft trend = trend(evaluation);
      if (trend != null) {
        drafts.add(trend);
      }
    }
    return drafts;
  }

  /** 规则③：连续 N 次下降 + 累计降幅 ≥ X；跳期重置。 */
  private static Draft trend(Evaluation evaluation) {
    int periods = evaluation.rules().trendPeriods();
    Map<Integer, BigDecimal> scores = new HashMap<>();
    for (PeriodInput period : evaluation.history()) {
      if (period.totalScore() != null) {
        scores.put(key(period.year(), period.quarter()), period.totalScore());
      }
    }
    // 本次事件是当前周期的权威值（同事务查询也会看到它；这里再覆盖一次，避免夹具/事件不一致）
    if (evaluation.totalScore() != null) {
      scores.put(key(evaluation.year(), evaluation.quarter()), evaluation.totalScore());
    }

    int year = evaluation.year();
    int quarter = evaluation.quarter();
    List<BigDecimal> chain = new ArrayList<>(periods + 1);
    List<String> labels = new ArrayList<>(periods + 1);
    for (int step = 0; step <= periods; step++) {
      BigDecimal score = scores.get(key(year, quarter));
      if (score == null) {
        return null; // 缺期（跳期）⇒ 连续计数重置
      }
      chain.add(score);
      labels.add(period(year, quarter));
      quarter -= 1;
      if (quarter == 0) {
        quarter = 4;
        year -= 1;
      }
    }
    // chain[0] = 本期，chain[N] = 最早；每一步都必须严格下降
    for (int step = 0; step < periods; step++) {
      if (chain.get(step).compareTo(chain.get(step + 1)) >= 0) {
        return null;
      }
    }
    BigDecimal drop = chain.get(periods).subtract(chain.get(0));
    if (drop.compareTo(BigDecimal.valueOf(evaluation.rules().trendDrop())) < 0) {
      return null;
    }
    String content =
        "供应商 "
            + evaluation.supplierName()
            + " 最近 "
            + (periods + 1)
            + " 个连续季度总分从 "
            + plain(chain.get(periods))
            + "（"
            + labels.get(periods)
            + "）降至 "
            + plain(chain.get(0))
            + "（"
            + labels.get(0)
            + "），累计下降 "
            + plain(drop)
            + " 分";
    return new Draft(AlertTypes.TREND_DECLINE, "总分连续下降", content);
  }

  private static int key(int year, int quarter) {
    return year * 4 + quarter;
  }

  private static String period(int year, int quarter) {
    return year + " Q" + quarter;
  }

  private static String plain(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }
}
