package com.acervo.ingest;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class PdfExtractor implements TextExtractor {

    /** Páginas com menos chars que isso são consideradas "vazias". */
    private static final int EMPTY_PAGE_THRESHOLD = 30;
    /** Proporção de páginas vazias que dispara "PDF escaneado". */
    private static final double SCANNED_PDF_RATIO = 0.7;

    @Override
    public List<Page> extract(Path file) throws Exception {
        List<Page> pages = new ArrayList<>();
        int total;
        int emptyPages = 0;
        try (PDDocument doc = Loader.loadPDF(Files.readAllBytes(file).clone())) {
            PDFTextStripper stripper = new PDFTextStripper();
            total = doc.getNumberOfPages();
            for (int i = 1; i <= total; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = stripper.getText(doc).trim();
                if (text.length() < EMPTY_PAGE_THRESHOLD) {
                    emptyPages++;
                } else {
                    pages.add(new Page("p. " + i, text));
                }
            }
        }
        // Heurística simples: se a maioria das páginas tem ~zero texto
        // extraível, provavelmente é um PDF escaneado (imagens). OCR de
        // verdade fica fora desta versão — sinaliza o usuário pra rodar
        // por fora (e ajusta a Phase 2.1 do Melhorias.md).
        if (total > 0 && emptyPages >= Math.max(1, (int) Math.ceil(total * SCANNED_PDF_RATIO))) {
            throw new ScannedPdfException(
                    "PDF parece ser escaneado (" + emptyPages + " de " + total
                            + " páginas sem texto extraível). Rode OCR (ex: ocrmypdf) "
                            + "no arquivo e reenvie.");
        }
        return pages;
    }
}
