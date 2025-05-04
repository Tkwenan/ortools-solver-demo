package com.example.timetable_solver_demo.entities;

import lombok.Getter;
import lombok.Setter;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Entity;
import java.time.DayOfWeek;


@Getter
@Setter
@Entity
@Table(name = "lesson")
public class Lesson {
    @Id 
    @GeneratedValue
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private LessonInfo lessonInfo;

    @ManyToOne(fetch = FetchType.LAZY)
    private ScheduleTimeslot timeslot;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = true)
    private DayOfWeek dayOfWeek;
}
