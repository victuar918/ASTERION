// ============================================================
// ASTERION — Layout Formatter  v2
// Details ↔ Layout 문자열 변환
// design.js / analysismemo.html / PDF 공통 사용
//
// ▸ Details 객체 필드 (design.js 통일 기준):
//     { nameKr, size, qty }  ← 소문자 카멜케이스
//     isButton:true 항목은 자동 필터 제거
//
// ▸ Layout 문자열 (Archive 저장 / UI 표시):
//     "자수정(6mm,2pcs) + 루비(4mm,1pcs)"
//
// ▸ PDF 문자열:
//     "자수정(6mm×2) / 루비(4mm×1)"
// ============================================================

/**
 * Details 배열 → Layout 문자열
 * [LF1] d.name → d.nameKr
 * [LF2] isButton 필터 추가
 * [LF6] 구분자 ' + ' 통일
 *
 * @param {Array} details  [{nameKr, size, qty, isButton?}, ...]
 * @returns {string}
 */
function detailsToLayout(details) {
  if (!Array.isArray(details) || !details.length) return "";
  return details
    .filter(function(d) { return !d.isButton; })          // [LF2]
    .map(function(d) {
      return (d.nameKr || d.name || "") +                  // [LF1]
             "(" + (d.size || "") + "," + (d.qty || 0) + "pcs)";
    })
    .join(" + ");                                          // [LF6]
}

/**
 * Details 배열 → PDF 문자열
 * [LF1] d.name → d.nameKr
 * [LF2] isButton 필터 추가
 *
 * @param {Array} details
 * @returns {string}
 */
function detailsToPDF(details) {
  if (!Array.isArray(details) || !details.length) return "";
  return details
    .filter(function(d) { return !d.isButton; })
    .map(function(d) {
      return (d.nameKr || d.name || "") +
             "(" + (d.size || "") + "×" + (d.qty || 0) + ")";
    })
    .join(" / ");
}

/**
 * Layout 문자열 → Details 배열
 * [LF4] null 체크 추가 — 파싱 불가 항목 스킵
 * [LF5] qty 파싱 명확화
 * [LF6] '+' 및 '|' 구분자 모두 파싱 (구버전 호환)
 *
 * @param {string} layout  "자수정(6mm,2pcs) + 루비(4mm,1pcs)"
 * @returns {Array<{nameKr:string, size:string, qty:number}>}
 */
function layoutToDetails(layout) {
  if (!layout || typeof layout !== "string") return [];

  // [LF6] '+' 와 '|' 구분자 모두 지원
  return layout
    .split(/\s*[+|]\s*/)
    .map(function(part) {
      part = part.trim();
      if (!part) return null;

      // [LF4] null 체크: nameKr(size,qty) 형식 파싱
      const m = part.match(/^(.+?)\((.+)\)$/);
      if (!m) return null;

      const nameKr = m[1].trim();
      const inside = m[2].trim();   // "6mm,2pcs" 또는 "6mm×2"

      // "6mm,2pcs" 형식
      const commaM = inside.match(/^([\d.]+mm),(\d+)(?:pcs)?$/);
      if (commaM) {
        return {
          nameKr: nameKr,
          size  : commaM[1],
          qty   : parseInt(commaM[2].replace("pcs", ""), 10) // [LF5]
        };
      }

      // "6mm×2" 형식 (PDF)
      const crossM = inside.match(/^([\d.]+mm)×(\d+)$/);
      if (crossM) {
        return { nameKr: nameKr, size: crossM[1], qty: parseInt(crossM[2], 10) };
      }

      return null; // [LF4] 파싱 불가 항목 제외
    })
    .filter(function(d) { return d !== null; });
}

/**
 * Details 배열 Total Size(mm) 계산
 * [LF3] d.size가 '6mm' string → parseFloat 처리
 *
 * @param {Array} details
 * @returns {number}
 */
function calcTotalSize(details) {
  if (!Array.isArray(details)) return 0;
  return details
    .filter(function(d) { return !d.isButton; })
    .reduce(function(sum, d) {
      const mm = parseFloat(String(d.size || "0").replace("mm", "")) || 0; // [LF3]
      return sum + mm * (Number(d.qty) || 0);
    }, 0);
}

