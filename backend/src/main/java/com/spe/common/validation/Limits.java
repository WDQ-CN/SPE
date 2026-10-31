package com.spe.common.validation;

/** 跨 DTO 与 Service 的字段上限（03 §51 P3-17）：注解与服务层引用同一常量，改一处即两处生效。 */
public final class Limits {

  public static final int NAME_100 = 100;
  public static final int TEXT_50 = 50;
  public static final int TEXT_20 = 20;
  public static final int TEXT_500 = 500;

  public static final int YEAR_MIN = 2000;
  public static final int YEAR_MAX = 2999;
  public static final int QUARTER_MIN = 1;
  public static final int QUARTER_MAX = 4;

  private Limits() {}
}
