package com.example.timetable_solver_demo.entities;

import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.*;

@Entity
@Table(name = "subject")
@Getter
@Setter
public class Subject {
    @Id
    @Column(name = "subject_id")
    private Long id;

    @Column(name = "name")
    private String name;

    @Column(name = "code")
    private Integer intCode;

    @JoinColumn(name = "category", referencedColumnName = "id")
    @ManyToOne
    private SubjectCategory category;

}
