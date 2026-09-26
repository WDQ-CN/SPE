<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'

import { login } from '@/features/auth/api'
import { useAuthStore } from '@/features/auth/store'
import { useSubmit } from '@/shared/composables/useSubmit'
import { APP_NAME } from '@/shared/constants'
import { safeRedirect } from '@/shared/redirect'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const formRef = ref<FormInstance>()
const form = reactive({ username: '', password: '' })

// 用 useSubmit 而不是自己写 submitting/errorMessage：它的闸门在**同步入口**处就置位，
// 而把 submitting 写在 `await validate()` 之后再置位等于没防——两次快速点击会同时通过检查、
// 发两次登录请求，还会让第二次的错误覆盖第一次（04-037，由组件测试发现）。
const { submitting, errorMessage, run } = useSubmit()

// 与后端 LoginRequest 的约束一致（账号非空且 ≤50、口令非空）；这里只做即时反馈，判定在后端
const rules: FormRules = {
  username: [
    { required: true, message: '请输入账号', trigger: 'blur' },
    { max: 50, message: '账号长度不得超过 50', trigger: 'blur' },
  ],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function submit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  await run(async () => {
    const result = await login({ username: form.username.trim(), password: form.password })
    auth.setSession({
      token: result.token,
      name: result.name,
      roles: result.roles,
      department: result.department,
    })
    await router.replace(safeRedirect(route.query.redirect))
  })
}
</script>

<template>
  <div class="login">
    <el-card
      class="login__card"
      shadow="never"
    >
      <template #header>
        {{ APP_NAME }}
      </template>

      <el-alert
        v-if="errorMessage"
        class="login__error"
        type="error"
        :title="errorMessage"
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
          label="账号"
          prop="username"
        >
          <el-input
            v-model="form.username"
            placeholder="请输入账号"
            :disabled="submitting"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-form-item
          label="密码"
          prop="password"
        >
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="请输入密码"
            :disabled="submitting"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button
          type="primary"
          class="login__submit"
          :loading="submitting"
          @click="submit"
        >
          登录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}

.login__card {
  width: 360px;
}

.login__error {
  margin-bottom: 16px;
}

.login__submit {
  width: 100%;
}
</style>
