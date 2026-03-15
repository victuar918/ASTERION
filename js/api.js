/**
 * api.js — ASTERION API Layer (전체 페이지 완전판)
 * 로드 순서: config.js → api_contract.js → state.js → api.js
 */
"use strict";

/* ══════════════════════════════════════════════
   1. 핵심 통신 함수
══════════════════════════════════════════════ */
async function apiPost(data, retries) {
  if (retries === undefined) retries = 2;
  var url = (typeof CONFIG !== "undefined" && CONFIG.API_BASE) ? CONFIG.API_BASE : null;
  if (!url || url.indexOf("YOUR_DEPLOYMENT_ID") !== -1) {
    var m = "CONFIG.API_BASE 미설정. config.js를 확인하세요.";
    alert(m); throw new Error(m);
  }
  var lastErr;
  for (var i = 0; i <= retries; i++) {
    try {
      var res  = await fetch(url, { method:"POST", headers:{"Content-Type":"text/plain;charset=utf-8"}, body:JSON.stringify(data) });
      if (!res.ok) throw new Error("HTTP " + res.status);
      var text = await res.text();
      if (!text || !text.trim()) throw new Error("빈 응답");
      var json; try { json = JSON.parse(text); } catch(e) { throw new Error("JSON 파싱 실패: " + text.slice(0,80)); }
      if (json.success === false) throw new Error(json.error || "서버 오류");
      return json;
    } catch(err) {
      lastErr = err;
      console.warn("[API] \"" + data.action + "\" attempt " + (i+1) + " 실패:", err.message);
      if (i < retries) await new Promise(function(r){ setTimeout(r, 600*(i+1)); });
    }
  }
  alert("통신 오류 (" + data.action + "): " + lastErr.message);
  throw lastErr;
}

/* ── apiGet 래퍼 (하위 호환) ── */
async function apiGet(action, params) {
  return await apiPost(Object.assign({ action: action }, params || {}));
}

/* ── 이미지 파일 → byteArray ── */
async function fileToByteArray(file) {
  if (file.size > UPLOAD.MAX_BYTES) { alert("이미지 4MB 이하"); throw new Error("크기 초과"); }
  if (UPLOAD.ALLOWED_MIME.indexOf(file.type.toLowerCase()) === -1) { alert("JPG/PNG/WEBP/GIF만 허용"); throw new Error("MIME 오류"); }
  var buf = await file.arrayBuffer();
  return Array.from(new Uint8Array(buf));
}

/* ══════════════════════════════════════════════
   2. worksdesk.html
══════════════════════════════════════════════ */
async function apiGetTaskingList() {
  var k = "taskingList", c = cacheGet(k); if (c) return c;
  var res = await apiPost({ action: A.GET_TASKING_LIST });
  var d = Array.isArray(res.data) ? res.data : [];
  if (d.length) cacheSet(k, d, 30000);
  return d;
}
async function apiUpdateStatus(structureCode, status) {
  cacheClear("taskingList"); cacheClear("archive_" + structureCode);
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
  if (s.length) cacheSet(k, s, 300000);
  return s;
}
async function apiSaveUsedStones(structureCode, stones) {
  cacheClear("usedItems_" + structureCode);
  return await apiPost({ action: A.SAVE_USED_STONES, structureCode: structureCode, stones: stones });
}

/* ══════════════════════════════════════════════
   4. design.html
══════════════════════════════════════════════ */
async function apiGetUsedItems(structureCode) {
  var k = "usedItems_" + structureCode, c = cacheGet(k); if (c) return c;
  var res = await apiPost({ action: A.GET_USED_ITEMS, structureCode: structureCode });
  var r = validateGetUsedItems(res);
  if (r.items.length) cacheSet(k, r.items, 600000);
  return r.items;
}
async function apiGetDetails(structureCode) {
  var k = "details_" + structureCode, c = cacheGet(k); if (c) return c;
  var res = await apiPost({ action: A.GET_DETAILS, structureCode: structureCode });
  var r = validateGetDetails(res);
  cacheSet(k, r.details, 60000);
  return r.details;
}
async function apiSaveDesign(structureCode, details, deduct) {
  cacheClear("details_" + structureCode);
  var res = await apiPost({
    action: A.SAVE_DETAILS, structureCode: structureCode,
    details: details.map(function(d){ return { nameKr:d.nameKr, size:d.size, qty:d.qty }; }),
    deduct: deduct === true
  });
  return validateSaveDetails(res);
}
async function apiUpdateLayoutSummary(structureCode, layoutSummary) {
  cacheClear("archive_" + structureCode);
  return await apiPost({ action: A.UPDATE_LAYOUT_SUMMARY, structureCode: structureCode, layoutSummary: String(layoutSummary || "") });
}

/* ══════════════════════════════════════════════
   5. analysismemo.html
══════════════════════════════════════════════ */
async function apiUpdateAnalysisMemo(structureCode, analysis, memo) {
  cacheClear("archive_" + structureCode);
  return await apiPost({ action: A.UPDATE_ANALYSIS_MEMO, structureCode: structureCode, analysis: String(analysis || ""), memo: String(memo || "") });
}
async function apiAppendMemo(structureCode, memoLine) {
  cacheClear("archive_" + structureCode);
  return await apiPost({ action: A.APPEND_MEMO, structureCode: structureCode, memoLine: String(memoLine || "") });
}

/* ══════════════════════════════════════════════
   6. productimage.html
══════════════════════════════════════════════ */
async function apiUploadProductImage(structureCode, file) {
  var ba = await fileToByteArray(file);
  return await apiPost({ action: A.UPLOAD_PRODUCT_IMAGE, structureCode: structureCode, byteArray: ba, mimeType: file.type, fileName: file.name });
}

/* ══════════════════════════════════════════════
   7. booklet.html
══════════════════════════════════════════════ */
async function apiGetBookletData(structureCode) {
  var k = "booklet_" + structureCode, c = cacheGet(k); if (c) return c;
  var res = await apiPost({ action: A.GET_BOOKLET_DATA, structureCode: structureCode });
  if (res.data) cacheSet(k, res.data, 120000);
  return res.data || null;
}
async function apiCreatePdf(structureCode) {
  return await apiPost({ action: A.CREATE_PDF, structureCode: structureCode });
}

/* ══════════════════════════════════════════════
   8. inventory.html / stoneinfo.html
══════════════════════════════════════════════ */
async function apiGetAllStonesFull() {
  var k = "allStonesFull", c = cacheGet(k); if (c) return c;
  var res = await apiPost({ action: A.GET_ALL_STONES_FULL });
  var s = Array.isArray(res.stones) ? res.stones : [];
  if (s.length) cacheSet(k, s, 300000);
  return s;
}
async function apiUpdateStoneInfo(nameKr, nameEng, exp) {
  cacheClear("allStones"); cacheClear("allStonesFull");
  return await apiPost({ action: A.UPDATE_STONE_INFO, data: { nameKr: nameKr, nameEng: nameEng, exp: exp } });
}
async function apiUploadStoneImage(nameKr, nameEng, file) {
  var ba = await fileToByteArray(file);
  cacheClear("allStones"); cacheClear("allStonesFull");
  return await apiPost({ action: A.UPLOAD_STONE_IMAGE, nameKr: nameKr, nameEng: nameEng, byteArray: ba, mimeType: file.type, fileName: file.name });
}

/* ══════════════════════════════════════════════
   9. newlisting.html
══════════════════════════════════════════════ */
async function apiAddStone(nameKr, nameEng, exp) {
  cacheClear("allStones"); cacheClear("allStonesFull");
  return await apiPost({ action: A.ADD_STONE, data: { nameKr: nameKr, nameEng: nameEng, exp: exp } });
}

/* ══════════════════════════════════════════════
   10. invenmanage.html
══════════════════════════════════════════════ */
async function apiGetStoneInventory(nameKr) {
  var k = "inv_" + nameKr, c = cacheGet(k); if (c) return c;
  var res = await apiPost({ action: A.GET_STONE_INVENTORY, nameKr: nameKr });
  var inv = Array.isArray(res.inventory) ? res.inventory : [];
  if (inv.length) cacheSet(k, inv, 60000);
  return inv;
}
async function apiGetSizes() {
  var k = "sizes", c = cacheGet(k); if (c) return c;
  var res = await apiPost({ action: A.GET_SIZES });
  var s = Array.isArray(res.sizes) ? res.sizes : [];
  if (s.length) cacheSet(k, s, 600000);
  return s;
}
async function apiUpdateStock(nameKr, rows) {
  cacheClear("inv_" + nameKr); cacheClear("inventoryMatrix");
  return await apiPost({ action: A.UPDATE_STOCK, nameKr: nameKr, rows: rows });
}

/* ══════════════════════════════════════════════
   11. forwarding.html
══════════════════════════════════════════════ */
async function apiUpdateForwarding(structureCode, forwardingDate, deliveryCompletedDate) {
  cacheClear("archive_" + structureCode); cacheClear("taskingList");
  return await apiPost({
    action: A.UPDATE_FORWARDING,
    structureCode: structureCode,
    forwardingDate: forwardingDate || "",
    deliveryCompletedDate: deliveryCompletedDate || ""
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
