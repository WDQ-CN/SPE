<script setup lang="ts">
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import {
  getPortalRfq,
  submitPortalQuote,
  withdrawPortalQuote,
  type PortalRfqDetail,
  type PortalRfqLine,
} from '@/features/portal/api'
import {
  QUOTE_STATUS_LABEL,
  RFQ_STATUS_LABEL,
  formatAmount,
  formatDateTime,
  formatQty,
  labelOf,
  statusTone,
} from '@/features/erp/format'

const route = useRoute()
const rfqId = ref(Number(route.params.id))
const now = ref(Date.now())
let clock: number | undefined
let loadSeq = 0

const detail = ref<PortalRfqDetail | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const validUntil = ref('')
const forms = reactive<Record<number, QuoteForm>>({})

interface QuoteForm {
  unitPrice: number
  taxRate: number
  leadTimeDays: number | null
  moq: number | null
  qtyAvailable: number | null
  incoterm: string
  remark: string
}

const deadlinePassed = computed(
  () => detail.value !== null && new Date(detail.value.deadlineAt).getTime() <= now.value,
)
const canQuote = computed(
  () => detail.value !== null && !deadlinePassed.value && detail.value.status === 'SENT',
)
const canWithdraw = computed(
  () => detail.value?.myQuote?.status === 'SUBMITTED' && !deadlinePassed.value,
)
const quoteTotal = computed(() =>
  (detail.value?.lines ?? []).reduce((sum, line) => {
    const form = forms[line.id]
    return form ? sum + form.unitPrice * Number(line.qty) * (1 + form.taxRate) : sum
  }, 0),
)

function quoteLineFor(rfqLineId: number) {
  return detail.value?.myQuote?.lines.find((line) => line.rfqLineId === rfqLineId)
}

function buildForms(data: PortalRfqDetail): void {
  for (const line of data.lines) {
    const quoteLine = data.myQuote?.lines.find((item) => item.rfqLineId === line.id)
    forms[line.id] = {
      unitPrice: quoteLine ? Number(quoteLine.unitPrice) : Number(line.targetPrice ?? 0),
      taxRate: quoteLine ? Number(quoteLine.taxRate) : 0.13,
      leadTimeDays: quoteLine?.leadTimeDays ?? null,
      moq: quoteLine?.moq ?? null,
      qtyAvailable: quoteLine?.qtyAvailable ?? Number(line.qty),
      incoterm: quoteLine?.incoterm ?? '',
      remark: quoteLine?.remark ?? '',
    }
  }
  validUntil.value = data.myQuote?.validUntil ?? ''
}

async function load(): Promise<void> {
  const current = ++loadSeq
  loading.value = true
  error.value = ''
  try {
    const data = await getPortalRfq(rfqId.value)
    if (current !== loadSeq) {
      return
    }
    detail.value = data
    buildForms(data)
  } catch (caught) {
    if (current === loadSeq) {
      error.value = caught instanceof Error ? caught.message : 'RFQ 加载失败'
    }
  } finally {
    if (current === loadSeq) {
      loading.value = false
    }
  }
}

function reset(): void {
  detail.value = null
  error.value = ''
  validUntil.value = ''
  for (const key of Object.keys(forms)) {
    delete forms[Number(key)]
  }
}

onMounted(() => {
  void load()
  clock = window.setInterval(() => {
    now.value = Date.now()
  }, 30_000)
})

onUnmounted(() => {
  if (clock !== undefined) {
    window.clearInterval(clock)
  }
})

watch(
  () => route.params.id,
  () => {
    rfqId.value = Number(route.params.id)
    reset()
    void load()
  },
)

async function submitQuote(): Promise<void> {
  if (saving.value || !detail.value) {
    return
  }
  if (!canQuote.value) {
    ElMessage.warning('该询价已关闭或已截止，不能再提交报价')
    return
  }
  for (const line of detail.value.lines) {
    const form = forms[line.id]
    if (!form || form.unitPrice == null || Number.isNaN(Number(form.unitPrice)) || form.unitPrice < 0) {
      ElMessage.warning(`第 ${line.lineNo} 行单价不能为空或为负`)
      return
    }
  }
  saving.value = true
  error.value = ''
  try {
    await submitPortalQuote(rfqId.value, {
      validUntil: validUntil.value || null,
      lines: detail.value.lines.map((line) => {
        const form = forms[line.id]
        return {
          rfqLineId: line.id,
          unitPrice: form.unitPrice,
          taxRate: form.taxRate,
          leadTimeDays: form.leadTimeDays,
          moq: form.moq,
          qtyAvailable: form.qtyAvailable,
          incoterm: form.incoterm.trim() || null,
          remark: form.remark.trim() || null,
        }
      }),
    })
    ElMessage.success('报价已提交')
    await load()
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '报价提交失败'
  } finally {
    saving.value = false
  }
}

async function withdraw(): Promise<void> {
  if (saving.value) {
    return
  }
  const confirmed = await ElMessageBox.confirm('确定撤回当前报价吗？', '撤回报价', {
    type: 'warning',
  }).catch(() => false)
  if (!confirmed) {
    return
  }
  saving.value = true
  error.value = ''
  try {
    await withdrawPortalQuote(rfqId.value)
    ElMessage.success('报价已撤回')
    await load()
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '撤回失败'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <PageHeader
    eyebrow="SUPPLIER PORTAL / RFQ"
    :title="detail?.title || '询价详情'"
    :description="detail ? `${detail.rfqNo} · ${detail.currency}` : ''"
  >
    <template #actions>
      <RouterLink
        class="back-link"
        to="/portal/rfqs"
      >
        ← 返回询价列表
      </RouterLink>
    </template>
  </PageHeader>

  <el-alert
    v-if="error"
    class="page-alert"
    type="error"
    :title="error"
    :closable="false"
    show-icon
  />
  <div
    v-if="loading && !detail"
    v-loading="loading"
    class="portal-loading"
  />

  <template v-if="detail">
    <div class="rfq-meta">
      <span
        class="status-mark"
        :class="statusTone(detail.status)"
      >
        {{ labelOf(RFQ_STATUS_LABEL, detail.status) }}
      </span>
      <span>截止：{{ formatDateTime(detail.deadlineAt) }}</span>
      <span>{{ detail.sealed ? '密封报价' : '公开询价' }}</span>
      <span v-if="detail.myQuote">
        我的报价：{{ labelOf(QUOTE_STATUS_LABEL, detail.myQuote.status) }} ·
        {{ detail.myQuote.quoteNo }}
      </span>
    </div>

    <el-alert
      v-if="deadlinePassed"
      class="page-alert"
      type="warning"
      title="已过截止时间，报价只读"
      :closable="false"
      show-icon
    />

    <section>
      <h3 class="detail-title">
        询价行与报价
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
          min-width="190"
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
          label="目标价"
          align="right"
          width="110"
        >
          <template #default="{ row }">
            {{ row.targetPrice == null ? '—' : formatAmount(row.targetPrice) }}
          </template>
        </el-table-column>
        <el-table-column
          label="我的单价"
          width="140"
        >
          <template #default="{ row }">
            <el-input-number
              v-if="canQuote"
              v-model="forms[(row as PortalRfqLine).id].unitPrice"
              :min="0"
              :precision="4"
              :controls="false"
              class="line-number"
            />
            <span v-else>{{ quoteLineFor((row as PortalRfqLine).id)?.unitPrice ?? '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="税率"
          width="110"
        >
          <template #default="{ row }">
            <el-input-number
              v-if="canQuote"
              v-model="forms[(row as PortalRfqLine).id].taxRate"
              :min="0"
              :max="1"
              :step="0.01"
              :precision="4"
              :controls="false"
              class="line-number"
            />
            <span v-else>
              {{ quoteLineFor((row as PortalRfqLine).id)?.taxRate ?? '—' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column
          label="交期天数"
          width="110"
        >
          <template #default="{ row }">
            <el-input-number
              v-if="canQuote"
              v-model="forms[(row as PortalRfqLine).id].leadTimeDays"
              :min="0"
              :controls="false"
              class="line-number"
            />
            <span v-else>{{ quoteLineFor((row as PortalRfqLine).id)?.leadTimeDays ?? '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="可供量"
          width="120"
        >
          <template #default="{ row }">
            <el-input-number
              v-if="canQuote"
              v-model="forms[(row as PortalRfqLine).id].qtyAvailable"
              :min="0"
              :controls="false"
              class="line-number"
            />
            <span v-else>{{ quoteLineFor((row as PortalRfqLine).id)?.qtyAvailable ?? '—' }}</span>
          </template>
        </el-table-column>
      </el-table>

      <div
        v-if="canQuote"
        class="quote-footer"
      >
        <el-date-picker
          v-model="validUntil"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="报价有效期（可空）"
        />
        <span class="quote-total">含税合计：{{ formatAmount(quoteTotal, detail.currency) }}</span>
        <el-button
          v-if="canWithdraw"
          :loading="saving"
          @click="withdraw"
        >
          撤回报价
        </el-button>
        <el-button
          type="primary"
          :loading="saving"
          @click="submitQuote"
        >
          提交报价
        </el-button>
      </div>
    </section>
  </template>
</template>

<style scoped>
.back-link {
  color: var(--muted);
  font-size: 12px;
  text-decoration: none;
  border-bottom: 1px solid var(--rule);
}

.portal-loading {
  min-height: 240px;
}

.rfq-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  margin-bottom: 18px;
  color: var(--muted);
  font-size: 12px;
}

.detail-title {
  margin: 0 0 8px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.line-number {
  width: 100%;
}

.quote-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 16px;
  flex-wrap: wrap;
}

.quote-total {
  color: var(--muted);
  font-size: 12px;
}
</style>
