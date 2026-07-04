package com.acervo.ingest;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class PlainTextExtractor implements TextExtractor {
    @Override
    public List<Page> extract(Path file) throws Exception {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        return List.of(new Page("—", content));
    }
}
