<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

import {
  approvePurchaseOrder,
  cancelPurchaseOrder,
  getPurchaseOrder,
  listCompanies,
  listPurchaseOrders,
  listSuppliers,
  rejectPurchaseOrder,
  sendPurchaseOrder,
  type CompanyOption,
  type PurchaseOrderDetail,
  type PurchaseOrderRow,
} from '@/features/erp/api'
import {
  PURCHASE_ORDER_STATUS_LABEL,
  formatAmount,
  formatDateTime,
  formatQty,
  labelOf,
  statusTone,
} from '@/features/erp/format'
import { usePagedList } from '@/shared/composables/usePagedList'
import type { SupplierSummary } from '@/shared/types/dto'

const router = useRouter()
const filters = reactive<{ companyId?: number; supplierId?: number; status: string; keyword: string }>({
  status: '',
  keyword: '',
})

const companies = ref<CompanyOption[]>([])
const suppliers = ref<SupplierSummary[]>([])

const { rows, total, page, size, loading, error, load } = usePagedList<PurchaseOrderRow>(
  (targetPage, pageSize) =>
    listPurchaseOrders({
      page: targetPage,
      size: pageSize,
      companyId: filters.companyId,
      supplierId: filters.supplierId,
      status: filters.status || undefined,
      keyword: filters.keyword.trim() || undefined,
    }),
)

const statusOptions = Object.entries(PURCHASE_ORDER_STATUS_LABEL)

async function loadOptions(): Promise<void> {
  try {
    const [companyList, supplierList] = await Promise.all([listCompanies(), listSuppliers()])
    companies.value = companyList
    suppliers.value = supplierList
    if (!filters.companyId && companyList.length > 0) {
      filters.companyId = companyList[0].id
    }
  } catch {
    // 主数据不可用时保留空选项
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
  filters.supplierId = undefined
  filters.status = ''
  filters.keyword = ''
  filters.companyId = companies.value[0]?.id
  void load(1)
}

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<PurchaseOrderDetail | null>(null)

async function openDetail(id: number): Promise<void> {
  detailLoading.value = true
  detailVisible.value = true
  try {
    detail.value = await getPurchaseOrder(id)
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function approve(row: PurchaseOrderRow): Promise<void> {
  const confirmed = await ElMessageBox.confirm(`确定审批通过「${row.docNo}」吗？`, '审批', {
    type: 'warning',
  }).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    await approvePurchaseOrder(row.id, row.version)
    ElMessage.success('已审批通过')
    await afterAction(row.id)
  } catch {
    // http 拦截器已提示
  }
}

async function reject(row: PurchaseOrderRow): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt('请输入驳回意见', '驳回采购订单', {
      inputType: 'textarea',
      inputValidator: (input) => (input?.trim() ? true : '驳回意见不能为空'),
    })
    await rejectPurchaseOrder(row.id, value.trim(), row.version)
    ElMessage.success('已驳回')
    await afterAction(row.id)
  } catch {
    // 取消或接口错误（错误已由拦截器提示）
  }
}

async function send(row: PurchaseOrderRow): Promise<void> {
  const confirmed = await ElMessageBox.confirm(`确定发送「${row.docNo}」给供应商吗？`, '发送', {
    type: 'warning',
  }).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    await sendPurchaseOrder(row.id, row.version)
    ElMessage.success('已发送')
    await afterAction(row.id)
  } catch {
    // http 拦截器已提示
  }
}

async function cancel(row: PurchaseOrderRow): Promise<void> {
  const confirmed = await ElMessageBox.confirm(`确定取消「${row.docNo}」吗？`, '取消', {
    type: 'warning',
  }).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    await cancelPurchaseOrder(row.id, row.version)
    ElMessage.success('已取消')
    await afterAction(row.id)
  } catch {
    // http 拦截器已提示
  }
}

async function afterAction(id: number): Promise<void> {
  await load()
  if (detail.value?.id === id) {
    detail.value = await getPurchaseOrder(id)
  }
}
</script>

<template>
  <PageHeader
    eyebrow="ERP / PROCURE TO PAY"
    title="采购订单"
    :description="`共 ${total} 张订单 · 审批、发送、收货与开票进度`"
  >
    <template #actions>
      <el-button @click="router.push('/procurement/operations')">
        采购执行
      </el-button>
    </template>
  </PageHeader>

  <form
    class="list-toolbar"
    @submit.prevent="search"
  >
    <el-select
      v-model="filters.companyId"
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
      v-model="filters.supplierId"
      class="list-toolbar__select"
      placeholder="供应商"
      clearable
      filterable
      aria-label="供应商"
      @change="search"
    >
      <el-option
        v-for="supplier in suppliers"
        :key="supplier.id"
        :label="`${supplier.code} · ${supplier.name}`"
        :value="supplier.id"
      />
    </el-select>
    <el-select
      v-model="filters.status"
      class="list-toolbar__select"
      placeholder="状态"
      clearable
      aria-label="状态"
      @change="search"
    >
      <el-option
        v-for="[value, label] in statusOptions"
        :key="value"
        :label="label"
        :value="value"
      />
    </el-select>
    <el-input
      v-model="filters.keyword"
      class="list-toolbar__search"
      placeholder="单号或供应商"
      clearable
      aria-label="关键字"
      @keyup.enter="search"
    />
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
        label="订单号"
        min-width="150"
      >
        <template #default="{ row }">
          <span class="mono">{{ (row as PurchaseOrderRow).docNo }}</span>
        </template>
      </el-table-column>
      <el-table-column
        prop="supplierName"
        label="供应商"
        min-width="180"
      />
      <el-table-column
        prop="plantName"
        label="工厂"
        min-width="130"
      />
      <el-table-column
        label="状态"
        width="130"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="statusTone((row as PurchaseOrderRow).status)"
          >
            {{ labelOf(PURCHASE_ORDER_STATUS_LABEL, (row as PurchaseOrderRow).status) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="金额"
        align="right"
        min-width="140"
      >
        <template #default="{ row }">
          {{ formatAmount((row as PurchaseOrderRow).amount, (row as PurchaseOrderRow).currency) }}
        </template>
      </el-table-column>
      <el-table-column
        label="创建时间"
        min-width="160"
      >
        <template #default="{ row }">
          {{ formatDateTime((row as PurchaseOrderRow).createdAt) }}
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="230"
        align="right"
      >
        <template #default="{ row }">
          <el-button
            link
            @click="openDetail((row as PurchaseOrderRow).id)"
          >
            详情
          </el-button>
          <template v-if="(row as PurchaseOrderRow).status === 'DRAFT'">
            <el-button
              link
              @click="approve(row as PurchaseOrderRow)"
            >
              通过
            </el-button>
            <el-button
              link
              @click="reject(row as PurchaseOrderRow)"
            >
              驳回
            </el-button>
          </template>
          <el-button
            v-if="(row as PurchaseOrderRow).status === 'APPROVED'"
            link
            @click="send(row as PurchaseOrderRow)"
          >
            发送
          </el-button>
          <el-button
            v-if="['DRAFT', 'APPROVED'].includes((row as PurchaseOrderRow).status)"
            link
            @click="cancel(row as PurchaseOrderRow)"
          >
            取消
          </el-button>
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

  <el-drawer
    v-model="detailVisible"
    title="采购订单详情"
    size="720px"
  >
    <div v-loading="detailLoading">
      <template v-if="detail">
        <el-descriptions
          :column="2"
          border
        >
          <el-descriptions-item label="订单号">
            {{ detail.docNo }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ labelOf(PURCHASE_ORDER_STATUS_LABEL, detail.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="供应商">
            {{ detail.supplierName }}
          </el-descriptions-item>
          <el-descriptions-item label="工厂">
            {{ detail.plantName }}
          </el-descriptions-item>
          <el-descriptions-item label="金额">
            {{ formatAmount(detail.amount, detail.currency) }}
          </el-descriptions-item>
          <el-descriptions-item label="付款条件">
            {{ detail.paymentTerms || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="创建人">
            {{ detail.createdByName }}
          </el-descriptions-item>
          <el-descriptions-item label="审批人">
            {{ detail.approvedByName || '—' }}
          </el-descriptions-item>
        </el-descriptions>

        <h3 class="detail-title">
          订单行
        </h3>
        <el-table
          :data="detail.lines"
          class="table-list"
        >
          <el-table-column
            prop="lineNo"
            label="#"
            width="50"
          />
          <el-table-column
            label="物料"
            min-width="180"
          >
            <template #default="{ row }">
              {{ row.itemName }} <span class="mono">{{ row.itemCode }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="数量"
            align="right"
            width="110"
          >
            <template #default="{ row }">
              {{ formatQty(row.qty) }} {{ row.uom }}
            </template>
          </el-table-column>
          <el-table-column
            label="单价"
            align="right"
            width="110"
          >
            <template #default="{ row }">
              {{ formatAmount(row.price) }}
            </template>
          </el-table-column>
          <el-table-column
            label="已收货"
            align="right"
            width="100"
          >
            <template #default="{ row }">
              {{ formatQty(row.receivedQty) }}
            </template>
          </el-table-column>
          <el-table-column
            label="已开票"
            align="right"
            width="100"
          >
            <template #default="{ row }">
              {{ formatQty(row.invoicedQty) }}
            </template>
          </el-table-column>
        </el-table>
      </template>
      <el-empty
        v-else-if="!detailLoading"
        description="未找到订单"
      />
    </div>
  </el-drawer>
</template>

<style scoped>
.detail-title {
  margin: 24px 0 8px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
</style>
