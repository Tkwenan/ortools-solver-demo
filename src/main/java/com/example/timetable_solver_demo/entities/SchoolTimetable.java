package com.example.timetable_solver_demo.entities;

import com.example.timetable_solver_demo.enums.TimetableStatus;

import jakarta.persistence.Table;
import jakarta.persistence.Id;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "timetable")
public class SchoolTimetable {
    @Id
    @GeneratedValue
    private Long Id;

    @Enumerated(EnumType.STRING) 
    private TimetableStatus timetableStatus;

}
