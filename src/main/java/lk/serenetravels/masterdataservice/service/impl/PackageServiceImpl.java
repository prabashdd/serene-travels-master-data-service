package lk.serenetravels.masterdataservice.service.impl;

import lk.serenetravels.masterdataservice.dto.PackageResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.RateAmount;
import lk.serenetravels.masterdataservice.dto.RateUnit;
import lk.serenetravels.masterdataservice.dto.RefResponse;
import lk.serenetravels.masterdataservice.exception.ConflictException;
import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.exception.NotFoundException;
import lk.serenetravels.masterdataservice.service.PackageService;
import lk.serenetravels.masterdataservice.util.Coerce;
import lk.serenetravels.masterdataservice.util.ColumnValues;
import lk.serenetravels.masterdataservice.util.LookupDefinitions.Column;
import lk.serenetravels.masterdataservice.util.LookupDefinitions.Type;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PackageServiceImpl implements PackageService {

    private static final int MAX_PAGE_SIZE = 200;

    private static final List<Column> COLUMNS = List.of(new Column("name", "name", Type.TEXT, true));

    private static final Map<String, String> SORTS = Map.of("name", "p.name", "id", "p.id");

    private final JdbcClient jdbc;
    private final Clock clock;

    public PackageServiceImpl(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reads

    @Override
    public PageResponse<PackageResponse> list(String q, Integer attractionId, LocalDate at, List<String> sort, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        Map<String, Object> params = new HashMap<>();
        List<String> where = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            where.add("lower(p.name) like :q escape '\\'");
            params.put("q", "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (attractionId != null) {
            where.add("exists (select 1 from site_tour_package_site s where s.package_id = p.id and s.attraction_id = :attractionId)");
            params.put("attractionId", attractionId);
        }
        String whereSql = where.isEmpty() ? "" : " where " + String.join(" and ", where);

        Long total = jdbc.sql("select count(*) from site_tour_package p" + whereSql).params(params).query(Long.class).single();

        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<PackageResponse> rows = jdbc.sql("select p.id, p.name from site_tour_package p" + whereSql
                        + " order by " + orderBy(sort) + " limit :limit offset :offset")
                .params(params).query((rs, n) -> new PackageResponse(rs.getInt("id"), rs.getString("name"), List.of(), null)).list();
        return PageResponse.of(withDetails(rows, at), total, page, size);
    }

    @Override
    public PackageResponse get(int id, LocalDate at) {
        PackageResponse row = jdbc.sql("select id, name from site_tour_package where id = :id").param("id", id)
                .query((rs, n) -> new PackageResponse(rs.getInt("id"), rs.getString("name"), List.of(), null))
                .optional().orElseThrow(() -> notFound(id));
        return withDetails(List.of(row), at).get(0);
    }

    // ------------------------------------------------------------------ writes

    @Override
    @Transactional
    public PackageResponse create(Map<String, Object> body) {
        Map<String, Object> fields = new LinkedHashMap<>(body);
        Object rawSites = fields.remove("attractionIds");

        Map<String, Object> values = ColumnValues.coerce("package", COLUMNS, fields, true);
        Map<String, String> errors = new LinkedHashMap<>();
        List<Integer> sites = List.of();
        if (rawSites != null) {
            try {
                sites = parseIds(rawSites, "attractionIds");
            } catch (InvalidRequestException ex) {
                errors.putAll(ex.getFieldErrors());
            }
        }
        checkAttractionsExist(sites, "attractionIds", errors);
        throwIfAny(errors);
        checkNameFree((String) values.get("name"), null);

        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("insert into site_tour_package (name) values (:name)").params(values).update(keyHolder, "id");
        int id = keyHolder.getKey().intValue();
        insertSites(id, sites);
        return get(id, null);
    }

    @Override
    @Transactional
    public PackageResponse update(int id, Map<String, Object> body) {
        get(id, null);
        Map<String, Object> values = ColumnValues.coerce("package", COLUMNS, body, false);
        if (values.isEmpty()) {
            throw new InvalidRequestException("Nothing to change: provide at least one field");
        }
        checkNameFree((String) values.get("name"), id);
        jdbc.sql("update site_tour_package set name = :name where id = :id").param("name", values.get("name")).param("id", id).update();
        return get(id, null);
    }

    @Override
    @Transactional
    public void delete(int id) {
        get(id, null);
        Long rates = jdbc.sql("select count(*) from site_tour_package_rate where package_id = :id").param("id", id)
                .query(Long.class).single();
        if (rates > 0) {
            throw new ConflictException("IN_USE", "This package has price history, which is never deleted; rename it instead");
        }
        jdbc.sql("delete from site_tour_package_site where package_id = :id").param("id", id).update();
        jdbc.sql("delete from site_tour_package where id = :id").param("id", id).update();
    }

    @Override
    @Transactional
    public List<RefResponse> replaceSites(int packageId, List<Integer> attractionIds) {
        get(packageId, null);
        if (attractionIds == null) {
            throw new InvalidRequestException("The request body must be a list of attraction ids (use [] to clear it)");
        }
        List<Integer> ids = parseIds(attractionIds, "attractionIds");
        Map<String, String> errors = new LinkedHashMap<>();
        checkAttractionsExist(ids, "attractionIds", errors);
        throwIfAny(errors);

        jdbc.sql("delete from site_tour_package_site where package_id = :id").param("id", packageId).update();
        insertSites(packageId, ids);
        return sites(List.of(packageId)).getOrDefault(packageId, List.of());
    }

    // ------------------------------------------------------------------ validation

    // A package name is unique across all packages (the database enforces it case-sensitively; people do not).
    private void checkNameFree(String name, Integer selfId) {
        Long clash = jdbc.sql("select count(*) from site_tour_package where lower(name) = :name and id <> :self")
                .param("name", name.toLowerCase()).param("self", selfId == null ? -1 : selfId).query(Long.class).single();
        if (clash > 0) {
            throw new ConflictException("DUPLICATE", "A package named '" + name + "' already exists");
        }
    }

    private void checkAttractionsExist(List<Integer> ids, String field, Map<String, String> errors) {
        if (ids.isEmpty()) {
            return;
        }
        Set<Integer> existing = new HashSet<>(jdbc.sql("select id from attraction where id in (:ids)")
                .param("ids", ids).query(Integer.class).list());
        for (int i = 0; i < ids.size(); i++) {
            if (!existing.contains(ids.get(i))) {
                errors.put(field + "[" + i + "]", "is not a known attraction");
            }
        }
    }

    private List<Integer> parseIds(Object raw, String field) {
        if (!(raw instanceof List<?> list)) {
            throw InvalidRequestException.field(field, "must be a list of attraction ids");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        Set<Integer> seen = new HashSet<>();
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            try {
                Integer id = Coerce.toInt(list.get(i), field + "[" + i + "]");
                if (!seen.add(id)) {
                    errors.put(field + "[" + i + "]", "is listed more than once");
                }
                ids.add(id);
            } catch (InvalidRequestException ex) {
                errors.putAll(ex.getFieldErrors());
            }
        }
        throwIfAny(errors);
        return ids;
    }

    private void throwIfAny(Map<String, String> errors) {
        if (!errors.isEmpty()) {
            throw new InvalidRequestException("Validation failed", errors);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void insertSites(int packageId, List<Integer> attractionIds) {
        for (Integer attractionId : attractionIds) {
            jdbc.sql("insert into site_tour_package_site (package_id, attraction_id) values (:p, :a)")
                    .param("p", packageId).param("a", attractionId).update();
        }
    }

    private Map<Integer, List<RefResponse>> sites(List<Integer> packageIds) {
        Map<Integer, List<RefResponse>> byPackage = new HashMap<>();
        jdbc.sql("""
                        select s.package_id, a.id, a.name from site_tour_package_site s
                          join attraction a on a.id = s.attraction_id
                         where s.package_id in (:ids) order by s.package_id, a.name, a.id""")
                .param("ids", packageIds)
                .query((rs, n) -> {
                    byPackage.computeIfAbsent(rs.getInt("package_id"), k -> new ArrayList<>())
                            .add(new RefResponse(rs.getInt("id"), rs.getString("name")));
                    return null;
                }).list();
        return byPackage;
    }

    // One query each for the whole page: the included sites and the price in force on `at`.
    private List<PackageResponse> withDetails(List<PackageResponse> rows, LocalDate at) {
        if (rows.isEmpty()) {
            return rows;
        }
        LocalDate day = at == null ? LocalDate.now(clock) : at;
        List<Integer> ids = rows.stream().map(PackageResponse::id).toList();
        Map<Integer, List<RefResponse>> sitesById = sites(ids);
        Map<Integer, BigDecimal> rateById = new HashMap<>();
        jdbc.sql("""
                        select package_id, rate from site_tour_package_rate
                         where package_id in (:ids) and effective_from <= :day
                           and (effective_to is null or effective_to > :day)""")
                .param("ids", ids).param("day", day)
                .query((rs, n) -> {
                    rateById.put(rs.getInt("package_id"), rs.getBigDecimal("rate"));
                    return null;
                }).list();
        return rows.stream().map(r -> new PackageResponse(r.id(), r.name(), sitesById.getOrDefault(r.id(), List.of()),
                rateById.containsKey(r.id()) ? new RateAmount(rateById.get(r.id()), RateUnit.USD) : null)).toList();
    }

    private String orderBy(List<String> sort) {
        List<String> parts = new ArrayList<>();
        if (sort != null) {
            for (String entry : sort) {
                String[] bits = entry.split(",");
                String sql = SORTS.get(bits[0].trim());
                String direction = bits.length > 1 ? bits[1].trim().toLowerCase() : "asc";
                if (sql == null || bits.length > 2 || !(direction.equals("asc") || direction.equals("desc"))) {
                    throw InvalidRequestException.field("sort",
                            "'" + entry + "' is not valid; use field,asc|desc with field one of " + SORTS.keySet());
                }
                parts.add(sql + " " + direction);
            }
        }
        if (parts.isEmpty()) {
            parts.add("p.name asc");
        }
        parts.add("p.id asc");
        return String.join(", ", parts);
    }

    private NotFoundException notFound(int id) {
        return new NotFoundException("No package found with id '" + id + "'");
    }
}
