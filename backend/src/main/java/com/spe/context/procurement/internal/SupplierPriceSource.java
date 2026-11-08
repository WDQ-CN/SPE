package com.spe.context.procurement.internal;

/** 价格来源；优先级 CONTRACT > AWARD > MANUAL。 */
public enum SupplierPriceSource {
  MANUAL,
  CONTRACT,
  AWARD
}
