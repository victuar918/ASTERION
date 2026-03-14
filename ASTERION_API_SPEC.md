# ASTERION API SPECIFICATION
**Version:** 1.0  
**Last Updated:** 2025  
**Stack:** GitHub Pages → Google Apps Script → Google Sheets

---

## 0. 설계 원칙

| 원칙 | 내용 |
|------|------|
| **단일 진실** | 모든 action 이름은 `api_contract.js`에서 상수로 관리 |
| **명명 규칙** | Frontend 내부: `camelCase` / GAS 전송 payload: `camelCase` / GAS 내부: `camelCase` |
| **케이스 통일** | 모든 payload 필드 `camelCase` (이번 수정의 핵심 결정) |
| **응답 스키마** | 항상 `{ success: boolean, error?: string, ...data }` |
| **에러 전파** | GAS는 절대 throw하지 않음. 항상 `{ success: false, error }` 반환 |

---

## 1. 공통 요청/응답 구조

### 요청 (Frontend → GAS)
```json
{
  "action": "ACTION_NAME",
  "structureCode": "SC-001",
  ...actionSpecificFields
}
```

### 응답 (GAS → Frontend)
```json
{
  "success": true | false,
  "error": "에러 메시지 (success:false일 때만)",
  ...actionSpecificResponse
}
```

---

## 2. Action 명세

### 2-1. `getUsedItems`
**목적:** design.html — 원석별 사이즈 버튼 행 구성  
**호출 시점:** design.html 초기 로드  

**Request:**
```json
{ "action": "getUsedItems", "structureCode": "SC-001" }
```

**Response:**
```json
{
  "success": true,
  "items": [
    { "nameKr": "자수정", "sizes": ["4mm", "6mm", "8mm"] },
    { "nameKr": "루비",   "sizes": ["6mm", "8mm", "10mm"] }
  ]
}
```

**규칙:**
- `sizes`: Stock > 0 인 것만 포함
- `sizes`: SizeMaster 순서 기준 정렬
- Archive.UsedStones 없으면 전체 원석 폴백

---

### 2-2. `saveDetails`
**목적:** Details 시트 저장 + 선택적 재고 차감  
**호출 시점:** Save → 팝업 → Yes/No  

**Request:**
```json
{
  "action": "saveDetails",
  "structureCode": "SC-001",
  "deduct": true,
  "details": [
    { "nameKr": "자수정", "size": "6mm", "qty": 2 },
    { "nameKr": "루비",   "size": "4mm", "qty": 1 }
  ]
}
```

**Response:**
```json
{
  "success": true,
  "saved": 2,
  "deducted": 2,
  "deduct": true
}
```

**규칙:**
- `deduct: true`  → 저장 + Inventory.Stock 차감
- `deduct: false` → 저장만 (차감 없음)
- `deduct` 미전달 → `false`로 처리 (안전 기본값)
- 기존 동일 StructureCode 행 먼저 삭제 후 재삽입
- Inventory에 없는 nameKr+size 는 로그 후 스킵 (전체 실패 아님)

---

### 2-3. `updateLayoutSummary`
**목적:** Archive.LayoutSummary 컬럼 업데이트 (PDF 출력용)  
**호출 시점:** saveDetails 완료 직후 (직렬 실행)  

**Request:**
```json
{
  "action": "updateLayoutSummary",
  "structureCode": "SC-001",
  "layoutSummary": "6mm(자수정) - 4mm(루비) - 6mm(자수정)"
}
```

**Response:**
```json
{ "success": true }
```

**규칙:**
- `layoutSummary`: qty만큼 개별 항목으로 펼쳐서 저장
  - `자수정 6mm × 2pcs` → `"6mm(자수정) - 6mm(자수정)"`
- LayoutSummary 컬럼 없으면 자동 생성

---

### 2-4. `getDetails`
**목적:** Details 시트에서 기존 구성 로드  
**호출 시점:** design.html 재진입 시  

**Request:**
```json
{ "action": "getDetails", "structureCode": "SC-001" }
```

**Response:**
```json
{
  "success": true,
  "details": [
    { "invId": "INV-ABC-0001", "nameKr": "자수정", "size": "6mm", "qty": 2, "seqNo": 1 },
    { "invId": "INV-ABC-0002", "nameKr": "루비",   "size": "4mm", "qty": 1, "seqNo": 2 }
  ]
}
```

**규칙:**
- `seqNo` 순 정렬
- Inventory에서 찾지 못한 invId는 스킵
- Details 시트 없으면 `{ success: true, details: [] }`

---

### 2-5. `updateStatus`
**목적:** Archive.Status 변경  

**Request:**
```json
{
  "action": "updateStatus",
  "structureCode": "SC-001",
  "status": "Tasking"
}
```

**Response:**
```json
{ "success": true, "updated": 1 }
```

**유효값:** `Pending | Approved | Rejected | Archived | Tasking | Forwarding | A/S`

---

### 2-6. `updateAnalysisMemo`
**목적:** analysismemo.html — Analysis/Memo 저장  

**Request:**
```json
{
  "action": "updateAnalysisMemo",
  "structureCode": "SC-001",
  "analysis": "분석 내용",
  "memo": "메모 내용"
}
```

**Response:**
```json
{ "success": true }
```

---

### 2-7. `uploadProductImage`
**목적:** productimage.html — 제품 이미지 업로드  

**Request:**
```json
{
  "action": "uploadProductImage",
  "structureCode": "SC-001",
  "byteArray": [255, 216, 255, ...],
  "mimeType": "image/jpeg",
  "fileName": "product.jpg"
}
```

**Response:**
```json
{ "success": true, "url": "https://drive.google.com/uc?export=view&id=..." }
```

**규칙:**
- 최대 4MB
- 허용 MIME: `image/jpeg, image/jpg, image/png, image/webp, image/gif`
- 기존 이미지 자동 삭제 (Drive 고아 파일 방지)

---

### 2-8. `saveUsedStones`
**목적:** selectstone.html — 사용 원석 선택 저장  

**Request:**
```json
{
  "action": "saveUsedStones",
  "structureCode": "SC-001",
  "stones": ["자수정", "루비", "아쿠아마린"]
}
```

**Response:**
```json
{ "success": true, "saved": 3 }
```

---

### 2-9. `getAllStones`
**목적:** 전체 원석 목록 조회 (doGet 경유)  

**Request:** `GET ?action=getAllStones`

**Response:**
```json
{
  "success": true,
  "stones": [
    { "nameKr": "자수정", "nameEng": "Amethyst", "exp": "설명", "image": "https://..." }
  ]
}
```

---

### 2-10. `uploadImage` (원석 이미지)
**목적:** newlisting.html / GemstoneInfo.html — 원석 이미지 업로드  

**Request:**
```json
{
  "action": "uploadImage",
  "nameKr": "자수정",
  "byteArray": [...],
  "mimeType": "image/jpeg",
  "fileName": "amethyst.jpg"
}
```

**Response:**
```json
{ "success": true, "url": "https://drive.google.com/uc?..." }
```

---

## 3. 에러 코드 목록

| error 문자열 패턴 | 원인 | 조치 |
|-----------------|------|------|
| `structureCode 필요` | payload에 structureCode 없음 | URL param 확인 |
| `StructureCode 없음: SC-xxx` | Archive 시트에 해당 행 없음 | 데이터 확인 |
| `Inventory 미존재: nameKr+size` | 재고 없는 조합 | 재고 확인 |
| `Lock 획득 실패` | 동시 요청 충돌 | 잠시 후 재시도 |
| `Drive 업로드 실패: ...` | Drive API 오류 | 네트워크/권한 확인 |
| `이미지 크기 초과 (최대 4MB)` | 파일 크기 초과 | 이미지 압축 후 재시도 |
| `허용되지 않는 이미지 형식: ...` | MIME 타입 오류 | JPG/PNG로 변환 |
| `POST body 없음` | GET으로 잘못 호출 | POST로 재시도 |

---

## 4. 필드 명명 규칙 (확정)

| 위치 | 규칙 | 예시 |
|------|------|------|
| Frontend 내부 변수 | camelCase | `structureCode`, `nameKr`, `invId` |
| API payload 전송 | camelCase | `"structureCode"`, `"nameKr"`, `"qty"` |
| GAS 내부 변수 | camelCase | `structureCode`, `nameKr`, `qty` |
| Sheets 컬럼명 | PascalCase | `StructureCode`, `NameKr`, `Qty` |
| GAS → Sheet 읽기 시 | PascalCase로 headers 탐색 | `headers.indexOf("NameKr")` |

**핵심 결정:** payload는 전구간 camelCase  
(이전 오류의 원인이었던 PascalCase payload 완전 제거)

---

## 5. 페이지별 사용 Action 매핑

| 페이지 | 사용 Action |
|--------|------------|
| `selectstone.html` | `getAllStones`, `saveUsedStones` |
| `design.html` | `getUsedItems`, `getDetails`, `saveDetails`, `updateLayoutSummary` |
| `analysismemo.html` | `updateAnalysisMemo`, `updateStatus` |
| `productimage.html` | `uploadProductImage` |
| `worksdesk.html` | `getTaskingList`, `updateStatus` |
| `newlisting.html` | `addStone`, `uploadImage` (google.script.run) |
