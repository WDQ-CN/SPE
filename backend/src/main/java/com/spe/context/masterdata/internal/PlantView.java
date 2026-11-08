package com.spe.context.masterdata.internal;

import com.spe.context.masterdata.api.MasterDataStatus;

/** 工厂列表/详情投影。 */
public record PlantView(
    long id,
    long companyId,
    String code,
    String name,
    String address,
    MasterDataStatus status,
    int version) {

  static PlantView of(Plant plant) {
    return new PlantView(
        plant.getId(),
        plant.getCompanyId(),
        plant.getCode(),
        plant.getName(),
        plant.getAddress(),
        plant.getStatus(),
        plant.getVersion());
  }
}
