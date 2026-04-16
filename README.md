# ASTERION Archive System — 전체 기술 문서

> 최종 업데이트: 2026-04-17  
> 시스템 유형: Google Apps Script 기반 웹앱  
> 운영 환경: Google Sheets + Google Drive + Google Forms

---

## 목차

1. [시스템 개요](#1-시스템-개요)
2. [아키텍처](#2-아키텍처)
3. [스프레드시트 구조](#3-스프레드시트-구조)
4. [StructureCode 체계](#4-structurecode-체계)
5. [구글폼 연동](#5-구글폼-연동)
6. [HTML 페이지 목록 및 기능](#6-html-페이지-목록-및-기능)
7. [JavaScript 공통 레이어](#7-javascript-공통-레이어)
8. [GAS 함수 레퍼런스 — Archive GAS](#8-gas-함수-레퍼런스--archive-gas)
9. [GAS 함수 레퍼런스 — SignReg GAS](#9-gas-함수-레퍼런스--signreg-gas)
10. [PDF 시스템](#10-pdf-시스템)
11. [개인정보 보호 설계](#11-개인정보-보호-설계)
12. [Drive 폴더 구조](#12-drive-폴더-구조)
13. [북클릿 페이지 구성](#13-북클릿-페이지-구성)
14. [배포 및 운영 절차](#14-배포-및-운영-절차)
15. [알려진 제약 및 주의사항](#15-알려진-제약-및-주의사항)

---

## 1. 시스템 개요

ASTERION Archive System은 베딕 점성술 기반 원석 팔찌 제작 브랜드 **ASTERION**의 주문 접수부터 제작·발송·고객 기록 보존까지 전 과정을 관리하는 내부 전용 웹앱이다.

### 핵심 기능

| 기능 | 설명 |
|------|------|
| 주문 접수 | 구글폼 3종으로 접수 → StructureCode 자동 발급 |
| 제작 워크플로 | 분석·메모 → Structure Type 지정 → 원석 선택 → 디자인 → 제품 이미지 → 북클릿 → 발송 |
| PDF 자동 생성 | 접수 즉시 제작용 PDF 생성 / 작업 완료 후 고객용 북클릿 PDF 생성 |
| 개인정보 자동 삭제 | 배송 완료 15일 후 ExpireDate 기준 개인정보 자동 삭제 |
| 비상 복구 | neworder.html을 통해 미생성 StructureCode 수동 발급 및 PDF 재생성 |

---

## 2. 아키텍처

```
[사용자 (모바일 앱)]
        │
        │ HTTPS POST/GET
        ▼
[Archive GAS 웹앱] ──────────────────────────────────────────────────────┐
(Code.gs, 배포됨)                                                         │
        │                                                                 │
        ├── Archive 스프레드시트 (직접 접근)                               │
        │     ├── Archive 시트 (메인 데이터)                               │
        │     ├── StoneMaster 시트                                        │
        │     ├── SizeMaster 시트                                         │
        │     ├── Inventory 시트                                          │
        │     └── Details 시트                                            │
        │                                                                 │
        ├── SignReg 스프레드시트 (ID로 원격 접근)                           │
        │     ├── SignReg 시트                                            │
        │     ├── EvReg 시트                                              │
        │     └── PvReg 시트                                              │
        │                                                                 │
        └── Google Drive                                                  │
              ├── 북클릿 PDF 폴더 (1w0Ll81Al6sFVLtG3X5vHdL_QYryfpTST)   │
              ├── 제작용 PDF 폴더 (117dfqwOb1VuF_CzCWpcT50baBrgzJTrZ)    │
              ├── 제품 이미지 폴더 (19-712Gf2gX3YLP23JvcQ_snUbhGByJ9v)   │
              └── 원석 이미지 폴더 (14vQVzrGLT6U2NRg...)                  │
                                                                          │
[SignReg GAS] ◄───────────────────────────────────────────────────────────┘
(트리거 전용, 배포 불필요)
        │
        ├── onAnyFormSubmit() ── 폼 제출 시 자동 실행
        └── deleteExpiredData() ── 매일 03:00 실행
```

### 스프레드시트 분리 원칙

- **Archive 스프레드시트**: 작업 데이터 전용. GAS가 여기에 붙어서 웹앱으로 배포됨.
- **SignReg 스프레드시트**: 폼 응답 전용. GAS는 트리거 전용이며 웹앱 배포 불필요. Archive GAS에서 ID(`1JFR7O9wxzvK4aqw_O2s6ZUSkX8_hw03rAc02l4Km90k`)로 원격 접근.

---

## 3. 스프레드시트 구조

### 3-1. Archive 스프레드시트 — Archive 시트

| 컬럼명 | 설명 | 삭제 여부 (ExpireDate 도래 시) |
|--------|------|-------------------------------|
| StructureCode | 고유 코드 (예: S-00001AA-260410) | 유지 |
| Created Date | 접수 일시 | 유지 |
| Status | 현재 상태 | 유지 |
| GuestName | 고객 성함 | **삭제** |
| SourceSheet | 접수 시트명 | 유지 |
| StructureType | Core / Phase Structure | 유지 |
| PhaseStartDate | Phase 시작일 | 유지 |
| PhaseEndDate | Phase 종료일 | 유지 |
| BTR | Birth Time Rectification (예: 1979년 11월 07일 05시 25분) | 유지 (가공 정보) |
| Analysis | 분석 내용 | 유지 |
| Memo | 내부 메모 (타임스탬프 추가 방식) | 유지 |
| UsedStones | 사용 원석 목록 (콤마 구분) | 유지 |
| LayoutSummary | 배열 요약 | 유지 |
| DetailsStr | 디테일 문자열 | 유지 |
| ProductImage | 제품 이미지 Drive URL | 유지 |
| ForwardingDate | 발송일 | 유지 |
| DeliveryCompletedDate | 배송 완료일 | 유지 |
| ExpireDate | 삭제 예정일 (DeliveryCompletedDate + 15일 자동 계산) | 삭제 후 공백 |
| TempPDF | 제작용 PDF 파일 ID | **삭제** (파일도 Drive에서 삭제) |

> **주의**: `BirthDate`, `BirthTime`, `Phone`, `Address`는 Archive 시트에 저장하지 않음. SignReg 스프레드시트에만 보관되며 ExpireDate 도래 시 해당 행 전체 삭제.

### 3-2. Archive 스프레드시트 — StoneMaster 시트

| 컬럼명 | 설명 |
|--------|------|
| NameKr | 원석 한국어명 |
| NameEng | 원석 영문명 |
| Exp | 원석 설명 (북클릿에 표시) |
| Image | 원석 이미지 Drive URL |
| IsDeleted | 소프트 삭제 여부 (true/false) |

### 3-3. Archive 스프레드시트 — Inventory 시트

| 컬럼명 | 설명 |
|--------|------|
| ID | 재고 항목 ID (INV-XXXXXXXX) |
| NameKr | 원석 한국어명 |
| Size | 사이즈 (숫자만, 예: 4) |
| Stock | 재고 수량 |
| LastUpdate | 최종 업데이트 일시 |

> **Size 정규화**: 시트에는 `"4"` 형태로 저장, 시스템 내부에서는 `"4mm"` 형태로 통일 (`normalizeSize()` 함수).

### 3-4. Archive 스프레드시트 — Details 시트

| 컬럼명 | 설명 |
|--------|------|
| StructureCode | 코드 |
| InvID | Inventory 항목 ID |
| Qty | 수량 |
| SeqNo | 배열 순서 |

### 3-5. SignReg 스프레드시트

| 시트명 | 연결 폼 | StructureCode 접두사 |
|--------|---------|---------------------|
| SignReg | Signature Registration | S- |
| EvReg | Evolution Process Registration | V- |
| PvReg | Private Registration | L- |

각 시트의 주요 컬럼: `타임스탬프`, `성함`, `생년월일`, `출생시간`, `연락처`, `배송주소`, `StructureCode`

---

## 4. StructureCode 체계

### 형식

```
[접두사]-[5자리 순번][2자리 알파벳]-[YYMMDD]

예시:
S-00001AA-260410   ← Signature Registration, 2026년 4월 10일 접수
V-00001AA-260410   ← Evolution Process Registration
L-00001AA-260410   ← Private Registration
```

### 접두사 의미

| 접두사 | 접수 경로 | 의미 |
|--------|----------|------|
| S | Signature Registration | 일반 접수 |
| V | Evolution Process Registration | 기존 고객 재설계 신청 |
| L | Private Registration | 비밀 경로 (선한 영향력) |

### 순번 체계

- 5자리 숫자 (00001 ~ 99999) + 2자리 알파벳 (AA ~ ZZ)
- 순서: 00001AA → 99999AA → 00001AB → ... → 99999ZZ → 00001BA → ...
- 최대 발급 가능 코드 수: 99,999 × 676 = **약 6,759만 개**
- 날짜 접미사(YYMMDD)로 같은 번호라도 날짜가 다르면 중복 없음

### 채번 로직

1. Archive 시트 + 해당 SignReg 시트에서 기존 코드 전부 수집
2. 가장 큰 순번을 찾아 다음 번호 계산
3. 오늘 날짜 접미사 조합 → 새 코드 확정

---

## 5. 구글폼 연동

### 폼 목록

| 폼명 | 폼 ID | 응답 시트 | 코드 접두사 |
|------|-------|----------|------------|
| Signature Registration | `1wok68NIvsZ6ylmyDMd2-6BDr9U3y2QbzybmS-dUt2jI` | SignReg | S |
| Evolution Process Registration | `18gldOkEnAwZBeiOsjYGjCYKr5oz_LDBJ7zfo0ClQS1M` | EvReg | V |
| Private Registration | `1dNZcSG_XhmfUWhxjOReJiB6vUlj-ZRUYsOZwcyRVMqU` | PvReg | L |

### 폼 제출 시 자동 처리 흐름

```
구글폼 제출
    ↓
onAnyFormSubmit(e)  ← 시트명으로 라우팅
    ↓
_handleFormSubmit(e, sheetName, prefix)
    ↓
1. StructureCode 채번 (_generateCode)
2. SignReg/EvReg/PvReg 시트 해당 행에 StructureCode 기록
3. Archive 시트에 최소 항목 행 추가:
   - StructureCode, Created Date, Status(Tasking), GuestName, SourceSheet
4. EvReg 전용: 첫 번째 질문 답변(기존 Archive No.) → Memo 저장
```

### 트리거 설정

SignReg GAS의 `installTriggers()` 함수를 1회 실행:
- `onAnyFormSubmit` ← 스프레드시트 폼 제출 트리거
- `deleteExpiredData` ← 매일 오전 3시 시간 기반 트리거

---

## 6. HTML 페이지 목록 및 기능

### 네비게이션 흐름

```
index.html (홈)
    ├── worksdesk.html (작업 목록)
    │     └── [코드 선택]
    │           ├── analysismemo.html (분석·메모·BTR)
    │           │     └── structuretype.html (Structure Type 지정)
    │           │           └── selectstone.html (원석 선택)
    │           │                 └── design.html (디자인)
    │           │                       └── productimage.html (제품 이미지)
    │           │                             └── booklet.html (북클릿 미리보기·PDF)
    │           │                                   └── forwarding.html (발송 관리)
    │           └── (홈으로)
    ├── structureindex.html (코드 직접 조회·Status 변경)
    ├── inventory.html (원석 목록)
    │     └── stoneinfo.html (원석 상세·이미지)
    │           └── invenmanage.html (재고 관리)
    │                 └── newlisting.html (신규 원석 등록)
    └── neworder.html (미생성 코드 발급·PDF 재생성)
```

### 페이지별 상세

#### index.html — 홈
- 배경: `bg_home.jpg`
- 버튼 2×2 그리드 배치 (30% 크기)
  - Works Desk / Code Index / Inventory / New Registration

#### worksdesk.html — 작업 목록
- Status가 `Tasking`, `A/S`, `Forwarding`인 항목만 표시
- 하단 버튼 2행 4열:
  - 1행: 홈 / Analysis&Memo / Structure Type / 원석 선택
  - 2행: Design / Product Image / Booklet / Forwarding

#### analysismemo.html — 분석·메모·BTR
- **Internal Memo**: 타임스탬프 추가 방식 (덮어쓰기 없음)
- **BTR** (Birth Time Rectification): 12자리 숫자 입력 → `1979년 11월 07일 05시 25분` 형식으로 저장
  - 실시간 프리뷰 표시
  - 기존 값 있으면 입력란에 복원
  - Save 시 덮어쓰기
- **Analysis**: 전체 덮어쓰기 방식
- Save 버튼: Memo(추가) + BTR(저장) + Analysis(덮어쓰기) 동시 처리

#### structuretype.html — Structure Type 지정
- Title: `ttl_type.png`
- 드롭다운: `Core Structure` / `Phase Structure` (투명도 60%)
- Phase Structure 선택 시: Phase Start Date, Phase End Date 입력창 추가
- Save → Archive 시트 `StructureType`, `PhaseStartDate`, `PhaseEndDate` 저장

#### selectstone.html — 원석 선택
- StoneMaster에서 원석 목록 로드
- 선택한 원석 → Archive `UsedStones` 저장 (localStorage 병행)

#### design.html — 디자인
- 선택된 원석별 사이즈 선택
- flat `details` 배열 + `isButton:true` 마커로 배열 구성
- `activePlusIndex`로 삽입 위치 추적
- `compactButtons()`로 연속 버튼 방지
- Save & Deduct: 재고 차감 + LayoutSummary + DetailsStr 저장

#### productimage.html — 제품 이미지
- 사진 업로드 → Drive `PRODUCT_FOLDER` 저장
- Archive `ProductImage` 컬럼 업데이트

#### booklet.html — 북클릿 미리보기·PDF
- 11페이지 구성 (1, 2, 11페이지: 금색 배경)
- 좌측: 페이지 버튼 11개
- 우측: 미리보기 영역
- Create PDF → 북클릿 PDF 폴더에 저장 → `/view` URL로 열기 (폰 자동 저장 방지)
- **TempPDF 저장 안 함** (북클릿용과 제작용 분리)

#### forwarding.html — 발송 관리
- Forwarding Date 입력 → Status: `Forwarding`
- Delivery Completed Date 입력 → Status: `Complete`, ExpireDate 자동 계산(+15일)
- Memo에 날짜 이력 자동 추가

#### structureindex.html — 코드 직접 조회
- 입력 형식: `S-00001AA-260410` (최대 16자)
- 실시간 프리뷰: `S-□□□□□□□-□□□□□□`
- 가이드: `S/V/L - 5자리 순번 + 2자리 알파 - YYMMDD`
- 버튼: Select(코드 선택) / A/S / Complete (Status 변경)

#### neworder.html — 미생성 코드 발급·PDF 재생성
- SignReg/EvReg/PvReg 전체 주문 타임스탬프 순 표시
- StructureCode 미생성 행: 빨간 "미생성" 표시
- StructureCode 미생성 행 선택 → **Create Code** 버튼 출현
  - 코드 발급 + Archive 행 생성 + 제작용 PDF 생성 + TempPDF 저장
- StructureCode 있는 행 선택 → **Create PDF** 버튼으로 제작용 PDF 재생성

#### inventory.html, stoneinfo.html, invenmanage.html, newlisting.html
- 원석 목록 조회·관리
- 재고 수량 업데이트
- 원석 이미지 업로드
- 신규 원석 등록

---

## 7. JavaScript 공통 레이어

### 로드 순서

```html
<script src="js/config.js"></script>
<script src="js/api_contract.js"></script>
<script src="js/state.js"></script>
<script src="js/api.js"></script>
<script src="js/ui.js"></script>
```

### config.js
```javascript
CONFIG.API_BASE  // Archive GAS 웹앱 배포 URL
```

### api_contract.js — Action 상수 (A 객체)

| 상수 | action 값 | 용도 |
|------|-----------|------|
| `A.GET_TASKING_LIST` | `getTaskingList` | worksdesk 목록 |
| `A.GET_ARCHIVE_ITEM` | `getArchiveItem` | 단건 조회 |
| `A.UPDATE_STATUS` | `updateStatus` | Status 변경 |
| `A.GET_ALL_STONES` | `getAllStones` | 원석 목록 |
| `A.SAVE_USED_STONES` | `saveUsedStones` | 원석 선택 저장 |
| `A.GET_USED_ITEMS` | `getUsedItems` | 디자인용 원석·사이즈 |
| `A.GET_DETAILS` | `getDetails` | 디자인 상세 조회 |
| `A.SAVE_DESIGN` | `saveDesign` | 디자인 저장 |
| `A.UPDATE_ANALYSIS_MEMO` | `updateAnalysisMemo` | 분석·BTR 저장 |
| `A.APPEND_MEMO` | `appendMemo` | 메모 추가 |
| `A.UPLOAD_PRODUCT_IMAGE` | `uploadProductImage` | 제품 이미지 |
| `A.SAVE_STRUCTURE_TYPE` | `saveStructureType` | Structure Type 저장 |
| `A.GET_BOOKLET_DATA` | `getBookletData` | 북클릿 데이터 |
| `A.CREATE_PDF` | `createPdf` | 북클릿 PDF 생성 |
| `A.UPDATE_FORWARDING` | `updateForwarding` | 발송 정보 저장 |
| `A.GET_ALL_REG_ROWS` | `getAllRegRows` | 전체 폼 접수 목록 |
| `A.CREATE_CODE_AND_REGISTER` | `createCodeAndRegister` | 코드 발급+등록 |
| `A.RECREATE_PDF` | `recreatePdf` | 제작용 PDF 재생성 |

### state.js — 상태 관리

```javascript
// StructureCode 관리
getStructureCode()   // URL ?code= 파라미터 또는 window._sc
setStructureCode()
clearAndGo(page)     // 코드 초기화 후 이동
goPage(page, code)   // 코드 유지하며 이동
requireStructureCode() // 없으면 worksdesk로 리다이렉트

// 캐시
cacheSet(key, value, ttl)
cacheGet(key)
cacheClear(key)

// Size 유틸
normalizeSize(s)    // "4" → "4mm"
getSizeNumber(s)    // "4mm" → 4
```

### api.js — API 래퍼

- `apiPost(data)`: 재시도 2회, JSON 파싱, 에러 toast
- `apiGetArchiveItem(sc)`: 60초 캐시
- `apiGetAllStones()`: 5분 캐시
- `apiUpdateAnalysisMemo(sc, analysis, btr)`: BTR 파라미터 포함
- `apiSaveDesign(sc, details, deduct, layoutSummary, detailsStr)`: 단일 GAS 콜

### ui.js — 공통 UI

- `showLoading(text)` / `hideLoading()`
- `toastOk(msg)` / `toastErr(msg)`
- `confirmPopup(question)` → Promise\<boolean\>
- `setStatus(elId, msg, type)`
- `initImageBox(opts)`: 이미지 선택 박스

---

## 8. GAS 함수 레퍼런스 — Archive GAS

### 상수

```javascript
var IMAGE_FOLDER_ID   = "14vQVzrGLT6U2NRg...";  // 원석 이미지
var PRODUCT_FOLDER_ID = "19-712Gf2gX3YLP23...";  // 제품 이미지
var PDF_FOLDER_ID     = "1w0Ll81Al6sFVLtG3...";  // 북클릿 PDF
var ORDER_PDF_FOLDER_ID = "117dfqwOb1VuF_Cz..."; // 제작용 PDF
var SIGREG_SS_ID      = "1JFR7O9wxzvK4aqw...";   // SignReg 스프레드시트
```

### PDF 템플릿 ID (원석 수별)

| 원석 수 | 템플릿 ID |
|--------|----------|
| 1 | `10Bed5FIVomdiOJ5TxlL5PZuQ4boFteo8yQ9drATp348` |
| 2 | `1UnSB__hHTCVqvChC9GrzTMfSWYwOyTUkCiqyF2xTzN4` |
| 3 | `1WqBd0cr1GCulgkk_TZYS5vsuWGgjt58XyBxJjh4-BhA` |
| 4 | `1WVj3TTV0erfo176rh2BrHmignpZVM437V-CuaejK5Rw` |
| 5 | `1cD7YQD4C9WQZ5pUhYsRA87bHNnOr73fTxcVK0cqbrNk` |
| 6 | `1io4ZwyQa17qKlEYnpK_zqvsM7LGb5UvUUEJBizWjvXQ` |
| 7 | `17_UuLgH5TFXRwhOth1PjzKnBQ3rDGYyK4Jp4NmtGWfs` |
| 8+ | `1LupkyTxF8g67YEbt0rzfptqKt85qc4LXVa-amg-QPAM` |

### 주요 함수

#### `createPdf(structureCode)` — 북클릿 PDF 생성
- 원석 수 기준 템플릿 자동 선택
- 플레이스홀더 치환 후 PDF 변환
- **PDF_FOLDER_ID** (북클릿 폴더)에 저장
- TempPDF 저장 안 함
- 반환 URL: `/view` 형식 (자동 다운로드 방지)

#### `createOrderPdf(structureCode)` — 제작용 PDF 생성
- SignReg 시트에서 폼 원본 데이터 조회
- Google Docs로 제작 신청서 생성 후 PDF 변환
- **ORDER_PDF_FOLDER_ID** (제작용 폴더)에 저장
- Archive 시트 `TempPDF` 컬럼에 파일 ID 저장 (삭제 추적용)
- 반환 URL: `/view` 형식

#### `_removeIfPhaseBlock(body, removeBlock)` — 조건 블록 처리
```
템플릿 형식:
  {{#IF_PHASE}}Structure 유효 구간{{/IF_PHASE}}
  {{#IF_PHASE}}{{PhaseStartDate}} ~ {{PhaseEndDate}}{{/IF_PHASE}}

removeBlock=true  (Core Structure): 태그 포함 단락 전체 삭제
removeBlock=false (Phase Structure): 태그 텍스트만 제거, 내용 유지
```

#### `updateAnalysisMemo(payload)` — 분석·BTR 저장
- `payload.analysis`: 덮어쓰기
- `payload.btr`: 셀 형식을 텍스트(`@`)로 강제 후 저장 (날짜 자동변환 방지)
- `payload.memo`: 선택적 덮어쓰기

#### `updateForwarding(payload)` — 발송 정보 저장
- ForwardingDate, DeliveryCompletedDate 저장
- Memo에 날짜 이력 자동 추가
- DeliveryCompletedDate 입력 시 ExpireDate = +15일 자동 계산

#### `deleteExpiredData()` — 만료 데이터 삭제
- 기준: Archive 시트 `ExpireDate` ≤ 오늘
- 삭제 항목:
  1. Archive `GuestName` 컬럼값 삭제
  2. Archive `TempPDF` 파일 ID로 Drive 파일 삭제 → 컬럼값 삭제
  3. `ExpireDate` 컬럼값 삭제 (재실행 방지)
  4. SignReg/EvReg/PvReg 해당 StructureCode 행 전체 삭제

#### `createCodeAndRegister(payload)` — 코드 발급·등록·PDF 생성
- `prefixMap`: SignReg→"S-", EvReg→"V-", PvReg→"L-"
- `_generateNextCode(prefix, existingCodes)` 호출
- Archive 최소 항목 행 추가: StructureCode, Created Date, Status(Tasking), GuestName, SourceSheet
- `createOrderPdf(code)` 자동 호출 → TempPDF 저장

#### `normalizeSize(s)` — Size 정규화
```javascript
"4"   → "4mm"
"12"  → "12mm"
"4mm" → "4mm"
```

---

## 9. GAS 함수 레퍼런스 — SignReg GAS

> 웹앱 배포 불필요. 트리거 전용.

### 상수

```javascript
SIGNREG_CONFIG.ARCHIVE_SS_ID = "1ym1cgr1apEyTlqtJXqrfdnLjoyJTh086CjGycMcUOS8"

// Archive에 생성할 최소 컬럼
ARCHIVE_COLS = [
  "StructureCode", "Created Date", "Status",
  "GuestName", "SourceSheet"
]
```

### 채번 함수

```javascript
_alphaToIdx(a)         // "AA" → 0, "ZZ" → 675
_idxToAlpha(i)         // 0 → "AA", 675 → "ZZ"
_parseCodeMiddle(code, prefix)  // "S-00001AA-260410" → {num:1, alpha:"AA"}
_generateNextCode(prefix, existingCodes)  // 다음 코드 계산
_generateCode(prefix, regSheetName)       // 최종 채번 (중복 확인 포함)
```

### 폼 제출 처리

```javascript
onAnyFormSubmit(e)
  → e.range.getSheet().getName() 으로 시트 구분
  → SignReg: "S-" / EvReg: "V-" / PvReg: "L-"
  → _handleFormSubmit(e, sheetName, prefix)

_handleFormSubmit(e, sheetName, prefix)
  1. 폼 응답에서 성함 추출
  2. StructureCode 채번
  3. 해당 시트 마지막 행에 코드 기록
  4. Archive 시트에 최소 항목 추가
  5. EvReg 전용: values[1] (기존 Archive No.) → Memo 저장
```

### 만료 삭제

```javascript
deleteExpiredData()
  기준: Archive 시트 ExpireDate
  삭제:
    - Archive GuestName 컬럼값
    - TempPDF Drive 파일 (파일 ID로 직접 삭제)
    - ExpireDate 컬럼값 (재실행 방지)
    - SignReg/EvReg/PvReg 해당 StructureCode 행 전체
```

---

## 10. PDF 시스템

### 제작용 PDF vs 북클릿 PDF

| 구분 | 제작용 PDF | 북클릿 PDF |
|------|-----------|-----------|
| 생성 시점 | 폼 접수 직후 자동 / neworder.html 수동 | booklet.html에서 수동 |
| 내용 | StructureCode + 폼 원본 답변 | 고객용 11페이지 북클릿 |
| 저장 폴더 | ORDER_PDF_FOLDER_ID | PDF_FOLDER_ID |
| TempPDF 기록 | ✅ (삭제 추적) | ❌ |
| ExpireDate 삭제 | ✅ | - |

### 북클릿 플레이스홀더 목록

```
{{STRUCTURE_CODE}}    ← Archive No.
{{GUEST_NAME}}        ← 고객 성함
{{LAYOUT_SUMMARY}}    ← 배열 요약
{{ANALYSIS}}          ← 분석 내용
{{MEMO}}              ← 내부 메모
{{DETAILS_STR}}       ← 디테일 문자열
{{BTR}}               ← Birth Time Rectification (예: 1979년 11월 07일 05시 25분)
{{StructureType}}     ← Core Structure / Phase Structure
{{#IF_PHASE}}Structure 유효 구간{{/IF_PHASE}}
{{#IF_PHASE}}{{PhaseStartDate}} ~ {{PhaseEndDate}}{{/IF_PHASE}}
{{PRODUCT_IMG}}       ← 제품 이미지 (252px)
{{STONE1_KR}} ~ {{STONE8_KR}}     ← 원석 한국어명
{{STONE1_ENG}} ~ {{STONE8_ENG}}   ← 원석 영문명
{{STONE1_EXP}} ~ {{STONE8_EXP}}   ← 원석 설명
{{STONE1_IMG}} ~ {{STONE8_IMG}}   ← 원석 이미지 (50px)
```

### `{{#IF_PHASE}}` 처리 규칙

- 템플릿에서 **같은 줄에 인라인**으로 작성 필수:
  ```
  {{#IF_PHASE}}내용{{/IF_PHASE}}
  ```
- `PhaseStartDate` 있음(Phase Structure): 태그만 제거, 내용 유지
- `PhaseStartDate` 없음(Core Structure): 태그 포함 단락 전체 삭제

---

## 11. 개인정보 보호 설계

### 데이터 흐름 원칙

```
구글폼 (성함, 생년월일, 출생시간, 연락처, 배송주소 전부 기록)
    ↓
SignReg/EvReg/PvReg 시트 (원본 전부 보관)
    ↓ (최소 정보만 이동)
Archive 시트 (StructureCode, Created Date, Status, GuestName, SourceSheet만 생성)
```

### ExpireDate 자동 계산

```
DeliveryCompletedDate 입력
    ↓
ExpireDate = DeliveryCompletedDate + 15일 (자동 계산·저장)
    ↓
deleteExpiredData() 매일 03:00 실행
    ↓
Archive GuestName 삭제
TempPDF Drive 파일 삭제
SignReg/EvReg/PvReg 해당 행 전체 삭제
```

### 영구 보존 항목 (개인 특정 불가)

Archive 시트에서 ExpireDate 이후에도 남는 항목:
- StructureCode, Created Date, Status, SourceSheet
- StructureType, PhaseStartDate, PhaseEndDate
- **BTR** (가공된 출생 시각, 단독으로 개인 특정 불가)
- Analysis, Memo, UsedStones, LayoutSummary, DetailsStr
- ProductImage, ForwardingDate, DeliveryCompletedDate

---

## 12. Drive 폴더 구조

```
Google Drive
├── 북클릿 PDF 폴더 (1w0Ll81Al6sFVLtG3X5vHdL_QYryfpTST)
│     └── SC-00001AA-260410_1744200000000.pdf
│
├── 제작용 PDF 폴더 (117dfqwOb1VuF_CzCWpcT50baBrgzJTrZ)
│     └── ORDER_SC-00001AA-260410_20260410_123456.pdf
│
├── 제품 이미지 폴더 (19-712Gf2gX3YLP23JvcQ_snUbhGByJ9v)
│     └── SC00001AA2604101744200000000.jpg
│
└── 원석 이미지 폴더 (14vQVzrGLT6U2NRg3kbzZY7PC-MYHsu3ZX-...)
      └── StoneName1744200000000.jpg
```

---

## 13. 북클릿 페이지 구성

| 페이지 | 배경 | 내용 |
|--------|------|------|
| 1 | 금색 | ASTERION 브랜드 커버 |
| 2 | 금색 | Signature Archive (Archive No. + GuestName + 문구) |
| 3 | 흰색 | Structure Design (제품 이미지 + Layout Summary) |
| 4 | 흰색 | Gemstone Description (원석 이미지·한국명·영문명·설명) |
| 5 | 흰색 | Analysis (전체 페이지, 스크롤 가능) |
| 6 | 흰색 | Structure Type 안내 (Core / Phase 설명) |
| 7 | 흰색 | 현재 적용된 Structure Type + Phase 유효 구간 |
| 8 | 흰색 | Evolution Process 안내 |
| 9 | 흰색 | QR코드 + Evolution Process 신청 URL |
| 10 | 흰색 | 브랜드 철학 텍스트 |
| 11 | 금색 | 슬로건 |

---

## 14. 배포 및 운영 절차

### Archive GAS 배포

1. Archive 스프레드시트 → 확장 프로그램 → Apps Script
2. 코드 교체 후 저장 (Ctrl+S)
3. 배포 → 배포 관리 → 연필 아이콘 → **새 버전** → 배포
4. 웹앱 URL 복사 → `js/config.js`의 `CONFIG.API_BASE`에 입력

### SignReg GAS 트리거 설치

1. SignReg 스프레드시트 → Apps Script
2. 코드 교체 후 저장
3. `installTriggers` 함수 선택 → 실행 → 권한 승인
4. 트리거 메뉴에서 `onAnyFormSubmit`, `deleteExpiredData` 등록 확인

### GAS 트리거 실패 알림 설정 (권장)

Apps Script → 트리거 → 각 트리거 편집 → 실패 알림: **즉시**

### Archive 시트 수동 정리 사항

- `RegisterDate` 컬럼 삭제 → `Created Date` 통합
- `ProductImageUrl` 컬럼 삭제 → `ProductImage` 통합
- `BirthDate`, `BirthTime`, `Phone`, `Address` 컬럼 삭제 (Archive에 불필요)

---

## 15. 알려진 제약 및 주의사항

### Google Docs replaceText() 제약

- 단락(paragraph) 내부에서만 정규식 작동
- 여러 단락에 걸친 블록 치환 불가
- 해결: `{{#IF_PHASE}}내용{{/IF_PHASE}}` 형식으로 한 줄에 작성

### Google Sheets 날짜 자동변환

- 한국어 날짜 문자열을 날짜 타입으로 자동변환하는 문제
- 해결: `setNumberFormat("@")` 으로 셀 형식을 텍스트로 강제 지정 (BTR 저장 시 적용)

### GAS Lock Service

- 동시 접근 방지를 위해 쓰기 작업에 `LockService.getScriptLock()` 사용
- 복합 동작 시 Lock을 한 번만 획득하고 내부 함수(`_saveDetailsCore` 등)를 직접 호출

### Size 형식 불일치

- Inventory/SizeMaster 시트: 숫자만 저장 (`"4"`, `"12"`)
- 프론트엔드 전송: mm 포함 (`"4mm"`, `"12mm"`)
- `normalizeSize()` 함수로 모든 경로에서 통일

### StructureCode 형식 변경 이력

| 시기 | 형식 | 예시 |
|------|------|------|
| 초기 | `SC-001` | SC-001 |
| 중간 | `S-00001-260327` | S-00001-260327 |
| 현재 | `S-00001AA-260410` | S-00001AA-260410 |

> 현재 Archive 시트에 구형 코드(S-00001-260327)와 신형 코드(S-00001AA-260410)가 혼재할 수 있음. 시스템은 두 형식 모두 조회 가능.

---

*ASTERION Archive System — 내부 기술 문서*  
*작성 기준일: 2026-04-17*
