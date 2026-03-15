/**
 * design.js — Structure Design 페이지 로직 (완전판)
 *
 * [FIX-3] buildDetailsString() A/S용 상세 문자열 → Archive.DetailsStr 저장
 * [SPEC]  activeInsIdx: [+] 클릭으로 위치 선택 → Size 클릭으로 삽입 → 자동 해제
 *         아이템 클릭: qty-1 (0이면 삭제), activeInsIdx 유지
 */
"use strict";

var sc           = null;
var details      = [];
var activeInsIdx = null;

/* ══════════════════════════════════════════════
   초기화
══════════════════════════════════════════════ */
document.addEventListener("DOMContentLoaded", async function () {
  sc = requireStructureCode();
  if (!sc) return;
  document.getElementById("code-disp").textContent = sc;

  document.getElementById("b-home").addEventListener("click", function () { clearAndGo("index.html"); });
  document.getElementById("b-back").addEventListener("click", function () { goPage("selectstone.html", sc); });
  document.getElementById("b-save").addEventListener("click", onSaveClick);
  document.getElementById("b-next").addEventListener("click", function () { goPage("analysismemo.html", sc); });

  document.getElementById("pop-yes").addEventListener("click", function () { hidePopup(); execSave(true); });
  document.getElementById("pop-no").addEventListener("click",  function () { hidePopup(); execSave(false); });
  document.getElementById("popup").addEventListener("click", function (e) { if (e.target === this) hidePopup(); });

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
  } catch (e) {}
}

/* ══════════════════════════════════════════════
   Used Items 로드
══════════════════════════════════════════════ */
async function loadItems() {
  var el = document.getElementById("sel-list");
  try {
    var items = await apiGetUsedItems(sc);
    if (!items || !items.length) {
      el.innerHTML = '<div style="padding:10px;font-size:12px;color:rgba(255,180,80,.7)">재고 있는 항목 없음. selectstone에서 원석을 먼저 선택하세요.</div>';
      return;
    }
    el.innerHTML = "";
    items.forEach(function (item) {
      var row = document.createElement("div"); row.className = "stone-row";
      var nm = document.createElement("span"); nm.className = "stone-name"; nm.textContent = item.nameKr; nm.title = item.nameKr;
      row.appendChild(nm);
      (item.sizes || []).forEach(function (sz) {
        var b = document.createElement("button"); b.className = "btn-size"; b.textContent = formatSize(sz);
        b.addEventListener("click", function () { onSizeClick(item.nameKr, sz, b); });
        row.appendChild(b);
      });
      el.appendChild(row);
    });
  } catch (err) {
    el.innerHTML = '<div style="padding:10px;font-size:12px;color:rgba(220,80,80,.8)">로드 실패: ' + esc(err.message) + '</div>';
  }
}

/* ══════════════════════════════════════════════
   Size 클릭
══════════════════════════════════════════════ */
function onSizeClick(nameKr, size, btn) {
  if (activeInsIdx === null) {
    var ps = document.querySelectorAll(".btn-plus");
    ps.forEach(function (b) { b.style.borderColor = "rgba(220,80,80,.8)"; b.style.color = "rgba(220,80,80,.8)"; });
    setTimeout(function () { ps.forEach(function (b) { b.style.borderColor = ""; b.style.color = ""; }); }, 700);
    return;
  }
  var sz = formatSize(size), pos = activeInsIdx;
  var prev = pos > 0              ? details[pos - 1] : null;
  var next = pos < details.length ? details[pos]     : null;
  if      (prev && prev.nameKr === nameKr && prev.size === sz) { prev.qty++; }
  else if (next && next.nameKr === nameKr && next.size === sz) { next.qty++; activeInsIdx = pos + 1; }
  else { details.splice(pos, 0, { nameKr: nameKr, size: sz, qty: 1 }); activeInsIdx = pos + 1; }
  if (btn) { btn.style.background = "rgba(180,140,90,.6)"; setTimeout(function () { btn.style.background = ""; }, 250); }
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
  var total = 0;
  details.forEach(function (d) { total += getSizeNumber(d.size) * d.qty; });
  document.getElementById("total-disp").textContent = "Total Size " + total + "mm";
}

/**
 * PDF Layout 요약 (북클릿용)
 * 연속된 같은 nameKr 묶음
 * 예: 아파타이트(3pcs) - 화이트캐츠아이(1pcs) - 아쿠아마린(5pcs)
 */
function buildLayoutSummary() {
  if (!details.length) return "";
  var groups = [];
  details.forEach(function (d) {
    if (groups.length && groups[groups.length - 1].nameKr === d.nameKr) {
      groups[groups.length - 1].totalQty += d.qty;
    } else {
      groups.push({ nameKr: d.nameKr, totalQty: d.qty });
    }
  });
  return groups.map(function (g) { return g.nameKr + "(" + g.totalQty + "pcs)"; }).join(" - ");
}

/**
 * [FIX-3] A/S 정비용 상세 배열 문자열
 * 예: 아파타이트(4mm,1pcs) + 아파타이트(8mm,1pcs) + 화이트캐츠아이(12mm,1pcs)
 */
function buildDetailsString() {
  if (!details.length) return "";
  var parts = [];
  details.forEach(function (d) {
    for (var i = 0; i < d.qty; i++) { parts.push(d.nameKr + "(" + d.size + ",1pcs)"); }
  });
  return parts.join(" + ");
}

function renderLayout() {
  document.getElementById("layout-text").textContent = buildLayoutSummary() || "—";
}

/* ══════════════════════════════════════════════
   저장 처리
══════════════════════════════════════════════ */
function onSaveClick() {
  if (!details.length) { alert("Details가 비어있습니다."); return; }
  document.getElementById("popup").classList.add("show");
}
function hidePopup() { document.getElementById("popup").classList.remove("show"); }

async function execSave(deduct) {
  document.getElementById("ld-ov").classList.add("show");
  try {
    /* Step1: Details 시트 저장 + 선택적 재고 차감 */
    await apiSaveDesign(sc, details, deduct);
    /* Step2: Archive.LayoutSummary (PDF 북클릿용) */
    await apiUpdateLayoutSummary(sc, buildLayoutSummary());
    /* [FIX-3] Step3: Archive.DetailsStr (A/S용 상세 문자열) */
    await apiPost({ action: "updateDetailsStr", structureCode: sc, detailsStr: buildDetailsString() });
    document.getElementById("ld-ov").classList.remove("show");
    alert(deduct ? "저장 완료 (재고 차감됨)" : "저장 완료");
  } catch (err) {
    document.getElementById("ld-ov").classList.remove("show");
    console.error("[design] save 실패:", err);
  }
}

