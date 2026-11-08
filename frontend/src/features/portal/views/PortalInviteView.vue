<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { acceptInvitation } from '@/features/portal/api'

const route = useRoute()
const router = useRouter()
const form = reactive({ loginName: '', password: '', confirm: '' })
const submitting = ref(false)
const error = ref('')
const accepted = ref(false)

async function submit(): Promise<void> {
  const token = String(route.params.token ?? '')
  if (!token) {
    error.value = '邀请链接无效'
    return
  }
  if (!form.loginName.trim() || !form.password) {
    error.value = '请填写登录名与口令'
    return
  }
  if (form.password !== form.confirm) {
    error.value = '两次输入的口令不一致'
    return
  }
  submitting.value = true
  error.value = ''
  try {
    await acceptInvitation(token, form.loginName.trim(), form.password)
    accepted.value = true
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '接受邀请失败'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="portal-login">
    <div class="portal-login__card">
      <p class="page-eyebrow">
        SUPPLIER PORTAL / INVITATION
      </p>
      <h1 class="page-title">
        接受邀请
      </h1>
      <p class="page-lede">
        邀请 Token 单次有效。完成后即可用新账号登录门户。
      </p>
      <el-alert
        v-if="error"
        class="page-alert"
        type="error"
        :title="error"
        :closable="false"
        show-icon
      />
      <el-result
        v-if="accepted"
        icon="success"
        title="账号已创建"
        sub-title="现在可以用登录名与口令进入供应商门户。"
      >
        <template #extra>
          <el-button
            type="primary"
            @click="router.replace('/portal/login')"
          >
            去登录
          </el-button>
        </template>
      </el-result>
      <el-form
        v-else
        label-position="top"
        @submit.prevent="submit"
      >
        <el-form-item label="登录名">
          <el-input
            v-model="form.loginName"
            maxlength="64"
          />
        </el-form-item>
        <el-form-item label="口令">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            maxlength="72"
          />
        </el-form-item>
        <el-form-item label="确认口令">
          <el-input
            v-model="form.confirm"
            type="password"
            show-password
            maxlength="72"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button
          type="primary"
          class="portal-login__submit"
          :loading="submitting"
          @click="submit"
        >
          创建门户账号
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.portal-login {
  display: grid;
  min-height: 100vh;
  place-items: center;
  background: var(--paper);
  padding: 24px;
}

.portal-login__card {
  width: min(440px, 100%);
  padding: 36px 32px;
  background: var(--surface);
  border: 1px solid var(--rule);
}

.portal-login__submit {
  width: 100%;
  margin-top: 8px;
}
</style>
