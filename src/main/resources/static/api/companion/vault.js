const VERSION=1
const encode=bytes=>btoa(String.fromCharCode(...new Uint8Array(bytes)))
const decode=value=>Uint8Array.from(atob(value),c=>c.charCodeAt(0))
async function key(password,salt,cryptoProvider){
  if(password.length<8)throw new Error('잠금 암호는 8자 이상으로 입력하세요.')
  const material=await cryptoProvider.subtle.importKey('raw',new TextEncoder().encode(password),'PBKDF2',false,['deriveKey'])
  return cryptoProvider.subtle.deriveKey({name:'PBKDF2',salt,iterations:310000,hash:'SHA-256'},material,{name:'AES-GCM',length:256},false,['encrypt','decrypt'])
}
export async function sealSession(session,password,cryptoProvider=globalThis.crypto){
  const salt=cryptoProvider.getRandomValues(new Uint8Array(16)),iv=cryptoProvider.getRandomValues(new Uint8Array(12)),derived=await key(password,salt,cryptoProvider)
  const ciphertext=await cryptoProvider.subtle.encrypt({name:'AES-GCM',iv},derived,new TextEncoder().encode(JSON.stringify(session)))
  return {version:VERSION,salt:encode(salt),iv:encode(iv),ciphertext:encode(ciphertext)}
}
export async function openSession(envelope,password,cryptoProvider=globalThis.crypto){
  if(envelope.version!==VERSION)throw new Error('저장된 계정 형식을 확인하세요.')
  try{
    const iv=decode(envelope.iv),salt=decode(envelope.salt),derived=await key(password,salt,cryptoProvider)
    const plaintext=await cryptoProvider.subtle.decrypt({name:'AES-GCM',iv},derived,decode(envelope.ciphertext))
    const session=JSON.parse(new TextDecoder().decode(plaintext))
    if(!session.origin||!session.accountId||!session.accessToken)throw new Error('session')
    return session
  }catch{throw new Error('잠금 암호가 다르거나 저장된 계정이 손상됐어요.')}
}
