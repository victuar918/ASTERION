/**
 * booklet.js — ASTERION Booklet 미리보기 + PDF 생성
 * 12페이지 구조 (P12 = 금색 공백, 버튼 없음)
 */
"use strict";

var GOLD_PAGES = [1, 2, 11];
var GOLD_BG    = "#f2dfa0";
var WHITE_BG   = "#ffffff";

var sc   = null;
var data = null;

document.addEventListener("DOMContentLoaded", async function(){
  sc = requireStructureCode(); if(!sc) return;
  document.getElementById("code-disp").textContent = sc;

  document.getElementById("b-home").onclick = function(){ clearAndGo("index.html"); };
  document.getElementById("b-back").onclick = function(){ goPage("productimage.html", sc); };
  document.getElementById("b-pdf").onclick  = onCreatePdf;

  document.querySelectorAll(".pg-btn").forEach(function(btn){
    btn.addEventListener("click", function(){
      switchPage(parseInt(btn.getAttribute("data-pg")));
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

async function loadBooklet(){
  showLoading("LOADING...");
  try{
    data = await apiGetBookletData(sc);
    hideLoading();
    if(!data){ document.getElementById("pg1-c").textContent = "데이터를 찾을 수 없습니다"; return; }

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

function renderAll(d){
  renderPage1(d);
  renderPage2(d);
  renderPage3(d);
  renderPage4(d);
  renderPage5(d);
  renderPage6();
  renderPage7(d);
  renderPage8();
  renderPage9();
  renderPage10();
  renderPage11();
}

/* PAGE 1: Cover (금색) */
function renderPage1(d){
  document.getElementById("pg1-c").innerHTML =
    '<div class="pg-cover" style="height:100%">' +
    '<div class="pg-brand">ASTERION</div>' +
    '<div class="pg-sub">The Architecture of Fate</div>' +
    '</div>';
}

/* PAGE 2: Signature Archive (금색) */
function renderPage2(d){
  document.getElementById("pg2-c").innerHTML =
    '<div class="pg-sig">' +
    '<div class="pg-sig-group"><div class="pg-sig-title">ASTERION</div><div class="pg-sig-sub">Signature Archive</div></div>' +
    '<div class="pg-sig-group-no">' +
    '<div class="pg-sig-no">Archive No. <strong>' + esc(d.structureCode||"") + '</strong></div>' +
    '<div class="pg-sig-for">Designed for <strong>' + esc(d.guestName||"—") + '</strong></div>' +
    '</div>' +
    '<div class="pg-sig-quote"><em>Beyond science,<br>We architect life\'s possibilities.</em></div>' +
    '<div class="pg-sig-auth"><em>ASTERION<br>Architectural Authority</em></div>' +
    '</div>';
}

/* PAGE 3: Product Image + Layout Summary (Structure Design) */
function renderPage3(d){
  var imgUrl  = d.productImage || "";
  var imgHtml = imgUrl
    ? '<img src="' + convertDriveUrl(imgUrl) + '" class="pg-product-img" alt="Product"/>'
    : '<div class="pg-no-img">이미지 없음</div>';
  var layout = esc(d.layoutSummary || "").replace(/\n/g, "<br>");
  document.getElementById("pg3-c").innerHTML =
    '<div class="pg-struct" style="justify-content:flex-start;padding-top:16px;">' +
    '<div class="pg-section-title">Structure Design</div>' +
    '<div class="pg-product-frame" style="width:140px;height:140px;margin:0 auto 10px;">' + imgHtml + '</div>' +
    '<div class="pg-product-code" style="text-align:center;margin-bottom:10px;">Archive No. ' + esc(d.structureCode||"") + '</div>' +
    '<div class="pg-struct-layout">' + layout + '</div>' +
    '</div>';
}

/* PAGE 4: Gemstone Description */
function renderPage4(d){
  var stones = Array.isArray(d.stones) ? d.stones : [];
  var rows = "";
  for(var i=0; i<Math.max(stones.length,1); i++){
    var s = stones[i] || {};
    var imgHtml = s.image
      ? '<img src="' + convertDriveUrl(s.image) + '" class="stone-img" alt=""/>'
      : '<div class="stone-img-empty"></div>';
    rows += '<tr><td class="stone-td-img">' + imgHtml + '</td>' +
      '<td class="stone-td-name"><div class="stone-kr">' + esc(s.nameKr||"") + '</div><div class="stone-eng">' + esc(s.nameEng||"") + '</div></td>' +
      '<td class="stone-td-exp">' + esc(s.exp||"").replace(/\n/g,"<br>") + '</td></tr>';
  }
  document.getElementById("pg4-c").innerHTML =
    '<div class="pg-gem"><div class="pg-gem-title">Gemstone Description</div>' +
    '<table class="stone-table"><tbody>' + rows + '</tbody></table></div>';
}

/* PAGE 5: Analysis (전체 단독 페이지) */
function renderPage5(d){
  document.getElementById("pg5-c").innerHTML =
    '<div class="pg-struct">' +
    '<div class="pg-section-title">Analysis</div>' +
    '<div class="pg-analysis" style="flex:1;overflow-y:auto;">' + esc(d.analysis||"—").replace(/\n/g,"<br>") + '</div>' +
    '</div>';
}

/* PAGE 6: Core/Phase Structure 안내 [신규] */
function renderPage6(){
  document.getElementById("pg6-c").innerHTML =
    '<div class="pg-phil">' +
    '<p style="font-size:15px;font-family:serif;color:#c9a84c;text-align:center;letter-spacing:1px;margin-bottom:12px;">Structure Type</p>' +
    '<p><strong>ASTERION</strong> 구조는 단일 기능이 아닌<br>상태에 따라 다르게 작동하는<br>두 가지 구조로 이루어집니다.</p>' +
    '<p><strong>Core Structure</strong>는<br>개인의 전반적인 흐름을 안정화하고<br>에너지의 기반을 유지하기 위한 구조입니다.<br>이 구조는 장기적인 흐름을 기준으로 설계되며,<br>외부 간섭을 줄이고 내부 균형을 유지하는 역할을 합니다.</p>' +
    '<p><strong>Phase Structure</strong>는<br>특정 시기 또는 특정 상황에 대응하기 위해<br>집중적으로 개입하는 구조입니다.<br>이 구조는 제한된 기간 동안 작동하며,<br>변화가 필요한 구간에서 흐름을 보완하거나<br>특정 방향으로의 작용을 강화합니다.</p>' +
    '<p>각 구조는 목적과 작동 구간이 명확히 구분되며,<br><strong>ASTERION</strong>은 현재 상태에 따라<br>가장 필요한 구조를 우선 적용합니다.</p>' +
    '</div>';
}

/* PAGE 7: 현재 적용된 Structure Type [신규] */
function renderPage7(d){
  var type       = d.structureType  || "";
  var phaseStart = d.phaseStartDate || "";
  var phaseEnd   = d.phaseEndDate   || "";
  var phaseBlock = "";
  if(type === "Phase Structure" && phaseStart && phaseEnd){
    phaseBlock =
      '<p style="font-weight:600;color:#c9a84c;margin-bottom:2px;">Structure 유효 구간</p>' +
      '<p style="font-size:12px;color:#1a2e5a;margin-bottom:12px;">' + esc(phaseStart) + ' ~ ' + esc(phaseEnd) + '</p>';
  }
  document.getElementById("pg7-c").innerHTML =
    '<div class="pg-phil">' +
    '<p style="font-size:15px;font-family:serif;color:#c9a84c;text-align:center;letter-spacing:1px;margin-bottom:12px;">현재 적용된 Structure Type</p>' +
    '<p style="font-size:14px;font-weight:700;color:#1a2e5a;margin-bottom:10px;">' + esc(type||"—") + '</p>' +
    phaseBlock +
    '<p><strong>Phase Structure</strong>인 경우<br>유효 구간이 종료된 이후에도 구조를 유지할 경우<br>다음과 같은 작용이 발생할 수 있습니다.</p>' +
    '<p style="padding-left:10px;color:#555;">· 잔류 에너지의 비효율적 순환<br>· 현재 상태와의 불일치로 인한 에너지 간섭<br>· 흐름의 균형 저하</p>' +
    '<p><strong>ASTERION</strong> 구조는<br>특정 시점의 흐름을 기준으로 설계된 작동 구조입니다.<br>역할이 종료된 이후에는 유지를 권장하지 않습니다.</p>' +
    '</div>';
}

/* PAGE 8: Evolution Process [신규] */
function renderPage8(){
  document.getElementById("pg8-c").innerHTML =
    '<div class="pg-phil">' +
    '<p style="font-size:15px;font-family:serif;color:#c9a84c;text-align:center;letter-spacing:1px;margin-bottom:12px;">Evolution Process</p>' +
    '<p><strong>Evolution Process</strong>는 현재 상태를 기준으로<br>구조를 재해석하고 전환하는 과정입니다.</p>' +
    '<p>Phase Structure의 유효 구간이 얼마 남지 않았거나,<br>Core Structure를 사용 중이더라도<br>어느 순간 삶의 급격한 전환을 인지하신 경우 등<br>다양한 사유에 의해 신청하실 수 있습니다.</p>' +
    '<p>신청 이후 현재 상태에 대한 분석이 먼저 진행됩니다.<br>분석 결과 기존 구조가 여전히 유효하고<br>안정적으로 작동하고 있다고 판단될 경우,<br><strong>ASTERION</strong>은 추가 전환 대신<br>현재 구조의 유지를 안내드립니다.</p>' +
    '<p>이것은 서비스의 거절이 아니라,<br>현재 상태에 가장 적합한 판단을 제공하는 것입니다.</p>' +
    '<p><strong>ASTERION</strong>은 필요하지 않은 구조 변경을 권장하지 않으며,<br>모든 전환은 실제 필요성에 의해 결정됩니다.</p>' +
    '</div>';
}

/* PAGE 9: QR코드 + URL [신규] */
function renderPage9(){
  document.getElementById("pg9-c").innerHTML =
    '<div class="pg-phil" style="align-items:center;text-align:center;">' +
    '<p>이 QR코드는<br><strong>ASTERION Structure</strong> 보유 고객을 위한<br>전용 경로입니다.</p>' +
    '<div style="margin:14px auto;">' +
    '<img src="images/qr_evolution.png" alt="QR" style="width:130px;height:130px;"/>' +
    '</div>' +
    '<p style="font-size:10px;color:#666;">https://naver.me/5RArPUQM</p>' +
    '<p style="margin-top:10px;">공개되지 않은 이 경로를 통해<br><strong>Evolution Process</strong>를 신청하실 수 있습니다.</p>' +
    '<p style="margin-top:8px;">모든 진행은 현재 상태 분석을 기준으로 판단됩니다.</p>' +
    '</div>';
}

/* PAGE 10: 철학 텍스트 (구 P6) */
function renderPage10(){
  document.getElementById("pg10-c").innerHTML =
    '<div class="pg-phil">' +
    '<p>원석과 구조는 단순한 장식이 아닙니다.<br>개인의 에너지 흐름과 공명을 고려한 단 하나의 설계입니다.</p>' +
    '<p>이 설계는 단순한 원석 선택 과정이 아닙니다.<br>개인의 에너지 구조를 분석하고,<br>행성 공명 교차 검증과 배열에서 발생하는<br>수리적 리듬까지 함께 고려합니다.</p>' +
    '<p>원석이 지닌 성질과 배열에서 형성되는 공명이<br>서로 간섭하지 않도록 정교하게 설계합니다.<br>ASTERION은 색을 맞추는 것이 아니라,<br>구조를 정렬합니다.</p>' +
    '<p>고객의 개인적인 에너지 구조를 기반으로 제작되며,<br>각 원석의 조합, 크기, 배열은<br>고객에 따라 설계가 달라집니다.</p>' +
    '</div>';
}

/* PAGE 11: 슬로건 (구 P7, 금색) */
function renderPage11(){
  document.getElementById("pg11-c").innerHTML =
    '<div class="pg-slogan">' +
    '<p>빛은 선택된 이에게만 닿는다.</p>' +
    '<p>보이지 않는 흐름을 설계하다.</p>' +
    '<p>운은 우연이 아니다.</p>' +
    '</div>';
}

/* Drive URL 변환 */
function convertDriveUrl(url){
  if(!url) return "";
  if(url.indexOf("thumbnail") !== -1) return url;
  var m = url.match(/[?&]id=([a-zA-Z0-9_\-]+)/);
  if(m) return "https://drive.google.com/thumbnail?id=" + m[1] + "&sz=w400";
  m = url.match(/\/d\/([a-zA-Z0-9_\-]+)/);
  if(m) return "https://drive.google.com/thumbnail?id=" + m[1] + "&sz=w400";
  return url;
}

/* PDF 생성 */
async function onCreatePdf(){
  showLoading("CREATING PDF...");
  try{
    var res = await apiCreatePdf(sc);
    hideLoading();
    if(res && res.url){
      toastOk("PDF 생성 완료!");
      window.open(res.url, "_blank");
    } else {
      toastErr("PDF 생성 실패: URL을 받지 못했습니다");
    }
  }catch(err){
    hideLoading();
    toastErr("PDF 생성 실패: " + err.message);
  }
}

