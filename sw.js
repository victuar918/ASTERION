/* ASTERION Service Worker — 최소 동작 */
self.addEventListener("install",  function(e){ self.skipWaiting(); });
self.addEventListener("activate", function(e){ e.waitUntil(self.clients.claim()); });
/* fetch 핸들러 없음 — 모든 요청은 브라우저 기본 동작으로 처리 */
