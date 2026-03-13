// ============================================================
// ASTERION — Layout Formatter
// Details ↔ Layout 문자열 변환
// design.html / analysismemo.html / PDF 공통 사용
//
// Details 객체 필드 (design.js와 통일):
//   { nameKr, size, qty }   ← 소문자 카멜케이스
//
// Layout 문자열 형식:
//   "자수정(6mm,3pcs) + 루비(2mm,4pcs)"  ← UI/Archive 저장용
//
// PDF 문자열 형식:
//   "자수정(6mm×3) / 루비(2mm×4)"        ← PDF 출력용
// ============================================================

/**
 * Details 배열 → Layout 문자열 (UI/Archive 저장용)
 * [+] 구분자 포함 (design.js details 배열 그대로 전달 가능)
 *
 * @param {Array} details - [{ isButton?, nameKr, size, qty }, ...]
 * @returns {string}
 */
function detailsToLayout(details) {
  if (!Array.isArray(details) || !details.length) return "";
  return details
    .filter(function(d) { return !d.isButton; })
    .map(function(d) {
      return d.nameKr + "(" + d.size + "," + d.qty + "pcs)";
    })
    .join(" + ");
}

/**
 * Details 배열 → PDF 문자열
 *
 * @param {Array} details
 * @returns {string}
 */
function detailsToPDF(details) {
  if (!Array.isArray(details) || !details.length) return "";
  return details
    .filter(function(d) { return !d.isButton; })
    .map(function(d) {
      return d.nameKr + "(" + d.size + "×" + d.qty + ")";
    })
    .join(" / ");
}

/**
 * Layout 문자열 → Details 배열 (isButton 없이 데이터만)
 * 형식: "자수정(6mm,3pcs) + 루비(2mm,4pcs)"
 *
 * @param {string} layout
 * @returns {Array<{nameKr:string, size:string, qty:number}>}
 *
 * [L1 FIX] match null 체크 추가 — 형식 불일치 항목 스킵
 * [L2 FIX] 필드명 nameKr 통일 (d.name → d.nameKr)
 */
function layoutToDetails(layout) {
  if (!layout || typeof layout !== "string") return [];

  return layout
    .split(/\s*\+\s*/)          // " + " 또는 "+" 구분
    .map(function(part) {
      part = part.trim();
      if (!part) return null;

      const parenMatch = part.match(/^(.+?)\((.+)\)$/); // [L1 FIX] null 체크
      if (!parenMatch) return null;

      const nameKr = parenMatch[1].trim();
      const inside = parenMatch[2].trim();              // "6mm,3pcs" 또는 "6mm×3"

      // "6mm,3pcs" 형식
      const commaMatch = inside.match(/^([\d.]+mm),(\d+)pcs$/);
      if (commaMatch) {
        return { nameKr: nameKr, size: commaMatch[1], qty: parseInt(commaMatch[2], 10) };
      }

      // "6mm×3" 형식 (PDF)
      const crossMatch = inside.match(/^([\d.]+mm)×(\d+)$/);
      if (crossMatch) {
        return { nameKr: nameKr, size: crossMatch[1], qty: parseInt(crossMatch[2], 10) };
      }

      return null; // [L1 FIX] 파싱 불가 항목 제외
    })
    .filter(function(d) { return d !== null; });
}

/**
 * Details 배열에서 Total Size(mm) 계산
 * size = "6mm" → 6 × qty
 *
 * @param {Array} details
 * @returns {number}
 */
function calcTotalSize(details) {
  if (!Array.isArray(details)) return 0;
  return details
    .filter(function(d) { return !d.isButton; })
    .reduce(function(sum, d) {
      const mm = parseFloat(String(d.size).replace("mm", "")) || 0;
      return sum + mm * (d.qty || 0);
    }, 0);
}

