package lk.serenetravels.masterdataservice.util;

import lk.serenetravels.masterdataservice.exception.NotFoundException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Cold reference tables served by the generic lookup endpoints. As with RateFamilies, these fixed
// definitions are the only source of table/column identifiers that reach SQL.
public final class LookupDefinitions {

    public enum Type {
        TEXT,
        INT,
        DECIMAL,
        DATE,
        BOOL
    }

    public record Column(String json, String db, Type type, boolean required) {
    }

    /**
     * @param name          path segment, e.g. vehicle-types
     * @param bundleKey     key in the GET /lookups bundle, e.g. vehicleTypes
     * @param softDeleteColumn when set, DELETE sets this boolean column to false instead of removing the row
     */
    public record Lookup(String name, String bundleKey, String table, String orderBy, List<Column> columns,
                         String softDeleteColumn) {

        public Column column(String json) {
            return columns.stream().filter(c -> c.json().equals(json)).findFirst().orElse(null);
        }
    }

    private static final Map<String, Lookup> LOOKUPS = new LinkedHashMap<>();

    static {
        add(new Lookup("destinations", "destinations", "destination", "name", List.of(
                text("name", true), text("region", false), text("country", true)), null));
        add(new Lookup("nationalities", "nationalities", "nationality", "name", List.of(
                text("name", true), new Column("isoCode", "iso_code", Type.TEXT, true)), null));
        add(new Lookup("languages", "languages", "language", "name", List.of(text("name", true)), null));
        add(new Lookup("seasons", "seasons", "season", "start_date, id", List.of(
                text("name", true),
                new Column("startDate", "start_date", Type.DATE, true),
                new Column("endDate", "end_date", Type.DATE, true)), null));
        add(new Lookup("vehicle-types", "vehicleTypes", "vehicle_type", "min_capacity, id", List.of(
                text("name", true),
                new Column("minCapacity", "min_capacity", Type.INT, true),
                new Column("maxCapacity", "max_capacity", Type.INT, true)), null));
        add(new Lookup("dietary-categories", "dietaryCategories", "dietary_category", "name", List.of(
                text("name", true)), null));
        add(new Lookup("meal-plans", "mealPlans", "meal_plan_type", "code", List.of(
                text("code", true), text("name", true)), null));
        add(new Lookup("hotel-inclusions", "hotelInclusions", "hotel_inclusion", "name", List.of(
                text("name", true), text("description", false)), null));
        add(new Lookup("misc-cost-types", "miscCostTypes", "misc_cost_type", "name", List.of(
                text("name", true)), null));
        add(new Lookup("tax-rates", "taxRates", "tax_rate", "name", List.of(text("name", true)), null));
        add(new Lookup("technical-visit-categories", "technicalVisitCategories", "technical_visit_category", "name",
                List.of(text("name", true), new Column("active", "active", Type.BOOL, false)), "active"));
    }

    private LookupDefinitions() {
    }

    public static Lookup require(String name) {
        Lookup lookup = LOOKUPS.get(name);
        if (lookup == null) {
            throw new NotFoundException("Unknown lookup '" + name + "'");
        }
        return lookup;
    }

    public static List<Lookup> all() {
        return List.copyOf(LOOKUPS.values());
    }

    private static void add(Lookup lookup) {
        LOOKUPS.put(lookup.name(), lookup);
    }

    private static Column text(String name, boolean required) {
        return new Column(name, name, Type.TEXT, required);
    }
}
