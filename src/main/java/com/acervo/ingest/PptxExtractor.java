package com.acervo.ingest;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class PptxExtractor implements TextExtractor {
    @Override
    public List<Page> extract(Path file) throws Exception {
        List<Page> pages = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file);
             XMLSlideShow ppt = new XMLSlideShow(in)) {
            int idx = 1;
            for (XSLFSlide slide : ppt.getSlides()) {
                StringBuilder buf = new StringBuilder();
                for (XSLFShape sh : slide.getShapes()) {
                    if (sh instanceof XSLFTextShape ts) {
                        buf.append(ts.getText()).append('\n');
                    }
                }
                String text = buf.toString().trim();
                if (!text.isBlank()) {
                    pages.add(new Page("slide " + idx, text));
                }
                idx++;
            }
        }
        return pages;
    }
}
