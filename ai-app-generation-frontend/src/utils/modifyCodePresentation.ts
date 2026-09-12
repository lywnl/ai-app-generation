import { parse } from 'acorn'
import { diffChars } from 'diff'
import hljs from 'highlight.js'
import MarkdownIt from 'markdown-it'

const markdown = new MarkdownIt({ html: false, linkify: false })
const encoder = new TextEncoder()
const MAX_CODE_BYTES = 64 * 1024
type Syntax = 'keyword' | 'string' | 'comment' | 'number' | 'title' | 'attribute' | 'tag' | 'literal'

export interface CodeToken {
  text: string
  syntax?: Syntax
  change?: 'added' | 'removed'
}

export type ContentPreview =
  | { available: true; code: string; language?: string }
  | { available: false; reason: string }

export interface CodeVersionPresentation {
  source: CodeToken[]
  preview: ContentPreview
  previewTokens?: CodeToken[]
}

export interface ModifyCodePresentation {
  before: CodeVersionPresentation
  after: CodeVersionPresentation
}

function withinLimit(source: string): boolean {
  return source.length <= MAX_CODE_BYTES && encoder.encode(source).length <= MAX_CODE_BYTES
}

export function getStringContentPreview(source: string): ContentPreview {
  if (!withinLimit(source)) return { available: false, reason: '片段超过 64 KiB，保留源码' }
  const literal = source.trim()
  if (!['\'', '"', '`'].includes(literal[0] ?? '')) {
    return { available: false, reason: '当前片段不是完整的单个字符串' }
  }
  try {
    let hasComment = false
    const ast = parse(`[\n${literal}\n]`, { ecmaVersion: 'latest', onComment: () => { hasComment = true } })
    const statement = ast.body[0]
    if (hasComment || ast.body.length !== 1 || statement?.type !== 'ExpressionStatement' ||
      statement.expression.type !== 'ArrayExpression' || statement.expression.elements.length !== 1) {
      return { available: false, reason: '当前片段不是完整的单个字符串' }
    }
    const node = statement.expression.elements[0]
    const decoded = node?.type === 'Literal' && typeof node.value === 'string' ? node.value
      : node?.type === 'TemplateLiteral' && node.expressions.length === 0 ? node.quasis[0]?.value.cooked : undefined
    if (decoded === undefined || decoded === null) {
      return { available: false, reason: '仅支持字符串，不支持表达式或模板插值' }
    }
    if (!decoded.includes('\n') && !decoded.includes('\r')) {
      return { available: false, reason: '字符串没有可展开的换行内容' }
    }
    const blocks = markdown.parse(decoded, {})
    if (blocks.length === 1 && blocks[0]?.type === 'fence') {
      const language = blocks[0].info.trim().split(/\s+/)[0]
      return { available: true, code: blocks[0].content, ...(language ? { language } : {}) }
    }
    return { available: true, code: decoded }
  } catch {
    return { available: false, reason: '字符串语法不完整或有错误，保留源码' }
  }
}

const EXTENSION_LANGUAGES: Record<string, string> = {
  js: 'javascript', mjs: 'javascript', cjs: 'javascript', jsx: 'javascript',
  ts: 'typescript', tsx: 'typescript', vue: 'xml', html: 'xml', css: 'css', scss: 'scss',
  json: 'json', md: 'markdown', yml: 'yaml', yaml: 'yaml',
}
const SYNTAX_CLASSES: Record<string, Syntax> = {
  'hljs-keyword': 'keyword', 'hljs-built_in': 'keyword', 'hljs-string': 'string',
  'hljs-comment': 'comment', 'hljs-number': 'number', 'hljs-title': 'title',
  'hljs-attr': 'attribute', 'hljs-attribute': 'attribute', 'hljs-tag': 'tag',
  'hljs-name': 'tag', 'hljs-literal': 'literal',
}

function plain(source: string): CodeToken[] {
  return source ? [{ text: source }] : []
}

function appendToken(tokens: CodeToken[], token: CodeToken): void {
  if (!token.text) return
  const last = tokens.at(-1)
  if (last && last.syntax === token.syntax && last.change === token.change) last.text += token.text
  else tokens.push(token)
}

function highlight(source: string, language?: string): CodeToken[] {
  if (!source || !withinLimit(source) || !language || !hljs.getLanguage(language) || typeof document === 'undefined') {
    return plain(source)
  }
  try {
    // 只解析高亮库生成的转义 HTML，再转换为 Vue 文本节点；不渲染模型提供的 HTML。
    const template = document.createElement('template')
    template.innerHTML = hljs.highlight(source, { language, ignoreIllegals: true }).value
    const tokens: CodeToken[] = []
    function visit(node: Node, inherited?: Syntax) {
      if (node.nodeType === 3) {
        appendToken(tokens, { text: node.textContent ?? '', ...(inherited ? { syntax: inherited } : {}) })
        return
      }
      const syntax = node instanceof Element
        ? Array.from(node.classList).map((name) => SYNTAX_CLASSES[name]).find(Boolean) ?? inherited : inherited
      node.childNodes.forEach((child) => visit(child, syntax))
    }
    visit(template.content)
    return tokens.map((token) => token.text).join('') === source ? tokens : plain(source)
  } catch {
    return plain(source)
  }
}

interface ChangedRange { start: number; end: number; change: 'added' | 'removed' }

function changedRanges(before: string | undefined, after: string | undefined) {
  const ranges: { before: ChangedRange[]; after: ChangedRange[] } = { before: [], after: [] }
  if (before === undefined || after === undefined || !withinLimit(before) || !withinLimit(after)) return ranges
  const changes = diffChars(before, after, { timeout: 50 })
  let oldOffset = 0
  let newOffset = 0
  for (const part of changes ?? []) {
    if (part.removed) ranges.before.push({ start: oldOffset, end: oldOffset + part.value.length, change: 'removed' })
    if (part.added) ranges.after.push({ start: newOffset, end: newOffset + part.value.length, change: 'added' })
    if (!part.added) oldOffset += part.value.length
    if (!part.removed) newOffset += part.value.length
  }
  return ranges
}

function markChanges(tokens: CodeToken[], ranges: ChangedRange[]): CodeToken[] {
  const marked: CodeToken[] = []
  let offset = 0
  let rangeIndex = 0
  for (const token of tokens) {
    const tokenEnd = offset + token.text.length
    let start = offset
    while (start < tokenEnd) {
      while ((ranges[rangeIndex]?.end ?? Infinity) <= start) rangeIndex++
      const range = ranges[rangeIndex]
      const inside = range && range.start <= start
      const end = Math.min(tokenEnd, range ? (inside ? range.end : range.start) : tokenEnd)
      appendToken(marked, { ...token, text: token.text.slice(start - offset, end - offset),
        ...(inside ? { change: range.change } : {}) })
      start = end
    }
    offset = tokenEnd
  }
  return marked
}

function version(source: string | undefined, language: string | undefined, ranges: ChangedRange[]): CodeVersionPresentation {
  const preview = getStringContentPreview(source ?? '')
  return {
    source: markChanges(highlight(source ?? '', language), ranges), preview,
    ...(preview.available ? { previewTokens: highlight(preview.code, preview.language) } : {}),
  }
}

export function createModifyCodePresenter() {
  let cached: { before?: string; after?: string; path: string; value: ModifyCodePresentation } | undefined
  return (before: string | undefined, after: string | undefined, path: string): ModifyCodePresentation => {
    if (cached && cached.before === before && cached.after === after && cached.path === path) return cached.value
    const language = EXTENSION_LANGUAGES[path.split('.').at(-1)?.toLowerCase() ?? '']
    const ranges = changedRanges(before, after)
    const value = { before: version(before, language, ranges.before), after: version(after, language, ranges.after) }
    cached = { before, after, path, value }
    return value
  }
}
