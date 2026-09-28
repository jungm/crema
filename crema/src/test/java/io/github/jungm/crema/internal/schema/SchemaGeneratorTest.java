package io.github.jungm.crema.internal.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import io.github.jungm.crema.internal.schema.SampleModel.Accessors;
import io.github.jungm.crema.internal.schema.SampleModel.Address;
import io.github.jungm.crema.internal.schema.SampleModel.Clash;
import io.github.jungm.crema.internal.schema.SampleModel.Containers;
import io.github.jungm.crema.internal.schema.SampleModel.Creator;
import io.github.jungm.crema.internal.schema.SampleModel.Customized;
import io.github.jungm.crema.internal.schema.SampleModel.FieldVisibility;
import io.github.jungm.crema.internal.schema.SampleModel.Money;
import io.github.jungm.crema.internal.schema.SampleModel.NillableClass;
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
import jakarta.json.bind.annotation.JsonbTransient;
import jakarta.json.bind.annotation.JsonbTypeSerializer;
import jakarta.json.bind.serializer.JsonbSerializer;
import jakarta.json.bind.serializer.SerializationContext;
import jakarta.json.stream.JsonGenerator;
import org.junit.jupiter.api.Test;

class SchemaGeneratorTest {

    private static final String INT = "{'type':'integer','minimum':-2147483648,'maximum':2147483647}";
    private static final String ADDRESS_OUT =
            "{'type':'object','properties':{'city':{'type':'string'},'street':{'type':'string'},'zip':" + INT
            + "},'required':['zip']}";

    private final SchemaGenerator generator = new SchemaGenerator();

    @Test
    void inputSchemaWithoutArgumentsIsAnEmptyObject() {
        assertSchema("{'type':'object','properties':{}}", generator.inputSchema(List.of()));
    }

    @Test
    void inputSchemaListsArgumentsInOrderWithDescriptionsAndRequired() {
        JsonObject schema = generator.inputSchema(List.of(
                new SchemaProperty("query", String.class, "What to search for", true),
                new SchemaProperty("limit", int.class, null, false),
                new SchemaProperty("status", Status.class, "", true)));

        assertSchema("{'type':'object','properties':{"
                + "'query':{'type':'string','description':'What to search for'},"
                + "'limit':" + INT + ","
                + "'status':{'type':'string','enum':['ACTIVE','INACTIVE']}},"
                + "'required':['query','status']}", schema);
        assertEquals(List.of("query", "limit", "status"), new ArrayList<>(schema.getJsonObject("properties").keySet()));
    }

    @Test
    void optionalArgumentsUseTheirValueSchema() {
        JsonObject schema = generator.inputSchema(List.of(
                new SchemaProperty("a", new TypeLiteral<Optional<String>>() { }.type(), null, false),
                new SchemaProperty("b", OptionalInt.class, null, false),
                new SchemaProperty("c", Optional.class, null, false)));

        assertSchema("{'type':'object','properties':{'a':{'type':'string'},'b':" + INT + ",'c':{}}}", schema);
    }

    @Test
    void inputSchemaDescribesWhatJsonbReads() {
        JsonObject schema = generator.inputSchema(List.of(
                new SchemaProperty("accessors", Accessors.class, null, true),
                new SchemaProperty("creator", Creator.class, null, true),
                new SchemaProperty("address", Address.class, null, true)));

        JsonObject properties = schema.getJsonObject("properties");
        // public field without setter, and a setter without getter; no read-only or computed properties
        assertSchema("{'type':'object','properties':{'privateGetter':{'type':'string'},'writeOnly':{'type':'string'}}}",
                properties.getJsonObject("accessors"));
        // creator parameters and the public field; nothing is required
        assertSchema("{'type':'object','properties':{'extra':{'type':'string'},'count':" + INT
                + ",'name':{'type':'string'}}}", properties.getJsonObject("creator"));
        // record components through the canonical constructor
        assertSchema("{'type':'object','properties':{'city':{'type':'string'},'street':{'type':'string'},'zip':"
                + INT + "}}", properties.getJsonObject("address"));
    }

    @Test
    void outputSchemaOfRecordWithRepeatedTypeUsesDefs() {
        assertSchema("{'type':'object','properties':{"
                + "'addresses':{'type':'array','items':{'$ref':'#/$defs/Address'}},"
                + "'age':" + INT + ","
                + "'e-mail':{'type':'string'},"
                + "'name':{'type':'string'},"
                + "'nickname':{'type':'string'},"
                + "'note':{'type':['string','null']},"
                + "'primary':{'$ref':'#/$defs/Address'},"
                + "'scores':{'type':'object','additionalProperties':" + INT + "},"
                + "'status':{'type':'string','enum':['ACTIVE','INACTIVE']}},"
                + "'required':['age','note'],"
                + "'$defs':{'Address':" + ADDRESS_OUT + "}}", generator.schemaFor(Person.class));
    }

    @Test
    void typesUsedOnceAreInlined() {
        assertSchema("{'type':'object','properties':{'a':{'type':'object','properties':{'a':{'type':'string'}}}}}",
                generator.schemaFor(SingleUse.class));
    }

    public record SingleUse(SampleModel.Outer1.Item a) {
    }

    @Test
    void recursiveRootIsReferencedAsDocumentRoot() {
        assertSchema("{'type':'object','properties':{'children':{'type':'array','items':{'$ref':'#'}},"
                + "'label':{'type':'string'}}}", generator.schemaFor(TreeNode.class));
    }

    @Test
    void recursiveArgumentGoesToDefs() {
        assertSchema("{'type':'object','properties':{'tree':{'$ref':'#/$defs/TreeNode'}},"
                + "'$defs':{'TreeNode':{'type':'object','properties':{"
                + "'children':{'type':'array','items':{'$ref':'#/$defs/TreeNode'}},'label':{'type':'string'}}}}}",
                generator.inputSchema(List.of(new SchemaProperty("tree", TreeNode.class, null, false))));
    }

    @Test
    void genericsAreResolvedPerParameterization() {
        JsonObject schema = generator.schemaFor(new TypeLiteral<Page<Page<Address>>>() { }.type());

        assertSchema("{'type':'object','properties':{"
                + "'first':{'$ref':'#/$defs/PageOfAddress'},"
                + "'items':{'type':'array','items':{'$ref':'#/$defs/PageOfAddress'}},"
                + "'total':" + INT + "},'required':['total'],"
                + "'$defs':{'Address':" + ADDRESS_OUT + ",'PageOfAddress':{'type':'object','properties':{"
                + "'first':{'$ref':'#/$defs/Address'},"
                + "'items':{'type':'array','items':{'$ref':'#/$defs/Address'}},"
                + "'total':" + INT + "},'required':['total']}}}", schema);
    }

    @Test
    void typeVariablesAndWildcardsResolveToBounds() {
        assertSchema("{'type':'object','properties':{'value':{'type':'number'},"
                + "'values':{'type':'array','items':{'type':'number'}}}}", generator.schemaFor(Wrapper.class));
        // JSON-B 3.0, section 3.17.1: an unbounded wildcard is Object
        assertSchema("{'type':'object','properties':{'value':{},'values':{'type':'array','items':{}}}}",
                generator.schemaFor(new TypeLiteral<Wrapper<?>>() { }.type()));
        assertSchema("{'type':'object','properties':{'value':{'type':'string'},"
                + "'values':{'type':'array','items':{'type':'string'}}}}", generator.schemaFor(StringHolder.class));
        // T extends SelfBounded<T> resolves to SelfBounded<Object>, whose T is Object
        assertSchema("{'type':'object','properties':{'self':{'type':'object','properties':{'self':{}}}}}",
                generator.schemaFor(SelfBounded.class));
    }

    public static class SelfBounded<T extends SelfBounded<T>> {
        public T self;
    }

    public static class Holder<T extends CharSequence> {
        public T value;
        public List<? extends T> values;
    }

    public static class StringHolder extends Holder<String> {
    }

    @Test
    void definitionNameClashesAreDisambiguated() {
        JsonObject schema = generator.schemaFor(Clash.class);

        assertEquals("#/$defs/Item", schema.getJsonObject("properties").getJsonObject("a1").getString("$ref"));
        assertEquals("#/$defs/Item2", schema.getJsonObject("properties").getJsonObject("b1").getString("$ref"));
        assertEquals(2, schema.getJsonObject("$defs").size());
    }

    @Test
    void outputSchemaFollowsJsonbVisibilityRules() {
        // private getter hides the public field; getters without field; Introspector-style decapitalization;
        // is-getters for boolean and Boolean
        assertSchema("{'type':'object','properties':{'URL':{'type':'string'},'active':{'type':'boolean'},"
                + "'boxed':{'type':'boolean'},'computed':{'type':'string'},'hidden':{'type':'string'},"
                + "'readOnly':{'type':'string'}},'required':['active']}", generator.schemaFor(Accessors.class));
        assertSchema("{'type':'object','properties':{'secretField':{'type':'string'}}}",
                generator.schemaFor(FieldVisibility.class));
        assertSchema("{'type':'object','properties':{'displayName':{'type':'string'},'name':{'type':'string'}}}",
                generator.schemaFor(WithDefaultMethod.class));
    }

    @Test
    void superclassPropertiesComeFirst() {
        List<String> names = new ArrayList<>(generator.schemaFor(Scalars.class).getJsonObject("properties").keySet());

        assertEquals(List.of("id", "version"), names.subList(0, 2));
        assertFalse(names.contains("secret"), "@JsonbTransient");
        assertFalse(names.contains("constant"), "static");
        assertFalse(names.contains("volatileState"), "transient");
    }

    @Test
    void scalarMappings() {
        assertScalar("{'type':'string'}", String.class);
        assertScalar("{'type':'string','minLength':1,'maxLength':1}", char.class);
        assertScalar("{'type':'boolean'}", Boolean.class);
        assertScalar("{'type':'integer','minimum':-128,'maximum':127}", byte.class);
        assertScalar("{'type':'integer','minimum':-32768,'maximum':32767}", Short.class);
        assertScalar(INT, int.class);
        assertScalar("{'type':'integer'}", long.class);
        assertScalar("{'type':'integer'}", BigInteger.class);
        assertScalar("{'type':'number'}", double.class);
        assertScalar("{'type':'number'}", Float.class);
        assertScalar("{'type':'number'}", BigDecimal.class);
        assertScalar("{'type':'number'}", Number.class);
        assertScalar("{'type':'string','format':'date-time'}", Instant.class);
        assertScalar("{'type':'string','format':'date'}", LocalDate.class);
        assertScalar("{'type':'string','format':'time'}", OffsetTime.class);
        assertScalar("{'type':'string'}", LocalDateTime.class);
        assertScalar("{'type':'string'}", ZonedDateTime.class);
        assertScalar("{'type':'string'}", Duration.class);
        assertScalar("{'type':'string','format':'duration'}", Period.class);
        assertScalar("{'type':'string','format':'uuid'}", UUID.class);
        assertScalar("{'type':'array','items':{'type':'integer','minimum':-128,'maximum':127}}", byte[].class);
        assertScalar("{}", Object.class);
        assertScalar("{}", Thread.class);
        assertScalar("{'type':['object','array']}", jakarta.json.JsonStructure.class);
    }

    @Test
    void nillableProperties() {
        assertSchema("{'type':'object','properties':{'a':{'type':['string','null']},'c':{'type':'string'},"
                + "'d':{'type':['string','null']},'renamed':{'type':'string'}},'required':['a','d']}",
                generator.schemaFor(NillableClass.class));
    }

    @Test
    void nillableObjectsAndEnums() {
        assertSchema("{'type':'object','properties':{"
                + "'address':" + ADDRESS_OUT.replace("'type':'object'", "'type':['object','null']") + ","
                + "'status':{'anyOf':[{'type':'string','enum':['ACTIVE','INACTIVE']},{'type':'null'}]}},"
                + "'required':['status']}", generator.schemaFor(NillableRefs.class));
    }

    @jakarta.json.bind.annotation.JsonbNillable
    public static class NillableRefs {
        public Address address;
        public Status status;
    }

    @Test
    void nillableReferencesUseAnyOf() {
        assertSchema("{'type':'object','properties':{"
                + "'a':{'anyOf':[{'$ref':'#/$defs/Address'},{'type':'null'}]},"
                + "'b':{'anyOf':[{'$ref':'#/$defs/Address'},{'type':'null'}]}},"
                + "'$defs':{'Address':" + ADDRESS_OUT + "}}",
                generator.schemaFor(NillableTwice.class));
    }

    @jakarta.json.bind.annotation.JsonbNillable
    public static class NillableTwice {
        public Address a;
        public Address b;
    }

    @Test
    void optionalRootIsNullable() {
        assertSchema(ADDRESS_OUT.replace("'type':'object'", "'type':['object','null']"),
                generator.schemaFor(new TypeLiteral<Optional<Address>>() { }.type()));
    }

    @Test
    void customizedProperties() {
        assertSchema("{'type':'object','properties':{"
                + "'adapted':" + INT + ","
                + "'millis':{'type':'integer'},"
                + "'money':{'type':'string'},"
                + "'optionalPrice':{'type':'string'},"
                + "'price':{'type':'string'},"
                + "'prices':{'type':'array','items':{'type':'number'}},"
                + "'year':{'type':'string'}},"
                + "'required':['price']}", generator.schemaFor(Customized.class));
        assertSchema("{'type':'string'}", generator.schemaFor(Money.class));
    }

    @Test
    void customSerializersAcceptAnything() {
        assertSchema("{}", generator.schemaFor(Custom.class));
        assertSchema("{'type':'object','properties':{'custom':{},'plain':{'type':'string'}}}",
                generator.schemaFor(WithCustom.class));
        // deserialization isn't customized
        assertSchema("{'type':'object','properties':{'value':{'type':'string'}}}",
                generator.inputSchema(List.of(new SchemaProperty("c", Custom.class, null, false)))
                        .getJsonObject("properties").getJsonObject("c"));
    }

    @JsonbTypeSerializer(CustomSerializer.class)
    public static class Custom {
        public String value;
    }

    public static class WithCustom {
        public Custom custom;
        public String plain;
    }

    public static class CustomSerializer implements JsonbSerializer<Custom> {
        @Override
        public void serialize(Custom custom, JsonGenerator generator, SerializationContext context) {
            generator.write(42);
        }
    }

    @Test
    void transientOnAccessorOnlyAffectsItsDirection() {
        assertSchema("{'type':'object','properties':{'onlyWritten':{'type':'string'}}}",
                generator.schemaFor(TransientAccessors.class));
        assertSchema("{'type':'object','properties':{'onlyRead':{'type':'string'}}}",
                generator.inputSchema(List.of(new SchemaProperty("t", TransientAccessors.class, null, false)))
                        .getJsonObject("properties").getJsonObject("t"));
    }

    public static class TransientAccessors {
        private String onlyRead;
        private String onlyWritten;

        @JsonbTransient
        public String getOnlyRead() {
            return onlyRead;
        }

        public void setOnlyRead(String value) {
            onlyRead = value;
        }

        public String getOnlyWritten() {
            return onlyWritten;
        }

        @JsonbTransient
        public void setOnlyWritten(String value) {
            onlyWritten = value;
        }
    }

    @Test
    void containers() {
        JsonObject properties = generator.schemaFor(Containers.class).getJsonObject("properties");

        assertSchema("{}", properties.getJsonObject("byAddress"));
        assertSchema("{'type':'object','additionalProperties':{'type':'string'}}", properties.getJsonObject("byNumber"));
        assertSchema("{'type':'array','items':{'type':['string','null']}}", properties.getJsonObject("optionals"));
        assertSchema("{'type':'array','items':{'type':'array','items':{'$ref':'#/$defs/Address'}}}",
                properties.getJsonObject("grid"));
        assertSchema("{'type':'array','items':{'type':'string'}}", properties.getJsonObject("iterable"));
        assertSchema("{'type':'object'}", generator.schemaFor(new TypeLiteral<Map<String, Object>>() { }.type()));
    }

    @Test
    void outputSchemasOfNonObjectTypes() {
        assertSchema("{'type':'string'}", generator.schemaFor(String.class));
        assertSchema("{}", generator.schemaFor(Object.class));
        JsonObject strings = generator.schemaFor(new TypeLiteral<List<String>>() { }.type());
        assertSchema("{'type':'array','items':{'type':'string'}}", strings);
        assertTrue(SchemaValidator.validate(strings, parse("[\"a\",\"b\"]")).isEmpty());
        assertFalse(SchemaValidator.validate(strings, parse("{\"a\":1}")).isEmpty());
        JsonObject optional = generator.schemaFor(new TypeLiteral<Optional<String>>() { }.type());
        assertTrue(SchemaValidator.validate(optional, JsonValue.NULL).isEmpty(), optional::toString);
        assertTrue(SchemaValidator.validate(optional, parse("\"x\"")).isEmpty(), optional::toString);
        JsonObject people = generator.schemaFor(new TypeLiteral<List<Person>>() { }.type());
        assertEquals("array", people.getString("type"));
        assertTrue(people.containsKey("$defs") || people.getJsonObject("items").containsKey("properties"),
                people::toString);
    }

    @Test
    void outputIsDeterministic() {
        Type type = new TypeLiteral<Page<Page<Address>>>() { }.type();
        assertEquals(generator.schemaFor(type).toString(), new SchemaGenerator().schemaFor(type).toString());
        assertEquals(generator.schemaFor(Scalars.class).toString(), generator.schemaFor(Scalars.class).toString());
    }

    private void assertScalar(String expected, Type type) {
        assertSchema(expected, generator.schemaFor(type));
    }

    private static JsonValue parse(String json) {
        return Json.createReader(new StringReader(json)).readValue();
    }

    private static void assertSchema(String expected, JsonObject actual) {
        JsonValue expectedJson = Json.createReader(new StringReader(expected.replace('\'', '"'))).readValue();
        assertEquals(expectedJson, actual, () -> "actual: " + actual);
    }
}
