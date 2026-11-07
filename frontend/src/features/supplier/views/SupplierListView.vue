<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
// ElMessage 由 unplugin-auto-import 按需注入；ElMessageBox 不在它的白名单里，
// 需要显式引入**并带上样式**——按需引入模式下不会自动注入它的 CSS，否则弹窗样式会缺失。
import { ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

import {
  changeSupplierStatus,
  createSupplier,
  listSuppliers,
  updateSupplier,
} from '@/features/supplier/api'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'
import { SUPPLIER_STATUS_LABEL } from '@/shared/labels'
import type { SupplierSummary } from '@/shared/types/dto'

/** 搜索条件：空串不发送（10 §E18 的前置约束——空串会被后端当成一个真实的搜索词） */
const query = reactive({ keyword: '', category: '' })

const { rows, total, page, size, loading, error, load } = usePagedList<SupplierSummary>(
  (targetPage, pageSize) =>
    listSuppliers({
      page: targetPage,
      size: pageSize,
      keyword: query.keyword.trim(),
      category: query.category.trim(),
    }),
)

onMounted(() => void load(1))

/** 查询与重置都要回到第 1 页：停在第 5 页搜索，结果往往不足 5 页，会看到空列表 */
function search(): void {
  void load(1)
}

function resetSearch(): void {
  query.keyword = ''
  query.category = ''
  void load(1)
}

// ---- 新增 / 编辑 ----
const dialogVisible = ref(false)
const editing = ref<SupplierSummary | null>(null)
const formRef = ref<FormInstance>()
const form = reactive({ code: '', name: '', category: '' })
const { submitting: saving, errorMessage: saveError, run: runSave, clearError } = useSubmit()

const isEdit = computed(() => editing.value !== null)

// 与后端 CreateSupplierRequest/UpdateSupplierRequest 对齐：编码 ≤50、名称 ≤100、分类 ≤50 且可空
const rules: FormRules = {
  code: [
    { required: true, message: '请输入编码', trigger: 'blur' },
    { max: 50, message: '编码长度不得超过 50', trigger: 'blur' },
  ],
  name: [
    { required: true, message: '请输入名称', trigger: 'blur' },
    { max: 100, message: '名称长度不得超过 100', trigger: 'blur' },
  ],
  category: [{ max: 50, message: '分类长度不得超过 50', trigger: 'blur' }],
}

function openDialog(row: SupplierSummary | null): void {
  editing.value = row
  Object.assign(form, {
    code: row?.code ?? '',
    name: row?.name ?? '',
    category: row?.category ?? '',
  })
  clearError()
  dialogVisible.value = true
  void nextTick(() => formRef.value?.clearValidate())
}

async function submit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  // 分类留空要发 null 而不是空串：库里「未填写」应当是 NULL，空串是另一个值
  const payload = {
    code: form.code.trim(),
    name: form.name.trim(),
    category: form.category.trim() || null,
  }
  const savedRow = editing.value
  const ok = await runSave(async () => {
    if (savedRow) {
      // 编码可改（10 §E17），version 必带（守卫 UPDATE 的前置条件）
      await updateSupplier(savedRow.id, { ...payload, version: savedRow.version })
    } else {
      await createSupplier(payload)
    }
  })
  if (ok) {
    dialogVisible.value = false
    ElMessage.success(savedRow ? '已保存' : '已新增')
    await load(savedRow ? page.value : 1)
  }
}

async function toggleStatus(row: SupplierSummary): Promise<void> {
  const next = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  const confirmed = await ElMessageBox.confirm(
    `确定要${SUPPLIER_STATUS_LABEL[next]}供应商「${row.name}」吗？`,
    '提示',
    { type: 'warning' },
  ).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    await changeSupplierStatus(row.id, next, row.version)
    ElMessage.success(`已${SUPPLIER_STATUS_LABEL[next]}`)
    await load()
  } catch {
    // 3xxx（版本冲突）与 4xxx/5xxx 已由 http 拦截器提示，其中 3xxx 还会刷新当前视图
  }
}
</script>

<template>
  <PageHeader
    eyebrow="MASTER DATA / SUPPLIERS"
    title="供应商档案"
    :description="`共 ${total} 家 · 编码为唯一业务键`"
  >
    <template #actions>
      <el-button
        type="primary"
        @click="openDialog(null)"
      >
        新增供应商
      </el-button>
    </template>
  </PageHeader>

  <form
    class="list-toolbar"
    @submit.prevent="search"
  >
    <el-input
      v-model="query.keyword"
      class="list-toolbar__search"
      placeholder="编码或名称包含"
      clearable
      aria-label="编码或名称"
      @keyup.enter="search"
    >
      <template #prefix>
        <svg
          class="list-toolbar__icon"
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <circle
            cx="11"
            cy="11"
            r="7"
          />
          <path d="m20 20-4-4" />
        </svg>
      </template>
    </el-input>
    <el-input
      v-model="query.category"
      class="list-toolbar__select"
      placeholder="分类包含"
      clearable
      aria-label="分类"
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
        prop="code"
        label="编码"
        min-width="140"
      >
        <template #default="{ row }">
          <span class="supplier-code">{{ (row as SupplierSummary).code }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="供应商"
        min-width="230"
      >
        <template #default="{ row }">
          <span class="supplier-name">{{ (row as SupplierSummary).name }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="分类"
        width="120"
      >
        <template #default="{ row }">
          {{ (row as SupplierSummary).category || '—' }}
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="110"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="(row as SupplierSummary).status === 'ENABLED' ? 'is-success' : 'is-off'"
          >
            {{ SUPPLIER_STATUS_LABEL[(row as SupplierSummary).status] }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="150"
        align="right"
      >
        <template #default="{ row }">
          <el-button
            link
            @click="openDialog(row as SupplierSummary)"
          >
            编辑
          </el-button>
          <el-button
            link
            @click="toggleStatus(row as SupplierSummary)"
          >
            {{ (row as SupplierSummary).status === 'ENABLED' ? '停用' : '启用' }}
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

  <el-dialog
    v-model="dialogVisible"
    :title="isEdit ? '编辑供应商' : '新增供应商'"
    width="480px"
  >
    <el-alert
      v-if="saveError"
      class="page-alert"
      type="error"
      :title="saveError"
      :closable="false"
      show-icon
    />
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="64px"
      @submit.prevent="submit"
    >
      <el-form-item
        label="编码"
        prop="code"
      >
        <el-input
          v-model="form.code"
          placeholder="唯一，可修改"
        />
      </el-form-item>
      <el-form-item
        label="名称"
        prop="name"
      >
        <el-input v-model="form.name" />
      </el-form-item>
      <el-form-item
        label="分类"
        prop="category"
      >
        <el-input
          v-model="form.category"
          placeholder="可留空"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="saving"
        @click="submit"
      >
        保存
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.supplier-code {
  color: var(--muted);
  font-family: var(--font-mono);
  font-size: 11px;
}

.supplier-name {
  color: var(--ink);
  font-weight: 500;
}

</style>
