/**
 * design.js — Structure Design 페이지 로직
 * 의존: config.js → api_contract.js → state.js → api.js → (design.html)
 */
"use strict";

var sc            = null;
var details       = [];   // [{nameKr, size, qty}]
var activeInsIdx  = null; // 현재 선택된 [+] 위치 (null=미선택)

/* ──────────────────────────────────────────
   초기화
────────────────────────────────────────── */
document.addEventListener("DOMContentLoaded", async function(){
  sc = requireStructureCode(); if(!sc) return;
  document.getElementById("code-disp").textContent = sc;

  // 네비
  document.getElementById("b-home").onclick = function(){ clearAndGo("index.html"); };
  document.getElementById("b-back").onclick = function(){ goPage("selectstone.html", sc); };
  document.getElementById("b-save").onclick = onSaveClick;
  document.getElementById("b-next").onclick = function(){ goPage("analysismemo.html", sc); };
  // 팝업
  document.getElementById("pop-yes").onclick = function(){ hidePopup(); execSave(true);  };
  document.getElementById("pop-no").onclick  = function(){ hidePopup(); execSave(false); };
  document.getElementById("popup").addEventListener("click", function(e){ if(e.target===this) hidePopup(); });

  await loadItems();
  renderDetails();
});

/* ──────────────────────────────────────────
   Used Items 로드
────────────────────────────────────────── */
async function loadItems(){
  var el = document.getElementById("sel-list");
  try {
    var items = await apiGetUsedItems(sc);
    if(!items || !items.length){
      el.innerHTML='<div style="padding:12px;font-size:12px;color:rgba(255,180,80,.7)">재고 있는 항목 없음. selectstone에서 원석을 선택하세요.</div>';
      return;
    }
    el.innerHTML="";
    items.forEach(function(item){
      var row=document.createElement("div"); row.className="stone-row";
      var nm=document.createElement("span"); nm.className="stone-name"; nm.textContent=item.nameKr; nm.title=item.nameKr;
      row.appendChild(nm);
      (item.sizes||[]).forEach(function(sz){
        var b=document.createElement("button"); b.className="btn-size"; b.textContent=formatSize(sz);
        b.addEventListener("click",function(){ onSizeClick(item.nameKr, sz, b); });
        row.appendChild(b);
      });
      el.appendChild(row);
    });
  } catch(err){
    el.innerHTML='<div style="padding:12px;font-size:12px;color:rgba(220,80,80,.8)">로드 실패: '+esc(err.message)+'</div>';
  }
}

/* ──────────────────────────────────────────
   Size 클릭
────────────────────────────────────────── */
function onSizeClick(nameKr, size, btn){
  if(activeInsIdx === null){
    // [+] 미선택: 버튼들을 잠깐 강조
    document.querySelectorAll(".btn-plus").forEach(function(b){ b.style.borderColor="rgba(220,80,80,.8)"; });
    setTimeout(function(){ document.querySelectorAll(".btn-plus").forEach(function(b){ b.style.borderColor=""; }); }, 700);
    return;
  }
  var sz = formatSize(size);
  var pos = activeInsIdx;
  var prev = pos > 0 ? details[pos-1] : null;
  var next = pos < details.length ? details[pos] : null;

  if(prev && prev.nameKr===nameKr && prev.size===sz){
    prev.qty++;
    // activeInsIdx 유지 (계속 같은 위치에 추가 가능)
  } else if(next && next.nameKr===nameKr && next.size===sz){
    next.qty++;
    activeInsIdx = pos+1;
  } else {
    details.splice(pos, 0, {nameKr:nameKr, size:sz, qty:1});
    activeInsIdx = pos+1;
  }
  // 시각 피드백
  if(btn){ btn.style.background="rgba(180,140,90,.6)"; setTimeout(function(){ btn.style.background=""; },250); }
  // [+] 자동 해제
  activeInsIdx = null;
  renderDetails();
}

/* ──────────────────────────────────────────
   Details 렌더링
────────────────────────────────────────── */
function renderDetails(){
  var wrap = document.getElementById("details-wrap");
  wrap.innerHTML="";
  wrap.appendChild(makePlusBtn(0));
  details.forEach(function(item, i){
    wrap.appendChild(makeItemBtn(item, i));
    wrap.appendChild(makePlusBtn(i+1));
  });
  renderTotal();
  renderLayout();
}

function makePlusBtn(idx){
  var b=document.createElement("button"); b.className="btn-plus";
  if(activeInsIdx===idx) b.classList.add("active-plus");
  b.textContent="+";
  b.addEventListener("click",function(){
    activeInsIdx = (activeInsIdx===idx) ? null : idx;
    renderDetails();
  });
  return b;
}

function makeItemBtn(item, idx){
  var b=document.createElement("button"); b.className="btn-item";
  b.innerHTML='<span>'+esc(item.nameKr)+'</span><span class="sub">'+esc(item.size)+'×'+item.qty+'pcs</span>';
  b.addEventListener("click",function(){ onItemClick(idx); });
  return b;
}

/* 아이템 클릭: qty-1, 0이면 삭제 */
function onItemClick(idx){
  details[idx].qty--;
  if(details[idx].qty <= 0) details.splice(idx,1);
  renderDetails();
}

/* ──────────────────────────────────────────
   Total Size
────────────────────────────────────────── */
function renderTotal(){
  var total=0;
  details.forEach(function(d){ total += getSizeNumber(d.size)*d.qty; });
  document.getElementById("total-disp").textContent="Total Size "+total+"mm";
}

/* ──────────────────────────────────────────
   Layout for PDF
   Details 배열에서 같은 nameKr은 사이즈 무관 묶어서 표시
   예: 아파타이트(4mm,1pcs) + 아파타이트(8mm,1pcs) → 아파타이트(2pcs)
────────────────────────────────────────── */
function buildLayoutSummary(){
  if(!details.length) return "";
  // Details 배열 순서 기준으로 nameKr이 연속된 경우 묶기
  var groups=[]; // [{nameKr, totalQty}]
  details.forEach(function(d){
    if(groups.length && groups[groups.length-1].nameKr===d.nameKr){
      groups[groups.length-1].totalQty += d.qty;
    } else {
      groups.push({nameKr:d.nameKr, totalQty:d.qty});
    }
  });
  return groups.map(function(g){ return g.nameKr+"("+g.totalQty+"pcs)"; }).join(" - ");
}

function buildDetailsString(){
  // 상세 배열 (A/S용 정확한 순서)
  return details.map(function(d){
    var parts=[];
    for(var i=0;i<d.qty;i++) parts.push(d.nameKr+"("+d.size+",1pcs)");
    return parts.join(" + ");
  }).join(" + ");
}

function renderLayout(){
  document.getElementById("layout-text").textContent = buildLayoutSummary() || "—";
}

/* ──────────────────────────────────────────
   저장
────────────────────────────────────────── */
function onSaveClick(){
  if(!details.length){ alert("Details가 비어있습니다."); return; }
  document.getElementById("popup").classList.add("show");
}
function hidePopup(){ document.getElementById("popup").classList.remove("show"); }

async function execSave(deduct){
  document.getElementById("ld-ov").classList.add("show");
  try {
    await apiSaveDesign(sc, details, deduct);
    await apiUpdateLayoutSummary(sc, buildLayoutSummary());
    document.getElementById("ld-ov").classList.remove("show");
    alert(deduct ? "저장 완료 (재고 차감됨)" : "저장 완료");
  } catch(err){
    document.getElementById("ld-ov").classList.remove("show");
  }
}
