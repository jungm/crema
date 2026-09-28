package io.github.jungm.crema.internal.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class UriTemplateTest {

    @Test
    void matchesVariables() {
        UriTemplate template = UriTemplate.parse("db:///{database}/tables/{table}");
        assertEquals(List.of("database", "table"), template.variables());
        assertEquals(Optional.of(Map.of("database", "shop", "table", "orders")),
                template.match("db:///shop/tables/orders"));
    }

    @Test
    void variablesDontMatchSlashesOrNothing() {
        UriTemplate template = UriTemplate.parse("test://template/{id}/data");
        assertEquals(Optional.of(Map.of("id", "123")), template.match("test://template/123/data"));
        assertEquals(Optional.empty(), template.match("test://template/1/2/data"));
        assertEquals(Optional.empty(), template.match("test://template//data"));
        assertEquals(Optional.empty(), template.match("test://template/123/data/more"));
    }

    @Test
    void literalsAreNotPatterns() {
        UriTemplate template = UriTemplate.parse("a.b+c://{x}?q=1");
        assertEquals(Optional.of(Map.of("x", "y")), template.match("a.b+c://y?q=1"));
        assertEquals(Optional.empty(), template.match("aXb+c://y?q=1"));
    }

    @Test
    void decodesPercentEncodedValues() {
        UriTemplate template = UriTemplate.parse("file:///{name}");
        assertEquals(Optional.of(Map.of("name", "a b ü")), template.match("file:///a%20b%20%C3%BC"));
        assertEquals(Optional.of(Map.of("name", "100%")), template.match("file:///100%"));
        assertEquals(Optional.of(Map.of("name", "...")), template.match("file:///%2E%2E%2E"));
    }

    @Test
    void rejectsValuesThatArentSinglePathSegments() {
        UriTemplate template = UriTemplate.parse("file:///docs/{name}");
        for (String uri : List.of("file:///docs/..%2F..%2Fetc%2Fpasswd", "file:///docs/a%2fb", "file:///docs/..",
                "file:///docs/.", "file:///docs/%2E%2E", "file:///docs/%2e", "file:///docs/..%5C..%5Cwindows",
                "file:///docs/a\\b")) {
            assertEquals(Optional.empty(), template.match(uri), uri);
        }
    }

    @Test
    void shapeIgnoresVariableNames() {
        assertEquals("db:///{}/tables/{}", UriTemplate.parse("db:///{database}/tables/{table}").shape());
        assertEquals(UriTemplate.parse("x://{a}").shape(), UriTemplate.parse("x://{b}").shape());
    }

    @Test
    void rejectsTemplatesBeyondLevelOne() {
        for (String invalid : List.of("file:///{+path}", "file:///{path*}", "file:///{a,b}", "file:///{path",
                "file:///path}", "file:///{}", "x://{a}/{a}")) {
            assertThrows(IllegalArgumentException.class, () -> UriTemplate.parse(invalid), invalid);
        }
    }
}
