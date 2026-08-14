package com.worldgit.ai;

import java.util.Base64;

public record AiImageInput(
        String fileName,
        String mimeType,
        String base64Data
) {

    public boolean isPresent() {
        return mimeType != null
                && !mimeType.isBlank()
                && base64Data != null
                && !base64Data.isBlank();
    }

    public String dataUrl() {
        if (!isPresent()) {
            return null;
        }
        return "data:" + mimeType + ";base64," + base64Data;
    }

    public int byteLength() {
        if (!isPresent()) {
            return 0;
        }
        return Base64.getDecoder().decode(base64Data).length;
    }
}
