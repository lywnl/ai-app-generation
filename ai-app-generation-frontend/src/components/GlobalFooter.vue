<template>
  <a-layout-footer class="footer">
    <div class="footer-grid">
      <!-- 关于 -->
      <div class="footer-col">
        <div class="footer-brand">
          <svg class="brand-mark" viewBox="0 0 32 32" aria-hidden="true">
            <defs>
              <linearGradient id="footerBrandGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                <stop offset="0%" stop-color="#3b82f6" />
                <stop offset="100%" stop-color="#8b5cf6" />
              </linearGradient>
            </defs>
            <rect width="32" height="32" rx="8" fill="url(#footerBrandGrad)" />
            <path
              d="M10 22V10l6 12 6-12v12"
              stroke="white"
              stroke-width="2.5"
              fill="none"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
          <span class="brand-text">AI 应用生成</span>
        </div>
        <p class="footer-desc">一句话需求 → 可部署 Web 应用</p>
      </div>

      <!-- 技术栈 -->
      <div class="footer-col">
        <h4 class="footer-title">技术栈</h4>
        <ul class="footer-list">
          <li>Spring Boot 3.5 · LangChain4j</li>
          <li>Vue 3.5 · Ant Design Vue</li>
          <li>DeepSeek · Qwen · pgvector</li>
        </ul>
      </div>

      <!-- 链接 -->
      <div class="footer-col">
        <h4 class="footer-title">资源</h4>
        <ul class="footer-list footer-links">
          <li>
            <a href="https://gitee.com/lywynl/ai-app-generation" target="_blank" rel="noopener" aria-label="Gitee 仓库">
              <svg class="link-icon" viewBox="0 0 24 24" aria-hidden="true">
                <path
                  d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm5 11h-3v3h-2v-3H9v-2h3V8h2v3h3v2z"
                  fill="currentColor"
                />
              </svg>
              开源仓库
            </a>
          </li>
        </ul>
      </div>
    </div>
    <div class="footer-bottom">
      <div class="beian">
        <span class="beian-item">
          ICP备案/许可证号：
          <a
            href="https://beian.miit.gov.cn/"
            target="_blank"
            rel="noopener"
            class="beian-link"
          >
            鲁ICP备2026024359号
          </a>
        </span>
        <a
          href="https://beian.mps.gov.cn/#/query/webSearch?code=37010302001772"
          target="_blank"
          rel="noopener"
          class="beian-link beian-gov"
        >
          <img :src="beianIcon" alt="公网安备" class="beian-icon" />
          鲁公网安备37010302001772号
        </a>
      </div>
    </div>
  </a-layout-footer>
</template>

<script setup lang="ts">
import beianIcon from '@/assets/beian.jpg'
</script>

<style scoped>
.footer {
  background: var(--bg-soft);
  border-top: 1px solid var(--border-light);
  padding: 40px 24px 20px;
  margin-top: 60px;
}

.footer-grid {
  max-width: 1200px;
  margin: 0 auto;
  display: grid;
  grid-template-columns: 1.5fr 1fr 1fr;
  gap: 48px;
  padding-bottom: 28px;
  border-bottom: 1px solid var(--border-light);
}

.footer-col {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.footer-brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.brand-mark {
  width: 32px;
  height: 32px;
  border-radius: var(--radius-sm);
  filter: drop-shadow(0 4px 12px rgba(59, 130, 246, 0.25));
}

.brand-text {
  font-size: 16px;
  font-weight: 700;
  background: var(--brand-gradient-pure);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
}

.footer-desc {
  margin: 0;
  font-size: 13px;
  color: var(--text-tertiary);
  line-height: 1.6;
}

.footer-title {
  margin: 0 0 4px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.footer-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.footer-list li {
  font-size: 13px;
  color: var(--text-tertiary);
  line-height: 1.6;
}

.footer-links a {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-tertiary);
  text-decoration: none;
  transition: color var(--transition-base);
  cursor: pointer;
}

.footer-links a:hover {
  color: var(--brand-primary);
}

.link-icon {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
}

.footer-bottom {
  max-width: 1200px;
  margin: 0 auto;
  padding-top: 20px;
  text-align: center;
}

.beian {
  margin: 0;
  color: var(--text-muted);
  font-size: 12px;
  letter-spacing: 0.3px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 16px;
}

.beian-item {
  display: inline-flex;
  align-items: center;
}

.beian-link {
  color: var(--text-muted);
  text-decoration: none;
  transition: color var(--transition-base);
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.beian-link:hover {
  color: var(--brand-primary);
}

.beian-icon {
  width: 14px;
  height: 14px;
  display: inline-block;
  object-fit: contain;
}

@media (max-width: 768px) {
  .footer-grid {
    grid-template-columns: 1fr;
    gap: 28px;
  }
}
</style>
