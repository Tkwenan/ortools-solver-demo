package com.example.timetable_solver_demo.repositories;

import com.example.timetable_solver_demo.entities.SchoolTimetable;
import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class SchoolTimetableRepository {

  private final EntityManager entityManager;

  public SchoolTimetableRepository(EntityManager entityManager) {
      this.entityManager = entityManager;
  }

  public Optional<SchoolTimetable> findById(Long id) {
      if (id == null) {
          return Optional.empty();
      }
      return Optional.ofNullable(entityManager.find(SchoolTimetable.class, id));
  }
   
}
