<script setup lang="ts">
import { nextTick, onMounted, reactive, ref } from 'vue'
// 与用户页同口径：ElMessageBox 不在 auto-import 白名单，需显式引入并带样式
import { ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

import { createApiToken, disableApiToken, listApiTokens } from '@/features/api-token/api'
import { formatUtcLocal } from '@/shared/approval-timeline'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'
import { isFormLevelError } from '@/shared/error-code'
import { API_TOKEN_STATUS_LABEL } from '@/shared/labels'
import type { ApiTokenCreated, ApiTokenSummary } from '@/shared/types/dto'

/**
 * 开放 API Token 管理（M6 I3 / 01 §7，仅 ADMIN）。
 *
 * <p>明文只在「生成」响应出现一次：组件 state 持有，不写 localStorage/sessionStorage、不进 URL；
 * 关闭对话框后不可恢复，只能重新生成。停用带 version 乐观锁。
 */
const list = usePagedList<ApiTokenSummary>((page, size) => listApiTokens({ page, size }))
onMounted(() => void list.load(1))

const createVisible = ref(false)
const formRef = ref<FormInstance>()
const form = reactive({ name: '' })
const { submitting: creating, errorMessage: createError, run: runCreate, clearError } = useSubmit()
const rules: FormRules = {
  name: [
    { required: true, message: '请输入 Token 名称', trigger: 'blur' },
    { max: 100, message: 'Token 名称不得超过 100 字', trigger: 'blur' },
  ],
}

const tokenVisible = ref(false)
const created = ref<ApiTokenCreated | null>(null)
const copied = ref(false)
const actionError = ref('')

function openCreate(): void {
  form.name = ''
  clearError()
  createVisible.value = true
  void nextTick(() => formRef.value?.clearValidate())
}

async function submitCreate(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  const ok = await runCreate(async () => {
    created.value = await createApiToken({ name: form.name.trim() })
  })
  if (ok) {
    createVisible.value = false
    copied.value = false
    tokenVisible.value = true
    await list.load(1)
  }
}

async function copyToken(): Promise<void> {
  if (!created.value) {
    return
  }
  try {
    await navigator.clipboard.writeText(created.value.token)
    copied.value = true
    ElMessage.success('已复制到剪贴板')
  } catch {
    // 非安全上下文/权限拒绝：明文仍在页面上，用户可手动选择
    ElMessage.warning('复制失败，请手动选择 Token 文本')
  }
}

function closeToken(): void {
  tokenVisible.value = false
  created.value = null
  copied.value = false
}

async function disable(row: ApiTokenSummary): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `确定停用「${row.name}」吗？停用后使用该 Token 的外部系统将立即无法访问；此操作不可恢复。`,
    '停用 API Token',
    { type: 'warning' },
  ).catch(() => false)
  if (confirmed === false) {
    return
  }
  actionError.value = ''
  try {
    await disableApiToken(row.id, row.version)
    ElMessage.success('已停用')
    await list.load()
  } catch (caught) {
    // 2xxx（已停用/不存在）拦截器不弹全局提示，需页面回显；3xxx/4xxx/5xxx 已全局提示
    if (isFormLevelError(caught)) {
      actionError.value = caught.message
    }
  }
}
</script>

<template>
  <PageHeader
    eyebrow="OPEN API / TOKENS"
    title="开放 API"
    description="对外只读接口与分数写入凭据 — 明文只在生成时显示一次"
  >
    <template #actions>
      <el-button
        type="primary"
        @click="openCreate"
      >
        生成 Token
      </el-button>
    </template>
  </PageHeader>

  <SectionCard title="API Token">
    <p class="note-block">
      外部系统使用 <code>Authorization: Bearer &lt;token&gt;</code> 调用
      <code>/open/v1/tasks/active</code>、<code>/open/v1/tasks/{id}/indicators</code> 与
      <code>/open/v1/scores</code>。Token 明文只在生成时显示一次。
    </p>

    <el-alert
      v-if="list.error.value"
      class="page__error"
      type="error"
      :title="list.error.value"
      :closable="false"
      show-icon
    >
      <el-button
        link
        type="primary"
        @click="list.load()"
      >
        重试
      </el-button>
    </el-alert>
    <el-alert
      v-if="actionError"
      class="page__error"
      type="error"
      :title="actionError"
      :closable="false"
      show-icon
    />

    <el-empty
      v-if="!list.loading.value && list.rows.value.length === 0"
      description="还没有 API Token；生成后把明文交给对接方（只显示一次）"
    />
    <el-table
      v-else
      v-loading="list.loading.value"
      :data="list.rows.value"
      class="table-list"
    >
      <el-table-column
        label="名称"
        min-width="200"
      >
        <template #default="{ row }">
          <span class="token-name">{{ (row as ApiTokenSummary).name }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="前缀"
        min-width="190"
      >
        <template #default="{ row }">
          <code class="token-prefix">{{ (row as ApiTokenSummary).tokenPrefix }}…</code>
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="100"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="(row as ApiTokenSummary).status === 'ENABLED' ? 'is-success' : 'is-off'"
          >
            {{ API_TOKEN_STATUS_LABEL[(row as ApiTokenSummary).status] }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="最近使用"
        width="180"
      >
        <template #default="{ row }">
          <span
            v-if="(row as ApiTokenSummary).lastUsedAt"
            class="mono"
          >{{ formatUtcLocal((row as ApiTokenSummary).lastUsedAt as string) }}</span>
          <span
            v-else
            class="token-muted"
          >从未使用</span>
        </template>
      </el-table-column>
      <el-table-column
        label="创建时间"
        width="180"
      >
        <template #default="{ row }">
          <span class="mono">{{ formatUtcLocal((row as ApiTokenSummary).createdAt) }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="100"
        align="right"
        fixed="right"
      >
        <template #default="{ row }">
          <el-button
            v-if="(row as ApiTokenSummary).status === 'ENABLED'"
            link
            type="danger"
            @click="disable(row as ApiTokenSummary)"
          >
            停用
          </el-button>
          <span v-else>—</span>
        </template>
      </el-table-column>
    </el-table>

    <ListPagination
      v-model:page="list.page.value"
      v-model:size="list.size.value"
      :total="list.total.value"
      :page-sizes="[10, 20, 50]"
      class="list-pager"
      @change="list.load"
      @size-change="list.load(1)"
    />
  </SectionCard>

  <el-dialog
    v-model="createVisible"
    title="生成 API Token"
    width="480px"
  >
    <el-alert
      v-if="createError"
      class="page__error"
      type="error"
      :title="createError"
      :closable="false"
      show-icon
    />
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="90px"
      @submit.prevent="submitCreate"
    >
      <el-form-item
        label="名称"
        prop="name"
      >
        <el-input
          v-model="form.name"
          placeholder="例如：ERP 生产环境"
          maxlength="100"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createVisible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="creating"
        :disabled="creating"
        @click="submitCreate"
      >
        生成
      </el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="tokenVisible"
    title="Token 已生成"
    width="580px"
    :close-on-click-modal="false"
    @closed="closeToken"
  >
    <el-alert
      title="Token 明文只会显示这一次。关闭后无法恢复，请立即复制并安全保存。"
      type="warning"
      :closable="false"
      show-icon
    />
    <p class="token__name">
      {{ created?.name }}（{{ created?.tokenPrefix }}…）
    </p>
    <div class="token__box">
      <code class="token__value">{{ created?.token }}</code>
      <el-button
        type="primary"
        plain
        @click="copyToken"
      >
        {{ copied ? '已复制' : '复制' }}
      </el-button>
    </div>
    <template #footer>
      <el-button
        type="primary"
        @click="tokenVisible = false"
      >
        我已保存
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.token-name {
  color: var(--ink);
  font-weight: 500;
}
.token-prefix {
  color: var(--ink-secondary);
  font-family: var(--font-mono);
  font-size: 10px;
}
.token-muted {
  color: var(--muted-2);
  font-size: 10.5px;
}
.token__name {
  margin: 14px 0 8px;
  font-family: var(--font-serif);
  font-size: 17px;
  font-weight: 500;
}
.token__box {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--rule);
  background: var(--paper-deep);
}
.token__value {
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--ink-secondary);
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 1.7;
}
</style>
