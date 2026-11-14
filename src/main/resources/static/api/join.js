'use strict';
const el=id=>document.getElementById(id);
const fragment=location.hash.slice(1);
const [inviteTrip,code]=fragment.split('.');
const trip=inviteTrip || new URLSearchParams(location.search).get('trip');
const storageKey='drivelog.guest.'+trip;
let guest=JSON.parse(localStorage.getItem(storageKey)||'null');
let timeline, pending, objectUrl, playing;
function say(message){el('status').textContent=message;}
async function api(path,method='GET',body){
  const headers={'Content-Type':'application/json'};
  if(guest)headers.Authorization='Bearer '+guest.accessToken;
  const r=await fetch('/api/private-trips/'+trip+path,{method,headers,cache:'no-store',body:body?JSON.stringify(body):undefined});
  if(!r.ok)throw Error('요청을 처리하지 못했습니다 ('+r.status+')');
  return r;
}
function stop(){el('player').pause();el('player').removeAttribute('src');el('player').hidden=true;
  if(objectUrl)URL.revokeObjectURL(objectUrl);objectUrl=null;playing=null;}
async function refresh(){
  timeline=await (await api('/timeline')).json();
  el('entry').hidden=true;el('ride').hidden=false;
  el('mark').disabled=!['DRIVING','PENDING','UPLOAD'].includes(timeline.phase);
  el('consent').hidden=timeline.phase==='DRIVING';
  if(playing&&!timeline.entries.some(e=>e.clipUrl===playing))stop();
  el('list').replaceChildren(...timeline.entries.map(e=>{
    const row=document.createElement('li');row.textContent=[e.authorName||'동승자',e.place,
      new Date(Date.parse(timeline.startedAt)+e.mark.atMs).toLocaleTimeString('ko-KR'),e.title].filter(Boolean).join(' · ');
    if(e.clipUrl){const b=document.createElement('button');b.textContent='재생';row.append(b);
      b.onclick=()=>action(async()=>{stop();const r=await api('/marks/'+e.mark.id+'/clip');const blob=await r.blob();
        const latest=await (await api('/timeline')).json();if(!latest.entries.some(x=>x.clipUrl===e.clipUrl))return;
        playing=e.clipUrl;objectUrl=URL.createObjectURL(blob);el('player').src=objectUrl;el('player').hidden=false;await el('player').play();});}
    return row;}));
}
async function action(f){try{await f();}catch(e){stop();say(e.message);}}
el('entry').onsubmit=e=>{e.preventDefault();action(async()=>{
  if(!code)throw Error('초대 링크를 열어 주세요.');
  const key='drivelog.claim.'+trip+'.'+code;
  let claimKey=sessionStorage.getItem(key);
  if(!claimKey){claimKey=btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32)))).replaceAll('+','-').replaceAll('/','_').replaceAll('=','');sessionStorage.setItem(key,claimKey);}
  guest=await (await api('/join','POST',{code,claimKey,displayName:el('nick').value})).json();
  localStorage.setItem(storageKey,JSON.stringify(guest));sessionStorage.removeItem(key);
  history.replaceState(null,'','?trip='+encodeURIComponent(trip));await refresh();say('참여했습니다.');
});};
el('mark').onclick=()=>action(async()=>{pending??={id:crypto.randomUUID(),atMs:Math.max(0,Date.now()-Date.parse(timeline.startedAt))};
  await api('/marks/'+pending.id,'PUT',{kind:'TAP',atMs:pending.atMs});pending=null;await refresh();say('표시했어요.');});
async function vote(yes){await api('/consents/'+guest.accountId+'/PASSENGER','PUT',{extraction:yes,geminiTranscript:yes,useTripContext:yes&&el('locationOpt').checked,shareMarkedClips:yes&&el('shareOpt').checked});
  if(!yes){stop();el('ride').hidden=true;say('철회했습니다.');}else{await refresh();say('동의가 반영됐습니다.');}}
el('consent').onsubmit=e=>{e.preventDefault();action(()=>vote(true));};
el('deny').onclick=()=>action(()=>vote(false));el('refresh').onclick=()=>action(refresh);
if(guest&&!code)action(refresh);
setInterval(()=>{if(guest&&!el('ride').hidden&&!document.hidden)action(refresh);},10000);
