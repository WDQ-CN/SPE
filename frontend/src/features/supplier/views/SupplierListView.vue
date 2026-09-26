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
  <el-card shadow="never">
    <template #header>
      <div class="page__header">
        <span>供应商档案</span>
        <el-button
          type="primary"
          @click="openDialog(null)"
        >
          新增供应商
        </el-button>
      </div>
    </template>

    <el-form
      class="page__search"
      :inline="true"
      @submit.prevent="search"
    >
      <el-form-item label="关键字">
        <el-input
          v-model="query.keyword"
          placeholder="编码或名称包含"
          clearable
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item label="分类">
        <el-input
          v-model="query.category"
          placeholder="分类包含"
          clearable
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item>
        <el-button
          type="primary"
          @click="search"
        >
          查询
        </el-button>
        <el-button @click="resetSearch">
          重置
        </el-button>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="error"
      class="page__error"
      type="error"
      :title="error"
      :closable="false"
      show-icon
    >
      <el-button
        link
        type="primary"
        @click="load()"
      >
        重试
      </el-button>
    </el-alert>

    <el-table
      v-loading="loading"
      :data="rows"
      border
    >
      <el-table-column
        prop="code"
        label="编码"
        min-width="150"
      />
      <el-table-column
        prop="name"
        label="名称"
        min-width="180"
      />
      <el-table-column
        label="分类"
        min-width="120"
      >
        <template #default="{ row }">
          {{ (row as SupplierSummary).category || '—' }}
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="90"
      >
        <template #default="{ row }">
          <el-tag
            :type="(row as SupplierSummary).status === 'ENABLED' ? 'success' : 'info'"
            size="small"
          >
            {{ SUPPLIER_STATUS_LABEL[(row as SupplierSummary).status] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="150"
        fixed="right"
      >
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            @click="openDialog(row as SupplierSummary)"
          >
            编辑
          </el-button>
          <el-button
            link
            type="primary"
            @click="toggleStatus(row as SupplierSummary)"
          >
            {{ (row as SupplierSummary).status === 'ENABLED' ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="page"
      v-model:page-size="size"
      class="page__pager"
      :total="total"
      :page-sizes="[10, 20, 50]"
      layout="total, sizes, prev, pager, next"
      @current-change="(target: number) => void load(target)"
      @size-change="() => void load(1)"
    />

    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑供应商' : '新增供应商'"
      width="480px"
    >
      <el-alert
        v-if="saveError"
        class="page__error"
        type="error"
        :title="saveError"
        :closable="false"
        show-icon
      />
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="72px"
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
  </el-card>
</template>

<style scoped>
.page__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page__search {
  margin-bottom: 4px;
}

.page__error {
  margin-bottom: 12px;
}

.page__pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
