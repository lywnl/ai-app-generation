<script setup lang="ts">
import BasicLayout from '@/layouts/BasicLayout.vue'
</script>

<template>
  <BasicLayout />
</template>

<style>
/* ========== Design Tokens · 科技蓝紫(Vercel/v0 风) ========== */
:root {
  /* 品牌色 */
  --brand-primary: #3b82f6;
  --brand-primary-hover: #2563eb;
  --brand-secondary: #8b5cf6;
  --brand-secondary-hover: #7c3aed;
  --brand-accent: #10b981;
  --brand-gradient: linear-gradient(135deg, #3b82f6 0%, #8b5cf6 50%, #10b981 100%);
  --brand-gradient-pure: linear-gradient(135deg, #3b82f6 0%, #8b5cf6 100%);
  --brand-gradient-soft: linear-gradient(135deg, rgba(59, 130, 246, 0.1) 0%, rgba(139, 92, 246, 0.1) 100%);

  /* 中性色 - 背景 */
  --bg-base: #ffffff;
  --bg-soft: #f8fafc;
  --bg-mute: #f1f5f9;
  --bg-elevated: rgba(255, 255, 255, 0.92);

  /* 边框 */
  --border-light: #e2e8f0;
  --border-default: #cbd5e1;
  --border-focus: #3b82f6;

  /* 文字 */
  --text-primary: #0f172a;
  --text-secondary: #475569;
  --text-tertiary: #64748b;
  --text-muted: #94a3b8;
  --text-inverse: #ffffff;

  /* 状态色 */
  --success: #10b981;
  --success-bg: rgba(16, 185, 129, 0.08);
  --success-border: rgba(16, 185, 129, 0.4);
  --warning: #f59e0b;
  --warning-bg: rgba(245, 158, 11, 0.08);
  --warning-border: rgba(245, 158, 11, 0.4);
  --danger: #ef4444;
  --danger-bg: rgba(239, 68, 68, 0.08);

  /* 阴影 */
  --shadow-sm: 0 2px 8px rgba(15, 23, 42, 0.06);
  --shadow-md: 0 8px 24px rgba(15, 23, 42, 0.08);
  --shadow-lg: 0 15px 40px rgba(59, 130, 246, 0.12);
  --shadow-xl: 0 25px 60px rgba(59, 130, 246, 0.18);
  --shadow-glow: 0 0 40px rgba(139, 92, 246, 0.25);

  /* 圆角 */
  --radius-sm: 8px;
  --radius-md: 12px;
  --radius-lg: 16px;
  --radius-xl: 24px;
  --radius-pill: 999px;

  /* 过渡 */
  --transition-fast: 150ms ease;
  --transition-base: 200ms ease;
  --transition-slow: 300ms ease;
}

/* ========== 全局样式 ========== */
* {
  box-sizing: border-box;
}

body {
  margin: 0;
  padding: 0;
  font-family:
    -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Roboto', 'Oxygen', 'Ubuntu', 'Cantarell',
    'Fira Sans', 'Droid Sans', 'Helvetica Neue', sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  overflow-x: hidden;
  color: var(--text-primary);
  background: var(--bg-base);
}

html {
  overflow-x: hidden;
}

/* 默认聚焦环 */
*:focus-visible {
  outline: 2px solid var(--brand-primary);
  outline-offset: 2px;
  border-radius: 4px;
}
</style>
