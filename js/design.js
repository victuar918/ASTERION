// ============================================================
// ASTERION — design.js  v3
// isButton 방식 details 배열 (user 선호 구조 유지)
// 다중 원석 선택 지원 (activeStoneName)
//
// 의존: config.js → api.js → state.js → ui.js → layoutFormatter.js
// DOM:  #structureCodeDisplay / #stoneSelector / #sizeButtons
//       #detailsContainer / #totalSize / #layoutSummary
// ============================================================

// ── 전역 상태 ─────────────────────────────────────────────────
// [DJ1] activeStoneName: inventory용 getSelectedStone()과 완전 분리
let details         = [{ isButton: true }]; // 항상 [+] 로 시작
let activePlusIndex = null;                 // [DJ3] null 초기화
let activeStoneName = "";                   // [DJ1] 현재 삽입 대상 원석
let availableStones = [];                   // getUsedSizes().stones
let availableSizes  = [];                   // getUsedSizes().sizes
let structureCode   = "";

// ── 초기화 ────────────────────────────────────────────────────
document.addEventListener("DOMContentLoaded", async function() {
  structureCode = getStructureCode();

  if (!structureCode) {
    alert("Structure 정보가 없습니다.");
    location.href = "worksdesk.html";
    return;
  }

  document.getElementById("structureCodeDisplay").textContent =
    "StructureCode: " + structureCode;

  try {
    showLoading("Loading...");

    // 1. 사이즈 + 원석 목록 로드
    const res = await apiPost({ action: "getUsedSizes", structureCode: structureCode });

    // success:false 이거나 빈 배열이면 SizeMaster 폴백
    availableSizes = (res.sizes && res.sizes.length)
      ? res.sizes
      : ["2mm", "4mm", "6mm", "8mm", "10mm", "12mm"];

    availableStones = (res.stones && res.stones.length)
      ? res.stones
      : [];

    // [DJ1] 첫 번째 원석을 기본 활성값으로 설정
    activeStoneName = availableStones.length ? availableStones[0] : "";

    renderStoneSelector();
    renderSizeButtons();

    // 2. 기존 Details 로드
    const existing = await apiPost({ action: "getDetails", structureCode: structureCode });
    if (existing.success && existing.details && existing.details.length) {
      details = [];
      existing.details.forEach(function(d) {
        details.push({ isButton: true });
        // [DJ7] NameKr(대) → nameKr(소) 명시적 매핑
        details.push({
          isButton: false,
          nameKr  : String(d.NameKr || ""),
          size    : String(d.Size   || ""),
          qty     : Number(d.Qty)   || 1
        });
      });
      details.push({ isButton: true });
    }

    renderDetails();

  } catch (err) {
    alert("Init failed: " + err.message);
  } finally {
    hideLoading();
  }
});

// ── Stone 선택 버튼 ───────────────────────────────────────────
// [DJ1] 원석을 클릭해 activeStoneName 변경 → 이후 사이즈 선택 시 해당 원석 사용
function renderStoneSelector() {
  const el = document.getElementById("stoneSelector");
  if (!el) return;
  el.innerHTML = "";

  if (!availableStones.length) {
    el.innerHTML = '<span style="opacity:.5;font-size:13px;">선택된 원석 없음</span>';
    return;
  }

  availableStones.forEach(function(name) {
    const btn = document.createElement("button");
    btn.textContent = name;
    btn.className   = "stone-select-btn" + (name === activeStoneName ? " active-stone" : "");
    btn.onclick     = function() {
      activeStoneName = name;
      renderStoneSelector(); // 하이라이트 갱신
    };
    el.appendChild(btn);
  });
}

// ── Size 버튼 ─────────────────────────────────────────────────
function renderSizeButtons() {
  const container = document.getElementById("sizeButtons");
  if (!container) return;
  container.innerHTML = "";
  availableSizes.forEach(function(size) {
    const btn = document.createElement("button");
    btn.textContent = size;
    btn.className   = "size-btn";
    btn.onclick     = function() { selectSize(size); };
    container.appendChild(btn);
  });
}

// ── Details 렌더링 ────────────────────────────────────────────
function renderDetails() {
  const container = document.getElementById("detailsContainer");
  if (!container) return;
  container.innerHTML = "";

  details.forEach(function(d, idx) {
    const btn = document.createElement("button");

    if (d.isButton) {
      btn.textContent = "+";
      btn.className   = "plus-btn" + (activePlusIndex === idx ? " active-plus" : "");
      // [DJ3] [+] 클릭 시 activePlusIndex 설정 + 하이라이트
      btn.onclick     = (function(i) {
        return function() {
          activePlusIndex = i;
          renderDetails();
        };
      })(idx);
    } else {
      // [DJ9] 중복 textContent 제거 — 단일 할당
      btn.textContent = d.nameKr + "(" + d.size + "," + d.qty + "pcs)";
      btn.className   = "detail-btn";
      btn.title       = "클릭하면 1개 제거 / 0이면 삭제";
      btn.onclick     = (function(i) {
        return function() { removeDetail(i); };
      })(idx);
    }

    container.appendChild(btn);
  });

  updateTotalSize();
  updateLayoutSummary();
}

// ── Size 선택 → Details 삽입 ─────────────────────────────────
function selectSize(size) {
  // [DJ1] activeStoneName 사용 (getSelectedStone() 불사용)
  if (!activeStoneName) {
    alert("원석을 먼저 선택하세요.");
    return;
  }

  // [DJ3] activePlusIndex가 null이면 details 끝 [+] 앞에 삽입(폴백)
  // details의 마지막 요소는 항상 isButton:true이므로 length-1이 마지막 [+] 위치
  let plusIdx = (activePlusIndex !== null)
    ? activePlusIndex
    : details.length - 1;

  // 범위 방어: 항상 isButton인 위치를 가리켜야 함
  if (plusIdx < 0 || plusIdx >= details.length || !details[plusIdx].isButton) {
    plusIdx = details.length - 1;
  }

  const nextIdx = plusIdx + 1;

  // 바로 다음 항목이 동일 nameKr+size이면 qty++ (연속 추가)
  // [DJ4] selectedStone.nameKr → activeStoneName
  const existing = details[nextIdx];
  if (existing && !existing.isButton &&
      existing.nameKr === activeStoneName && existing.size === size) {
    existing.qty++;
  } else {
    // 새 원석 항목 삽입
    details.splice(nextIdx, 0, {
      isButton: false,
      nameKr  : activeStoneName,
      size    : size,
      qty     : 1
    });

    // [DJ5] 새 [+] 삽입: 바로 뒤가 이미 isButton이면 중복 삽입 방지
    const afterNew = details[nextIdx + 1];
    if (!afterNew || !afterNew.isButton) {
      details.splice(nextIdx + 1, 0, { isButton: true });
    }
  }

  // 삽입 후 activePlusIndex를 새 항목 뒤의 [+]로 이동 (연속 삽입 편의)
  activePlusIndex = nextIdx + 1;
  if (activePlusIndex >= details.length || !details[activePlusIndex].isButton) {
    activePlusIndex = details.length - 1;
  }

  renderDetails();
}

// ── Detail 항목 제거 ─────────────────────────────────────────
function removeDetail(idx) {
  if (!details[idx] || details[idx].isButton) return;

  details[idx].qty--;

  if (details[idx].qty <= 0) {
    details.splice(idx, 1); // 원석 항목 삭제

    // [DJ6] 삭제 후 [+][+] 연속 병합
    compactButtons();

    // activePlusIndex 범위 보정
    if (activePlusIndex !== null && activePlusIndex >= details.length) {
      activePlusIndex = details.length - 1;
    }
  }

  renderDetails();
}

// ── [+][+] 연속 병합 ─────────────────────────────────────────
// [DJ5][DJ6] 원석 삭제 또는 삽입 후 연속 isButton 정리
function compactButtons() {
  // 1. 연속 isButton 제거
  let i = 0;
  while (i < details.length - 1) {
    if (details[i].isButton && details[i + 1].isButton) {
      details.splice(i + 1, 1);
      if (activePlusIndex !== null && activePlusIndex > i) activePlusIndex--;
    } else {
      i++;
    }
  }

  // 2. 첫 요소가 isButton이 아니면 앞에 추가
  if (!details.length || !details[0].isButton) {
    details.unshift({ isButton: true });
    if (activePlusIndex !== null) activePlusIndex++;
  }

  // 3. 마지막 요소가 isButton이 아니면 뒤에 추가
  if (!details[details.length - 1].isButton) {
    details.push({ isButton: true });
  }
}

// ── Total Size ────────────────────────────────────────────────
// [DJ8] parseInt → parseFloat (소수점 mm 지원)
function updateTotalSize() {
  const total = typeof calcTotalSize === "function"
    ? calcTotalSize(details)  // layoutFormatter.js 사용 (isButton 필터 내장)
    : details
        .filter(function(d) { return !d.isButton; })
        .reduce(function(sum, d) {
          return sum + (parseFloat(String(d.size).replace("mm", "")) || 0) * (d.qty || 0);
        }, 0);

  const el = document.getElementById("totalSize");
  if (el) el.textContent = "Total Size " + total + "mm";
}

// ── Layout Summary ────────────────────────────────────────────
// [LF1~6 FIX] layoutFormatter.detailsToLayout 사용 (isButton 자동 필터)
function updateLayoutSummary() {
  const el = document.getElementById("layoutSummary");
  if (!el) return;

  el.textContent = typeof detailsToLayout === "function"
    ? detailsToLayout(details)
    : details
        .filter(function(d) { return !d.isButton; })
        .map(function(d) { return d.nameKr + "(" + d.size + "," + d.qty + "pcs)"; })
        .join(" + ");
}

// ── 페이지 이동 ──────────────────────────────────────────────
function goBeforeDesign() { goPage("selectstone.html"); }
function goNextDesign()   { goPage("analysismemo.html"); }

// ── Save ─────────────────────────────────────────────────────
async function saveDesign() {
  if (!structureCode) { alert("StructureCode 없음"); return; }

  const dataItems = details.filter(function(d) { return !d.isButton; });
  if (!dataItems.length) { alert("저장할 항목이 없습니다."); return; }

  const confirmDeduct = confirm("Deduct from stock?");

  try {
    showLoading("Saving...");

    // [DJ7] details 필드: nameKr/size/qty(소) → NameKr/Size/Qty(대) 변환
    const detailsData = dataItems.map(function(d) {
      return { NameKr: d.nameKr, Size: d.size, Qty: d.qty };
    });

    const saveRes = await apiPost({
      action       : "saveDetails",
      structureCode: structureCode,
      details      : detailsData,
      deduct       : confirmDeduct
    });

    if (!saveRes.success) throw new Error(saveRes.error || "Details 저장 실패");

    // [LF1~3 FIX] layoutFormatter 사용 — 항상 올바른 포맷 보장
    const layoutSummary = typeof detailsToLayout === "function"
      ? detailsToLayout(details)
      : dataItems.map(function(d) {
          return d.nameKr + "(" + d.size + "," + d.qty + "pcs)";
        }).join(" + ");

    await apiPost({
      action       : "updateLayoutSummary",
      structureCode: structureCode,
      layoutSummary: layoutSummary
    });

    alert("Saved successfully ✓\n저장: " + saveRes.saved + "건" +
      (confirmDeduct ? "\n차감: " + saveRes.deducted + "건" : ""));

  } catch (err) {
    alert("Save failed: " + err.message);
  } finally {
    hideLoading();
  }
}
