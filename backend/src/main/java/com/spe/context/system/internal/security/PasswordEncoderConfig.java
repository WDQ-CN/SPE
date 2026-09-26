package com.spe.context.system.internal.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 口令编码器（01 §8：BCrypt）。
 *
 * <p>强度用库默认的 10（cost=10），与 `V5__seed_admin.sql` 里现场生成的种子哈希前缀 `$2a$10$` 一致——
 * 种子账号与新建账号的哈希强度必须相同，否则「种子的哈希能登录、新建的不能」会变成难查的谜题。
 *
 * <p>不注册为 `UserDetailsService`：本项目的认证是自研的 {@link AuthService} + JWT，
 * 不走 Spring Security 的表单登录（09 D6）。
 */
@Configuration
public class PasswordEncoderConfig {

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
