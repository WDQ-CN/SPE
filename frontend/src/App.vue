<script setup lang="ts">
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>

<template>
  <!-- Element Plus 内置文案（分页、表格空态、日期选择器等）统一走简体中文 -->
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>
