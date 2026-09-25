package com.spe.context.system.web;

/**
 * 测试夹具：模拟「对方上下文的非 api、非 internal 包」。R-A 要求跨上下文只允许依赖对方 api 包，
 * 依赖本类即违规；本类自身不违规。
 */
public class SharedWebHelper {

  public String describe() {
    return "shared";
  }
}
