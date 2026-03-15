/**
 * booklet.js — Booklet 페이지 로직
 *
 * PDF 4장 B5 가로 좌/우 페이지 매핑:
 *   PDF 1page 좌: 공백  / 우: 미리보기 1
 *   PDF 2page 좌: 미리보기 4 / 우: 미리보기 5
 *   PDF 3page 좌: 미리보기 2 / 우: 미리보기 7
 *   PDF 4page 좌: 미리보기 6 / 우: 미리보기 3
 */
"use strict";

// B5 북클릿 페이지 매핑 상수
var PAGE_MAP = Object.freeze({
  pdf1: { left: null, right: 1 },
  pdf2: { left: 4,    right: 5 },
  pdf3: { left: 2,    right: 7 },
  pdf4: { left: 6,    right: 3 },
});

var sc   = null;
var data = null;  // booklet data

document.addEventListener("DOMContentLoaded", async function(){
  sc = requireStructureCode(); if(!sc) return;
  document.getElementById("code-disp").textContent = sc;

  document.getElementById("b-home").onclick  = function(){ clearAndGo("index.html"); };
  document.getElementById("b-back").onclick  = function(){ goPage("productimage.html", sc); };
  document.getElementById("b-pdf").onclick   = onCreatePdf;

  // 페이지 버튼
  document.querySelectorAll(".pg-btn").forEach(function(btn){
    btn.addEventListener("click", function(){
      var pg = btn.getAttribute("data-pg");
      document.querySelectorAll(".pg-btn").forEach(function(b){ b.classList.remove("active"); });
      document.querySelectorAll(".preview-page").forEach(function(p){ p.classList.remove("active"); });
      btn.classList.add("active");
      var el = document.getElementById("pg" + pg);
      if(el) el.classList.add("active");
    });
  });

  await loadBooklet();
});

async function loadBooklet(){
  document.getElementById("st-msg").textContent = "";
  try {
    data = await apiGetBookletData(sc);
    if(!data){ document.getElementById("st-msg").textContent = "데이터를 찾을 수 없습니다"; return; }
    renderAllPages(data);
  } catch(err){
    document.getElementById("st-msg").textContent = "로드 실패: " + err.message;
  }
}

function renderAllPages(d){
  // PAGE 1 — StructureCode + 성함
  document.getElementById("pg1-content").textContent =
    "Structure Code: " + (d.structureCode || sc) + "\n" +
    "Name: " + (d.guestName || d.GuestName || "—");

  // PAGE 2 — UsedStones의 StoneMaster 정보
  var pg2 = document.getElementById("pg2-content");
  pg2.innerHTML = "";
  var stones = Array.isArray(d.stones) ? d.stones : [];
  stones.forEach(function(s){
    var blk = document.createElement("div"); blk.className = "stone-block";
    blk.innerHTML =
      '<div class="s-name">' + esc(s.nameKr || "") + '</div>' +
      '<div class="s-eng">'  + esc(s.nameEng|| "") + '</div>' +
      (s.exp ? '<div class="s-exp">' + esc(s.exp) + '</div>' : '');
    // 원석 이미지
    if(s.image){ var img=document.createElement("img"); img.src=s.image; img.style.cssText="max-width:80px;border-radius:6px;margin-top:4px"; blk.appendChild(img); }
    pg2.appendChild(blk);
  });

  // PAGE 3 — Product Image
  var pImg = document.getElementById("pg3-img");
  var imgUrl = d.productImage || d.ProductImage || "";
  if(imgUrl){ pImg.src=imgUrl; pImg.classList.add("show"); }

  // PAGE 4 — Layout Summary
  document.getElementById("pg4-content").textContent = d.layoutSummary || d.LayoutSummary || "—";

  // PAGE 5 — Analysis
  document.getElementById("pg5-content").textContent = d.analysis || d.Analysis || "—";

  // PAGE 6 — Memo
  document.getElementById("pg6-content").textContent = d.memo || d.Memo || "—";

  // PAGE 7 — Sign/Name placeholder
  document.getElementById("pg7-content").textContent = d.guestName || d.GuestName || "—";
}

async function onCreatePdf(){
  document.getElementById("ld-ov").classList.add("show");
  document.getElementById("st-msg").textContent = "";
  try {
    var res = await apiCreatePdf(sc);
    document.getElementById("ld-ov").classList.remove("show");
    if(res.url){
      document.getElementById("st-msg").textContent = "PDF 생성 완료!";
      document.getElementById("st-msg").style.color = "rgba(100,220,130,.9)";
    }
  } catch(err){
    document.getElementById("ld-ov").classList.remove("show");
    document.getElementById("st-msg").textContent = "PDF 생성 실패: " + err.message;
  }
}
