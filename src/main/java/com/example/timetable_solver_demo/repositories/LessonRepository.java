package com.example.timetable_solver_demo.repositories;

import java.util.List;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.springframework.stereotype.Repository;

import java.util.ArrayList;

import com.example.timetable_solver_demo.entities.Lesson;
import com.example.timetable_solver_demo.entities.SchoolTimetable;

@Repository
public class LessonRepository {
    private final EntityManager entityManager;

    public LessonRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public List<Lesson> fetchAllLessonsByTimetable(SchoolTimetable timetable) {
        List<Lesson> lessons;
        Query query = null;

        if (timetable != null) {
            query = entityManager.createQuery(
                "SELECT l FROM Lesson l WHERE l.lessonInfo.timetable = :timetable"
            );
            query.setParameter("timetable", timetable);
        }

        lessons = query != null ? query.getResultList() : new ArrayList<>();
        return lessons;
    }
}
