// ============================================================
// ASTERION — STATE
// structureCode / selectedStone 상태 관리
// sessionStorage + URL param 이중 저장으로 새로고침에도 유지
// ============================================================

// ── Structure Code ─────────────────────────────────────────────
// 우선순위: URL param → sessionStorage → ""

/**
 * StructureCode를 읽어 sessionStorage에도 저장하고 반환
 * @returns {string} structureCode 또는 ""
 */
function getStructureCode() {
  const params = new URLSearchParams(window.location.search);
  const code   = params.get("code")
              || sessionStorage.getItem("structureCode")
              || "";
  if (code) sessionStorage.setItem("structureCode", code);
  return code;
}

/**
 * StructureCode를 sessionStorage에 저장
 * @param {string} code
 */
function setStructureCode(code) {
  if (code) sessionStorage.setItem("structureCode", code);
}

// ── Selected Stone ─────────────────────────────────────────────

/**
 * 선택된 원석 정보를 sessionStorage에서 읽기
 * @returns {{ nameKr, nameEng, exp, image }}
 */
function getSelectedStone() {
  return {
    nameKr  : sessionStorage.getItem("selectedNameKr")  || "",
    nameEng : sessionStorage.getItem("selectedNameEng") || "",
    exp     : sessionStorage.getItem("selectedExp")     || "",
    image   : sessionStorage.getItem("selectedImage")   || "",
  };
}

/**
 * 선택된 원석 정보를 sessionStorage에 저장
 * Code.gs getAllStones 반환 형식(NameKr 대문자)과 소문자 키 모두 허용
 * @param {{ NameKr?, NameEng?, Exp?, Image?, nameKr?, nameEng?, exp?, image? }} stone
 */
function setSelectedStone(stone) {
  sessionStorage.setItem("selectedNameKr",  stone.NameKr  || stone.nameKr  || "");
  sessionStorage.setItem("selectedNameEng", stone.NameEng || stone.nameEng || "");
  sessionStorage.setItem("selectedExp",     stone.Exp     || stone.exp     || "");
  sessionStorage.setItem("selectedImage",   stone.Image   || stone.image   || "");
}

