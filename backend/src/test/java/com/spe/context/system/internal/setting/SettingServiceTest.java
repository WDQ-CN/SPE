package com.spe.context.system.internal.setting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.GradeThresholds;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 设置服务的行为锁定（10 §E9 / 01 §6.3）。
 *
 * <p>核心两条：①设置键缺失时返回代码默认值且**不落库**（E9 的「默认值只存在于代码一处」）
 * ②阈值约束校验在服务层拦下（含允许相等）。
 */
@ExtendWith(MockitoExtension.class)
class SettingServiceTest {

  @Mock private SysSettingMapper sysSettingMapper;
  @Mock private AuditFacade auditFacade;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private SettingService settingService;

  @BeforeEach
  void setUp() {
    // 指标 Counter 用独立 registry：本类是纯单元测试，不启动 Spring 上下文
    settingService =
        new SettingService(
            sysSettingMapper,
            objectMapper,
            auditFacade,
            Counter.builder("test.settings.thresholds.loads").register(new SimpleMeterRegistry()));
  }

  private static GradeThresholds thresholds(String a, String b, String c, String d) {
    return new GradeThresholds(
        new BigDecimal(a), new BigDecimal(b), new BigDecimal(c), new BigDecimal(d));
  }

  @Test
  @DisplayName("键缺失：返回代码默认值，且不写库也不审计（10 §E9）")
  void missingSettingReturnsCodeDefaultsWithoutPersisting() {
    when(sysSettingMapper.findByKey(SettingService.GRADE_THRESHOLDS_KEY)).thenReturn(Optional.empty());

    GradeThresholds thresholds = settingService.gradeThresholds();

    assertThat(thresholds).isEqualTo(GradeThresholds.defaults());
    assertThat(thresholds).isEqualTo(thresholds("90", "80", "70", "0"));
    verify(sysSettingMapper, never()).upsert(anyString(), anyString());
    verifyNoInteractions(auditFacade);
  }

  @Test
  @DisplayName("存量值可解析：按库存值返回")
  void storedValueIsRead() {
    when(sysSettingMapper.findByKey(SettingService.GRADE_THRESHOLDS_KEY))
        .thenReturn(Optional.of(setting("{\"a\":95,\"b\":85,\"c\":75,\"d\":60}")));

    assertThat(settingService.gradeThresholds()).isEqualTo(thresholds("95", "85", "75", "60"));
  }

  @Test
  @DisplayName("存量值损坏：回落默认值而不是把整个设置页打挂")
  void corruptedValueFallsBackToDefaults() {
    when(sysSettingMapper.findByKey(SettingService.GRADE_THRESHOLDS_KEY))
        .thenReturn(Optional.of(setting("{not-json")));
    assertThat(settingService.gradeThresholds()).isEqualTo(GradeThresholds.defaults());

    // 能解析但违反约束（如被手工改成 D > C）同样回落，避免把非法阈值当成定级依据
    when(sysSettingMapper.findByKey(SettingService.GRADE_THRESHOLDS_KEY))
        .thenReturn(Optional.of(setting("{\"a\":70,\"b\":80,\"c\":90,\"d\":95}")));
    assertThat(settingService.gradeThresholds()).isEqualTo(GradeThresholds.defaults());
  }

  @Test
  @DisplayName("保存：违反 0 ≤ D ≤ C ≤ B ≤ A ≤ 100 → 2005，且不写库（01 §6.3）")
  void invalidThresholdsRejected() {
    assertThatThrownBy(() -> settingService.updateGradeThresholds(thresholds("80", "90", "70", "0")))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.THRESHOLD_INVALID);
    verifyNoInteractions(auditFacade);
    verify(sysSettingMapper, never()).upsert(anyString(), anyString());
  }

  @Test
  @DisplayName("保存：允许两档相等（等于停用该档）")
  void equalThresholdsAreAccepted() {
    when(sysSettingMapper.findByKey(SettingService.GRADE_THRESHOLDS_KEY)).thenReturn(Optional.empty());

    GradeThresholds saved = settingService.updateGradeThresholds(thresholds("90", "90", "70", "0"));

    assertThat(saved).isEqualTo(thresholds("90", "90", "70", "0"));
    verify(sysSettingMapper).upsert(eq(SettingService.GRADE_THRESHOLDS_KEY), anyString());
  }

  @Test
  @DisplayName("保存：审计记 before/after，键名为 grade.thresholds")
  void saveAuditsBeforeAndAfter() {
    when(sysSettingMapper.findByKey(SettingService.GRADE_THRESHOLDS_KEY))
        .thenReturn(Optional.of(setting("{\"a\":90,\"b\":80,\"c\":70,\"d\":0}")));

    settingService.updateGradeThresholds(thresholds("92", "82", "72", "0"));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
    verify(auditFacade).record(eq(AuditAction.SETTING_UPDATE), any(), eq(null), detail.capture());
    assertThat(detail.getValue()).containsEntry("key", SettingService.GRADE_THRESHOLDS_KEY);
    assertThat(detail.getValue()).containsEntry("before", GradeThresholds.defaults());
    assertThat(detail.getValue()).containsEntry("after", thresholds("92", "82", "72", "0"));
  }

  @Test
  @DisplayName("落库值是 JSON 对象（setting_value 为 JSON 列），字段与 record 同名")
  void persistedValueIsJsonObject() {
    when(sysSettingMapper.findByKey(SettingService.GRADE_THRESHOLDS_KEY)).thenReturn(Optional.empty());

    settingService.updateGradeThresholds(thresholds("91", "81", "71", "1"));

    ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
    verify(sysSettingMapper).upsert(eq(SettingService.GRADE_THRESHOLDS_KEY), json.capture());
    assertThat(json.getValue()).contains("\"a\":91").contains("\"d\":1");
  }

  private static SysSetting setting(String value) {
    SysSetting setting = new SysSetting();
    setting.setSettingKey(SettingService.GRADE_THRESHOLDS_KEY);
    setting.setSettingValue(value);
    return setting;
  }
}
