// ============================================================
// ASTERION — design.js v4
// [+] 클릭 위치에 Size 삽입, 동일 NameKr+Size는 Qty 합산
// ============================================================

// 전역 상태
let details = [{ isButton: true }];      // 항상 [+] 시작
let activePlusIndex = null;              // 현재 선택된 [+] 위치
let structureCode = "";                  // 현재 StructureCode
let availableStones = [];                // 사용 가능한 원석 목록
let availableSizes  = [];                // 사용 가능한 Size 목록

// 현재 삽입 대상 원석
let activeStoneName = "";

// 초기화
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
    const res = await apiPost({ action: "getUsedSizes", structureCode });
    availableSizes  = (res.sizes && res.sizes.length) ? res.sizes : ["2mm","4mm","6mm","8mm","10mm","12mm"];
    availableStones = (res.stones && res.stones.length) ? res.stones : [];

    activeStoneName = availableStones.length ? availableStones[0] : "";

    renderStoneSelector();
    renderSizeButtons();

    // 2. 기존 Details 로드
    const existing = await apiPost({ action: "getDetails", structureCode });
    if (existing.success && existing.details && existing.details.length) {
      details = [];
      existing.details.forEach(d => {
        details.push({ isButton: true });
        details.push({
          isButton: false,
          nameKr: String(d.NameKr || ""),
          size  : String(d.Size   || ""),
          qty   : Number(d.Qty)   || 1
        });
      });
      details.push({ isButton: true });
    }

    renderDetails();

  } catch(err) {
    alert("Init failed: " + err.message);
  } finally {
    hideLoading();
  }
});

// ── 원석 선택 버튼 렌더링
function renderStoneSelector() {
  const el = document.getElementById("stoneSelector");
  if (!el) return;
  el.innerHTML = "";

  if (!availableStones.length) {
    el.innerHTML = '<span style="opacity:.5;font-size:13px;">선택된 원석 없음</span>';
    return;
  }

  availableStones.forEach(name => {
    const btn = document.createElement("button");
    btn.textContent = name;
    btn.className = "stone-select-btn" + (name===activeStoneName ? " active-stone" : "");
    btn.onclick = () => {
      activeStoneName = name;
      renderStoneSelector();
    };
    el.appendChild(btn);
  });
}

// ── Size 버튼 렌더링
function renderSizeButtons() {
  const container = document.getElementById("sizeButtons");
  if (!container) return;
  container.innerHTML = "";

  availableSizes.forEach(size => {
    const btn = document.createElement("button");
    btn.textContent = size;
    btn.className = "size-btn";
    btn.onclick = () => { addDetail(activeStoneName, size); };
    container.appendChild(btn);
  });
}

// ── Details 렌더링
function renderDetails() {
  const container = document.getElementById("detailsContainer");
  if (!container) return;
  container.innerHTML = "";

  details.forEach((d, idx) => {
    const btn = document.createElement("button");
    if (d.isButton) {
      btn.textContent = "+";
      btn.className = "plus-btn" + (activePlusIndex===idx?" active-plus":"");
      btn.onclick = () => { activePlusIndex = idx; renderDetails(); };
    } else {
      btn.textContent = `${d.nameKr}(${d.size},${d.qty}pcs)`;
      btn.className = "detail-btn";
      btn.title = "클릭하면 1개 제거 / 0이면 삭제";
      btn.onclick = () => { removeDetail(idx); };
    }
    container.appendChild(btn);
  });

  updateTotalSize();
  updateLayoutSummary();
}

// ── [+] 클릭 후 Size 선택 → Details 삽입 / Qty 합산
function addDetail(nameKr, size) {
  if (!nameKr) { alert("원석을 선택하세요."); return; }

  // 전체 검색: 동일 NameKr+Size 있으면 qty++
  let merged = false;
  for (let i = 0; i < details.length; i++) {
    const d = details[i];
    if (!d.isButton && d.nameKr===nameKr && d.size===size) {
      d.qty++;
      merged = true;
      break;
    }
  }

  if (!merged) {
    // 삽입 위치 결정
    let plusIdx = (activePlusIndex!==null)?activePlusIndex:details.length-1;
    if (plusIdx<0 || plusIdx>=details.length || !details[plusIdx].isButton) plusIdx = details.length-1;
    const nextIdx = plusIdx+1;

    details.splice(nextIdx, 0, { isButton:false, nameKr, size, qty:1 });

    // 새 [+] 삽입
    const afterNew = details[nextIdx+1];
    if (!afterNew || !afterNew.isButton) details.splice(nextIdx+1, 0, { isButton:true });

    activePlusIndex = nextIdx+1;
  }

  renderDetails();
}

// ── Detail 제거
function removeDetail(idx) {
  if (!details[idx] || details[idx].isButton) return;

  details[idx].qty--;
  if (details[idx].qty<=0) {
    details.splice(idx,1);
    compactButtons();
    if (activePlusIndex!==null && activePlusIndex>=details.length) activePlusIndex = details.length-1;
  }

  renderDetails();
}

// ── 연속 [+] 병합
function compactButtons() {
  let i = 0;
  while(i<details.length-1){
    if(details[i].isButton && details[i+1].isButton){
      details.splice(i+1,1);
      if(activePlusIndex!==null && activePlusIndex>i) activePlusIndex--;
    }else i++;
  }

  if(!details.length || !details[0].isButton) details.unshift({isButton:true});
  if(!details[details.length-1].isButton) details.push({isButton:true});
}

// ── Total Size
function updateTotalSize() {
  const total = details
    .filter(d => !d.isButton)
    .reduce((sum,d) => sum + (parseFloat(String(d.size).replace("mm",""))||0)*(d.qty||0),0);
  const el = document.getElementById("totalSize");
  if(el) el.textContent = "Total Size "+total+"mm";
}

// ── Layout Summary (같은 NameKr 묶어서 Qty 합산)
function updateLayoutSummary() {
  const el = document.getElementById("layoutSummary");
  if(!el) return;

  const layoutArr = [];
  let i=0;
  while(i<details.length){
    const d = details[i];
    if(d.isButton){ i++; continue; }

    let name = d.nameKr;
    let qtySum = d.qty;
    // 연속 NameKr 합산
    let j=i+1;
    while(j<details.length && !details[j].isButton && details[j].nameKr===name){
      qtySum += details[j].qty;
      j++;
    }

    layoutArr.push(`${name}(${qtySum}pcs)`);
    i=j;
  }

  el.textContent = layoutArr.join(" - ");
}

// ── 페이지 이동
function goBeforeDesign(){ goPage("selectstone.html"); }
function goNextDesign(){ goPage("analysismemo.html"); }

// ── Save
async function saveDesign() {
  if(!structureCode){ alert("StructureCode 없음"); return; }
  const dataItems = details.filter(d=>!d.isButton);
  if(!dataItems.length){ alert("저장할 항목이 없습니다."); return; }

  try{
    showLoading("Saving...");
    const detailsData = dataItems.map(d=>({ NameKr:d.nameKr, Size:d.size, Qty:d.qty }));
    const saveRes = await apiPost({
      action:"saveDetails",
      structureCode,
      details: detailsData,
      deduct: confirm("Deduct from stock?")
    });
    if(!saveRes.success) throw new Error(saveRes.error||"Details 저장 실패");
    alert("Saved ✓\n저장:"+saveRes.saved+"건");
  }catch(err){
    alert("Save failed: "+err.message);
  }finally{
    hideLoading();
  }
}
