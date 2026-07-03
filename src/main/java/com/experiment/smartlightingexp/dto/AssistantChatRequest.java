package com.experiment.smartlightingexp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AssistantChatRequest {

    @NotBlank(message = "消息不能为空")
    private String message;
}
