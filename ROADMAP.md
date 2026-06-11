# ASTERION Android — 개발 로드맵

> 최종 업데이트: 2026-06-12 (v3.28 기준)

---

## ✅ 완료된 작업 (v3.27–v3.28)

### 핵심 파이프라인
| 항목 | 버전 | 상태 |
|------|------|------|
| WAV + 카드 씬별 독립 인코딩 | v3.27 | ✅ 완료 |
| WAV–카드 타이밍 동기화 (누적 오차 제거) | v3.27 | ✅ 완료 |
| BGV 레이어 합성 (colorkey=0xFF00FF) | v3.27 | ✅ 완료 |
| BGM 페이드 (13s→15s, 0.40→0.05) | v3.25 | ✅ 완료 |
| 워터마크 (XRP/CRYPTO 자동 분기) | v3.25 | ✅ 완료 |
| 인트로 렌더링 + 면책 TTS | v3.25 | ✅ 완료 |

### 카드 렌더링 (CardRenderer)
| 항목 | 버전 | 상태 |
|------|------|------|
| Android Canvas 기반 ARGB PNG 생성 | v3.28 | ✅ 완료 |
| holdX / holdY 씬별 위치 전달 | v3.28 | ✅ 완료 |
| GradientPreset 폴백 (DEFAULT → cardStyle) | v3.28 | ✅ 완료 |
| effRGB 제거 (원본 그라디언트 색상 사용) | v3.28 | ✅ 완료 |
| 이모지 자동 제거 (FFmpeg drawtext 호환) | v3.27 | ✅ 완료 |

### 성능 & 안정성
| 항목 | 버전 | 상태 |
|------|------|------|
| 병렬 카드 인코딩 Semaphore(3) | v3.28 | ✅ 완료 |
| h264_mediacodec GPU 인코딩 + libx264 폴백 | v3.28 | ✅ 완료 |
| Foreground Service (백그라운드 우선순위 유지) | v3.28 | ✅ 완료 |
| YouTube 자동 업로드 (Resumable Upload) | v3.28 | ✅ 완료 |

---

## 🔜 즉시 해결 필요 (버그/검증)

### P0 — 다음 테스트에서 확인

1. **카드 색상 육안 확인**
   - effRGB 제거 후 TITLE(보라), CONCLUSION(남색)이 실제로 보이는지
   - DEFAULT 카드는 검정(정상) — 시트에 gradientPreset 설정 권장
   - 확인 방법: 렌더링 후 body.mp4 직접 재생

2. **GPU 인코딩 폴백 확인**
   - 로그: `[병렬 3x GPU]` → GPU 활성
   - `h264_mediacodec` 실패 시 자동으로 libx264로 전환되는지 확인

3. **YouTube 업로드 자격 증명 설정**
   - `youtube_credentials.json` 설정 (아래 참고)
   - 미설정 시 "업로드 건너뜀" 로그 후 정상 종료

#### YouTube 인증 설정 방법
```
1. Google Cloud Console → OAuth 2.0 클라이언트 ID (웹 애플리케이션 유형)
2. https://developers.google.com/oauthplayground 접속
   - YouTube Data API v3 → https://www.googleapis.com/auth/youtube.upload 선택
   - 인증 후 refresh_token 획득
3. youtube_credentials.json 생성:
   {
     "client_id": "...apps.googleusercontent.com",
     "client_secret": "...",
     "refresh_token": "1//..."
   }
4. GitHub Secret: YOUTUBE_CREDENTIALS_JSON 에 등록
   (빌드 시 assets/youtube_credentials.json 으로 자동 주입)
   또는: 디바이스 Android/data/com.asterion.video/files/ 에 직접 저장
```

---

## 📅 단기 목표 (1–2주)

### 카드 품질 개선
- [ ] **카드 반투명 효과** — BGV가 카드 뒤로 은은하게 비치는 효과
  - 현재: 카드가 완전 불투명 (BGV 완전 가림)
  - 목표: `최종색 = 카드색 × alpha + BGV × (1-alpha)`
  - 접근: `style.alpha`를 Paint.alpha에 직접 적용 (`Color.argb(styleAlpha, R, G, B)`)
  - 주의: 마젠타 배경과 블렌딩 오염 주의 → PorterDuff.Mode.SRC 또는 투명배경 방식 검토

- [ ] **DEFAULT 그라디언트 개선**
  - 현재: RGB(0,0,0) = 완전 검정
  - 목표: 아주 어두운 남색(예: #00051E)으로 변경 → 완전 검정보다 시각적으로 더 좋음
  - 파일: `ScriptDataRow.kt` GradientPreset.DEFAULT 수정

- [ ] **시트 gradientPreset 컬럼 설정 가이드**
  - 현재: 대부분 "DEFAULT" → 검정 카드
  - 권장값: TITLE, CONCLUSION, NOTICE 스타일 시트에 지정

### 안정성
- [ ] **YouTube 업로드 재시도 로직**
  - 네트워크 오류 시 3회까지 자동 재시도
  - 재시도 시 기존 세션 URL 재활용 (resumable upload 특성 활용)

- [ ] **YouTube 업로드 완료 후 시트 상태 업데이트**
  - 업로드 성공 → Sheets `videoId` 컬럼에 YouTube ID 기록
  - SheetsVideoReader.updateYouTubeId(sheet, videoId) 구현

---

## 📅 중기 목표 (1개월)

### 영상 품질
- [ ] **씬별 BGV 오프셋 매칭**
  - 현재: 전체 body에 동일 BGV stream_loop
  - 목표: 씬별로 BGV 구간이 다르게 적용 (더 자연스러운 흐름)
  - 구현: `prep.startSecs`로 `-ss` 오프셋 지정 (현재 파이프라인 유지)

- [ ] **카드 애니메이션 복원**
  - 현재: 정적 PNG 오버레이 (애니메이션 없음)
  - 목표: 슬라이드인, 페이드인 등 AnimationPattern별 효과
  - 방법: 씬별 PNG를 여러 장 생성하거나 FFmpeg overlay 타임라인 활용

- [ ] **영상 품질 프로파일 선택 UI**
  - 빠른 미리보기: CRF 28 / 720p
  - 표준: CRF 23 / 1080p (현재)
  - 고품질: CRF 18 / 1080p

### 자동화 강화
- [ ] **YouTube 공개 예약 업로드**
  - `publishAt` 파라미터로 특정 시간에 자동 공개
  - 시트에서 공개 일시 읽어서 자동 스케줄링

- [ ] **썸네일 자동 생성 + 업로드**
  - 인트로 특정 프레임 캡처 → 썸네일로 설정
  - YouTube thumbnails API 활용

- [ ] **업로드 후 YouTube 영상 ID → Archive SS 자동 기록**
  - GAS `video_update_row_status` 연계
  - 업로드 완료 → Sheets 상태 "UPLOADED" 로 업데이트

---

## 📅 장기 목표 (2–3개월)

### 완전 자동화 파이프라인
```
시트 데이터 입력
    ↓
Android 앱 (or 서버)
    ↓
[TTS → 카드 → BGV → 인트로 → BGM → 최종]
    ↓
YouTube 업로드 (비공개)
    ↓
Slack/알림 → 검토 → 수동 공개
```

- [ ] **스케줄러 (AlarmManager or WorkManager)**
  - 매일 특정 시간에 자동 영상 제작 시작
  - 배터리 최적화 예외 설정

- [ ] **Hub AI 자동 분석 연동**
  - Claude/Gemini/GPT 3자 루브릭 → 점수 낮은 씬 재생성
  - BTR 분석 결과 자동 반영

- [ ] **멀티 채널 관리**
  - 여러 YouTube 채널 계정 관리
  - 채널별 업로드 규칙 설정

### 성능 최적화
- [ ] **hevc_mediacodec (H.265) 인코딩 옵션**
  - H.264 대비 30~50% 파일 크기 절감
  - YouTube HEVC 지원 확인 후 적용

- [ ] **TTS 병렬 처리**
  - 현재: 씬별 TTS 순차 생성
  - 목표: `prepareScene`도 병렬화 (Semaphore 2-3)

- [ ] **BGV 씬 캐싱 고도화**
  - 자주 사용되는 BGV 구간 사전 인코딩 캐시

---

## 🏗️ 기술 부채 / 정리 필요

| 항목 | 우선순위 | 설명 |
|------|----------|------|
| buildCardVf 제거 | 낮음 | CardRenderer로 대체됨, 미사용 코드 정리 |
| 진단 로그 최종 정리 | 낮음 | STYLE/GRAD-RAW/COLOR 로그 설정 플래그화 |
| VideoMeta introType 표준화 | 중간 | "XRP"/"CRYPTO" 외 타입 대응 |
| 에러 복구 UI | 중간 | 중간 실패 시 어느 단계부터 재시작할지 선택 |

---

## 📋 설정 파일 목록

| 파일 | 위치 | 용도 |
|------|------|------|
| `service_account.json` | assets/ 또는 외부저장소 | Google Sheets 인증 |
| `youtube_credentials.json` | assets/ 또는 외부저장소 | YouTube Data API 인증 |
| BGV 영상 파일들 | AppConfig.BGV_DIR | 배경 영상 소스 |
| BGM 음악 파일들 | AppConfig.BGM_DIR | 배경 음악 소스 |
| 폰트 파일 | /system/fonts/ | 카드 텍스트 렌더링 |

---

*이 문서는 개발 진행에 따라 지속적으로 업데이트됩니다.*
