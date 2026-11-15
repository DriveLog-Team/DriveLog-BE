// The browser retains a non-exportable key. Each tab owns its encrypted guest session.
const entry = 'drivelog:tab-guest-v1'
function openDatabase() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open('drivelog-session-keys', 1)
    request.onupgradeneeded = () => request.result.createObjectStore('keys')
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}
export async function guestKey() {
  const candidate = await crypto.subtle.generateKey({name:'AES-GCM',length:256}, false, ['encrypt','decrypt'])
  const db = await openDatabase()
  try {
    return await new Promise((resolve, reject) => {
      const tx = db.transaction('keys', 'readwrite'), store = tx.objectStore('keys')
      const read = store.get('guest')
      let key
      read.onsuccess = () => {
        key = read.result || candidate
        if (!read.result) store.put(key, 'guest')
      }
      tx.oncomplete = () => resolve(key)
      tx.onabort = () => reject(tx.error)
    })
  } finally { db.close() }
}
export async function saveGuest(session, key) {
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const bytes = await crypto.subtle.encrypt({name:'AES-GCM',iv}, key, new TextEncoder().encode(JSON.stringify(session)))
  sessionStorage.setItem(entry, JSON.stringify({iv:[...iv], bytes:[...new Uint8Array(bytes)]}))
}
export async function loadGuest() {
  const raw = sessionStorage.getItem(entry)
  if (!raw) return null
  const {iv,bytes} = JSON.parse(raw)
  const plain = await crypto.subtle.decrypt({name:'AES-GCM',iv:new Uint8Array(iv)}, await guestKey(), new Uint8Array(bytes))
  return JSON.parse(new TextDecoder().decode(plain))
}
export function forgetGuest() { sessionStorage.removeItem(entry) }
