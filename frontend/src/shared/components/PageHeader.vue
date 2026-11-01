<script setup lang="ts">
withDefaults(
  defineProps<{
    eyebrow: string
    title: string
    description?: string
    delay?: string
  }>(),
  { description: '', delay: '0s' },
)
</script>

<template>
  <header
    class="page-head page-block"
    :style="{ animationDelay: delay }"
  >
    <div class="page-head__copy">
      <p class="page-eyebrow">
        {{ eyebrow }}
      </p>
      <h1 class="page-title">
        {{ title }}
      </h1>
      <p
        v-if="description"
        class="page-lede"
      >
        {{ description }}
      </p>
    </div>
    <div
      v-if="$slots.actions"
      class="page-actions"
    >
      <slot name="actions" />
    </div>
  </header>
</template>
