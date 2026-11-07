<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
// ElMessage 由 unplugin-auto-import 按需注入；ElMessageBox 不在它的白名单里，
// 需要显式引入**并带上样式**——按需引入模式下不会自动注入它的 CSS，否则弹窗样式会缺失。
import { ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

import {
  changeUserStatus,
  createUser,
  listUsers,
  resetUserPassword,
  updateUser,
  type CreateUserPayload,
} from '@/features/user/api'
import { usePagedList } from '@/shared/composables/usePagedList'
import { useSubmit } from '@/shared/composables/useSubmit'
import {
  DEPARTMENT_LABEL,
  DEPARTMENT_OPTIONS,
  ROLE_LABEL,
  ROLE_OPTIONS,
  USER_STATUS_LABEL,
} from '@/shared/labels'
import type { UserSummary } from '@/shared/types/dto'

const { rows, total, page, size, loading, error, load } = usePagedList<UserSummary>(
  (targetPage, pageSize) => listUsers({ page: targetPage, size: pageSize }),
)

onMounted(() => void load(1))

// ---- 新增 / 编辑（同一个对话框：字段几乎相同，分成两套只会双份维护校验规则）----
const dialogVisible = ref(false)
const editing = ref<UserSummary | null>(null)
const formRef = ref<FormInstance>()
const form = reactive<CreateUserPayload>({
  username: '',
  password: '',
  name: '',
  role: 'EVALUATOR',
  department: 'QUALITY',
})
const { submitting: saving, errorMessage: saveError, run: runSave, clearError } = useSubmit()

const isEdit = computed(() => editing.value !== null)

// 与后端 CreateUserRequest/UpdateUserRequest 的约束逐条对齐（账号 ≤50、口令 ≥8、姓名 ≤50）
const rules: FormRules = {
  username: [
    { required: true, message: '请输入账号', trigger: 'blur' },
    { max: 50, message: '账号长度不得超过 50', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入初始口令', trigger: 'blur' },
    { min: 8, message: '初始口令长度不得少于 8 位', trigger: 'blur' },
  ],
  name: [
    { required: true, message: '请输入姓名', trigger: 'blur' },
    { max: 50, message: '姓名长度不得超过 50', trigger: 'blur' },
  ],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }],
  department: [{ required: true, message: '请选择部门', trigger: 'change' }],
}

function openDialog(row: UserSummary | null): void {
  editing.value = row
  Object.assign(form, {
    username: row?.username ?? '',
    password: '',
    name: row?.name ?? '',
    role: row?.role ?? 'EVALUATOR',
    department: row?.department ?? 'QUALITY',
  })
  clearError()
  dialogVisible.value = true
  // 复用同一个表单实例：不清理的话上一次的红字会留在新开的对话框里
  void nextTick(() => formRef.value?.clearValidate())
}

async function submit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  const savedRow = editing.value
  const ok = await runSave(async () => {
    if (savedRow) {
      // 编辑不带 username（账号不可改），version 必带——它是后端守卫 UPDATE 的前置条件
      await updateUser(savedRow.id, {
        name: form.name,
        role: form.role,
        department: form.department,
        version: savedRow.version,
      })
    } else {
      await createUser({
        username: form.username.trim(),
        password: form.password,
        name: form.name,
        role: form.role,
        department: form.department,
      })
    }
  })
  if (ok) {
    dialogVisible.value = false
    ElMessage.success(savedRow ? '已保存' : '已新增')
    // 新增的账号排在列表末尾（按 id 升序），留在当前页会看不到它
    await load(savedRow ? page.value : 1)
  }
}

// ---- 启停 / 重置口令 ----
async function toggleStatus(row: UserSummary): Promise<void> {
  const next = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  const confirmed = await ElMessageBox.confirm(
    `确定要${USER_STATUS_LABEL[next]}「${row.name}」吗？`,
    '提示',
    { type: 'warning' },
  ).catch(() => false)
  if (confirmed === false) {
    return
  }
  try {
    await changeUserStatus(row.id, next, row.version)
    ElMessage.success(`已${USER_STATUS_LABEL[next]}`)
    await load()
  } catch {
    // 3xxx（版本冲突）与 4xxx/5xxx 已由 http 拦截器提示，其中 3xxx 还会刷新当前视图
  }
}

const passwordVisible = ref(false)
const passwordTarget = ref<UserSummary | null>(null)
const passwordFormRef = ref<FormInstance>()
const passwordForm = reactive({ newPassword: '' })
const {
  submitting: resetting,
  errorMessage: resetError,
  run: runReset,
  clearError: clearResetError,
} = useSubmit()

const passwordRules: FormRules = {
  newPassword: [
    { required: true, message: '请输入新口令', trigger: 'blur' },
    { min: 8, message: '新口令长度不得少于 8 位', trigger: 'blur' },
  ],
}

function openReset(row: UserSummary): void {
  passwordTarget.value = row
  passwordForm.newPassword = ''
  clearResetError()
  passwordVisible.value = true
  void nextTick(() => passwordFormRef.value?.clearValidate())
}

async function submitReset(): Promise<void> {
  const target = passwordTarget.value
  if (!target) {
    return
  }
  const valid = await passwordFormRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  const ok = await runReset(async () => {
    await resetUserPassword(target.id, passwordForm.newPassword)
  })
  if (ok) {
    passwordVisible.value = false
    ElMessage.success(`已重置「${target.name}」的口令`)
  }
}
</script>

<template>
  <PageHeader
    eyebrow="ACCESS CONTROL / USERS"
    title="用户管理"
    :description="`共 ${total} 个账号 · 角色决定可用入口与数据范围`"
  >
    <template #actions>
      <el-button
        type="primary"
        @click="openDialog(null)"
      >
        新增用户
      </el-button>
    </template>
  </PageHeader>

  <SectionCard title="用户列表">
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

    <el-table
      v-loading="loading"
      :data="rows"
      class="table-list"
    >
      <el-table-column
        label="账号"
        min-width="160"
      >
        <template #default="{ row }">
          <span class="user-username">{{ (row as UserSummary).username }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="姓名"
        min-width="130"
      >
        <template #default="{ row }">
          <span class="user-name">{{ (row as UserSummary).name }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="角色"
        min-width="120"
      >
        <template #default="{ row }">
          {{ ROLE_LABEL[(row as UserSummary).role] }}
        </template>
      </el-table-column>
      <el-table-column
        label="部门"
        min-width="110"
      >
        <template #default="{ row }">
          {{ DEPARTMENT_LABEL[(row as UserSummary).department] }}
        </template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="90"
      >
        <template #default="{ row }">
          <span
            class="status-mark"
            :class="(row as UserSummary).status === 'ENABLED' ? 'is-success' : 'is-off'"
          >
            {{ USER_STATUS_LABEL[(row as UserSummary).status] }}
          </span>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="240"
        align="right"
        fixed="right"
      >
        <template #default="{ row }">
          <el-button
            link
            @click="openDialog(row as UserSummary)"
          >
            编辑
          </el-button>
          <el-button
            link
            @click="toggleStatus(row as UserSummary)"
          >
            {{ (row as UserSummary).status === 'ENABLED' ? '停用' : '启用' }}
          </el-button>
          <el-button
            link
            @click="openReset(row as UserSummary)"
          >
            重置口令
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

    <!-- 新增 / 编辑 -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑用户' : '新增用户'"
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
        label-width="88px"
        @submit.prevent="submit"
      >
        <el-form-item
          label="账号"
          prop="username"
        >
          <!-- 账号是登录标识，后端 UpdateUserRequest 里没有它：编辑时只读 -->
          <el-input
            v-model="form.username"
            :disabled="isEdit"
            placeholder="登录用的账号"
          />
        </el-form-item>
        <el-form-item
          v-if="!isEdit"
          label="初始口令"
          prop="password"
        >
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="至少 8 位"
          />
        </el-form-item>
        <el-form-item
          label="姓名"
          prop="name"
        >
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item
          label="角色"
          prop="role"
        >
          <el-select
            v-model="form.role"
            placeholder="请选择"
          >
            <el-option
              v-for="option in ROLE_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item
          label="部门"
          prop="department"
        >
          <el-select
            v-model="form.department"
            placeholder="请选择"
          >
            <el-option
              v-for="option in DEPARTMENT_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
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

    <!-- 重置口令 -->
    <el-dialog
      v-model="passwordVisible"
      title="重置口令"
      width="440px"
    >
      <el-alert
        v-if="resetError"
        class="page__error"
        type="error"
        :title="resetError"
        :closable="false"
        show-icon
      />
      <p class="page__hint">
        将重置「{{ passwordTarget?.name }}」的登录口令；对方下次登录用新口令。
      </p>
      <el-form
        ref="passwordFormRef"
        :model="passwordForm"
        :rules="passwordRules"
        label-width="88px"
        @submit.prevent="submitReset"
      >
        <el-form-item
          label="新口令"
          prop="newPassword"
        >
          <el-input
            v-model="passwordForm.newPassword"
            type="password"
            show-password
            placeholder="至少 8 位"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordVisible = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="resetting"
          @click="submitReset"
        >
          确定
        </el-button>
      </template>
    </el-dialog>
  </SectionCard>
</template>

<style scoped>
.page__error {
  margin-bottom: 12px;
}

.page__hint {
  margin: 0 0 12px;
  color: var(--muted);
  font-size: 12px;
  line-height: 18px;
}

.user-username {
  color: var(--muted);
  font-family: var(--font-mono);
  font-size: 11px;
}

.user-name {
  color: var(--ink);
  font-weight: 500;
}

</style>
