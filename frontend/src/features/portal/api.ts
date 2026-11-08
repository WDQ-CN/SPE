import axios, {
  type AxiosError,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { ref } from 'vue'

import type { PageResult, Result } from '@/shared/types/result'

/**
 * 供应商门户 API：独立令牌、独立 401 处理，绝不复用内部员工的 token/拦截器。
 * 401 直接清门户会话并回门户登录页，不触碰内部 `spe.token`。
 */

const TOKEN_KEY = 'spe.portal.token'
const REFRESH_KEY = 'spe.portal.refresh'
const USER_KEY = 'spe.portal.user'

export interface PortalUser {
  id: number
  supplierId: number
  supplierCode: string
  supplierName: string
  loginName: string
  status: string
}

export interface PortalLoginResponse {
  accessToken: string
  refreshToken: string
  expiresIn: number
  user: PortalUser
}

export const portalUser = ref<PortalUser | null>(readUser())

function readUser(): PortalUser | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) {
    return null
  }
  try {
    return JSON.parse(raw) as PortalUser
  } catch {
    return null
  }
}

export function portalToken(): string {
  return localStorage.getItem(TOKEN_KEY) ?? ''
}

export function portalAuthenticated(): boolean {
  return Boolean(portalToken())
}

function saveSession(session: PortalLoginResponse): void {
  localStorage.setItem(TOKEN_KEY, session.accessToken)
  localStorage.setItem(REFRESH_KEY, session.refreshToken)
  localStorage.setItem(USER_KEY, JSON.stringify(session.user))
  portalUser.value = session.user
}

export function clearPortalSession(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(REFRESH_KEY)
  localStorage.removeItem(USER_KEY)
  portalUser.value = null
}

export class PortalError extends Error {
  constructor(
    readonly code: number,
    message: string,
  ) {
    super(message)
    this.name = 'PortalError'
  }
}

const instance = axios.create({ baseURL: '/api', timeout: 10_000 })

interface RetryableConfig extends InternalAxiosRequestConfig {
  _retry?: boolean
}

let refreshPromise: Promise<void> | null = null

function isAuthPath(url: string | undefined): boolean {
  return Boolean(
    url &&
      (url.includes('/supplier-portal/auth/login') ||
        url.includes('/supplier-portal/auth/refresh') ||
        url.includes('/supplier-portal/auth/logout')),
  )
}

/** 单飞刷新：并发 401 共享同一次 refresh，避免轮换令牌互相踢下线。 */
async function refreshAccessToken(): Promise<void> {
  const refreshToken = localStorage.getItem(REFRESH_KEY)
  if (!refreshToken) {
    throw new PortalError(1001, '登录状态已失效，请重新登录')
  }
  if (!refreshPromise) {
    refreshPromise = axios
      .post<Result<PortalLoginResponse>>('/api/supplier-portal/auth/refresh', { refreshToken })
      .then((response) => {
        const result = response.data
        if (result.code !== 0) {
          throw new PortalError(result.code, result.message || '登录状态已失效')
        }
        saveSession(result.data)
      })
      .finally(() => {
        refreshPromise = null
      })
  }
  return refreshPromise
}

instance.interceptors.request.use((config) => {
  const token = portalToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

function failWith(code: number, message: string): never {
  if (code >= 1000 && code < 2000) {
    clearPortalSession()
    if (window.location.pathname !== '/portal/login') {
      window.location.assign('/portal/login')
    }
  }
  throw new PortalError(code, message)
}

instance.interceptors.response.use(
  (response: AxiosResponse<Result<unknown>>) => {
    const result = response.data
    if (result.code !== 0) {
      failWith(result.code, result.message || '请求失败')
    }
    return response
  },
  async (error: AxiosError<Result<unknown>>) => {
    const body = error.response?.data
    const config = error.config as RetryableConfig | undefined
    if (
      error.response?.status === 401 &&
      config &&
      !config._retry &&
      !isAuthPath(config.url)
    ) {
      config._retry = true
      try {
        await refreshAccessToken()
        return instance(config)
      } catch {
        clearPortalSession()
        if (window.location.pathname !== '/portal/login') {
          window.location.assign('/portal/login')
        }
        throw new PortalError(body?.code ?? 1001, body?.message || '登录状态已失效，请重新登录')
      }
    }
    if (body && typeof body.code === 'number') {
      failWith(body.code, body.message || '请求失败')
    }
    if (error.response?.status === 401) {
      clearPortalSession()
      if (window.location.pathname !== '/portal/login') {
        window.location.assign('/portal/login')
      }
    }
    const message =
      error.response?.status === undefined
        ? '无法连接后端服务'
        : `请求失败（${error.response.status}）`
    throw new PortalError(-1, message)
  },
)

const http = {
  get<T>(url: string, params?: object): Promise<T> {
    return instance.get<Result<T>>(url, { params }).then((response) => response.data.data)
  },
  post<T>(url: string, data?: unknown): Promise<T> {
    return instance.post<Result<T>>(url, data).then((response) => response.data.data)
  },
}

// ---- 认证 ----

export async function portalLogin(loginName: string, password: string): Promise<PortalUser> {
  const session = await http.post<PortalLoginResponse>('/supplier-portal/auth/login', {
    loginName,
    password,
  })
  saveSession(session)
  return session.user
}

export async function portalLogout(): Promise<void> {
  const refreshToken = localStorage.getItem(REFRESH_KEY) ?? ''
  try {
    if (refreshToken) {
      await http.post<void>('/supplier-portal/auth/logout', { refreshToken })
    }
  } finally {
    clearPortalSession()
  }
}

export function acceptInvitation(
  token: string,
  loginName: string,
  password: string,
): Promise<{ portalUserId: number; supplierId: number }> {
  return http.post<{ portalUserId: number; supplierId: number }>(
    `/supplier-portal/invitations/${encodeURIComponent(token)}/accept`,
    { loginName, password },
  )
}

// ---- 门户竞价 ----

export interface PortalAuctionSummary {
  id: number
  auctionNo: string
  title: string
  status: string
  sealed: boolean
  currency: string
  startAt: string
  endAt: string
  myBidCount: number
}

export interface PortalAuctionLot {
  id: number
  lineNo: number
  itemCode: string
  itemName: string
  qty: number
  uom: string
  startPrice: number
  minDecrement: number
  currentLowestAmount: number | null
  myLastAmount: number | null
  myRank: number | null
}

export interface PortalAuctionDetail {
  id: number
  auctionNo: string
  title: string
  status: string
  sealed: boolean
  currency: string
  startAt: string
  endAt: string
  serverTime: string
  antiSnipingSeconds: number
  maxExtensions: number
  extensionCount: number
  lots: PortalAuctionLot[]
}

export interface PortalAuctionState {
  status: string
  serverTime: string
  endAt: string
  remainingMs: number
  sealed: boolean
  myRank: number | null
  participantCount: number | null
  lowestAmount: number | null
  nextMinAmount: number | null
  myLastAmount: number | null
  extensionCount: number
  extended: boolean
}

export interface PortalBid {
  id: number
  lotId: number
  amount: number
  status: string
  submittedAt: string
}

export interface PortalBidResult {
  bidId: number
  lotId: number
  status: string
  amount: number
  rank: number | null
  participantCount: number | null
  lowestAmount: number | null
  nextMinAmount: number | null
  serverTime: string
  endAt: string
  sealed: boolean
  extended: boolean
  extensionCount: number
}

export interface PortalAuctionResult {
  status: string
  sealed: boolean
  serverTime: string
  results: {
    lotId: number
    myRank: number | null
    myBestAmount: number | null
    winningAmount: number | null
    winner: boolean
  }[]
}

export function listPortalAuctions(
  page: number,
  size: number,
  status?: string,
): Promise<PageResult<PortalAuctionSummary>> {
  return http.get<PageResult<PortalAuctionSummary>>('/supplier-portal/auctions', {
    page,
    size,
    status,
  })
}

export function getPortalAuction(id: number): Promise<PortalAuctionDetail> {
  return http.get<PortalAuctionDetail>(`/supplier-portal/auctions/${id}`)
}

export function getPortalAuctionState(id: number, lotId?: number): Promise<PortalAuctionState> {
  return http.get<PortalAuctionState>(`/supplier-portal/auctions/${id}/state`, { lotId })
}

export function getPortalAuctionBids(id: number): Promise<PortalBid[]> {
  return http.get<PortalBid[]>(`/supplier-portal/auctions/${id}/my-bids`)
}

export function getPortalAuctionResult(id: number): Promise<PortalAuctionResult> {
  return http.get<PortalAuctionResult>(`/supplier-portal/auctions/${id}/result`)
}

export function submitPortalBid(
  auctionId: number,
  lotId: number,
  amount: number,
  idempotencyKey: string,
): Promise<PortalBidResult> {
  return http.post<PortalBidResult>(
    `/supplier-portal/auctions/${auctionId}/lots/${lotId}/bids`,
    { amount, idempotencyKey },
  )
}

// ---- 门户 RFQ ----

export interface PortalRfqSummary {
  rfqId: number
  rfqNo: string
  title: string
  currency: string
  deadlineAt: string
  status: string
  invitationStatus: string
  myQuoteStatus: string | null
}

export interface PortalRfqLine {
  id: number
  lineNo: number
  itemId: number
  itemCode: string
  itemName: string
  qty: number
  uom: string
  needDate: string | null
  targetPrice: number | null
  spec: string | null
}

export interface PortalQuoteLine {
  id: number
  rfqLineId: number
  unitPrice: number
  taxRate: number
  taxAmount: number
  leadTimeDays: number | null
  moq: number | null
  qtyAvailable: number | null
  incoterm: string | null
  remark: string | null
}

export interface PortalQuote {
  id: number
  quoteNo: string
  rfqId: number
  supplierId: number
  supplierCode: string
  supplierName: string
  currency: string
  validUntil: string | null
  status: string
  submittedAt: string
  version: number
  lines: PortalQuoteLine[]
}

export interface PortalRfqDetail {
  rfqId: number
  rfqNo: string
  title: string
  currency: string
  deadlineAt: string
  sealed: boolean
  status: string
  invitationStatus: string
  lines: PortalRfqLine[]
  myQuote: PortalQuote | null
}

export interface PortalQuotePayload {
  validUntil: string | null
  lines: {
    rfqLineId: number
    unitPrice: number
    taxRate: number
    leadTimeDays: number | null
    moq: number | null
    qtyAvailable: number | null
    incoterm: string | null
    remark: string | null
  }[]
}

export function listPortalRfqs(
  page: number,
  size: number,
  status?: string,
): Promise<PageResult<PortalRfqSummary>> {
  return http.get<PageResult<PortalRfqSummary>>('/supplier-portal/rfqs', { page, size, status })
}

export function getPortalRfq(id: number): Promise<PortalRfqDetail> {
  return http.get<PortalRfqDetail>(`/supplier-portal/rfqs/${id}`)
}

export function submitPortalQuote(id: number, payload: PortalQuotePayload): Promise<PortalQuote> {
  return http.post<PortalQuote>(`/supplier-portal/rfqs/${id}/quotes`, payload)
}

export function withdrawPortalQuote(id: number): Promise<PortalQuote> {
  return http.post<PortalQuote>(`/supplier-portal/rfqs/${id}/withdraw`)
}
