package com.example.receiptmemo.notion.dto.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class NotionCommentAttachmentResponse {
    /** "image" / "file" / "external" 등 */
    private String category;
    /** "file" 또는 "external" */
    private String type;
    private FileBody file;
    private ExternalBody external;
    /** category 가 image 인 경우 image 객체에 file/external 이 있을 수 있음 */
    private ImageBody image;
    private String name;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FileBody {
        private String url;
        @JsonProperty("expiry_time")
        private String expiryTime;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExternalBody {
        private String url;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ImageBody {
        private String type;
        private FileBody file;
        private ExternalBody external;
    }

    /** 다양한 케이스에서 파일/이미지 URL 을 추출. */
    public String resolveUrl() {
        if (file != null && file.getUrl() != null) return file.getUrl();
        if (external != null && external.getUrl() != null) return external.getUrl();
        if (image != null) {
            if (image.getFile() != null && image.getFile().getUrl() != null) return image.getFile().getUrl();
            if (image.getExternal() != null && image.getExternal().getUrl() != null) return image.getExternal().getUrl();
        }
        return null;
    }
}
