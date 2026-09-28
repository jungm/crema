package io.github.jungm.crema.internal.schema;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
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
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SortedMap;
import java.util.TimeZone;
import java.util.UUID;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.json.bind.adapter.JsonbAdapter;
import jakarta.json.bind.annotation.JsonbCreator;
import jakarta.json.bind.annotation.JsonbDateFormat;
import jakarta.json.bind.annotation.JsonbNillable;
import jakarta.json.bind.annotation.JsonbNumberFormat;
import jakarta.json.bind.annotation.JsonbProperty;
import jakarta.json.bind.annotation.JsonbTransient;
import jakarta.json.bind.annotation.JsonbTypeAdapter;
import jakarta.json.bind.annotation.JsonbVisibility;
import jakarta.json.bind.config.PropertyVisibilityStrategy;

/**
 * Application-like types for schema generation tests.
 */
public final class SampleModel {

    private SampleModel() {
    }

    /**
     * Captures a generic type: {@code new TypeLiteral<List<String>>() {}.type()}.
     */
    public abstract static class TypeLiteral<T> {
        public Type type() {
            return ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0];
        }
    }

    public enum Status { ACTIVE, INACTIVE { } }

    public record Address(String street, String city, int zip) {
    }

    public record Person(String name, int age, Optional<String> nickname, List<Address> addresses, Address primary,
                         Map<String, Integer> scores, Status status, @JsonbNillable String note,
                         @JsonbProperty("e-mail") String email) {
    }

    public static class Base {
        public String id;
        private long version;

        public long getVersion() {
            return version;
        }

        public void setVersion(long version) {
            this.version = version;
        }
    }

    public static class Scalars extends Base {
        public BigDecimal total;
        public BigInteger big;
        public Number number;
        public Instant instant;
        public LocalDate localDate;
        public LocalDateTime localDateTime;
        public LocalTime localTime;
        public OffsetDateTime offsetDateTime;
        public OffsetTime offsetTime;
        public ZonedDateTime zonedDateTime;
        public Duration duration;
        public Period period;
        public ZoneId zoneId;
        public ZoneOffset zoneOffset;
        public UUID uuid;
        public URI uri;
        public URL url;
        public Date date;
        public Calendar calendar;
        public TimeZone timeZone;
        public byte[] bytes;
        public char c;
        public Character boxedChar;
        public char[] chars;
        public boolean flag;
        public Boolean boxedFlag;
        public short s;
        public byte b;
        public int i;
        public long l;
        public float f;
        public double d;
        public Integer boxedInt;
        public OptionalInt optionalInt;
        public OptionalLong optionalLong;
        public OptionalDouble optionalDouble;
        public Object any;
        public JsonObject jsonObject;
        public JsonArray jsonArray;
        public JsonValue jsonValue;
        public Status status;
        @JsonbTransient
        public String secret;
        public static String constant = "constant";
        public transient String volatileState;
    }

    public static class TreeNode {
        public String label;
        public List<TreeNode> children;

        public TreeNode() {
        }

        public TreeNode(String label, List<TreeNode> children) {
            this.label = label;
            this.children = children;
        }
    }

    public static class Page<T> {
        public List<T> items;
        public T first;
        public int total;
    }

    public static class AddressPage extends Page<Address> {
    }

    public static class Wrapper<T extends Number> {
        public T value;
        public List<? extends T> values;
    }

    public static class Accessors {
        private String hidden = "hidden";
        public String privateGetter = "field";
        private final String readOnly = "readOnly";

        public String getComputed() {
            return "computed";
        }

        public String getHidden() {
            return hidden;
        }

        private String getPrivateGetter() {
            return privateGetter;
        }

        public String getURL() {
            return "url";
        }

        public boolean isActive() {
            return true;
        }

        public Boolean isBoxed() {
            return Boolean.TRUE;
        }

        public void setWriteOnly(String value) {
        }

        public String getReadOnly() {
            return readOnly;
        }
    }

    @JsonbNillable
    public static class NillableClass {
        public String a;
        @JsonbProperty("renamed")
        public String b;
        @JsonbNillable(false)
        public String c;
        public Optional<String> d = Optional.empty();
    }

    public static class Customized {
        @JsonbTypeAdapter(LengthAdapter.class)
        public String adapted = "abc";
        @JsonbNumberFormat("#0.00")
        public double price = 1.5;
        @JsonbNumberFormat("#0.00")
        public List<Double> prices = List.of(1.0, 2.5);
        @JsonbNumberFormat("#0.00")
        public Optional<Double> optionalPrice = Optional.of(2.0);        @JsonbDateFormat("yyyy")
        public LocalDate year = LocalDate.of(2024, 5, 1);
        @JsonbDateFormat(JsonbDateFormat.TIME_IN_MILLIS)
        public Instant millis = Instant.ofEpochMilli(1234);
        public Money money = new Money(12);
    }

    public static class LengthAdapter implements JsonbAdapter<String, Integer> {
        @Override
        public Integer adaptToJson(String value) {
            return value.length();
        }

        @Override
        public String adaptFromJson(Integer value) {
            return "x".repeat(value);
        }
    }

    @JsonbTypeAdapter(MoneyAdapter.class)
    public static class Money {
        public final long cents;

        public Money(long cents) {
            this.cents = cents;
        }
    }

    public static class MoneyAdapter implements JsonbAdapter<Money, String> {
        @Override
        public String adaptToJson(Money money) {
            return money.cents / 100 + "." + money.cents % 100;
        }

        @Override
        public Money adaptFromJson(String value) {
            return new Money(new BigDecimal(value).movePointRight(2).longValue());
        }
    }

    public static class FieldsOnly implements PropertyVisibilityStrategy {
        @Override
        public boolean isVisible(Field field) {
            return true;
        }

        @Override
        public boolean isVisible(Method method) {
            return false;
        }
    }

    @JsonbVisibility(FieldsOnly.class)
    public static class FieldVisibility {
        private String secretField = "field";

        public String getOther() {
            return "other";
        }
    }

    public interface Named {
        default String getDisplayName() {
            return "display";
        }
    }

    public static class WithDefaultMethod implements Named {
        public String name = "name";
    }

    public static class Containers {
        public List<Optional<String>> optionals;
        public Set<Status> statuses;
        public Map<Status, String> byStatus;
        public Map<Integer, String> byNumber;
        public Map<Address, String> byAddress;
        public SortedMap<String, List<Address>> nested;
        public Address[][] grid;
        public List<Map<String, Address>> listOfMaps;
        public Iterable<String> iterable;
    }

    public static class Creator {
        private final String name;
        private final int count;

        @JsonbCreator
        public Creator(@JsonbProperty("name") String name, @JsonbProperty("count") int count) {
            this.name = name;
            this.count = count;
        }

        public String getName() {
            return name;
        }

        public int getCount() {
            return count;
        }

        public String extra;
    }

    public static final class Outer1 {
        private Outer1() {
        }

        public record Item(String a) {
        }
    }

    public static final class Outer2 {
        private Outer2() {
        }

        public record Item(int b) {
        }
    }

    public record Clash(Outer1.Item a1, Outer1.Item a2, Outer2.Item b1, Outer2.Item b2) {
    }
}
