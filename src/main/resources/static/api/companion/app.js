import {DriveApi, DEFAULT_ORIGIN, parseInvite, claimKey, displayName, consentTargets, receiptFor, validateEdit, ApiError} from './api.js'
import {sealSession,openSession} from './vault.js'
import {guestKey,saveGuest,loadGuest,forgetGuest} from './guest-session.js'
const VAULT_KEY='drivelog:encrypted-session'
const $ = selector => document.querySelector(selector)
const root = $('#content'), notice = $('#notice')
document.addEventListener('submit',event=>event.preventDefault())
const state = {api:null,page:'trips',trips:[],library:[],trip:null,timeline:null,jobs:new Map(),busy:false,pendingMark:null,playing:null,ticket:null,controller:null,epoch:0,profiles:[],files:new Map(),invitation:null}
const phaseLabel = phase => ({DRIVING:'주행 중',PENDING:'동의 대기',UPLOAD:'원본 대기',READY:'추천 준비',SAVING:'보관 중',SAVED:'보관 완료',DISCARDING:'삭제 중',DISCARDED:'삭제 완료'})[phase] || '상태 확인 중'
function el(tag, text, className) { const node=document.createElement(tag); if(text!==undefined)node.textContent=text; if(className)node.className=className; return node }
function option(text,value){const node=el('option',text);node.value=value;return node}
function add(parent,...nodes){parent.append(...nodes);return parent}
function button(text, action, primary=false) {const node=el('button',text,primary?'primary':'');node.type='button';node.onclick=action;return node}
function field(form,label,value='',type='text') {const id=crypto.randomUUID(),input=el('input');input.type=type;input.id=id;input.value=value;input.autocomplete=type==='password'?'off':'on';add(form,Object.assign(el('label',label),{htmlFor:id}),input);return input}
function check(form,label,checked=false){const row=el('label',undefined,'check'),input=el('input');input.type='checkbox';input.checked=checked;add(row,input,el('span',label));form.append(row);return input}
function section(title){const node=el('section');node.append(el('h2',title));root.append(node);return node}
function say(message){notice.textContent=message;notice.hidden=!message}
function pendingKey(kind,id){return `drivelog:${state.api.origin}:${state.api.accountId}:${id}:${kind}`}
function stored(key){try{return JSON.parse(sessionStorage.getItem(key))}catch{return null}}
function persist(key,value){sessionStorage.setItem(key,JSON.stringify(value))}
function busy(value){state.busy=value;root.setAttribute('aria-busy',String(value));root.querySelectorAll('button,input,select,textarea').forEach(n=>n.disabled=value);$('#logout').disabled=value;$('#navigation').querySelectorAll('button').forEach(n=>n.disabled=value)}
async function run(action,{render=true}={}){
  if(state.busy)return
  const epoch=state.epoch,beforeApi=state.api
  let completed=false
  busy(true);say('처리 중이에요.')
  try{await action();completed=true;if(epoch===state.epoch && notice.textContent==='처리 중이에요.')say('완료됐어요.')}
  catch(error){if(epoch===state.epoch)say(error.name==='AbortError'?'전송을 취소했어요.':error instanceof TypeError?'서버 주소와 네트워크 연결을 확인하세요.':error instanceof ApiError?error.message:error.message || '연결을 확인하세요.')}
  finally{if(epoch===state.epoch){busy(false);$('#cancel').hidden=true;state.controller=null;if(render&&(completed||beforeApi!==state.api))paint()}}
}
let playerGeneration=0
function closePlayer(){playerGeneration++;const video=$('#player video');video.pause();video.removeAttribute('src');video.load();if(state.playing)URL.revokeObjectURL(state.playing.url);state.playing=null;$('#player').close()}
async function play(path,identity){
  closePlayer()
  const api=state.api, generation=playerGeneration
  const blob=await api.clip(path)
  if(!await mayPlay(api,identity))throw new Error('공유 또는 보관 상태가 바뀌었어요.')
  if(api!==state.api || generation!==playerGeneration || document.hidden)return
  const url=URL.createObjectURL(blob)
  state.playing={...identity,url}
  $('#player video').src=url
  $('#player').showModal()
  await $('#player video').play()
}
async function mayPlay(api, item){
  if(item.kind==='mark'){
    const timeline=await api.timeline(item.tripId)
    return timeline.entries.some(e=>e.mark.id===item.markId&&e.state==='CLIP_READY'&&e.clipUrl===item.clipUrl)
  }
  if(item.kind==='library'){
    return (await api.library()).some(i=>i.tripId===item.tripId&&i.markId===item.markId&&i.clipUrl===item.clipUrl)
  }
  return !['DISCARDING','DISCARDED'].includes((await api.trip(item.tripId)).phase)
}
function closeTicket(){state.ticket=null;$('#handoff img').removeAttribute('src');$('#handoff').close()}
function showTicket(ticket){
  if(!/^data:image\/png;base64,[A-Za-z0-9+/=\r\n]+$/.test(ticket.qrImage))throw new Error('QR 이미지를 확인하세요.')
  state.ticket=ticket;$('#handoff img').src=ticket.qrImage;$('#handoff-time').textContent='만료 '+new Date(ticket.expiresAt).toLocaleTimeString('ko-KR');$('#handoff').showModal()
}
$('#close-player').onclick=closePlayer;$('#player').addEventListener('cancel',event=>{event.preventDefault();closePlayer()})
$('#close-handoff').onclick=closeTicket;$('#handoff').addEventListener('cancel',event=>{event.preventDefault();closeTicket()})
$('#revoke').onclick=()=>run(async()=>{await state.api.revoke(state.ticket.id);closeTicket()},{render:false})
$('#cancel').onclick=()=>state.controller?.abort()
$('#logout').onclick=()=>{if(!confirm('연결을 해제할까요? 다시 연결하려면 개인 토큰이 필요해요.'))return;closePlayer();closeTicket();state.api?.disconnect();forgetGuest();state.api=null;state.page='trips';state.trips=[];state.library=[];state.profiles=[];state.trip=null;state.timeline=null;state.pendingMark=null;state.files.clear();state.jobs.clear();state.invitation=null;state.epoch++;say('연결을 해제했어요. 저장한 계정을 삭제하려면 기기 계정 삭제를 선택하세요.');paint()}
$('#navigation').onclick=event=>{const page=event.target.dataset.page;if(!page||state.busy)return;run(async()=>{state.page=page;state.trip=null;if(page==='library')state.library=await state.api.library();else state.trips=await state.api.trips()})}
document.addEventListener('visibilitychange',()=>{if(document.hidden){closePlayer();closeTicket();state.invitation=null;root.querySelectorAll('.invite-link').forEach(n=>n.remove())}})
async function selectTrip(id){const trip=await state.api.trip(id);state.trip=trip;state.page='trip';state.pendingMark=stored(pendingKey('mark',id));state.timeline=trip.occupants.some(s=>s.occupant===state.api.accountId)&&!['DISCARDING','DISCARDED'].includes(trip.phase)?await state.api.timeline(id):null}
function paint(){
  root.replaceChildren();const connected=!!state.api?.accountId;$('#navigation').hidden=!connected;$('#logout').hidden=!connected
  $('#navigation').querySelectorAll('button').forEach(n=>n.setAttribute('aria-current',n.dataset.page===state.page?'page':'false'))
  if(!connected){connectForm();return}
  if(state.page==='library'){libraryPage();return}
  if(state.page==='trip'&&state.trip){tripPage();return}
  add(root,el('p','YOUR DRIVES','eyebrow'),el('h1','함께 달린 드라이브'))
  root.append(button('운행 새로고침',()=>run(async()=>{state.trips=await state.api.trips()})))
  if(!state.trips.length)root.append(el('p','배정된 운행이 없어요. 운행 소유자나 운영 담당자에게 계정 배정을 요청하세요.','blank'))
  for(const [index,trip] of state.trips.entries()){const row=section(`드라이브 ${index+1}`);add(row,el('span',phaseLabel(trip.phase),'badge'),button('운행 열기',()=>run(()=>selectTrip(trip.tripId))))}
}
function connectForm(){
  add(root,el('p','DRIVELOG COMPANION','eyebrow'),el('h1','함께 남기는 순간'),el('p','초대받은 드라이브에 참여하거나, 발급받은 개인 계정으로 내 기록을 열어보세요.','muted'))
  const panel=section('내 계정 연결'),form=el('form'),origin=field(form,'HTTPS 서버 주소',(location.pathname.startsWith('/api/companion/') || ['localhost','127.0.0.1'].includes(location.hostname))?location.origin:DEFAULT_ORIGIN,'url'),account=field(form,'개인 accountId'),token=field(form,'개인 accessToken','','password')
  account.autocomplete='off';add(form,el('small','팀 서버 토큰은 입력하지 마세요. 개인 토큰은 이 화면의 메모리에만 유지돼요.'))
  const remember=check(form,'이 기기에 암호화해 계정 보관 (선택)'),password=field(form,'기기 계정 잠금 암호 (8자 이상)','','password')
  password.autocomplete='new-password';password.hidden=true;form.querySelector(`label[for="${password.id}"]`).hidden=true
  remember.onchange=()=>{password.hidden=!remember.checked;form.querySelector(`label[for="${password.id}"]`).hidden=!remember.checked}
  const submit=button('내 드라이브 연결',()=>{} ,true);submit.type='submit';form.append(submit);panel.append(form)
  form.onsubmit=event=>{event.preventDefault();run(async()=>{const api=new DriveApi(origin.value.trim());api.authorize(account.value.trim(),token.value);const [trips,profiles]=await Promise.all([api.trips(),api.profiles()]);state.api=api;state.trips=trips;state.profiles=profiles;if(remember.checked)localStorage.setItem(VAULT_KEY,JSON.stringify(await sealSession({origin:api.origin,accountId:api.accountId,accessToken:token.value.trim()},password.value)));token.value='';password.value='';say('개인 계정으로 연결됐어요.')})}
  let savedVault=null
  try{savedVault=localStorage.getItem(VAULT_KEY)}catch{}
  if(savedVault){
    const saved=section('이 기기에 보관한 계정'),unlock=el('form'),pin=field(unlock,'계정 잠금 암호','','password')
    unlock.append(button('암호로 다시 연결',()=>run(async()=>{const session=await openSession(JSON.parse(savedVault),pin.value),api=new DriveApi(session.origin);api.authorize(session.accountId,session.accessToken);const [trips,profiles]=await Promise.all([api.trips(),api.profiles()]);state.api=api;state.trips=trips;state.profiles=profiles;pin.value='';say('보관한 개인 계정으로 연결됐어요.')}),true))
    unlock.append(button('기기 계정 삭제',()=>{if(confirm('이 기기에 암호화해 보관한 계정을 삭제할까요? 다시 연결하려면 개인 토큰이 필요해요.')){localStorage.removeItem(VAULT_KEY);paint()}}));saved.append(unlock)
  }
  if(location.hash){
    let invitation
    try{invitation=parseInvite(location.hash)}catch(error){root.append(el('p',error.message));return}
    const panel=section('초대받은 드라이브 참여'),join=el('form'),name=field(join,'함께 탈 때 사용할 이름')
    const rememberJoin=check(join,'참여한 계정을 이 기기에 암호화해 보관'),joinPassword=field(join,'참여 계정 잠금 암호 (보관할 때 8자 이상)','','password')
    add(join,el('p','새로고침해도 이 탭에서 참여를 이어갑니다. 탭을 닫은 뒤에도 보관함을 열려면 암호화 계정 보관을 선택하세요.'))
    const submit=button('초대 수락하고 참여',()=>{},true);submit.type='submit';join.append(submit);panel.append(join)
    join.onsubmit=event=>{event.preventDefault();run(async()=>{
      const api=new DriveApi(origin.value.trim()),fingerprint=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(invitation.code)),key=`drivelog:claim:${api.origin}:${invitation.tripId}:${[...new Uint8Array(fingerprint)].map(n=>n.toString(16).padStart(2,'0')).join('')}`
      let attempt=stored(key)
      if(!attempt){attempt={claimKey:claimKey(),displayName:displayName(name.value)};persist(key,attempt)}
      if(rememberJoin.checked&&joinPassword.value.length<8)throw new Error('계정 잠금 암호는 8자 이상으로 입력하세요.');const keyForTab=await guestKey()
      const result=await api.join(invitation,attempt)
      const session={origin:api.origin,accountId:result.accountId,accessToken:result.accessToken,tripId:result.tripId}
      await saveGuest(session,keyForTab)
      api.authorize(result.accountId,result.accessToken);if(rememberJoin.checked)localStorage.setItem(VAULT_KEY,JSON.stringify(await sealSession({origin:api.origin,accountId:result.accountId,accessToken:result.accessToken},joinPassword.value)));state.api=api;joinPassword.value='';sessionStorage.removeItem(key);history.replaceState(null,'',location.pathname+location.search);await selectTrip(result.tripId);say('드라이브에 참여했어요.')
    })}
  }
}
function tripPage(){
  const trip=state.trip,owner=trip.owner===state.api.accountId,occupant=trip.occupants.some(s=>s.occupant===state.api.accountId)
  add(root,el('span',phaseLabel(trip.phase),'badge'),el('h1','이번 드라이브'),button('운행 상태 새로고침',()=>run(()=>selectTrip(trip.tripId))))
  if(trip.phase==='DRIVING'&&occupant){
    const panel=section('기억하고 싶은 순간'),mark=button(state.pendingMark?'같은 순간 다시 보내기':'지금 순간 남기기',()=>run(async()=>{
      if(!state.timeline)throw new Error('운행 시작 시각을 확인하세요.')
      if(!state.pendingMark){const atMs=Date.now()-Date.parse(state.timeline.startedAt);if(!Number.isFinite(atMs)||atMs<0||atMs>=86400000)throw new Error('기기 시각과 운행 시작 시각을 확인하세요.');state.pendingMark={id:crypto.randomUUID(),atMs};persist(pendingKey('mark',trip.tripId),state.pendingMark)}
      try{await state.api.mark(trip.tripId,state.pendingMark)}catch(error){if([400,403,404,409,410,422].includes(error.status)){state.pendingMark=null;sessionStorage.removeItem(pendingKey('mark',trip.tripId))}throw error}
      state.pendingMark=null;sessionStorage.removeItem(pendingKey('mark',trip.tripId));await selectTrip(trip.tripId);say('이 순간을 표시했어요.')
    }),true);mark.classList.add('mark');panel.append(mark)
    const form=el('form'),name=field(form,'표시할 이름');form.append(button('이름 저장',()=>run(async()=>{await state.api.name(trip.tripId,name.value);say('이름을 저장했어요.')})));panel.append(form)
  }
  if(owner&&trip.phase==='DRIVING')invitePanel(trip)
  if(!['DRIVING','DISCARDING','DISCARDED'].includes(trip.phase))consentPanel(trip)
  if(owner&&trip.phase==='UPLOAD')uploadPanel(trip)
  if(owner&&!['DISCARDING','DISCARDED','SAVED'].includes(trip.phase))jobPanel(trip)
  if(state.timeline)timelinePanel(trip)
  if(owner&&!['SAVING','DISCARDING','DISCARDED'].includes(trip.phase)){const panel=section('운행 관리');panel.append(button('이 운행 전체 삭제',()=>{if(confirm(`${trip.tripId}\n모든 탑승자의 원본·중간물·보관본이 폐기됩니다. 삭제할까요?`))run(async()=>{await state.api.discard(trip.tripId);closePlayer();closeTicket();await selectTrip(trip.tripId)})}));}
}
function invitePanel(trip){
  const panel=section('동승자 초대'),form=el('form'),child=check(form,'만 14세 미만 동승자'),guardian=el('select')
  guardian.setAttribute('aria-label','함께 탑승한 성인 보호자');guardian.append(option('성인 보호자 선택',''));trip.occupants.filter(s=>!s.under14).forEach(s=>guardian.append(option(s.occupant.slice(-8),s.occupant)));guardian.hidden=true;child.onchange=()=>guardian.hidden=!child.checked;form.append(guardian)
  form.append(button('한 사람용 초대 링크 발급',()=>run(async()=>{const invite=await state.api.invite(trip.tripId,child.checked,guardian.value);const fragment=new URL(invite.url).hash;parseInvite(fragment);state.invitation={tripId:trip.tripId,url:new URL(`index.html${fragment}`,location.href).href,expiresAt:invite.expiresAt}}),false));panel.append(form)
  const invite=state.invitation
  if(invite?.tripId===trip.tripId&&Date.parse(invite.expiresAt)>Date.now())add(panel,el('p',invite.url,'invite-link'),el('small','30分 동안 한 사람만 참여할 수 있어요. 주차 후 마감돼요. 링크를 선택해 동승자에게 전달하세요.'.replace('30分','30분')))

}
function consentPanel(trip){
  const panel=section('탑승자 동의')
  for(const target of consentTargets(trip)){
    const receipt=receiptFor(trip,target),label=`${target.occupant.slice(-8)} · ${target.role==='GUARDIAN'?'보호자':'본인'} 응답`
    panel.append(el('p',label+' · '+(!receipt?'미응답':receipt.extraction&&receipt.geminiTranscript?'분석 동의':'거절·철회')))
    if(target.actor!==state.api.accountId)continue
    const form=el('form'),extraction=check(form,'영상 추출에 동의',receipt?.extraction),transcript=check(form,'Gemini 음성 전사에 동의',receipt?.geminiTranscript),context=check(form,'위치 활용 (선택)',receipt?.useTripContext),sharing=check(form,'다른 탑승자의 표시 클립 재생 (선택)',receipt?.shareMarkedClips)
    form.append(button('이 내용으로 동의 제출',()=>{
      const decision={extraction:extraction.checked,geminiTranscript:transcript.checked,useTripContext:context.checked,shareMarkedClips:sharing.checked}
      const message=JSON.stringify({영상추출:decision.extraction,음성전사:decision.geminiTranscript,위치활용:decision.useTripContext,동승자재생:decision.shareMarkedClips})
      if(!confirm(`${label}\n${message}\n분석 거절·철회나 이미 사용한 위치 동의 철회 시 영상 폐기가 시작될 수 있어요. 공유만 철회하면 동승자 재생이 중단돼요. 제출할까요?`))return
      run(async()=>{const latest=await state.api.trip(trip.tripId)
        if(JSON.stringify(receiptFor(latest,target))!==JSON.stringify(receipt)){
          await selectTrip(trip.tripId);say('동의가 변경됐어요. 최신 내용을 확인하고 다시 선택하세요.');return
        }
        await state.api.consent(trip.tripId,target,decision,receipt?.at||'none');closePlayer();await selectTrip(trip.tripId)})
    }));panel.append(form)
  }
}
function timelinePanel(trip){
  const panel=section('함께 남긴 순간'),list=el('ol',undefined,'timeline');panel.dataset.timeline='true';panel.append(list)
  if(!state.timeline.entries.length)panel.append(el('p','아직 표시한 순간이 없어요.','muted'))
  for(const entry of state.timeline.entries){const row=el('li'),at=new Date(Date.parse(state.timeline.startedAt)+entry.mark.atMs).toLocaleTimeString('ko-KR',{hour:'2-digit',minute:'2-digit'});add(row,el('time',at+' · '+(entry.authorName||entry.mark.author?.slice(-8)||'동승자')),el('p',entry.title||'기억하고 싶은 순간','clip-title'))
    if(entry.place)row.append(el('p',entry.place,'muted'))
    row.append(el('small',({MARKED:'클립 생성 대기',NO_CLIP:'이 시점에 재생할 클립이 없어요.',SHARING_DISABLED:'공유 동의가 필요해요.',CLIP_READY:'재생 가능'})[entry.state]||'처리 중'))
    if(entry.state==='CLIP_READY'&&entry.clipUrl){row.append(button('재생',()=>run(()=>play(entry.clipUrl,{kind:'mark',tripId:trip.tripId,markId:entry.mark.id,clipUrl:entry.clipUrl}),{render:false})));if(!['SAVED','DISCARDED','DISCARDING'].includes(trip.phase))row.append(button('내 앨범에 선택',()=>run(async()=>{await state.api.pick(trip.tripId,entry.mark.id);say('내 앨범에 보관할 표시로 선택했어요.')}),false))}
    list.append(row)
  }
  return panel
}

function uploadPanel(trip){
  const panel=section('원본 영상 준비');panel.append(el('p','전방·후방 MP4를 각각 보내주세요. 선택한 파일의 길이와 SHA-256을 확인한 뒤 원본을 전송해요. 파일당 최대 8 GiB입니다.'))
  const allContext=consentTargets(trip).every(t=>{const r=receiptFor(trip,t);return r?.extraction&&r.geminiTranscript&&r.useTripContext})
  if(allContext){const form=el('form'),departure=field(form,'출발지'),destination=field(form,'도착지'),events=el('textarea');events.placeholder='예: 42, LANDMARK, 해안길';events.setAttribute('aria-label','장소 이벤트');add(form,el('label','장소 이벤트 (초, LANDMARK·NEAR_ARRIVAL·ARRIVAL, 이름 / 한 줄에 하나)'),events,button('위치 정보 등록',()=>run(async()=>{const values=events.value.trim()?events.value.trim().split('\n').map(line=>{const [seconds,kind,...label]=line.split(',').map(s=>s.trim());const atMs=Math.round(Number(seconds)*1000);if(!Number.isFinite(atMs)||atMs<0||atMs>=86400000||!['LANDMARK','NEAR_ARRIVAL','ARRIVAL'].includes(kind)||!label.join(',').trim())throw new Error('이벤트 시각·종류·이름을 확인하세요.');return {atMs,kind,label:label.join(',')}}):[];await state.api.context(trip.tripId,{departure:departure.value.trim(),destination:destination.value.trim(),events:values});say('위치 정보를 등록했어요.')})));panel.append(form);panel.append(el('small','두 번째 영상 업로드 전에 위치 등록을 완료하세요.'))}
  for(const [side,label]of [['front','전방'],['rear','후방']]){
    const key=trip.tripId+':'+side,selected=state.files.get(key),form=el('form'),file=field(form,`${label} MP4`,'','file');file.accept='video/mp4'
    if(selected)form.append(el('p',`${selected.file.name} · ${selected.accepted?'전송 수락됨':'선택됨'}`,'muted'))
    file.onchange=()=>{if(file.files[0])state.files.set(key,{file:file.files[0],accepted:false})}
    form.append(button(`${label} 원본 전송`,()=>run(async()=>{
      const selection=state.files.get(key);if(!selection)throw new Error('먼저 원본 MP4를 선택하세요.')
      if(selection.accepted){say('이미 수락된 파일이에요. 교체하려면 다른 파일을 선택하세요.');return}
      state.controller=new AbortController();$('#cancel').hidden=false
      const result=await state.api.upload(trip.tripId,side,selection.file,state.controller.signal,(read,total)=>say(`${label} 파일 확인 ${Math.floor(read/total*100)}% · 확인 후 전송해요.`))
      selection.accepted=true;state.trip=result;await selectTrip(trip.tripId);say(`${label} 원본을 수락했어요. 다른 파일은 전송 결과를 확인해 필요한 쪽만 보내세요.`)
    })));panel.append(form)
  }

}
function jobRecord(trip){let record=state.jobs.get(trip.tripId);if(!record){record={jobId:stored(pendingKey('job',trip.tripId)),status:null,profile:stored(pendingKey('profile',trip.tripId))||'STORY_15',pendingSave:stored(pendingKey('save',trip.tripId))};state.jobs.set(trip.tripId,record)}return record}
function operationKey(trip){
  const slot=pendingKey('request',trip.tripId)
  const key=stored(slot)||crypto.randomUUID()
  persist(slot,key)
  return key
}
async function followJob(trip,jobId){const record=jobRecord(trip);record.jobId=jobId;record.status=null;persist(pendingKey('job',trip.tripId),jobId);sessionStorage.removeItem(pendingKey('request',trip.tripId));await pollJob(trip)}
async function pollJob(trip){const record=jobRecord(trip);record.status=await state.api.job(record.jobId)}
async function finalize(trip,choice){const record=jobRecord(trip);persist(pendingKey('save',trip.tripId),choice);record.pendingSave=choice;const response=await state.api.finalize(trip.tripId,choice);state.trip=response;if(response.phase==='SAVED'){record.pendingSave=null;sessionStorage.removeItem(pendingKey('save',trip.tripId));state.page='library';state.library=await state.api.library()}else say('영상을 보관 중이에요. 같은 선택을 유지하며 상태를 확인하세요.')}
function jobPanel(trip){
  const record=jobRecord(trip);if(trip.phase!=='READY'&&!record.jobId)return
  const panel=section('추천 영상')
  if(!record.jobId){const select=el('select');select.setAttribute('aria-label','영상 길이');const profiles=state.profiles.filter(p=>p.renderingAvailable&&['STORY_15','SHORT_30'].includes(p.id));profiles.forEach(p=>select.append(option(`${p.targetDurationMs/1000}초 영상`,p.id)));if(!profiles.length){panel.append(el('p','현재 사용할 수 있는 출력 옵션을 확인하세요.'));panel.append(button('출력 옵션 다시 확인',()=>run(async()=>{state.profiles=await state.api.profiles()})));return}add(panel,select,button('추천 만들기',()=>run(async()=>{record.profile=select.value;persist(pendingKey('profile',trip.tripId),select.value);const response=await state.api.createJob(trip.tripId,select.value,operationKey(trip));await followJob(trip,response.jobId)}),true));return}
  if(record.pendingSave)panel.append(button('저장하던 같은 영상 다시 보관',()=>run(()=>finalize(trip,record.pendingSave)),true))
  if(!record.status){panel.append(button('저장된 추천 작업 이어서 보기',()=>run(()=>pollJob(trip))));return}
  const status=record.status
  if(['QUEUED','RUNNING'].includes(status.state)){panel.append(el('p',status.state==='QUEUED'?'추천 작업 대기 중이에요.':'추천 영상을 만들고 있어요.'));return}
  if(status.state==='FAILED'){panel.append(el('p','추천 작업이 실패했어요.'));if(!record.pendingSave)panel.append(button('새 작업으로 재시도',()=>run(async()=>{const response=await state.api.createJob(trip.tripId,record.profile,operationKey(trip));await followJob(trip,response.jobId)})));return}
  const cards=status.result?.cards||[]
  if(!cards.length){panel.append(el('p','추천할 장면이 없어요.'));return}
  for(const card of cards){const row=el('article',undefined,'card');add(row,el('span',`${(card.endMs-card.startMs)/1000}초`,'badge'),el('h3',card.title),el('p',card.summary||''));if(card.recommendation?.reason)row.append(el('p',card.recommendation.reason,'muted'));if(card.soundEvent)row.append(el('p',typeof card.soundEvent==='string'?card.soundEvent:'소리 이벤트가 연결된 장면이에요.','muted'));row.append(button('선택 영상 재생',()=>run(()=>play(state.api.cardPath(record.jobId,card.id),{kind:'job',tripId:trip.tripId}),{render:false})))
    if(!record.pendingSave){row.append(button('휴대폰 저장 QR',()=>run(async()=>showTicket(await state.api.handoff(trip.tripId,{jobId:record.jobId,moment:card.id}))),false));row.append(button('이 영상 보관',()=>{if(confirm(`“${card.title}” 편집 버전과 각자 선택한 표시 클립을 보관할까요?`))run(()=>finalize(trip,{jobId:record.jobId,moment:card.id}))},true));const details=el('details'),form=el('form');details.append(el('summary','제목·구간·자막 편집'));const title=field(form,'제목',card.title),start=field(form,'시작 (운행 기준 초)',String(card.startMs/1000)),end=field(form,'끝 (운행 기준 초)',String(card.endMs/1000)),captions=card.cues.map((cue,i)=>field(form,`자막 ${i+1}`,cue.text));form.append(button('새 버전으로 편집',()=>run(async()=>{const body=validateEdit(card,{title:title.value,start:start.value,end:end.value,captions:captions.map(f=>f.value)}),response=await state.api.edit(record.jobId,body,operationKey(trip));await followJob(trip,response.jobId)})));details.append(form);row.append(details)}panel.append(row)
  }
}
function libraryPage(){
  add(root,el('p','YOUR MEMORIES','eyebrow'),el('h1','보관한 순간'),button('보관함 새로고침',()=>run(async()=>{state.library=await state.api.library()})))
  if(!state.library.length)root.append(el('p','아직 보관한 영상이 없어요.','blank'))
  for(const item of state.library){const panel=section(item.title);panel.append(el('p',`${Math.round(item.durationMs/1000)}초 · ${new Date(item.savedAt).toLocaleDateString('ko-KR')}`,'muted'));const path=item.clipUrl||(!item.markId?`/api/library/${item.tripId}/clip`:null);if(path)panel.append(button('재생',()=>run(()=>play(path,{kind:'library',tripId:item.tripId,markId:item.markId,clipUrl:item.clipUrl}),{render:false})));if(!item.markId)panel.append(button('휴대폰 저장 QR',()=>run(async()=>showTicket(await state.api.handoff(item.tripId))),false))}
}
let polling=false
setInterval(async()=>{
  if(document.hidden||state.busy||polling||!state.api)return
  polling=true
  const api=state.api,epoch=state.epoch
  try{
    if(state.invitation&&Date.parse(state.invitation.expiresAt)<=Date.now()){state.invitation=null;root.querySelectorAll('.invite-link').forEach(n=>n.remove())}
    if(state.ticket&&Date.parse(state.ticket.expiresAt)<=Date.now())closeTicket()
    if(state.playing){const playing=state.playing
      if(!await mayPlay(api,playing)&&state.playing===playing)closePlayer()
    }
    if(state.page==='trip'&&state.trip){const trip=state.trip,record=jobRecord(trip)
      const fresh=await api.trip(trip.tripId)
      if(epoch!==state.epoch||state.busy||state.trip?.tripId!==trip.tripId)return
      if(fresh.phase!==trip.phase){
        await selectTrip(trip.tripId)
        if(fresh.phase==='SAVED'){
          record.pendingSave=null;sessionStorage.removeItem(pendingKey('save',trip.tripId));state.page='library';state.library=await api.library()
        }
        if(fresh.phase!=='DRIVING')state.invitation=null
        paint();return
      }
      if(JSON.stringify(fresh.receipts)!==JSON.stringify(trip.receipts)||JSON.stringify(fresh.occupants)!==JSON.stringify(trip.occupants)){
        state.trip=fresh;paint();say('탑승자 또는 동의 상태가 바뀌어 최신 내용으로 갱신했어요.');return
      }
      if(state.timeline){
        const timeline=await api.timeline(trip.tripId)
        if(epoch!==state.epoch||state.busy||state.trip?.tripId!==trip.tripId)return
        if(JSON.stringify(timeline)!==JSON.stringify(state.timeline)){
          state.timeline=timeline
          const old=root.querySelector('section[data-timeline]')
          if(old){const replacement=timelinePanel(trip);old.replaceWith(replacement)}
        }
      }
      if(record.jobId&&['QUEUED','RUNNING'].includes(record.status?.state)){await pollJob(trip);if(epoch===state.epoch)paint()}
      if(trip.phase==='SAVING'||trip.phase==='DISCARDING'){const updated=await api.trip(trip.tripId);if(epoch===state.epoch&&updated.phase!==trip.phase){await selectTrip(trip.tripId);if(updated.phase==='SAVED'){record.pendingSave=null;sessionStorage.removeItem(pendingKey('save',trip.tripId));state.page='library';state.library=await api.library()}paint()}}
    }
  }catch(error){if(epoch===state.epoch){if(state.playing)closePlayer();say(error.message)}}finally{polling=false}
},3000)
async function start(){
  if(!location.hash){
    try{
      const guest=await loadGuest()
      if(guest){
        const api=new DriveApi(guest.origin)
        api.authorize(guest.accountId,guest.accessToken)
        state.api=api
        try { await selectTrip(guest.tripId) }
        catch(error){say(error.message)}
      }
    }catch{say('이 탭의 참여 계정을 복원하지 못했어요. 보관한 계정으로 연결하세요.')}
  }
  paint()
}
await start()
