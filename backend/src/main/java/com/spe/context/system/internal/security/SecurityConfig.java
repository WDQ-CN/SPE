package com.spe.context.system.internal.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全链形态（09 D6）：无状态 JWT，{@code /api/ping} 与 {@code /actuator/health} 放行，
 * 其余（含全部 {@code /api/**}）需认证；401/403 由处理器输出 Result。
 *
 * <p>P0 无真实登录入口，令牌由 P1 的 system 上下文签发。CORS 不需要配置：
 * 开发期走 Vite 代理、生产由 Nginx 同源反代（05 §4.2）。
 *
 * <p>过滤器与处理器经 {@code @Bean} 方法参数注入而非构造器字段：{@link JwtAuthenticationFilter}
 * 依赖本类定义的 {@code jwtService}，若用构造器注入会形成 Bean 循环依赖（Boot 3 默认禁止）。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

  /** JwtService 不带 Spring 注解，便于单元测试直接构造。 */
  @Bean
  public JwtService jwtService(JwtProperties jwtProperties) {
    return new JwtService(jwtProperties);
  }

  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      JwtAuthenticationFilter jwtAuthenticationFilter,
      RestAuthenticationEntryPoint authenticationEntryPoint,
      RestAccessDeniedHandler accessDeniedHandler)
      throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests -> requests.requestMatchers("/api/ping", "/actuator/health").permitAll()
                .anyRequest().authenticated())
        .exceptionHandling(
            handling -> handling.authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler))
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
}
