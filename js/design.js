/**
 * design.js — Structure Design 페이지 로직 (최종판)
 * 의존: config.js → api_contract.js → state.js → api.js → ui.js
 *
 * [통합] apiSaveDesign 단일 호출로 3-step 서버 작업 완료
 * [ui.js] showLoading / hideLoading / confirmPopup / toastOk / toastErr 사용
 */
"use strict";

var sc           = null;
var details      = [];   // [{nameKr, size, qty}]
var activeInsIdx = null; // null = 미선택, number = [+] 위치

/* ══════════════════════════════════════════════
   초기화
══════════════════════════════════════════════ */
document.addEventListener("DOMContentLoaded", async function () {
  sc = requireStructureCode(); if (!sc) return;
  document.getElementById("code-disp").textContent = sc;

  document.getElementById("b-home").addEventListener("click", function () { clearAndGo("index.html"); });
  document.getElementById("b-back").addEventListener("click", function () { goPage("selectstone.html", sc); });
  document.getElementById("b-save").addEventListener("click", onSaveClick);
  document.getElementById("b-next").addEventListener("click", function () { goPage("analysismemo.html", sc); });

  await loadSavedDetails();
  await loadItems();
  renderDetails();
});

/* ══════════════════════════════════════════════
   기존 Details 복원
══════════════════════════════════════════════ */
async function loadSavedDetails() {
  try {
    var saved = await apiGetDetails(sc);
    if (saved && saved.length) {
      details = saved.map(function (d) {
        return { nameKr: d.nameKr, size: formatSize(d.size), qty: d.qty };
      });
    }
  } catch (e) { /* 실패 시 빈 상태로 시작 */ }
}

/* ══════════════════════════════════════════════
   Used Items 로드
══════════════════════════════════════════════ */
async function loadItems() {
  var el = document.getElementById("sel-list");
  showLoading("LOADING...");
  try {
    var items = await apiGetUsedItems(sc);
    hideLoading();
    if (!items || !items.length) {
      el.innerHTML = '<div style="padding:10px;font-size:12px;color:rgba(255,180,80,.7)">재고 있는 항목 없음 — selectstone에서 원석을 먼저 선택하세요.</div>';
      return;
    }
    el.innerHTML = "";
    items.forEach(function (item) {
      var row = document.createElement("div"); row.className = "stone-row";
      var nm  = document.createElement("span"); nm.className = "stone-name"; nm.textContent = item.nameKr; nm.title = item.nameKr;
      row.appendChild(nm);
      (item.sizes || []).forEach(function (sz) {
        var b = document.createElement("button"); b.className = "btn-size"; b.textContent = formatSize(sz);
        b.addEventListener("click", function () { onSizeClick(item.nameKr, sz, b); });
        row.appendChild(b);
      });
      el.appendChild(row);
    });
  } catch (err) {
    hideLoading();
    el.innerHTML = '<div style="padding:10px;font-size:12px;color:rgba(220,80,80,.8)">로드 실패: ' + esc(err.message) + '</div>';
  }
}

/* ══════════════════════════════════════════════
   Size 클릭
══════════════════════════════════════════════ */
function onSizeClick(nameKr, size, btn) {
  if (activeInsIdx === null) {
    /* [+] 버튼을 먼저 누르도록 힌트 */
    var ps = document.querySelectorAll(".btn-plus");
    ps.forEach(function (b) { b.style.borderColor = "rgba(220,80,80,.8)"; b.style.color = "rgba(220,80,80,.8)"; });
    setTimeout(function () { ps.forEach(function (b) { b.style.borderColor = ""; b.style.color = ""; }); }, 700);
    return;
  }
  var sz   = formatSize(size);
  var pos  = activeInsIdx;
  var prev = pos > 0              ? details[pos - 1] : null;
  var next = pos < details.length ? details[pos]     : null;

  if      (prev && prev.nameKr === nameKr && prev.size === sz) { prev.qty++; }
  else if (next && next.nameKr === nameKr && next.size === sz) { next.qty++; activeInsIdx = pos + 1; }
  else { details.splice(pos, 0, { nameKr: nameKr, size: sz, qty: 1 }); activeInsIdx = pos + 1; }

  if (btn) { btn.classList.add("flash"); setTimeout(function () { btn.classList.remove("flash"); }, 250); }
  activeInsIdx = null;
  renderDetails();
}

/* ══════════════════════════════════════════════
   Details 렌더링
══════════════════════════════════════════════ */
function renderDetails() {
  var wrap = document.getElementById("details-wrap");
  wrap.innerHTML = "";
  wrap.appendChild(makePlusBtn(0));
  details.forEach(function (item, i) { wrap.appendChild(makeItemBtn(item, i)); wrap.appendChild(makePlusBtn(i + 1)); });
  renderTotal();
  renderLayout();
}

function makePlusBtn(idx) {
  var b = document.createElement("button"); b.className = "btn-plus"; b.textContent = "+";
  if (activeInsIdx === idx) b.classList.add("active-plus");
  b.addEventListener("click", function () { activeInsIdx = (activeInsIdx === idx) ? null : idx; renderDetails(); });
  return b;
}
function makeItemBtn(item, idx) {
  var b = document.createElement("button"); b.className = "btn-item";
  b.innerHTML = '<span>' + esc(item.nameKr) + '</span><span class="sub">' + esc(item.size) + ' ×' + item.qty + 'pcs</span>';
  b.addEventListener("click", function () { details[idx].qty--; if (details[idx].qty <= 0) details.splice(idx, 1); renderDetails(); });
  return b;
}

/* ══════════════════════════════════════════════
   Total & Layout
══════════════════════════════════════════════ */
function renderTotal() {
  var total = 0; details.forEach(function (d) { total += getSizeNumber(d.size) * d.qty; });
  document.getElementById("total-disp").textContent = "Total Size " + total + "mm";
}

/** PDF 북클릿용 — 연속된 같은 nameKr 묶음 */
function buildLayoutSummary() {
  if (!details.length) return "";
  var groups = [];
  details.forEach(function (d) {
    if (groups.length && groups[groups.length - 1].nameKr === d.nameKr) { groups[groups.length - 1].totalQty += d.qty; }
    else { groups.push({ nameKr: d.nameKr, totalQty: d.qty }); }
  });
  return groups.map(function (g) { return g.nameKr + "(" + g.totalQty + "pcs)"; }).join(" - ");
}

/** A/S 정비용 상세 배열 */
function buildDetailsString() {
  if (!details.length) return "";
  var parts = [];
  details.forEach(function (d) { for (var i = 0; i < d.qty; i++) parts.push(d.nameKr + "(" + d.size + ",1pcs)"); });
  return parts.join(" + ");
}

function renderLayout() {
  document.getElementById("layout-text").textContent = buildLayoutSummary() || "—";
}

/* ══════════════════════════════════════════════
   저장 — confirmPopup(ui.js) 사용
══════════════════════════════════════════════ */
async function onSaveClick() {
  if (!details.length) { toastErr("Details가 비어있습니다."); return; }

  /* ui.js confirmPopup으로 팝업 표시 */
  var deduct = await confirmPopup("Deduct from stock?");

  showLoading("SAVING...");
  try {
    /* 단일 GAS 콜 — saveDetails + updateLayoutSummary + updateDetailsStr 통합 */
    await apiSaveDesign(sc, details, deduct, buildLayoutSummary(), buildDetailsString());
    hideLoading();
    toastOk(deduct ? "저장 완료 (재고 차감됨)" : "저장 완료");
  } catch (err) {
    hideLoading();
    toastErr("저장 실패: " + err.message);
    console.error("[design] save 실패:", err);
  }
}
