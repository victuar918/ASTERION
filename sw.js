/**
 * sw.js — ASTERION Service Worker
 * 역할: 앱 셸(HTML/JS/CSS/이미지) 캐싱 → 오프라인에서도 화면 로드 가능
 * 데이터(GAS API 통신)는 캐시하지 않음 — 항상 최신 서버 데이터 사용
 */

var CACHE_NAME = "asterion-v1";

/* 캐싱할 앱 셸 파일 목록 */
var CACHE_URLS = [
  "/ASTERION/index.html",
  "/ASTERION/worksdesk.html",
  "/ASTERION/selectstone.html",
  "/ASTERION/design.html",
  "/ASTERION/analysismemo.html",
  "/ASTERION/productimage.html",
  "/ASTERION/booklet.html",
  "/ASTERION/structureindex.html",
  "/ASTERION/inventory.html",
  "/ASTERION/stoneinfo.html",
  "/ASTERION/newlisting.html",
  "/ASTERION/invenmanage.html",
  "/ASTERION/forwarding.html",
  "/ASTERION/js/config.js",
  "/ASTERION/js/api_contract.js",
  "/ASTERION/js/state.js",
  "/ASTERION/js/api.js",
  "/ASTERION/js/ui.js",
  "/ASTERION/js/design.js",
  "/ASTERION/js/booklet.js",
  "/ASTERION/js/forwarding.js"
];

/* ── Install: 앱 셸 캐싱 ── */
self.addEventListener("install", function(event) {
  event.waitUntil(
    caches.open(CACHE_NAME).then(function(cache) {
      return cache.addAll(CACHE_URLS);
    }).then(function() {
      return self.skipWaiting();
    })
  );
});

/* ── Activate: 이전 버전 캐시 삭제 ── */
self.addEventListener("activate", function(event) {
  event.waitUntil(
    caches.keys().then(function(keys) {
      return Promise.all(
        keys.filter(function(key) { return key !== CACHE_NAME; })
            .map(function(key) { return caches.delete(key); })
      );
    }).then(function() {
      return self.clients.claim();
    })
  );
});

/* ── Fetch: 캐시 우선, 없으면 네트워크 ── */
self.addEventListener("fetch", function(event) {
  var url = event.request.url;

  /* GAS API 요청은 캐시하지 않고 항상 네트워크로 */
  if (url.indexOf("script.google.com") !== -1) {
    event.respondWith(fetch(event.request));
    return;
  }

  event.respondWith(
    caches.match(event.request).then(function(cached) {
      if (cached) return cached;
      return fetch(event.request).then(function(response) {
        /* 이미지 등 앱 셸 외 리소스는 동적 캐싱 */
        if (response && response.status === 200 && response.type === "basic") {
          var clone = response.clone();
          caches.open(CACHE_NAME).then(function(cache) {
            cache.put(event.request, clone);
          });
        }
        return response;
      }).catch(function() {
        /* 오프라인 + 캐시 없는 경우 index 반환 */
        return caches.match("/ASTERION/index.html");
      });
    })
  );
});

