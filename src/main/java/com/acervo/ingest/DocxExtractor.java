package com.acervo.ingest;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class DocxExtractor implements TextExtractor {
    @Override
    public List<Page> extract(Path file) throws Exception {
        List<Page> pages = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file);
             XWPFDocument doc = new XWPFDocument(in)) {
            StringBuilder buf = new StringBuilder();
            int section = 1;
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = p.getText();
                if (t == null) continue;
                buf.append(t).append('\n');
                if (buf.length() >= 1800) {
                    pages.add(new Page("seção " + section++, buf.toString().trim()));
                    buf.setLength(0);
                }
            }
            if (buf.length() > 0) {
                pages.add(new Page("seção " + section, buf.toString().trim()));
            }
        }
        return pages;
    }
}
