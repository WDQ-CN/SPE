package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 竞价批次 Mapper。 */
@Mapper
public interface AuctionLotMapper extends BaseMapper<AuctionLot> {

  String COLUMNS =
      "id, auction_id, line_no, item_id, qty, uom, start_price, reserve_price, min_decrement,"
          + " current_lowest_amount, current_lowest_bid_id, bid_count, version";

  @Select("SELECT " + COLUMNS + " FROM auction_lot WHERE auction_id = #{auctionId} ORDER BY line_no")
  List<AuctionLot> findByAuctionId(@Param("auctionId") long auctionId);

  @Select("SELECT " + COLUMNS + " FROM auction_lot WHERE id = #{id} AND auction_id = #{auctionId}")
  Optional<AuctionLot> findByIdAndAuction(@Param("id") long id, @Param("auctionId") long auctionId);

  @Select(
      "SELECT " + COLUMNS + " FROM auction_lot"
          + " WHERE id = #{id} AND auction_id = #{auctionId} FOR UPDATE")
  Optional<AuctionLot> findByIdAndAuctionForUpdate(
      @Param("id") long id, @Param("auctionId") long auctionId);

  @Update(
      """
      UPDATE auction_lot
      SET current_lowest_amount = #{amount}, current_lowest_bid_id = #{bidId},
          bid_count = bid_count + 1, version = version + 1
      WHERE id = #{id} AND version = #{version}
      """)
  int updateLowest(
      @Param("id") long id,
      @Param("amount") BigDecimal amount,
      @Param("bidId") long bidId,
      @Param("version") int version);
}
