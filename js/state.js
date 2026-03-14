// ============================================================
// ASTERION — STATE
// StructureCode : sessionStorage + URL param (페이지 이동 시 유실 방지)
// Stone 상태    : localStorage 단일 JSON (탭 닫아도 유지)
//                 { nameKr, nameEng, exp, image,
//                   analysis, memo, productImage }
// ============================================================

const _STONE_KEY = "ASTERION_SELECTED_STONE";

// ── StructureCode ──────────────────────────────────────────────

function getStructureCode() {
  const params = new URLSearchParams(window.location.search);
  const code   = params.get("code")
              || sessionStorage.getItem("structureCode")
              || "";
  if (code) sessionStorage.setItem("structureCode", code);
  return code;
}

function setStructureCode(code) {
  if (code) sessionStorage.setItem("structureCode", String(code));
}

// ── Selected Stone (localStorage) ─────────────────────────────

function getSelectedStone() {
  const defaults = {
    nameKr: "", nameEng: "", exp: "", image: "",
    analysis: "", memo: "", productImage: ""
  };
  try {
    const raw = localStorage.getItem(_STONE_KEY);
    if (!raw) return defaults;
    return { ...defaults, ...JSON.parse(raw) };
  } catch (_) { return defaults; }
}

function setSelectedStone(data) {
  if (!data || typeof data !== "object") return;
  const prev = getSelectedStone();
  const merged = {
    ...prev,
    nameKr      : data.NameKr       || data.nameKr       || prev.nameKr,
    nameEng     : data.NameEng      || data.nameEng      || prev.nameEng,
    exp         : data.Exp          || data.exp          || prev.exp,
    image       : data.Image        || data.image        || prev.image,
    analysis    : data.analysis    !== undefined ? data.analysis    : prev.analysis,
    memo        : data.memo        !== undefined ? data.memo        : prev.memo,
    productImage: data.productImage !== undefined ? data.productImage : prev.productImage,
  };
  try { localStorage.setItem(_STONE_KEY, JSON.stringify(merged)); } catch (_) {}
}

function updateStoneField(field, value) {
  const s = getSelectedStone();
  s[field] = value;
  try { localStorage.setItem(_STONE_KEY, JSON.stringify(s)); } catch (_) {}
}

function getSelectedImage(type) {
  const s = getSelectedStone();
  return type === "product" ? (s.productImage || "") : (s.image || "");
}

function setSelectedImage(src, type) {
  updateStoneField(type === "product" ? "productImage" : "image", src || "");
}

function clearSelectedStone() {
  try { localStorage.removeItem(_STONE_KEY); } catch (_) {}
}
