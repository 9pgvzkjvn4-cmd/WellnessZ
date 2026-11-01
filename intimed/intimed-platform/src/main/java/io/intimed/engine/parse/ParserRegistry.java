package io.intimed.engine.parse;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Grammar type → parser. Adapter options P1–P11 map onto these grammar types through the driver package. */
@Component
public class ParserRegistry {
    private final Map<String, PayloadParser> parsers = List.of(new RecordParser(true), new RecordParser(false), new SerialFrameParser(),
                    new CsvParser(), new XmlParser("XML"), new XmlParser("POCT1A"), new GdtParser(), new GattParser(),
                    new JsonPathParser("JSON"), new JsonPathParser("SDC"), new DicomJsonParser(), new FhirObservationParser(),
                    new FormParser("OCR_TEXT"), new FormParser("MANUAL"))
            .stream().collect(Collectors.toMap(PayloadParser::type, Function.identity()));

    public PayloadParser get(String type) {
        PayloadParser p = parsers.get(type);
        if (p == null) throw new ParseException("no parser for grammar " + type);
        return p;
    }

    public java.util.Set<String> types() { return parsers.keySet(); }
}
