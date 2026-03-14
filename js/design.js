// ============================================================
// design.js — Structure Design  (BUG FIX v2)
// [FIX-1] getElementById "structureCodeDisplay" → "structureCodeLabel"
// [FIX-2] activePlusIndex 활성 [+] 고정 (idx+1 이동 제거)
// [FIX-3] 전역 merge 제거 → 바로 다음 항목과만 비교
// [FIX-4] updateLayoutSummary → layoutFormatter.js detailsToPDF() 사용
// [FIX-5] updateTotalSize → layoutFormatter.js calcTotalSize() 사용
// [FIX-6] Save → 커스텀 Yes/No 팝업
// ============================================================

// ── 전역 상태 ─────────────────────────────────────────────────
let details        = [{ isButton: true }];
let activePlusIndex = null;
let availableStones = [];
let availableSizes  = [];
let structureCode   = "";

// ── 초기화 ────────────────────────────────────────────────────
document.addEventListener("DOMContentLoaded", async function () {
  structureCode = getStructureCode();
  if (!structureCode) {
    alert("StructureCode 없음");
    location.href = "worksdesk.html";
    return;
  }

  // [FIX-1] "structureCodeDisplay" → "structureCodeLabel" (design.html과 일치)
  document.getElementById("structureCodeLabel").textContent = "StructureCode: " + structureCode;

  try {
    showLoading("Loading...");

    // 사용 원석 + 사이즈 목록 로드
    const res = await apiPost({ action: "getUsedSizes", structureCode });
    availableSizes  = (res.sizes  && res.sizes.length)  ? res.sizes  : ["2mm","4mm","6mm","8mm","10mm","12mm"];
    availableStones = (res.stones && res.stones.length) ? res.stones : [];

    // 기존 저장된 Details 불러오기
    const existing = await apiPost({ action: "getDetails", structureCode });
    if (existing.success && existing.details && existing.details.length) {
      details = [];
      existing.details.forEach(function (d) {
        details.push({ isButton: true });
        details.push({ isButton: false, nameKr: d.NameKr, size: d.Size, qty: Number(d.Qty) || 1 });
      });
      details.push({ isButton: true });
    }

    renderStoneSelectorAndSizes();
    renderDetails();

  } catch (err) {
    alert("Init failed: " + err.message);
  } finally {
    hideLoading();
  }
});

// ── 상단 : NameKr + Size 버튼 렌더링 ──────────────────────────
function renderStoneSelectorAndSizes() {
  const container = document.getElementById("stoneSelector");
  container.innerHTML = "";

  if (!availableStones.length) {
    container.innerHTML = '<span style="opacity:.5;font-size:13px;">선택된 원석 없음</span>';
    return;
  }

  availableStones.forEach(function (name) {
    const row = document.createElement("div");

    const nameLabel = document.createElement("span");
    nameLabel.textContent = name;
    nameLabel.className = "stone-name";
    row.appendChild(nameLabel);

    availableSizes.forEach(function (size) {
      const btn = document.createElement("button");
      btn.textContent = size;
      btn.className   = "size-btn";
      btn.onclick     = function () { addDetail(name, size); };
      row.appendChild(btn);
    });

    container.appendChild(row);
  });
}

// ── Details 영역 렌더링 ───────────────────────────────────────
function renderDetails() {
  const container = document.getElementById("detailsContainer");
  container.innerHTML = "";

  details.forEach(function (d, idx) {
    const btn = document.createElement("button");

    if (d.isButton) {
      // [+] 버튼
      btn.textContent = "+";
      btn.className   = "plus-btn" + (activePlusIndex === idx ? " active-plus" : "");
      btn.onclick     = (function (i) {
        return function () { activePlusIndex = i; renderDetails(); };
      })(idx);
    } else {
      // 아이템 버튼 (클릭 시 qty 1 감소)
      btn.textContent = d.nameKr + " (" + d.size + ", " + d.qty + "pcs)";
      btn.className   = "detail-btn";
      btn.title       = "클릭: 1개 제거";
      btn.onclick     = (function (i) {
        return function () { removeDetail(i); };
      })(idx);
    }

    container.appendChild(btn);
  });

  updateTotalSize();
  updateLayoutSummary();
}

// ── Details 추가 ──────────────────────────────────────────────
// [FIX-2] activePlusIndex 고정 (삽입 후 이동 안 함)
// [FIX-3] 전역 merge 제거 → 바로 다음 항목과만 비교
function addDetail(nameKr, size) {
  // 활성 [+]가 없으면 맨 마지막 [+] 자동 선택
  if (activePlusIndex === null) {
    activePlusIndex = details.length - 1;
  }

  const insertAt = activePlusIndex + 1; // 활성 [+] 바로 다음 위치

  const nextItem = details[insertAt];

  if (nextItem && !nextItem.isButton &&
      nextItem.nameKr === nameKr && nextItem.size === size) {
    // ✅ 바로 다음 항목이 동일 → 수량만 증가
    nextItem.qty++;

  } else {
    // ✅ 새 항목 삽입 (activePlusIndex는 절대 변경하지 않음)
    details.splice(insertAt, 0, { isButton: false, nameKr: nameKr, size: size, qty: 1 });

    // 삽입 후 바로 다음이 [+]가 아니면 [+] 추가
    const afterNew = details[insertAt + 1];
    if (!afterNew || !afterNew.isButton) {
      details.splice(insertAt + 1, 0, { isButton: true });
    }
    // [FIX-2] activePlusIndex 변경 없음 → 같은 [+] 유지
  }

  renderDetails();
}

// ── Details 제거 (qty-1, qty=0이면 행 삭제) ──────────────────

