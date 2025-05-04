package com.example.timetable_solver_demo.entities;

import jakarta.persistence.Table;

import com.example.timetable_solver_demo.enums.TimeOfDay;
import com.example.timetable_solver_demo.enums.TimeslotActivity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "schedule_timeslot")
public class ScheduleTimeslot {
    @Id 
    @GeneratedValue
    @EqualsAndHashCode.Exclude
    private Long id;

    private TimeslotActivity timeslotActivity;

    @JoinColumn(name = "schedule", referencedColumnName = "id")
    @ManyToOne(optional = false)
    private Schedule schedule;

    @Enumerated(EnumType.STRING)
    private TimeOfDay timeOfDay;

}
