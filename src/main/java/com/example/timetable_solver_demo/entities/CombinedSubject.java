package com.example.timetable_solver_demo.entities;

import jakarta.persistence.*;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "combined_subject")
public class CombinedSubject {
    @GeneratedValue
    @Id
    @Column(name = "combined_subject_id")
    private Long id;

    private String subjectName;
    private Integer subjectCode;
}
