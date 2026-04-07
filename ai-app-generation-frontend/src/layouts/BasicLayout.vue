<template>
  <a-layout class="basic-layout">
    <a-layout-header class="header">
      <GlobalHeader />
    </a-layout-header>
    <a-layout-content class="content">
      <router-view />
    </a-layout-content>
    <a-layout-footer class="footer">
      <GlobalFooter />
    </a-layout-footer>
  </a-layout>
</template>

<script setup lang="ts">
import GlobalHeader from '@/components/GlobalHeader.vue'
import GlobalFooter from '@/components/GlobalFooter.vue'
</script>

<style scoped>
.basic-layout {
  min-height: 100vh;
}

.header {
  padding: 0;
  background: #fff;
  box-shadow: 0 2px 8px #f0f1f2;
}

.content {
  padding: 24px;
  background: linear-gradient(to right, #fefefe, #fff);
  /* Optionally add a background gradient or plain color here */
}

.footer {
  padding: 0;
}
</style>
