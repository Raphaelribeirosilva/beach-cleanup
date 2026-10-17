package com.raphael.beachcleanup.services;

import java.util.Collection;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.raphael.beachcleanup.dto.TrashCollectionDTO;
import com.raphael.beachcleanup.dto.UserDTO;
import com.raphael.beachcleanup.entities.TrashCollection;
import com.raphael.beachcleanup.entities.User;
import com.raphael.beachcleanup.repositories.TrashCollectionRepository;

/** Builds API responses using collected mass derived from the current collection records. */
@Service
@Transactional(readOnly = true)
public class CollectedTotalsService {
    private final TrashCollectionRepository collections;

    public CollectedTotalsService(TrashCollectionRepository collections) {
        this.collections = collections;
    }

    public UserDTO user(User user) {
        return users(List.of(user)).getFirst();
    }

    public List<UserDTO> users(List<User> users) {
        var totals = totals(users.stream().map(User::getId).toList());
        return users.stream().map(user -> userDto(user, totals)).toList();
    }

    public TrashCollectionDTO collection(TrashCollection collection) {
        return collections(List.of(collection)).getFirst();
    }

    public List<TrashCollectionDTO> collections(List<TrashCollection> records) {
        var totals = totals(records.stream().map(TrashCollection::getVolunteer)
            .filter(Objects::nonNull).map(User::getId).toList());
        return records.stream().map(record -> new TrashCollectionDTO(record,
            record.getVolunteer() == null ? null : userDto(record.getVolunteer(), totals))).toList();
    }

    private Map<Long, BigDecimal> totals(Collection<Long> userIds) {
        var ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return collections.sumWeightByUserIds(ids).stream().collect(Collectors.toMap(
            TrashCollectionRepository.CollectedTotal::getUserId,
            total -> total.getTotalKg() == null ? BigDecimal.ZERO.setScale(3) : total.getTotalKg()));
    }

    private UserDTO userDto(User user, Map<Long, BigDecimal> totals) {
        return new UserDTO(user, totals.getOrDefault(user.getId(), BigDecimal.ZERO.setScale(3)));
    }
}
