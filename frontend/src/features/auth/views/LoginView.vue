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
    <!-- Sentra 的 Hero 层次：eyebrow → 点阵大标题 → 操作面板 -->
    <div class="login__brand page-block">
      <p class="login__eyebrow">
        SUPPLIER PERFORMANCE EVALUATION
      </p>
      <h1 class="login__title font-display">
        {{ APP_NAME }}
      </h1>
    </div>

    <!-- 刻意不走 SectionCard（03 §35 登记在案的例外）：本页的卡片是"整页唯一、无标题"的登录面板，
         SectionCard 的语义是"标题 + 右侧操作"，在这里两个槽都是空的——用它反而要多写两个空槽。 -->
    <el-card
      class="login__card page-block"
      shadow="never"
      style="animation-delay: 0.15s"
    >
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
/* 登录页是**唯一不在暗壳里**的页面，所以它自己就是"整块 Sentra"：
   纯黑底 + 点阵大标题 + 一张亮色卡片（照 Hero 的层次）。 */
.login {
  display: flex;
  flex-direction: column;
  gap: 32px;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 24px 16px;
  background: #000;
}

.login__brand {
  text-align: center;
}

.login__eyebrow {
  margin: 0 0 12px;
  font-size: 11px;
  color: rgb(255 255 255 / 60%);
  letter-spacing: 0.3em;
  text-transform: uppercase;
}

.login__title {
  margin: 0;
  font-size: clamp(1.8rem, 5vw, 3rem);
  line-height: 1.05;
  color: #fff;
  letter-spacing: -0.01em;
}

.login__card {
  width: min(380px, calc(100vw - 32px));
}

.login__error {
  margin-bottom: 16px;
}

.login__submit {
  width: 100%;
}
</style>
