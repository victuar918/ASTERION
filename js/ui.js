// ============================================================
// ASTERION — UI
// 로딩 오버레이 / 공통 네비게이션
// ============================================================

// ── 로딩 오버레이 (DOM 준비 후 자동 삽입) ──────────────────────
document.addEventListener("DOMContentLoaded", function () {
  // CSS 주입
  const style = document.createElement("style");
  style.textContent = [
    "#_aLoading{display:none;position:fixed;inset:0;background:rgba(0,0,0,0.45);",
    "z-index:9999;flex-direction:column;justify-content:center;align-items:center;",
    "gap:12px;color:#fff;font-size:16px;}",
    "#_aLoading.on{display:flex;}",
    "#_aSpinner{width:40px;height:40px;border:4px solid rgba(255,255,255,0.3);",
    "border-top-color:#fff;border-radius:50%;animation:_aSpin 0.8s linear infinite;}",
    "@keyframes _aSpin{to{transform:rotate(360deg);}}"
  ].join("");
  document.head.appendChild(style);

  // 오버레이 DOM 삽입
  const el = document.createElement("div");
  el.id        = "_aLoading";
  el.innerHTML = '<div id="_aSpinner"></div><span id="_aMsg">처리 중...</span>';
  document.body.appendChild(el);
});

/**
 * 로딩 표시
 * @param {string} [msg]
 */
function showLoading(msg) {
  const overlay = document.getElementById("_aLoading");
  const msgEl   = document.getElementById("_aMsg");
  if (msgEl)   msgEl.textContent = msg || "처리 중...";
  if (overlay) overlay.classList.add("on");
}

/** 로딩 숨기기 */
function hideLoading() {
  const overlay = document.getElementById("_aLoading");
  if (overlay) overlay.classList.remove("on");
}

// ── 네비게이션 ──────────────────────────────────────────────────

/**
 * structureCode를 URL param + sessionStorage로 전달하며 페이지 이동
 * worksdesk 하위 페이지 이동 전용
 * @param {string} page  - 이동할 HTML 파일명
 */
function goPage(page) {
  const code = getStructureCode();
  if (!code) {
    alert("Structure를 선택하세요.");
    location.href = "worksdesk.html";
    return;
  }
  setStructureCode(code); // sessionStorage 갱신
  location.href = page + "?code=" + encodeURIComponent(code);
}

/** 홈으로 이동 */
function goHome() {
  location.href = "index.html";
}

/**
 * 뒤로 이동 (기본: history.back)
 * 페이지별로 재정의 가능 — 재정의 시 페이지 스크립트에서 function goBefore() {...} 선언
 */
function goBefore() {
  history.back();
}

