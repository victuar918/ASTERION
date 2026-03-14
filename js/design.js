/**
 * design.js — ASTERION Design Page
 *
 * [수정 내역]
 *  DJ-FIX-1: apiGetUsedItems 호출 (GAS getUsedItemsGs와 연동)
 *  DJ-FIX-2: apiSaveDesign(code, details, deduct) 통합 (apiDeductStock 제거)
 *  DJ-FIX-3: details 내부 camelCase → api.js에서 PascalCase 변환 (분리)
 *  DJ-FIX-4: activeInsertIndex null 체크 강화 + 힌트 UI
 *  DJ-FIX-5: 동일 stone+size 인접 시 qty 합산 (UX 안정화)
 *  DJ-FIX-6: 저장 순서 보장: saveDetails → updateLayoutSummary (await 직렬)
 *  DJ-FIX-7: 아이템 버튼 클릭 시 이동/삭제 명확한 모드 분리
 */

"use strict";

// ══════════════════════════════════════════════
// 0. 모듈 상태
// ══════════════════════════════════════════════

let structureCode     = null;  // 현재 StructureCode
let details           = [];    // [{ nameKr, size, qty }] — 내부 camelCase
let activeInsertIndex = null;  // [+] 선택 위치 (null=미선택, number=삽입 위치)

// ══════════════════════════════════════════════
// 1. 초기화
// ══════════════════════════════════════════════

document.addEventListener("DOMContentLoaded", async () => {

  // ── StructureCode 확인 ──────────────────────
  structureCode = requireStructureCode();
  if (!structureCode) return; // worksdesk로 redirect됨

  // ── 네비게이션 버튼 ─────────────────────────
  document.getElementById("btn-home").addEventListener("click", () => {
    window.location.href = "index.html";
  });

  document.getElementById("btn-before").addEventListener("click", () => {
    goPage("selectstone.html");
  });

  document.getElementById("btn-analysis").addEventListener("click", () => {
    if (details.length === 0) {
      // Details 없어도 이동 가능
      goPage("analysismemo.html");
    } else {
      goPage("analysismemo.html");
    }
  });

  document.getElementById("btn-save").addEventListener("click", onSaveClick);

  // ── 팝업 버튼 ──────────────────────────────
  document.getElementById("btn-popup-yes").addEventListener("click", () => {
    hidePopup();
    executeSave(true); // 재고 차감 포함
  });

  document.getElementById("btn-popup-no").addEventListener("click", () => {
    hidePopup();
    executeSave(false); // 저장만
  });

  // 팝업 배경 클릭 닫기
  document.getElementById("popup-overlay").addEventListener("click", (e) => {
    if (e.target === document.getElementById("popup-overlay")) {
      hidePopup();
    }
  });

  // ── Used Items 로드 ─────────────────────────
  await loadUsedItems();

  // ── 초기 Details 렌더링 ([+] 하나만) ────────
  renderDetails();
});

// ══════════════════════════════════════════════
// 2. Used Items 로드
// ══════════════════════════════════════════════

/**
 * [DJ-FIX-1] GAS getUsedItemsGs → per-stone sizes 표시
 * items = [{ nameKr: "자수정", sizes: ["4mm","6mm","8mm"] }]
 */
async function loadUsedItems() {
  const container = document.getElementById("used-container");
  container.innerHTML = '<div style="font-size:12px;color:rgba(255,255,255,0.35);">Loading...</div>';

  try {
    const items = await apiGetUsedItems(structureCode);

    if (!items || items.length === 0) {
      container.innerHTML =
        '<div style="font-size:12px;color:rgba(255,180,100,0.7);">' +
        '재고 있는 항목 없음<br>selectstone에서 원석을 먼저 선택하세요.' +
        '</div>';
      return;
    }

    container.innerHTML = "";

    items.forEach(item => {
      const row    = document.createElement("div");
      row.className = "stone-row";

      // 원석명 레이블
      const nameEl = document.createElement("span");
      nameEl.className   = "stone-name";
      nameEl.textContent = item.nameKr || "?";
      nameEl.title       = item.nameKr || "";
      row.appendChild(nameEl);

      // Size 버튼들
      const sizes = Array.isArray(item.sizes) ? item.sizes : [];
      if (sizes.length === 0) {
        const noStock = document.createElement("span");
        noStock.style.cssText = "font-size:10px;color:rgba(255,255,255,0.3);";
        noStock.textContent   = "재고 없음";
        row.appendChild(noStock);
      } else {
        sizes.forEach(size => {
          const btn = document.createElement("button");
          btn.className   = "btn-size";
          btn.textContent = formatSize(size);
          btn.addEventListener("click", () => {
            onSizeClick(item.nameKr, size, btn);
          });
          row.appendChild(btn);
        });
      }

      container.appendChild(row);
    });

  } catch (err) {
    console.error("[loadUsedItems]", err);
    container.innerHTML =
      '<div style="font-size:12px;color:rgba(220,80,80,0.8);">' +
      '로드 실패. 네트워크를 확인하세요.' +
      '</div>';
  }
}

// ══════════════════════════════════════════════
// 3. Size 버튼 클릭 처리
// ══════════════════════════════════════════════

/**
 * [DJ-FIX-4] activeInsertIndex null이면 힌트만 표시하고 무시
 * [DJ-FIX-5] 인접 아이템과 같은 stone+size면 qty++ (합산)
 *
 * @param {string} nameKr - 원석명
 * @param {string} size   - 사이즈 문자열 (예: "6mm" 또는 "6")
 * @param {HTMLElement} btn - 클릭된 버튼 (시각 피드백용)
 */
function onSizeClick(nameKr, size, btn) {
  // [DJ-FIX-4] 삽입 위치 미선택 시 힌트
  if (activeInsertIndex === null) {
    showHint("먼저 아래 Details에서 [+] 버튼을 눌러 삽입 위치를 선택하세요.");
    flashInsertButtons();
    return;
  }

  const formattedSize = formatSize(size);
  const pos           = activeInsertIndex;

  // ── 인접 아이템 합산 판단 ────────────────────
  // 삽입 위치 바로 앞 아이템 (pos-1 위치)
  const prevItem = pos > 0 ? details[pos - 1] : null;
  // 삽입 위치 바로 뒤 아이템 (pos 위치, 아직 삽입 전이므로 현재 pos)
  const nextItem = pos < details.length ? details[pos] : null;

  if (prevItem && prevItem.nameKr === nameKr && prevItem.size === formattedSize) {
    // 앞 아이템과 동일 → qty++, 위치 이동 없음
    prevItem.qty++;
    // activeInsertIndex 유지 (계속 같은 위치에 추가 가능)
  } else if (nextItem && nextItem.nameKr === nameKr && nextItem.size === formattedSize) {
    // 뒤 아이템과 동일 → qty++, activeInsertIndex를 다음 아이템 뒤로 이동
    nextItem.qty++;
    activeInsertIndex = pos + 1;
  } else {
    // 새 아이템 삽입
    details.splice(pos, 0, { nameKr, size: formattedSize, qty: 1 });
    activeInsertIndex = pos + 1; // 삽입 후 커서를 새 아이템 뒤로 이동
  }

  // 버튼 시각 피드백
  if (btn) {
    btn.classList.add("flash");
    setTimeout(() => btn.classList.remove("flash"), 280);
  }

  clearHint();
  renderDetails();
}

// ══════════════════════════════════════════════
// 4. Details 렌더링
// ══════════════════════════════════════════════

/**
 * Details 영역 전체 재렌더링
 * 구조: [+][item][+][item][+] ...
 *
 * 삽입 모드 (activeInsertIndex != null):
 *   → 아이템 버튼 클릭 시 이동 동작
 *   → 아이템 버튼에 move-target 스타일 표시
 * 일반 모드 (activeInsertIndex == null):
 *   → 아이템 버튼 클릭 시 qty-1 / 0이면 삭제
 */
function renderDetails() {
  const container = document.getElementById("details-scroll");
  container.innerHTML = "";

  const isInsertMode = (activeInsertIndex !== null);

  // 맨 앞 [+]
  container.appendChild(makeInsertBtn(0));

  details.forEach((item, i) => {
    container.appendChild(makeItemBtn(item, i, isInsertMode));
    container.appendChild(makeInsertBtn(i + 1));
  });

  renderTotal();
  renderLayout();
  updateHintForMode(isInsertMode);
}

/**
 * [+] 버튼 생성
 */
function makeInsertBtn(insertIndex) {
  const btn    = document.createElement("button");
  btn.className = "btn-insert";
  btn.textContent = "+";

  if (activeInsertIndex === insertIndex) {
    btn.classList.add("selected");
  }

  btn.addEventListener("click", () => {
    if (activeInsertIndex === insertIndex) {
      // 같은 버튼 재클릭 → 선택 해제
      activeInsertIndex = null;
    } else {
      activeInsertIndex = insertIndex;
    }
    renderDetails();
  });

  return btn;
}

/**
 * Detail 아이템 버튼 생성
 *
 * [DJ-FIX-7] 클릭 동작 모드 분리:
 *   삽입 모드: 아이템을 activeInsertIndex 위치로 이동
 *   일반 모드: qty-- (0이면 삭제)
 */
function makeItemBtn(item, index, isInsertMode) {
  const btn    = document.createElement("button");
  btn.className = "btn-item";

  if (isInsertMode) {
    btn.classList.add("move-target");
  }

  btn.innerHTML =
    `<span>${item.nameKr}</span>` +
    `<span class="item-sub">${item.size} × ${item.qty}pcs</span>`;

  btn.addEventListener("click", () => {
    onItemClick(index);
  });

  return btn;
}

/**
 * [DJ-FIX-7] 아이템 버튼 클릭
 *
 * ▣ 일반 모드 (activeInsertIndex == null):
 *   qty > 1 → qty--
 *   qty == 1 → 아이템 삭제
 *
 * ▣ 삽입 모드 (activeInsertIndex != null):
 *   클릭한 아이템을 activeInsertIndex 위치로 이동
 *   이동 완료 후 activeInsertIndex = null (선택 해제)
 */
function onItemClick(index) {
  if (activeInsertIndex === null) {
    // 일반 모드: qty 감소 / 삭제
    details[index].qty--;
    if (details[index].qty <= 0) {
      details.splice(index, 1);
    }
  } else {
    // 삽입 모드: 이동
    const item = details[index];

    // 먼저 원래 위치에서 제거
    details.splice(index, 1);

    // 제거 후 targetIndex 조정
    // (index가 targetIndex보다 앞이면 제거로 인해 인덱스가 1 줄어듦)
    let target = activeInsertIndex;
    if (index < target) {
      target--;
    }

    // 대상 위치에 삽입
    details.splice(target, 0, item);
    activeInsertIndex = null;
  }

  renderDetails();
}

// ══════════════════════════════════════════════
// 5. Total Size 계산
// ══════════════════════════════════════════════

function renderTotal() {
  let total = 0;
  details.forEach(item => {
    total += getSizeNumber(item.size) * item.qty;
  });

  document.getElementById("total-display").textContent =
    `Total Size ${total}mm`;
}

// ══════════════════════════════════════════════
// 6. Layout for PDF 미리보기
// ══════════════════════════════════════════════

/**
 * Details → PDF용 LayoutSummary 문자열
 * 예: "6mm(자수정) - 4mm(아쿠아마린) - 6mm(자수정)"
 * qty > 1 이면 개별 항목으로 펼쳐서 저장 (Booklet 파싱 편의)
 */
function buildLayoutSummary() {
  if (details.length === 0) return "";

  const parts = [];
  details.forEach(item => {
    for (let i = 0; i < item.qty; i++) {
      parts.push(`${item.size}(${item.nameKr})`);
    }
  });

  return parts.join(" - ");
}

function renderLayout() {
  const summary = buildLayoutSummary();
  document.getElementById("layout-preview").textContent = summary || "—";
}

// ══════════════════════════════════════════════
// 7. 저장 처리
// ══════════════════════════════════════════════

function onSaveClick() {
  if (details.length === 0) {
    alert("Details가 비어있습니다.\n먼저 원석을 추가해주세요.");
    return;
  }
  showPopup();
}

/**
 * [DJ-FIX-6] 저장 순서 직렬 보장
 *   Step 1: saveDetails (+ 선택적 재고 차감) — apiSaveDesign
 *   Step 2: updateLayoutSummary              — apiUpdateLayoutSummary
 *
 * @param {boolean} deduct - true = 재고 차감 포함
 */
async function executeSave(deduct) {
  showLoading();

  try {
    // [DJ-FIX-6] Step 1: Details 저장 (deduct 플래그 전달)
    // [DJ-FIX-2] 단일 apiSaveDesign 호출로 통합 (apiDeductStock 제거)
    await apiSaveDesign(structureCode, details, deduct);

    // [DJ-FIX-6] Step 2: LayoutSummary 저장 (Step 1 완료 후 실행)
    const layoutSummary = buildLayoutSummary();
    await apiUpdateLayoutSummary(structureCode, layoutSummary);

    hideLoading();

    const msg = deduct
      ? "저장 완료 (재고 차감됨)"
      : "저장 완료 (재고 차감 없음)";
    alert(msg);

  } catch (err) {
    hideLoading();
    // apiPost에서 이미 alert 처리됨
    console.error("[executeSave]", err);
  }
}

// ══════════════════════════════════════════════
// 8. UI 헬퍼
// ══════════════════════════════════════════════

function showPopup()  { document.getElementById("popup-overlay").classList.add("show"); }
function hidePopup()  { document.getElementById("popup-overlay").classList.remove("show"); }
function showLoading(){ document.getElementById("loading-overlay").classList.add("show"); }
function hideLoading(){ document.getElementById("loading-overlay").classList.remove("show"); }

function showHint(msg) {
  document.getElementById("insert-hint").textContent = msg || "";
}
function clearHint() {
  document.getElementById("insert-hint").textContent = "";
}

/**
 * 삽입 모드/일반 모드에 따라 힌트 문구 자동 변경
 */
function updateHintForMode(isInsertMode) {
  if (isInsertMode) {
    showHint("▲ Size 버튼을 눌러 선택 위치에 추가하거나, 아이템 버튼을 눌러 이동");
  } else {
    clearHint();
  }
}

/**
 * [DJ-FIX-4] [+] 버튼들을 잠깐 붉은색으로 강조 (삽입 위치 미선택 안내)
 */
function flashInsertButtons() {
  const btns = document.querySelectorAll(".btn-insert");
  btns.forEach(b => b.classList.add("hint-shake"));
  setTimeout(() => {
    btns.forEach(b => b.classList.remove("hint-shake"));
  }, 700);
}
