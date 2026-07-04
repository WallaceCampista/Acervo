package com.acervo.ingest;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cria PDFs programaticamente com PDFBox e exercita o detector de "escaneado".
 */
class PdfExtractorTest {

    private final PdfExtractor extractor = new PdfExtractor();

    @Test
    @DisplayName("PDF com texto normal: extrai todas as páginas (>30 chars cada)")
    void normalPdf() throws Exception {
        // PDFBox às vezes encurta sequências como travessões — uso ASCII puro.
        Path file = pdfWithPages(
                "Pagina 1 - conteudo extraivel com texto suficiente.",
                "Pagina 2 - mais conteudo legivel com palavras de sobra.",
                "Pagina 3 - finalizando o documento de teste corretamente.");
        try {
            List<TextExtractor.Page> pages = extractor.extract(file);
            assertThat(pages).hasSize(3);
            assertThat(pages.get(0).label()).isEqualTo("p. 1");
            assertThat(pages.get(0).content()).contains("extraivel");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("PDF com >70% das páginas vazias dispara ScannedPdfException")
    void scannedPdfDetected() throws Exception {
        // 3 páginas: uma com texto, duas vazias → 67% vazias (limítrofe)
        // Vou pra 4 páginas com 3 vazias = 75% pra garantir detecção.
        Path file = pdfWithPages(
                "Capa do documento com algum texto.",
                "", "", "");
        try {
            assertThatThrownBy(() -> extractor.extract(file))
                    .isInstanceOf(ScannedPdfException.class)
                    .hasMessageContaining("páginas sem texto extraível")
                    .hasMessageContaining("ocrmypdf");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("PDF totalmente vazio (sem texto) dispara ScannedPdfException")
    void allBlankPdfDetected() throws Exception {
        Path file = pdfWithPages("", "", "");
        try {
            assertThatThrownBy(() -> extractor.extract(file))
                    .isInstanceOf(ScannedPdfException.class);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * Gera um PDF temporário com as páginas dadas. Páginas com string vazia
     * ficam sem conteúdo (simulando PDF escaneado onde só há imagem).
     */
    private Path pdfWithPages(String... pages) throws IOException {
        Path tempFile = Files.createTempFile("acervo-pdf-test-", ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (String content : pages) {
                PDPage page = new PDPage();
                doc.addPage(page);
                if (content != null && !content.isEmpty()) {
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.beginText();
                        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        cs.newLineAtOffset(50, 700);
                        cs.showText(content);
                        cs.endText();
                    }
                }
            }
            doc.save(tempFile.toFile());
        }
        return tempFile;
    }
}
