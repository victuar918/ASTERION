// 전역 상태
let details = [{ isButton: true }];
let activePlusIndex = null;
let activeStoneName = "";
let availableStones = [];
let availableSizes = [];
let structureCode = "";

// 초기화
document.addEventListener("DOMContentLoaded", async function() {
  structureCode = getStructureCode();
  if (!structureCode) { alert("Structure 없음"); location.href="worksdesk.html"; return; }
  document.getElementById("structureCodeDisplay").textContent = structureCode;

  try {
    showLoading("Loading...");
    const res = await apiPost({ action:"getUsedSizes", structureCode });
    availableSizes  = res.sizes?.length  ? res.sizes : ["2mm","4mm","6mm","8mm","10mm","12mm"];
    availableStones = res.stones?.length ? res.stones : [];
    activeStoneName = availableStones[0] || "";

    renderStoneSelector();
    renderSizeButtons();

    const existing = await apiPost({ action:"getDetails", structureCode });
    if (existing.success && existing.details?.length) {
      details = [{ isButton:true }];
      existing.details.forEach(d=>{
        details.push({ isButton:false, nameKr:d.NameKr, size:d.Size, qty:Number(d.Qty)||1 });
        details.push({ isButton:true });
      });
    }
    renderDetails();
  } catch(e){ alert("Init failed: "+e.message); }
  finally{ hideLoading(); }
});

// 원석 선택
function renderStoneSelector() {
  const el = document.getElementById("stoneSelector");
  el.innerHTML="";
  if (!availableStones.length) { el.innerHTML='<span style="opacity:.5;font-size:13px;">선택된 원석 없음</span>'; return; }
  availableStones.forEach(name=>{
    const btn = document.createElement("button");
    btn.textContent = name;
    btn.className="stone-select-btn"+(name===activeStoneName?" active-stone":"");
    btn.onclick=()=>{ activeStoneName=name; renderStoneSelector(); };
    el.appendChild(btn);
  });
}

// Size 버튼
function renderSizeButtons() {
  const container = document.getElementById("sizeButtons");
  container.innerHTML="";
  availableSizes.forEach(size=>{
    const btn = document.createElement("button");
    btn.textContent = size;
    btn.className="size-btn";
    btn.onclick=()=>{ selectSize(size); };
    container.appendChild(btn);
  });
}

// Size 선택 → Details 삽입
function selectSize(size) {
  if (!activeStoneName){ alert("원석을 먼저 선택하세요."); return; }

  let plusIdx = activePlusIndex ?? details.length-1;
  if (plusIdx<0||plusIdx>=details.length||!details[plusIdx].isButton) plusIdx=details.length-1;
  const nextIdx = plusIdx+1;
  const existing = details[nextIdx];

  // 연속 동일 NameKr + Size → qty 합산
  if (existing && !existing.isButton && existing.nameKr===activeStoneName && existing.size===size) {
    existing.qty++;
  } else {
    details.splice(nextIdx,0,{ isButton:false, nameKr:activeStoneName, size, qty:1 });
    const afterNew = details[nextIdx+1];
    if (!afterNew || !afterNew.isButton) details.splice(nextIdx+1,0,{isButton:true});
  }

  activePlusIndex = nextIdx+1;
  if (activePlusIndex>=details.length||!details[activePlusIndex].isButton) activePlusIndex=details.length-1;

  renderDetails();
}

// Detail 제거
function removeDetail(idx) {
  if (!details[idx] || details[idx].isButton) return;
  details[idx].qty--;
  if (details[idx].qty<=0) { details.splice(idx,1); compactButtons(); if(activePlusIndex>=details.length) activePlusIndex=details.length-1; }
  renderDetails();
}

// [+][+] 병합
function compactButtons() {
  let i=0;
  while(i<details.length-1){
    if(details[i].isButton && details[i+1].isButton){ details.splice(i+1,1); if(activePlusIndex>i) activePlusIndex--; }
    else i++;
  }
  if(!details.length || !details[0].isButton) { details.unshift({isButton:true}); if(activePlusIndex!==null) activePlusIndex++; }
  if(!details[details.length-1].isButton) details.push({isButton:true});
}

// Details 렌더링
function renderDetails() {
  const container = document.getElementById("detailsContainer");
  container.innerHTML="";
  details.forEach((d,idx)=>{
    const btn = document.createElement("button");
    if(d.isButton){
      btn.textContent="+";
      btn.className="plus-btn"+(activePlusIndex===idx?" active-plus":"");
      btn.onclick=()=>{ activePlusIndex=idx; renderDetails(); };
    } else {
      btn.textContent=d.nameKr+"("+d.size+","+d.qty+"pcs)";
      btn.className="detail-btn";
      btn.title="클릭하면 1개 제거 / 0이면 삭제";
      btn.onclick=()=>{ removeDetail(idx); };
    }
    container.appendChild(btn);
  });
  updateTotalSize();
  updateLayoutSummary();
}

// Total Size
function updateTotalSize(){
  const total = details.filter(d=>!d.isButton).reduce((sum,d)=>sum+(parseFloat(d.size)||0)*d.qty,0);
  document.getElementById("totalSize").textContent="Total Size "+total+"mm";
}

// Layout Summary (동일 NameKr 묶어 Qty 합산, 사이즈 무시)
function updateLayoutSummary(){
  const summary = {};
  details.filter(d=>!d.isButton).forEach(d=>{
    if(summary[d.nameKr]) summary[d.nameKr]+=d.qty;
    else summary[d.nameKr]=d.qty;
  });
  const arr = [];
  for(let k in summary) arr.push(k+"("+summary[k]+"pcs)");
  document.getElementById("layoutSummary").textContent = arr.join(" - ");
}

// 페이지 이동
function goBeforeDesign(){ goPage("selectstone.html"); }
function goNextDesign(){ goPage("analysismemo.html"); }
async function saveDesign(){ /* 이전 saveDesign() 로직 사용 */ }
