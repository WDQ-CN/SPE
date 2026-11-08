package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 竞价结果 Mapper。 */
@Mapper
public interface AuctionResultMapper extends BaseMapper<AuctionResult> {

  String COLUMNS =
      "id, auction_id, lot_id, supplier_id, bid_id, amount, status, approved_by, approved_at,"
          + " version, created_by, created_at, updated_at";

  @Select("SELECT " + COLUMNS + " FROM auction_result WHERE id = #{id}")
  Optional<AuctionResult> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM auction_result WHERE id = #{id} FOR UPDATE")
  Optional<AuctionResult> lockById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM auction_result WHERE auction_id = #{auctionId} ORDER BY lot_id")
  List<AuctionResult> findByAuctionId(@Param("auctionId") long auctionId);

  @Update(
      """
      UPDATE auction_result
      SET status = 'APPROVED', approved_by = #{operator}, approved_at = #{now}, version = version + 1
      WHERE id = #{id} AND status = 'DRAFT'
      """)
  int markApproved(
      @Param("id") long id, @Param("operator") long operator, @Param("now") LocalDateTime now);

  @Update(
      """
      UPDATE auction_result SET status = 'PO_CREATED', version = version + 1
      WHERE id = #{id} AND status = 'APPROVED'
      """)
  int markPoCreated(@Param("id") long id);
}
