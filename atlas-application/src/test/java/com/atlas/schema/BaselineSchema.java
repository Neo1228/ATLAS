package com.atlas.schema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

/** 기준 스키마(V1__baseline.sql)를 테스트가 읽는 도우미. 빈 스키마에 전체를 실행하거나 인덱스 한 개만 꺼낸다. */
public final class BaselineSchema {
    private static final String RESOURCE = "db/migration/V1__baseline.sql";

    private BaselineSchema() {
    }

    /** 기준 스키마 전체. 빈 스키마에서 실행하면 운영 스키마가 만들어진다. */
    public static String script() {
        try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
            return StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 기준 스키마에서 이름이 같은 CREATE INDEX 문 하나(세미콜론 포함). */
    public static String createIndex(String name) {
        Matcher matcher = Pattern
                .compile("^CREATE (?:UNIQUE )?INDEX " + Pattern.quote(name) + " ON [^;]*;", Pattern.MULTILINE)
                .matcher(script());
        if (!matcher.find()) {
            throw new IllegalStateException("Index not found in baseline schema: " + name);
        }
        return matcher.group();
    }
}
