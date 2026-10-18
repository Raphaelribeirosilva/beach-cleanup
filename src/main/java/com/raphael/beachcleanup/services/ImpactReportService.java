package com.raphael.beachcleanup.services;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.raphael.beachcleanup.exceptions.FieldValidationException;

@Service
@Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class ImpactReportService {
    public record Metrics(BigDecimal weightKg, long collections, long collectors) {}
    public record Row(String key, String label, BigDecimal weightKg, long collections, long collectors) {}
    public record Report(Metrics totals, List<Row> rows) {}
    public record Participation(Long eventId, String title, long registeredParticipants) {}
    private final EntityManager em;
    public ImpactReportService(EntityManager em) { this.em = em; }

    public Report impact(LocalDate from, LocalDate to, Long beachId, Long materialId, Long eventId, String groupBy) {
        validate(from, to, beachId, materialId, eventId);
        String key;
        String label;
        switch (groupBy) {
            case "beach" -> { key = "b.id"; label = "b.name"; }
            case "material" -> { key = "m.id"; label = "m.name"; }
            case "event" -> { key = "e.id"; label = "e.title"; }
            case "period" -> { key = "c.registrationDate"; label = key; }
            default -> throw new FieldValidationException("groupBy", "Use beach, material, event ou period");
        }
        var params = new LinkedHashMap<String, Object>();
        String where = filters("c.registrationDate", from, to, beachId, materialId, eventId, params);
        String source = " from TrashCollection c left join c.beachReference b left join c.materialReference m left join c.event e" + where;
        String metrics = "coalesce(sum(c.weightKg), 0), count(c.id), count(distinct c.volunteer.id)";
        Object[] total = query("select " + metrics + source, params).getSingleResult();
        var rows = query("select " + key + ", " + label + ", " + metrics + source
            + " group by " + key + (key.equals(label) ? "" : ", " + label) + " order by " + key, params)
            .setMaxResults(501).getResultList().stream().map(row -> new Row(row[0] == null ? null : row[0].toString(),
                row[1] == null ? (groupBy.equals("event") ? "Sem evento" : "Sem catálogo/data") : row[1].toString(),
                decimal(row[2]), ((Number) row[3]).longValue(), ((Number) row[4]).longValue())).toList();
        if (rows.size() > 500) throw new FieldValidationException("filters", "Mais de 500 grupos. Reduza o período ou selecione praia, material ou evento.");
        return new Report(new Metrics(decimal(total[0]), ((Number) total[1]).longValue(), ((Number) total[2]).longValue()), rows);
    }

    // Registrations describe events, never a material or the people who submitted collections.
    public List<Participation> participation(LocalDate from, LocalDate to, Long beachId, Long eventId) {
        validate(from, to, beachId, null, eventId);
        var params = new LinkedHashMap<String, Object>();
        String where = filters("cast(e.dateTime as LocalDate)", from, to, beachId, null, eventId, params);
        var rows = query("select e.id, e.title, count(distinct u.id) from CleanupEvent e left join e.beachReference b"
            + " left join e.users u" + where + " group by e.id, e.title order by e.id", params).setMaxResults(501).getResultList()
            .stream().map(row -> new Participation((Long) row[0], (String) row[1], ((Number) row[2]).longValue())).toList();
        if (rows.size() > 500) throw new FieldValidationException("filters", "Mais de 500 eventos. Reduza o período ou selecione praia ou evento.");
        return rows;
    }

    private jakarta.persistence.TypedQuery<Object[]> query(String jpql, Map<String, Object> params) {
        var query = em.createQuery(jpql, Object[].class);
        params.forEach(query::setParameter);
        return query;
    }
    private static BigDecimal decimal(Object value) { return new BigDecimal(value.toString()); }
    private static void validate(LocalDate from, LocalDate to, Long... ids) {
        if (from != null && to != null && from.isAfter(to))
            throw new FieldValidationException("from", "A data inicial deve ser anterior ou igual à final");
        for (Long id : ids) if (id != null && id <= 0)
            throw new FieldValidationException("id", "Informe IDs positivos");
    }
    private static String filters(String date, LocalDate from, LocalDate to, Long beachId, Long materialId,
            Long eventId, Map<String, Object> params) {
        var clauses = new ArrayList<String>();
        if (from != null) { clauses.add(date + " >= :from"); params.put("from", from); }
        if (to != null) { clauses.add(date + " <= :to"); params.put("to", to); }
        if (beachId != null) { clauses.add("b.id = :beachId"); params.put("beachId", beachId); }
        if (materialId != null) { clauses.add("m.id = :materialId"); params.put("materialId", materialId); }
        if (eventId != null) { clauses.add("e.id = :eventId"); params.put("eventId", eventId); }
        return clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
    }
}
