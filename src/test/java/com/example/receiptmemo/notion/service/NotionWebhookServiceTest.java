package com.example.receiptmemo.notion.service;

import com.example.receiptmemo.ledger.service.ReceiptPageService;
import com.example.receiptmemo.notion.persistence.WebhookEventLog;
import com.example.receiptmemo.notion.persistence.WebhookEventLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotionWebhookServiceTest {

    private NotionService notionService;
    private ReceiptAttachmentService attachmentService;
    private ReceiptPageService receiptPageService;
    private WebhookEventLogRepository repo;
    private NotionWebhookService webhook;
    private final ObjectMapper om = new ObjectMapper();

    private static final String RAW_WITH_IMAGE = """
            {
              "object":"list",
              "results":[
                {"object":"comment","id":"cmt-9",
                 "parent":{"type":"page_id","page_id":"page-1"},
                 "attachments":[
                   {"category":"image",
                    "file":{"url":"https://prod-files-secure.s3/foo.png","expiry_time":"2026-05-08T08:59:45.397Z"}}
                 ]}
              ]
            }
            """;

    private static final String RAW_EMPTY = "{\"object\":\"list\",\"results\":[]}";

    @BeforeEach
    void setUp() {
        notionService = mock(NotionService.class);
        attachmentService = mock(ReceiptAttachmentService.class, CALLS_REAL_METHODS);
        // override constructor-only dependencies — use a fresh real-ish mock built around a real impl:
        attachmentService = mock(ReceiptAttachmentService.class);
        receiptPageService = mock(ReceiptPageService.class);
        repo = mock(WebhookEventLogRepository.class);
        webhook = new NotionWebhookService(notionService, attachmentService, receiptPageService, repo);
        webhook.retryDelaysMs = new long[]{0L, 0L, 0L}; // 테스트 빠르게
    }

    private JsonNode payload(String json) throws Exception {
        return om.readTree(json);
    }

    @Test
    void verification_token_요청은_단축처리() throws Exception {
        JsonNode p = payload("{\"verification_token\":\"abc123\"}");
        NotionWebhookService.WebhookResult r = webhook.handle(p);
        assertThat(r.isVerification).isTrue();
        assertThat(r.verificationToken).isEqualTo("abc123");
        verifyNoInteractions(notionService, attachmentService, receiptPageService);
    }

    @Test
    void comment_created_에서_pageId_commentId_추출후_처리() throws Exception {
        JsonNode p = payload("""
                {"id":"evt-1","type":"comment.created",
                 "entity":{"type":"comment","id":"cmt-9"},
                 "data":{"page_id":"page-1"}}
                """);

        when(notionService.listCommentsRaw("page-1")).thenReturn(RAW_WITH_IMAGE);
        when(attachmentService.extractImageAttachmentsFromRaw(RAW_WITH_IMAGE, "cmt-9"))
                .thenReturn(List.of(new ReceiptAttachmentService.ImageRef(
                        "https://prod-files-secure.s3/foo.png", "foo.png", "image/png")));
        when(attachmentService.downloadAll(anyList())).thenReturn(List.of(mock(MultipartFile.class)));

        webhook.handle(p);

        verify(notionService).listCommentsRaw("page-1");
        verify(receiptPageService).addReceiptsToPage(eq("page-1"), anyList(), isNull());
        verify(repo).save(any(WebhookEventLog.class));
    }

    @Test
    void 중복_eventId_는_스킵() throws Exception {
        when(repo.existsByEventId("evt-dup")).thenReturn(true);
        JsonNode p = payload("""
                {"id":"evt-dup","type":"comment.created",
                 "entity":{"type":"comment","id":"c1"},
                 "data":{"page_id":"page-1"}}
                """);

        webhook.handle(p);

        verifyNoInteractions(notionService, attachmentService, receiptPageService);
        verify(repo, never()).save(any());
    }

    @Test
    void 첨부_없으면_addReceiptsToPage_호출없음() throws Exception {
        JsonNode p = payload("""
                {"id":"evt-2","type":"comment.created",
                 "entity":{"type":"comment","id":"c2"},
                 "data":{"page_id":"page-2"}}
                """);
        when(notionService.listCommentsRaw("page-2")).thenReturn(RAW_EMPTY);
        when(attachmentService.extractImageAttachmentsFromRaw(RAW_EMPTY, "c2")).thenReturn(List.of());
        when(attachmentService.downloadAll(anyList())).thenReturn(List.of());

        webhook.handle(p);

        verify(receiptPageService, never()).addReceiptsToPage(anyString(), anyList(), any());
    }

    @Test
    void pageId_누락시_200_OK_무처리() throws Exception {
        JsonNode p = payload("{\"id\":\"evt-3\",\"type\":\"comment.created\"}");
        webhook.handle(p);
        verifyNoInteractions(notionService, attachmentService, receiptPageService);
    }

    @Test
    void page_content_updated_에서도_pageId_있으면_댓글_재시도() throws Exception {
        JsonNode p = payload("""
                {"id":"evt-pc","type":"page.content_updated",
                 "entity":{"type":"page","id":"page-7"}}
                """);
        when(notionService.listCommentsRaw("page-7")).thenReturn(RAW_WITH_IMAGE);
        when(attachmentService.extractImageAttachmentsFromRaw(eq(RAW_WITH_IMAGE), isNull()))
                .thenReturn(List.of(new ReceiptAttachmentService.ImageRef(
                        "https://prod-files-secure.s3/foo.png", "foo.png", "image/png")));
        when(attachmentService.downloadAll(anyList())).thenReturn(List.of(mock(MultipartFile.class)));

        webhook.handle(p);

        verify(notionService).listCommentsRaw("page-7");
        verify(receiptPageService).addReceiptsToPage(eq("page-7"), anyList(), isNull());
    }
}
