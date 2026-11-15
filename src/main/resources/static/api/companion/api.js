export const DEFAULT_ORIGIN = 'https://35.216.33.209'
const UUID = /^[a-f\d]{8}(?:-[a-f\d]{4}){3}-[a-f\d]{12}$/i
export function uuid(value) { if (!UUID.test(value)) throw new Error('계정 또는 작업 ID를 확인하세요.'); return value }
export function tripId(value) { if (!value.startsWith('trip-')) throw new Error('운행 ID를 확인하세요.'); uuid(value.slice(5)); return value }
export function displayName(value) {
  value = value.trim()
  if (!value || [...value].length > 32 || /[\u0000-\u001f\u007f-\u009f]/u.test(value)) throw new Error('이름은 제어문자 없이 1–32자로 입력하세요.')
  return value
}
export function parseInvite(fragment) {
  const value = fragment.replace(/^#/, '')
  const dot = value.indexOf('.')
  if (dot < 0) throw new Error('초대 링크를 확인하세요.')
  const id = tripId(value.slice(0, dot)), code = value.slice(dot + 1)
  if (!/^[A-Za-z0-9_-]{16,256}$/.test(code)) throw new Error('초대 코드를 확인하세요.')
  return { tripId: id, code }
}
export function claimKey(cryptoProvider = globalThis.crypto) {
  const bytes = cryptoProvider.getRandomValues(new Uint8Array(32))
  return btoa(String.fromCharCode(...bytes)).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '')
}
export class ApiError extends Error {
  constructor(status) { super(errorMessage(status)); this.status = status }
}
export function errorMessage(status) {
  return ({400:'입력 내용과 파일을 확인하세요.',401:'개인 토큰을 확인하고 다시 연결하세요.',403:'현재 계정의 역할과 동의를 확인하세요.',404:'대상을 다시 조회하세요.',409:'운행·작업 상태가 바뀌었어요. 새로고침하세요.',410:'만료·사용되었거나 폐기된 링크예요.',422:'영상 규격·오디오 또는 표시 요청을 확인하세요.',429:'요청이 많아요. 잠시 후 다시 시도하세요.',507:'서버 저장 공간이 부족해요.',503:'서버 처리 중이에요. 같은 선택으로 다시 시도하세요.'})[status] || '서버 연결을 확인하고 다시 시도하세요.'
}
export function consentTargets(trip) {
  return trip.occupants.flatMap(s => [{occupant:s.occupant, role:'PASSENGER', actor:s.occupant},
    ...(s.under14 && s.guardian ? [{occupant:s.occupant, role:'GUARDIAN', actor:s.guardian}] : [])])
}
export function receiptFor(trip, target) { return trip.receipts.find(r => r.occupant === target.occupant && r.role === target.role && r.actor === target.actor) }
export function validateEdit(card, draft) {
  const startMs = seconds(draft.start), endMs = seconds(draft.end)
  if (!draft.title.trim() || [...draft.title].length > 80 || startMs < card.startMs || endMs > card.endMs || startMs >= endMs) throw new Error('제목과 원래 구간 안의 시작·끝을 확인하세요.')
  if (draft.captions.length !== card.cues.length || draft.captions.some(t => [...t].length > 200)) throw new Error('자막 수·순서와 200자 제한을 확인하세요.')
  const body = {id:card.id}
  if (draft.title !== card.title) body.title = draft.title
  if (startMs !== card.startMs) body.startMs = startMs
  if (endMs !== card.endMs) body.endMs = endMs
  if (draft.captions.some((t,i) => t !== card.cues[i].text)) body.captions = draft.captions
  if (Object.keys(body).length === 1) throw new Error('수정할 내용을 입력하세요.')
  return body
}
function seconds(value) {
  if (!/^\d+(?:\.\d{1,3})?$/.test(value.trim())) throw new Error('초 단위로 소수점 세 자리까지 입력하세요.')
  const result = Math.round(Number(value) * 1000)
  if (!Number.isSafeInteger(result)) throw new Error('시각을 확인하세요.')
  return result
}
export class DriveApi {
  #token = ''
  #generation = 0
  constructor(origin = DEFAULT_ORIGIN, fetcher = globalThis.fetch.bind(globalThis)) {
    const url = new URL(origin)
    if (url.protocol !== 'https:' && !(url.protocol === 'http:' && ['localhost','127.0.0.1'].includes(url.hostname))) throw new Error('HTTPS 서버 주소를 입력하세요.')
    if (url.username || url.password || url.pathname !== '/' || url.search || url.hash) throw new Error('서버 원점 주소만 입력하세요.')
    this.origin = url.origin; this.fetcher = fetcher
  }
  authorize(accountId, token) { uuid(accountId); const embedded=token.trim().split('.')[0]; if(UUID.test(embedded)&&embedded.toLowerCase()!==accountId.toLowerCase())throw new Error('accountId와 개인 토큰의 계정이 다릅니다.'); if (!token.trim()) throw new Error('개인 토큰을 입력하세요.'); this.#generation++; this.accountId = accountId; this.#token = token.trim() }
  disconnect() { this.#generation++; this.accountId = undefined; this.#token = '' }
  path(value) {
    const url = new URL(value, this.origin)
    if (url.origin !== this.origin || url.username || url.password || url.hash || !url.pathname.startsWith('/api/')) throw new Error('허용되지 않은 재생 주소예요.')
    return url.pathname + url.search
  }
  async request(path, {method='GET', body, raw, headers={}, authenticated=true, signal} = {}) {
    const generation = this.#generation
    const safe = this.path(path)
    const outgoing = new Headers(headers)
    if (authenticated) {
      if (!this.#token) throw new ApiError(401)
      outgoing.set('Authorization', `Bearer ${this.#token}`)
    }
    if (body !== undefined) outgoing.set('Content-Type','application/json')
    const response = await this.fetcher(this.origin + safe, {method, headers:outgoing, body:body === undefined ? raw : JSON.stringify(body), redirect:'error', credentials:'omit', cache:'no-store', referrerPolicy:'no-referrer', signal})
    if (generation !== this.#generation) throw new DOMException('Session changed','AbortError')
    if (!response.ok) throw new ApiError(response.status)
    return response
  }
  async json(path, options) {
    const generation=this.#generation,response=await this.request(path,options)
    const body=response.status===204?undefined:await response.json()
    if(generation!==this.#generation)throw new DOMException('Session changed','AbortError')
    return body
  }
  tripPath(id) { return `/api/private-trips/${tripId(id)}` }
  trips() { return this.json('/api/private-trips') }
  trip(id) { return this.json(this.tripPath(id)) }
  timeline(id) { return this.json(this.tripPath(id)+'/timeline') }
  library() { return this.json('/api/library') }
  profiles() { return this.json('/api/export-profiles',{authenticated:false}) }
  integrations() { return this.json('/api/integrations',{authenticated:false}) }
  join(invite, attempt) { return this.json(this.tripPath(invite.tripId)+'/join',{method:'POST',authenticated:false,body:{code:invite.code,claimKey:attempt.claimKey,displayName:displayName(attempt.displayName)}}) }
  name(id, name) { return this.json(this.tripPath(id)+'/display-name',{method:'PUT',body:{displayName:displayName(name)}}) }
  mark(id, pending) { return this.json(`${this.tripPath(id)}/marks/${uuid(pending.id)}`,{method:'PUT',body:{kind:'TAP',atMs:pending.atMs}}) }
  consent(id,target,decision,version) { if(!['PASSENGER','GUARDIAN'].includes(target.role))throw new Error('역할을 확인하세요.'); return this.json(`${this.tripPath(id)}/consents/${uuid(target.occupant)}/${target.role}`,{method:'PUT',headers:version?{'X-DriveLog-Receipt-Version':version}:{},body:decision}) }
  pick(id, mark) { return this.json(`${this.tripPath(id)}/picks/${uuid(mark)}`,{method:'PUT'}) }
  invite(id, under14, guardian) { return this.json(this.tripPath(id)+'/invites',{method:'POST',body:{under14,...(under14?{guardian:uuid(guardian)}:{})}}) }
  context(id, context) { return this.json(this.tripPath(id)+'/context',{method:'PUT',body:context}) }
  job(id) { return this.json(`/api/media/jobs/${uuid(id)}`) }
  createJob(id, profile, key) { if(!['STORY_15','SHORT_30'].includes(profile))throw new Error('출력 형식을 확인하세요.'); return this.json('/api/media/jobs',{method:'POST',headers:key?{'Idempotency-Key':uuid(key)}:{},body:{tripId:tripId(id),profile}}) }
  edit(job, body, key) { return this.json(`/api/media/jobs/${uuid(job)}/edits`,{method:'POST',headers:key?{'Idempotency-Key':uuid(key)}:{},body}) }
  finalize(id, choice) { return this.json(this.tripPath(id)+'/finalize',{method:'POST',body:{jobId:uuid(choice.jobId),moment:choice.moment}}) }
  discard(id) { return this.json(this.tripPath(id),{method:'DELETE'}) }
  handoff(id, choice) { return choice ? this.json(this.cardPath(choice.jobId,choice.moment)+'/handoffs',{method:'POST'}) : this.json(`/api/library/${tripId(id)}/handoffs`,{method:'POST'}) }
  revoke(id) { return this.json(`/api/media/handoffs/${uuid(id)}`,{method:'DELETE'}) }
  cardPath(job, moment) { if(!/^[A-Za-z0-9_-]+$/.test(moment))throw new Error('카드를 확인하세요.'); return `/api/media/jobs/${uuid(job)}/clips/${moment}` }
  async clip(path, signal) {
    const generation=this.#generation
    const response = await this.request(path,{signal})
    if (!response.headers.get('content-type')?.startsWith('video/mp4')) throw new Error('영상 응답을 확인하세요.')
    const blob=await response.blob()
    if(generation!==this.#generation)throw new DOMException('Session changed','AbortError')
    return blob
  }
  async upload(id, side, file, signal, onHashProgress) {
    if (!['front','rear'].includes(side) || !file || file.size <= 0 || file.size > 8*1024**3) throw new Error('8 GiB 이하의 MP4를 선택하세요.')
    const {hashBlob} = await import('./sha256.js')
    const sha = await hashBlob(file,signal,onHashProgress)
    // The browser supplies Content-Length from the Blob; never set this forbidden header manually.
    return this.json(`${this.tripPath(id)}/media/${side}`,{method:'PUT',raw:file,headers:{'Content-Type':'video/mp4','X-Content-SHA256':sha},signal})
  }
}
