package com.spe.context.alert.internal;

/** 用户意图到整改状态的命令（由 Service 根据 API 动作传入）。 */
public enum CorrectiveActionCommand {
  START,
  SUBMIT,
  VERIFY_PASS,
  VERIFY_FAIL,
  CANCEL
}
