// ============================================================
// ASTERION — design.js
// design.html 전용 로직
// 공통: config.js / api.js / state.js / ui.js / layoutFormatter.js
// ============================================================

// ── 전역 상태 ─────────────────────────────────────────────────
// [D6 FIX] activePlusIndex 초기값 null (클릭 전 사이즈 선택 방지)
let details        = [{ isButton: true }];  // 항상 [+]로 시작
let activePlusIdx  = null;                  // 현재 활성 [+] 위치
let activeStone    = null;                  // 현재 선택된 원석 NameKr

// [D1 FIX] state.js 함수 사용 — 구 selectedStone.nameKr 단일 원석 의존 제거
let structureCode  = getStructureCode();
let availableSizes = [];
let selectedStones = []; // selectstone.html에서 저장한 다중 원석 배열

// ── 초기화 ─────────────────────────────────────────────────────
document.addEventListener("DOMContentLoaded", async function() {
  // structureCode 없으면 worksdesk로
  if (!structureCode) {
    alert("Structure 정보가 없습니다.");
    location.href = "worksdesk.html";
    return;
  }

  // [D5 FIX] sessionStorage에서 다중 원석 배열 로드
  try {
    const raw = sessionStorage.getItem("selectedStones");
    selectedStones = raw ? JSON.parse(raw) : [];
  } catch (_) {
    selectedStones = [];
  }

  try {
    showLoading("사이즈 로드 중...");

    // [D1 FIX] getUsedSizes: Archive.UsedStones → Inventory 기반 Size 목록
    const res = await apiPost({ action: "getUsedSizes", structureCode: structureCode });
    if (!res.success) throw new Error(res.error || "사이즈 로드 실패");

    availableSizes = res.sizes || [];

    // [D5 FIX] 서버에서 돌아온 stones로 selectedStones 보완
    if (!selectedStones.length && res.stones && res.stones.length) {
      selectedStones = res.stones;
    }

    // 폴백: 그래도 없으면 SizeMaster 전체 (빈 경우 드물게 발생)
    if (!availableSizes.length) {
      const szRes = await apiGet("getSizes");
      availableSizes = (szRes.success && szRes.sizes) ? szRes.sizes : ["2mm","4mm","6mm","8mm","10mm","12mm"];
    }

    // 첫 번째 원석을 기본 선택
    if (selectedStones.length) activeStone = selectedStones[0];

    renderStoneSelector();
    renderSizeButtons();

    showLoading("이전 디자인 불러오는 중...");

    // [D2 FIX] getDetails: Details 시트 + Inventory 조인, SHEET.DETAILS 상수 사용
    const existing = await apiPost({ action: "getDetails", structureCode: structureCode });
    if (existing.success && existing.details && existing.details.length) {
      details = [];
      existing.details.forEach(function(d) {
        details.push({ isButton: true });
        details.push({ isButton: false, nameKr: d.NameKr, size: d.Size, qty: d.Qty });
      });
      details.push({ isButton: true });
    }

    renderDetails();
  } catch (err) {
    alert("초기화 실패: " + err.message);
  } finally {
    hideLoading();
  }
});

// ── 원석 선택 버튼 렌더링 ─────────────────────────────────────
// [D5 FIX] 다중 원석 지원 — 클릭으로 activeStone 변경
function renderStoneSelector() {
  let el = document.getElementById("stoneSelector");
  if (!el) return; // HTML에 없으면 스킵

  el.innerHTML = "";
  selectedStones.forEach(function(name) {
    const btn = document.createElement("button");
    btn.textContent = name;
    btn.className   = "stone-btn" + (name === activeStone ? " active" : "");
    btn.onclick     = function() {
      activeStone = name;
      renderStoneSelector(); // 하이라이트 갱신
    };
    el.appendChild(btn);
  });
}

// ── Size 버튼 렌더링 ─────────────────────────────────────────
function renderSizeButtons() {
  const container = document.getElementById("sizeButtons");
  if (!container) return;
  container.innerHTML = "";
  availableSizes.forEach(function(size) {
    const btn = document.createElement("button");
    btn.textContent = size;
    btn.onclick     = function() { selectSize(size); };
    container.appendChild(btn);
  });
}

// ── Details 렌더링 ───────────────────────────────────────────
function renderDetails() {
  const container = document.getElementById("detailsContainer");
  if (!container) return;
  container.innerHTML = "";

  details.forEach(function(d, idx) {
    const btn = document.createElement("button");
    if (d.isButton) {
      btn.textContent = "+";
      btn.className   = "plus-btn" + (idx === activePlusIdx ? " active-plus" : "");
      btn.onclick     = (function(i) {
        return function() {
          activePlusIdx = i;
          renderDetails(); // 하이라이트 갱신
        };
      })(idx);
    } else {
      btn.textContent = d.nameKr + "(" + d.size + "," + d.qty + "pcs)";
      btn.className   = "detail-btn";
      btn.title       = "클릭하면 제거";
      btn.onclick     = (function(i) {
        return function() { removeDetail(i); };
      })(idx);
    }
    container.appendChild(btn);
  });

  updateTotalSize();
  updateLayoutSummary();
}

// ── [+] 클릭 후 Size 선택 ───────────────────────────────────
// [D5 FIX] activeStone 기반 (다중 원석 지원)
// [D6 FIX] activePlusIdx null 체크
function selectSize(size) {
  // [+] 를 먼저 클릭해야 함
  if (activePlusIdx === null) {
    alert("[+] 버튼을 먼저 클릭하세요.");
    return;
  }
  // 원석이 선택되어 있어야 함
  if (!activeStone) {
    alert("원석을 먼저 선택하세요.");
    return;
  }

  const insertAfter = activePlusIdx; // [+] 위치
  const nextIdx     = insertAfter + 1;
  const existing    = details[nextIdx];

  // 동일 원석+사이즈가 바로 뒤에 있으면 qty 증가
  if (existing && !existing.isButton && existing.nameKr === activeStone && existing.size === size) {
    existing.qty += 1;
  } else {
    // 새 항목 삽입: [원석항목] + [+]
    details.splice(nextIdx, 0,
      { isButton: false, nameKr: activeStone, size: size, qty: 1 },
      { isButton: true }
    );
    // activePlusIdx를 새로 삽입된 [+] 위치로 자동 이동
    activePlusIdx = nextIdx + 1;
  }

  // [D7 FIX] 연속 isButton 병합: [+][+][+] → [+]
  compactButtons();
  renderDetails();
}

// ── 연속 isButton 병합 ───────────────────────────────────────
// [D7 FIX] removeDetail 후 [+][+] 발생 방지
function compactButtons() {
  let i = 0;
  while (i < details.length) {
    if (details[i].isButton && i + 1 < details.length && details[i + 1].isButton) {
      details.splice(i + 1, 1); // 연속된 두 번째 [+] 제거
      // activePlusIdx 조정
      if (activePlusIdx !== null && activePlusIdx > i) activePlusIdx--;
    } else {
      i++;
    }
  }
  // 마지막이 [+]가 아니면 추가
  if (!details.length || !details[details.length - 1].isButton) {
    details.push({ isButton: true });
  }
  // 첫 번째가 [+]가 아니면 앞에 추가
  if (!details[0].isButton) {
    details.unshift({ isButton: true });
    if (activePlusIdx !== null) activePlusIdx++;
  }
}

// ── Detail 항목 제거 ─────────────────────────────────────────
function removeDetail(idx) {
  if (details[idx] && !details[idx].isButton) {
    details.splice(idx, 1);
    // [D7 FIX] 제거 후 연속 [+] 병합
    compactButtons();
    // activePlusIdx 범위 조정
    if (activePlusIdx !== null && activePlusIdx >= details.length) {
      activePlusIdx = details.length - 1;
    }
    renderDetails();
  }
}

// ── Total Size 계산 ──────────────────────────────────────────
function updateTotalSize() {
  // layoutFormatter.js calcTotalSize 사용
  const total = typeof calcTotalSize === "function"
    ? calcTotalSize(details)
    : details.filter(function(d) { return !d.isButton; })
        .reduce(function(sum, d) {
          return sum + (parseFloat(String(d.size).replace("mm","")) || 0) * (d.qty || 0);
        }, 0);
  const el = document.getElementById("totalSize");
  if (el) el.textContent = "Total Size " + total + "mm";
}

// ── Layout Summary 계산 ──────────────────────────────────────
function updateLayoutSummary() {
  // layoutFormatter.js detailsToLayout 사용
  const layout = typeof detailsToLayout === "function"
    ? detailsToLayout(details)
    : details.map(function(d) {
        return d.isButton ? "+" : d.nameKr + "(" + d.size + "," + d.qty + "pcs)";
      }).join(" ");
  const el = document.getElementById("layoutSummary");
  if (el) el.textContent = layout;
}

// ── 페이지 이동 ──────────────────────────────────────────────
// [D9 확인] goPage는 ui.js 함수, head에서 ui.js가 먼저 로드됨 → 정상
function goBeforeDesign() { goPage("selectstone.html"); }
function goNextDesign()   { goPage("analysismemo.html"); }

// ── Save 처리 ────────────────────────────────────────────────
async function saveDesign() {
  if (!structureCode) { alert("StructureCode 없음"); return; }

  const detailsOnly = details.filter(function(d) { return !d.isButton; });
  if (!detailsOnly.length) { alert("저장할 디자인이 없습니다."); return; }

  const confirmDeduct = confirm("재고를 차감하시겠습니까?\n(아니오: 디자인만 저장)");

  try {
    showLoading("저장 중...");

    const detailsData = detailsOnly.map(function(d) {
      return { NameKr: d.nameKr, Size: d.size, Qty: d.qty };
    });

    // [D2/D3 FIX] saveDetails — Stock 컬럼 사용, Details 시트 자동 생성
    const saveRes = await apiPost({
      action       : "saveDetails",
      structureCode: structureCode,
      details      : detailsData,
      deduct       : confirmDeduct,
    });
    if (!saveRes.success) throw new Error(saveRes.error || "Details 저장 실패");

    // [D8 FIX] updateLayoutSummaryGs — LayoutSummary 컬럼 자동 생성
    const layoutSummary = typeof detailsToLayout === "function"
      ? detailsToLayout(details)
      : detailsOnly.map(function(d) {
          return d.nameKr + "(" + d.size + "," + d.qty + "pcs)";
        }).join(" + ");

    const layoutRes = await apiPost({
      action       : "updateLayoutSummary",
      structureCode: structureCode,
      layoutSummary: layoutSummary,
    });
    if (!layoutRes.success) {
      // Layout 업데이트 실패는 경고만 (Details 저장은 성공)
      console.warn("LayoutSummary 업데이트 실패:", layoutRes.error);
    }

    alert("저장 완료 ✓\n저장: " + saveRes.saved + "건" +
      (confirmDeduct ? "\n차감: " + saveRes.deducted + "건" : ""));
  } catch (err) {
    alert("저장 실패: " + err.message);
  } finally {
    hideLoading();
  }
}

