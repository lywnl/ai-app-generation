import { readFileSync } from 'node:fs'

import { describe, expect, it } from 'vitest'

const pageSource = readFileSync(new URL('./AppChatPage.vue', import.meta.url), 'utf8')

describe('代码生成外层消息容器吸底', () => {
  it('使用即时滚动，避免程序化平滑滚动被误判为用户上滑', () => {
    const messagesContainerStyles = pageSource.match(
      /\.messages-container\s*\{(?<rules>[\s\S]*?)\}/,
    )?.groups?.rules

    expect(messagesContainerStyles).toBeDefined()
    expect(messagesContainerStyles).toContain('overflow-y: auto')
    expect(messagesContainerStyles).toContain('scroll-behavior: auto')
    expect(messagesContainerStyles).not.toContain('scroll-behavior: smooth')
  })

  it('用户离开底部时暂停吸底，回到底部阈值内时恢复', () => {
    expect(pageSource).toContain('stickBottom.value = distance <= 32')
    expect(pageSource).toContain("addEventListener('scroll', handleMessagesScroll")
    expect(pageSource).toContain("removeEventListener('scroll', handleMessagesScroll)")
    expect(pageSource).toMatch(
      /if \(!stickBottom\.value\) return[\s\S]*?el\.scrollTop = el\.scrollHeight/,
    )
  })

  it('初次进入已有对话时在会话恢复完成后定位到最新消息', () => {
    expect(pageSource).toMatch(
      /const scrollToLatestMessageOnEntry = \(\) => \{[\s\S]*?if \(messages\.value\.length === 0\) return[\s\S]*?stickBottom\.value = true[\s\S]*?scrollToBottom\(\)[\s\S]*?\}/,
    )

    const fetchAppInfoStart = pageSource.indexOf('const fetchAppInfo = async () => {')
    const applySessionSnapshotStart = pageSource.indexOf(
      'const applySessionSnapshot = (snapshot:',
      fetchAppInfoStart,
    )
    const fetchAppInfoSource = pageSource.slice(fetchAppInfoStart, applySessionSnapshotStart)

    expect(fetchAppInfoStart).toBeGreaterThanOrEqual(0)
    expect(applySessionSnapshotStart).toBeGreaterThan(fetchAppInfoStart)
    expect(fetchAppInfoSource.indexOf('scrollToLatestMessageOnEntry()')).toBeGreaterThan(
      fetchAppInfoSource.indexOf('restoreActiveSessionIfNeeded()'),
    )
    expect(fetchAppInfoSource).toMatch(
      /if \(shouldSendInitialMessage\) \{[\s\S]*?await sendInitialMessage\(initialPrompt\)[\s\S]*?\} else \{[\s\S]*?scrollToLatestMessageOnEntry\(\)[\s\S]*?\}/,
    )
  })

  it('加载更早历史时不触发首次进入定位', () => {
    const loadMoreHistoryStart = pageSource.indexOf('const loadMoreHistory = async () => {')
    const fetchAppInfoStart = pageSource.indexOf(
      'const fetchAppInfo = async () => {',
      loadMoreHistoryStart,
    )
    const loadMoreHistorySource = pageSource.slice(loadMoreHistoryStart, fetchAppInfoStart)

    expect(loadMoreHistorySource).toContain('await loadChatHistory(true)')
    expect(loadMoreHistorySource).not.toContain('scrollToLatestMessageOnEntry()')
  })
})
