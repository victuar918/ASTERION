/**
 * state.js — ASTERION 전역 상태 관리
 * - StructureCode (worksdesk 계열)
 * - currentNameKr  (inventory 계열)
 * - 캐시, 에러핸들러, 유틸
 */
"use strict";

/* ══════════════════════════════════════════════
   1. StructureCode 관리
══════════════════════════════════════════════ */
function getStructureCode() {
  if (window._sc) return window._sc;
  try {
    var p = new URL(window.location.href).searchParams.get("code");
    if (p && p.trim()) { window._sc = p.trim(); return window._sc; }
  } catch(e) {}
  return null;
}
function setStructureCode(code) {
  if (code && code.trim()) window._sc = code.trim();
}
/** Home 버튼용: StructureCode 초기화 후 이동 */
function clearAndGo(page) {
  window._sc = null;
  window.location.href = page;
}
/** Before/Next 버튼용: StructureCode 유지하며 이동 */
function goPage(page, code) {
  var c = (code && code.trim()) || getStructureCode();
  window.location.href = c ? (page + "?code=" + encodeURIComponent(c)) : page;
}
/** StructureCode 필수 페이지: 없으면 worksdesk로 */
function requireStructureCode() {
  var c = getStructureCode();
  if (!c) { alert("StructureCode가 없습니다."); window.location.href = "worksdesk.html"; return null; }
  return c;
}

/* ══════════════════════════════════════════════
   2. NameKr 관리 (inventory 계열)
══════════════════════════════════════════════ */
function getCurrentNameKr() {
  if (window._nk) return window._nk;
  try {
    var p = new URL(window.location.href).searchParams.get("name");
    if (p && p.trim()) { window._nk = decodeURIComponent(p.trim()); return window._nk; }
  } catch(e) {}
  return null;
}
function setCurrentNameKr(name) {
  if (name && name.trim()) window._nk = name.trim();
}
function clearAndGoFromInventory(page) {
  window._nk = null;
  window.location.href = page;
}
function goPageWithName(page, name) {
  var n = (name && name.trim()) || getCurrentNameKr();
  window.location.href = n ? (page + "?name=" + encodeURIComponent(n)) : page;
}
function requireNameKr() {
  var n = getCurrentNameKr();
  if (!n) { alert("원석을 선택하세요."); window.location.href = "inventory.html"; return null; }
  return n;
}

/* ══════════════════════════════════════════════
   3. 캐시 레이어
══════════════════════════════════════════════ */
var _cache = {};
function cacheSet(key, value, ttl) {
  if (!ttl) ttl = 300000;
  _cache[key] = { value: value, expiry: Date.now() + ttl };
}
function cacheGet(key) {
  var item = _cache[key];
  if (!item) return null;
  if (Date.now() > item.expiry) { delete _cache[key]; return null; }
  return item.value;
}
function cacheClear(key) {
  if (key) { delete _cache[key]; }
  else { Object.keys(_cache).forEach(function(k){ delete _cache[k]; }); }
}

/* ══════════════════════════════════════════════
   4. 전역 에러 핸들러
══════════════════════════════════════════════ */
window.onerror = function(msg, src, line) {
  console.error("[ASTERION]", msg, "line", line, "in", src);
  return false;
};
window.onunhandledrejection = function(e) {
  console.error("[ASTERION PROMISE]", e.reason);
};

/* ══════════════════════════════════════════════
   5. Size/숫자 유틸
══════════════════════════════════════════════ */
function getSizeNumber(s) {
  if (!s && s !== 0) return 0;
  var n = parseFloat(String(s).replace(/mm/gi, "").trim());
  return isNaN(n) ? 0 : n;
}
function formatSize(s) { return getSizeNumber(s) + "mm"; }

/* ══════════════════════════════════════════════
   6. HTML 이스케이프
══════════════════════════════════════════════ */
function esc(s) {
  return String(s || "")
    .replace(/&/g,"&amp;").replace(/</g,"&lt;")
    .replace(/>/g,"&gt;").replace(/"/g,"&quot;");
}

