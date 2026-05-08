package com.example.receiptmemo.notion.service;

import com.example.receiptmemo.ledger.service.ReceiptPageService;
import com.example.receiptmemo.notion.dto.api.NotionCommentAttachmentResponse;
import com.example.receiptmemo.notion.dto.api.NotionCommentResponse;
import com.example.receiptmemo.notion.persistence.WebhookEventLog;
import com.example.receiptmemo.notion.persistence.WebhookEventLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
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

    @BeforeEach
    void setUp() {
        notionService = mock(NotionService.class);
        attachmentService = mock(ReceiptAttachmentService.class);
        receiptPageService = mock(ReceiptPageService.class);
        repo = mock(WebhookEventLogRepository.class);
        webhook = new NotionWebhookService(notionService, attachmentService, receiptPageService, repo);
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
    void comment_created_에서_pageId_commentId_추출() throws Exception {
        JsonNode p = payload("""
                {"id":"evt-1","type":"comment.created",
                 "entity":{"type":"comment","id":"cmt-9"},
                 "data":{"page_id":"page-1"}}
                """);

        when(notionService.listComments("page-1")).thenReturn(comments("cmt-9", "https://files/foo.png"));
        when(attachmentService.downloadAllImages(anyList())).thenReturn(List.<MultipartFile>of(mock(MultipartFile.class)));

        webhook.handle(p);

        verify(notionService).listComments("page-1");
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
        when(notionService.listComments("page-2")).thenReturn(List.of());
        when(attachmentService.downloadAllImages(anyList())).thenReturn(List.of());

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
    void 이미지_있을때_addReceiptsToPage_호출() throws Exception {
        JsonNode p = payload("""
                {"id":"evt-4","type":"comment.created",
                 "entity":{"type":"comment","id":"c4"},
                 "data":{"page_id":"page-4"}}
                """);
        when(notionService.listComments("page-4")).thenReturn(comments("c4", "https://x/a.jpg"));
        MultipartFile mf = mock(MultipartFile.class);
        when(attachmentService.downloadAllImages(anyList())).thenReturn(List.of(mf));

        webhook.handle(p);

        verify(receiptPageService).addReceiptsToPage(eq("page-4"), anyList(), isNull());
    }

    private List<NotionCommentResponse> comments(String id, String fileUrl) {
        NotionCommentResponse c = new NotionCommentResponse();
        c.setId(id);
        NotionCommentAttachmentResponse a = new NotionCommentAttachmentResponse();
        NotionCommentAttachmentResponse.FileBody fb = new NotionCommentAttachmentResponse.FileBody();
        fb.setUrl(fileUrl);
        a.setFile(fb);
        a.setCategory("image");
        List<NotionCommentAttachmentResponse> attachments = new ArrayList<>();
        attachments.add(a);
        c.setAttachments(attachments);
        return List.of(c);
    }
}
