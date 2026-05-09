package com.example.receiptmemo.notion.service;

import com.example.receiptmemo.global.support.ByteArrayMultipartFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Notion 댓글 raw JSON에서 이미지 첨부를 추출하고 다운로드한다.
 *
 * 지원 구조:
 *  - root.results[].attachments[]
 *  - attachment.category == "image"
 *  - attachment.file.url        (Notion presigned URL)
 *  - attachment.external.url    (외부 URL)
 *  - attachment.content_type
 *  - attachment.file_upload.id  (url 없는 경우 → skip + 로그)
 */
@Slf4j
@Service
public class ReceiptAttachmentService {

    private final WebClient downloadClient;
    private final ObjectMapper objectMapper;

    public ReceiptAttachmentService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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

    /** raw JSON 문자열에서 이미지 첨부 추출. */
    public List<ImageRef> extractImageAttachmentsFromRaw(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return List.of();
        try {
            return extractImageAttachments(objectMapper.readTree(rawJson), null);
        } catch (Exception e) {
            log.error("[Attachment] raw JSON 파싱 실패. err={}", e.getMessage());
            return List.of();
        }
    }

    /** raw JSON 문자열에서 특정 commentId만 필터링하여 이미지 첨부 추출. */
    public List<ImageRef> extractImageAttachmentsFromRaw(String rawJson, String onlyCommentId) {
        if (rawJson == null || rawJson.isBlank()) return List.of();
        try {
            return extractImageAttachments(objectMapper.readTree(rawJson), onlyCommentId);
        } catch (Exception e) {
            log.error("[Attachment] raw JSON 파싱 실패. err={}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Notion comments list response (root)에서 이미지 첨부 추출.
     * @param onlyCommentId null/blank이면 전체, 값이 있으면 해당 commentId만
     */
    public List<ImageRef> extractImageAttachments(JsonNode root, String onlyCommentId) {
        List<ImageRef> out = new ArrayList<>();
        if (root == null || root.isMissingNode() || root.isNull()) {
            log.info("[Attachment] root JSON 비어있음");
            return out;
        }
        JsonNode results = root.path("results");
        if (!results.isArray()) {
            log.info("[Attachment] results 배열 없음. node={}", root.path("object").asText("?"));
            return out;
        }

        boolean filterByComment = onlyCommentId != null && !onlyCommentId.isBlank();
        log.info("[Attachment] comments count={}, filterByCommentId={}",
                results.size(), filterByComment ? onlyCommentId : "(none)");

        int matchedComments = 0;
        for (JsonNode comment : results) {
            String commentId = comment.path("id").asText("");
            if (filterByComment && !onlyCommentId.equals(commentId)) continue;
            matchedComments++;

            JsonNode atts = comment.path("attachments");
            int attCount = atts.isArray() ? atts.size() : 0;
            log.info("[Attachment]  comment id={}, attachments={}", commentId, attCount);
            if (attCount == 0) continue;

            int idx = 0;
            for (JsonNode a : atts) {
                String category = a.path("category").asText("");
                String contentType = a.path("content_type").asText("");
                String fileUrl = nonBlank(a.path("file").path("url").asText(null));
                String externalUrl = nonBlank(a.path("external").path("url").asText(null));
                String fileUploadId = nonBlank(a.path("file_upload").path("id").asText(null));

                log.info("[Attachment]    #{} category={}, content_type={}, file.url? {}, external.url? {}",
                        idx, category, contentType.isBlank() ? "(none)" : contentType,
                        fileUrl != null, externalUrl != null);

                String url = fileUrl != null ? fileUrl : externalUrl;
                if (url == null) {
                    if (fileUploadId != null) {
                        log.info("[Attachment]    file_upload.id={} 만 있고 url 없음 → skip", fileUploadId);
                    } else {
                        log.info("[Attachment]    url 없음 → skip");
                    }
                    idx++;
                    continue;
                }

                boolean isImage = isImageAttachment(category, contentType, url);
                if (!isImage) {
                    log.info("[Attachment]    image 판정 실패 → skip (category={}, ct={})", category, contentType);
                    idx++;
                    continue;
                }

                String filename = inferFilename(url, fallbackFilename(commentId, idx, contentType, category));
                String ct = !contentType.isBlank() ? contentType : guessContentType(filename);
                out.add(new ImageRef(url, filename, ct));
                idx++;
            }
        }

        if (filterByComment && matchedComments == 0) {
            log.warn("[Attachment] commentId={} 매칭 댓글 없음 → 전체 댓글로 fallback", onlyCommentId);
            return extractImageAttachments(root, null);
        }
        log.info("[Attachment] 최종 이미지 추출 개수 = {}", out.size());
        return out;
    }

    /**
     * Notion block children raw JSON 에서 image/file block 의 이미지 URL 추출.
     * 지원:
     *  - block.type = "image", image.type = "file" → image.file.url
     *  - block.type = "image", image.type = "external" → image.external.url
     *  - block.type = "file",  file.type  = "file" → file.file.url
     */
    public List<ImageRef> extractImageRefsFromBlocksRaw(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return List.of();
        try {
            return extractImageRefsFromBlocks(objectMapper.readTree(rawJson));
        } catch (Exception e) {
            log.error("[Attachment] blocks raw JSON 파싱 실패. err={}", e.getMessage());
            return List.of();
        }
    }

    public List<ImageRef> extractImageRefsFromBlocks(JsonNode root) {
        List<ImageRef> out = new ArrayList<>();
        if (root == null || root.isMissingNode() || root.isNull()) return out;
        JsonNode results = root.path("results");
        if (!results.isArray()) {
            log.info("[Attachment-Block] results 배열 없음. node={}", root.path("object").asText("?"));
            return out;
        }

        log.info("[Attachment-Block] block count={}", results.size());
        int idx = 0;
        for (JsonNode block : results) {
            String type = block.path("type").asText("");
            ImageRef ref = imageRefFromBlock(block, type, idx);
            if (ref != null) out.add(ref);
            idx++;
        }
        log.info("[Attachment-Block] block 이미지 추출 개수 = {}", out.size());
        return out;
    }

    private ImageRef imageRefFromBlock(JsonNode block, String type, int idx) {
        JsonNode body = block.path(type);
        if (!body.isObject()) return null;

        String url = null;
        String contentType = null;

        if ("image".equals(type) || "file".equals(type)) {
            String mediaType = body.path("type").asText("");
            if ("file".equals(mediaType)) {
                url = nonBlank(body.path("file").path("url").asText(null));
            } else if ("external".equals(mediaType)) {
                url = nonBlank(body.path("external").path("url").asText(null));
            }
            contentType = nonBlank(body.path("content_type").asText(null));
        }
        if (url == null) return null;

        if ("file".equals(type)) {
            // file block 은 이미지 확장자/contentType 일 때만 영수증 후보로 본다.
            if (!urlLooksLikeImage(url) && (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/"))) {
                log.info("[Attachment-Block] file block 인데 이미지 아님 → skip");
                return null;
            }
        }

        String blockId = block.path("id").asText("blk-" + idx);
        String filename = inferFilename(url, fallbackFilename(blockId, idx, contentType, "image"));
        String ct = (contentType != null && !contentType.isBlank()) ? contentType : guessContentType(filename);
        log.info("[Attachment-Block] image url 추출. type={}, filename={}", type, filename);
        return new ImageRef(url, filename, ct);
    }

    /** url 기준 중복 제거 (LinkedHashSet 순서 유지). */
    public List<ImageRef> dedupeByUrl(List<ImageRef> a, List<ImageRef> b) {
        java.util.LinkedHashMap<String, ImageRef> map = new java.util.LinkedHashMap<>();
        if (a != null) for (ImageRef r : a) {
            if (r != null && r.getUrl() != null) map.putIfAbsent(r.getUrl(), r);
        }
        if (b != null) for (ImageRef r : b) {
            if (r != null && r.getUrl() != null) map.putIfAbsent(r.getUrl(), r);
        }
        return new ArrayList<>(map.values());
    }

    /** Notion presigned URL을 다운로드한다. 실패 시 null. */
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

    /** ImageRef 리스트를 다운로드해 MultipartFile 리스트로 변환. */
    public List<MultipartFile> downloadAll(List<ImageRef> refs) {
        List<MultipartFile> files = new ArrayList<>();
        if (refs == null || refs.isEmpty()) return files;
        for (ImageRef ref : refs) {
            try {
                byte[] data = downloadAttachment(ref.getUrl());
                if (data == null || data.length == 0) {
                    log.warn("[Attachment] 다운로드 결과가 비어있음. file={}", ref.getFilename());
                    continue;
                }
                files.add(convertToMultipartFile(data, ref.getFilename(), ref.getContentType()));
                log.info("[Attachment] 다운로드 성공. filename={}, contentType={}, size={}, sha256[0..8]={}",
                        ref.getFilename(), ref.getContentType(), data.length, sha256Prefix(data));
            } catch (Exception e) {
                log.error("[Attachment] 처리 실패. file={}, err={}", ref.getFilename(), e.getMessage());
            }
        }
        return files;
    }

    /** raw JSON → 이미지 추출 → 다운로드. */
    public List<MultipartFile> downloadAllImagesFromRaw(String rawJson) {
        return downloadAll(extractImageAttachmentsFromRaw(rawJson));
    }

    public List<MultipartFile> downloadAllImagesFromRaw(String rawJson, String onlyCommentId) {
        return downloadAll(extractImageAttachmentsFromRaw(rawJson, onlyCommentId));
    }

    // ---------- helpers ----------

    private boolean isImageAttachment(String category, String contentType, String url) {
        if (category != null && "image".equalsIgnoreCase(category)) return true;
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/")) return true;
        return urlLooksLikeImage(url);
    }

    private boolean urlLooksLikeImage(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        // strip query for extension check
        int q = lower.indexOf('?');
        String pathish = q >= 0 ? lower.substring(0, q) : lower;
        return pathish.endsWith(".jpg") || pathish.endsWith(".jpeg")
                || pathish.endsWith(".png") || pathish.endsWith(".webp")
                || lower.contains(".jpg") || lower.contains(".jpeg")
                || lower.contains(".png") || lower.contains(".webp");
    }

    private String inferFilename(String url, String fallback) {
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (path != null) {
                int slash = path.lastIndexOf('/');
                if (slash >= 0 && slash < path.length() - 1) {
                    String last = path.substring(slash + 1);
                    if (!last.isBlank()) {
                        try {
                            return URLDecoder.decode(last, StandardCharsets.UTF_8);
                        } catch (Exception ignore) {
                            return last;
                        }
                    }
                }
            }
        } catch (Exception ignored) { /* fall through */ }
        return fallback;
    }

    private String fallbackFilename(String commentId, int index, String contentType, String category) {
        String ext = ".jpg";
        if (contentType != null) {
            String ct = contentType.toLowerCase(Locale.ROOT);
            if (ct.contains("png")) ext = ".png";
            else if (ct.contains("webp")) ext = ".webp";
            else if (ct.contains("jpeg") || ct.contains("jpg")) ext = ".jpg";
        }
        String safeId = commentId == null ? "unknown" : commentId.replaceAll("[^a-zA-Z0-9-]", "");
        if (safeId.isBlank()) safeId = "unknown";
        return "receipt-" + safeId + "-" + index + ext;
    }

    private String guessContentType(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase(Locale.ROOT);
        int q = lower.indexOf('?');
        if (q >= 0) lower = lower.substring(0, q);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private String nonBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private String sha256Prefix(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4 && i < dig.length; i++) {
                sb.append(String.format("%02x", dig[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "(n/a)";
        }
    }
}
