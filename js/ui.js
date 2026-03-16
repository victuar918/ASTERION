/**
 * ui.js — ASTERION 공통 UI 레이어
 * 로드 순서: config.js → api_contract.js → state.js → api.js → ui.js → (page.js)
 *
 * 포함 기능:
 *  1. 로딩 오버레이  : showLoading(text) / hideLoading()
 *  2. 토스트 메시지  : showToast(msg, type, ms) / toastOk / toastErr
 *  3. 확인 팝업     : await confirmPopup(question) → boolean
 *  4. 상태 메시지   : setStatus(elId, msg, type, autoClearMs)
 *  5. 이미지 박스   : initImageBox(opts)
 *
 * 각 HTML은 이 파일만 로드하면 중복 인라인 코드가 필요 없습니다.
 */
"use strict";

/* ══════════════════════════════════════════════════════
   0. 공통 요소 자동 삽입 (DOMContentLoaded 전에도 안전)
══════════════════════════════════════════════════════ */
(function () {
  function _initUI() {
    if (document.getElementById("_ui_loading")) return; // 중복 방지

  /* 스타일 */
  var s = document.createElement("style");
  s.textContent =
    /* 로딩 */
    "#_ui_loading{display:none;position:fixed;inset:0;background:rgba(0,0,0,.6);z-index:9000;align-items:center;justify-content:center;flex-direction:column;gap:13px}" +
    "#_ui_loading.show{display:flex}" +
    "#_ui_loading .ld-dots{display:flex;gap:7px}" +
    "#_ui_loading .ld-dots span{width:9px;height:9px;border-radius:50%;background:rgba(220,188,128,.9);animation:_ldp 1.2s ease-in-out infinite}" +
    "#_ui_loading .ld-dots span:nth-child(2){animation-delay:.2s}" +
    "#_ui_loading .ld-dots span:nth-child(3){animation-delay:.4s}" +
    "@keyframes _ldp{0%,80%,100%{transform:scale(.7);opacity:.4}40%{transform:scale(1.1);opacity:1}}" +
    "#_ui_loading .ld-txt{font-size:12px;letter-spacing:2.5px;color:#2d4a7a}" +
    /* 토스트 */
    "#_ui_toast{position:fixed;bottom:80px;left:50%;transform:translateX(-50%) translateY(20px);background:rgba(255,252,240,.92);border:1px solid rgba(180,140,60,.45);border-radius:20px;padding:9px 20px;font-size:12px;color:#2d4a7a;z-index:9100;opacity:0;transition:opacity .25s,transform .25s;pointer-events:none;white-space:nowrap}" +
    "#_ui_toast.show{opacity:1;transform:translateX(-50%) translateY(0)}" +
    "#_ui_toast.t-err{border-color:rgba(200,60,60,.5);color:rgba(160,30,30,.9)}" +
    "#_ui_toast.t-ok{border-color:rgba(40,140,80,.5);color:rgba(20,100,50,.9)}" +
    /* 팝업 */
    "#_ui_popup{display:none;position:fixed;inset:0;background:rgba(0,0,0,.65);z-index:9200;align-items:center;justify-content:center}" +
    "#_ui_popup.show{display:flex}" +
    "#_ui_popup .pop-box{background:rgba(20,15,8,.97);border:1px solid rgba(200,160,100,.45);border-radius:14px;padding:26px 24px 20px;width:270px;text-align:center}" +
    "#_ui_popup .pop-q{font-size:14px;color:rgba(220,188,128,1);line-height:1.55;margin-bottom:20px}" +
    "#_ui_popup .pop-btns{display:flex;gap:12px;justify-content:center}" +
    "#_ui_popup .pop-btns button{flex:1;padding:10px 0;border-radius:8px;font-size:14px;font-weight:bold;cursor:pointer;border:none;-webkit-tap-highlight-color:transparent}" +
    "#_ui_popup .p-yes{background:rgba(170,130,75,.85);color:#fff}" +
    "#_ui_popup .p-no{background:rgba(255,255,255,.09);color:rgba(255,255,255,.78);border:1px solid rgba(255,255,255,.2)!important}" +
    /* 공통 상태 메시지 클래스 */
    ".st-msg{font-size:12px;text-align:center;min-height:18px;padding:2px 0}" +
    ".st-err{color:rgba(220,80,80,.9)}" +
    ".st-ok{color:rgba(100,220,130,.9)}" +
    ".st-info{color:rgba(220,188,128,.8)}" +
    /* 이미지 오버레이 공통 */
    ".img-ov{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;z-index:2;display:none}" +
    ".img-ov.show{display:block}";
  document.head.appendChild(s);

  /* 로딩 오버레이 */
  var ld = document.createElement("div"); ld.id = "_ui_loading";
  ld.innerHTML = '<div class="ld-dots"><span></span><span></span><span></span></div><div class="ld-txt" id="_ui_ld_txt">LOADING...</div>';
  document.body.appendChild(ld);

  /* 토스트 */
  var t = document.createElement("div"); t.id = "_ui_toast";
  document.body.appendChild(t);

  /* 팝업 */
  var p = document.createElement("div"); p.id = "_ui_popup";
  p.innerHTML = '<div class="pop-box"><div class="pop-q" id="_ui_pop_q"></div><div class="pop-btns"><button class="p-yes" id="_ui_pop_yes">Yes</button><button class="p-no" id="_ui_pop_no">No</button></div></div>';
  document.body.appendChild(p);
  }
  /* body가 준비됐으면 즉시, 아니면 DOMContentLoaded 이후 실행 */
  if (document.body) {
    _initUI();
  } else {
    document.addEventListener("DOMContentLoaded", _initUI);
  }
})();

/* ══════════════════════════════════════════════════════
   1. 로딩 오버레이
══════════════════════════════════════════════════════ */
function showLoading(text) {
  var el = document.getElementById("_ui_loading");
  var tx = document.getElementById("_ui_ld_txt");
  if (tx) tx.textContent = text ? String(text).toUpperCase() : "LOADING...";
  if (el) el.classList.add("show");
}
function hideLoading() {
  var el = document.getElementById("_ui_loading");
  if (el) el.classList.remove("show");
}

/* ══════════════════════════════════════════════════════
   2. 토스트 메시지
══════════════════════════════════════════════════════ */
var _toastTimer = null;
/**
 * @param {string} msg
 * @param {"ok"|"err"|""} [type]
 * @param {number} [ms] 표시 시간 (기본 2400)
 */
function showToast(msg, type, ms) {
  if (!ms) ms = 2400;
  var el = document.getElementById("_ui_toast"); if (!el) return;
  if (_toastTimer) { clearTimeout(_toastTimer); _toastTimer = null; }

  /* [FIX-2] className 직접 대입 제거 → classList 방식
   *  이유: el.id = "_ui_toast" 이므로 id 셀렉터로 CSS가 적용됨.
   *        className에 "_ui_toast"를 넣어도 스타일 효과가 없고 혼란만 줌.
   *        classList로 상태 클래스만 명시적으로 관리 */
  el.classList.remove("show", "t-ok", "t-err"); // 이전 상태 초기화
  el.textContent = String(msg || "");
  el.classList.add("show");
  if (type === "ok")  el.classList.add("t-ok");
  if (type === "err") el.classList.add("t-err");

  _toastTimer = setTimeout(function () {
    el.classList.remove("show", "t-ok", "t-err"); _toastTimer = null;
  }, ms);
}
function toastOk(msg, ms)  { showToast(msg, "ok",  ms || 2400); }
function toastErr(msg, ms) { showToast(msg, "err", ms || 3000); }

/* ══════════════════════════════════════════════════════
   3. 확인 팝업 (Promise 기반, async/await 지원)
══════════════════════════════════════════════════════ */
/**
 * @param {string} question
 * @returns {Promise<boolean>} Yes → true, No/배경 클릭 → false
 *
 * 사용법:
 *   var confirmed = await confirmPopup("Deduct from stock?");
 *   if (confirmed) { ... }
 */
function confirmPopup(question) {
  return new Promise(function (resolve) {
    var overlay = document.getElementById("_ui_popup");
    var qEl     = document.getElementById("_ui_pop_q");
    var yesBtn  = document.getElementById("_ui_pop_yes");
    var noBtn   = document.getElementById("_ui_pop_no");
    if (!overlay) { resolve(false); return; }
    qEl.textContent = question || "";
    overlay.classList.add("show");

    function onYes() { cleanup(); resolve(true);  }
    function onNo()  { cleanup(); resolve(false); }
    function onBg(e) { if (e.target === overlay) { cleanup(); resolve(false); } }
    function cleanup() {
      overlay.classList.remove("show");
      yesBtn.removeEventListener("click", onYes);
      noBtn.removeEventListener("click",  onNo);
      overlay.removeEventListener("click", onBg);
    }
    yesBtn.addEventListener("click", onYes);
    noBtn.addEventListener("click",  onNo);
    overlay.addEventListener("click", onBg);
  });
}

/* ══════════════════════════════════════════════════════
   4. 상태 메시지 (인라인 영역용)
══════════════════════════════════════════════════════ */
/**
 * @param {string} elId         - 표시할 요소 id
 * @param {string} msg          - 메시지 (빈 문자열이면 지움)
 * @param {"ok"|"err"|"info"|""} [type]
 * @param {number} [autoClearMs] - ms 후 자동 삭제 (0 또는 미지정이면 유지)
 */
function setStatus(elId, msg, type, autoClearMs) {
  var el = document.getElementById(elId); if (!el) return;
  el.textContent = msg || "";
  el.className = "st-msg " + (type === "err" ? "st-err" : type === "ok" ? "st-ok" : type === "info" ? "st-info" : "");
  if (autoClearMs && msg) {
    var snapshot = msg;
    setTimeout(function () {
      if (el.textContent === snapshot) { el.textContent = ""; el.className = "st-msg"; }
    }, autoClearMs);
  }
}

/* ══════════════════════════════════════════════════════
   5. 이미지 선택 박스 초기화
══════════════════════════════════════════════════════ */
/**
 * HTML 구조 (img-ov 클래스는 ui.js CSS에서 정의됨):
 *   <div id="WRAP" style="position:relative;...">
 *     <input type="file" id="FILE_INPUT" .../>   ← inset:0, opacity:0, z-index:3
 *     <img class="img-bg" src="fallback.png"/>   ← 배경 버튼 이미지
 *     <img class="img-ov" id="OV_ID" src=""/>    ← 선택 후 오버레이 (ui.js 스타일 적용)
 *   </div>
 *
 * @param {Object}   opts
 * @param {string}   opts.inputId    - file input id
 * @param {string}   opts.overlayId  - 오버레이 img id
 * @param {Function} [opts.onSelect] - 파일 선택 콜백(file)
 * @param {string}   [opts.existingUrl] - 기존 이미지 URL
 */
function initImageBox(opts) {
  var input   = document.getElementById(opts.inputId);
  var overlay = document.getElementById(opts.overlayId);
  if (!input || !overlay) return;

  if (opts.existingUrl) { overlay.src = opts.existingUrl; overlay.classList.add("show"); }

  input.addEventListener("change", function (e) {
    var file = e.target.files && e.target.files[0]; if (!file) return;

    /* [FIX-1] UPLOAD 방어 코드
     *  UPLOAD는 api_contract.js에서 정의되므로 config.js 로드와 무관.
     *  그러나 api_contract.js 로드 실패 시 ReferenceError 방지를 위해
     *  폴백값을 명시적으로 지정. */
    var maxBytes   = (typeof UPLOAD !== "undefined" && UPLOAD.MAX_BYTES)   || 4194304;  // 4MB
    var allowedMime = (typeof UPLOAD !== "undefined" && UPLOAD.ALLOWED_MIME) || ["image/jpeg","image/jpg","image/png","image/webp","image/gif"];

    if (file.size > maxBytes) { toastErr("이미지는 4MB 이하여야 합니다."); e.target.value = ""; return; }
    if (allowedMime.indexOf(file.type.toLowerCase()) === -1) { toastErr("허용 형식: JPG, PNG, WEBP, GIF"); e.target.value = ""; return; }

    var r = new FileReader();
    r.onload = function (ev) { overlay.src = ev.target.result; overlay.classList.add("show"); };
    r.readAsDataURL(file);
    if (typeof opts.onSelect === "function") opts.onSelect(file);
  });
}

