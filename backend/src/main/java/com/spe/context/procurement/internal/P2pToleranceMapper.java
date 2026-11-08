package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 三单匹配容差 Mapper。 */
@Mapper
public interface P2pToleranceMapper extends BaseMapper<P2pTolerance> {

  @Select(
      """
      SELECT id, company_id, dimension, percent, absolute, status
      FROM p2p_tolerance
      WHERE dimension = #{dimension} AND status = 'ACTIVE'
        AND (company_id = #{companyId} OR company_id IS NULL)
      ORDER BY CASE WHEN company_id IS NULL THEN 1 ELSE 0 END, id
      LIMIT 1
      """)
  Optional<P2pTolerance> findApplicable(
      @Param("dimension") MatchDimension dimension, @Param("companyId") long companyId);
}
