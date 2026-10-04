package com.spe.context.notify.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * `notification` 的 Mapper（notify 上下文唯一一张表，05 §8.2 的 `idx(receiver_id, is_read)`
 * 让未读角标与列表共用同一个索引前缀）。
 *
 * <p>列表按 `id DESC` 排（自增主键即时间序），因此不再另建 created_at 排序索引（02 §3.2）。
 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {

  @Insert(
      """
      <script>
      INSERT INTO notification (receiver_id, type, title, content, is_read) VALUES
      <foreach item="item" collection="items" separator=",">
        (#{item.receiverId}, #{item.type}, #{item.title}, #{item.content}, 0)
      </foreach>
      </script>
      """)
  int insertBatch(@Param("items") List<Notification> items);

  @Select(
      "SELECT id, receiver_id, type, title, content, is_read, created_at FROM notification"
          + " WHERE receiver_id = #{receiverId} ORDER BY id DESC LIMIT #{size} OFFSET #{offset}")
  List<Notification> findPage(
      @Param("receiverId") long receiverId, @Param("offset") long offset, @Param("size") int size);

  @Select("SELECT COUNT(*) FROM notification WHERE receiver_id = #{receiverId}")
  long countByReceiver(@Param("receiverId") long receiverId);

  @Select(
      "SELECT COUNT(*) FROM notification WHERE receiver_id = #{receiverId} AND is_read = 0")
  long countUnread(@Param("receiverId") long receiverId);

  /**
   * 全部已读（01 SM6 最小版）：只动**自己的**未读行——`receiver_id` 是这里的第二层过滤，
   * 与"只看自己的通知"同一口径（05 §9：过滤不依赖前端）。0 行不是错误（本来就没有未读）。
   */
  @Update("UPDATE notification SET is_read = 1 WHERE receiver_id = #{receiverId} AND is_read = 0")
  int markAllRead(@Param("receiverId") long receiverId);
}
