<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { portalAuthenticated, portalLogin } from '@/features/portal/api'

const router = useRouter()
const route = useRoute()
const form = reactive({ loginName: '', password: '' })
const submitting = ref(false)
const error = ref('')

onMounted(() => {
  if (portalAuthenticated()) {
    void router.replace('/portal/auctions')
  }
})

async function submit(): Promise<void> {
  if (!form.loginName.trim() || !form.password) {
    error.value = '请输入登录名与口令'
    return
  }
  submitting.value = true
  error.value = ''
  try {
    await portalLogin(form.loginName.trim(), form.password)
    const redirect =
      typeof route.query.redirect === 'string' ? route.query.redirect : '/portal/auctions'
    await router.replace(redirect)
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '登录失败'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="portal-login">
    <div class="portal-login__card">
      <p class="page-eyebrow">
        SUPPLIER PORTAL
      </p>
      <h1 class="page-title">
        供应商门户
      </h1>
      <p class="page-lede">
        使用门户账号登录，查看被邀请的询价与竞价。内部员工账号不可用于门户。
      </p>
      <el-alert
        v-if="error"
        class="page-alert"
        type="error"
        :title="error"
        :closable="false"
        show-icon
      />
      <el-form
        label-position="top"
        @submit.prevent="submit"
      >
        <el-form-item label="登录名">
          <el-input
            v-model="form.loginName"
            maxlength="64"
            placeholder="门户登录名"
          />
        </el-form-item>
        <el-form-item label="口令">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="口令"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button
          type="primary"
          class="portal-login__submit"
          :loading="submitting"
          @click="submit"
        >
          登录
        </el-button>
      </el-form>
      <p class="portal-login__hint">
        收到邀请邮件/链接？打开邀请链接完成账号绑定。
      </p>
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
  width: min(420px, 100%);
  padding: 36px 32px;
  background: var(--surface);
  border: 1px solid var(--rule);
}

.portal-login__submit {
  width: 100%;
  margin-top: 8px;
}

.portal-login__hint {
  margin-top: 20px;
  color: var(--muted);
  font-size: 12px;
  line-height: 18px;
}
</style>
