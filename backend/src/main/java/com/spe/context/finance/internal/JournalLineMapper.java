package com.spe.context.finance.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 会计分录行 Mapper；聚合查询供试算平衡与 AP 对账使用。 */
@Mapper
public interface JournalLineMapper extends BaseMapper<JournalLine> {

  String COLUMNS = "id, entry_id, line_no, account_id, debit, credit, description";

  @Select("SELECT " + COLUMNS + " FROM finance_journal_line WHERE entry_id = #{entryId}"
      + " ORDER BY line_no")
  List<JournalLine> findByEntryId(@Param("entryId") long entryId);

  /**
   * 按科目汇总已过账分录。
   *
   * @param accountIds 为空表示全部科目；传 AP 科目集合时用于子账对账
   */
  @Select(
      """
      <script>
      SELECT l.account_id AS accountId,
             COALESCE(SUM(l.debit), 0) AS debitTotal,
             COALESCE(SUM(l.credit), 0) AS creditTotal
      FROM finance_journal_line l
      JOIN finance_journal_entry e ON e.id = l.entry_id
      WHERE e.company_id = #{companyId} AND e.status = 'POSTED'
        <if test="from != null">AND e.entry_date &gt;= #{from}</if>
        <if test="to != null">AND e.entry_date &lt;= #{to}</if>
        <if test="accountIds != null and accountIds.size() > 0">
          AND l.account_id IN
          <foreach collection="accountIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        </if>
      GROUP BY l.account_id
      ORDER BY l.account_id
      </script>
      """)
  List<AccountTotals> totals(
      @Param("companyId") long companyId,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to,
      @Param("accountIds") Collection<Long> accountIds);

  record AccountTotals(long accountId, BigDecimal debitTotal, BigDecimal creditTotal) {}
}
