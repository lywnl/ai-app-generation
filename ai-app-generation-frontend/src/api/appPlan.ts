import request from '@/request'
import { parsePlanSnapshot } from '@/utils/planSnapshot'

export class PlanQueryError extends Error {
  constructor(readonly code: number) {
    super([40100, 40101, 40400].includes(code) ? '当前计划不可访问' : '计划暂时无法读取，请重试')
  }
  get accessLost() { return [40100, 40101, 40400].includes(this.code) }
}
export async function getAppPlan(appId: string, signal: AbortSignal) {
  if (!/^[1-9]\d*$/.test(appId)) throw new Error('应用标识无效')
  const response = await request.get(`/app/${appId}/plan`, { signal, timeout: 10_000, skipLoginRedirect: true })
  if (response.data?.code !== 0) throw new PlanQueryError(response.data?.code ?? 50000)
  return parsePlanSnapshot(response.data.data)
}
