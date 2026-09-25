<script setup lang="ts">
import { reactive, ref } from 'vue'

import { ping } from '@/features/auth/api'

const form = reactive({ username: '', password: '' })

const checking = ref(false)
const checkState = ref<'idle' | 'ok' | 'fail'>('idle')
const checkMessage = ref('')

async function runSelfCheck(): Promise<void> {
  checking.value = true
  checkState.value = 'idle'
  checkMessage.value = ''
  try {
    const data = await ping()
    checkState.value = 'ok'
    checkMessage.value = `已连通，/api/ping 返回 ${JSON.stringify(data ?? null)}`
  } catch (error) {
    checkState.value = 'fail'
    checkMessage.value = error instanceof Error ? error.message : '未知错误'
  } finally {
    checking.value = false
  }
}

function submit(): void {
  ElMessage.info('登录接口在 P1 接入（01 A1 / 02 §4 POST /auth/login）')
}
</script>

<template>
  <div class="login">
    <el-card
      class="login__card"
      shadow="never"
    >
      <template #header>
        SPE 供应商绩效评估
      </template>
      <el-form
        :model="form"
        label-width="72px"
        @submit.prevent="submit"
      >
        <el-form-item label="账号">
          <el-input
            v-model="form.username"
            placeholder="P1 接入真实登录"
          />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="P1 接入真实登录"
          />
        </el-form-item>
        <el-button
          type="primary"
          class="login__submit"
          @click="submit"
        >
          登录
        </el-button>
      </el-form>
    </el-card>

    <!-- P0 临时自检条：验证 Vite 代理与后端 ping 链路；P1 替换为真实登录（09 WP2） -->
    <el-card
      class="login__check"
      shadow="never"
    >
      <div class="login__check-row">
        <span>后端连通性</span>
        <el-tag
          v-if="checkState === 'ok'"
          type="success"
        >
          成功
        </el-tag>
        <el-tag
          v-else-if="checkState === 'fail'"
          type="danger"
        >
          失败
        </el-tag>
        <el-tag
          v-else
          type="info"
        >
          未检测
        </el-tag>
        <el-button
          size="small"
          :loading="checking"
          @click="runSelfCheck"
        >
          检测
        </el-button>
      </div>
      <p
        v-if="checkMessage"
        class="login__check-message"
      >
        {{ checkMessage }}
      </p>
    </el-card>
  </div>
</template>

<style scoped>
.login {
  display: flex;
  flex-direction: column;
  gap: 16px;
  align-items: center;
  justify-content: center;
  height: 100%;
}

.login__card {
  width: 360px;
}

.login__submit {
  width: 100%;
}

.login__check {
  width: 360px;
}

.login__check-row {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.login__check-message {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}
</style>
