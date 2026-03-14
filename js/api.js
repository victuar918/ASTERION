/**
 * api.js — ASTERION API Layer
 * 로드 순서: config.js → api_contract.js → state.js → api.js
 *
 * [FIX] apiGet() 추가 — worksdesk.html, inventory.html "not defined" 해결
 * [FIX] 모든 페이지 전용 API 함수 완비
 */
"use strict";

// ══════════════════════════════════════════════════════
// 1. 기반 통신 함수
// ══════════════════════════════════════════════════════

async function apiPost(data, retries) {
  if (retries === undefined) retries = 2;
  var url = (typeof CONFIG !== "undefined" && CONFIG.API_BASE) ? CONFIG.API_BASE : null;
  if (!url || url.indexOf("YOUR_DEPLOYMENT_ID") !== -1) {
    var msg0 = "CONFIG.API_BASE 미설정. config.js를 확인하세요.";
    alert(msg0); throw new Error(msg0);
  }
  var lastError;
  for (var attempt = 0; attempt <= retries; attempt++) {
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
      if (json.success === false)
        throw new Error(json.error || "서버 오류 (success:false)");
      return json;
    } catch (err) {
      lastError = err;
      console.warn("[API POST] \"" + data.action + "\" attempt " + (attempt + 1) + " 실패:", err.message);
      if (attempt < retries) await new Promise(function(r){ setTimeout(r, 600 * (attempt + 1)); });
    }
  }
  var msg1 = "통신 오류 (" + data.action + "): " + lastError.message;
  alert(msg1); throw lastError;
}

/**
 * apiGet — GAS doGet 호환 래퍼
 * 실제로는 doPost에 동일 action이 등록되어 있으므로 POST로 처리
 * worksdesk.html, inventory.html, selectstone.html 등 호출 대응
 *
 * @param {string} action
 * @param {Object} [params]
 */
async function apiGet(action, params) {
  var payload = Object.assign({ action: action }, params || {});
  return await apiPost(payload);
}

// ══════════════════════════════════════════════════════
// 2. worksdesk.html
// ══════════════════════════════════════════════════════

async function apiGetTaskingList() {
  var cacheKey = "taskingList";
  var cached   = cacheGet(cacheKey);
  if (cached) return cached;
  var res  = await apiPost({ action: A.GET_TASKING_LIST });
  var list = Array.isArray(res.data) ? res.data : [];
  if (list.length) cacheSet(cacheKey, list, 60000);
  return list;
}

async function apiUpdateStatus(structureCode, status) {
  cacheClear("taskingList");
  return await apiPost({ action: A.UPDATE_STATUS, structureCode: structureCode, status: status });
}

// ══════════════════════════════════════════════════════
// 3. inventory.html
// ══════════════════════════════════════════════════════

async function apiGetAllStones() {
  var cacheKey = "allStones";
  var cached   = cacheGet(cacheKey);
  if (cached) return cached;
  var res    = await apiPost({ action: A.GET_ALL_STONES });
  var stones = Array.isArray(res.stones) ? res.stones : [];
  if (stones.length) cacheSet(cacheKey, stones, 300000);
  return stones;
}

async function apiGetInventory() {
  var cacheKey = "inventoryMatrix";
  var cached   = cacheGet(cacheKey);
  if (cached) return cached;
  var res = await apiPost({ action: A.GET_INVENTORY });
  cacheSet(cacheKey, res, 60000);
  return res;
}

async function apiUpdateStock(stockObj) {
  cacheClear("inventoryMatrix");
  return await apiPost({ action: A.UPDATE_STOCK, data: stockObj });
}

// ══════════════════════════════════════════════════════
// 4. newlisting.html
// ══════════════════════════════════════════════════════

async function apiAddStone(nameKr, nameEng, exp) {
  cacheClear("allStones");
  cacheClear("inventoryMatrix");
  return await apiPost({ action: A.ADD_STONE, data: { nameKr: nameKr, nameEng: nameEng, exp: exp } });
}

async function apiUploadStoneImage(nameKr, file) {
  if (file.size > UPLOAD.MAX_BYTES) {
    alert("이미지는 4MB 이하여야 합니다."); throw new Error("크기 초과");
  }
  if (UPLOAD.ALLOWED_MIME.indexOf(file.type.toLowerCase()) === -1) {
    alert("허용 형식: JPG, PNG, WEBP, GIF"); throw new Error("MIME 오류");
  }
  var buf       = await file.arrayBuffer();
  var byteArray = Array.from(new Uint8Array(buf));
  cacheClear("allStones");
  return await apiPost({
    action   : A.UPLOAD_IMAGE,
    nameKr   : nameKr,
    byteArray: byteArray,
    mimeType : file.type,
    fileName : file.name
  });
}

async function apiDeleteStone(nameKr) {
  cacheClear("allStones");
  cacheClear("inventoryMatrix");
  return await apiPost({ action: A.DELETE_STONE, nameKr: nameKr, softDelete: true });
}

// ══════════════════════════════════════════════════════
// 5. selectstone.html
// ══════════════════════════════════════════════════════

async function apiSaveUsedStones(structureCode, stones) {
  cacheClear("usedItems_" + structureCode);
  return await apiPost({ action: A.SAVE_USED_STONES, structureCode: structureCode, stones: stones });
}

// ══════════════════════════════════════════════════════
// 6. design.html
// ══════════════════════════════════════════════════════

async function apiGetUsedItems(structureCode) {
  var cacheKey = "usedItems_" + structureCode;
  var cached   = cacheGet(cacheKey);
  if (cached) return cached;
  var res    = await apiPost({ action: A.GET_USED_ITEMS, structureCode: structureCode });
  var result = validateGetUsedItems(res);
  if (result.items.length) cacheSet(cacheKey, result.items, 600000);
  return result.items;
}

async function apiSaveDesign(structureCode, details, deduct) {
  cacheClear("details_" + structureCode);
  var res = await apiPost({
    action        : A.SAVE_DETAILS,
    structureCode : structureCode,
    details       : details.map(function(d){ return { nameKr: d.nameKr, size: d.size, qty: d.qty }; }),
    deduct        : deduct === true
  });
  return validateSaveDetails(res);
}

async function apiUpdateLayoutSummary(structureCode, layoutSummary) {
  cacheClear("archive_" + structureCode);
  return await apiPost({ action: A.UPDATE_LAYOUT_SUMMARY, structureCode: structureCode, layoutSummary: String(layoutSummary || "") });
}

async function apiGetDetails(structureCode) {
  var cacheKey = "details_" + structureCode;
  var cached   = cacheGet(cacheKey);
  if (cached) return cached;
  var res    = await apiPost({ action: A.GET_DETAILS, structureCode: structureCode });
  var result = validateGetDetails(res);
  cacheSet(cacheKey, result.details, 60000);
  return result.details;
}

// ══════════════════════════════════════════════════════
// 7. analysismemo.html
// ══════════════════════════════════════════════════════

async function apiUpdateAnalysisMemo(structureCode, analysis, memo) {
  return await apiPost({
    action       : A.UPDATE_ANALYSIS_MEMO,
    structureCode: structureCode,
    analysis     : String(analysis || ""),
    memo         : String(memo     || "")
  });
}

// ══════════════════════════════════════════════════════
// 8. productimage.html
// ══════════════════════════════════════════════════════

async function apiUploadProductImage(structureCode, file) {
  if (file.size > UPLOAD.MAX_BYTES) {
    alert("이미지는 4MB 이하여야 합니다."); throw new Error("크기 초과");
  }
  if (UPLOAD.ALLOWED_MIME.indexOf(file.type.toLowerCase()) === -1) {
    alert("허용 형식: JPG, PNG, WEBP, GIF"); throw new Error("MIME 오류");
  }
  var buf       = await file.arrayBuffer();
  var byteArray = Array.from(new Uint8Array(buf));
  return await apiPost({
    action        : A.UPLOAD_PRODUCT_IMAGE,
    structureCode : structureCode,
    byteArray     : byteArray,
    mimeType      : file.type,
    fileName      : file.name
  });
}
