<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
// ElMessage 由 unplugin-auto-import 按需注入；本页不用 ElMessageBox（改名不需要二次确认）
import type { FormInstance, FormRules } from 'element-plus'

import { createTemplate, listTemplates, renameTemplate } from '@/features/template/api'
import { useAuthStore } from '@/features/auth/store'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'
import type { TemplateSummary } from '@/shared/types/dto'

/**
 * 模板列表（01 T1 / 02 §4 `GET /templates`）。
 *
 * <p>**没有搜索框**：后端该端点只接受 `page`/`size`（12 §3-W3 的端点表），前端不造一个后端不支持的
 * 搜索条件——那种"看着能搜、其实没生效"的输入框比没有更糟。
 *
 * <p>**MANAGER 只读**（01 §7 给 MANAGER「查看」）：新增/改名入口按角色隐藏。但这只是体验，
 * 真正的判定始终在后端（写端点要求 ADMIN，越权返回 403+4001 并留 `ACCESS_DENIED`审计）。
 */
const router = useRouter()
const auth = useAuthStore()
const canWrite = computed(() => auth.roles.includes('ADMIN'))

const { rows, total, page, size, loading, error, load } = usePagedList<TemplateSummary>(
  (targetPage, pageSize) => listTemplates({ page: targetPage, size: pageSize }),
)

onMounted(() => void load(1))

// ---- 新建 / 改名（模板级没有版本号守卫：template 表没有乐观锁列，12 §E2）----
const dialogVisible = ref(false)
const editing = ref<TemplateSummary | null>(null)
const formRef = ref<FormInstance>()
const form = reactive({ name: '' })
const { submitting: saving, errorMessage: saveError, run: runSave, clearError } = useSubmit()

const isEdit = computed(() => editing.value !== null)
const dialogTitle = computed(() => (isEdit.value ? '重命名模板' : '新建模板'))

// 与后端 CreateTemplateRequest/RenameTemplateRequest 对齐：名称必填且 ≤100
const rules: FormRules = {
  name: [
    { required: true, message: '请输入模板名称', trigger: 'blur' },
    { max: 100, message: '模板名称长度不得超过 100', trigger: 'blur' },
  ],
}

function openCreate(): void {
  editing.value = null
  form.name = ''
  clearError()
  dialogVisible.value = true
  void nextTick(() => formRef.value?.clearValidate())
}

function openRename(row: TemplateSummary): void {
  editing.value = row
  form.name = row.name
  clearError()
  dialogVisible.value = true
  void nextTick(() => formRef.value?.clearValidate())
}

async function submit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  const name = form.name.trim()
  const target = editing.value
  // ⚠️ 用**包装对象**接住新建结果，而不是 `let created: TemplateSummary | null`：
  // `useSubmit.run` 接收的是闭包，TS 的控制流分析看不到闭包内的赋值，会把 `let` 变量一直窄化成
  // `null`（读属性直接报 `Property 'latestVersionId' does not exist on type 'never'`）。
  // 它**只窄化变量、不窄化对象属性**，所以换成一个对象就正常了。
  const created: { value: TemplateSummary | null } = { value: null }
  const ok = await runSave(async () => {
    if (target) {
      await renameTemplate(target.id, name)
    } else {
      created.value = await createTemplate(name)
    }
  })
  if (!ok) {
    return
  }
  dialogVisible.value = false

  if (target) {
    ElMessage.success('已重命名')
    await load(page.value)
    return
  }

  // ⚠️ 新建成功后**直接用创建响应里的 latestVersionId**，不要再去列表里按名称找那一行：
  //   · `template.name` 后端**没有唯一键**（12 §E2：不替需求加约束），同名时
  //     `rows.find(r => r.name === name)` 会命中页面顺序靠前的**旧模板**（列表 `ORDER BY t.id` 升序）
  //     → 用户被带进别人的模板，接着在那里改维度、发布，**写到了非预期的模板上**；
  //   · 模板数超过一页时新模板根本不在第 1 页，按名称必然找不到。
  // 创建响应本来就带 `latestVersionId`（`TemplateService.create` 的返回值），所以这样既更正确、
  // 也省掉一次「为了拿 id 而重新拉列表」的请求。坑由 P2 深审的前端审查者发现（03 §30 的 P1-3）。
  const versionId = created.value?.latestVersionId
  if (versionId) {
    ElMessage.success('已新建，正在进入编辑器')
    await router.push({ name: 'template-editor', params: { versionId } })
  } else {
    // 理论上到不了这里（create 一定回 latestVersionId）；留着是为了万一契约变了能说清楚
    ElMessage.warning('已创建，但响应里没有版本 id，请从列表进入编辑器')
  }
}

async function openEditor(row: TemplateSummary): Promise<void> {
  if (!row.latestVersionId) {
    ElMessage.warning('该模板还没有版本')
    return
  }
  await router.push({ name: 'template-editor', params: { versionId: row.latestVersionId } })
}

/** 草稿标记：有草稿说明"改过但还没发布"，这是列表上最需要一眼看到的状态 */
function draftTagType(row: TemplateSummary): 'warning' | 'info' {
  return row.draftCount > 0 ? 'warning' : 'info'
}
</script>

<template>
  <PageHeader
    eyebrow="EVALUATION TEMPLATES"
    title="评估模板"
  />

  <SectionCard title="模板列表">
    <template #actions>
      <el-button
        v-if="canWrite"
        type="primary"
        @click="openCreate"
      >
        新建模板
      </el-button>
    </template>

    <el-alert
      v-if="!canWrite"
      class="page__notice"
      type="info"
      :closable="false"
      show-icon
      title="当前角色为只读：可以查看模板与权重口径，但不能修改或发布。"
    />

    <el-alert
      v-if="error"
      class="page__error"
      type="error"
      :closable="false"
      show-icon
      :title="error"
    />

    <el-table
      v-loading="loading"
      :data="rows"
      stripe
    >
      <el-table-column
        prop="name"
        label="模板名称"
        min-width="200"
      />
      <el-table-column
        label="版本数"
        width="100"
      >
        <template #default="{ row }">
          {{ row.versionCount }}
        </template>
      </el-table-column>
      <el-table-column
        label="最新已发布"
        width="120"
      >
        <template #default="{ row }">
          <span v-if="row.latestPublishedNo === null">—</span>
          <span v-else>v{{ row.latestPublishedNo }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="120"
      >
        <template #default="{ row }">
          <el-tag :type="draftTagType(row as TemplateSummary)">
            {{ row.draftCount > 0 ? '有草稿' : '无草稿' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="200"
        fixed="right"
      >
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            :disabled="!row.latestVersionId"
            @click="openEditor(row as TemplateSummary)"
          >
            编辑版本
          </el-button>
          <el-button
            v-if="canWrite"
            link
            type="primary"
            @click="openRename(row as TemplateSummary)"
          >
            重命名
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- ⚠️ 用 `v-model:` 双向绑定，与 UserListView/SupplierListView 一致（P2 深审的 P2-11）：
         单向 `:current-page` + 模板里手写 `size = target` 虽然实测能工作（编译产物里 ref 赋值被改写成
         `.value`），但失败路径下 Element Plus 内部的当前页与数据可能不一致，且偏离了两处既有约定。 -->
    <el-pagination
      v-model:current-page="page"
      v-model:page-size="size"
      class="page__pager"
      layout="total, prev, pager, next, sizes"
      :total="total"
      :page-sizes="[10, 20, 50]"
      @current-change="(target: number) => load(target)"
      @size-change="() => load(1)"
    />

    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="440px"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="90px"
      >
        <el-form-item
          label="模板名称"
          prop="name"
        >
          <el-input
            v-model="form.name"
            maxlength="100"
            show-word-limit
            placeholder="如：2026 年度供应商评估"
          />
        </el-form-item>
        <el-form-item v-if="saveError">
          <span class="page__error-text">{{ saveError }}</span>
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
  </SectionCard>
</template>

<style scoped>
.page__notice {
  margin-bottom: 12px;
}

.page__error {
  margin-bottom: 12px;
}

.page__error-text {
  color: var(--el-color-danger);
}

.page__pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
