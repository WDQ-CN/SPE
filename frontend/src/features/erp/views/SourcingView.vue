<script setup lang="ts">
import { onMounted, ref } from 'vue'

import {
  listCompanies,
  listItems,
  listPlants,
  type CompanyOption,
  type ItemOption,
  type PlantOption,
} from '@/features/erp/api'
import AuctionPanel from '@/features/erp/components/AuctionPanel.vue'
import ContractPanel from '@/features/erp/components/ContractPanel.vue'
import PricePanel from '@/features/erp/components/PricePanel.vue'
import RfqPanel from '@/features/erp/components/RfqPanel.vue'

const companies = ref<CompanyOption[]>([])
const plants = ref<PlantOption[]>([])
const items = ref<ItemOption[]>([])
const optionsLoading = ref(false)
const optionsReady = ref(false)
const optionsError = ref('')

async function loadOptions(): Promise<void> {
  optionsLoading.value = true
  optionsError.value = ''
  try {
    const [companyList, plantList, itemList] = await Promise.all([
      listCompanies(),
      listPlants(),
      listItems(),
    ])
    companies.value = companyList
    plants.value = plantList
    items.value = itemList
    optionsReady.value = true
  } catch (caught) {
    optionsError.value = caught instanceof Error ? caught.message : '主数据加载失败'
  } finally {
    optionsLoading.value = false
  }
}

onMounted(() => void loadOptions())
</script>

<template>
  <PageHeader
    eyebrow="ERP / SOURCING"
    title="寻源"
    description="RFQ/报价/比价/定标、合同/框架协议释放订单、供应商有效价格库"
  />

  <el-alert
    v-if="optionsError"
    class="page-alert"
    type="error"
    :title="optionsError"
    :closable="false"
    show-icon
  >
    <el-button
      link
      @click="loadOptions"
    >
      重试
    </el-button>
  </el-alert>

  <div
    v-if="optionsLoading"
    v-loading="optionsLoading"
    class="options-loading"
  />

  <el-tabs
    v-else-if="optionsReady"
    class="sourcing-tabs"
  >
    <el-tab-pane
      label="询价 RFQ"
      lazy
    >
      <RfqPanel
        :companies="companies"
        :plants="plants"
        :items="items"
      />
    </el-tab-pane>
    <el-tab-pane
      label="竞价"
      lazy
    >
      <AuctionPanel
        :companies="companies"
        :plants="plants"
        :items="items"
      />
    </el-tab-pane>
    <el-tab-pane
      label="合同 / 框架协议"
      lazy
    >
      <ContractPanel
        :companies="companies"
        :plants="plants"
        :items="items"
      />
    </el-tab-pane>
    <el-tab-pane
      label="供应商价格库"
      lazy
    >
      <PricePanel
        :companies="companies"
        :items="items"
      />
    </el-tab-pane>
  </el-tabs>
</template>

<style scoped>
.options-loading {
  min-height: 220px;
}

.sourcing-tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
}
</style>
