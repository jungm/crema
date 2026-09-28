package io.github.jungm.crema.internal.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.StringReader;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import io.github.jungm.crema.internal.bind.ArgumentBinder;
import io.github.jungm.crema.internal.bind.JsonbBridge;
import io.github.jungm.crema.internal.schema.SampleModel.Accessors;
import io.github.jungm.crema.internal.schema.SampleModel.Address;
import io.github.jungm.crema.internal.schema.SampleModel.AddressPage;
import io.github.jungm.crema.internal.schema.SampleModel.Clash;
import io.github.jungm.crema.internal.schema.SampleModel.Containers;
import io.github.jungm.crema.internal.schema.SampleModel.Creator;
import io.github.jungm.crema.internal.schema.SampleModel.Customized;
import io.github.jungm.crema.internal.schema.SampleModel.FieldVisibility;
import io.github.jungm.crema.internal.schema.SampleModel.NillableClass;
import io.github.jungm.crema.internal.schema.SampleModel.Outer1;
import io.github.jungm.crema.internal.schema.SampleModel.Outer2;
import io.github.jungm.crema.internal.schema.SampleModel.Page;
import io.github.jungm.crema.internal.schema.SampleModel.Person;
import io.github.jungm.crema.internal.schema.SampleModel.Scalars;
import io.github.jungm.crema.internal.schema.SampleModel.Status;
import io.github.jungm.crema.internal.schema.SampleModel.TreeNode;
import io.github.jungm.crema.internal.schema.SampleModel.TypeLiteral;
import io.github.jungm.crema.internal.schema.SampleModel.WithDefaultMethod;
import io.github.jungm.crema.internal.schema.SampleModel.Wrapper;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Serializes sample values with JSON-B (Yasson) and validates the JSON against the generated output schema, so the
 * schema and the serializer can't disagree.
 */
class SchemaRoundTripTest {

    private static Jsonb jsonb;
    private final SchemaGenerator generator = new SchemaGenerator();

    @BeforeAll
    static void createJsonb() {
        jsonb = JsonbBuilder.create();
    }

    @AfterAll
    static void closeJsonb() throws Exception {
        jsonb.close();
    }

    static Stream<Arguments> samples() throws Exception {
        List<Arguments> samples = new ArrayList<>();
        samples.add(sample("full person", Person.class, new Person("Ann", 42, Optional.of("A"),
                List.of(new Address("Main", "Town", 12345), new Address("Side", null, 0)),
                new Address("Main", "Town", 1), Map.of("x", 1), Status.INACTIVE, "note", "ann@example.com")));
        samples.add(sample("empty person", Person.class,
                new Person(null, 0, Optional.empty(), null, null, null, null, null, null)));
        samples.add(sample("scalars", Scalars.class, scalars()));
        samples.add(sample("empty scalars", Scalars.class, new Scalars()));
        samples.add(sample("tree", TreeNode.class, new TreeNode("root", List.of(
                new TreeNode("a", List.of(new TreeNode("a1", null))), new TreeNode("b", List.of())))));
        samples.add(sample("generic page", new TypeLiteral<Page<Address>>() { }.type(), page()));
        samples.add(sample("page subclass", AddressPage.class, addressPage()));
        samples.add(sample("page of pages", new TypeLiteral<Page<Page<Address>>>() { }.type(), pageOfPages()));
        samples.add(sample("bounded wrapper", new TypeLiteral<Wrapper<Integer>>() { }.type(), wrapper()));
        samples.add(sample("accessors", Accessors.class, new Accessors()));
        samples.add(sample("nillable empty", NillableClass.class, new NillableClass()));
        samples.add(sample("nillable full", NillableClass.class, nillableFull()));
        samples.add(sample("customized", Customized.class, new Customized()));
        samples.add(sample("field visibility", FieldVisibility.class, new FieldVisibility()));
        samples.add(sample("default method", WithDefaultMethod.class, new WithDefaultMethod()));
        samples.add(sample("containers", Containers.class, containers()));
        samples.add(sample("empty containers", Containers.class, new Containers()));
        samples.add(sample("creator", Creator.class, new Creator("c", 3)));
        samples.add(sample("clash", Clash.class, new Clash(new Outer1.Item("x"), null, new Outer2.Item(1),
                new Outer2.Item(2))));
        samples.add(sample("map root", new TypeLiteral<Map<String, List<Address>>>() { }.type(),
                Map.of("k", List.of(new Address("s", "c", 1)))));
        samples.add(sample("list root", new TypeLiteral<List<Optional<Person>>>() { }.type(),
                Arrays.asList(Optional.empty(), Optional.of(new Person("p", 1, Optional.empty(), List.of(), null,
                        Map.of(), Status.ACTIVE, null, null)))));
        samples.add(sample("string root", String.class, "text"));
        samples.add(sample("optional root", new TypeLiteral<Optional<Address>>() { }.type(),
                Optional.of(new Address("s", "c", 1))));
        samples.add(sample("empty optional root", new TypeLiteral<Optional<Address>>() { }.type(), Optional.empty()));
        samples.add(sample("nillable class", SchemaGeneratorTest.NillableTwice.class,
                new SchemaGeneratorTest.NillableTwice()));
        samples.add(sample("transient accessors", SchemaGeneratorTest.TransientAccessors.class,
                new SchemaGeneratorTest.TransientAccessors()));
        samples.add(sample("custom serializer", SchemaGeneratorTest.WithCustom.class,
                new SchemaGeneratorTest.WithCustom()));
        samples.add(sample("byte array root", byte[].class, new byte[] {-128, 0, 127}));
        samples.add(sample("period root", Period.class, Period.ofYears(1).plusMonths(2).plusDays(3)));
        return samples.stream();
    }

    private static Arguments sample(String name, Type type, Object value) {
        return Arguments.of(name, type, value);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    void serializedValueMatchesOutputSchema(String name, Type type, Object value) {
        JsonObject schema = generator.schemaFor(type);
        String json = jsonb.toJson(value, type);
        JsonValue instance = Json.createReader(new StringReader(json)).readValue();

        List<String> errors = SchemaValidator.validate(schema, instance);

        assertEquals(List.of(), errors, () -> "JSON " + json + "\nschema " + schema);
    }

    static Stream<Arguments> inputSamples() {
        return Stream.of(
                sample("person", Person.class, new Person("Ann", 42, Optional.of("A"),
                        List.of(new Address("Main", "Town", 12345)), new Address("Main", "Town", 1), Map.of("x", 1),
                        Status.INACTIVE, "note", "ann@example.com")),
                sample("tree", TreeNode.class, new TreeNode("root", List.of(new TreeNode("a", List.of())))),
                sample("generic page", new TypeLiteral<Page<Address>>() { }.type(), page()),
                sample("page of pages", new TypeLiteral<Page<Page<Address>>>() { }.type(), pageOfPages()),
                sample("list of optionals", new TypeLiteral<List<Optional<String>>>() { }.type(),
                        Arrays.asList(Optional.of("a"), Optional.empty())),
                sample("map of lists", new TypeLiteral<Map<String, List<Address>>>() { }.type(),
                        Map.of("k", List.of(new Address("s", "c", 1)))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inputSamples")
    void serializedValueMatchesInputSchemaAndBindsBack(String name, Type type, Object value) throws Exception {
        JsonObject schema = generator.inputSchema(List.of(new SchemaProperty("value", type, null, true)));
        String json = jsonb.toJson(value, type);
        JsonValue argument = parse(json);

        List<String> errors = SchemaValidator.validate(schema, Json.createObjectBuilder().add("value", argument).build());

        assertEquals(List.of(), errors, () -> "JSON " + json + "\nschema " + schema);
        try (JsonbBridge bridge = new JsonbBridge()) {
            Object bound = new ArgumentBinder(bridge).bind(argument, type);
            assertEquals(json, jsonb.toJson(bound, type));
        }
    }

    @Test
    void validatorRejectsMismatches() {
        JsonObject schema = generator.schemaFor(Person.class);
        assertFalse(SchemaValidator.validate(schema, parse("{\"age\":\"x\"}")).isEmpty());
        assertFalse(SchemaValidator.validate(schema, parse("{\"name\":\"n\"}")).isEmpty(), "age is required");
        assertFalse(SchemaValidator.validate(schema, parse("{\"age\":1,\"status\":\"OTHER\"}")).isEmpty());
        assertFalse(SchemaValidator.validate(schema, parse("{\"age\":1,\"addresses\":[{\"zip\":1.5}]}")).isEmpty());

        JsonObject tree = generator.schemaFor(TreeNode.class);
        assertFalse(SchemaValidator.validate(tree, parse("{\"children\":[{\"children\":[{\"label\":1}]}]}"))
                .isEmpty());

        JsonObject scalars = generator.schemaFor(Scalars.class);
        String valid = jsonb.toJson(new Scalars());
        assertEquals(List.of(), SchemaValidator.validate(scalars, parse(valid)));
        assertFalse(SchemaValidator.validate(scalars, parse(valid.replace("\"b\":0", "\"b\":300"))).isEmpty());
        assertFalse(SchemaValidator.validate(scalars,
                parse(valid.replace("{", "{\"instant\":\"2020-01-01T00:00\","))).isEmpty());
    }

    private static JsonValue parse(String json) {
        return Json.createReader(new StringReader(json)).readValue();
    }

    private static Scalars scalars() throws Exception {
        Scalars scalars = new Scalars();
        scalars.id = "id";
        scalars.setVersion(7);
        scalars.total = new BigDecimal("12345678901234567890.123456789");
        scalars.big = new BigInteger("123456789012345678901234567890");
        scalars.number = 3;
        scalars.instant = Instant.parse("2024-01-02T03:04:05.123456Z");
        scalars.localDate = LocalDate.of(2024, 2, 29);
        scalars.localDateTime = LocalDateTime.of(2024, 1, 2, 3, 4);
        scalars.localTime = LocalTime.of(3, 4);
        scalars.offsetDateTime = OffsetDateTime.of(2024, 1, 2, 3, 4, 0, 0, ZoneOffset.ofHours(-5));
        scalars.offsetTime = OffsetTime.of(3, 4, 0, 500, ZoneOffset.UTC);
        scalars.zonedDateTime = ZonedDateTime.of(2024, 1, 2, 3, 4, 5, 0, ZoneId.of("Europe/Berlin"));
        scalars.duration = Duration.ofMillis(-1500);
        scalars.period = Period.of(1, 2, 3);
        scalars.zoneId = ZoneId.of("America/New_York");
        scalars.zoneOffset = ZoneOffset.ofHoursMinutes(5, 30);
        scalars.uuid = UUID.randomUUID();
        scalars.uri = URI.create("relative/path?q=1");
        scalars.url = new URI("https://example.com/x").toURL();
        scalars.date = new Date(0);
        scalars.calendar = new GregorianCalendar(2024, Calendar.MARCH, 1);
        scalars.timeZone = TimeZone.getTimeZone("UTC");
        scalars.bytes = new byte[] {1, -1};
        scalars.c = 'x';
        scalars.boxedChar = 'y';
        scalars.chars = new char[] {'a', 'b'};
        scalars.flag = true;
        scalars.boxedFlag = false;
        scalars.s = Short.MIN_VALUE;
        scalars.b = Byte.MAX_VALUE;
        scalars.i = Integer.MIN_VALUE;
        scalars.l = Long.MAX_VALUE;
        scalars.f = 0.1f;
        scalars.d = 1e300;
        scalars.boxedInt = 5;
        scalars.optionalInt = OptionalInt.of(1);
        scalars.optionalLong = OptionalLong.empty();
        scalars.optionalDouble = OptionalDouble.of(2.5);
        scalars.any = Map.of("anything", List.of(1, "two"));
        scalars.jsonObject = Json.createObjectBuilder().add("k", 1).build();
        scalars.jsonArray = Json.createArrayBuilder().add(1).build();
        scalars.jsonValue = JsonValue.TRUE;
        scalars.status = Status.ACTIVE;
        scalars.secret = "secret";
        scalars.volatileState = "state";
        return scalars;
    }

    private static Page<Address> page() {
        Page<Address> page = new Page<>();
        page.items = List.of(new Address("a", "b", 1));
        page.first = new Address("a", "b", 1);
        page.total = 1;
        return page;
    }

    private static AddressPage addressPage() {
        AddressPage page = new AddressPage();
        page.items = List.of(new Address("a", "b", 1));
        page.total = 1;
        return page;
    }

    private static Page<Page<Address>> pageOfPages() {
        Page<Page<Address>> page = new Page<>();
        page.items = List.of(page(), new Page<>());
        page.first = page();
        return page;
    }

    private static Wrapper<Integer> wrapper() {
        Wrapper<Integer> wrapper = new Wrapper<>();
        wrapper.value = 1;
        wrapper.values = List.of(1, 2);
        return wrapper;
    }

    private static NillableClass nillableFull() {
        NillableClass value = new NillableClass();
        value.a = "a";
        value.b = "b";
        value.c = "c";
        value.d = Optional.of("d");
        return value;
    }

    private static Containers containers() {
        Containers containers = new Containers();
        containers.optionals = Arrays.asList(Optional.of("a"), Optional.empty());
        containers.statuses = Set.of(Status.ACTIVE);
        containers.byStatus = Map.of(Status.INACTIVE, "inactive");
        containers.byNumber = Map.of(1, "one");
        containers.byAddress = Map.of(new Address("s", "c", 1), "home");
        containers.nested = new TreeMap<>(Map.of("k", List.of(new Address("s", "c", 1))));
        containers.grid = new Address[][] {{new Address("s", "c", 1)}, {}};
        Map<String, Address> map = new LinkedHashMap<>();
        map.put("a", new Address("s", null, 2));
        containers.listOfMaps = List.of(map);
        containers.iterable = List.of("x");
        return containers;
    }
}
