package com.example.receiptmemo.notion.service;

import com.example.receiptmemo.global.config.NotionConfig;
import com.example.receiptmemo.global.exception.CustomException;
import com.example.receiptmemo.global.exception.ErrorCode;
import com.example.receiptmemo.notion.dto.NotionPageCandidateResponse;
import com.example.receiptmemo.notion.dto.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Notion API 연동 서비스.
 *  - updateMemo : 페이지의 메모(rich_text) 컬럼 업데이트
 *  - getAmount  : 페이지의 금액(number) 컬럼 조회
 *  - findCandidates : 가계부 데이터베이스에서 날짜±1일/금액/가게명으로 검색
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotionService {

    private final WebClient notionWebClient;
    private final NotionConfig notionConfig;
    private final ObjectMapper objectMapper;

    /** 페이지의 메모 컬럼을 갱신. (메모만 단독 업데이트) */
    public void updateMemo(String pageId, String memo) {
        updateMemoOnly(pageId, memo);
    }

    /** updateMemo 의 명시적 별칭. 메모 단독 업데이트. */
    public void updateMemoOnly(String pageId, String memo) {
        String memoCol = notionConfig.getColumns().getMemo();
        NotionPageUpdateRequest body = NotionPageUpdateRequest.forRichText(memoCol, memo);

        notionWebClient.patch()
                .uri("/pages/{id}", pageId)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toError)
                .bodyToMono(String.class)
                .block();

        log.info("[Notion] memo updated. pageId={}", pageId);
    }

    /**
     * 가계부 페이지에 항목/금액/날짜/카테고리/메모 컬럼을 일괄 업데이트.
     */
    public void updateExpensePage(String pageId, String title, Integer amount, LocalDate date,
                                  String category, String memo) {
        Map<String, Object> properties = buildExpenseProperties(title, amount, date, category, memo, /*setCreatedAt*/ false);
        Map<String, Object> body = Map.of("properties", properties);

        notionWebClient.patch()
                .uri("/pages/{id}", pageId)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toError)
                .bodyToMono(String.class)
                .block();

        log.info("[Notion] expense page updated. pageId={}, title={}, amount={}, category={}",
                pageId, title, amount, category);
    }

    /**
     * 가계부 데이터베이스에 새 페이지를 생성한다. 생성된 pageId 반환.
     */
    public String createExpensePage(String title, Integer amount, LocalDate date,
                                    String category, String memo) {
        Map<String, Object> properties = buildExpenseProperties(title, amount, date, category, memo, /*setCreatedAt*/ true);
        Map<String, Object> body = Map.of(
                "parent", Map.of("database_id", notionConfig.getDatabaseId()),
                "properties", properties
        );

        JsonNode created = notionWebClient.post()
                .uri("/pages")
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toError)
                .bodyToMono(JsonNode.class)
                .block();

        String pageId = created == null ? null : created.path("id").asText(null);
        if (pageId == null || pageId.isBlank()) {
            throw new CustomException(ErrorCode.NOTION_API_ERROR, "페이지 생성 응답에 id 가 없습니다.");
        }
        log.info("[Notion] expense page created. pageId={}, title={}, category={}", pageId, title, category);
        return pageId;
    }

    /** 항목/금액/날짜/카테고리/메모 properties 페이로드 빌더. null 인 필드는 제외. */
    Map<String, Object> buildExpenseProperties(String title, Integer amount, LocalDate date,
                                               String category, String memo, boolean setCreatedAt) {
        Map<String, Object> props = new LinkedHashMap<>();
        var cols = notionConfig.getColumns();

        if (title != null) {
            props.put(cols.getName(), Map.of(
                    "title", List.of(Map.of("text", Map.of("content", title)))
            ));
        }
        if (amount != null) {
            Map<String, Object> num = new HashMap<>();
            num.put("number", amount);
            props.put(cols.getAmount(), num);
        }
        if (date != null) {
            props.put(cols.getDate(), Map.of(
                    "date", Map.of("start", date.toString())
            ));
        }
        if (category != null && !category.isBlank() && cols.getCategory() != null && !cols.getCategory().isBlank()) {
            props.put(cols.getCategory(), Map.of(
                    "select", Map.of("name", category)
            ));
        }
        if (memo != null) {
            props.put(cols.getMemo(), Map.of(
                    "rich_text", List.of(Map.of("text", Map.of("content", memo)))
            ));
        }
        if (setCreatedAt && cols.getCreatedAt() != null && !cols.getCreatedAt().isBlank()) {
            props.put(cols.getCreatedAt(), Map.of(
                    "date", Map.of("start", java.time.OffsetDateTime.now().toString())
            ));
        }
        return props;
    }

    /** 페이지의 댓글 목록 조회 (raw JsonNode 반환). */
    public JsonNode listCommentsRaw(String pageId) {
        return notionWebClient.get()
                .uri(uriBuilder -> uriBuilder.path("/comments").queryParam("block_id", pageId).build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toError)
                .bodyToMono(JsonNode.class)
                .block();
    }

    /** 페이지의 댓글 목록 조회 (DTO 매핑). */
    public List<com.example.receiptmemo.notion.dto.api.NotionCommentResponse> listComments(String pageId) {
        com.example.receiptmemo.notion.dto.api.NotionCommentListResponse resp = notionWebClient.get()
                .uri(uriBuilder -> uriBuilder.path("/comments").queryParam("block_id", pageId).build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toError)
                .bodyToMono(com.example.receiptmemo.notion.dto.api.NotionCommentListResponse.class)
                .block();
        if (resp == null || resp.getResults() == null) return List.of();
        return resp.getResults();
    }

    /** 페이지의 금액(number) 컬럼을 읽는다. 없으면 null. */
    public Integer getAmount(String pageId) {
        JsonNode page = notionWebClient.get()
                .uri("/pages/{id}", pageId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toError)
                .bodyToMono(JsonNode.class)
                .block();
        if (page == null) return null;
        JsonNode num = page.path("properties").path(notionConfig.getColumns().getAmount()).path("number");
        return num.isNumber() ? num.intValue() : null;
    }

    /**
     * 가계부 데이터베이스 후보 검색.
     *  - date 가 있으면 ±1일 범위로 조회 (HIGH/MEDIUM 후보를 모두 잡기 위함)
     *  - amount 가 있으면 number==amount 필터
     *  - merchantOrName 이 있으면 title contains 필터
     * 결과는 매칭/스코어링 전 단계의 목록이므로 confidence 는 비어있다.
     */
    public List<NotionPageCandidateResponse> findCandidates(String date, Integer amount, String merchantOrName) {
        List<NotionPropertyFilter> ands = new ArrayList<>();
        String dateCol = notionConfig.getColumns().getDate();
        String amountCol = notionConfig.getColumns().getAmount();
        String nameCol = notionConfig.getColumns().getName();

        if (date != null && !date.isBlank()) {
            LocalDate d = safeParse(date);
            if (d != null) {
                ands.add(NotionPropertyFilter.builder()
                        .property(dateCol)
                        .date(NotionDateFilter.builder().on_or_after(d.minusDays(1).toString()).build())
                        .build());
                ands.add(NotionPropertyFilter.builder()
                        .property(dateCol)
                        .date(NotionDateFilter.builder().on_or_before(d.plusDays(1).toString()).build())
                        .build());
            }
        }
        if (amount != null) {
            ands.add(NotionPropertyFilter.builder()
                    .property(amountCol)
                    .number(NotionNumberFilter.builder().equals(amount).build())
                    .build());
        }
        if (merchantOrName != null && !merchantOrName.isBlank()) {
            ands.add(NotionPropertyFilter.builder()
                    .property(nameCol)
                    .title(NotionTitleFilter.builder().contains(merchantOrName).build())
                    .build());
        }

        NotionDatabaseQueryRequest req = NotionDatabaseQueryRequest.builder()
                .filter(ands.isEmpty() ? null : NotionCompoundFilter.builder().and(ands).build())
                .page_size(20)
                .build();

        NotionDatabaseQueryResponse resp = notionWebClient.post()
                .uri("/databases/{id}/query", notionConfig.getDatabaseId())
                .bodyValue(req)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toError)
                .bodyToMono(NotionDatabaseQueryResponse.class)
                .block();

        List<NotionPageCandidateResponse> candidates = new ArrayList<>();
        if (resp == null || resp.getResults() == null) return candidates;

        for (NotionDatabaseQueryResponse.Page page : resp.getResults()) {
            JsonNode props = page.getProperties();
            if (props == null) continue;
            String title = props.path(nameCol).path("title").path(0).path("plain_text").asText("");
            JsonNode amountNode = props.path(amountCol).path("number");
            Integer amt = amountNode.isNumber() ? amountNode.intValue() : null;
            String dt = props.path(dateCol).path("date").path("start").asText(null);

            candidates.add(NotionPageCandidateResponse.builder()
                    .pageId(page.getId())
                    .title(title)
                    .amount(amt)
                    .date(dt)
                    .build());
        }
        return candidates;
    }

    /** WebClient 에러 응답을 CustomException 으로 변환. */
    private Mono<Throwable> toError(org.springframework.web.reactive.function.client.ClientResponse resp) {
        return resp.bodyToMono(String.class).defaultIfEmpty("").map(body -> {
            String message = body;
            try {
                NotionErrorResponse parsed = objectMapper.readValue(body, NotionErrorResponse.class);
                if (parsed.getMessage() != null) {
                    message = "[" + parsed.getCode() + "] " + parsed.getMessage();
                }
            } catch (Exception ignore) { /* keep raw body */ }

            HttpStatusCode status = resp.statusCode();
            log.error("[Notion] API error. status={}, body={}", status, body);

            if (status.value() == 404) {
                return new CustomException(ErrorCode.NOTION_PAGE_NOT_FOUND, message);
            }
            return new CustomException(ErrorCode.NOTION_API_ERROR, message);
        });
    }

    private LocalDate safeParse(String s) {
        try {
            return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
