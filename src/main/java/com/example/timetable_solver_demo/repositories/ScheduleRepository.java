package com.example.timetable_solver_demo.repositories;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;

import com.example.timetable_solver_demo.entities.Schedule;
import java.util.Optional;

@Repository
public class ScheduleRepository {
    private final EntityManager entityManager;

    public ScheduleRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Optional<Schedule> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entityManager.find(Schedule.class, id));
    }
}
