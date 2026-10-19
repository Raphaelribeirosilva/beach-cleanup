package com.raphael.beachcleanup.services;

import java.util.*;
import com.raphael.beachcleanup.exceptions.FieldValidationException;

/** Local IBGE snapshot shared with the portal; no network dependency at runtime. */
public final class Geography {
    private static final Map<String, List<String>> CITIES = load();
    private Geography() {}
    private static Map<String, List<String>> load() {
        try (var source = Geography.class.getResourceAsStream("/static/locations.json")) {
            var result = new LinkedHashMap<String, List<String>>();
            for (var state : new tools.jackson.databind.ObjectMapper().readTree(source)) {
                var cities = new ArrayList<String>();
                for (var city : state.get("cities")) cities.add(city.asText());
                result.put(state.get("uf").asText(), List.copyOf(cities));
            }
            return Map.copyOf(result);
        } catch (Exception exception) {
            throw new IllegalStateException("Não foi possível carregar a base de municípios.", exception);
        }
    }
    public static void requireState(String state) {
        if (state == null || !CITIES.containsKey(state))
            throw new FieldValidationException("state", "Selecione um Estado brasileiro válido.");
    }
    public static String requireCity(String state, String city) {
        requireState(state);
        String key = CatalogNames.key(city);
        return CITIES.get(state).stream().filter(name -> CatalogNames.key(name).equals(key)).findFirst()
            .orElseThrow(() -> new FieldValidationException("city", "Selecione uma cidade pertencente ao Estado informado."));
    }
}
