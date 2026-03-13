// ============================================================
// ASTERION — API
// 모든 서버 통신은 이 파일을 통해 처리
// ============================================================

/**
 * GET 요청
 * @param {string} action  - doGet action 파라미터
 * @param {object} params  - 추가 쿼리 파라미터 (key/value)
 * @returns {Promise<object>} 서버 JSON 응답 전체
 * @throws HTTP 오류 시 Error
 */
async function apiGet(action, params) {
  const query = new URLSearchParams({ action });

  // 추가 파라미터 병합 (한글 등 자동 encodeURIComponent)
  if (params && typeof params === "object") {
    Object.entries(params).forEach(function([k, v]) {
      query.set(k, v);
    });
  }

  const res = await fetch(CONFIG.API_BASE + "?" + query.toString());
  if (!res.ok) throw new Error("HTTP " + res.status);
  return await res.json();
}

/**
 * POST 요청 (GAS CORS 호환 — Content-Type: text/plain)
 * Content-Type: application/json → CORS Preflight → GAS OPTIONS 미지원 → 차단
 * Content-Type: text/plain       → Simple Request  → Preflight 없음   → 정상 수신
 * GAS는 Content-Type과 무관하게 e.postData.contents 를 JSON.parse로 파싱 가능
 *
 * @param {object} data - { action, ...payload }
 * @returns {Promise<object>} 서버 JSON 응답 전체
 * @throws HTTP 오류 시 Error
 */
async function apiPost(data) {
  const res = await fetch(CONFIG.API_BASE, {
    method : "POST",
    headers: { "Content-Type": "text/plain" },
    body   : JSON.stringify(data),
  });
  if (!res.ok) throw new Error("HTTP " + res.status);
  return await res.json();
}

