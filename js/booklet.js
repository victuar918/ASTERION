/**
 * booklet.js — ASTERION Booklet 미리보기 + PDF 생성
 *
 * PDF 4장 B5 가로 좌/우 페이지 매핑:
 *   PDF 1page 좌: 공백  / 우: 미리보기 1
 *   PDF 2page 좌: 미리보기 4 / 우: 미리보기 5
 *   PDF 3page 좌: 미리보기 2 / 우: 미리보기 7
 *   PDF 4page 좌: 미리보기 6 / 우: 미리보기 3
 *
 * 미리보기 배경:
 *   1, 2, 7페이지 → 옅은 금색 (#fdf8ee)
 *   3, 4, 5, 6페이지 → 흰색 (#ffffff)
 */
"use strict";

var PAGE_MAP = Object.freeze({
  pdf1: { left: null, right: 1 },
  pdf2: { left: 4,    right: 5 },
  pdf3: { left: 2,    right: 7 },
  pdf4: { left: 6,    right: 3 },
});

/* 옅은 금색 배경 페이지 */
var GOLD_PAGES = [1, 2, 7];
var GOLD_BG    = "#f2dfa0";
var WHITE_BG   = "#ffffff";

var sc   = null;
var data = null;

/* ══════════════════════════════════════════════
   초기화
══════════════════════════════════════════════ */
document.addEventListener("DOMContentLoaded", async function(){
  sc = requireStructureCode(); if(!sc) return;
  document.getElementById("code-disp").textContent = sc;

  document.getElementById("b-home").onclick  = function(){ clearAndGo("index.html"); };
  document.getElementById("b-back").onclick  = function(){ goPage("productimage.html", sc); };
  document.getElementById("b-pdf").onclick   = onCreatePdf;

  /* 페이지 버튼 클릭 */
  document.querySelectorAll(".pg-btn").forEach(function(btn){
    btn.addEventListener("click", function(){
      var pg = parseInt(btn.getAttribute("data-pg"));
      switchPage(pg);
    });
  });

  await loadBooklet();
});

function switchPage(pg){
  document.querySelectorAll(".pg-btn").forEach(function(b){
    b.classList.toggle("active", parseInt(b.getAttribute("data-pg")) === pg);
  });
  document.querySelectorAll(".preview-page").forEach(function(p){
    p.classList.toggle("active", p.id === "pg"+pg);
  });
}

/* ══════════════════════════════════════════════
   데이터 로드 + 렌더링
══════════════════════════════════════════════ */
async function loadBooklet(){
  showLoading("LOADING...");
  try{
    data = await apiGetBookletData(sc);
    hideLoading();
    if(!data){ document.getElementById("pg1-c").textContent = "데이터를 찾을 수 없습니다"; return; }

    /* 배경색 적용 */
    document.querySelectorAll(".preview-page").forEach(function(el){
      var pg = parseInt(el.id.replace("pg",""));
      el.style.background = GOLD_PAGES.indexOf(pg) !== -1 ? GOLD_BG : WHITE_BG;
    });

    renderAll(data);
  }catch(err){
    hideLoading();
    document.getElementById("pg1-c").textContent = "로드 실패: " + err.message;
  }
}

/* ══════════════════════════════════════════════
   7페이지 렌더링
══════════════════════════════════════════════ */
function renderAll(d){
  renderPage1(d);
  renderPage2(d);
  renderPage3(d);
  renderPage4(d);
  renderPage5(d);
  renderPage6();
  renderPage7();
}

/* ── PAGE 1: Cover (금색 배경) ── */
function renderPage1(d){
  var el = document.getElementById("pg1-c");
  el.innerHTML =
    '<div class="pg-cover" style="height:100%">' +
    '  <div class="pg-brand">ASTERION</div>' +
    '  <div class="pg-sub">The Architecture of Fate</div>' +
    '</div>';
}

/* ── PAGE 2: Signature Archive (금색 배경) ── */
function renderPage2(d){
  var el = document.getElementById("pg2-c");
  el.innerHTML =
    '<div class="pg-sig">' +
    '  <div class="pg-sig-title">ASTERION</div>' +
    '  <div class="pg-sig-sub">Signature Archive</div>' +
    '  <div class="pg-sig-no">Archive No. <strong>' + esc(d.structureCode||"") + '</strong></div>' +
    '  <div class="pg-sig-for">Designed for <strong>' + esc(d.guestName||"—") + '</strong></div>' +
    '  <div class="pg-sig-quote"><em>Beyond science,<br>We architect life\'s possibilities.</em></div>' +
    '  <div class="pg-sig-auth"><em>ASTERION<br>Architectural Authority</em></div>' +
    '</div>';
}

/* ── PAGE 3: Product Image (흰색 배경) ── */
function renderPage3(d){
  var el = document.getElementById("pg3-c");
  var imgUrl = d.productImage || "";
  var imgHtml = imgUrl
    ? '<img src="' + convertDriveUrl(imgUrl) + '" class="pg-product-img" alt="Product"/>'
    : '<div class="pg-no-img">이미지 없음</div>';
  el.innerHTML =
    '<div class="pg-product">' +
    '  <div class="pg-product-frame">' + imgHtml + '</div>' +
    '  <div class="pg-product-code">Archive No. ' + esc(d.structureCode||"") + '</div>' +
    '</div>';
}

/* ── PAGE 4: Gemstone Description (흰색 배경) ── */
function renderPage4(d){
  var el = document.getElementById("pg4-c");
  var stones = Array.isArray(d.stones) ? d.stones : [];
  var rows = "";
  for(var i=0; i<Math.max(stones.length,1); i++){
    var s = stones[i] || {};
    var imgHtml = s.image
      ? '<img src="' + convertDriveUrl(s.image) + '" class="stone-img" alt=""/>'
      : '<div class="stone-img-empty"></div>';
    rows +=
      '<tr>' +
      '  <td class="stone-td-img">' + imgHtml + '</td>' +
      '  <td class="stone-td-name">' +
      '    <div class="stone-kr">' + esc(s.nameKr||"") + '</div>' +
      '    <div class="stone-eng">' + esc(s.nameEng||"") + '</div>' +
      '  </td>' +
      '  <td class="stone-td-exp">' + esc(s.exp||"").replace(/\n/g,"<br>") + '</td>' +
      '</tr>';
  }
  el.innerHTML =
    '<div class="pg-gem">' +
    '  <div class="pg-gem-title">Gemstone Description</div>' +
    '  <table class="stone-table"><tbody>' + rows + '</tbody></table>' +
    '</div>';
}

/* ── PAGE 5: Structure Design + Analysis (흰색 배경) ── */
function renderPage5(d){
  var el = document.getElementById("pg5-c");
  el.innerHTML =
    '<div class="pg-struct">' +
    '  <div class="pg-section-title">Structure Design</div>' +
    '  <div class="pg-struct-code">' + esc(d.structureCode||"") + '</div>' +
    '  <div class="pg-divider"></div>' +
    '  <div class="pg-section-title">Analysis</div>' +
    '  <div class="pg-analysis">' + esc(d.analysis||"—").replace(/\n/g,"<br>") + '</div>' +
    '</div>';
}

/* ── PAGE 6: 철학 텍스트 (흰색 배경) ── */
function renderPage6(){
  var el = document.getElementById("pg6-c");
  el.innerHTML =
    '<div class="pg-phil">' +
    '  <p>원석과 구조는 단순한 장식이 아닙니다.<br>개인의 에너지 흐름과 공명을 고려한 단 하나의 설계입니다.</p>' +
    '  <p>이 설계는 단순한 원석 선택 과정이 아닙니다.<br>개인의 에너지 구조를 분석하고,<br>행성 공명 교차 검증과 배열에서 발생하는<br>수리적 리듬까지 함께 고려합니다.</p>' +
    '  <p>원석이 지닌 성질과 배열에서 형성되는 공명이<br>서로 간섭하지 않도록 정교하게 설계합니다.<br>ASTERION은 색을 맞추는 것이 아니라,<br>구조를 정렬합니다.</p>' +
    '  <p>고객의 개인적인 에너지 구조를 기반으로 제작되며,<br>각 원석의 조합, 크기, 배열은<br>고객에 따라 설계가 달라집니다.</p>' +
    '</div>';
}

/* ── PAGE 7: 슬로건 (금색 배경) ── */
function renderPage7(){
  var el = document.getElementById("pg7-c");
  el.innerHTML =
    '<div class="pg-slogan">' +
    '  <p>빛은 선택된 이에게만 닿는다.</p>' +
    '  <p>보이지 않는 흐름을 설계하다.</p>' +
    '  <p>운은 우연이 아니다.</p>' +
    '</div>';
}

/* ══════════════════════════════════════════════
   Drive URL 변환 (CORS 문제 해결)
══════════════════════════════════════════════ */
function convertDriveUrl(url){
  if(!url) return "";
  if(url.indexOf("thumbnail") !== -1) return url;
  var m = url.match(/[?&]id=([a-zA-Z0-9_\-]+)/);
  if(m) return "https://drive.google.com/thumbnail?id=" + m[1] + "&sz=w400";
  m = url.match(/\/d\/([a-zA-Z0-9_\-]+)/);
  if(m) return "https://drive.google.com/thumbnail?id=" + m[1] + "&sz=w400";
  return url;
}

/* ══════════════════════════════════════════════
   PDF 생성
══════════════════════════════════════════════ */
async function onCreatePdf(){
  showLoading("CREATING PDF...");
  try{
    var res = await apiCreatePdf(sc);
    hideLoading();
    if(res && res.url){
      toastOk("PDF 생성 완료!");
    }
  }catch(err){
    hideLoading();
    toastErr("PDF 생성 실패: " + err.message);
  }
}
