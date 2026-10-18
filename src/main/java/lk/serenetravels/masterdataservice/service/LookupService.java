package lk.serenetravels.masterdataservice.service;

import java.util.List;
import java.util.Map;

public interface LookupService {

    // Every lookup keyed by its bundle name (destinations, vehicleTypes, ...), for FE dropdown bootstrap.
    Map<String, List<Map<String, Object>>> bundle(boolean includeInactive);

    List<Map<String, Object>> list(String name, boolean includeInactive);

    Map<String, Object> create(String name, Map<String, Object> body);

    Map<String, Object> update(String name, int id, Map<String, Object> body);

    // Soft-deletes (active=false) lookups that support it, otherwise removes the row; 409 IN_USE if referenced.
    void delete(String name, int id);
}
