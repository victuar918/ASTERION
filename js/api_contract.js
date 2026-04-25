/**
 * api_contract.js — ASTERION API Contract (살아있는 명세서)
 * 로드 순서: config.js → api_contract.js → state.js → api.js
 */
"use strict";

/* ── Action 상수 ─────────────────────────────────────── */
var A = Object.freeze({
  // design.html
  GET_USED_ITEMS        : "getUsedItems",
  /** Details + LayoutSummary + DetailsStr 단일 GAS 콜 통합 저장 */
  SAVE_DESIGN           : "saveDesign",
  SAVE_DETAILS          : "saveDetails",
  UPDATE_LAYOUT_SUMMARY : "updateLayoutSummary",
  GET_DETAILS           : "getDetails",
  // selectstone.html
  GET_ALL_STONES        : "getAllStones",
  SAVE_USED_STONES      : "saveUsedStones",
  // analysismemo.html
  UPDATE_ANALYSIS_MEMO  : "updateAnalysisMemo",
  APPEND_MEMO           : "appendMemo",
  // productimage.html / stoneinfo.html
  UPLOAD_PRODUCT_IMAGE  : "uploadProductImage",
  UPLOAD_STONE_IMAGE    : "uploadStoneImage",
  // booklet.html
  GET_BOOKLET_DATA      : "getBookletData",
  CREATE_PDF            : "createPdf",
  // worksdesk.html / structureindex.html
  GET_TASKING_LIST      : "getTaskingList",
  GET_ARCHIVE_ITEM      : "getArchiveItem",
  UPDATE_STATUS         : "updateStatus",
  // inventory.html / stoneinfo.html / newlisting.html
  GET_ALL_STONES_FULL   : "getAllStonesFull",
  UPDATE_STONE_INFO     : "updateStoneInfo",
  ADD_STONE             : "addStone",
  DELETE_STONE          : "deleteStone",
  // invenmanage.html
  GET_STONE_INVENTORY   : "getStoneInventory",
  GET_SIZES             : "getSizes",
  UPDATE_STOCK          : "updateStock",
  // structuretype.html
  SAVE_STRUCTURE_TYPE   : "saveStructureType",
  // forwarding.html
  UPDATE_FORWARDING     : "updateForwarding",
  // 공통
  GET_INVENTORY         : "getInventory",
  COMPACT_SHEET         : "compactSheet",
  // neworder.html
  GET_ALL_REG_ROWS         : "getAllRegRows",
  GET_STOCK_LIST           : "getStockList",
  CREATE_CODE_AND_REGISTER : "createCodeAndRegister",
  RECREATE_PDF             : "recreatePdf",
});

/* ── Status 유효값 ───────────────────────────────────── */
var STATUS = Object.freeze({
  PENDING    : "Pending",
  APPROVED   : "Approved",
  REJECTED   : "Rejected",
  ARCHIVED   : "Archived",
  TASKING    : "Tasking",
  FORWARDING : "Forwarding",
  AS         : "A/S",
  COMPLETE   : "Complete",
});

/* ── 업로드 제약 ─────────────────────────────────────── */
var UPLOAD = Object.freeze({
  MAX_BYTES   : 4 * 1024 * 1024,
  ALLOWED_MIME: Object.freeze([
    "image/jpeg","image/jpg","image/png","image/webp","image/gif"
  ])
});

/* ── 응답 정규화 함수 ────────────────────────────────── */
function validateGetUsedItems(res) {
  var items = Array.isArray(res && res.items) ? res.items : [];
  return {
    valid: !!(res && res.success),
    items: items.map(function(i){ return {
      nameKr: String(i.nameKr || ""),
      sizes : Array.isArray(i.sizes) ? i.sizes.map(String).filter(Boolean) : []
    }; }).filter(function(i){ return i.nameKr; })
  };
}

function validateGetDetails(res) {
  var raw = Array.isArray(res && res.details) ? res.details : [];
  return {
    valid  : !!(res && res.success),
    details: raw.map(function(d){ return {
      invId : String(d.invId  || d.InvID  || ""),
      nameKr: String(d.nameKr || d.NameKr || ""),
      size  : String(d.size   || d.Size   || ""),
      qty   : Math.max(1, Number(d.qty || d.Qty || 1)),
      seqNo : Number(d.seqNo  || d.SeqNo  || 0),
    }; }).filter(function(d){ return d.nameKr && d.size; })
  };
}

function validateSaveDetails(res) {
  return {
    valid    : !!(res && res.success),
    saved    : Number((res && res.saved)    || 0),
    deducted : Number((res && res.deducted) || 0)
  };
}
