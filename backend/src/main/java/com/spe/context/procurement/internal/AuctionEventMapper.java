package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 竞价事件 Mapper。 */
@Mapper
public interface AuctionEventMapper extends BaseMapper<AuctionEvent> {

  String COLUMNS =
      "id, auction_no, company_id, title, currency, sealed, anti_sniping_seconds,"
          + " max_extensions, start_at, end_at, extension_count, result_visibility, status,"
          + " created_by, version, created_at, updated_at";

  @Select("SELECT " + COLUMNS + " FROM auction_event WHERE id = #{id}")
  Optional<AuctionEvent> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM auction_event WHERE id = #{id} FOR UPDATE")
  Optional<AuctionEvent> findByIdForUpdate(@Param("id") long id);

  @Select("SELECT CURRENT_TIMESTAMP(3)")
  LocalDateTime currentTime();

  @Select(
      """
      <script>SELECT id, auction_no, company_id, title, currency, sealed,
                     anti_sniping_seconds, max_extensions, start_at, end_at,
                     extension_count, result_visibility, status, created_by,
                     version, created_at, updated_at
      FROM auction_event
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>
      """)
  List<AuctionEvent> findPage(
      @Param("companyId") Long companyId,
      @Param("status") AuctionStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select(
      """
      <script>SELECT COUNT(*) FROM auction_event
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="status != null">AND status = #{status}</if>
      </where></script>
      """)
  long countBy(@Param("companyId") Long companyId, @Param("status") AuctionStatus status);

  @Select(
      """
      <script>SELECT e.id, e.auction_no, e.company_id, e.title, e.currency, e.sealed,
                     e.anti_sniping_seconds, e.max_extensions, e.start_at, e.end_at,
                     e.extension_count, e.result_visibility, e.status, e.created_by,
                     e.version, e.created_at, e.updated_at
      FROM auction_event e
      JOIN auction_invitation i ON i.auction_id = e.id
      WHERE i.supplier_id = #{supplierId}
      <if test="status != null">AND e.status = #{status}</if>
      ORDER BY e.id DESC LIMIT #{size} OFFSET #{offset}</script>
      """)
  List<AuctionEvent> findPageForSupplier(
      @Param("supplierId") long supplierId,
      @Param("status") AuctionStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select(
      """
      <script>SELECT COUNT(*) FROM auction_event e
      JOIN auction_invitation i ON i.auction_id = e.id
      WHERE i.supplier_id = #{supplierId}
      <if test="status != null">AND e.status = #{status}</if></script>
      """)
  long countForSupplier(
      @Param("supplierId") long supplierId, @Param("status") AuctionStatus status);

  @Update(
      """
      UPDATE auction_event SET status = #{target}, version = version + 1
      WHERE id = #{id} AND status = #{expected} AND version = #{version}
      """)
  int updateStatus(
      @Param("id") long id,
      @Param("target") AuctionStatus target,
      @Param("expected") AuctionStatus expected,
      @Param("version") int version);

  @Update(
      """
      UPDATE auction_event
      SET end_at = #{newEndAt}, extension_count = extension_count + 1, version = version + 1
      WHERE id = #{id} AND version = #{version}
      """)
  int extend(
      @Param("id") long id,
      @Param("newEndAt") LocalDateTime newEndAt,
      @Param("version") int version);
}
