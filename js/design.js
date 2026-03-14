// 전역 상태
let details = [{ isButton:true }];
let activePlusIndex = null;
let availableStones = [];
let availableSizes  = [];
let structureCode = "";

// 초기화
document.addEventListener("DOMContentLoaded", async function(){
  structureCode = getStructureCode();
  if(!structureCode){ alert("StructureCode 없음"); location.href="worksdesk.html"; return; }
  document.getElementById("structureCodeDisplay").textContent = "StructureCode: "+structureCode;

  try{
    showLoading("Loading...");

    const res = await apiPost({ action:"getUsedSizes", structureCode });
    availableSizes  = (res.sizes && res.sizes.length)? res.sizes:["2mm","4mm","6mm","8mm","10mm","12mm"];
    availableStones = (res.stones && res.stones.length)? res.stones:[];

    const existing = await apiPost({ action:"getDetails", structureCode });
    if(existing.success && existing.details && existing.details.length){
      details = [];
      existing.details.forEach(d=>{
        details.push({isButton:true});
        details.push({isButton:false,nameKr:d.NameKr,size:d.Size,qty:Number(d.Qty)||1});
      });
      details.push({isButton:true});
    }

    renderStoneSelectorAndSizes();
    renderDetails();
  }catch(err){ alert("Init failed: "+err.message); }
  finally{ hideLoading(); }
});

// NameKr+Size 버튼 렌더링
function renderStoneSelectorAndSizes(){
  const container = document.getElementById("stoneSelector");
  container.innerHTML = "";
  if(!availableStones.length){ container.innerHTML='<span style="opacity:.5;font-size:13px;">선택된 원석 없음</span>'; return; }

  availableStones.forEach(name=>{
    const row = document.createElement("div");

    const nameLabel = document.createElement("span");
    nameLabel.textContent = name;
    nameLabel.className = "stone-name";
    row.appendChild(nameLabel);

    availableSizes.forEach(size=>{
      const btn = document.createElement("button");
      btn.textContent = size;
      btn.className = "size-btn";
      btn.onclick = ()=> addDetail(name,size);
      row.appendChild(btn);
    });

    container.appendChild(row);
  });
}

// Details 렌더링
function renderDetails(){
  const container = document.getElementById("detailsContainer");
  container.innerHTML = "";

  details.forEach((d,idx)=>{
    const btn = document.createElement("button");
    if(d.isButton){
      btn.textContent = "+";
      btn.className = "plus-btn"+(activePlusIndex===idx?" active-plus":"");
      btn.onclick = ()=>{ activePlusIndex=idx; renderDetails(); };
    } else {
      btn.textContent = `${d.nameKr} (${d.size},${d.qty}pcs)`;
      btn.className = "detail-btn";
      btn.title = "클릭하면 1개 제거 / 0이면 삭제";
      btn.onclick = ()=> removeDetail(idx);
    }
    container.appendChild(btn);
  });

  updateTotalSize();
  updateLayoutSummary();
}

// Details 추가 (동일 NameKr+Size 합산)
function addDetail(nameKr,size){
  if(activePlusIndex===null) activePlusIndex = details.length-1;
  let idx = activePlusIndex+1;

  const existing = details[idx];
  if(existing && !existing.isButton && existing.nameKr===nameKr && existing.size===size){
    existing.qty++;
  } else {
    // 삽입 전에 연속된 같은 NameKr+Size 찾기
    let merged=false;
    for(let i=0;i<details.length;i++){
      const d=details[i];
      if(!d.isButton && d.nameKr===nameKr && d.size===size){
        d.qty++;
        merged=true;
        break;
      }
    }
    if(!merged){
      details.splice(idx,0,{isButton:false,nameKr,size,qty:1});
      const after=details[idx+1];
      if(!after || !after.isButton) details.splice(idx+1,0,{isButton:true});
    }
  }

  activePlusIndex = idx+1;
  renderDetails();
}

// Details 제거
function removeDetail(idx){
  if(!details[idx]||details[idx].isButton) return;
  details[idx].qty--;
  if(details[idx].qty<=0){
    details.splice(idx,1);
    compactButtons();
    if(activePlusIndex!==null && activePlusIndex>=details.length) activePlusIndex=details.length-1;
  }
  renderDetails();
}

// [+][+] 연속 병합
function compactButtons(){
  let i=0;
  while(i<details.length-1){
    if(details[i].isButton && details[i+1].isButton){
      details.splice(i+1,1);
      if(activePlusIndex!==null && activePlusIndex>i) activePlusIndex--;
    } else i++;
  }
  if(!details.length||!details[0].isButton){ details.unshift({isButton:true}); if(activePlusIndex!==null) activePlusIndex++; }
  if(!details[details.length-1].isButton) details.push({isButton:true});
}

// Total Size
function updateTotalSize(){
  const total=details.filter(d=>!d.isButton).reduce((sum,d)=>sum+(parseFloat(d.size)||0)*d.qty,0);
  document.getElementById("totalSize").textContent="Total Size "+total+"mm";
}

// Layout Summary (연속 NameKr 합산)
function updateLayoutSummary(){
  const el=document.getElementById("layoutSummary");
  if(!el) return;

  const summary=[];
  let i=0;
  while(i<details.length){
    const d=details[i];
    if(d.isButton){ i++; continue; }
    const name=d.nameKr;
    let qtySum=0,j=i;
    while(j<details.length && !details[j].isButton && details[j].nameKr===name){ qtySum+=details[j].qty; j++; }
    summary.push(`${name} (${qtySum}pcs)`);
    i=j;
  }
  el.textContent=summary.join(" - ");
}

// 페이지 이동
function goBeforeDesign(){ goPage("selectstone.html"); }
function goNextDesign(){ goPage("analysismemo.html"); }

// Save
async function saveDesign(){
  if(!structureCode){ alert("StructureCode 없음"); return; }
  const dataItems = details.filter(d=>!d.isButton);
  if(!dataItems.length){ alert("저장할 항목이 없습니다."); return; }

  const confirmDeduct = confirm("Deduct from stock?");
  try{
    showLoading("Saving...");
    const detailsData = dataItems.map(d=>({NameKr:d.nameKr, Size:d.size, Qty:d.qty}));
    const saveRes = await apiPost({action:"saveDetails", structureCode, details:detailsData, deduct:confirmDeduct});
    if(!saveRes.success) throw new Error(saveRes.error||"Details 저장 실패");

    updateLayoutSummary(); // PDF용 Layout Summary 업데이트
    await apiPost({action:"updateLayoutSummary", structureCode, layoutSummary:document.getElementById("layoutSummary").textContent});

    alert("Saved successfully ✓\n저장: "+saveRes.saved+"건"+(confirmDeduct?`\n차감: ${saveRes.deducted}건`:""));
  }catch(err){ alert("Save failed: "+err.message);}
  finally{ hideLoading(); }
}
