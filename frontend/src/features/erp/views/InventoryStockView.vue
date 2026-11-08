<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import {
  inventoryValuation,
  listCompanies,
  listInventoryStock,
  listItems,
  listWarehouses,
  type CompanyOption,
  type InventoryStockRow,
  type InventoryValuationReport,
  type ItemOption,
  type WarehouseOption,
} from '@/features/erp/api'
import { formatAmount, formatQty } from '@/features/erp/format'
import { usePagedList } from '@/shared/composables/usePagedList'

const router = useRouter()
const query = reactive<{ companyId?: number; warehouseId?: number; itemId?: number }>({})

const companies = ref<CompanyOption[]>([])
const warehouses = ref<WarehouseOption[]>([])
const items = ref<ItemOption[]>([])

const { rows, total, page, size, loading, error, load } = usePagedList<InventoryStockRow>(
  (targetPage, pageSize) =>
    listInventoryStock({
      page: targetPage,
      size: pageSize,
      companyId: query.companyId,
      warehouseId: query.warehouseId,
      itemId: query.itemId,
    }),
)

const pageTotals = computed(() => {
  const value = rows.value.reduce((sum, row) => sum + Number(row.totalValue ?? 0), 0)
  const available = rows.value.reduce((sum, row) => sum + Number(row.availableQty ?? 0), 0)
  return { value, available }
})

const valuation = ref<InventoryValuationReport | null>(null)
const valuationVisible = ref(false)
const valuationLoading = ref(false)

async function openValuation(): Promise<void> {
  if (!query.companyId) {
    ElMessage.warning('请先选择公司')
    return
  }
  valuationLoading.value = true
  try {
    valuation.value = await inventoryValuation(query.companyId, query.warehouseId)
    valuationVisible.value = true
  } catch {
    // 错误已由 http 拦截器提示
  } finally {
    valuationLoading.value = false
  }
}

async function loadOptions(): Promise<void> {
  try {
    const [companyList, warehouseList, itemList] = await Promise.all([
      listCompanies(),
      listWarehouses(),
      listItems(),
    ])
    companies.value = companyList
    warehouses.value = warehouseList
    items.value = itemList
    if (!query.companyId && companyList.length > 0) {
      query.companyId = companyList[0].id
    }
  } catch {
    // 主数据不可用时仍允许查看全量库存（筛选为空）
  }
}

onMounted(async () => {
  await loadOptions()
  await load(1)
})

function search(): void {
  void load(1)
}

function resetSearch(): void {
  query.warehouseId = undefined
  query.itemId = undefined
  void load(1)
}
</script>

<template>
  <PageHeader
    eyebrow="ERP / INVENTORY"
    title="库存存量"
    :description="`共 ${total} 条库存记录 · 当前页可用 ${formatQty(pageTotals.available)} · 价值 ${formatAmount(pageTotals.value)}`"
  >
    <template #actions>
      <el-button @click="router.push('/inventory/operations')">
        库存作业
      </el-button>
      <el-button
        :loading="valuationLoading"
        @click="openValuation"
      >
        估值汇总
      </el-button>
    </template>
  </PageHeader>

  <form
    class="list-toolbar"
    @submit.prevent="search"
  >
    <el-select
      v-model="query.companyId"
      class="list-toolbar__select"
      placeholder="公司"
      clearable
      aria-label="公司"
      @change="search"
    >
      <el-option
        v-for="company in companies"
        :key="company.id"
        :label="`${company.code} · ${company.name}`"
        :value="company.id"
      />
    </el-select>
    <el-select
      v-model="query.warehouseId"
      class="list-toolbar__select"
      placeholder="仓库"
      clearable
      aria-label="仓库"
      @change="search"
    >
      <el-option
        v-for="warehouse in warehouses"
        :key="warehouse.id"
        :label="`${warehouse.code} · ${warehouse.name}`"
        :value="warehouse.id"
      />
    </el-select>
    <el-select
      v-model="query.itemId"
      class="list-toolbar__select"
      placeholder="物料"
      clearable
      filterable
      aria-label="物料"
      @change="search"
    >
      <el-option
        v-for="item in items"
        :key="item.id"
        :label="`${item.code} · ${item.name}`"
        :value="item.id"
      />
    </el-select>
    <el-button
      type="primary"
      @click="search"
    >
      查询
    </el-button>
    <el-button @click="resetSearch">
      重置
    </el-button>
  </form>

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
      @click="load()"
    >
      重试
    </el-button>
  </el-alert>

  <section>
    <el-table
      v-loading="loading"
      :data="rows"
      :border="false"
      class="table-list"
    >
      <el-table-column
        label="仓库 / 库位"
        min-width="190"
      >
        <template #default="{ row }">
          <div>{{ (row as InventoryStockRow).warehouseName }}</div>
          <div class="mono">
            {{ (row as InventoryStockRow).locationCode || '—' }}
          </div>
        </template>
      </el-table-column>
      <el-table-column
        label="物料"
        min-width="220"
      >
        <template #default="{ row }">
          <div>{{ (row as InventoryStockRow).itemName }}</div>
          <div class="mono">
            {{ (row as InventoryStockRow).itemCode }}
          </div>
        </template>
      </el-table-column>
      <el-table-column
        label="批次"
        min-width="130"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as InventoryStockRow).lotNo || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="在库"
        align="right"
        min-width="110"
      >
        <template #default="{ row }">
          {{ formatQty((row as InventoryStockRow).qtyOnHand) }}
        </template>
      </el-table-column>
      <el-table-column
        label="预留"
        align="right"
        min-width="100"
      >
        <template #default="{ row }">
          {{ formatQty((row as InventoryStockRow).qtyReserved) }}
        </template>
      </el-table-column>
      <el-table-column
        label="冻结"
        align="right"
        min-width="100"
      >
        <template #default="{ row }">
          {{ formatQty((row as InventoryStockRow).qtyFrozen) }}
        </template>
      </el-table-column>
      <el-table-column
        label="可用"
        align="right"
        min-width="110"
      >
        <template #default="{ row }">
          <strong>{{ formatQty((row as InventoryStockRow).availableQty) }}</strong>
        </template>
      </el-table-column>
      <el-table-column
        label="单位成本"
        align="right"
        min-width="120"
      >
        <template #default="{ row }">
          {{ formatAmount((row as InventoryStockRow).avgUnitCost) }}
        </template>
      </el-table-column>
      <el-table-column
        label="库存价值"
        align="right"
        min-width="130"
      >
        <template #default="{ row }">
          {{ formatAmount((row as InventoryStockRow).totalValue) }}
        </template>
      </el-table-column>
    </el-table>

    <ListPagination
      v-model:page="page"
      v-model:size="size"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      class="list-pager"
      @change="(target: number) => void load(target)"
      @size-change="() => void load(1)"
    />
  </section>

  <el-dialog
    v-model="valuationVisible"
    title="库存估值"
    width="760px"
  >
    <template v-if="valuation">
      <p class="list-toolbar__note valuation-total">
        估值合计：<strong>{{ formatAmount(valuation.totalValue) }}</strong>
        · 共 {{ valuation.rows.length }} 个批次/物料组合
      </p>
      <el-table
        :data="valuation.rows"
        max-height="420"
        class="table-list"
      >
        <el-table-column
          prop="warehouseId"
          label="仓库"
          width="90"
        />
        <el-table-column
          label="物料"
          min-width="200"
        >
          <template #default="{ row }">
            {{ row.itemName }} <span class="mono">{{ row.itemCode }}</span>
          </template>
        </el-table-column>
        <el-table-column
          prop="lotNo"
          label="批次"
          min-width="120"
        />
        <el-table-column
          prop="method"
          label="计价方法"
          width="110"
        />
        <el-table-column
          label="数量"
          align="right"
          width="120"
        >
          <template #default="{ row }">
            {{ formatQty(row.qty) }}
          </template>
        </el-table-column>
        <el-table-column
          label="金额"
          align="right"
          width="140"
        >
          <template #default="{ row }">
            {{ formatAmount(row.value) }}
          </template>
        </el-table-column>
      </el-table>
    </template>
    <el-empty
      v-else-if="!valuationLoading"
      description="暂无估值数据"
    />
  </el-dialog>
</template>

<style scoped>
.valuation-total {
  margin: 0 0 12px;
}
</style>
