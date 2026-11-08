package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 竞价邀请 Mapper。 */
@Mapper
public interface AuctionInvitationMapper extends BaseMapper<AuctionInvitation> {

  String COLUMNS = "id, auction_id, supplier_id, status, invited_by, invited_at, accepted_at";

  @Select(
      "SELECT " + COLUMNS + " FROM auction_invitation"
          + " WHERE auction_id = #{auctionId} AND supplier_id = #{supplierId}")
  Optional<AuctionInvitation> findByAuctionAndSupplier(
      @Param("auctionId") long auctionId, @Param("supplierId") long supplierId);

  @Select("SELECT " + COLUMNS + " FROM auction_invitation WHERE auction_id = #{auctionId}"
      + " ORDER BY supplier_id")
  List<AuctionInvitation> findByAuctionId(@Param("auctionId") long auctionId);
}
