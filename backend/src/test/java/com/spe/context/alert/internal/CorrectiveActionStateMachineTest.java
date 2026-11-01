package com.spe.context.alert.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 整改状态机（W1）：合法迁移与所有常见非法入口。 */
class CorrectiveActionStateMachineTest {

  @Test
  @DisplayName("OPEN → IN_PROGRESS → WAITING → VERIFIED 的主路径")
  void happyPath() {
    CorrectiveActionStatus started =
        CorrectiveActionStateMachine.transition(
            CorrectiveActionStatus.OPEN, CorrectiveActionCommand.START);
    assertThat(started).isEqualTo(CorrectiveActionStatus.IN_PROGRESS);

    CorrectiveActionStatus submitted =
        CorrectiveActionStateMachine.transition(started, CorrectiveActionCommand.SUBMIT);
    assertThat(submitted).isEqualTo(CorrectiveActionStatus.WAITING_VERIFICATION);

    assertThat(
            CorrectiveActionStateMachine.transition(
                submitted, CorrectiveActionCommand.VERIFY_PASS))
        .isEqualTo(CorrectiveActionStatus.VERIFIED);
  }

  @Test
  @DisplayName("验证失败回到 REOPENED，可重新开始整改")
  void verifyFailReopens() {
    CorrectiveActionStatus reopened =
        CorrectiveActionStateMachine.transition(
            CorrectiveActionStatus.WAITING_VERIFICATION, CorrectiveActionCommand.VERIFY_FAIL);
    assertThat(reopened).isEqualTo(CorrectiveActionStatus.REOPENED);
    assertThat(
            CorrectiveActionStateMachine.transition(
                reopened, CorrectiveActionCommand.START))
        .isEqualTo(CorrectiveActionStatus.IN_PROGRESS);
  }

  @Test
  @DisplayName("未完成状态允许取消，终态不可再迁移")
  void cancelAndTerminal() {
    for (CorrectiveActionStatus status :
        new CorrectiveActionStatus[] {
          CorrectiveActionStatus.OPEN,
          CorrectiveActionStatus.IN_PROGRESS,
          CorrectiveActionStatus.WAITING_VERIFICATION,
          CorrectiveActionStatus.REOPENED
        }) {
      assertThat(CorrectiveActionStateMachine.transition(status, CorrectiveActionCommand.CANCEL))
          .isEqualTo(CorrectiveActionStatus.CANCELED);
    }
    assertThatThrownBy(
            () ->
                CorrectiveActionStateMachine.transition(
                    CorrectiveActionStatus.VERIFIED, CorrectiveActionCommand.CANCEL))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("VERIFIED");
  }

  @Test
  @DisplayName("OPEN 不能直接提交，WAITING 不能开始，终态不能验证")
  void invalidTransitions() {
    assertThatThrownBy(
            () ->
                CorrectiveActionStateMachine.transition(
                    CorrectiveActionStatus.OPEN, CorrectiveActionCommand.SUBMIT))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                CorrectiveActionStateMachine.transition(
                    CorrectiveActionStatus.WAITING_VERIFICATION,
                    CorrectiveActionCommand.START))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                CorrectiveActionStateMachine.transition(
                    CorrectiveActionStatus.VERIFIED, CorrectiveActionCommand.VERIFY_PASS))
        .isInstanceOf(IllegalStateException.class);
  }
}
