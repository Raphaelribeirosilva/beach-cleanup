package com.raphael.beachcleanup;

import java.util.List;
import org.springframework.context.ApplicationContext;
import com.raphael.beachcleanup.dto.CatalogInputDTO;
import com.raphael.beachcleanup.services.CatalogNames;
import com.raphael.beachcleanup.services.CatalogService;

/** Explicit catalogs for existing domain tests; production never auto-creates unknown names. */
final class CatalogTestData {
    static void seed(ApplicationContext context) {
        var service = context.getBean(CatalogService.class);
        for (String kind : List.of("beaches", "materials")) {
            var existing = service.list(kind).stream().flatMap(item -> item.aliases().stream()).toList();
            var names = kind.equals("beaches")
                ? List.of("Praia A", "Praia B", "Praia São José", "Primeira edicao", "Formulario antigo", "Edicao revisada")
                : List.of("Plástico", "Vidro", "Metal", "Papel");
            for (String name : names) {
                if (!existing.contains(CatalogNames.key(name))) {
                    service.create(kind, new CatalogInputDTO(name, List.of(), null));
                }
            }
        }
    }
}
