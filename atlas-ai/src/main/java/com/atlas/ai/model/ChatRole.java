package com.atlas.ai.model;

/** OpenAI 호환 chat completions의 메시지 역할. 전송할 때 소문자 이름을 쓴다. */
public enum ChatRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
}
