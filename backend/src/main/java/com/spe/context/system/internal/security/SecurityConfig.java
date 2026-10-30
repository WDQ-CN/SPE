package com.spe.context.system.internal.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationEventPublisher;
import org.springframework.security.authorization.SpringAuthorizationEventPublisher;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全链形态（09 D6）：无状态 JWT，{@code /api/ping}、{@code /api/auth/login} 与
 * {@code /actuator/health} 放行，其余（含全部 {@code /api/**}）需认证；401/403 由处理器输出 Result。
 *
 * <p>P0 无真实登录入口，令牌由 P1 的 system 上下文签发（`AuthService`）。CORS 不需要配置：
 * 开发期走 Vite 代理、生产由 Nginx 同源反代（05 §4.2）。
 *
 * <p>过滤器与处理器经 {@code @Bean} 方法参数注入而非构造器字段：{@link JwtAuthenticationFilter}
 * 依赖本类定义的 {@code jwtService}，若用构造器注入会形成 Bean 循环依赖（Boot 3 默认禁止）。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({JwtProperties.class, LoginLimiterProperties.class})
public class SecurityConfig {

  /** JwtService 不带 Spring 注解，便于单元测试直接构造。 */
  @Bean
  public JwtService jwtService(JwtProperties jwtProperties) {
    return new JwtService(jwtProperties);
  }

  /**
   * 让方法级鉴权（`@PreAuthorize`）在被拒时发出 {@code AuthorizationDeniedEvent}（10 §E15）。
   *
   * <p>**必须显式注册**：Spring Security 6.5 的方法拦截器默认用 `NoOpAuthorizationEventPublisher`，
   * 不注册这个 Bean 时越权静默发生——403 照常返回，只是没有任何事件可监听，`ACCESS_DENIED`
   * 审计会「看起来实现了但一条都不落」。实测依据：不注册时集成测试的 ACCESS_DENIED 行为空。
   * `PrePostMethodSecurityConfiguration` 会按类型注入它。
   */
  @Bean
  public AuthorizationEventPublisher authorizationEventPublisher(ApplicationEventPublisher eventPublisher) {
    return new SpringAuthorizationEventPublisher(eventPublisher);
  }

  /**
   * 该过滤器由安全链显式挂载（见 {@code addFilterBefore}）。Boot 会把容器里的 Filter Bean 自动注册到
   * servlet 过滤链，若不禁用就会重复注册一次（第二次虽因上下文已认证而跳过，但顺序与语义都会失去预期）。
   */
  @Bean
  public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
      JwtAuthenticationFilter jwtAuthenticationFilter) {
    FilterRegistrationBean<JwtAuthenticationFilter> registration =
        new FilterRegistrationBean<>(jwtAuthenticationFilter);
    registration.setEnabled(false);
    return registration;
  }

  /**
   * 开放 API Token 过滤器同样由安全链显式挂载，禁止 Boot 自动注册（与 JWT 过滤器同一理由）。
   */
  @Bean
  public FilterRegistrationBean<ApiTokenAuthenticationFilter> apiTokenAuthenticationFilterRegistration(
      ApiTokenAuthenticationFilter apiTokenAuthenticationFilter) {
    FilterRegistrationBean<ApiTokenAuthenticationFilter> registration =
        new FilterRegistrationBean<>(apiTokenAuthenticationFilter);
    registration.setEnabled(false);
    return registration;
  }

  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      JwtAuthenticationFilter jwtAuthenticationFilter,
      ApiTokenAuthenticationFilter apiTokenAuthenticationFilter,
      RestAuthenticationEntryPoint authenticationEntryPoint,
      RestAccessDeniedHandler accessDeniedHandler)
      throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests ->
                requests
                    // 登录必须免认证：否则「未认证 → 401」会先于 AuthController 生效，永远登不进来
                    .requestMatchers("/api/ping", "/api/auth/login")
                    .permitAll()
                    // 健康检查免认证：容器 healthcheck 用它，且不展示组件细节（show-details=never）
                    .requestMatchers("/actuator/health")
                    .permitAll()
                    // 其余 actuator 端点收紧到 ADMIN：指标含 JVM/HTTP/DB 信息，没必要让普通登录用户看到。
                    // 这也是**纵深防御**——将来若有人误把 exposure 放宽成 "*"，这一层仍挡得住
                    // （actuator 的 env/configprops 会打印 JWT_SECRET，那是不能公开的东西，见 03 §19）。
                    .requestMatchers("/actuator/**")
                    .hasRole("ADMIN")
                    // 开放 API 只接受独立 Token（ADR-8）；JWT 用户没有 ROLE_API_TOKEN
                    .requestMatchers("/open/v1/**")
                    .hasRole("API_TOKEN")
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            handling -> handling.authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler))
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(apiTokenAuthenticationFilter, JwtAuthenticationFilter.class);
    return http.build();
  }
}
