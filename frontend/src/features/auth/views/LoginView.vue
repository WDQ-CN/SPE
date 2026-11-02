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

const { submitting, errorMessage, run } = useSubmit()

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
    <div class="login__panel">
      <section class="login__brand">
        <p class="login__eyebrow">
          SUPPLIER PERFORMANCE LEDGER
        </p>
        <h1 class="login__title">
          {{ APP_NAME }}
        </h1>
        <p class="login__rule" />
        <p class="login__quote">
          把每一次评估，<br>写成可追溯的记录。
        </p>
        <p class="login__brand-note">
          SPE · Editorial Ledger<br>
          Internal access only
        </p>
      </section>

      <el-card
        class="login__card"
        shadow="never"
      >
        <div class="login__card-head">
          <p class="login__card-eyebrow">
            Account access
          </p>
          <h2 class="login__card-title">
            登录
          </h2>
          <p class="login__card-sub">
            使用内部账号进入供应商绩效台账。
          </p>
        </div>

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
          label-width="52px"
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
  </div>
</template>

<style scoped>
.login {
  display: grid;
  place-items: center;
  min-height: 100vh;
  padding: 32px;
  background:
    linear-gradient(rgb(247 244 237 / 92%), rgb(247 244 237 / 92%)),
    repeating-linear-gradient(0deg, transparent 0 63px, rgb(222 216 204 / 38%) 63px 64px);
}

.login__panel {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(360px, 0.95fr);
  width: min(1000px, calc(100vw - 64px));
  min-height: 560px;
  border: 1px solid var(--rule);
  background: var(--surface);
}

.login__brand {
  display: flex;
  flex-direction: column;
  padding: 42px 48px 36px;
  background: var(--ink);
  color: var(--paper);
}

.login__eyebrow {
  margin: 0;
  color: rgb(247 244 237 / 48%);
  font-size: 8.5px;
  line-height: 13px;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.login__title {
  margin: 18px 0 0;
  color: var(--paper);
  font-family: var(--font-serif);
  font-size: clamp(28px, 3.4vw, 42px);
  line-height: 1.15;
  font-weight: 500;
  letter-spacing: -1px;
}

.login__rule {
  width: 42px;
  height: 1px;
  margin: 32px 0 24px;
  background: var(--accent);
}

.login__quote {
  margin: 0;
  color: rgb(247 244 237 / 88%);
  font-family: var(--font-serif);
  font-size: 23px;
  line-height: 34px;
  font-weight: 500;
}

.login__brand-note {
  margin: auto 0 0;
  color: rgb(247 244 237 / 38%);
  font-family: var(--font-mono);
  font-size: 9px;
  line-height: 16px;
}

.login :deep(.login__card.el-card) {
  align-self: center;
  margin: 0;
  border: 0;
  background: transparent;
  box-shadow: none;
}

.login :deep(.login__card .el-card__body) {
  padding: 42px 48px;
}

.login__card-head {
  margin-bottom: 26px;
}

.login__card-eyebrow {
  margin: 0;
  color: var(--muted-2);
  font-size: 8.5px;
  line-height: 13px;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.login__card-title {
  margin: 8px 0 0;
  font-family: var(--font-serif);
  font-size: 34px;
  line-height: 40px;
  font-weight: 500;
  letter-spacing: -1px;
}

.login__card-sub {
  margin: 7px 0 0;
  color: var(--muted);
  font-size: 11px;
  line-height: 18px;
}

.login__error {
  margin-bottom: 18px;
}

.login__submit {
  width: 100%;
  margin-top: 4px;
}

@media (max-width: 820px) {
  .login {
    padding: 16px;
  }

  .login__panel {
    grid-template-columns: 1fr;
    width: min(100%, 460px);
    min-height: 0;
  }

  .login__brand {
    padding: 28px 28px 24px;
  }

  .login__quote {
    font-size: 19px;
    line-height: 29px;
  }

  .login__brand-note {
    margin-top: 24px;
  }

  .login :deep(.login__card .el-card__body) {
    padding: 30px 28px;
  }
}
</style>
