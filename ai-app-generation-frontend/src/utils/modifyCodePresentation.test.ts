import { describe, expect, it, vi } from 'vitest'
import type { ChangeObject } from 'diff'
import { createModifyCodePresenter, getStringContentPreview } from './modifyCodePresentation'

const diffCharsMock = vi.hoisted(() => vi.fn<(before: string, after: string, options: { timeout: number }) => ChangeObject<string>[] | undefined>())
vi.mock('diff', async (importOriginal) => {
  const actual = await importOriginal<typeof import('diff')>()
  diffCharsMock.mockImplementation((before, after, options) => actual.diffChars(before, after, options))
  return { ...actual, diffChars: diffCharsMock }
})

const innerBefore = "const error = ref('')\nerror.value = ''\nerror.value = '加载失败，请稍后重试'"
const innerAfter = 'const error = ref("")\nerror.value = ""\nerror.value = "加载失败，请稍后重试"'
const before = "      '```js\\n" + innerBefore.split('\n').join('\\n') + "\\n```',"
const after = "      '```js\\n" + innerAfter.split('\n').join('\\n') + "\\n```',"

describe('修改字符串内容预览', () => {
  it('真实引号修复仅合法一侧可预览，并去掉完整代码围栏', () => {
    expect(getStringContentPreview(before).available).toBe(false)
    expect(getStringContentPreview(after)).toEqual({ available: true, code: innerAfter + '\n', language: 'js' })
  })

  it.each([
    [" '第一行\\n第二行', ", '第一行\n第二行'],
    ['"第一行\\n第二行"', '第一行\n第二行'],
    ['`第一行\n第二行`,', '第一行\n第二行'],
    ['"\\u4e2d\\n\\t文😀"', '中\n\t文😀'],
    ['"a\\r\\nb"', 'a\r\nb'],
    [JSON.stringify('第一行\n字面量\\n和\\t'), '第一行\n字面量\\n和\\t'],
    [JSON.stringify('介绍\n```js\nconst a = 1\n```'), '介绍\n```js\nconst a = 1\n```'],
    [JSON.stringify('```js\na\n```\n```css\nb\n```'), '```js\na\n```\n```css\nb\n```'],
  ])('单层解析 %s，保留原文字符', (source, code) => {
    expect(getStringContentPreview(source)).toEqual({ available: true, code })
  })

  it.each([
    '', "''", "'单行'", "'字面量\\\\n不是换行'", "'引号未闭合",
    "'a\\n' + 'b'", "String('a\\n')", "'a\\n', 'b'", "'a\\n',,",
    '`a\n${run()}`', "['a\\n']", "...['a\\n']", "'a\\n']; run(); ['b'",
    'const value = "a\\nb"', '/* 前缀 */ "a\\nb"', '"a\\nb" // 尾部注释',
    '<template>\n<div>中文</div>\n</template>', '.card {\n color: red;\n}',
  ])('拒绝非完整单字符串或无预览意义的输入 %s', (source) => {
    expect(getStringContentPreview(source).available).toBe(false)
  })

  it('恶意 HTML 与链接仅作为解码文本返回', () => {
    const code = '<img src="https://example.com/test.png" onerror="alert(1)">\n<script>alert(1)</script>'
    expect(getStringContentPreview(JSON.stringify(code))).toEqual({ available: true, code })
  })

  it('64 KiB 按 UTF-8 字节限制，超限不解析', () => {
    expect(getStringContentPreview(JSON.stringify('中'.repeat(22_000) + '\n')).available).toBe(false)
  })
})

describe('修改源码差异', () => {
  it('逐字符标记三处引号，不改变两侧源码', () => {
    const result = createModifyCodePresenter()(before, after, 'src/data/articles.js')
    expect(result.before.source.map((token) => token.text).join('')).toBe(before)
    expect(result.after.source.map((token) => token.text).join('')).toBe(after)
    expect(result.before.source.filter((token) => token.change === 'removed').map((token) => token.text).join('')).toBe("''''''")
    expect(result.after.source.filter((token) => token.change === 'added').map((token) => token.text).join('')).toBe('""""""')
    expect(result.before.preview.available).toBe(false)
    expect(result.after.preview.available).toBe(true)
  })

  it('内容相同不标记差异；缺少一侧参数不推断整段新增或删除', () => {
    const present = createModifyCodePresenter()
    for (const result of [present('相同', '相同', 'a.js'), present(undefined, '已到达', 'a.js')]) {
      expect([...result.before.source, ...result.after.source].every((token) => !token.change)).toBe(true)
    }
  })

  it('空字符串代表真实删除或新增，不能视为参数缺失', () => {
    const result = createModifyCodePresenter()('删除😀', '', 'a.txt')
    expect(result.before.source).toEqual([{ text: '删除😀', change: 'removed' }])
    expect(result.after.source).toEqual([])
  })

  it('相同内容复用模型；文件语言及任一侧内容变化时重新计算', () => {
    const present = createModifyCodePresenter()
    const first = present(before, after, 'a.js')
    expect(present(before, after, 'a.js')).toBe(first)
    expect(present(before, after + ' ', 'a.js')).not.toBe(first)
    expect(present(before, after, 'a.css')).not.toBe(first)
  })

  it('超长一侧完整回退原文，差异超时后也不丢字符', () => {
    const long = '中'.repeat(22_000)
    const result = createModifyCodePresenter()(long, '短文本', 'a.js')
    expect(result.before.source).toEqual([{ text: long }])
    expect(result.after.source.map((token) => token.text).join('')).toBe('短文本')
    diffCharsMock.mockReturnValueOnce(undefined)
    const timedOut = createModifyCodePresenter()('原文', '新文', 'a.txt')
    expect(diffCharsMock).toHaveBeenLastCalledWith('原文', '新文', { timeout: 50 })
    expect(timedOut.before.source).toEqual([{ text: '原文' }])
    expect(timedOut.after.source).toEqual([{ text: '新文' }])
  })
})
