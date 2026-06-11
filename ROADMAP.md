# ASTERION 전체 시스템 현황 및 진행 로드맵 v4.0
> 최종 업데이트: 2026-06-03
> 이 파일이 유일한 정본 문서입니다.

---

## 목차
1. [브랜드 개요 및 핵심 철학](#1-브랜드-개요-및-핵심-철학)
2. [전체 아키텍처](#2-전체-아키텍처)
3. [ASTERION Archive System — GAS Web App](#3-asterion-archive-system--gas-web-app)
4. [BTR (Birth Time Rectification) 파이프라인](#4-btr-birth-time-rectification-파이프라인)
5. [ASTERION AI Evolution Engine (MCP Server)](#5-asterion-ai-evolution-engine-mcp-server)
6. [ASTERION Hub (AI Chat 관제시스템)](#6-asterion-hub-ai-chat-관제시스템)
7. [ASTERION 영상 자동화 앱 (Android)](#7-asterion-영상-자동화-앱-android)
8. [인프라 현황](#8-인프라-현황)
9. [미결 사항 및 보완 필요 항목](#9-미결-사항-및-보완-필요-항목)

---

## 1. 브랜드 개요 및 핵심 철학

ASTERION은 베딕 점성술(Lahiri 아야남샤)과 명리학을 결합한 에너지 공학 기반 분석을 통해 개인화된 원석 팔찌를 제작하는 프리미엄 브랜드다.

**핵심 원칙**
- 색을 맞추는 것이 아니라, 구조를 정렬한다.
- 고객의 개인 에너지 구조를 기반으로 제작하며, 원석의 조합·크기·배열은 고객마다 다르다.
- BTR(Birth Time Rectification)로 개인 표준시를 확정한 후에만 분석 결과물이 생성된다.
- S-Class(세 AI 모두 97점↑ AND critical_issues 없음)를 달성해야만 BTR이 확정된다.

**Structure 유형**
- **Core Structure**: 개인의 에너지 흐름을 장기적으로 안정화. 외부 간섭을 줄이고 내부 균형 유지.
- **Phase Structure**: 특정 시기 또는 상황에 집중 개입하는 유한 구간 구조. 유효 구간 종료 후 유지 비권장.

**Evolution Process**: Phase Structure 유효 구간 만료 또는 급격한 삶의 전환 인지 시 신청. 신청 후 현재 상태 분석을 먼저 진행하며, 기존 구조가 여전히 유효하면 추가 전환 없이 유지를 안내한다. 이것은 서비스 거절이 아닌 최적 판단 제공이다.

**영상 자동화 전략적 위치**: ASTERION 홍보 채널(YouTube) 콘텐츠를 자동 생산하여 영상 제작 시간을 절감하고, 절약된 시간을 BTR 파이프라인·신규 고객 서비스 고도화에 집중하기 위한 장기 인프라.

---

## 2. 전체 아키텍처

```
[고객]
  └─ 구글폼(SignReg / EvReg / PvReg)
       │ 폼 제출
       ▼
[SignReg GAS] ── onAnyFormSubmit()
  └─ StructureCode 채번 → Archive 시트 최소 행 생성

[지훈 (내부 작업자)]
  └─ ASTERION Mobile App (GitHub Pages PWA)
       │ HTTPS POST
       ▼
[Archive GAS 웹앱]
  ├─ Archive 스프레드시트 (작업 데이터)
  │    ├─ Archive 시트 (메인)
  │    ├─ StoneMaster (원석 정보)
  │    ├─ SizeMaster (사이즈 목록)
  │    ├─ Inventory (재고)
  │    └─ Details (디자인 상세)
  ├─ SignReg 스프레드시트 (폼 응답 원본)
  │    ├─ SignReg (S-)
  │    ├─ EvReg (V-)
  │    └─ PvReg (L-)
  └─ Google Drive
       ├─ 북클릿 PDF 폴더 (1w0Ll81Al6sFVLtG3X5vHdL_QYryfpTST)
       ├─ 제작용 PDF 폴더 (117dfqwOb1VuF_CzCWpcT50baBrgzJTrZ)
       ├─ 제품 이미지 폴더 (19-712Gf2gX3YLP23JvcQ_snUbhGByJ9v)
       └─ 원석 이미지 폴더 (14vQVzrGLT6U2NRg...)

[AI BTR Pipeline — Cloud Run: ai-chat-hub]
  └─ Claude × Gemini × GPT 3자 루브릭 검증
       │ S-Class 달성 시 StructureCode 확정
       ▼
[ASTERION AI Evolution Engine — Cloud Run: mcp-server]
  └─ L0(VedAstro 15도구) + L2(Google Cloud 5도구) + L3(System Ops 7도구)
       MCP 프로토콜: SSE(GET /sse) + Streamable HTTP(POST /)

[ASTERION 영상 자동화 앱 — Android APK]
  └─ Google Sheets(VS_ 시트) 대본 읽기
       → sherpa-onnx Supertonic 3 TTS
       → FFmpegKit 씬 렌더링
       → BGM amix concat → 최종 MP4 출력
```

---

## 3. ASTERION Archive System — GAS Web App

### 3-A. 스프레드시트 구조

#### Archive 스프레드시트 ID: `1ym1cgr1apEyTlqtJXqrfdnLjoyJTh086CjGycMcUOS8`

**Archive 시트 컬럼**

| 컬럼 | 설명 | ExpireDate 도래 시 |
|------|------|-------------------|
| StructureCode | 고유 코드 (S-00001AA-260410) | 유지 |
| Created Date | 접수 일시 | 유지 |
| Status | Tasking / Forwarding / Complete / A/S | 유지 |
| GuestName | 고객 성함 | **삭제** |
| SourceSheet | SignReg / EvReg / PvReg | 유지 |
| StructureType | Core Structure / Phase Structure | 유지 |
| PhaseStartDate | Phase 시작일 | 유지 |
| PhaseEndDate | Phase 종료일 | 유지 |
| BTR | 확정 출생 시각 (예: 1979년 11월 07일 05시 25분) | 유지 (가공 정보) |
| Analysis | 분석 내용 | 유지 |
| Memo | 타임스탬프 누적 방식 (발송 이력 포함) | 유지 |
| UsedStones | 사용 원석 목록 (콤마 구분) | 유지 |
| LayoutSummary | 배열 요약 | 유지 |
| DetailsStr | 디테일 문자열 (A/S용) | 유지 |
| ProductImage | 제품 이미지 Drive URL | 유지 |
| ForwardingDate | 발송일 | **삭제** (Memo에 이력 보존) |
| DeliveryCompletedDate | 배송 완료일 | **삭제** (Memo에 이력 보존) |
| ExpireDate | 삭제 예정일 (DeliveryCompletedDate + 15일) | 삭제 후 공백 |
| TempPDF | 제작용 PDF 파일 ID | **삭제** (Drive 파일도 삭제) |

> BirthDate / BirthTime / Phone / Address는 Archive에 없음 — SignReg 시트에만 보관, ExpireDate 도래 시 해당 행 전체 삭제.

**StoneMaster 시트**: NameKr / NameEng / Exp / Image(Drive URL) / IsDeleted(소프트 삭제)

**Inventory 시트**: ID(INV-XXXXXXXX) / NameKr / Size(숫자만, 예: "4") / Stock / LastUpdate  
Size 정규화: 시트 "4" ↔ 프론트엔드 "4mm" → `normalizeSize()` 함수로 통일

**Details 시트**: StructureCode / InvID / Qty / SeqNo

#### SignReg 스프레드시트 (폼 응답 원본)

| 시트 | 폼 ID | 접두사 |
|------|-------|--------|
| SignReg | 1wok68NIvsZ6ylmyDMd2-6BDr9U3y2QbzybmS-dUt2jI | S- |
| EvReg | 18gldOkEnAwZBeiOsjYGjCYKr5oz_LDBJ7zfo0ClQS1M | V- |
| PvReg | 1dNZcSG_XhmfUWhxjOReJiB6vUlj-ZRUYsOZwcyRVMqU | L- |

#### JuliarCalendar 스프레드시트: `1whKvFyWmb-qbR6OJt5dcI6WOJMLB5MUIzNMlJBFeq_g`
- 1900~2060년 24절기 데이터, KST 기준, 명리학 월건 포함

### 3-B. StructureCode 체계

**형식**: `[접두사]-[5자리 순번][2자리 알파벳]-[YYMMDD]`

```
S-00001AA-260410   ← Signature, 2026-04-10 접수
V-00001AA-260410   ← Evolution Process
L-00001AA-260410   ← Private
```

**순번 체계**: 00001AA → 99999AA → 00001AB → ... → 99999ZZ (최대 약 6,759만 개)

**채번 로직**: Archive + SignReg 시트에서 기존 코드 수집 → 가장 큰 순번 다음 계산 → 오늘 날짜 접미사 조합

**형식 변경 이력**: `SC-001` → `S-00001-260327` → `S-00001AA-260410` (현재)  
현재 Archive에 구형/신형 코드 혼재 가능 — 시스템은 두 형식 모두 조회 지원.

### 3-C. 폼 제출 자동 처리 흐름

```
구글폼 제출
  ↓
onAnyFormSubmit(e)  ← e.range.getSheet().getName()으로 시트 구분
  ↓ (단일 트리거 — 라우팅 방식)
_handleFormSubmit(e, sheetName, prefix)
  1. StructureCode 채번
  2. 해당 시트 행에 StructureCode 기록
  3. Archive 최소 항목 추가: StructureCode / Created Date / Status(Tasking) / GuestName / SourceSheet
  4. EvReg 전용: values[1] (기존 Archive No.) → Memo 저장
```

트리거: `onAnyFormSubmit` (폼 제출) + `deleteExpiredData` (매일 03:00)  
**주의**: 트리거를 여러 개의 `onFormSubmit`으로 분리하면 모든 제출 시 전부 실행됨 — 반드시 단일 `onAnyFormSubmit` 라우터 방식 사용

### 3-D. HTML 페이지 완전 구현 현황

**네비게이션 흐름**

```
index.html (홈)
  ├─ worksdesk.html (작업 목록: Tasking / Forwarding / A/S 상태만)
  │    └─ [코드 선택 후 하단 버튼으로 이동]
  │         ├─ analysismemo.html → structuretype.html → selectstone.html
  │         │    → design.html → productimage.html → booklet.html → forwarding.html
  │         └─ (홈으로)
  ├─ structureindex.html (코드 직접 조회·Status 변경)
  ├─ inventory.html → stoneinfo.html → invenmanage.html → newlisting.html
  └─ neworder.html (미생성 코드 발급·PDF 재생성)
```

---

#### index.html ✅

배경 `bg_home.jpg`, 버튼 2×2 그리드 (크기 30% 축소):
Works Desk / Code Index / Inventory / New Registration

---

#### worksdesk.html ✅

- Status가 `Tasking`, `A/S`, `Forwarding`인 항목만 표시
- 하단 버튼 2행 4열: 홈 / Analysis&Memo / Structure Type / 원석 선택 // Design / Product Image / Booklet / Forwarding
- API: `apiGetTaskingList()` — 30초 캐시

---

#### analysismemo.html ✅

**Internal Memo**: 타임스탬프 추가 방식 (덮어쓰기 없음), flex 0.6

**BTR (Birth Time Rectification)**
- Memo와 Analysis 사이 배치
- 입력: 12자리 숫자 (예: `197911070525`)
- 저장: `1979년 11월 07일 05시 25분` (텍스트 형식)
- `setNumberFormat("@")` → Google Sheets 날짜 자동변환 방지
- 실시간 프리뷰: 입력 즉시 변환 결과 표시
- 기존 값 있으면 12자리 숫자로 복원
- Save: 덮어쓰기 방식
- ExpireDate 이후 영구 보존 (단독으로 개인 특정 불가)
- 북클릿 플레이스홀더: `{{BTR}}`

**Analysis**: 전체 덮어쓰기, flex 1 (고객 최우선 관심 항목 → 북클릿 Page 5 전체 단독 배치)

Save 버튼: Memo(추가) + BTR(덮어쓰기) + Analysis(덮어쓰기) 동시 처리  
API: `apiUpdateAnalysisMemo(sc, analysis, btr)`

---

#### structuretype.html ✅

- 드롭다운: Core Structure / Phase Structure
- Phase 선택 시: Phase Start Date + Phase End Date 추가
- API: `apiSaveStructureType(sc, structureType, phaseStartDate, phaseEndDate)`

---

#### selectstone.html ✅

- StoneMaster 원석 목록 로드 (소프트 삭제 제외)
- 선택 → Archive `UsedStones` + localStorage(`ASTERION_SELECTED_STONE`) 병행 저장
- API: `apiGetAllStones()` 5분 캐시, `apiSaveUsedStones(sc, stones)`

---

#### design.html ✅

- 원석별 사이즈 선택
- flat `details` 배열 + `isButton:true` 마커로 배열 위치 관리
- `activePlusIndex`로 삽입 위치 추적, `compactButtons()`로 연속 버튼 방지
- Save & Deduct: 재고 차감 + LayoutSummary + DetailsStr 저장 (단일 GAS 콜)
- API: `apiSaveDesign(sc, details, deduct, layoutSummary, detailsStr)` → action: `saveDesign`

---

#### productimage.html ✅

- 사진 업로드 → Drive `PRODUCT_FOLDER_ID` 저장 → Archive `ProductImage` 컬럼 업데이트
- API: `apiUploadProductImage(sc, file)` — 파일 → byteArray 변환 후 전송

---

#### booklet.html ✅ **[완전 구현 — 실제 코드 기반 확인]**

**UI 구조**
- 좌: 페이지 번호 버튼 11개 (세로, 34×34px, 활성: 금색 rgba(170,130,75,.6))
- 우: 미리보기 영역 (530px 고정 높이, 스크롤 가능)
- 하단: Home / Back(→ productimage.html) / Create PDF
- 배경: 금색(#f2dfa0) — 1, 2, 11 페이지 / 흰색(#ffffff) — 3~10 페이지

**11개 페이지 완전 구현 상세**

| 페이지 | 배경 | 내용 | 데이터 |
|--------|------|------|--------|
| P1 | 금색 | ASTERION 브랜드 커버 + "The Architecture of Fate" | 고정 |
| P2 | 금색 | Signature Archive — Archive No. / "Designed for {GuestName}" / 인용구("Beyond science, We architect life's possibilities.") / ASTERION Architectural Authority | structureCode, guestName |
| P3 | 흰색 | Structure Design — 제품 이미지(140×140px) + Archive No. + Layout Summary | productImage, structureCode, layoutSummary |
| P4 | 흰색 | Gemstone Description — 원석별 이미지(36×36px) + 한국명 + 영문명 + 설명 (테이블 형식) | stones[] |
| P5 | 흰색 | **Analysis 전체 단독 페이지** (스크롤 가능) — 고객 최우선 관심 항목 | analysis |
| P6 | 흰색 | Structure Type 안내 — Core vs Phase 개념 설명 (고정 텍스트) | 고정 |
| P7 | 흰색 | 현재 적용된 Structure Type 명시 + Phase인 경우 유효 구간 날짜 표시 / Phase 종료 후 잔류 에너지 위험 안내 | structureType, phaseStartDate, phaseEndDate |
| P8 | 흰색 | Evolution Process 안내 — 신청 사유 / 분석 우선 / 유지 권장 가능성 안내 (고정) | 고정 |
| P9 | 흰색 | QR코드(images/qr_evolution.png) + URL(https://naver.me/5RArPUQM) — 비공개 전용 경로 | 고정 |
| P10 | 흰색 | 브랜드 철학 텍스트 — 원석·구조·에너지 정렬 철학 4단락 (고정) | 고정 |
| P11 | 금색 | 슬로건 3줄: "빛은 선택된 이에게만 닿는다." / "보이지 않는 흐름을 설계하다." / "운은 우연이 아니다." | 고정 |

**PDF 생성**
- `apiCreatePdf(sc)` → GAS `createPdf()` → `/view` URL 반환 (폰 자동 저장 방지)
- TempPDF 컬럼 저장 안 함 — 북클릿 PDF는 영구 보존, 제작용 PDF와 완전 분리
- 저장 폴더: PDF_FOLDER_ID (북클릿 전용)

**데이터 로드**: `apiGetBookletData(sc)` — 2분 캐시  
반환: `{ structureCode, guestName, productImage, layoutSummary, stones[], analysis, structureType, phaseStartDate, phaseEndDate }`

Drive URL 변환: 공유 URL → `https://drive.google.com/thumbnail?id=...&sz=w400` 자동 변환 (`convertDriveUrl()`)

---

#### forwarding.html ✅ **[완전 구현 — 실제 코드 기반 확인]**

**StructureCode 전달**: worksdesk.html에서 URL 파라미터(`?code=`)로 전달 — 화면 내 별도 입력 없음. `requireStructureCode()`로 세션에서 가져와 상단 표시.

**UI 구성**
- 상단(flex 1): StructureCode 텍스트 (#code-text)
- 중앙(flex 2): Forwarding Date + Delivery Completed Date 날짜 입력 2개
- 하단 힌트: 실시간 상태 표시
- 네비: Home / Back(→ worksdesk.html) / Update

**날짜 입력 규칙**

| 입력 상태 | 실시간 힌트 | Update 결과 |
|-----------|-----------|-------------|
| ForwardingDate만 | "Update 시 Status → Forwarding" (금색) | Status: Forwarding + Memo 이력 추가 |
| 두 날짜 모두 | "Update 시 Status → Complete" (녹색) | Status: Complete + ExpireDate(+15일) + Memo 이력 추가 |
| DeliveryCompletedDate만 | "⚠ Forwarding Date를 먼저 입력하세요" (주황) | Update 차단 |
| 둘 다 비어있음 | 힌트 없음 | Update 차단 |

페이지 진입 시: `apiGetArchiveItem(sc)`로 기존 날짜 로드 후 입력란 복원

**날짜 컬럼 초기화 시점**: Update 버튼이 아닌 `deleteExpiredData()` 실행 시. Memo에 이력 영구 보존.

API: `apiUpdateForwarding(sc, forwardingDate, deliveryCompletedDate)`

**A/S 재진입**: structureindex.html에서 Status → A/S 변경 → worksdesk에서 확인 → forwarding.html에서 새 날짜 입력 → 동일 프로세스 반복 (A/S 전용 별도 처리 없음)

**GAS `updateForwarding()` 내부 처리**
- ForwardingDate만: 시트 저장 + Memo 이력(`[날짜] 발송 완료`) + Status: Forwarding
- 두 날짜: 시트 저장 + Memo 이력 + ExpireDate = DeliveryCompletedDate + 15일 자동 계산 + Status: Complete
- "15일 후 자동 삭제 예약"은 GAS Time-driven trigger(`deleteExpiredData`)가 처리

---

#### structureindex.html ✅

- 입력: 최대 16자, 실시간 프리뷰 `S-□□□□□□□-□□□□□□`
- 가이드: `S / V / L — 5자리 순번 + 2자리 알파 — YYMMDD`
- 버튼: Select(코드 선택) / A/S / Complete (Status 직접 변경)

---

#### neworder.html ✅

- SignReg/EvReg/PvReg 전체 타임스탬프 순 표시
- 미생성 행: 빨간 "미생성" 표시
- Create Code: 코드 발급 + Archive 행 + 제작용 PDF + TempPDF 저장
- Create PDF: 기존 TempPDF 삭제 후 재생성
- 용도: 시스템 오류로 StructureCode 미생성 시 구글폼 재접수 없이 복구 (고객 법적 동의 시점 보전)

---

#### inventory.html / stoneinfo.html / invenmanage.html / newlisting.html ✅

- inventory.html: 전체 원석 목록 (소프트 삭제 포함)
- stoneinfo.html: 원석 상세 편집 + 이미지 업로드
- invenmanage.html: 원석별 사이즈별 재고 수량 관리
- newlisting.html: 신규 원석 등록

### 3-E. JavaScript 공통 레이어

로드 순서 (모든 HTML 동일): `config.js → api_contract.js → state.js → api.js → ui.js`

**config.js**: `CONFIG.API_BASE` — Archive GAS 웹앱 배포 URL

**api_contract.js** — A 객체 Action 상수

| 상수 | action | 용도 |
|------|--------|------|
| A.GET_TASKING_LIST | getTaskingList | worksdesk 목록 |
| A.GET_ARCHIVE_ITEM | getArchiveItem | 단건 조회 |
| A.UPDATE_STATUS | updateStatus | Status 변경 |
| A.GET_ALL_STONES | getAllStones | 원석 목록 |
| A.SAVE_USED_STONES | saveUsedStones | 원석 선택 저장 |
| A.GET_USED_ITEMS | getUsedItems | 디자인용 원석·사이즈 |
| A.GET_DETAILS | getDetails | 디자인 상세 조회 |
| A.SAVE_DESIGN | saveDesign | 디자인 저장 (통합 단일 콜) |
| A.UPDATE_ANALYSIS_MEMO | updateAnalysisMemo | 분석·BTR·메모 저장 |
| A.APPEND_MEMO | appendMemo | 메모 추가 |
| A.UPLOAD_PRODUCT_IMAGE | uploadProductImage | 제품 이미지 |
| A.SAVE_STRUCTURE_TYPE | saveStructureType | Structure Type 저장 |
| A.GET_BOOKLET_DATA | getBookletData | 북클릿 데이터 |
| A.CREATE_PDF | createPdf | 북클릿 PDF 생성 |
| A.UPDATE_FORWARDING | updateForwarding | 발송 정보 저장 |
| A.GET_ALL_REG_ROWS | getAllRegRows | 전체 폼 접수 목록 |
| A.CREATE_CODE_AND_REGISTER | createCodeAndRegister | 코드 발급+등록+PDF |
| A.RECREATE_PDF | recreatePdf | 제작용 PDF 재생성 |

**state.js**

```javascript
getStructureCode()       // URL ?code= 또는 sessionStorage
setStructureCode(code)
clearAndGo(page)         // 코드 초기화 후 이동
goPage(page, code)       // 코드 유지하며 이동
requireStructureCode()   // 없으면 worksdesk 리다이렉트

cacheSet(key, value, ttl) / cacheGet(key) / cacheClear(key)
normalizeSize(s)          // "4" → "4mm"
getSizeNumber(s)          // "4mm" → 4
```

**캐시 TTL**: taskingList 30초 / archive_{sc} 60초 / booklet_{sc} 120초 / allStones 300초 / details_{sc} 600초

**api.js 캐시 무효화 패턴**
- `_clearArchiveCache(sc)`: archive_ + booklet_ + taskingList + allArchive
- `_clearDesignCache(sc)`: details_ + usedItems_ + _clearArchiveCache 포함
- `_clearStoneCache()`: allStones + allStonesFull + inventoryMatrix

**ui.js**: `showLoading(text)` / `hideLoading()` / `toastOk(msg)` / `toastErr(msg, duration)` / `confirmPopup(question)→Promise<boolean>` / `initImageBox(opts)`

### 3-F. GAS 핵심 함수 레퍼런스

**Archive GAS 상수**
```
IMAGE_FOLDER_ID     = "14vQVzrGLT6U2NRg..."
PRODUCT_FOLDER_ID   = "19-712Gf2gX3YLP23..."
PDF_FOLDER_ID       = "1w0Ll81Al6sFVLtG3X5vHdL_QYryfpTST"
ORDER_PDF_FOLDER_ID = "117dfqwOb1VuF_CzCWpcT50baBrgzJTrZ"
SIGREG_SS_ID        = "1JFR7O9wxzvK4aqw..."
```

**북클릿 PDF 템플릿 (원석 수별)**

| 1 | 10Bed5FIVomdiOJ5TxlL5PZuQ4boFteo8yQ9drATp348 |
| 2 | 1UnSB__hHTCVqvChC9GrzTMfSWYwOyTUkCiqyF2xTzN4 |
| 3 | 1WqBd0cr1GCulgkk_TZYS5vsuWGgjt58XyBxJjh4-BhA |
| 4 | 1WVj3TTV0erfo176rh2BrHmignpZVM437V-CuaejK5Rw |
| 5 | 1cD7YQD4C9WQZ5pUhYsRA87bHNnOr73fTxcVK0cqbrNk |
| 6 | 1io4ZwyQa17qKlEYnpK_zqvsM7LGb5UvUUEJBizWjvXQ |
| 7 | 17_UuLgH5TFXRwhOth1PjzKnBQ3rDGYyK4Jp4NmtGWfs |
| 8+ | 1LupkyTxF8g67YEbt0rzfptqKt85qc4LXVa-amg-QPAM |

**북클릿 플레이스홀더 목록**
```
{{STRUCTURE_CODE}} / {{GUEST_NAME}} / {{LAYOUT_SUMMARY}} / {{ANALYSIS}}
{{BTR}} / {{StructureType}} / {{PhaseStartDate}} / {{PhaseEndDate}}
{{PRODUCT_IMG}}
{{STONE1_KR}}~{{STONE8_KR}} / {{STONE1_ENG}}~{{STONE8_ENG}}
{{STONE1_EXP}}~{{STONE8_EXP}} / {{STONE1_IMG}}~{{STONE8_IMG}}
{{#IF_PHASE}}내용{{/IF_PHASE}}  ← 반드시 한 줄 인라인
```

**`{{#IF_PHASE}}` 처리 규칙**
- Phase Structure: 태그만 `replaceText()`로 제거, 내용 유지
- Core Structure / 미지정: 태그 포함 단락 전체 역순 삭제
- 반드시 같은 줄 인라인 — 여러 단락 걸친 블록 처리 불가 (Google Docs 제약)

**`updateAnalysisMemo(payload)`**: analysis 덮어쓰기 / btr `setNumberFormat("@")` 강제 후 저장 / memo 선택적

**`updateForwarding(payload)`**: ForwardingDate만→Forwarding / 두 날짜→Complete+ExpireDate+15일 / 날짜 컬럼 초기화는 deleteExpiredData()에서

**`deleteExpiredData()` 삭제 순서**: GuestName → ForwardingDate → DeliveryCompletedDate → TempPDF Drive파일+컬럼 → ExpireDate컬럼 → SignReg/EvReg/PvReg 해당 행 전체

**`saveDetails` Lock 구조**: wrapper(lock 획득) + `_saveDetailsCore()`(실제 처리) 분리 → 복합 동작 시 lock 중복 방지

**GAS 트리거 실패 알림**: Apps Script → 트리거 → 편집 → 실패 알림: 즉시 설정 권장

### 3-G. 개인정보 보호 설계

**데이터 흐름 원칙**

```
구글폼 (성함, 생년월일, 출생시간, 연락처, 배송주소)
  ↓ (최소 정보만 이동)
Archive 시트 (StructureCode + Created Date + Status + GuestName + SourceSheet만)
  ↓ ExpireDate 도래 시
삭제: GuestName / ForwardingDate / DeliveryCompletedDate / TempPDF
      + SignReg/EvReg/PvReg 해당 행 전체
보존: StructureCode / BTR / Analysis / Memo / UsedStones / LayoutSummary / DetailsStr / ProductImage
```

**영구 보존 항목** (개인 특정 불가): StructureCode / Created Date / Status / SourceSheet / StructureType / PhaseStartDate / PhaseEndDate / BTR(가공 정보) / Analysis / Memo / UsedStones / LayoutSummary / DetailsStr / ProductImage

---

## 4. BTR (Birth Time Rectification) 파이프라인

### 핵심 원칙

- Claude × Gemini × GPT 독립 채점
- **S-Class 조건**: 세 AI 모두 97점↑ AND critical_issues 없음 → Hard Stop
- 채점은 "97점 달성 목표"가 아닌 루브릭 항목별 독립 평가

### 루브릭 (100점 만점)

| 항목 | 배점 | 비고 |
|------|------|------|
| 과거 사건 부합성 | 40점 | 사건 3건 미만 시 25점 초과 불가 |
| D-9(나바암샤) 정렬 | 20점 | 라시↔나바암샤 논리 모순 없을 때 만점 |
| 외형·기질 일치 | 15점 | 기질 정보 미제공 시 10점 초과 불가 |
| 다샤 전환점(Sandhi) | 15점 | 인생 변곡점과 Sandhi 구간 일치도 |
| 논리 일관성 보너스 | 10점 | 세 AI 핵심 결론 일치 시 자동 반영 |

### 프롬프트 최적화 전략 (확정)

**원칙**: 내부 루브릭·채점 기준·프롬프트 가이드라인은 **영어 키워드 + XML/Markdown 기호**로 압축 작성.  
출력만 `Respond in Korean` 한 줄로 지정 → 내부 추론은 영어(경량), 최종 응답만 한국어.

**효과**:
- 한글 대비 토큰 30~50% 절감 (한 글자 = 1~3토큰 vs 영어 단어 = 1~2토큰)
- AI 학습 데이터 대부분이 영어 → 영어 추론 시 정확도·일관성 향상
- 할루시네이션 억제 (구조적 기호로 채점 기준 명확화)

**확정 SYSTEM_PROMPT_BTR (최적화 버전)**

```xml
<role>ASTERION BTR rubric analyst</role>
<goal>S-Class confirmation only — no compromise on incomplete data</goal>

<rubric total="100">
  <criterion id="event_fit"   max="40" cap="lt3_events→max25"/>
  <criterion id="navamsa_d9"  max="20" note="rashi↔navamsa_no_contradiction"/>
  <criterion id="appearance"  max="15" cap="no_physique_data→max10"/>
  <criterion id="sandhi"      max="15" note="life_inflection↔sandhi_overlap"/>
  <criterion id="consistency" max="10" note="auto_if_3ai_core_conclusion_agree"/>
</rubric>

<hard_stop>all_scores≥97 AND critical_issues=[] → Confirmed(S-Class) IMMEDIATELY</hard_stop>
<failure>5rounds_exhausted → generate 3 JSON follow-up questions → Status:WaitInfo_BTR</failure>

<prohibited>
  - rationalized_low_score
  - compromise_with_incomplete_data
  - candidate_time_outside_given_range
</prohibited>

<output_format>
{
  "candidate_time": "HH:MM",
  "analysis": "string",
  "scores": { "event_fit":0, "navamsa_d9":0, "appearance":0, "sandhi":0, "consistency":0 },
  "total": 0,
  "critical_issues": [],
  "minor_issues": [],
  "suggestions": [],
  "confidence": "LOW|MEDIUM|HIGH"
}
</output_format>

<lang>Respond in Korean</lang>
```

> **주의**: `SYSTEM_PROMPT_FREESTYLE`은 한글 유지 (자유 대화용, 최적화 불필요)

### 3자 루브릭 라운드 구조

5라운드 × 3역할, 최대 15회 AI 호출

| 라운드 | Author | Critic | Challenger |
|--------|--------|--------|------------|
| 1 | Gemini | Claude | GPT |
| 2 | Claude | GPT | Gemini |
| 3 | GPT | Gemini | Claude |
| 4 | Gemini | Claude | GPT |
| 5 | Claude | GPT | Gemini |

**역할 정의**
- Author: 독립 분석 → JSON 루브릭 평가 제출
- Critic: Author 분석 비판 + 논리 오류 지적 + 독자 점수
- Challenger: Author/Critic 충돌 분석 + 최종 검증 점수

**Hard Stop**: 한 라운드에서 세 AI 모두 97점↑ + critical_issues 없음 → Confirmed (S Class)

**5라운드 실패**: 3자 동시 실패 분석 → 추가 질문 3개 JSON 제안 → Status: WaitInfo_BTR

**구현 위치**: `ai-chat-hub` Cloud Run, `server.js` (BTR Pipeline Server)

### AI 모델 확정 스펙 (2026-05)

| AI | 모델 | Thinking | MCP 방식 |
|----|------|----------|---------|
| Claude | claude-sonnet-4-6 | Extended Thinking (budget_tokens: 10000) | Native API connector (beta: mcp-client-2025-11-20) |
| Gemini | gemini-3.1-pro-preview | thinkingConfig.thinkingLevel: 'high' (최상위 위치) | Function Calling 수동 루프 |
| GPT | gpt-5.5 | reasoning.effort: 'medium' | Responses API (/v1/responses) + type:'mcp' |

**Gemini thinkingConfig 올바른 위치 (확정)**
```javascript
// ✅ 올바른 위치
{ generationConfig: { maxOutputTokens: 65000, ... }, thinkingConfig: { thinkingLevel: 'high' } }

// ❌ 잘못된 위치 (400 오류 발생)
{ generationConfig: { thinking_level: 'high', ... } }
```

**프롬프트 캐싱**
- Claude: cache_control: ephemeral (system + 도구 목록 — 마지막 도구에 마커)
- Gemini: Implicit Caching (Google 자동)
- GPT: Automatic Prefix Caching (OpenAI 자동, 1024토큰↑ 시 자동 적용)

> 캐싱 효과 극대화: SYSTEM_PROMPT_BTR을 영어+XML로 압축 → 캐시 히트율 향상 + 토큰 절감 이중 효과

---

## 5. ASTERION AI Evolution Engine (MCP Server)

**서비스**: mcp-server (Cloud Run)  
**URL**: https://mcp-server-611151539232.asia-northeast3.run.app  
**버전**: v4.0 | **GitHub**: victuar918/MCP_Server

### MCP Transport 이중 지원

| 엔드포인트 | 방식 | 용도 |
|-----------|------|------|
| GET /sse | SSE (직접 구현) | Hub SDK, Claude native connector |
| POST /message?sessionId= | SSE 세션 메시지 | Hub SDK |
| POST / | Streamable HTTP (직접 구현) | GCP Agent Platform, Claude.ai 커스텀 커넥터, ChatGPT |
| GET / | 헬스체크 | 상태 확인 |

> @modelcontextprotocol/sdk 미사용 — express + cors만으로 완전 독립 구현

### 도구 목록 (총 27개)

**L0: VedAstro 천문 계산 (15개)**
geocode_location / get_timezone / get_planet_positions / get_house_positions / get_navamsa_chart / get_ascendant / get_planet_in_house / get_planet_in_sign / get_current_dasha / get_dasha_timeline / get_dasha_sandhi / get_birth_nakshatra / get_planet_yogas / get_transit_planets / get_full_chart_analysis

**L2: Google Cloud 제어 (5개)**

| 도구 | 설명 |
|------|------|
| gcloud_submit | Cloud Build로 gcloud 명령어 실행 — Agent Registry 등록 등 모든 gcloud 작업. 빌드 ID 반환 |
| cloudbuild_status | 빌드 상태/결과 조회 |
| cloudrun_services | Cloud Run 서비스 목록과 상태 (URL, 트래픽, revision) |
| artifact_list | Artifact Registry Docker 이미지 목록 |
| cloudrun_set_env | Cloud Run 서비스 환경변수 설정 |

**L3: System Ops & Evolution (7개)**

| 도구 | 설명 |
|------|------|
| github_read_file | GitHub 파일 읽기 |
| github_write_file | ★ GitHub 파일 쓰기/커밋 → Cloud Build 자동배포 트리거 |
| github_list_files | GitHub 파일 목록 조회 |
| sheets_read | Google Sheets 읽기 |
| sheets_write | Google Sheets 쓰기 |
| http_request | 임의 HTTP 요청 |
| get_system_status | 전체 시스템 상태 확인 |

### 환경변수

| 변수 | 값/설명 |
|------|---------|
| GITHUB_PAT | GitHub Personal Access Token (Cloud Run 환경변수로 관리) |
| GITHUB_OWNER | victuar918 |
| GCP_PROJECT | asterion-server |
| GCP_REGION | asia-northeast3 |
| VEDASTRO_API_KEY | VedAstro API 키 (없으면 공개 엔드포인트) |
| MCP_SECRET_KEY | Bearer 토큰 인증 (설정 시 모든 엔드포인트 인증 활성화) |

GCP 인증: Cloud Run ADC (메타데이터 서버, 추가 설정 불필요)

---

## 6. ASTERION Hub (AI Chat 관제시스템)

**서비스**: ai-chat-hub (Cloud Run)  
**URL**: https://ai-chat-hub-w4ozxil5aq-du.a.run.app  
**버전**: v3.0 | **GitHub**: victuar918/AI_Chat_Hub

### 기능

- Claude / Gemini / GPT 세 모델 채팅
- 각 모델별 ASTERION MCP 연결
- Freestyle 모드 (ASTERION 페르소나 없이 자유 대화)
- Google Drive 지식베이스 로드
- BTR 서버 프록시 (`/api/btr/start`, `/api/btr/status/:jobId`)

### 모델별 MCP 연결 방식

**Claude**: `anthropic-beta: mcp-client-2025-11-20` 헤더 + `mcp_servers` + `tools: [type:mcp_toolset]`  
→ Anthropic API가 MCP 서버 서버사이드 직접 호출. Hub에서 도구 루프 불필요.

**GPT**: `/v1/responses` + `tools: [{ type:'mcp', server_url: '...', require_approval:'never' }]`  
→ OpenAI가 MCP 서버 직접 호출. Chat Completions 아님.

**Gemini**: SSE 클라이언트로 도구 목록 가져온 후 functionDeclarations 변환 → 수동 도구 실행 루프

### ASTERION Mobile App (현재 운영 중)

**GitHub**: victuar918/ASTERION (GitHub Pages PWA)  
**현재 역할**: 내부 작업 전용 Archive 시스템 프론트엔드 (메인 도구)  
**향후 기능 배분 방향 (검토 중)**:
- Hub: 관제·AI 작업 중심 (BTR 모니터링, 시스템 관리, AI 상호작용)
- Mobile App: 현장 제작 작업 중심 (원석 선택, 디자인, 발송 관리)
- 구체적 배분 기준은 실제 운영 패턴 확인 후 결정 예정

### 환경변수

| 변수 | 설명 |
|------|------|
| ANTHROPIC_API_KEY | Anthropic Console |
| GEMINI_API_KEY | Google AI Studio |
| OPENAI_API_KEY | OpenAI Platform |
| MCP_SERVER_URL | https://mcp-server-611151539232.asia-northeast3.run.app |
| MCP_SECRET_KEY | mcp-server와 동일값 |
| BTR_SERVER_URL | BTR Pipeline Server URL |
| ASTERION_KNOWLEDGE_FOLDER_ID | Google Drive 지식베이스 폴더 ID |

---

## 7. ASTERION 영상 자동화 앱 (Android)

> **전략 목적**: YouTube 채널 콘텐츠 자동 생산 → 영상 제작 시간 절감 → BTR·고객 서비스 고도화에 집중  
> **위치**: `victuar918/ASTERION` 리포 `android/` 디렉토리  
> **APK 빌드**: Cloud Build (`android/cloudbuild.yaml`) → GCS 버킷 자동 업로드  
> **현재 버전**: v3.2.0 (versionCode 15)

---

### 7-A. 전체 파이프라인 흐름

```
[Google Sheets — VS_{시트명}]
  대본(Script_Data) + 메타(Video_Meta) 읽기
       ↓ SheetsVideoReader (Sheets API v4)
[AsterionVideoActivity — UI]
  VS_ 시트 선택 / 화자별 sid·speed 설정 / 렌더링 시작·중지
       ↓
[RenderForegroundService — Android Foreground Service]
  백그라운드 렌더링 중 알림 상주 (앱 킬 방지)
       ↓
[AsterionRenderEngine — 씬 단위 루프]
  ┌─ SupertonicTtsEngine.synthesize()
  │    sherpa-onnx OfflineTts → WAV 파일 (cacheDir)
  │
  ├─ BGV 파일 해석 (bgFile 파이프 파싱)
  │    형식: "파일명|TRANSITION|초|효과코드"
  │    fallback: VedicEnergyByPlanet_XRP_MovingChart.mp4
  │
  └─ FFmpegKit.executeAsync()
       배경 BGV(-stream_loop) + WAV 합성
       vf: fade in/out (FADE 타입 시)
       -c:v libx264 veryfast crf23 / -c:a aac 128k
       → scene_XXXX.mp4 (OUTPUT_DIR)
       ↓ 씬별 K열 상태 업데이트: DONE / ERROR
[concatSubclips()]
  concat_list.txt → -f concat → raw MP4
  BGM amix: volume=0.25, afade in 3s / out 5s
  → {시트명}.mp4 최종 출력
```

---

### 7-B. 핵심 모듈 상세

#### AppConfig.kt
```
외부 저장소 기준 경로 (Documents/work/ASTERION/YouTube/)
  BGV_DIR    → /BGV/       배경 동영상 소스
  BGM_DIR    → /bgm/       배경 음악
  OUTPUT_DIR → /output/    씬별 MP4 + 최종 출력

TTS_MODEL_SUBDIR  = "tts_model"
TTS_READY_MARKER  = ".ready_supertonic3_int8"
TTS_TAR_URL       = GitHub Releases tts-models 태그
                    sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2
                    (~350MB 압축 / ~700MB 해제)
VIDEO_SS_ID       = "1ugWJmyLItD95Vz7Jq8Wjxn0_Ml5REjrhUxNZVFoIFmc"
```

#### SheetsVideoReader.kt
- Google Sheets API v4 직접 호출 (OkHttp, Bearer 토큰)
- `listScriptSheets()`: VS_ 접두사 시트만 필터링
- `readScript(sheetName)`: Video_Meta(상단) + Script_Data(Section 헤더 이후) 파싱
- `readReadyRows()`: Status == "READY" 행만 필터
- `updateStatus(rowIndex, status)`: K열 → "DONE" / "ERROR" 갱신 (sheetRow = 7 + rowIndex)

#### ScriptDataRow.kt — 데이터 모델

**Video_Meta (시트 상단 Key-Value 영역)**

| Key | 설명 |
|-----|------|
| YouTube_Title | 영상 제목 |
| Top_Watermark | 상단 워터마크 텍스트 (기본: "ASTERION") |
| Thumbnail_Text | 썸네일 텍스트 |
| Main_BGM | BGM 파일명 (bgm 폴더 기준) |

**Script_Data 컬럼 매핑 (A~R열)**

| 열 | 필드 | 설명 |
|----|------|------|
| A | section | TPL-INT / TPL-PLA / TPL-TIM / TPL-SUM / TPL-SYN / TPL-BUF |
| B | speaker | 화자 번호 (1=아스터 / 2=리언 / 3=나레이터) |
| C | cardMain | 카드 대제목 |
| D | cardSub | 카드 소제목 |
| E | cardDesc | 카드 요약 |
| F | highlightWord | 강조 키워드 |
| G | script | TTS 합성 텍스트 |
| H | bgFile | "파일명\|TRANSITION\|초\|효과코드" |
| I | animation | 카드 애니메이션 패턴 (A~G / NONE) |
| J | cardStyle | DEFAULT / TITLE / CONCLUSION / NOTICE / MINIMAL / NONE |
| K | status | READY → DONE / ERROR (렌더 완료 후 자동 갱신) |
| L | note | 메모 (렌더링 미사용) |
| M | bgEffect | 배경 효과 코드 (NONE / MOTION_BLUR / EDGE_GLOW / VIGNETTE) |
| N | bgTransition | 전환 코드 (FADE / SLIDE_LEFT / SLIDE_UP / ZOOM_IN / ZOOM_OUT / BLUR_FADE / WIPE_RIGHT) |
| O | cardExtraEffect | NONE / HEARTBEAT / VIBRATE |
| P | lottieFile | Lottie JSON 파일명 또는 NONE |
| Q | stickerFile | "파일명\|x비율\|y비율\|w\|h\|start\|end\|rotation" 또는 NONE |
| R | gradientPreset | DEFAULT / TITLE / CONCLUSION / NOTICE |

**열거형 (ScriptDataRow.kt 내 정의)**
```kotlin
SectionType   : INTRO, PLANET, TIMING, SUMMARY, SYNTHESIS, BUFFER, UNKNOWN
CardStyle     : DEFAULT(0.75), TITLE(0.85), CONCLUSION(0.90), NOTICE(0.80), MINIMAL(0.0), NONE(0.0)
AnimationPattern : A, B, C, D, E, F, G, NONE
BgTransition  : FADE, SLIDE_LEFT, SLIDE_UP, ZOOM_IN, ZOOM_OUT, BLUR_FADE, WIPE_RIGHT
CardExtraEffect : NONE, HEARTBEAT, VIBRATE
GradientPreset: DEFAULT(블랙→딥네이비), TITLE(딥퍼플→블랙), CONCLUSION(딥네이비→블랙), NOTICE(딥레드→블랙)
```

#### SupertonicTtsEngine.kt (VoiceConfig.kt)

**모델**: sherpa-onnx Supertonic 3 (int8 최적화)  
**배포**: GitHub Releases `tts-models` 태그 tar.bz2 → 최초 실행 시 자동 다운로드·해제  
**7개 필수 파일**: `duration_predictor.int8.onnx` / `text_encoder.int8.onnx` / `vector_estimator.int8.onnx` / `vocoder.int8.onnx` / `tts.json` / `unicode_indexer.bin` / `voice.bin`

**화자 구성 (VoiceConfig)**

| ASTERION 화자번호 | 이름 | sherpa-onnx sid | speed | 성별 |
|-------------------|------|-----------------|-------|------|
| 1 | 아스터 | 5 | 1.00x | 남성 |
| 2 | 리언 | 0 | 0.95x | 여성 |
| 3 | 나레이터 | 6 | 1.05x | 남성 |

- Supertonic 3 전체 화자: sid 0~9 (총 10명, sid 0~4 여성 / sid 5~9 남성)
- UI 스피너: VOICE_LABELS 10개 표시, 🔊 버튼으로 실시간 테스트 가능
- `GenerationConfig.extra = mapOf("lang" to "ko")` — 한국어 발음 필수 파라미터
- `NUM_STEPS = 8` (diffusion 스텝, 품질·속도 균형)
- 출력 샘플레이트: 24kHz 고정
- WAV 헤더 파싱으로 오디오 길이 추정 (`estimateDurationFromFile`)

**초기화 흐름**
```
앱 시작
  → prepareModelDir() — .ready 마커 확인
    없으면: OkHttp로 tar.bz2 다운로드 (~350MB, 30분 타임아웃)
            → BZip2CompressorInputStream + TarArchiveInputStream 스트리밍 해제
            → validateModelFiles() 7개 검증
            → .ready_supertonic3_int8 마커 생성
  → OfflineTts(assetManager=null, config) 초기화
  → numSpeakers / sampleRate 확인 로그
```

#### AsterionRenderEngine.kt — v3.4

**씬 렌더링 (`renderScene`)**
```
1. TTS 합성 → cacheDir/{id}.wav
2. bgFile 파이프 파싱
   [0] 파일명  [1] TRANSITION  [2] 초  [3] 효과코드
3. fade 계산
   effectiveFade = min(fadeDur, sceneDur/2)  ← 씬 길이 초과 방지
   fadeOutStart  = max(0, sceneDur - effectiveFade)
4. vf 필터 조립
   NONE → 빈 문자열
   FADE → "fade=t=in:st=0:d=X,fade=t=out:st=Y:d=X"
5. BGV 파일 존재 여부 분기
   있으면: -stream_loop -1 -i {bgv} -i {wav}
   없으면: -f lavfi -i color=c=black:size=1920x1080:rate=30 -i {wav}
6. FFmpegKit.executeAsync()
   -c:v libx264 -preset veryfast -crf 23
   -c:a aac -b:a 128k
   -shortest -movflags +faststart
   → OUTPUT_DIR/scene_XXXX.mp4
```

**최종 합치기 (`concatSubclips`)**
```
1. OUTPUT_DIR에서 scene_*.mp4 정렬 수집
2. concat_list.txt 생성 → -f concat → raw MP4 (stream copy)
3. BGM 파일 존재 시 amix 오버레이
   volume=0.25 / afade in 3s / afade out 5s
   -c:v copy -c:a aac 192k
4. raw MP4 삭제 → 최종 {시트명}.mp4
```

#### AsterionVideoActivity.kt — UI

- ScrollView 기반 단일 화면 (코드 빌드 — XML 레이아웃 없음)
- 화자 섹션: 화자번호별 (Spinner + 🔊 테스트 버튼 + SeekBar 속도 조절) 동적 생성
- speed 범위: SeekBar 0~100 → `0.7 + progress/100 * 0.6` (0.70~1.30x)
- ForegroundService 시작: `startForegroundService()` — 백그라운드 렌더링 안전 보장
- 렌더링 완료 후 씬별 Status K열 자동 갱신 (DONE/ERROR)
- `MANAGE_ALL_FILES_ACCESS_PERMISSION` 요청 — 외부 저장소 BGV/BGM/output 접근 필수

#### ServiceAccountAuth.kt
- GCP 서비스 계정 JSON 키 기반 OAuth 2.0 토큰 발급
- Sheets API 접근용 (`https://www.googleapis.com/auth/spreadsheets`)
- 토큰 만료(1시간) 전 자동 갱신

#### RenderForegroundService.kt
- 렌더링 시작 시 Foreground Service 실행 (알림 채널 생성)
- Android 14+ `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` 명시
- 렌더링 완료·중지·오류 모든 경우 `stopService()`로 정리

---

### 7-C. 빌드 시스템

**build.gradle.kts 핵심 의존성**

| 라이브러리 | 버전 | 용도 |
|-----------|------|------|
| sherpa-onnx AAR | v1.13.2 | TTS 추론 엔진 (로컬 libs/ 배치) |
| ffmpeg-kit-full-gpl | 6.0-2.LTS | 영상 인코딩 |
| smart-exception-java | 0.2.1 | FFmpegKit 런타임 필수 의존 |
| commons-compress | 1.26.2 | tar.bz2 해제 (Android 기본 미지원) |
| okhttp3 | 4.12.0 | Sheets API + 모델 다운로드 |
| kotlinx-coroutines-android | 1.7.3 | 비동기 처리 |

**JNI 충돌 해결**
```
jniLibs.pickFirsts += "**/libc++_shared.so", "**/libonnxruntime.so"
← sherpa-onnx AAR + FFmpegKit AAR 동시 포함 시 중복 SO 충돌 방지
```

**sherpa-onnx AAR 배포 방식**  
JitPack 미사용 (v1.12.40 하드코딩 문제) → Cloud Build CI에서 GitHub Releases URL 직접 다운로드 후 `android/app/libs/` 배치

**Cloud Build (`android/cloudbuild.yaml`)**
```
Step 1: git clone victuar918/ASTERION
Step 2: Android SDK 설치 (cmdline-tools 11076708) + Gradle assembleDebug
Step 3: APK → gs://{PROJECT_ID}-asterion-apk/asterion-video-debug.apk 업로드
머신: E2_HIGHCPU_8 / 타임아웃: 3600s
```

---

### 7-D. Google Sheets 영상 대본 스프레드시트

**Spreadsheet ID**: `1ugWJmyLItD95Vz7Jq8Wjxn0_Ml5REjrhUxNZVFoIFmc`

**시트 명명 규칙**: `VS_{코인명}_{날짜}` — 앱이 VS_ 접두사로 필터링

**시트 구조**
```
행 1~5 : Video_Meta (Key | Value 형식)
행 6   : 헤더 행 (Section | Speaker | Card_Main | ...)
행 7~  : Script_Data (A~R열, 씬별 1행)
```

**Status 워크플로우**
```
READY → (앱 렌더링) → DONE
                    → ERROR (TTS/FFmpeg 실패)
```

---

### 7-E. 구현 완료 / 미구현 현황

#### ✅ 구현 완료

| 항목 | 상세 |
|------|------|
| TTS 엔진 | sherpa-onnx Supertonic 3 int8, 화자 sid 0~9, 한국어 발음 |
| 모델 자동 다운로드 | tar.bz2 스트리밍 해제 + 마커 기반 캐시 재사용 |
| FFmpegKit 렌더링 | BGV + WAV 합성, fade in/out, libx264 veryfast |
| BGM 오버레이 | amix volume 0.25, afade in/out, 최종 concat |
| BG Transition — FADE | vf fade in/out, 씬 길이 기반 자동 클램프 |
| BG Transition — NONE | vf 필터 미적용 |
| Sheets 연동 | VS_ 시트 읽기, READY 필터, DONE/ERROR 갱신 |
| 화자 UI | Spinner(sid) + SeekBar(speed) + 🔊 테스트 |
| ForegroundService | 백그라운드 렌더링 안정화 |
| 외부 저장소 접근 | MANAGE_ALL_FILES 권한 요청 흐름 |
| Cloud Build CI | APK 자동 빌드 + GCS 업로드 |
| 데이터 모델 | ScriptDataRow A~R열 전체 파싱 + Enum 정의 |

#### 🔲 미구현 / 다음 단계

| 항목 | 우선순위 | 설명 |
|------|---------|------|
| BG Transition (SLIDE_LEFT/UP, ZOOM_IN/OUT, BLUR_FADE, WIPE_RIGHT) | 高 | FFmpeg xfade 필터 적용 — 현재 FADE/NONE만 동작 |
| BG Effect (MOTION_BLUR, EDGE_GLOW, VIGNETTE) | 中 | FFmpeg 필터 파라미터 조합 필요 |
| 카드 오버레이 렌더링 | 高 | FFmpeg drawbox + 좌표 계산 (CardStyle, GradientPreset) |
| ASS 자막 생성 | 高 | Card_Main/Sub/Desc/Highlight_Word → .ass 파일 → FFmpeg subtitles 필터 |
| 카드 애니메이션 (A~G 패턴) | 中 | 씬별 키프레임 좌표 계산 + FFmpeg overlay 타임라인 |
| CardExtraEffect (HEARTBEAT/VIBRATE) | 低 | 스케일 펄스 / 진동 필터 파라미터 |
| Lottie 오브젝트 | 低 | Lottie 행성 심볼 JSON 합성 레이어 |
| Sticker/GIF 오브젝트 | 低 | StickerItem 파라미터 → FFmpeg overlay |
| 워터마크 고정 레이어 | 中 | Top_Watermark → drawtext 필터 전체 영상 고정 |
| BGM 볼륨 덕킹 | 中 | TTS 재생 구간 BGM 자동 감소 (현재: 단순 0.25 고정) |
| Section별 효과 자동 배정 | 中 | Claude MCP 연동으로 Script_Data M~R열 자동 채우기 |
| 썸네일 자동 생성 | 低 | Thumbnail_Text → 첫 씬 프레임 추출 + 텍스트 합성 |

---

### 7-F. 파일 경로 레퍼런스

```
android/
  app/
    build.gradle.kts              의존성 + 빌드 설정
    libs/                         sherpa-onnx AAR (CI 배치)
    src/main/
      AndroidManifest.xml         권한 + 서비스 선언
      java/com/asterion/video/
        AppConfig.kt              경로·상수·URL 중앙 관리
        auth/
          ServiceAccountAuth.kt  GCP SA 토큰 발급
        model/
          ScriptDataRow.kt        VideoMeta + ScriptDataRow + Enum 전체
        render/
          AsterionRenderEngine.kt 씬 렌더링 + concat + BGM
        service/
          RenderForegroundService.kt Foreground Service
        sheets/
          SheetsVideoReader.kt   Sheets API 읽기/쓰기
        tts/
          VoiceConfig.kt         SupertonicTtsEngine + VoiceConfig + SpeakerConfig
        ui/
          AsterionVideoActivity.kt 메인 UI
  cloudbuild.yaml                 CI 빌드 파이프라인
```

---

## 8. 인프라 현황

### Cloud Run 서비스 (GCP: asterion-server / 리전: asia-northeast3)

| 서비스 | URL | 역할 |
|--------|-----|------|
| mcp-server | https://mcp-server-611151539232.asia-northeast3.run.app | ASTERION AI Evolution Engine v4.0 |
| ai-chat-hub | https://ai-chat-hub-w4ozxil5aq-du.a.run.app | Hub Chat v3.0 + BTR Pipeline |

### GitHub 리포지토리

| 리포 | 역할 | 자동 배포 |
|------|------|-----------|
| victuar918/MCP_Server | MCP 서버 | mcp-server Cloud Run |
| victuar918/AI_Chat_Hub | Hub Chat | ai-chat-hub Cloud Run |
| victuar918/ASTERION | Mobile App(Archive PWA) + 영상자동화 Android | GitHub Pages(PWA) / Cloud Build(APK) |

### 자동 배포 흐름

```
GitHub push (main)
  → Cloud Build 트리거 (cloudbuild.yaml)
  → Docker 빌드 → Artifact Registry 푸시
  → Cloud Run 새 revision 배포

Android APK:
  gcloud_submit / 수동 트리거
  → android/cloudbuild.yaml
  → assembleDebug → GCS 업로드
```

### 핵심 ID 모음

```
Archive 스프레드시트:      1ym1cgr1apEyTlqtJXqrfdnLjoyJTh086CjGycMcUOS8
JuliarCalendar:           1whKvFyWmb-qbR6OJt5dcI6WOJMLB5MUIzNMlJBFeq_g
영상대본 스프레드시트:     1ugWJmyLItD95Vz7Jq8Wjxn0_Ml5REjrhUxNZVFoIFmc
SignReg 폼:               1wok68NIvsZ6ylmyDMd2-6BDr9U3y2QbzybmS-dUt2jI
EvReg 폼:                 18gldOkEnAwZBeiOsjYGjCYKr5oz_LDBJ7zfo0ClQS1M
PvReg 폼:                 1dNZcSG_XhmfUWhxjOReJiB6vUlj-ZRUYsOZwcyRVMqU
북클릿 PDF 폴더:          1w0Ll81Al6sFVLtG3X5vHdL_QYryfpTST
제작용 PDF 폴더:          117dfqwOb1VuF_CzCWpcT50baBrgzJTrZ
제품 이미지 폴더:         19-712Gf2gX3YLP23JvcQ_snUbhGByJ9v
GCP 프로젝트:             asterion-server
MCP SSE URL:             https://mcp-server-611151539232.asia-northeast3.run.app/sse
GitHub 계정:             victuar918
GitHub PAT:              Cloud Run 환경변수로 관리 (mcp-server, ai-chat-hub)
APK GCS 버킷:            gs://asterion-server-asterion-apk/
```

---

## 9. 미결 사항 및 보완 필요 항목

### 즉시 필요

**[1] signature_reg.html 구현 (HTML Registration v6.1.1)**
- 현재 구글폼으로만 접수 → 내부 HTML 접수 페이지 필요
- AI 실시간 루브릭 검증 통합 (Birth Validation → BTR Event → Question Logic 다단계 플로우)
- ASTERION Security Protocol 보안 강화 설계 포함
- 설계 문서: ASTERION_Registration_System_v6.1.1_보안강화_최종안 참조

**[2] GCP Agent Registry MCP 등록 (Gemini 직접 연결)**
- gcloud_submit 도구로 직접 처리 가능
- toolspec.json 작성 완료 (MCP_Server 리포)
- 정확한 protocolBinding enum 값 확인 필요 (HTTP_JSON 오류 확인됨)

**[3] Google OAuth 환경변수 → mcp-server 설정**
- GOOGLE_REFRESH_TOKEN / GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET
- sheets_read / sheets_write 도구의 OAuth 인증에 필요
- BTR Server `/auth` 엔드포인트에서 발급 가능

**[4] 영상자동화 — BG Transition 확장 구현 (즉시)**
- 현재 FADE / NONE만 동작
- SLIDE_LEFT / SLIDE_UP: FFmpeg xfade=slideleft/slideup 필터
- ZOOM_IN / ZOOM_OUT: FFmpeg xfade=zoom 계열
- BLUR_FADE: xfade=fade + 별도 blur 필터 체인
- 카드 오버레이(drawbox) + ASS 자막(subtitles 필터) 추가가 가장 시각적 임팩트 큼

### 중기 계획

**[5] MCP L1 도구 구현 (BTR Core)**
- btr_run_rubric / btr_generate_question / btr_consensus_analyzer / btr_hard_stop_check

**[6] Evolution Memory Ledger A/B 스키마 설계**

**[7] Hub Chat 관제시스템 기능 고도화**
- BTR 파이프라인 모니터링 UI
- Mobile App과 기능 배분 구체화

**[8] 영상자동화 — 카드 오버레이 + 자막 레이어 (중기)**
- drawbox 기반 카드 배경 (CardStyle alpha + GradientPreset)
- ASS 자막 파일 생성 → subtitles 필터 (Card_Main / Card_Sub / Card_Desc / Highlight_Word)
- AnimationPattern A~G 키프레임 → FFmpeg overlay 타임라인

**[9] 영상자동화 — Claude MCP 대본 자동 효과 배정 (중기)**
- Script_Data M~R열(BG_Effect / BG_Transition / Card_ExtraEffect / Lottie / Sticker / Gradient)을
  Claude가 Section 코드 + Script 내용 분석 → MCP(sheets_write)로 자동 기재
- Section별 기본 프리셋 + 키워드 감지 오버라이드 규칙 적용

### 기술 불확실 사항

**[A] ~~GCP Agent Registry protocolBinding 유효값 미확인~~ → 해결됨 ✅**
- **확정값**: `JSONRPC` (MCP SSE 서버에 적용)
- HTTP_JSON 오류 원인: MCP 서버가 SSE 방식이므로 HTTP_JSON 규격 미충족
- 해결 방법: UI 대신 gcloud CLI로 직접 등록
```bash
gcloud alpha agent-registry services create asterion-mcp \
  --project=asterion-server \
  --location=asia-northeast3 \
  --display-name="ASTERION BTR MCP Server" \
  --mcp-server-spec-type=tool-spec \
  --mcp-server-spec-content=@toolspec.json \
  --interfaces=url=https://mcp-server-611151539232.asia-northeast3.run.app/sse,protocolBinding=JSONRPC
```
- toolspec.json: MCP_Server 리포에 작성 완료 필요 → `github_write_file` 도구로 커밋 후 `gcloud_submit`으로 등록

**[B] mcp-server 서비스 계정 Sheets/Docs 권한**
- Cloud Build/Artifact Registry 권한은 확인됨
- Sheets API 권한: 현재 GOOGLE_REFRESH_TOKEN으로 대체
- 서비스 계정에 직접 Sheets 권한 부여 시 OAuth 불필요 — 검토 필요

---

*ASTERION 전체 시스템 로드맵 v4.0 — 단일 정본 문서*  
*작성: 2026-06-03*
