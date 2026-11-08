package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 竞价出价 Mapper。 */
@Mapper
public interface AuctionBidMapper extends BaseMapper<AuctionBid> {

  String COLUMNS =
      "id, auction_id, lot_id, supplier_id, amount, status, idempotency_key, request_hash,"
          + " below_reserve, client_ip, submitted_at";

  @Select(
      "SELECT " + COLUMNS + " FROM auction_bid"
          + " WHERE lot_id = #{lotId} AND supplier_id = #{supplierId}"
          + " AND idempotency_key = #{idempotencyKey}")
  Optional<AuctionBid> findByKey(
      @Param("lotId") long lotId,
      @Param("supplierId") long supplierId,
      @Param("idempotencyKey") String idempotencyKey);

  @Select(
      "SELECT " + COLUMNS + " FROM auction_bid"
          + " WHERE auction_id = #{auctionId} AND supplier_id = #{supplierId}"
          + " ORDER BY id DESC")
  List<AuctionBid> findByAuctionAndSupplier(
      @Param("auctionId") long auctionId, @Param("supplierId") long supplierId);

  @Select("SELECT COUNT(DISTINCT supplier_id) FROM auction_bid"
      + " WHERE lot_id = #{lotId} AND status IN ('VALID','OUTBID','WINNING')")
  long countDistinctSuppliers(@Param("lotId") long lotId);

  @Select("SELECT COUNT(*) FROM auction_bid"
      + " WHERE auction_id = #{auctionId} AND supplier_id = #{supplierId}")
  long countByAuctionAndSupplier(
      @Param("auctionId") long auctionId, @Param("supplierId") long supplierId);

  /** 比自己金额更低的供应商数 + 1（反向竞价排名）。 */
  @Select("SELECT COUNT(DISTINCT supplier_id) FROM auction_bid"
      + " WHERE lot_id = #{lotId} AND amount < #{amount}"
      + " AND status IN ('VALID','OUTBID','WINNING')")
  long countLowerSuppliers(@Param("lotId") long lotId, @Param("amount") java.math.BigDecimal amount);

  @Update(
      "UPDATE auction_bid SET status = 'OUTBID'"
          + " WHERE lot_id = #{lotId} AND status = 'VALID' AND id <> #{exceptBidId}")
  int outbidPrevious(@Param("lotId") long lotId, @Param("exceptBidId") long exceptBidId);
}
