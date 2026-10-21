package com.infi.agent;

import com.infi.agent.rag.DocumentParser;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DocumentParserTest {
    private final DocumentParser parser = new DocumentParser();

    @Test
    void extractsPdfText() throws Exception {
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument();
                var output = new java.io.ByteArrayOutputStream()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            document.addPage(page);
            try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("Knowledge retrieval");
                content.endText();
            }
            document.save(output);
            assertThat(parser.parse("manual.pdf", output.toByteArray())).contains("Knowledge retrieval");
        }
    }

    @Test
    void extractsDocxText() throws Exception {
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument();
                var output = new java.io.ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("知识库资料");
            document.write(output);
            assertThat(parser.parse("manual.docx", output.toByteArray())).contains("知识库资料");
        }
    }

    @Test
    void extractsSpreadsheetAndPresentationText() throws Exception {
        try (var workbook = new org.apache.poi.hssf.usermodel.HSSFWorkbook();
                var output = new java.io.ByteArrayOutputStream()) {
            workbook.createSheet("Legacy").createRow(0).createCell(0).setCellValue("Legacy knowledge");
            workbook.write(output);
            assertThat(parser.parse("legacy.XLS", output.toByteArray())).contains("Legacy knowledge");
        }
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
                var output = new java.io.ByteArrayOutputStream()) {
            workbook.createSheet("资料").createRow(0).createCell(0).setCellValue("季度营收");
            workbook.write(output);
            assertThat(parser.parse("report.xlsx", output.toByteArray())).contains("季度营收");
        }
        try (var slides = new org.apache.poi.xslf.usermodel.XMLSlideShow();
                var output = new java.io.ByteArrayOutputStream()) {
            slides.createSlide().createTextBox().setText("项目介绍");
            slides.write(output);
            assertThat(parser.parse("intro.pptx", output.toByteArray())).contains("项目介绍");
        }
    }

    @Test
    void extractsHtmlBodyAndEnforcesTextLimit() throws Exception {
        assertThat(parser.parse("note.rtf", "{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Arial;}}\\f0\\fs24 Knowledge reference\\par}"
                .getBytes(StandardCharsets.US_ASCII))).contains("Knowledge reference");
        assertThat(parser.parse("page.html", "<html><body><p>知识资料</p></body></html>"
                .getBytes(StandardCharsets.UTF_8))).contains("知识资料").doesNotContain("<p>");
        assertThatThrownBy(() -> parser.parse("large.txt", "a".repeat(200).getBytes(StandardCharsets.UTF_8), 100))
                .isInstanceOf(java.io.IOException.class);
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument();
                var output = new java.io.ByteArrayOutputStream()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            document.save(output);
            assertThat(parser.parse("scan.pdf", output.toByteArray())).isBlank();
        }
    }

    @Test
    void rejectsUnsupportedFilesAndMalformedUtf8() {
        assertThatThrownBy(() -> parser.parse("program.exe", new byte[]{1})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("bad.txt", new byte[]{(byte) 0xff})).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unicodeChunksPreserveOverlapAndDoNotSplitEmoji() throws Exception {
        String text = "😀".repeat(150);
        assertThat(parser.parse("笔记.md", text.getBytes(StandardCharsets.UTF_8))).isEqualTo(text);
        var chunks = parser.split(text, 100, 20);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).isEqualTo("😀".repeat(100));
        assertThat(chunks.get(1)).isEqualTo("😀".repeat(70));
        assertThatThrownBy(() -> parser.split(text, 100, 100)).isInstanceOf(IllegalArgumentException.class);
    }
}
