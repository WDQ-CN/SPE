package com.spe.context.system.internal.web;

import com.spe.common.web.Result;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 连通性探针（09 D6 / WP1）：免认证，供前端登录页的「后端连通性」自检与部署探活使用。
 */
@RestController
public class PingController {

  @GetMapping("/api/ping")
  public Result<PingResponse> ping() {
    return Result.ok(new PingResponse("UP", Instant.now()));
  }

  /** 探针载荷，不承载业务语义。 */
  public record PingResponse(String status, Instant time) {
  }
}
