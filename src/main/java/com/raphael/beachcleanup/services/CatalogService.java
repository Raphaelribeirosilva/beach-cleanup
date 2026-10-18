package com.raphael.beachcleanup.services;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import com.raphael.beachcleanup.dto.*;
import com.raphael.beachcleanup.entities.*;
import com.raphael.beachcleanup.exceptions.*;
import com.raphael.beachcleanup.repositories.*;

@Service
@Transactional
public class CatalogService {
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;
    private final CatalogEntryRepository entries;
    private final CatalogAliasRepository aliases;
    private final JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired private AuditService audit;

    public CatalogService(CatalogEntryRepository entries, CatalogAliasRepository aliases, JdbcTemplate jdbc) {
        this.entries = entries;
        this.aliases = aliases;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<CatalogDTO> list(String kind) {
        requireKind(kind);
        Map<Long, List<String>> names = new HashMap<>();
        for (var alias : aliases.findByIdKind(kind)) {
            names.computeIfAbsent(alias.getEntry().getId(), id -> new ArrayList<>()).add(alias.getId().name());
        }
        return entries.findByKindAndMergedIntoIsNullOrderByNameAscIdAsc(kind).stream()
            .map(entry -> dto(entry, names.getOrDefault(entry.getId(), List.of()))).toList();
    }

    public CatalogDTO create(String kind, CatalogInputDTO input) {
        requireKind(kind);
        String name = requireName(input.name(), "name");
        var keys = keys(input, name);
        requireAvailable(kind, keys, null);
        var entry = entries.saveAndFlush(new CatalogEntry(kind, name));
        for (String key : keys) entityManager.persist(new CatalogAlias(entry, key));
        aliases.flush(); // Uniqueness also protects concurrent creates.
        return dto(entry, keys);
    }

    public CatalogDTO update(String kind, Long id, CatalogInputDTO input) {
        var entry = locked(kind, id);
        FormVersion.requireCurrent(input.version(), entry.getVersion());
        String name = requireName(input.name(), "name");
        var keys = keys(input, name);
        // Keep old names valid for existing clients even after a canonical rename.
        aliases.findByEntryId(id).forEach(alias -> keys.add(alias.getId().name()));
        requireAvailable(kind, keys, id);
        entry.setName(name);
        for (String key : keys) {
            if (!aliases.existsById(new CatalogAlias.Key(kind, key))) entityManager.persist(new CatalogAlias(entry, key));
        }
        propagate(kind, id, entry);
        entries.flush();
        return dto(entry, keys);
    }

    public CatalogDTO merge(String kind, Long sourceId, CatalogMergeDTO input) {
        if (sourceId.equals(input.targetId())) {
            throw new FieldValidationException("targetId", "Escolha outro registro como destino.");
        }
        // Use the same order for both directions of an attempted merge.
        CatalogEntry first = locked(kind, Math.min(sourceId, input.targetId()));
        CatalogEntry second = locked(kind, Math.max(sourceId, input.targetId()));
        CatalogEntry source = sourceId.equals(first.getId()) ? first : second;
        CatalogEntry target = sourceId.equals(first.getId()) ? second : first;
        FormVersion.requireCurrent(input.version(), source.getVersion());
        FormVersion.requireCurrent(input.targetVersion(), target.getVersion());
        for (var alias : aliases.findByEntryId(sourceId)) alias.setEntry(target);
        propagate(kind, sourceId, target);
        source.mergeInto(target);
        audit.record(kind.equals("beaches") ? "BEACH" : "MATERIAL", sourceId, "MERGE",
            null, null, null, null, target.getId(), null, null);
        target.touch();
        entries.flush();
        return dto(target, aliases.findByEntryId(target.getId()).stream().map(a -> a.getId().name()).toList());
    }

    /** Called inside the writer's transaction, before it acquires any event lock. */
    public CatalogEntry resolve(String kind, CatalogEntry reference, String legacyName, String field) {
        Long id = reference == null ? null : reference.getId();
        if (id == null) {
            String key = CatalogNames.key(legacyName);
            if (key.isEmpty()) throw new FieldValidationException(field, "Selecione um item do catálogo.");
            id = aliases.findById(new CatalogAlias.Key(kind, key)).map(alias -> alias.getEntry().getId())
                .orElseThrow(() -> new FieldValidationException(field,
                    "Nome não cadastrado. Selecione um item do catálogo ou solicite o cadastro ao administrador."));
        }
        var entry = locked(kind, id);
        if (legacyName != null && !legacyName.isBlank()) {
            var alias = aliases.findById(new CatalogAlias.Key(kind, CatalogNames.key(legacyName)));
            if (alias.isEmpty() || !alias.get().getEntry().getId().equals(entry.getId())) {
                throw new FieldValidationException(field, "O nome informado não corresponde ao ID selecionado.");
            }
        }
        return entry;
    }

    private CatalogEntry locked(String kind, Long id) {
        requireKind(kind);
        if (id == null || id <= 0) throw new FieldValidationException("id", "Informe um ID positivo.");
        var entry = entries.lockById(id).orElseThrow(() -> new ResourceNotFoundException(id));
        // An ownership check or a related event may already have loaded this entry into OSIV.
        // Acquiring a lock alone does not refresh that cached state after a completed merge.
        entityManager.refresh(entry, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (!entry.getKind().equals(kind)) throw new FieldValidationException("id", "O item pertence a outro catálogo.");
        if (entry.getMergedInto() != null) {
            throw new CatalogConflictException("Este item foi unificado. Recarregue o catálogo e revise o formulário.");
        }
        return entry;
    }

    private void propagate(String kind, Long previousId, CatalogEntry target) {
        // Increment row versions so an open form cannot undo the reassignment or canonical rename.
        if (kind.equals("beaches")) {
            jdbc.update("update tb_cleanup_event set beach_id=?,location=?,row_version=row_version+1 where beach_id=?",
                target.getId(), target.getName(), previousId);
            jdbc.update("update tb_trash_collection set beach_id=?,beach=?,row_version=row_version+1 where beach_id=?",
                target.getId(), target.getName(), previousId);
        } else {
            jdbc.update("update tb_trash_collection set material_id=?,material_type=?,row_version=row_version+1 where material_id=?",
                target.getId(), target.getName(), previousId);
        }
    }

    private LinkedHashSet<String> keys(CatalogInputDTO input, String name) {
        var keys = new LinkedHashSet<String>();
        keys.add(CatalogNames.key(name));
        if (input.aliases() != null) input.aliases().forEach(alias -> keys.add(CatalogNames.key(requireName(alias, "aliases"))));
        return keys;
    }

    private void requireAvailable(String kind, Set<String> keys, Long owner) {
        for (String key : keys) aliases.findById(new CatalogAlias.Key(kind, key)).ifPresent(alias -> {
            if (!Objects.equals(owner, alias.getEntry().getId())) {
                throw new CatalogConflictException("O nome ou uma variação já pertence a outro item. Use esse item ou unifique os cadastros.");
            }
        });
    }

    private String requireName(String value, String field) {
        String name = CatalogNames.display(value);
        if (CatalogNames.key(name).isBlank() || name.length() > 255) throw new FieldValidationException(field, "Informe um nome de 1 a 255 caracteres.");
        return name;
    }

    private void requireKind(String kind) {
        if (!Set.of("beaches", "materials").contains(kind)) {
            throw new FieldValidationException("kind", "Catálogo desconhecido. Use beaches ou materials.");
        }
    }

    private CatalogDTO dto(CatalogEntry entry, Collection<String> keys) {
        return new CatalogDTO(entry.getId(), entry.getName(), entry.getVersion(), keys.stream().sorted().toList());
    }
}
