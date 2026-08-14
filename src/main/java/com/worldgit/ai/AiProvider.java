package com.worldgit.ai;

public interface AiProvider {

    AiConversationResult runConversation(
            AiExecutionContext context,
            String systemPrompt,
            String userPrompt,
            AiImageInput imageInput,
            AiRunLogger logger
    ) throws Exception;
}
