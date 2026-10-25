package com.spe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * 后端入口。
 *
 * <p>⚠️ 排除 {@code UserDetailsServiceAutoConfiguration}：本项目的认证是自研的 {@code AuthService + JWT}
 * （全仓没有任何 {@code UserDetailsService}，见 {@code PasswordEncoderConfig} 的注释），但这个 auto-config
 * 会在"容器里没有人自己的 UserDetailsService"时兜底装配一个**内存用户**并把随机口令打进启动日志
 * （实测 {@code docker logs spe-backend} 里有 {@code Using generated security password: …}，违 06 §2.8，
 * 即 03 §38 的 P2-20）。口令本身不可用（实测拿它登录仍 401），但"暴露一个没人用的凭据"这件事不该留在日志里。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class SpeBackendApplication {

  public static void main(String[] args) {
    SpringApplication.run(SpeBackendApplication.class, args);
  }
}
