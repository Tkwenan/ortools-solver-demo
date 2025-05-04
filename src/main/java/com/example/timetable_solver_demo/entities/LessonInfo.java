package com.example.timetable_solver_demo.entities;

import java.util.HashSet;
import java.util.Set;


import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

import com.example.timetable_solver_demo.enums.LessonType;

@Getter
@Setter
@Entity
@Table(name = "lesson_info")
public class LessonInfo {
    @Id
    @GeneratedValue
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "lesson_type")
    private LessonType lessonType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stream", referencedColumnName = "id", nullable = false)
    private Stream stream;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subject", referencedColumnName = "subject_id", nullable = true)
    private Subject subject;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "combined_subject", referencedColumnName = "combined_subject_id", nullable = true)
    private CombinedSubject combinedSubject;

    @ManyToMany(cascade = {CascadeType.MERGE},fetch = FetchType.EAGER)
    @JoinTable(name = "stream_lesson_teachers", joinColumns = @JoinColumn(name = "lesson_info"), inverseJoinColumns = @JoinColumn(name = "teacher"))
    private Set<Teacher> teachers = new HashSet<>();

    @ManyToOne(cascade = {CascadeType.REMOVE}, fetch = FetchType.LAZY)
    @JoinColumn(name = "timetable_id", nullable = false)
    private SchoolTimetable timetable;

}
