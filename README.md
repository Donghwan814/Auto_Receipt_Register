# Receipt → Notion Ledger

영수증 사진을 OCR로 분석해서 Notion 가계부 페이지를 만들거나 갱신하는 서비스.

## 핵심 모델

- **가게마다 Notion 페이지 1개.** 마시타야와 커피빈은 절대 같은 페이지로 묶이지 않음.
- **같은 가게의 추가 주문**으로 영수증이 여러 장 나오면, 그 가게의 `notionPageId` 에 영수증을 추가 업로드 → 페이지 안에서만 금액·메모가 합산됨.
- **중복 방지**: 영수증 파일의 SHA-256 해시로 같은 (pageId, fileHash) 가 이미 있으면 합산에서 제외 (`duplicated=true`).
- **집계는 항상 DB 의 전체 row 로 재계산** → Notion 페이지에 일괄 반영. 같은 영수증을 다시 올려도 금액이 부풀지 않음.

## 기술 스택
- Java 21, Spring Boot 3.3.x, Gradle
- Spring Web + WebFlux(WebClient) + Validation
- Spring Data JPA + H2 (file 모드, `./data/receipts.mv.db`)
- Lombok, Springdoc OpenAPI

## 실행

```bash
export NOTION_API_KEY=secret_xxx
export NOTION_DATABASE_ID=xxxxxxxx
export GOOGLE_VISION_API_KEY=...   # ocr.engine=google 인 경우

./gradlew bootRun
```

- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OCR 엔진 선택: `ocr.engine=mock | google | clova`

## 사용 흐름

### 1. 새 가게 영수증 — `POST /api/receipts/create-page`
영수증을 처음 올리는 가게라면 새 Notion 페이지를 만든다.

```bash
curl -X POST http://localhost:8080/api/receipts/create-page \
  -F "files=@masitaya-1.jpg" \
  -F "date=2026-05-03"
```

응답:
```json
{
  "success": true,
  "pageId": "27152c50-...",
  "title": "🍜 마시타야",
  "totalAmount": 25000,
  "date": "2026-05-03",
  "memo": "블랙라멘 + 시오라멘 + 공기밥",
  "receipts": [{ "merchant": "마시타야", "amount": 25000, "date": "2026-05-03",
                 "memo": "블랙라멘 + 시오라멘 + 공기밥", "duplicated": false }]
}
```

서로 다른 가게 영수증을 한 번에 보내면 페이지를 만들지 않고 `rejected: true` + `warning` 으로 응답한다.

### 2. 같은 가게 추가 주문 — `POST /api/receipts/add-to-page`
방금 만든 `pageId` 에 두 번째 영수증을 추가한다.

```bash
curl -X POST http://localhost:8080/api/receipts/add-to-page \
  -F "notionPageId=27152c50-..." \
  -F "files=@masitaya-2.jpg"
```

응답 (페이지 전체로 재집계됨):
```json
{
  "success": true,
  "pageId": "27152c50-...",
  "title": "🍜 마시타야",
  "totalAmount": 33500,
  "date": "2026-05-03",
  "memo": "블랙라멘 + 시오라멘 + 공기밥 + 제로콜라 + 교자",
  "receipts": [
    { "merchant": "마시타야", "amount": 8500, "memo": "제로콜라 + 교자", "duplicated": false }
  ]
}
```

여러 파일을 한 번에 올려도 됨: `-F "files=@a.jpg" -F "files=@b.jpg"`.
같은 파일을 다시 올리면 `receipts[i].duplicated=true` 가 되고 `totalAmount` 는 그대로.

### 3. 미리보기 (Notion 미반영) — `POST /api/receipts/preview`
OCR 결과가 어떻게 잡히는지만 확인.

```bash
curl -X POST http://localhost:8080/api/receipts/preview -F "files=@a.jpg"
```

### 4. 재계산 — `PATCH /api/receipts/pages/{pageId}/recalculate`
DB 의 영수증 기록으로 다시 합산해서 Notion 페이지를 강제 동기화.

```bash
curl -X PATCH http://localhost:8080/api/receipts/pages/27152c50-.../recalculate
```

### 5. 개발 테스트 — `POST /api/receipts/parse-text`
파서 동작만 확인. **Notion 을 절대 호출하지 않는다.**

```bash
curl -X POST http://localhost:8080/api/receipts/parse-text \
  -H 'Content-Type: application/json' \
  -d '{"rawText":"마시타야\n2026년 5월 3일\n블랙라멘 1\n합계 25,000원"}'
```

### Swagger 로 테스트
1. `./gradlew bootRun`
2. <http://localhost:8080/swagger-ui.html> 접속
3. `ReceiptPage` 섹션의 endpoint 펼치기 → `Try it out` → 파일 선택 → 실행

## 이모지 매칭 규칙

| 카테고리 | 키워드 |
|---|---|
| 🍜 | 라멘, 우동, 국수, 면, 마시타야 |
| ☕ | 카페, 커피, 스타벅스, 투썸, 커피빈, 메가커피, 이디야, 빽다방, 컴포즈, 더벤티 |
| 🥩 | 고기, 삼겹살, 갈비, 스테이크 |
| 🍗 | 치킨, 닭강정 |
| 🍕 | 피자 |
| 🍔 | 햄버거, 버거, 맥도날드, 버거킹, 롯데리아 |
| 🎬 | 영화, CGV, 롯데시네마, 메가박스 |
| 🅿️ | 주차, 주차장, 파킹 |
| 🏪 | 편의점, CU, GS25, 세븐일레븐 |
| 🛍️ | 다이소, 올리브영, 쇼핑, 구매 |
| 💳 | (기본값) |

## application.yaml 예시

```yaml
spring:
  datasource:
    url: jdbc:h2:file:./data/receipts;AUTO_SERVER=TRUE

notion:
  api-key: ${NOTION_API_KEY}
  database-id: ${NOTION_DATABASE_ID}
  columns:
    name: "항목"
    amount: "금액"
    date: "날짜"
    memo: "메모"

ocr:
  engine: google     # mock | google | clova
  google:
    api-key: ${GOOGLE_VISION_API_KEY}
  clova:
    secret: ${CLOVA_OCR_SECRET:}
    invoke-url: ${CLOVA_OCR_URL:}
```

## 테스트
```bash
./gradlew test
```
- `ReceiptParserTest` — 파싱 규칙 (메뉴/수량/날짜/가게명)
- `ExpenseMatcherTest` — 후보 매칭 신뢰도
- `ReceiptControllerTest` — `/parse-text` 가 Notion 미호출 / `/upload` 자동 매칭
- `ReceiptPageServiceTest` — 페이지별 합산, 중복 방지, 가게 혼합 경고, 날짜 fallback
- `CategoryClassifierTest` — 가게/메뉴 키워드 기반 카테고리 분류
- `NotionWebhookServiceTest` — 웹훅 verification/comment 처리/중복 스킵
- `NotionServicePropertiesTest` — Notion property payload (Select/Date) 형식

## 카테고리 자동 분류 (CategoryClassifier)

가게명과 메뉴 텍스트를 보고 자동으로 카테고리를 추론합니다.

| 카테고리 | 키워드 (일부) |
|---------|---------------|
| 식비 | 라멘, 마시타야, 고기, 김밥, 치킨, 피자, 햄버거, 식당 ... |
| 카페 | 커피, 카페, 스타벅스, 투썸, 커피빈, 메가커피, 아메리카노 ... |
| 간식비 | 편의점, CU, GS25, 세븐일레븐, 과자, 아이스크림, 빵 ... |
| 여가비 | 영화, CGV, 롯데시네마, 노래방, PC방, 전시, 공연 ... |
| 교통비 | 주차, 주차장, 파킹, 택시, 버스, 지하철, 톨게이트 ... |
| 쇼핑 | 다이소, 올리브영, 무신사, 쿠팡, 문구, 잡화 ... |

우선순위: (1) merchant 매칭 → (2) memo 매칭 → (3) 기본값 `식비`. 단,
`교통/주차` 키워드와 merchant 의 `카페` 키워드는 다른 카테고리보다 우선합니다.

Notion 데이터베이스에서 카테고리(Select) 컬럼을 사용하려면 환경변수
`NOTION_COL_CATEGORY=카테고리` 를 지정하세요. 미지정 시 카테고리 컬럼은 건너뜁니다.

## Notion 웹훅 (POST /api/notion/webhook)

Notion Integration 의 webhook 으로 등록하세요.

- 첫 등록 시 Notion 이 `verification_token` 페이로드를 보내면 동일 토큰을 echo 합니다.
- `comment.created` / `comment.updated` 이벤트에서 `pageId` 와 `commentId` 를 추출 → Notion 댓글 첨부의 이미지(`.jpg/.jpeg/.png/.webp`) 만 다운로드해서 `addReceiptsToPage` 처리.
- `eventId` 기반 중복 처리 방지 (`webhook_event_log`).
- `.pdf` 등 미지원 확장자는 스킵.

### Notion 데이터베이스 컬럼

| 역할 | 기본 이름 | 환경변수 |
|------|-----------|----------|
| 항목(title) | 항목 | NOTION_COL_NAME |
| 금액(number) | 금액 | NOTION_COL_AMOUNT |
| 날짜(date) | 날짜 | NOTION_COL_DATE |
| 메모(rich_text) | 메모 | NOTION_COL_MEMO |
| 카테고리(select) | (미설정) | NOTION_COL_CATEGORY |
| 등록일(date) | (미설정) | NOTION_COL_CREATED_AT |

카테고리 Select 의 옵션은 `식비 / 교통비 / 간식비 / 여가비 / 쇼핑 / 카페` 6개를 미리 만들어두세요.

### 페이지 정렬 안내

페이지 자체의 위치는 변경하지 않습니다. Notion 데이터베이스 뷰에서
정렬을 원하는 컬럼(예: `등록일` 내림차순) 으로 설정하세요.

### 디버그 엔드포인트

`notion.debug-comments=true` (또는 `NOTION_DEBUG_COMMENTS=true`) 인 경우에만,
`GET /api/notion/comments/debug?pageId=...` 로 raw 댓글 JSON 조회가 가능합니다.

## 프로파일 / 배포

- 기본 활성 프로파일: `local` (H2 file). `application-local.yaml`
- 운영: `SPRING_PROFILES_ACTIVE=prod` (MySQL). `application-prod.yaml`
  - `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` 환경변수 필요
- Health check: `GET /health` → `OK`

### Cloud Run 배포

1. `Dockerfile` 사용 (Java 21 multi-stage build).
2. Cloud Run 서비스에 secrets 를 Secret Manager 로 주입:
   - `NOTION_API_KEY`, `GOOGLE_VISION_API_KEY`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `NOTION_WEBHOOK_SECRET` 등.
3. 환경변수 `SPRING_PROFILES_ACTIVE=prod` 설정.
4. Cloud Run 이 주입하는 `PORT` 변수를 자동으로 사용 (`server.port=${PORT:8080}`).

### 로컬 웹훅 테스트 (ngrok)

```bash
ngrok http 8080
# https://xxxxx.ngrok.app/api/notion/webhook 을 Notion integration 의 webhook URL 로 등록
```

## 최종 사용 흐름 (Notion Webhook 자동 등록)

가장 간편한 흐름은 Notion 데이터베이스 + 웹훅 + 댓글 첨부 조합입니다.

1. **Notion DB 에서 빈 페이지를 새로 만든다.** 제목은 비워두거나 `신규 item`
   같은 임시 텍스트여도 OK — 시스템이 자동으로 덮어씁니다.
2. **그 페이지의 댓글 영역에 영수증 사진을 첨부**합니다 (.jpg/.jpeg/.png/.webp).
3. Notion 이 `comment.created` 웹훅을 `POST /api/notion/webhook` 으로 호출.
4. 서버가 OCR → 가게/금액/날짜/메뉴 추출 → 카테고리/이모지 자동 결정 →
   페이지 제목을 `🍜 마시타야` 같은 형식으로 자동 변환합니다. 동시에 금액·
   날짜·메모·카테고리 컬럼이 채워집니다.
5. **같은 페이지에 추가 영수증을 더 첨부**하면, 같은 가게의 추가 주문으로
   간주되어 금액이 합산되고 메모가 ` + ` 로 이어집니다.
6. **같은 이미지를 다시 올리면** SHA-256 해시 중복으로 인식되어
   `duplicated=true` 처리되고 금액은 변하지 않습니다.
7. Notion 데이터베이스 뷰는 **`날짜` 내림차순 정렬**을 권장합니다 (가장 최근
   영수증이 위로 오도록).

## 보안

- 모든 시크릿(API key, DB password) 은 환경변수 또는 Secret Manager 로만 주입합니다. yaml 에 평문 시크릿을 넣지 않습니다.
- 로그에는 토큰 등 민감정보는 단축형으로만 노출 (verification_token 은 1회성이므로 운영에서는 추가 마스킹을 고려).

