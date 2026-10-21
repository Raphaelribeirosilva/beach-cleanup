package com.raphael.beachcleanup.services;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import com.raphael.beachcleanup.dto.CatalogInputDTO;
import com.raphael.beachcleanup.entities.CatalogEntry;
import com.raphael.beachcleanup.entities.CleanupEvent;
import com.raphael.beachcleanup.entities.EventStatus;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Entire batch and marker commit together. Never edits events from previous runs. */
@Service
@ConditionalOnProperty(name = "demo.data.enabled", havingValue = "true")
public class DemoDataService {
    private final JdbcTemplate jdbc;
    private final CleanupEventService events;
    private final CatalogService catalogs;
    private final Clock clock;

    public DemoDataService(JdbcTemplate jdbc, CleanupEventService events, CatalogService catalogs, Clock clock) {
        this.jdbc = jdbc; this.events = events; this.catalogs = catalogs; this.clock = clock;
    }

    @Transactional
    public void seed() {
        Boolean completed = jdbc.queryForObject(
            "select completed from tb_demo_data where seed_key='coastal-events-v1' for update", Boolean.class);
        if (Boolean.TRUE.equals(completed)) {
            LoggerFactory.getLogger(getClass()).info("Carga de demonstração já aplicada; eventos preservados.");
            return;
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("America/Sao_Paulo")));
        create("Praia do Morro", "Guarapari", "Cuidado com a areia", today.plusDays(7),
            "Simulação de observação e separação de resíduos na faixa de areia.", false);
        create("Praia de Setiba", "Guarapari", "Conhecendo os resíduos", today.plusDays(14),
            "Simulação educativa sobre identificação de resíduos e preservação da praia.", false);
        create("Praia da Costa", "Vila Velha", "Orla consciente", today.plusDays(21),
            "Simulação de um mutirão de cuidado com a orla e descarte responsável.", false);
        create("Praia de Itaparica", "Vila Velha", "Memória de um mutirão", today.minusDays(7),
            "Exemplo fictício de evento finalizado para demonstrar o histórico do portal. Nenhuma coleta real é atribuída a ele.", true);
        jdbc.update("update tb_demo_data set completed=true where seed_key='coastal-events-v1'");
        LoggerFactory.getLogger(getClass()).info("Carga de demonstração aplicada: quatro eventos fictícios.");
    }

    private void create(String beach, String city, String theme, LocalDate date, String narrative, boolean finished) {
        String qualified = beach + " — " + city + "/ES";
        // Reuse existing, correctly located catalog entries, including manually registered ones.
        var existing = catalogs.list("beaches").stream().filter(item -> "ES".equals(item.state())
            && city.equals(item.city()) && (CatalogNames.key(item.name()).equals(CatalogNames.key(beach))
                || CatalogNames.key(item.name()).equals(CatalogNames.key(qualified)))).findFirst();
        var place = existing.orElseGet(() -> catalogs.create("beaches",
            new CatalogInputDTO(qualified, List.of(), null, "ES", city)));
        CleanupEvent input = new CleanupEvent(null, "[DEMONSTRAÇÃO FICTÍCIA] " + theme + " — " + beach,
            null, date.atTime(8, 0), EventStatus.SCHEDULED.value());
        input.setBeachReference(CatalogEntry.reference(place.id()));
        input.setDescription("Evento fictício de demonstração do Elo do Mar. Não há ação real agendada nem ponto de encontro. "
            + "A data e as inscrições são apenas para testar o site; não compareça ao local. " + narrative);
        CleanupEvent saved = events.insert(input);
        if (finished) {
            // Use separate input objects: mutating a managed entity would bypass transition validation.
            for (EventStatus status : List.of(EventStatus.IN_PROGRESS, EventStatus.COMPLETED)) {
                CleanupEvent change = new CleanupEvent(null, saved.getTitle(), null, saved.getDateTime(), status.value());
                change.setBeachReference(CatalogEntry.reference(place.id()));
                saved = events.update(saved.getId(), change, saved.getRowVersion());
            }
        }
    }
}
