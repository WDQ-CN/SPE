package com.spe.context.system.api;

/** 角色（01 §2 / §7 权限矩阵）。存 `VARCHAR(20)` 的大写常量，不加 CHECK（05 §8.1）。 */
public enum Role {
  ADMIN,
  MANAGER,
  EVALUATOR,
  VIEWER
}
