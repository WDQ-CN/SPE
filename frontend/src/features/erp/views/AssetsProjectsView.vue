<script setup lang="ts">
import { onMounted, ref } from 'vue'

import {
  listCompanies,
  listCustomers,
  listPlants,
  type CompanyOption,
  type CustomerOption,
  type PlantOption,
} from '@/features/erp/api'
import AssetPanel from '@/features/erp/components/AssetPanel.vue'
import ProjectPanel from '@/features/erp/components/ProjectPanel.vue'
import TimesheetPanel from '@/features/erp/components/TimesheetPanel.vue'

const companies = ref<CompanyOption[]>([])
const plants = ref<PlantOption[]>([])
const customers = ref<CustomerOption[]>([])
const loading = ref(false)
const ready = ref(false)
const error = ref('')

async function loadOptions(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [companyList, plantList, customerList] = await Promise.all([
      listCompanies(),
      listPlants(),
      listCustomers(),
    ])
    companies.value = companyList
    plants.value = plantList
    customers.value = customerList
    ready.value = true
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '主数据加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => void loadOptions())
</script>

<template>
  <PageHeader
    eyebrow="ERP / PROJECTS & ASSETS"
    title="资产与项目"
    description="固定资产资本化/折旧/处置；项目/WBS/预算；工时提交与审批"
  />

  <el-alert
    v-if="error"
    class="page-alert"
    type="error"
    :title="error"
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
    v-if="loading"
    v-loading="loading"
    class="options-loading"
  />

  <el-tabs
    v-else-if="ready"
    class="asset-tabs"
  >
    <el-tab-pane
      label="固定资产"
      lazy
    >
      <AssetPanel
        :companies="companies"
        :plants="plants"
      />
    </el-tab-pane>
    <el-tab-pane
      label="项目"
      lazy
    >
      <ProjectPanel
        :companies="companies"
        :customers="customers"
      />
    </el-tab-pane>
    <el-tab-pane
      label="工时"
      lazy
    >
      <TimesheetPanel :companies="companies" />
    </el-tab-pane>
  </el-tabs>
</template>

<style scoped>
.options-loading {
  min-height: 220px;
}

.asset-tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
}
</style>
