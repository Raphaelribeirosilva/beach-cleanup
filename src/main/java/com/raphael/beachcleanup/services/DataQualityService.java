package com.raphael.beachcleanup.services;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.raphael.beachcleanup.dto.PageDTO;

@Service
@Transactional(readOnly = true)
public class DataQualityService {
    public record Issue(String resource, long id, String code, String message) {}
    public record Rounding(long collectionId, Long volunteerId, String originalWeight,
        String roundedWeight, String deltaWeight) {}
    private final JdbcTemplate jdbc;
    private final CollectionDataRules rules;
    public DataQualityService(JdbcTemplate jdbc, CollectionDataRules rules) { this.jdbc = jdbc; this.rules = rules; }
    private static final Map<String, String> MESSAGES = Map.of(
        "MISSING_BEACH", "Selecione uma praia ativa do catálogo.",
        "MISSING_MATERIAL", "Selecione um material ativo do catálogo.",
        "INVALID_WEIGHT", "Revise o peso: permitido de 0,001 a 100.000 kg.",
        "INVALID_DATE", "Revise a data: coleta entre 1900 e hoje; evento entre 1900 e 9999.",
        "EVENT_BEACH", "A praia da coleta difere da praia do evento ou não está identificada.",
        "EVENT_DATE", "A data da coleta difere da data do evento ou não está informada.");
    private static final String ISSUES = """
        select 'collection' as resource,c.id,'MISSING_BEACH' as code from tb_trash_collection c
        left join tb_catalog_entry b on b.id=c.beach_id and b.kind='beaches' and b.merged_into_id is null where b.id is null
        union all select 'collection',c.id,'MISSING_MATERIAL' from tb_trash_collection c
        left join tb_catalog_entry m on m.id=c.material_id and m.kind='materials' and m.merged_into_id is null where m.id is null
        union all select 'collection',id,'INVALID_WEIGHT' from tb_trash_collection
        where weight_kg is null or weight_kg<0.001 or weight_kg>100000
        union all select 'collection',id,'INVALID_DATE' from tb_trash_collection
        where registration_date is null or registration_date<'1900-01-01' or registration_date>?
        union all select 'collection',c.id,'EVENT_BEACH' from tb_trash_collection c join tb_cleanup_event e on c.event_id=e.id
        where c.beach_id is null or e.beach_id is null or c.beach_id<>e.beach_id
        union all select 'collection',c.id,'EVENT_DATE' from tb_trash_collection c join tb_cleanup_event e on c.event_id=e.id
        where c.registration_date is null or e.date_time is null or c.registration_date<>cast(e.date_time as date)
        union all select 'event',e.id,'MISSING_BEACH' from tb_cleanup_event e
        left join tb_catalog_entry b on b.id=e.beach_id and b.kind='beaches' and b.merged_into_id is null where b.id is null
        union all select 'event',id,'INVALID_DATE' from tb_cleanup_event
        where date_time is null or date_time<'1900-01-01' or date_time>'9999-12-31 23:59:59.999999'
        """;

    public PageDTO<Issue> issues(int page, int size) {
        validatePage(page, size);
        var today = java.sql.Date.valueOf(rules.today());
        long total = jdbc.queryForObject("select count(*) from (" + ISSUES + ") q", Long.class, today);
        var content = jdbc.query("select * from (" + ISSUES + ") q order by resource,id,code limit ? offset ?",
            (rows, index) -> new Issue(rows.getString("resource"), rows.getLong("id"), rows.getString("code"),
                MESSAGES.get(rows.getString("code"))), today, size, (long) page * size);
        return new PageDTO<>(content, page, size, total, (int) ((total + size - 1) / size));
    }

    public PageDTO<Rounding> rounding(int page, int size) {
        validatePage(page, size);
        long total = jdbc.queryForObject("select count(*) from tb_weight_conversion_audit", Long.class);
        List<Rounding> content = jdbc.query("select * from tb_weight_conversion_audit order by collection_id limit ? offset ?",
            (rows, index) -> new Rounding(rows.getLong("collection_id"),
                rows.getObject("volunteer_id") == null ? null : rows.getLong("volunteer_id"), rows.getString("original_weight"),
                rows.getBigDecimal("rounded_weight") == null ? null : rows.getBigDecimal("rounded_weight").toPlainString(),
                rows.getString("delta_weight")), size, (long) page * size);
        return new PageDTO<>(content, page, size, total, (int) ((total + size - 1) / size));
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new com.raphael.beachcleanup.exceptions.FieldValidationException("page", "Use página >= 0 e tamanho entre 1 e 100.");
        }
    }
}
