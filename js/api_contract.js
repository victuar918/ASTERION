/**
 * api_contract.js — ASTERION API Contract
 *
 * ════════════════════════════════════════════════════════════
 * 이 파일이 Frontend ↔ Backend 계약서입니다.
 *
 * 역할:
 *  1. ACTION 상수 — 문자열 오타 방지, IDE 자동완성
 *  2. FIELD 상수 — payload 필드명 통일
 *  3. 스키마 주석 — API_SPEC.md의 코드 표현
 *
 * 명명 규칙 (확정):
 *  - payload 전구간 camelCase
 *  - Sheets 컬럼은 PascalCase (GAS 내부에서만 처리)
 *  - Frontend는 이 파일의 상수만 사용, 문자열 직접 입력 금지
 * ════════════════════════════════════════════════════════════
 */

"use strict";

// ══════════════════════════════════════════════
// 1. Action 이름 상수
//    Frontend: A.ACTION_NAME 으로 사용
//    GAS:      ACTIONS.ACTION_NAME 으로 사용 (복사본)
// ══════════════════════════════════════════════

const A = Object.freeze({

  // ── design.html ───────────────────────────
  /** Archive.UsedStones → per-stone sizes 반환 */
  GET_USED_ITEMS        : "getUsedItems",

  /** Details 시트 저장 + 선택적 재고 차감 */
  SAVE_DETAILS          : "saveDetails",

  /** Archive.LayoutSummary 업데이트 */
  UPDATE_LAYOUT_SUMMARY : "updateLayoutSummary",

  /** Details 시트 기존 구성 로드 */
  GET_DETAILS           : "getDetails",

  // ── selectstone.html ──────────────────────
  /** 전체 원석 목록 (doGet) */
  GET_ALL_STONES        : "getAllStones",

  /** Archive.UsedStones 저장 */
  SAVE_USED_STONES      : "saveUsedStones",

  // ── analysismemo.html ─────────────────────
  /** Archive.Analysis / Memo 저장 */
  UPDATE_ANALYSIS_MEMO  : "updateAnalysisMemo",

  // ── productimage.html ─────────────────────
  /** 제품 이미지 업로드 → Archive.ProductImage */
  UPLOAD_PRODUCT_IMAGE  : "uploadProductImage",

  // ── worksdesk.html / structureindex.html ──
  /** Archive 전체 목록 */
  GET_TASKING_LIST      : "getTaskingList",

  /** Archive.Status 변경 */
  UPDATE_STATUS         : "updateStatus",

  // ── newlisting.html / GemstoneInfo.html ───
  /** 원석 단건 등록 */
  ADD_STONE             : "addStone",

  /** 원석 일괄 등록 */
  ADD_STONES            : "addStones",

  /** 원석 소프트/하드 삭제 */
  DELETE_STONE          : "deleteStone",

  /** 원석 이미지 업로드 (fetch 방식) */
  UPLOAD_IMAGE          : "uploadImage",

  /** 원석 정보 수정 */
  UPDATE_STONE_INFO     : "updateStoneInfo",

  // ── StockManage.html ──────────────────────
  /** Inventory 매트릭스 조회 */
  GET_INVENTORY         : "getInventory",

  /** Inventory Stock 업데이트 */
  UPDATE_STOCK          : "updateStock",

  /** 단일 원석 Inventory 조회 (doGet) */
  GET_STONE_INVENTORY   : "getStoneInventory",

  // ── 공통 유틸 ─────────────────────────────
  /** 시트 빈 행 압축 */
  COMPACT_SHEET         : "compactSheet",

});

// ══════════════════════════════════════════════
// 2. Payload 필드 상수
//    payload 필드명 직접 문자열 입력 방지
// ══════════════════════════════════════════════

const F = Object.freeze({
  ACTION          : "action",
  STRUCTURE_CODE  : "structureCode",
  NAME_KR         : "nameKr",
  NAME_ENG        : "nameEng",
  SIZE            : "size",
  QTY             : "qty",
  DEDUCT          : "deduct",
  DETAILS         : "details",
  STONES          : "stones",
  LAYOUT_SUMMARY  : "layoutSummary",
  ANALYSIS        : "analysis",
  MEMO            : "memo",
  STATUS          : "status",
  BYTE_ARRAY      : "byteArray",
  MIME_TYPE       : "mimeType",
  FILE_NAME       : "fileName",
  DATA            : "data",
  SOFT_DELETE     : "softDelete",
  SHEET_NAME      : "sheetName",
  KEY_COL_NAME    : "keyColName",
  OLD_NAME_KR     : "oldNameKr",
  ALL             : "all",
  INV_ID          : "invId",
  SEQ_NO          : "seqNo",
});

// ══════════════════════════════════════════════
// 3. 응답 스키마 (JSDoc 형태 — 런타임 검증용)
//    validate*(response) 함수로 사용
// ══════════════════════════════════════════════

/**
 * getUsedItems 응답 검증
 * @param {Object} res
 * @returns {{ valid: boolean, items: Array }}
 */
function validateGetUsedItems(res) {
  const items = Array.isArray(res && res.items) ? res.items : [];
  return {
    valid: res && res.success && items.length >= 0,
    items: items.map(item => ({
      nameKr: String(item.nameKr || ""),
      sizes : Array.isArray(item.sizes)
        ? item.sizes.map(s => String(s || "")).filter(Boolean)
        : []
    })).filter(item => item.nameKr)
  };
}

/**
 * saveDetails 응답 검증
 * @param {Object} res
 * @returns {{ valid: boolean, saved: number, deducted: number }}
 */
function validateSaveDetails(res) {
  return {
    valid    : !!(res && res.success),
    saved    : Number(res && res.saved    || 0),
    deducted : Number(res && res.deducted || 0)
  };
}

/**
 * getDetails 응답 검증 + camelCase 정규화
 * GAS가 반환한 필드명이 무엇이든 camelCase로 통일
 * @param {Object} res
 * @returns {{ valid: boolean, details: Array }}
 */
function validateGetDetails(res) {
  const raw = Array.isArray(res && res.details) ? res.details : [];
  const details = raw.map(d => ({
    // GAS 응답 필드 camelCase 정규화 (방어적 처리)
    invId  : String(d.invId   || d.InvID   || d.invID   || ""),
    nameKr : String(d.nameKr  || d.NameKr  || ""),
    size   : String(d.size    || d.Size    || ""),
    qty    : Number(d.qty     || d.Qty     || 1),
    seqNo  : Number(d.seqNo   || d.SeqNo   || 0),
  })).filter(d => d.nameKr && d.size);
  return { valid: !!(res && res.success), details };
}

// ══════════════════════════════════════════════
// 4. Status 유효값
// ══════════════════════════════════════════════

const VALID_STATUSES = Object.freeze([
  "Pending",
  "Approved",
  "Rejected",
  "Archived",
  "Tasking",
  "Forwarding",
  "A/S"
]);

// ══════════════════════════════════════════════
// 5. 파일 업로드 제약
// ══════════════════════════════════════════════

const UPLOAD = Object.freeze({
  MAX_BYTES  : 4 * 1024 * 1024, // 4MB
  ALLOWED_MIME: Object.freeze([
    "image/jpeg",
    "image/jpg",
    "image/png",
    "image/webp",
    "image/gif"
  ])
});

