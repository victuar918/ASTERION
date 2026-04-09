/**
 * api.js — ASTERION API Layer (완전 수정판)
 * 로드 순서: config.js → api_contract.js → state.js → api.js → ui.js
 *
 * [수정 1] apiSaveDesign → action:"saveDesign" 단일 GAS 콜
 *           (saveDetails + updateLayoutSummary + updateDetailsStr 서버 통합)
 * [수정 2] 캐시 무효화 완전성: 쓰기 시 관련 캐시 전부 무효화
 * [수정 3] apiPost 에러 표시 → toastErr() 사용 (ui.js의 toastErr)
 */
"use strict";

/* ══════════════════════════════════════════════
   내부 헬퍼: 에러 표시
   (ui.js의 toastErr 사용, 미로드 시 alert 폴백)
══════════════════════════════════════════════ */
function _uiErr(msg) {
  if (typeof toastErr === "function") {
    toastErr(msg, 4000);
  } else {
    alert(msg);
  }
}

/* ══════════════════════════════════════════════
   1. 핵심 통신 함수
══════════════════════════════════════════════ */
async function apiPost(data, retries) {
  if (retries === undefined) retries = 2;
  var url = (typeof CONFIG !== "undefined" && CONFIG.API_BASE) ? CONFIG.API_BASE : null;
  if (!url || url.indexOf("YOUR_DEPLOYMENT_ID") !== -1) {
    var m = "CONFIG.API_BASE 미설정. config.js를 확인하세요.";
    _uiErr(m); throw new Error(m);
  }
  var lastErr;
  for (var i = 0; i <= retries; i++) {
    try {
      var res = await fetch(url, {
        method : "POST",
        headers: { "Content-Type": "text/plain;charset=utf-8" },
        body   : JSON.stringify(data)
      });
      if (!res.ok) throw new Error("HTTP " + res.status);
      var text = await res.text();
      if (!text || !text.trim()) throw new Error("빈 응답");
      var json;
      try { json = JSON.parse(text); }
      catch (e) { throw new Error("JSON 파싱 실패: " + text.slice(0, 80)); }
      if (json.success === false) throw new Error(json.error || "서버 오류");
      return json;
    } catch (err) {
      lastErr = err;
      console.warn("[API] \"" + data.action + "\" attempt " + (i + 1) + " 실패:", err.message);
      if (i < retries) await new Promise(function (r) { setTimeout(r, 600 * (i + 1)); });
    }
  }
  var errMsg = "통신 오류 (" + data.action + "): " + lastErr.message;
  _uiErr(errMsg);
  throw lastErr;
}

/* apiGet 래퍼 (하위 호환) */
async function apiGet(action, params) {
  return await apiPost(Object.assign({ action: action }, params || {}));
}

/* 이미지 File → byteArray 변환 */
async function fileToByteArray(file) {
  var buf = await file.arrayBuffer();
  return Array.from(new Uint8Array(buf));
}

/* ══════════════════════════════════════════════
   캐시 무효화 헬퍼 (완전성 보장)
══════════════════════════════════════════════ */
function _clearArchiveCache(structureCode) {
  cacheClear("archive_" + structureCode);
  cacheClear("booklet_"  + structureCode);
  cacheClear("taskingList");
  /* allArchive 캐시도 무효화 (worksdesk 목록) */
  cacheClear("allArchive");
}

function _clearDesignCache(structureCode) {
  cacheClear("details_"  + structureCode);
  cacheClear("usedItems_"+ structureCode);
  _clearArchiveCache(structureCode);
}

function _clearStoneCache() {
  cacheClear("allStones");
  cacheClear("allStonesFull");
  cacheClear("inventoryMatrix");
}

/* ══════════════════════════════════════════════
   2. worksdesk.html
══════════════════════════════════════════════ */
async function apiGetTaskingList() {
  var k = "taskingList", c = cacheGet(k); if (c) return c;
  var res = await apiPost({ action: A.GET_TASKING_LIST });
  var d = Array.isArray(res.data) ? res.data : [];
  if (d.length) cacheSet(k, d, 30000);  /* 30초 캐시 — 실시간성 중요 */
  return d;
}

async function apiUpdateStatus(structureCode, status) {
  _clearArchiveCache(structureCode);
  return await apiPost({ action: A.UPDATE_STATUS, structureCode: structureCode, status: status });
}

async function apiGetArchiveItem(structureCode) {
  var k = "archive_" + structureCode, c = cacheGet(k); if (c) return c;
  var res = await apiPost({ action: A.GET_ARCHIVE_ITEM, structureCode: structureCode });
  if (res.item) cacheSet(k, res.item, 60000);
  return res.item || null;
}

/* ══════════════════════════════════════════════
   3. selectstone.html
══════════════════════════════════════════════ */
async function apiGetAllStones() {
  var k = "allStones", c = cacheGet(k); if (c) return c;
  var res = await apiPost({ action: A.GET_ALL_STONES });
  var s = Array.isArray(res.stones) ? res.stones : [];
  if (s.length) cacheSet(k, s, 300000);  /* 5분 캐시 */
  return s;
}

async function apiSaveUsedStones(structureCode, stones) {
  cacheClear("usedItems_" + structureCode);
  _clearArchiveCache(structureCode);
  return await apiPost({ action: A.SAVE_USED_STONES, structureCode: structureCode, stones: stones });
}

/* ══════════════════════════════════════════════
   4. design.html
══════════════════════════════════════════════ */
async function apiGetUsedItems(structureCode) {
  var k = "usedItems_" + structureCode, c = cacheGet(k); if (c) return c;
  var res = await apiPost({ action: A.GET_USED_ITEMS, structureCode: structureCode });
  var r = validateGetUsedItems(res);
  if (r.items.length) cacheSet(k, r.items, 600000);  /* 10분 캐시 */
  return r.items;
}

async function apiGetDetails(structureCode) {
  var k = "details_" + structureCode, c = cacheGet(k); if (c) return c;
  var res = await apiPost({ action: A.GET_DETAILS, structureCode: structureCode });
  var r = validateGetDetails(res);
  cacheSet(k, r.details, 60000);  /* 1분 캐시 */
  return r.details;
}

/**
 * [수정 1] 단일 GAS 콜로 통합
 * saveDetails + updateLayoutSummary + updateDetailsStr → action:"saveDesign"
 *
 * @param {string}  structureCode
 * @param {Array}   details       - [{nameKr, size, qty}]
 * @param {boolean} deduct        - 재고 차감 여부
 * @param {string}  layoutSummary - PDF 북클릿용 문자열
 * @param {string}  detailsStr    - A/S용 상세 문자열
 */
async function apiSaveDesign(structureCode, details, deduct, layoutSummary, detailsStr) {
  /* [수정 2] 관련 캐시 전부 무효화 */
  _clearDesignCache(structureCode);

  return await apiPost({
    action        : A.SAVE_DESIGN,
    structureCode : structureCode,
    details       : details.map(function (d) {
      return { nameKr: d.nameKr, size: d.size, qty: d.qty };
    }),
    deduct        : deduct === true,
    layoutSummary : String(layoutSummary || ""),
    detailsStr    : String(detailsStr    || "")
  });
}

/* 개별 호출이 필요한 경우를 위한 래퍼 (하위 호환) */
async function apiUpdateLayoutSummary(structureCode, layoutSummary) {
  _clearArchiveCache(structureCode);
  return await apiPost({
    action        : A.UPDATE_LAYOUT_SUMMARY,
    structureCode : structureCode,
    layoutSummary : String(layoutSummary || "")
  });
}

/* ══════════════════════════════════════════════
   5. analysismemo.html
══════════════════════════════════════════════ */
async function apiUpdateAnalysisMemo(structureCode, analysis) {
  _clearArchiveCache(structureCode);
  return await apiPost({
    action        : A.UPDATE_ANALYSIS_MEMO,
    structureCode : structureCode,
    analysis      : String(analysis || "")
  });
}

async function apiAppendMemo(structureCode, memoLine) {
  _clearArchiveCache(structureCode);
  return await apiPost({
    action      : A.APPEND_MEMO,
    structureCode: structureCode,
    memoLine    : String(memoLine || "")
  });
}

/* ══════════════════════════════════════════════
   6. productimage.html
══════════════════════════════════════════════ */
async function apiUploadProductImage(structureCode, file) {
  _clearArchiveCache(structureCode);
  var ba = await fileToByteArray(file);
  return await apiPost({
    action        : A.UPLOAD_PRODUCT_IMAGE,
    structureCode : structureCode,
    byteArray     : ba,
    mimeType      : file.type,
    fileName      : file.name
  });
}

/* ══════════════════════════════════════════════
   7. booklet.html
══════════════════════════════════════════════ */
async function apiGetBookletData(structureCode) {
  var k = "booklet_" + structureCode, c = cacheGet(k); if (c) return c;
  var res = await apiPost({ action: A.GET_BOOKLET_DATA, structureCode: structureCode });
  if (res.data) cacheSet(k, res.data, 120000);  /* 2분 캐시 */
  return res.data || null;
}

async function apiCreatePdf(structureCode) {
  /* PDF 생성 후 관련 캐시 무효화 불필요 (읽기만) */
  return await apiPost({ action: A.CREATE_PDF, structureCode: structureCode });
}

/* ══════════════════════════════════════════════
   8. inventory.html / stoneinfo.html
══════════════════════════════════════════════ */
async function apiGetAllStonesFull() {
  var k = "allStonesFull", c = cacheGet(k); if (c) return c;
  var res = await apiPost({ action: A.GET_ALL_STONES_FULL });
  var s = Array.isArray(res.stones) ? res.stones : [];
  if (s.length) cacheSet(k, s, 300000);  /* 5분 캐시 */
  return s;
}

async function apiUpdateStoneInfo(nameKr, nameEng, exp) {
  _clearStoneCache();
  return await apiPost({
    action: A.UPDATE_STONE_INFO,
    data  : { nameKr: nameKr, nameEng: nameEng, exp: exp }
  });
}

async function apiUploadStoneImage(nameKr, nameEng, file) {
  _clearStoneCache();
  var ba = await fileToByteArray(file);
  return await apiPost({
    action   : A.UPLOAD_STONE_IMAGE,
    nameKr   : nameKr,
    nameEng  : nameEng || nameKr,
    byteArray: ba,
    mimeType : file.type,
    fileName : file.name
  });
}

/* ══════════════════════════════════════════════
   9. newlisting.html
══════════════════════════════════════════════ */
async function apiAddStone(nameKr, nameEng, exp) {
  _clearStoneCache();
  return await apiPost({
    action: A.ADD_STONE,
    data  : { nameKr: nameKr, nameEng: nameEng, exp: exp }
  });
}

/* ══════════════════════════════════════════════
   10. invenmanage.html
══════════════════════════════════════════════ */
async function apiGetStoneInventory(nameKr) {
  var k = "inv_" + nameKr, c = cacheGet(k); if (c) return c;
  var res = await apiPost({ action: A.GET_STONE_INVENTORY, nameKr: nameKr });
  var inv = Array.isArray(res.inventory) ? res.inventory : [];
  if (inv.length) cacheSet(k, inv, 300000);  /* [수정 2] 5분 캐시 (관리자만 수정) */
  return inv;
}

async function apiGetSizes() {
  var k = "sizes", c = cacheGet(k); if (c) return c;
  var res = await apiPost({ action: A.GET_SIZES });
  var s = Array.isArray(res.sizes) ? res.sizes : [];
  if (s.length) cacheSet(k, s, 600000);  /* 10분 캐시 (SizeMaster는 거의 변경 없음) */
  return s;
}

async function apiUpdateStock(nameKr, rows) {
  cacheClear("inv_"          + nameKr);
  cacheClear("inventoryMatrix");
  cacheClear("allStonesFull");  /* [수정 2] allStonesFull도 재고 연관 */
  return await apiPost({ action: A.UPDATE_STOCK, nameKr: nameKr, rows: rows });
}

/* ══════════════════════════════════════════════
   11. forwarding.html
══════════════════════════════════════════════ */
async function apiUpdateForwarding(structureCode, forwardingDate, deliveryCompletedDate) {
  _clearArchiveCache(structureCode);
  return await apiPost({
    action                : A.UPDATE_FORWARDING,
    structureCode         : structureCode,
    forwardingDate        : forwardingDate         || "",
    deliveryCompletedDate : deliveryCompletedDate  || ""
  });
}

/* ══════════════════════════════════════════════
   12. structureindex.html
══════════════════════════════════════════════ */
async function apiGetAllArchive() {
  var k = "allArchive", c = cacheGet(k); if (c) return c;
  var res = await apiPost({ action: A.GET_TASKING_LIST });
  var d = Array.isArray(res.data) ? res.data : [];
  if (d.length) cacheSet(k, d, 60000);
  return d;
}

/* ══════════════════════════════════════════════
   13. structuretype.html
══════════════════════════════════════════════ */
async function apiSaveStructureType(structureCode, structureType, phaseStartDate, phaseEndDate) {
  _clearArchiveCache(structureCode);
  return await apiPost({
    action         : A.SAVE_STRUCTURE_TYPE,
    structureCode  : structureCode,
    structureType  : String(structureType  || ""),
    phaseStartDate : String(phaseStartDate || ""),
    phaseEndDate   : String(phaseEndDate   || "")
  });
}

/* ══════════════════════════════════════════════
   14. neworder.html
══════════════════════════════════════════════ */

/* SignReg / EvReg / PvReg 전체 행 조회 */
async function apiGetAllRegRows() {
  return await apiPost({ action: A.GET_ALL_REG_ROWS });
}

/* StructureCode 생성 + Archive 등록 + PDF 자동 생성 */
async function apiCreateCodeAndRegister(payload) {
  cacheClear("taskingList");
  cacheClear("allArchive");
  return await apiPost(Object.assign({ action: A.CREATE_CODE_AND_REGISTER }, payload));
}

/* PDF 재생성 + TempPDF 갱신 */
async function apiRecreatePdf(structureCode) {
  return await apiPost({ action: A.RECREATE_PDF, structureCode: structureCode });
}

/* ── 하위 호환 래퍼 ────────────────────────────────────────
   이전 버전 HTML에서 apiGetInventory()를 호출하는 경우 대비.
   실제로는 inventory.html이 apiGetAllStones()를 사용하지만,
   GitHub에 구버전 파일이 남아있을 경우를 위한 안전망.
── */
async function apiGetInventory() {
  // inventory.html의 실제 로직은 apiGetAllStones()를 사용.
  // 이 함수는 하위 호환 목적으로 apiGetAllStones를 호출.
  return await apiGetAllStones();
}
