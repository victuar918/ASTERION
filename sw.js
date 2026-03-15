/**
 * sw.js — ASTERION Service Worker (안전판)
 *
 * 전략: Network-First
 * - 네트워크 요청 성공 → 응답 반환 + 백그라운드 캐싱
 * - 네트워크 실패(오프라인) → 캐시에서 반환
 * - GAS API(script.google.com) → 캐싱 없이 항상 네트워크
 *
 * 설치 실패 방지: 사전 캐싱 없음, 동적 캐싱만 사용
 */

var CACHE = "asterion-v1";

/* ── Install: 최소 설치, 실패 없음 ── */
self.addEventListener("install", function(e) {
  self.skipWaiting();
});

/* ── Activate: 이전 캐시 정리 ── */
self.addEventListener("activate", function(e) {
  e.waitUntil(
    caches.keys().then(function(keys) {
      return Promise.all(
        keys.filter(function(k) { return k !== CACHE; })
            .map(function(k) { return caches.delete(k); })
      );
    }).then(function() { return self.clients.claim(); })
  );
});

/* ── Fetch: Network-First ── */
self.addEventListener("fetch", function(e) {
  /* GET만 처리, POST(GAS API)는 그대로 통과 */
  if (e.request.method !== "GET") return;

  /* GAS API는 캐싱 없이 통과 */
  if (e.request.url.indexOf("script.google.com") !== -1) return;

  e.respondWith(
    fetch(e.request)
      .then(function(res) {
        /* 정상 응답이면 캐시에 저장 후 반환 */
        if (res && res.status === 200) {
          var clone = res.clone();
          caches.open(CACHE).then(function(c) { c.put(e.request, clone); });
        }
        return res;
      })
      .catch(function() {
        /* 오프라인 → 캐시 반환 */
        return caches.match(e.request);
      })
  );
});
