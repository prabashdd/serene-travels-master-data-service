package lk.serenetravels.masterdataservice.util;

import lk.serenetravels.masterdataservice.dto.RateUnit;
import lk.serenetravels.masterdataservice.exception.NotFoundException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// The registry the generic rate engine is driven by. All ten price tables share one shape -
// (key columns..., value, effective_from, effective_to, created_at, created_by) - so a family is
// just "which table, which columns are the key, what is the value column, what unit is it in".
// Table and column names here are the ONLY identifiers ever concatenated into SQL, and they come
// from this fixed list, never from a request.
public final class RateFamilies {

    public enum KeyType {
        INT,
        TEXT
    }

    /**
     * @param name        JSON name of the key, e.g. propertyId
     * @param column      table column
     * @param label       human label for generic forms
     * @param lookup      lookup/resource that supplies the choices (null for free values)
     * @param required    must be supplied on write
     * @param nullMeansAll a NULL value is a real row meaning "applies to all" (e.g. all nationalities)
     * @param allLabel    suffix of the `matched` value when resolve falls back to the NULL row
     * @param labelSql    query returning (id, name) for `:ids`, used to fill display labels; null = no label
     * @param allowedValues the only values a TEXT key may take (mirrors a CHECK constraint); null = any text
     */
    public record Key(String name, String column, KeyType type, String label, String lookup, boolean required,
                      boolean nullMeansAll, String allLabel, String labelSql, List<String> allowedValues) {
    }

    /**
     * @param fanOutKey optional key that, when omitted on write, means "every value from fanOutSql"
     *                  (vehicle rates: no season = all seasons, since a flat rate is stored once per season)
     */
    public record Family(String name, String label, String table, String valueColumn, RateUnit unit, boolean perKm,
                         BigDecimal max, List<Key> keys, String fanOutKey, String fanOutSql) {

        public Key key(String keyName) {
            return keys.stream().filter(k -> k.name().equals(keyName)).findFirst().orElse(null);
        }
    }

    private static final BigDecimal MAX_MONEY = new BigDecimal("99999999.99");
    private static final BigDecimal MAX_PERCENT = new BigDecimal("100.00");

    // Mirrors the CHECK constraint on attraction_rate.rate_type.
    private static final List<String> ATTRACTION_RATE_TYPES = List.of("PER_PERSON", "FIXED_GROUP_BASE", "EXTRA_PERSON",
            "GUEST_ENTRY_FEE", "GUIDE_ENTRY_FEE", "CHILD_DISCOUNT", "JEEP_RATE");

    private static final Map<String, Family> FAMILIES = new LinkedHashMap<>();

    static {
        add(new Family("property", "Property room rates", "property_rate", "rate", RateUnit.USD, false, MAX_MONEY,
                List.of(
                        ref("propertyId", "property_id", "Property", "properties", "property"),
                        ref("roomTypeId", "room_type_id", "Room type", null, "room_type"),
                        ref("seasonId", "season_id", "Season", "seasons", "season"),
                        number("occupancy", "occupancy", "Occupancy"),
                        all("nationalityId", "nationality_id", "Nationality", "nationalities", "nationality",
                                "ALL_NATIONALITIES")),
                null, null));
        add(new Family("vehicle", "Vehicle rates (per km)", "vehicle_rate", "rate", RateUnit.LKR, true, MAX_MONEY,
                List.of(
                        ref("vehicleTypeId", "vehicle_type_id", "Vehicle type", "vehicle-types", "vehicle_type"),
                        ref("seasonId", "season_id", "Season", "seasons", "season")),
                "seasonId", "select id from season order by id"));
        add(new Family("attraction", "Attraction rates", "attraction_rate", "rate", RateUnit.USD, false, MAX_MONEY,
                List.of(
                        ref("attractionId", "attraction_id", "Attraction", "attractions", "attraction"),
                        text("rateType", "rate_type", "Rate type", ATTRACTION_RATE_TYPES)),
                null, null));
        add(new Family("guide-accommodation", "Guide accommodation rates", "guide_accommodation_rate", "rate",
                RateUnit.LKR, false, MAX_MONEY,
                List.of(
                        ref("seasonId", "season_id", "Season", "seasons", "season"),
                        all("destinationId", "destination_id", "Destination", "destinations", "destination",
                                "ALL_DESTINATIONS")),
                null, null));
        add(new Family("misc", "Miscellaneous cost rates", "misc_cost_rate", "rate", RateUnit.USD, false, MAX_MONEY,
                List.of(
                        ref("miscCostTypeId", "misc_cost_type_id", "Cost type", "misc-cost-types", "misc_cost_type"),
                        all("nationalityId", "nationality_id", "Nationality", "nationalities", "nationality",
                                "ALL_NATIONALITIES")),
                null, null));
        add(new Family("tuk-tuk", "Tuk-tuk fares", "tuk_tuk_fare_rate", "fare", RateUnit.LKR, false, MAX_MONEY,
                List.of(new Key("tukTukLocationId", "tuk_tuk_location_id", KeyType.INT, "Tuk-tuk location", null, true,
                        false, null,
                        "select l.id, a.name from tuk_tuk_location l join attraction a on a.id = l.attraction_id "
                                + "where l.id in (:ids)", null)),
                null, null));
        add(new Family("package", "Site tour package rates", "site_tour_package_rate", "rate", RateUnit.USD, false,
                MAX_MONEY,
                List.of(ref("packageId", "package_id", "Package", "packages", "site_tour_package")),
                null, null));
        add(new Family("tax", "Tax rates", "tax_rate_value", "percentage", RateUnit.PERCENT, false, MAX_PERCENT,
                List.of(ref("taxRateId", "tax_rate_id", "Tax", "tax-rates", "tax_rate")),
                null, null));
        // The unit is USD until promotion.type has agreed values: a percentage promotion would be PERCENT.
        add(new Family("promotion", "Promotion values", "promotion_value", "\"value\"", RateUnit.USD, false,
                MAX_MONEY,
                List.of(ref("promotionId", "promotion_id", "Promotion", "promotions", "promotion")),
                null, null));
        add(new Family("transport", "Transport rate (per km)", "transport_rate_config", "per_km_rate", RateUnit.LKR,
                true, MAX_MONEY, List.of(), null, null));
    }

    private RateFamilies() {
    }

    public static Family require(String name) {
        Family family = FAMILIES.get(name);
        if (family == null) {
            throw new NotFoundException("Unknown rate family '" + name + "'");
        }
        return family;
    }

    public static List<Family> all() {
        return List.copyOf(FAMILIES.values());
    }

    private static void add(Family family) {
        FAMILIES.put(family.name(), family);
    }

    private static Key ref(String name, String column, String label, String lookup, String labelTable) {
        return new Key(name, column, KeyType.INT, label, lookup, true, false, null, labelSql(labelTable), null);
    }

    private static Key all(String name, String column, String label, String lookup, String labelTable,
                           String allLabel) {
        return new Key(name, column, KeyType.INT, label, lookup, false, true, allLabel, labelSql(labelTable), null);
    }

    private static Key number(String name, String column, String label) {
        return new Key(name, column, KeyType.INT, label, null, true, false, null, null, null);
    }

    private static Key text(String name, String column, String label, List<String> allowedValues) {
        return new Key(name, column, KeyType.TEXT, label, null, true, false, null, null, allowedValues);
    }

    private static String labelSql(String table) {
        return "select id, name from " + table + " where id in (:ids)";
    }
}
