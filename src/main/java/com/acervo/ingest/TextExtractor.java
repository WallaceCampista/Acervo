package com.acervo.ingest;

import java.nio.file.Path;
import java.util.List;

public interface TextExtractor {

    /** Texto bruto + label de localização (página/slide/parágrafo) por trecho lógico. */
    List<Page> extract(Path file) throws Exception;

    record Page(String label, String content) {}
}
