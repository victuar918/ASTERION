/**
 * state.js — ASTERION Global State & Router
 *
 * 포함 내용:
 *  - StructureCode 이중 관리 (window 변수 + URL param)
 *  - 페이지 이동 시 code 유지 (goPage)
 *  - 데이터 캐시 레이어 (TTL 기반)
 *  - 전역 에러 핸들러
 *  - Size 파싱 유틸 (getSizeNumber, formatSize)
 */

"use strict";

// ══════════════════════════════════════════════
// 1. StructureCode 관리
// ══════════════════════════════════════════════

/**
 * StructureCode 가져오기
 * 우선순위: window.structureCode → URL param → null
 * 한번 복원되면 window에 캐시되므로 이후 URL param 없어도 유지
 */
function getStructureCode() {
  if (window.structureCode) return window.structureCode;

  try {
    const url  = new URL(window.location.href);
    const code = url.searchParams.get("code");
    if (code && code.trim()) {
      window.structureCode = code.trim();
      return window.structureCode;
    }
  } catch (e) {
    // URL 파싱 실패 — 무시
  }

  return null;
}

/**
 * StructureCode 명시 설정
 * selectstone.html 등에서 새 code를 받았을 때 사용
 */
function setStructureCode(code) {
  if (code && code.trim()) {
    window.structureCode = code.trim();
  }
}

/**
 * StructureCode를 유지하며 페이지 이동
 * @param {string} page  - 이동할 HTML 파일명 (예: "design.html")
 * @param {string} [code] - 명시 code (없으면 현재 code 사용)
 */
function goPage(page, code) {
  const c = (code && code.trim()) || getStructureCode();
  if (c) {
    window.location.href = `${page}?code=${encodeURIComponent(c)}`;
  } else {
    window.location.href = page;
  }
}

/**
 * StructureCode 필수 페이지에서 호출
 * code가 없으면 worksdesk.html로 redirect하고 null 반환
 * code가 있으면 code 문자열 반환
 */
function requireStructureCode() {
  const code = getStructureCode();
  if (!code) {
    alert("StructureCode가 없습니다.\n작업 목록으로 이동합니다.");
    window.location.href = "worksdesk.html";
    return null;
  }
  return code;
}

// ══════════════════════════════════════════════
// 2. 데이터 캐시 레이어
// ══════════════════════════════════════════════

const _cache = {};

/**
 * 캐시 저장
 * @param {string} key
 * @param {any} value
 * @param {number} [ttl=300000] - 유효시간(ms), 기본 5분
 */
function cacheSet(key, value, ttl = 300000) {
  _cache[key] = {
    value,
    expiry: Date.now() + ttl
  };
}

/**
 * 캐시 조회
 * 만료되었거나 없으면 null 반환
 */
function cacheGet(key) {
  const item = _cache[key];
  if (!item) return null;
  if (Date.now() > item.expiry) {
    delete _cache[key];
    return null;
  }
  return item.value;
}

/**
 * 캐시 삭제
 * @param {string} [key] - 없으면 전체 삭제
 */
function cacheClear(key) {
  if (key) {
    delete _cache[key];
  } else {
    Object.keys(_cache).forEach(k => delete _cache[k]);
  }
}

// ══════════════════════════════════════════════
// 3. 전역 에러 핸들러
// ══════════════════════════════════════════════

window.onerror = function (message, source, lineno, colno, error) {
  console.error("[ASTERION ERROR]", message, "at line", lineno, "in", source);
  return false; // 브라우저 기본 에러 처리 유지
};

window.onunhandledrejection = function (event) {
  console.error("[ASTERION UNHANDLED PROMISE]", event.reason);
};

// ══════════════════════════════════════════════
// 4. Size 유틸리티
// ══════════════════════════════════════════════

/**
 * size 문자열에서 숫자 추출
 * "4mm" → 4 | "10mm" → 10 | "10" → 10 | null/undefined → 0
 * NaN 방지: parseFloat 실패 시 0 반환
 */
function getSizeNumber(size) {
  if (size === null || size === undefined || size === "") return 0;
  const cleaned = String(size).replace(/mm/gi, "").trim();
  const num = parseFloat(cleaned);
  return isNaN(num) ? 0 : num;
}

/**
 * size를 "Nmm" 형태로 포맷
 * "4"   → "4mm"
 * "4mm" → "4mm" (멱등)
 * 4     → "4mm"
 */
function formatSize(size) {
  const n = getSizeNumber(size);
  return n + "mm";
}
