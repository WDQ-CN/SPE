package com.spe.context.finance.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 会计凭证 Mapper。 */
@Mapper
public interface JournalEntryMapper extends BaseMapper<JournalEntry> {

  String COLUMNS =
      "id, doc_no, company_id, entry_date, source_type, source_id, currency, description,"
          + " total_amount, status, created_by, posted_at, version, created_at";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="sourceType != null and sourceType != ''">AND source_type = #{sourceType}</if>
        <if test="keyword != null and keyword != ''">AND doc_no LIKE CONCAT('%', #{keyword}, '%')</if>
        <if test="from != null">AND entry_date &gt;= #{from}</if>
        <if test="to != null">AND entry_date &lt;= #{to}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM finance_journal_entry " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<JournalEntry> findPage(
      @Param("companyId") Long companyId,
      @Param("sourceType") String sourceType,
      @Param("keyword") String keyword,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM finance_journal_entry " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("companyId") Long companyId,
      @Param("sourceType") String sourceType,
      @Param("keyword") String keyword,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  @Select("SELECT " + COLUMNS + " FROM finance_journal_entry WHERE id = #{id}")
  Optional<JournalEntry> findById(@Param("id") long id);

  @Select(
      "SELECT " + COLUMNS + " FROM finance_journal_entry"
          + " WHERE source_type = #{sourceType} AND source_id = #{sourceId}")
  Optional<JournalEntry> findBySource(
      @Param("sourceType") String sourceType, @Param("sourceId") long sourceId);
}
