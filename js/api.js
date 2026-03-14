/**
 * api.js — ASTERION API Layer (Contract 기반)
 *
 * 이 파일은 api_contract.js의 A(action 상수)와 F(field 상수)를
 * 사용하므로 반드시 api_contract.js 이후에 로드되어야 합니다.
 *
 * 로드 순서:
 *   config.js → api_contract.js → state.js → api.js
 */

"use strict";

// ══════════════════════════════════════════════
// 1. 핵심 POST 함수
// ══════════════════════════════════════════════

/**
 * GAS에 POST 요청
 * Content-Type: text/plain → CORS preflight 없음
 *
 * @param {Object} data    - { action, ...fields }
 * @param {number} retries - 재시도 횟수 (기본 2)
 */
async function apiPost(data, retries = 2) {
  const url = (typeof CONFIG !== "undefined" && CONFIG.API_BASE)
    ? CONFIG.API_BASE : null;

  if (!url || url.includes("YOUR_DEPLOYMENT_ID")) {
    const msg = "CONFIG.API_BASE 미설정. config.js를 확인하세요.";
    alert(msg);
    throw new Error(msg);
  }

  let lastError;

  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      const res = await fetch(url, {
        method : "POST",
        headers: { "Content-Type": "text/plain;charset=utf-8" },
        body   : JSON.stringify(data)
      });

      if (!res.ok) throw new Error(`HTTP ${res.status}`);

      const text = await res.text();
      if (!text || !text.trim()) throw new Error("빈 응답");

      let json;
      try { json = JSON.parse(text); }
      catch (e) { throw new Error("JSON 파싱 실패: " + text.slice(0, 80)); }

      if (json.success === false)
        throw new Error(json.error || "서버 오류 (success:false)");

      return json;

    } catch (err) {
      lastError = err;
      console.warn(`[API] "${data.action}" attempt ${attempt + 1} 실패:`, err.message);
      if (attempt < retries)
        await new Promise(r => setTimeout(r, 600 * (attempt + 1)));
    }
  }

  const msg = `통신 오류 (${data.action}): ${lastError.message}`;
  alert(msg);
  throw lastError;
}

// ══════════════════════════════════════════════
// 2. design.html 전용
// ══════════════════════════════════════════════

/**
 * 원석별 Size 목록 조회
 * @returns {Array} [{ nameKr, sizes }]
 */
async function apiGetUsedItems(structureCode) {
  const cacheKey = `usedItems_${structureCode}`;
  const cached   = cacheGet(cacheKey);
  if (cached) return cached;

  const res    = await apiPost({ [F.ACTION]: A.GET_USED_ITEMS, [F.STRUCTURE_CODE]: structureCode });
  const result = validateGetUsedItems(res);

  if (result.items.length)
    cacheSet(cacheKey, result.items, 600000);

  return result.items;
}

/**
 * Details 저장 + 선택적 재고 차감 (통합)
 *
 * @param {string}  structureCode
 * @param {Array}   details - [{ nameKr, size, qty }] camelCase
 * @param {boolean} deduct
 */
async function apiSaveDesign(structureCode, details, deduct) {
  cacheClear(`details_${structureCode}`);

  const res = await apiPost({
    [F.ACTION]         : A.SAVE_DETAILS,
    [F.STRUCTURE_CODE] : structureCode,
    [F.DETAILS]        : details.map(d => ({
      [F.NAME_KR] : d.nameKr,
      [F.SIZE]    : d.size,
      [F.QTY]     : d.qty
    })),
    [F.DEDUCT] : deduct === true
  });

  return validateSaveDetails(res);
}

/**
 * LayoutSummary 저장
 */
async function apiUpdateLayoutSummary(structureCode, layoutSummary) {
  cacheClear(`archive_${structureCode}`);

  return await apiPost({
    [F.ACTION]         : A.UPDATE_LAYOUT_SUMMARY,
    [F.STRUCTURE_CODE] : structureCode,
    [F.LAYOUT_SUMMARY] : String(layoutSummary || "")
  });
}

/**
 * 기존 Details 로드 (design.html 재진입 시)
 * @returns {Array} [{ invId, nameKr, size, qty, seqNo }] camelCase 정규화됨
 */
async function apiGetDetails(structureCode) {
  const cacheKey = `details_${structureCode}`;
  const cached   = cacheGet(cacheKey);
  if (cached) return cached;

  const res    = await apiPost({ [F.ACTION]: A.GET_DETAILS, [F.STRUCTURE_CODE]: structureCode });
  const result = validateGetDetails(res);

  cacheSet(cacheKey, result.details, 60000);
  return result.details;
}

// ══════════════════════════════════════════════
// 3. 공용 API 함수
// ══════════════════════════════════════════════

async function apiSaveUsedStones(structureCode, stones) {
  cacheClear(`usedItems_${structureCode}`);
  return await apiPost({
    [F.ACTION]         : A.SAVE_USED_STONES,
    [F.STRUCTURE_CODE] : structureCode,
    [F.STONES]         : stones
  });
}

async function apiUpdateAnalysisMemo(structureCode, analysis, memo) {
  return await apiPost({
    [F.ACTION]         : A.UPDATE_ANALYSIS_MEMO,
    [F.STRUCTURE_CODE] : structureCode,
    [F.ANALYSIS]       : String(analysis || ""),
    [F.MEMO]           : String(memo     || "")
  });
}

async function apiUpdateStatus(structureCode, status) {
  return await apiPost({
    [F.ACTION]         : A.UPDATE_STATUS,
    [F.STRUCTURE_CODE] : structureCode,
    [F.STATUS]         : status
  });
}

async function apiUploadProductImage(structureCode, byteArray, mimeType, fileName) {
  if (byteArray.length > UPLOAD.MAX_BYTES) {
    alert("이미지는 4MB 이하여야 합니다.");
    throw new Error("이미지 크기 초과");
  }
  if (!UPLOAD.ALLOWED_MIME.includes(mimeType.toLowerCase())) {
    alert("허용되지 않는 이미지 형식입니다: " + mimeType);
    throw new Error("MIME 타입 오류");
  }
  return await apiPost({
    [F.ACTION]         : A.UPLOAD_PRODUCT_IMAGE,
    [F.STRUCTURE_CODE] : structureCode,
    [F.BYTE_ARRAY]     : byteArray,
    [F.MIME_TYPE]      : mimeType,
    [F.FILE_NAME]      : fileName
  });
}
