package com.example.receiptmemo.notion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Notion 댓글 API 응답 구조를 fixture 로 사용하여
 * ReceiptAttachmentService 의 JsonNode 기반 첨부 추출 로직을 검증한다.
 */
class ReceiptAttachmentServiceTest {

    private final ObjectMapper om = new ObjectMapper();
    private final ReceiptAttachmentService svc = new ReceiptAttachmentService(om);

    private static final String NOTION_COMMENTS_FIXTURE = """
            {
              "object":"list",
              "results":[
                {
                  "object":"comment",
                  "id":"35a52c50-de5a-8038-a523-001dbbdebb29",
                  "parent":{
                    "type":"page_id",
                    "page_id":"35a52c50-de5a-80b8-a718-c98c157b3fa4"
                  },
                  "attachments":[
                    {
                      "category":"image",
                      "file":{
                        "url":"https://prod-files-secure.s3.us-west-2.amazonaws.com/abc/메가커피.jpg?X-Amz-Algorithm=AWS4-HMAC",
                        "expiry_time":"2026-05-08T08:59:45.397Z"
                      }
                    }
                  ]
                }
              ]
            }
            """;

    @Test
    void 실제_Notion_댓글_JSON_에서_이미지_첨부_1개_추출() {
        List<ReceiptAttachmentService.ImageRef> refs =
                svc.extractImageAttachmentsFromRaw(NOTION_COMMENTS_FIXTURE);

        assertThat(refs).hasSize(1);
        ReceiptAttachmentService.ImageRef ref = refs.get(0);
        assertThat(ref.getUrl()).startsWith("https://prod-files-secure.s3");
        assertThat(ref.getFilename()).contains("메가커피").endsWith(".jpg");
        assertThat(ref.getContentType()).isEqualTo("image/jpeg");
    }

    @Test
    void category_image_이면_확장자_없어도_이미지로_판정() {
        String raw = """
                {"object":"list","results":[
                  {"id":"c1","attachments":[
                    {"category":"image",
                     "file":{"url":"https://x.example/abc/some-presigned-key"}}
                  ]}
                ]}
                """;
        List<ReceiptAttachmentService.ImageRef> refs = svc.extractImageAttachmentsFromRaw(raw);
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).getFilename()).startsWith("some-presigned-key")
                .doesNotContain("?");
    }

    @Test
    void external_url_도_지원() {
        String raw = """
                {"object":"list","results":[
                  {"id":"c2","attachments":[
                    {"category":"image",
                     "external":{"url":"https://example.com/photo.png"}}
                  ]}
                ]}
                """;
        List<ReceiptAttachmentService.ImageRef> refs = svc.extractImageAttachmentsFromRaw(raw);
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).getUrl()).isEqualTo("https://example.com/photo.png");
        assertThat(refs.get(0).getContentType()).isEqualTo("image/png");
    }

    @Test
    void file_upload_id_만_있고_url_없으면_skip() {
        String raw = """
                {"object":"list","results":[
                  {"id":"c3","attachments":[
                    {"category":"image",
                     "file_upload":{"id":"upload-xyz"}}
                  ]}
                ]}
                """;
        List<ReceiptAttachmentService.ImageRef> refs = svc.extractImageAttachmentsFromRaw(raw);
        assertThat(refs).isEmpty();
    }

    @Test
    void content_type_이_image_로_시작하면_이미지로_판정() {
        String raw = """
                {"object":"list","results":[
                  {"id":"c4","attachments":[
                    {"category":"file","content_type":"image/png",
                     "file":{"url":"https://x/y/foo"}}
                  ]}
                ]}
                """;
        List<ReceiptAttachmentService.ImageRef> refs = svc.extractImageAttachmentsFromRaw(raw);
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).getContentType()).isEqualTo("image/png");
    }

    @Test
    void commentId_필터로_특정_댓글만_추출() {
        String raw = """
                {"object":"list","results":[
                  {"id":"c-A","attachments":[
                    {"category":"image","file":{"url":"https://x/a.png"}}
                  ]},
                  {"id":"c-B","attachments":[
                    {"category":"image","file":{"url":"https://x/b.png"}}
                  ]}
                ]}
                """;
        List<ReceiptAttachmentService.ImageRef> refs =
                svc.extractImageAttachmentsFromRaw(raw, "c-B");
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).getUrl()).endsWith("b.png");
    }

    @Test
    void 매칭되는_commentId_가_없으면_전체로_fallback() {
        String raw = """
                {"object":"list","results":[
                  {"id":"c-A","attachments":[
                    {"category":"image","file":{"url":"https://x/a.png"}}
                  ]}
                ]}
                """;
        List<ReceiptAttachmentService.ImageRef> refs =
                svc.extractImageAttachmentsFromRaw(raw, "missing-id");
        assertThat(refs).hasSize(1);
    }

    @Test
    void results_배열이_없으면_빈_리스트() {
        assertThat(svc.extractImageAttachmentsFromRaw("{}")).isEmpty();
        assertThat(svc.extractImageAttachmentsFromRaw("")).isEmpty();
        assertThat(svc.extractImageAttachmentsFromRaw(null)).isEmpty();
    }
}
