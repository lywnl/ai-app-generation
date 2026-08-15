import { readFileSync } from 'node:fs'

import { describe, expect, it } from 'vitest'

const readSource = (relativeUrl: string) =>
  readFileSync(new URL(relativeUrl, import.meta.url), 'utf8')

const typingsSource = readSource('../api/typings.d.ts')
const envSource = readSource('./env.ts')
const envExampleSource = readSource('./env.example.ts')
const developmentEnv = readSource('../../.env.development')
const homeSource = readSource('../pages/HomePage.vue')
const appCardSource = readSource('../components/AppCard.vue')
const appEditSource = readSource('../pages/app/AppEditPage.vue')

describe('后端统一部署URL契约', () => {
  it('AppVO暴露后端生成的完整部署地址', () => {
    expect(typingsSource).toMatch(
      /type AppVO = \{[\s\S]*?deployKey\?: string[\s\S]*?deployUrl\?: string/,
    )
  })

  it('所有作品入口只读取deployUrl而不使用deployKey拼接', () => {
    expect(homeSource).not.toContain("import { getDeployUrl } from '@/config/env'")
    expect(homeSource).toContain('if (app.deployUrl)')
    expect(homeSource).toContain("window.open(app.deployUrl, '_blank')")
    expect(homeSource).not.toContain('getDeployUrl(')

    expect(appCardSource).toContain('v-if="app.deployUrl"')
    expect(appEditSource).toContain('v-if="appInfo?.deployUrl"')
    expect(appEditSource).toContain('if (appInfo.value?.deployUrl)')
    expect(appEditSource).toContain("window.open(appInfo.value.deployUrl, '_blank')")
  })

  it('前端不再配置或拼接部署域名', () => {
    for (const source of [envSource, envExampleSource, developmentEnv]) {
      expect(source).not.toContain('VITE_DEPLOY_DOMAIN')
      expect(source).not.toContain('DEPLOY_DOMAIN')
    }
    expect(envSource).not.toContain('getDeployUrl')
  })
})
