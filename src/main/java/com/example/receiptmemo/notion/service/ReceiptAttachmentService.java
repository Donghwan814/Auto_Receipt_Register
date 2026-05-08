package com.example.receiptmemo.notion.service;

import com.example.receiptmemo.global.support.ByteArrayMultipartFile;
import com.example.receiptmemo.notion.dto.api.NotionCommentAttachmentResponse;
import com.example.receiptmemo.notion.dto.api.NotionCommentResponse;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Notion 댓글 첨부에서 이미지를 추출/다운로드해 MultipartFile 로 변환.
 */
@Slf4j
@Service
public class ReceiptAttachmentService {

    private static final Set<String> IMAGE_EXT = Set.of(".jpg", ".jpeg", ".png", ".webp");

    private final WebClient downloadClient;

    public ReceiptAttachmentService() {
        this.downloadClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(20 * 1024 * 1024))
                .build();
    }

    @Getter
    @RequiredArgsConstructor
    public static class ImageRef {
        private final String url;
        private final String filename;
        private final String contentType;
    }

    public List<ImageRef> extractImageAttachments(List<NotionCommentResponse> comments) {
        List<ImageRef> out = new ArrayList<>();
        if (comments == null) return out;
        for (NotionCommentResponse c : comments) {
            if (c.getAttachments() == null) continue;
            for (NotionCommentAttachmentResponse a : c.getAttachments()) {
                String url = a.resolveUrl();
                if (url == null) continue;
                String filename = inferFilename(url, a.getName());
                String ext = ext(filename);
                if (".pdf".equals(ext)) {
                    log.info("[Attachment] pdf 첨부는 건너뜁니다. file={}", filename);
                    continue;
                }
                if (!IMAGE_EXT.contains(ext)) {
                    log.info("[Attachment] 지원하지 않는 확장자, 건너뜁니다. file={}, ext={}", filename, ext);
                    continue;
                }
                String ct = guessContentType(ext);
                out.add(new ImageRef(url, filename, ct));
            }
        }
        return out;
    }

    public byte[] downloadAttachment(String url) {
        try {
            return downloadClient.get()
                    .uri(URI.create(url))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> resp.createException().map(e -> (Throwable) e))
                    .bodyToMono(byte[].class)
                    .block();
        } catch (Exception e) {
            log.error("[Attachment] 다운로드 실패. url={}, err={}", url, e.getMessage());
            return null;
        }
    }

    public MultipartFile convertToMultipartFile(byte[] bytes, String filename, String contentType) {
        return new ByteArrayMultipartFile("files", filename, contentType, bytes);
    }

    /** comments → MultipartFile 리스트 (실패는 개별 skip). */
    public List<MultipartFile> downloadAllImages(List<NotionCommentResponse> comments) {
        List<ImageRef> refs = extractImageAttachments(comments);
        log.info("[Attachment] 이미지 {}개 추출", refs.size());
        List<MultipartFile> files = new ArrayList<>();
        for (ImageRef ref : refs) {
            try {
                byte[] data = downloadAttachment(ref.getUrl());
                if (data == null || data.length == 0) {
                    log.warn("[Attachment] 다운로드 결과가 비어있음. file={}", ref.getFilename());
                    continue;
                }
                files.add(convertToMultipartFile(data, ref.getFilename(), ref.getContentType()));
                log.info("[Attachment] 다운로드 성공. file={}, size={}", ref.getFilename(), data.length);
            } catch (Exception e) {
                log.error("[Attachment] 처리 실패. file={}, err={}", ref.getFilename(), e.getMessage());
            }
        }
        return files;
    }

    private String inferFilename(String url, String fallbackName) {
        try {
            String path = URI.create(url).getPath();
            if (path != null) {
                int slash = path.lastIndexOf('/');
                if (slash >= 0 && slash < path.length() - 1) {
                    return path.substring(slash + 1);
                }
            }
        } catch (Exception ignored) {}
        return fallbackName != null ? fallbackName : "attachment";
    }

    private String ext(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return "";
        String e = filename.substring(dot).toLowerCase(Locale.ROOT);
        int q = e.indexOf('?');
        if (q > 0) e = e.substring(0, q);
        return e;
    }

    private String guessContentType(String ext) {
        return switch (ext) {
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".png" -> "image/png";
            case ".webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }
}
