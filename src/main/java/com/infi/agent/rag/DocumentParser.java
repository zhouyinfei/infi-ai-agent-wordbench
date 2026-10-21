package com.infi.agent.rag;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;
import org.springframework.stereotype.Component;

/** 有界文本解析与带重叠的分块，不执行文档中的指令。 */
@Component
public class DocumentParser {
    /** Tika 统一提取文档文本，显式关闭 OCR；扫描 PDF 无文本时由业务层拒绝。 */
    public String parse(String filename, byte[] bytes) throws IOException {
        return parse(filename, bytes, 200000);
    }

    public String parse(String filename, byte[] bytes, int maxCharacters) throws IOException {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (!lower.matches(".*\\.(txt|md|pdf|doc|docx|xls|xlsx|ppt|pptx|html|htm|rtf|odt|ods|odp)$")) {
            throw new IllegalArgumentException("仅支持 TXT、Markdown、PDF、Word、Excel、PowerPoint、HTML、RTF、OpenDocument 文件");
        }
        if (lower.endsWith(".txt") || lower.endsWith(".md")) {
            try {
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes));
            } catch (CharacterCodingException exception) {
                throw new IllegalArgumentException("文本文件须使用 UTF-8 编码", exception);
            }
        }
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        if (lower.endsWith(".txt") || lower.endsWith(".md")) {
            metadata.set(Metadata.CONTENT_TYPE, "text/plain; charset=UTF-8");
        }
        ParseContext context = new ParseContext();
        TesseractOCRConfig ocr = new TesseractOCRConfig();
        ocr.setSkipOcr(true);
        context.set(TesseractOCRConfig.class, ocr);
        PDFParserConfig pdf = new PDFParserConfig();
        pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        context.set(PDFParserConfig.class, pdf);
        if (maxCharacters <= 0) {
            throw new IllegalArgumentException("文本长度上限须大于 0");
        }
        BodyContentHandler handler = new BodyContentHandler(maxCharacters);
        try (var input = new ByteArrayInputStream(bytes)) {
            new AutoDetectParser().parse(input, handler, metadata, context);
            return handler.toString().strip();
        } catch (TikaException | SAXException exception) {
            throw new IOException("文档无法解析或超过文本长度限制", exception);
        }
    }

    /** 按 Unicode 码点分块，避免拆开代理对；配置错误时拒绝处理。 */
    public List<String> split(String text, int size, int overlap) {
        if (size < 100 || overlap < 0 || overlap >= size) {
            throw new IllegalArgumentException("分块配置须满足 size >= 100 且 0 <= overlap < size");
        }
        int[] points = text.codePoints().toArray();
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < points.length; start += size - overlap) {
            int length = Math.min(size, points.length - start);
            String chunk = new String(points, start, length).strip();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
            if (start + length == points.length) {
                break;
            }
        }
        return List.copyOf(chunks);
    }
}
