package com.spe.context.alert.internal;

/**
 * 整改状态机（29 §3.2）。纯函数，不查库；Service 负责 version 与守卫 UPDATE。
 *
 * <pre>
 * OPEN ──START──► IN_PROGRESS ──SUBMIT──► WAITING_VERIFICATION
 *   │                  │                         ├─ VERIFY_PASS → VERIFIED
 *   │                  │                         └─ VERIFY_FAIL → REOPENED
 *   │                  └──────────── CANCEL ◄────┘
 *   └───────────────────────────── CANCEL
 * REOPENED ──START──► IN_PROGRESS
 * </pre>
 *
 * <p>`OPEN` 不能直接 `SUBMIT`：至少要有一次负责人确认“开始整改”的动作，避免创建人代填完成。
 */
public final class CorrectiveActionStateMachine {

  private CorrectiveActionStateMachine() {}

  public static CorrectiveActionStatus transition(
      CorrectiveActionStatus current, CorrectiveActionCommand command) {
    return switch (command) {
      case START -> {
        ensure(current, CorrectiveActionStatus.OPEN, CorrectiveActionStatus.REOPENED);
        yield CorrectiveActionStatus.IN_PROGRESS;
      }
      case SUBMIT -> {
        ensure(current, CorrectiveActionStatus.IN_PROGRESS);
        yield CorrectiveActionStatus.WAITING_VERIFICATION;
      }
      case VERIFY_PASS -> {
        ensure(current, CorrectiveActionStatus.WAITING_VERIFICATION);
        yield CorrectiveActionStatus.VERIFIED;
      }
      case VERIFY_FAIL -> {
        ensure(current, CorrectiveActionStatus.WAITING_VERIFICATION);
        yield CorrectiveActionStatus.REOPENED;
      }
      case CANCEL -> {
        ensure(
            current,
            CorrectiveActionStatus.OPEN,
            CorrectiveActionStatus.IN_PROGRESS,
            CorrectiveActionStatus.WAITING_VERIFICATION,
            CorrectiveActionStatus.REOPENED);
        yield CorrectiveActionStatus.CANCELED;
      }
    };
  }

  private static void ensure(
      CorrectiveActionStatus current, CorrectiveActionStatus... allowed) {
    for (CorrectiveActionStatus candidate : allowed) {
      if (current == candidate) {
        return;
      }
    }
    throw new IllegalStateException("非法整改状态迁移：" + current);
  }
}
