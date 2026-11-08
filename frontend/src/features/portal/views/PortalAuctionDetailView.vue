<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import {
  getPortalAuction,
  getPortalAuctionBids,
  getPortalAuctionResult,
  getPortalAuctionState,
  submitPortalBid,
  type PortalAuctionDetail,
  type PortalAuctionResult,
  type PortalAuctionState,
  type PortalAuctionLot,
  type PortalBid,
} from '@/features/portal/api'
import {
  AUCTION_BID_STATUS_LABEL,
  AUCTION_STATUS_LABEL,
  formatAmount,
  formatDateTime,
  formatQty,
  labelOf,
  statusTone,
} from '@/features/erp/format'

const route = useRoute()
const auctionId = ref(Number(route.params.id))

const detail = ref<PortalAuctionDetail | null>(null)
const state = ref<PortalAuctionState | null>(null)
const bids = ref<PortalBid[]>([])
const result = ref<PortalAuctionResult | null>(null)
const loading = ref(false)
const error = ref('')
const biddingLot = ref<number | null>(null)
const bidAmounts = reactive<Record<number, number>>({})
let timer: number | undefined
let stateRefreshing = false

const live = computed(() => detail.value?.status === 'LIVE')
const closed = computed(
  () => detail.value !== null && ['CLOSED', 'AWARDED'].includes(detail.value.status),
)
const remainingText = computed(() => {
  const ms = state.value?.remainingMs ?? 0
  if (ms <= 0) {
    return '已结束'
  }
  const seconds = Math.floor(ms / 1000)
  const minutes = Math.floor(seconds / 60)
  return `${minutes} 分 ${seconds % 60} 秒`
})

function nextMin(lot: PortalAuctionLot): number {
  if (lot.currentLowestAmount == null) {
    return Number(lot.startPrice)
  }
  return Math.max(Number(lot.currentLowestAmount) - Number(lot.minDecrement), 0)
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    detail.value = await getPortalAuction(auctionId.value)
    bids.value = await getPortalAuctionBids(auctionId.value)
    state.value = await getPortalAuctionState(auctionId.value).catch(() => null)
    if (detail.value.status === 'CLOSED' || detail.value.status === 'AWARDED') {
      result.value = await getPortalAuctionResult(auctionId.value).catch(() => null)
    }
    for (const lot of detail.value.lots) {
      if (bidAmounts[lot.id] === undefined) {
        bidAmounts[lot.id] = nextMin(lot)
      }
    }
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '竞价加载失败'
  } finally {
    loading.value = false
  }
}

async function refreshState(): Promise<void> {
  if (stateRefreshing || !detail.value || !live.value) {
    return
  }
  stateRefreshing = true
  try {
    detail.value = await getPortalAuction(auctionId.value)
    state.value = await getPortalAuctionState(auctionId.value)
    bids.value = await getPortalAuctionBids(auctionId.value)
  } catch {
    // 轮询失败保留上一帧；下一轮继续
  } finally {
    stateRefreshing = false
  }
}

onMounted(async () => {
  await load()
  timer = window.setInterval(() => void refreshState(), 5000)
})

onUnmounted(() => {
  if (timer !== undefined) {
    window.clearInterval(timer)
  }
})

watch(
  () => route.params.id,
  () => {
    auctionId.value = Number(route.params.id)
    detail.value = null
    state.value = null
    bids.value = []
    result.value = null
    error.value = ''
    void load()
  },
)

function newKey(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

async function bid(lot: PortalAuctionLot): Promise<void> {
  if (biddingLot.value !== null) {
    return
  }
  const amount = bidAmounts[lot.id]
  if (!amount || amount <= 0) {
    ElMessage.warning('请输入有效出价金额')
    return
  }
  biddingLot.value = lot.id
  try {
    const result = await submitPortalBid(auctionId.value, lot.id, amount, newKey())
    const rankText = result.rank == null ? '' : `，当前排名 ${result.rank}`
    ElMessage.success(`出价已提交${rankText}`)
    await refreshState()
  } catch (caught) {
    ElMessage.error(caught instanceof Error ? caught.message : '出价失败')
  } finally {
    biddingLot.value = null
  }
}
</script>

<template>
  <PageHeader
    eyebrow="SUPPLIER PORTAL / AUCTION"
    :title="detail?.title || '竞价详情'"
    :description="detail ? `${detail.auctionNo} · ${detail.currency}` : ''"
  >
    <template #actions>
      <RouterLink
        class="back-link"
        to="/portal/auctions"
      >
        ← 返回竞价列表
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
    <div class="auction-meta">
      <span
        class="status-mark"
        :class="statusTone(detail.status)"
      >
        {{ labelOf(AUCTION_STATUS_LABEL, detail.status) }}
      </span>
      <span>结束：{{ formatDateTime(detail.endAt) }}</span>
      <span v-if="live">剩余（最近轮询）：{{ remainingText }}</span>
      <span>防狙击：{{ detail.antiSnipingSeconds }} 秒 / 最多 {{ detail.maxExtensions }} 次</span>
      <span>{{ detail.sealed ? '密封报价' : '公开竞价' }}</span>
    </div>

    <section class="lot-list">
      <article
        v-for="lot in detail.lots"
        :key="lot.id"
        class="lot-card"
      >
        <header class="lot-head">
          <div>
            <h3>{{ lot.itemName }}</h3>
            <p class="mono">
              #{{ lot.lineNo }} · {{ lot.itemCode }} · {{ formatQty(lot.qty) }} {{ lot.uom }}
            </p>
          </div>
          <div class="lot-rank">
            <template v-if="!detail.sealed">
              <span>当前最低：{{ lot.currentLowestAmount == null ? '尚无' : formatAmount(lot.currentLowestAmount) }}</span>
              <span>我的排名：{{ lot.myRank ?? '—' }}</span>
            </template>
            <span>我的最后出价：{{ lot.myLastAmount == null ? '—' : formatAmount(lot.myLastAmount) }}</span>
          </div>
        </header>

        <form
          v-if="live"
          class="bid-form"
          @submit.prevent="bid(lot)"
        >
          <div class="bid-hint">
            <span>起拍价 {{ formatAmount(lot.startPrice) }}</span>
            <span v-if="!detail.sealed">最小降幅 {{ formatAmount(lot.minDecrement) }}</span>
            <span v-if="!detail.sealed">下一最低可报 {{ formatAmount(nextMin(lot)) }}</span>
          </div>
          <div class="bid-controls">
            <el-input-number
              v-model="bidAmounts[lot.id]"
              :min="0.0001"
              :precision="4"
              :controls="false"
              class="bid-amount"
              aria-label="出价金额"
            />
            <el-button
              type="primary"
              native-type="submit"
              :loading="biddingLot === lot.id"
            >
              出价
            </el-button>
          </div>
        </form>
        <p
          v-else
          class="section-note"
        >
          当前状态不可出价。
        </p>
      </article>
    </section>

    <div class="portal-grid">
      <section>
        <h3 class="detail-title">
          我的出价记录
        </h3>
        <el-table
          :data="bids"
          class="table-list"
          empty-text="尚未出价"
        >
          <el-table-column
            prop="lotId"
            label="批次"
            width="90"
          />
          <el-table-column
            label="金额"
            align="right"
            width="140"
          >
            <template #default="{ row }">
              {{ formatAmount(row.amount) }}
            </template>
          </el-table-column>
          <el-table-column
            label="状态"
            width="120"
          >
            <template #default="{ row }">
              {{ labelOf(AUCTION_BID_STATUS_LABEL, row.status) }}
            </template>
          </el-table-column>
          <el-table-column
            label="时间"
            min-width="170"
          >
            <template #default="{ row }">
              {{ formatDateTime(row.submittedAt) }}
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section v-if="closed && result">
        <h3 class="detail-title">
          竞价结果
        </h3>
        <el-table
          :data="result.results"
          class="table-list"
          empty-text="暂无结果"
        >
          <el-table-column
            prop="lotId"
            label="批次"
            width="90"
          />
          <el-table-column
            label="我的排名"
            width="110"
          >
            <template #default="{ row }">
              {{ row.myRank ?? '—' }}
            </template>
          </el-table-column>
          <el-table-column
            label="我的最优价"
            align="right"
            width="140"
          >
            <template #default="{ row }">
              {{ row.myBestAmount == null ? '—' : formatAmount(row.myBestAmount) }}
            </template>
          </el-table-column>
          <el-table-column
            label="成交价"
            align="right"
            width="140"
          >
            <template #default="{ row }">
              {{ row.winningAmount == null ? '—' : formatAmount(row.winningAmount) }}
            </template>
          </el-table-column>
          <el-table-column
            label="中标"
            width="90"
          >
            <template #default="{ row }">
              <span
                class="status-mark"
                :class="row.winner ? 'is-success' : 'is-off'"
              >
                {{ row.winner ? '是' : '否' }}
              </span>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>
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

.auction-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  margin-bottom: 20px;
  color: var(--muted);
  font-size: 12px;
}

.lot-list {
  display: grid;
  gap: 14px;
}

.lot-card {
  padding: 18px 20px;
  background: var(--surface);
  border: 1px solid var(--rule);
}

.lot-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.lot-head h3 {
  margin: 0;
  font-family: var(--font-serif);
  font-size: 18px;
  font-weight: 600;
}

.lot-head p {
  margin: 4px 0 0;
}

.lot-rank {
  display: flex;
  flex-direction: column;
  gap: 4px;
  color: var(--muted);
  font-size: 12px;
  text-align: right;
}

.bid-form {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--rule);
}

.bid-hint {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  color: var(--muted);
  font-size: 12px;
}

.bid-controls {
  display: flex;
  gap: 10px;
  margin-top: 10px;
}

.bid-amount {
  width: 180px;
}

.portal-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 28px;
  margin-top: 28px;
}

.detail-title {
  margin: 0 0 8px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
</style>
