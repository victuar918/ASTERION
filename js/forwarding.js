/**
 * forwarding.js — Forwarding Management 페이지 로직
 *
 * 동작 규칙:
 *  - ForwardingDate만 입력 → Status = Forwarding
 *  - ForwardingDate + DeliveryCompletedDate 모두 입력 → Status = Complete
 *    + 15일 후 SigReg 데이터 삭제 (GAS Time-driven trigger 처리)
 *  - DeliveryCompletedDate만 입력 (ForwardingDate 없음) → 경고
 */
"use strict";

var sc = null;

document.addEventListener("DOMContentLoaded", async function () {
  sc = requireStructureCode();
  if (!sc) return;

  document.getElementById("code-text").textContent = sc;

  /* 네비게이션 */
  document.getElementById("b-home").addEventListener("click", function () {
    clearAndGo("index.html");
  });
  document.getElementById("b-back").addEventListener("click", function () {
    goPage("worksdesk.html", sc);
  });
  document.getElementById("b-update").addEventListener("click", onUpdate);

  /* 날짜 입력 변경 시 상태 힌트 실시간 표시 */
  document.getElementById("fwd-date").addEventListener("change", updateHint);
  document.getElementById("del-date").addEventListener("change", updateHint);

  /* 기존 저장된 날짜 로드 */
  await loadExisting();
});

/* ── 기존 데이터 로드 ──────────────────────────────── */
async function loadExisting() {
  try {
    var item = await apiGetArchiveItem(sc);
    if (!item) return;
    var fd = item.forwardingDate         || item.ForwardingDate         || "";
    var dd = item.deliveryCompletedDate  || item.DeliveryCompletedDate  || "";
    if (fd) document.getElementById("fwd-date").value = fd;
    if (dd) document.getElementById("del-date").value = dd;
    updateHint();
  } catch (e) {
    /* 로드 실패는 무시 — 빈 입력란으로 시작 */
  }
}

/* ── 상태 힌트 업데이트 ────────────────────────────── */
function updateHint() {
  var fd  = document.getElementById("fwd-date").value.trim();
  var dd  = document.getElementById("del-date").value.trim();
  var el  = document.getElementById("status-hint");

  if (fd && dd) {
    el.textContent = "Update 시 Status → Complete";
    el.style.color = "rgba(100,220,130,.7)";
  } else if (fd) {
    el.textContent = "Update 시 Status → Forwarding";
    el.style.color = "rgba(220,188,128,.7)";
  } else if (dd) {
    el.textContent = "⚠ Forwarding Date를 먼저 입력하세요";
    el.style.color = "rgba(220,120,80,.8)";
  } else {
    el.textContent = "";
  }
}

/* ── Update 처리 ───────────────────────────────────── */
async function onUpdate() {
  var fd = document.getElementById("fwd-date").value.trim();
  var dd = document.getElementById("del-date").value.trim();

  /* 검증 */
  if (!fd && !dd) {
    setStatus("날짜를 하나 이상 입력하세요.", "err");
    return;
  }
  if (!fd && dd) {
    setStatus("Forwarding Date를 먼저 입력해야 합니다.", "err");
    return;
  }

  showLoading();
  setStatus("", "");

  try {
    await apiUpdateForwarding(sc, fd, dd);
    hideLoading();

    var newStatus = (fd && dd) ? STATUS.COMPLETE : STATUS.FORWARDING;
    setStatus(
      "저장 완료 → Status: " + newStatus +
      (dd ? "  (15일 후 자동 삭제 예약)" : ""),
      "ok"
    );
    setTimeout(function () { setStatus("", ""); }, 3500);

  } catch (err) {
    hideLoading();
    setStatus("저장 실패: " + err.message, "err");
  }
}

/* ── UI 헬퍼 ───────────────────────────────────────── */
function setStatus(msg, type) {
  var el = document.getElementById("st-msg");
  el.textContent = msg;
  el.className = "st-msg " +
    (type === "err"  ? "st-err"  :
     type === "ok"   ? "st-ok"   :
     type === "info" ? "st-info" : "");
}
function showLoading() { document.getElementById("ld-ov").classList.add("show"); }
function hideLoading() { document.getElementById("ld-ov").classList.remove("show"); }

